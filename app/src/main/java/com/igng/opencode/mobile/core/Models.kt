package com.igng.opencode.mobile.core

import org.json.JSONArray
import org.json.JSONObject

data class ServerProfile(
  val id: String,
  val name: String,
  val url: String,
  val username: String = "opencode",
  val autoConnect: Boolean = true,
  val notifications: Boolean = true,
  val companionUrl: String = ""
)

data class Project(val id: String, val directory: String, val name: String)
data class Session(val id: String, val directory: String, val title: String, val updated: Long, val parentId: String? = null)
data class Message(val id: String, val role: String, val created: Long, val parts: List<MessagePart>, val error: String? = null)
data class MessagePart(
  val id: String,
  val type: String,
  val text: String = "",
  val tool: String = "",
  val title: String = "",
  val status: String = "",
  val input: String = "",
  val output: String = "",
  val path: String = ""
)
data class PermissionRequest(val id: String, val sessionId: String, val directory: String, val action: String, val detail: String)
data class QuestionOption(val label: String, val description: String)
data class QuestionPrompt(val title: String, val options: List<QuestionOption>, val multiple: Boolean)
data class QuestionRequest(val id: String, val sessionId: String, val directory: String, val questions: List<QuestionPrompt>)
data class TodoItem(val content: String, val status: String, val priority: String)
data class FileChange(val path: String, val before: String, val after: String, val additions: Int, val deletions: Int)
data class FileNode(val path: String, val type: String)
data class ModelChoice(val providerId: String, val modelId: String, val label: String)
data class AgentChoice(val name: String, val description: String)
data class CommandChoice(val name: String, val description: String)

enum class TaskPhase { IDLE, THINKING, TOOL, SUBAGENT, TESTING, WAITING_PERMISSION, WAITING_QUESTION, COMPLETED, FAILED, ABORTED, DISCONNECTED }
data class TaskState(val sessionId: String, val phase: TaskPhase, val detail: String = "", val since: Long = System.currentTimeMillis()) {
  val active: Boolean get() = phase in setOf(TaskPhase.THINKING, TaskPhase.TOOL, TaskPhase.SUBAGENT, TaskPhase.TESTING, TaskPhase.WAITING_PERMISSION, TaskPhase.WAITING_QUESTION)
}

internal fun JSONObject.str(key: String): String = optString(key).takeUnless { it == "null" } ?: ""
internal fun JSONObject.obj(key: String): JSONObject = optJSONObject(key) ?: JSONObject()
internal fun JSONObject.arr(key: String): JSONArray = optJSONArray(key) ?: JSONArray()
internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.longPath(parent: String, child: String): Long = obj(parent).optLong(child, 0)

internal fun JSONObject.toProject(): Project {
  val directory = str("worktree").ifBlank { str("directory") }
  return Project(str("id"), directory, str("name").ifBlank { directory.substringAfterLast('/') })
}
internal fun JSONObject.toSession(): Session = Session(
  str("id"), str("directory"), str("title").ifBlank { "未命名会话" }, longPath("time", "updated"),
  str("parentID").ifBlank { null }
)
internal fun JSONObject.toMessage(): Message {
  val info = obj("info")
  val parts = arr("parts").objects().map { part ->
    val state = part.obj("state")
    MessagePart(
      id = part.str("id"), type = part.str("type"), text = part.str("text"), tool = part.str("tool"),
      title = state.str("title"), status = state.str("status"), input = state.optJSONObject("input")?.toString(2) ?: "",
      output = state.str("output"), path = part.str("filename").ifBlank { part.str("path") }
    )
  }
  return Message(info.str("id"), info.str("role"), info.longPath("time", "created"), parts,
    info.optJSONObject("error")?.optString("message"))
}
internal fun JSONObject.toPermission(directory: String): PermissionRequest {
  val detail = when {
    arr("patterns").length() > 0 -> arr("patterns").toString()
    arr("resources").length() > 0 -> arr("resources").toString()
    else -> obj("metadata").toString()
  }
  return PermissionRequest(str("id").ifBlank { str("requestID") }, str("sessionID"), directory,
    str("permission").ifBlank { str("action") }, detail)
}
internal fun JSONObject.toQuestion(directory: String): QuestionRequest = QuestionRequest(
  str("id").ifBlank { str("requestID") }, str("sessionID"), directory,
  arr("questions").objects().map { q -> QuestionPrompt(
    q.str("question"), q.arr("options").objects().map { QuestionOption(it.str("label"), it.str("description")) }, q.optBoolean("multiple")
  ) }
)
internal fun JSONObject.toTodo(): TodoItem = TodoItem(str("content"), str("status"), str("priority"))
internal fun JSONObject.toChange(): FileChange = FileChange(str("file"), str("before"), str("after"), optInt("additions"), optInt("deletions"))
internal fun JSONObject.toNode(): FileNode = FileNode(str("path"), str("type"))

object TaskReducer {
  fun status(sessionId: String, status: String, previous: TaskState? = null): TaskState = when (status) {
    "busy", "running" -> {
      val continuing = previous?.active == true && previous.phase !in setOf(TaskPhase.WAITING_PERMISSION, TaskPhase.WAITING_QUESTION)
      TaskState(sessionId, if (continuing) previous!!.phase else TaskPhase.THINKING,
        if (continuing) previous!!.detail else "正在处理",
        if (continuing) previous!!.since else System.currentTimeMillis())
    }
    "retry" -> TaskState(sessionId, TaskPhase.THINKING, "正在重试",
      if (previous?.active == true) previous.since else System.currentTimeMillis())
    "idle" -> when {
      previous?.active == true -> TaskState(sessionId, TaskPhase.COMPLETED, "任务已完成", previous.since)
      previous?.phase in setOf(TaskPhase.COMPLETED, TaskPhase.FAILED, TaskPhase.ABORTED) -> previous!!
      else -> TaskState(sessionId, TaskPhase.IDLE)
    }
    else -> previous ?: TaskState(sessionId, TaskPhase.IDLE)
  }
  fun event(sessionId: String, type: String, properties: JSONObject, previous: TaskState?): TaskState? {
    val since = previous?.since ?: System.currentTimeMillis()
    return when (type) {
      "session.status" -> status(sessionId, properties.obj("status").str("type"), previous)
      "session.idle" -> status(sessionId, "idle", previous)
      "session.error" -> TaskState(sessionId, TaskPhase.FAILED, properties.obj("error").str("message").ifBlank { "执行失败" }, since)
      "session.aborted" -> TaskState(sessionId, TaskPhase.ABORTED, "任务已停止", since)
      "permission.asked" -> TaskState(sessionId, TaskPhase.WAITING_PERMISSION, "等待权限确认", since)
      "question.asked" -> TaskState(sessionId, TaskPhase.WAITING_QUESTION, "等待你的回答", since)
      "message.part.updated" -> {
        val part = properties.obj("part")
        when {
          part.str("type") == "reasoning" -> TaskState(sessionId, TaskPhase.THINKING, "正在思考", since)
          part.str("type") == "tool" -> {
            val tool = part.str("tool")
            val phase = when {
              tool in listOf("task", "subagent") -> TaskPhase.SUBAGENT
              tool in listOf("bash", "shell") && Regex("(?i)(test|gradle|pytest|vitest|jest)").containsMatchIn(part.obj("state").obj("input").toString()) -> TaskPhase.TESTING
              else -> TaskPhase.TOOL
            }
            TaskState(sessionId, phase, part.obj("state").str("title").ifBlank { "正在运行 $tool" }, since)
          }
          else -> previous
        }
      }
      else -> previous
    }
  }
}
