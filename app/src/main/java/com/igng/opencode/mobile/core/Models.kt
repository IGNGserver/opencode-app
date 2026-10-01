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
  val allowCleartext: Boolean = false
)

/**
 * 统一项目模型（UI 视图）。
 *
 * [directory] 是项目路径标识，两套协议语义不同但都归一到此字段：
 * - V1 `Project.Info.worktree`
 * - V2 `Project.canonical`
 * 由 `V1Contract` / `V2Contract` 负责从各自契约填充，此处不做任何协议猜测。
 */
data class Project(val id: String, val directory: String, val name: String, val timeUpdated: Long = 0)

/**
 * 统一会话模型（UI 视图）。
 *
 * 归属判定统一用 [projectId]（V1 `SessionInfo.projectID` / V2 `Session.Info.projectID` 都存在），
 * 严禁用 [directory] 字符串反推归属。[titleIsDefault] 表示标题仍是协议默认值
 * （V2 `title` 可缺省，默认形如 `New session - <ISO>`），供 UI 显示"生成中"。
 */
data class Session(
  val id: String,
  val directory: String,
  val title: String,
  val updated: Long,
  val parentId: String? = null,
  val projectId: String = "",
  val titleIsDefault: Boolean = false
)

data class Message(val id: String, val role: String, val created: Long, val parts: List<MessagePart>, val error: String? = null)

/**
 * 统一消息片段模型。两套协议的消息结构不同（V1 是 `{info, parts}` + 12 种 Part；
 * V2 是 11 种 tagged union 消息 + `assistant.content[]`），都归一到本模型，
 * 保证 UI 渲染层无需感知协议差异。未知类型降级为 [type] 原值 + 原始文本，绝不静默丢弃。
 */
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
  val active: Boolean get() = phase in setOf(TaskPhase.THINKING, TaskPhase.TOOL, TaskPhase.SUBAGENT, TaskPhase.TESTING, TaskPhase.WAITING_PERMISSION, TaskPhase.WAITING_QUESTION)
}

/* 严格 JSON 原语：只做类型安全读取，不做字段名兜底。缺字段一律返回空，交由上层决定降级策略。 */
internal fun JSONObject.str(key: String): String = optString(key).takeUnless { it == "null" } ?: ""
internal fun JSONObject.obj(key: String): JSONObject = optJSONObject(key) ?: JSONObject()
internal fun JSONObject.arr(key: String): JSONArray = optJSONArray(key) ?: JSONArray()
internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }
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

/** 把任意 JSON 值格式化为可读文本（工具入参/出参展示用）。null → 空串。 */
internal fun JSONObject.valueText(key: String): String {
  val value = opt(key) ?: return ""
  return when (value) {
    is JSONObject -> value.toString(2)
    is JSONArray -> value.toString(2)
    JSONObject.NULL -> ""
    else -> value.toString()
  }
}

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
              tool in listOf("task", "subagent") -> TaskPhase.SUBAGENT
              tool in listOf("bash", "shell") && Regex("(?i)(test|gradle|pytest|vitest|jest)").containsMatchIn(part.obj("state").obj("input").toString()) -> TaskPhase.TESTING
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
