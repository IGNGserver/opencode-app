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
  val companionUrl: String = "",
  val allowCleartext: Boolean = false,
  /** Shared HMAC key used to authenticate companion push messages; not a login credential. */
  val pluginSecret: String = ""
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
  val path: String = "",
  val error: String = "",
  val patch: String = "",
  val files: List<String> = emptyList(),
  val attachments: List<String> = emptyList()
)
data class PermissionRequest(
  val id: String,
  val sessionId: String,
  val directory: String,
  val action: String,
  val detail: String,
  val always: List<String> = emptyList(),
  val toolMessageId: String = "",
  val toolCallId: String = ""
)
data class QuestionOption(val label: String, val description: String)
data class QuestionPrompt(
  val title: String,
  val options: List<QuestionOption>,
  val multiple: Boolean,
  val header: String = "",
  val custom: Boolean = false
)
data class QuestionRequest(val id: String, val sessionId: String, val directory: String, val questions: List<QuestionPrompt>)
data class TodoItem(val content: String, val status: String, val priority: String)
data class FileChange(
  val path: String,
  val before: String,
  val after: String,
  val additions: Int,
  val deletions: Int,
  val patch: String = "",
  val status: String = "modified"
)
data class FileNode(val path: String, val type: String, val name: String = "", val absolute: String = "", val ignored: Boolean = false)
data class FileContent(val type: String, val content: String, val encoding: String = "", val mimeType: String = "")
data class ModelChoice(val providerId: String, val modelId: String, val label: String)
data class AgentChoice(val name: String, val description: String)
data class CommandChoice(val name: String, val description: String)

enum class TaskPhase { IDLE, THINKING, TOOL, SUBAGENT, TESTING, WAITING_PERMISSION, WAITING_QUESTION, COMPLETED, FAILED, ABORTED, DISCONNECTED }
data class TaskState(val sessionId: String, val phase: TaskPhase, val detail: String = "", val since: Long = System.currentTimeMillis()) {
  val active: Boolean get() = when (phase) {
    TaskPhase.THINKING, TaskPhase.TOOL, TaskPhase.SUBAGENT, TaskPhase.TESTING, TaskPhase.WAITING_PERMISSION, TaskPhase.WAITING_QUESTION -> true
    else -> false
  }

  companion object {
    /** Phases where the agent is actively working, without an outstanding user prompt. */
    val RUNNING_PHASES: Set<TaskPhase> = setOf(TaskPhase.THINKING, TaskPhase.TOOL, TaskPhase.SUBAGENT, TaskPhase.TESTING)
    /** Phases waiting on a user decision. */
    val WAITING_PHASES: Set<TaskPhase> = setOf(TaskPhase.WAITING_PERMISSION, TaskPhase.WAITING_QUESTION)
    /** Union of [RUNNING_PHASES] and [WAITING_PHASES], matching [active]. */
    val ACTIVE_PHASES: Set<TaskPhase> = RUNNING_PHASES + WAITING_PHASES
  }
}

internal fun JSONObject.str(key: String): String = optString(key).takeUnless { it == "null" } ?: ""
internal fun JSONObject.obj(key: String): JSONObject = optJSONObject(key) ?: JSONObject()
internal fun JSONObject.arr(key: String): JSONArray = optJSONArray(key) ?: JSONArray()
internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.longPath(parent: String, child: String): Long = obj(parent).optLong(child, 0)
internal fun JSONObject.errorMessage(key: String = "error"): String {
  val direct = str("message")
  if (direct.isNotBlank()) return direct
  val value = opt(key)
  if (value is JSONObject) {
    return value.str("message").ifBlank { value.obj("data").str("message") }
  }
  return value?.toString().orEmpty().takeUnless { it == "null" }.orEmpty()
}

private fun JSONObject.valueText(key: String): String {
  val value = opt(key) ?: return ""
  return when (value) {
    is JSONObject -> value.toString(2)
    is JSONArray -> value.toString(2)
    JSONObject.NULL -> ""
    else -> value.toString()
  }
}

internal fun JSONObject.toProject(): Project {
  val directory = str("worktree").ifBlank { str("directory") }
  return Project(str("id"), directory, str("name").ifBlank { directory.substringAfterLast('/') })
}
internal fun JSONObject.toSession(): Session = Session(
  str("id"), str("directory").ifBlank { obj("location").str("directory") },
  str("title").ifBlank { "未命名会话" }, longPath("time", "updated"), str("parentID").ifBlank { null }
)
internal fun JSONObject.toMessage(): Message {
  val info = obj("info")
  if (info.length() == 0 && str("type").isNotBlank()) return toV2Message()
  val parts = arr("parts").objects().map { part -> part.toMessagePart() }
  return Message(info.str("id"), info.str("role"), info.longPath("time", "created"), parts,
    info.errorMessage().ifBlank { null })
}

/** Projects one legacy/V1 `part` object (as delivered in `message.part.updated` and `parts[]`). */
internal fun JSONObject.toMessagePart(): MessagePart {
  val state = obj("state")
  val type = str("type")
  val files = (0 until arr("files").length()).mapNotNull { index -> arr("files").optString(index).takeIf(String::isNotBlank) }
  val attachments = state.arr("attachments").objects().map { it.str("filename").ifBlank { it.str("url") } }
  return MessagePart(
    id = str("id"), type = type,
    text = str("text").ifBlank { str("description").ifBlank { str("prompt") } }, tool = str("tool"),
    title = state.str("title"), status = state.str("status"), input = state.valueText("input"),
    output = state.valueText("output").ifBlank { state.valueText("result") }, path = str("filename").ifBlank { str("path").ifBlank { str("url") } },
    error = state.errorMessage().ifBlank { errorMessage() }, patch = str("patch"), files = files, attachments = attachments
  )
}

private fun JSONObject.toV2Message(): Message {
  val type = str("type")
  val role = when (type) {
    "user" -> "user"
    "assistant" -> "assistant"
    else -> "system"
  }
  val parts = when (type) {
    "assistant" -> arr("content").objects().map { part -> part.toV2MessagePart() }
    "shell" -> listOf(MessagePart(str("id"), "tool", text = str("command"), tool = "shell", output = str("output")))
    else -> listOfNotNull(str("text").takeIf(String::isNotBlank)?.let { MessagePart(str("id"), type, text = it) })
  }
  return Message(
    id = str("id"), role = role, created = longPath("time", "created"), parts = parts,
    error = errorMessage().ifBlank { null }
  )
}

/** Projects one V2 `message.part.updated` part object. */
internal fun JSONObject.toV2MessagePart(): MessagePart {
  val state = obj("state")
  val attachments = state.arr("attachments").objects().map { it.str("name").ifBlank { it.str("url") } }
  val outputPaths = (0 until state.arr("outputPaths").length()).mapNotNull { index ->
    state.arr("outputPaths").optString(index).takeIf(String::isNotBlank)
  }
  return MessagePart(
    id = str("id"), type = str("type"), text = str("text"), tool = str("name"),
    status = state.str("status"), input = state.valueText("input"),
    output = state.valueText("result").ifBlank { state.valueText("content") },
    error = state.errorMessage().ifBlank { errorMessage() },
    files = outputPaths, attachments = attachments
  )
}
internal fun JSONObject.toPermission(directory: String): PermissionRequest {
  val detail = when {
    arr("patterns").length() > 0 -> arr("patterns").toString()
    arr("resources").length() > 0 -> arr("resources").toString()
    else -> obj("metadata").toString()
  }
  val tool = obj("tool")
  return PermissionRequest(
    str("id").ifBlank { str("requestID") }, str("sessionID"), directory,
    str("permission").ifBlank { str("action") }, detail,
    (0 until arr("always").length()).mapNotNull { index -> arr("always").optString(index).takeIf(String::isNotBlank) },
    tool.str("messageID"), tool.str("callID")
  )
}
internal fun JSONObject.toQuestion(directory: String): QuestionRequest = QuestionRequest(
  str("id").ifBlank { str("requestID") }, str("sessionID"), directory,
  arr("questions").objects().map { q -> QuestionPrompt(
    q.str("question"), q.arr("options").objects().map { QuestionOption(it.str("label"), it.str("description")) },
    q.optBoolean("multiple"), q.str("header"), q.optBoolean("custom")
  ) }
)
internal fun JSONObject.toTodo(): TodoItem = TodoItem(str("content"), str("status"), str("priority"))
internal fun JSONObject.toChange(): FileChange = FileChange(
  path = str("file"), before = str("before"), after = str("after"), additions = optInt("additions"), deletions = optInt("deletions"),
  patch = str("patch"), status = str("status").ifBlank { "modified" }
)
internal fun JSONObject.toNode(): FileNode = FileNode(str("path"), str("type"), str("name"), str("absolute"), optBoolean("ignored"))
internal fun JSONObject.toFileContent(): FileContent = FileContent(
  str("type").ifBlank { if (str("encoding") == "base64") "binary" else "text" },
  str("content"), str("encoding"), str("mimeType")
)

object TaskReducer {
  private val TEST_COMMAND = Regex("(?i)(test|gradle|pytest|vitest|jest)")
  private val SUBAGENT_TOOLS = setOf("task", "subagent")
  private val SHELL_TOOLS = setOf("bash", "shell")

  fun status(sessionId: String, status: String, previous: TaskState? = null): TaskState = when (status) {
    "busy", "running" -> {
      val continuing = previous?.active == true && previous.phase !in TaskState.WAITING_PHASES
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
      "session.error" -> TaskState(sessionId, TaskPhase.FAILED, properties.errorMessage().ifBlank { properties.obj("error").str("message").ifBlank { "执行失败" } }, since)
      "session.aborted" -> TaskState(sessionId, TaskPhase.ABORTED, "任务已停止", since)
      "permission.asked" -> TaskState(sessionId, TaskPhase.WAITING_PERMISSION, "等待权限确认", since)
      "question.asked" -> TaskState(sessionId, TaskPhase.WAITING_QUESTION, "等待你的回答", since)
      "permission.replied", "permission.rejected", "question.replied", "question.rejected" ->
        TaskState(sessionId, TaskPhase.THINKING, "继续执行", since)
      "message.part.updated" -> {
        val part = properties.obj("part")
        when {
          part.str("type") == "reasoning" -> TaskState(sessionId, TaskPhase.THINKING, "正在思考", since)
          part.str("type") == "tool" -> {
            val tool = part.str("tool")
            val phase = when {
              tool in SUBAGENT_TOOLS -> TaskPhase.SUBAGENT
              tool in SHELL_TOOLS && TEST_COMMAND.containsMatchIn(part.obj("state").obj("input").toString()) -> TaskPhase.TESTING
              else -> TaskPhase.TOOL
            }
            val state = part.obj("state")
            TaskState(sessionId, phase, state.str("title").ifBlank { state.str("error") }.ifBlank { "正在运行 $tool" }, since)
          }
          else -> previous
        }
      }
      else -> previous
    }
  }
}
