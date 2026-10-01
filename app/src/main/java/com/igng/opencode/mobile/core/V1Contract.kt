package com.igng.opencode.mobile.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * V1（legacy surface：`/project`、`/session`、`/global/` 前缀端点）契约解析层。
 *
 * 字段严格对照官方 schema，来源：
 * - `packages/schema/src/project.ts`      → `Project.Info`（路径字段是 `worktree`）
 * - `packages/schema/src/v1/session.ts`   → `SessionInfo` / `Message(User|Assistant)` / `WithParts` / `Part`(12) / `ToolState`(4)
 * - `packages/schema/src/v1/permission.ts`→ `PermissionV1.Request`
 * - `packages/schema/src/v1/question.ts`  → `QuestionV1.Request`
 *
 * 规则：不做字段名兜底；union 用判别字段穷举；未知变体降级保留原始 type + 原文，绝不静默丢弃。
 */
internal object V1Contract {

  /* ---- Project.Info ---- */
  fun project(json: JSONObject): Project = Project(
    id = json.str("id"),
    directory = json.str("worktree"),            // V1 路径标识字段
    name = json.str("name").ifBlank { json.str("worktree").substringAfterLast('/') },
    timeUpdated = json.longPath("time", "updated")
  )

  /* ---- SessionInfo ---- */
  fun session(json: JSONObject): Session = Session(
    id = json.str("id"),
    directory = json.str("directory"),           // V1 会话自带 directory
    title = json.str("title"),
    updated = json.longPath("time", "updated"),
    parentId = json.str("parentID").takeIf { it.isNotBlank() },
    projectId = json.str("projectID"),
    titleIsDefault = isDefaultTitle(json.str("title"))
  )

  /* ---- WithParts = { info: Message, parts: Part[] } ---- */
  fun message(json: JSONObject): Message {
    val info = json.obj("info")
    val parts = json.arr("parts").objects().map(::part)
    return Message(
      id = info.str("id"),
      role = info.str("role"),
      created = info.longPath("time", "created"),
      parts = parts,
      error = info.errorMessage().ifBlank { null }
    )
  }

  fun messages(array: JSONArray): List<Message> = array.objects().map(::message)

  /* ---- Part union（discriminator: type，共 12 种） ---- */
  fun part(json: JSONObject): MessagePart {
    val type = json.str("type")
    val base = MessagePart(id = json.str("id"), type = type)
    return when (type) {
      "text" -> base.copy(text = json.str("text"))
      "reasoning" -> base.copy(text = json.str("text"))
      "tool" -> {
        val state = json.obj("state")
        val status = state.str("status")
        base.copy(
          tool = json.str("tool"),
          status = status,
          title = state.str("title"),
          input = state.valueText("input"),
          // V1 ToolStateCompleted 才有 output；error 态是 error 字段
          output = state.str("output"),
          error = state.str("error")
        )
      }
      "file" -> base.copy(path = json.str("filename").ifBlank { json.str("url") }, text = json.str("url"))
      "patch" -> base.copy(patch = json.str("hash"), files = json.arr("files").strings())
      "step-start" -> base
      "step-finish" -> base.copy(output = "cost=${json.optDouble("cost", 0.0)}")
      "snapshot" -> base.copy(text = json.str("snapshot"))
      "agent" -> base.copy(title = json.str("name"))
      "subtask" -> base.copy(text = json.str("prompt"), title = json.str("description"), tool = json.str("agent"))
      "retry" -> base.copy(error = json.obj("error").errorMessage().ifBlank { json.obj("error").str("message") })
      "compaction" -> base.copy(text = "上下文已压缩")
      else -> base.copy(text = json.toString())   // 未知 Part：保留原文，禁止静默丢弃
    }
  }

  /* ---- PermissionV1.Request ---- */
  fun permission(json: JSONObject, directory: String): PermissionRequest = PermissionRequest(
    id = json.str("id"),
    sessionId = json.str("sessionID"),
    directory = directory,
    action = json.str("permission"),
    detail = json.arr("patterns").toString().ifBlank { json.obj("metadata").toString() },
    always = json.arr("always").strings(),
    toolMessageId = json.obj("tool").str("messageID"),
    toolCallId = json.obj("tool").str("callID")
  )

  /* ---- QuestionV1.Request ---- */
  fun question(json: JSONObject, directory: String): QuestionRequest = QuestionRequest(
    id = json.str("id"),
    sessionId = json.str("sessionID"),
    directory = directory,
    questions = json.arr("questions").objects().map { q ->
      QuestionPrompt(
        title = q.str("question"),
        options = q.arr("options").objects().map { QuestionOption(it.str("label"), it.str("description")) },
        multiple = q.optBoolean("multiple"),
        header = q.str("header"),
        custom = q.optBoolean("custom")
      )
    }
  )

  fun todo(json: JSONObject): TodoItem = TodoItem(json.str("content"), json.str("status"), json.str("priority"))

  fun change(json: JSONObject): FileChange = FileChange(
    path = json.str("file"), before = json.str("before"), after = json.str("after"),
    additions = json.optInt("additions"), deletions = json.optInt("deletions"),
    patch = json.str("patch"), status = json.str("status").ifBlank { "modified" }
  )

  fun node(json: JSONObject): FileNode = FileNode(
    json.str("path"), json.str("type"), json.str("name"), json.str("absolute"), json.optBoolean("ignored")
  )

  fun fileContent(json: JSONObject): FileContent = FileContent(
    type = json.str("type").ifBlank { if (json.str("encoding") == "base64") "binary" else "text" },
    content = json.str("content"), encoding = json.str("encoding"), mimeType = json.str("mimeType")
  )

  /**
   * V1 默认标题形如 `New session - <ISO时间>`（官方 `Session.isDefaultTitle`）。
   * 用于判断标题是否仍待 LLM 生成。
   */
  fun isDefaultTitle(title: String): Boolean =
    Regex("^(New session - |Child session - )\\d{4}-\\d{2}-\\d{2}T").containsMatchIn(title) ||
      title.isBlank()
}
