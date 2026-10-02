package com.igng.opencode.mobile.core

import com.igng.opencode.mobile.core.generated.Project as GProject
import com.igng.opencode.mobile.core.generated.Session_Info as GSessionInfo
import com.igng.opencode.mobile.core.generated.Permission_Request as GPermissionRequest
import org.json.JSONArray
import org.json.JSONObject

/**
 * V2（`/api/` 前缀 HttpApi）契约解析层。
 *
 * 字段严格对照 `https://opencode.ai/v2/openapi.json`（OpenAPI 3.1）：
 * - `Project`（路径字段是 `canonical`，**无** `worktree`/`directory`）
 * - `Session.Info`（`title` 可缺省；目录在 `location.directory`；归属 `projectID`）
 * - `Session.Message.Info`（11 种 tagged union，判别字段顶层 `type`）
 * - `Session.Message.Assistant.content[]`（Text | Reasoning | Tool）
 * - `Session.Message.ToolState.*`（Streaming/Running/Completed/Error，判别 `status`）
 *   → 工具输出在 `Completed.content: Tool.Content[]`（TextContent | FileContent）
 * - `Permission.Request`（`action`/`resources`/`save`，非 V1 的 `permission`/`patterns`）
 *
 * 规则同 V1：不兜底、union 穷举、未知变体保留原文不丢弃。
 */
internal object V2Contract {

  /* ---- Project ----（经生成类型 GProject 解析，字段名源自 spec） */
  fun project(json: JSONObject): Project {
    val p = GProject.fromJson(json)
    val canonical = p.canonical.orEmpty()
    return Project(
      id = p.id.orEmpty(),
      directory = canonical,                          // V2 路径标识字段 canonical
      name = p.name.orEmpty().ifBlank { canonical.substringAfterLast('/') },
      timeUpdated = (p.time?.updated ?: 0.0).toLong()
    )
  }

  /* ---- Session.Info ----（经生成类型 GSessionInfo 解析） */
  fun session(json: JSONObject): Session {
    val s = GSessionInfo.fromJson(json)
    val title = s.title.orEmpty()                     // V2 title 可缺省
    return Session(
      id = s.id.orEmpty(),
      directory = s.location?.directory.orEmpty(),    // 目录在 location.directory
      title = title,
      updated = (s.time?.updated ?: 0.0).toLong(),
      parentId = s.parentID,
      projectId = s.projectID.orEmpty(),              // 归属用 projectID
      titleIsDefault = V1Contract.isDefaultTitle(title)
    )
  }

  /* ---- Session.Message.Info（11 种 tagged union，判别 type） ---- */
  fun message(json: JSONObject): Message {
    val type = json.str("type")
    val created = json.longPath("time", "created")
    val id = json.str("id")
    return when (type) {
      "user" -> Message(
        id = id, role = "user", created = created,
        parts = listOf(MessagePart(id = "${id}_text", type = "text", text = json.str("text"))) +
          json.arr("files").objects().map {
            MessagePart(id = it.str("url"), type = "file", path = it.str("filename").ifBlank { it.str("url") }, text = it.str("url"))
          }
      )
      "assistant" -> Message(
        id = id, role = "assistant", created = created,
        parts = json.arr("content").objects().map(::assistantContent),
        error = json.obj("error").str("message").ifBlank { null }
      )
      "shell" -> Message(
        id = id, role = "system", created = created,
        parts = listOf(MessagePart(
          id = id, type = "tool", tool = "shell",
          title = json.str("command"), status = json.str("status"),
          output = json.obj("output").str("output")
        ))
      )
      "compaction" -> Message(
        id = id, role = "system", created = created,
        parts = listOf(MessagePart(id = id, type = "compaction",
          text = json.str("summary").ifBlank { "上下文已压缩" }))
      )
      // 以下类型内容较少，映射为 system 备注；字段缺失时保留原始 JSON，确保不丢内容
      "system", "synthetic", "skill", "agent-switched", "model-selected", "location-switched", "idle" -> Message(
        id = id, role = "system", created = created,
        parts = listOf(MessagePart(
          id = id, type = type,
          text = json.str("text").ifBlank { json.str("summary") }.ifBlank { json.str("name") }
            .ifBlank { json.toString() }
        ))
      )
      // 未知消息类型：保留原文，禁止静默丢弃（对应"内容显示不全"的唯一复发通道）
      else -> Message(
        id = id, role = "system", created = created,
        parts = listOf(MessagePart(id = id, type = type.ifBlank { "unknown" }, text = json.toString()))
      )
    }
  }

  fun messages(array: JSONArray): List<Message> = array.objects().map(::message)

  /* ---- assistant.content[] = Text | Reasoning | Tool（判别 type） ---- */
  private fun assistantContent(json: JSONObject): MessagePart {
    val type = json.str("type")
    val base = MessagePart(id = json.str("id"), type = type)
    return when (type) {
      "text" -> base.copy(text = json.str("text"))
      "reasoning" -> base.copy(text = json.str("text"))
      "tool" -> {
        val state = json.obj("state")
        val status = state.str("status")
        base.copy(
          tool = json.str("name"),
          status = status,
          input = state.valueText("input"),
          // V2 工具输出/附件在 ToolState.Completed.content: Tool.Content[]
          output = toolContentText(state.arr("content")),
          files = toolContentFiles(state.arr("content")),
          error = state.obj("error").str("message")
        )
      }
      else -> base.copy(text = json.toString())   // 未知 content：保留原文
    }
  }

  /* ---- Tool.Content[] = Tool.TextContent | Tool.FileContent ---- */
  private fun toolContentText(content: JSONArray): String =
    content.objects().filter { it.str("type") == "text" }.joinToString("\n") { it.str("text") }

  private fun toolContentFiles(content: JSONArray): List<String> =
    content.objects().filter { it.str("type") == "file" }
      .map { it.str("name").ifBlank { it.str("uri") }.ifBlank { it.str("uri") } }

  /* ---- Permission.Request（V2）----（经生成类型 GPermissionRequest 解析） */
  fun permission(json: JSONObject, directory: String): PermissionRequest {
    val p = GPermissionRequest.fromJson(json)
    val source = json.obj("source")
    val isTool = source.str("type") == "tool"
    return PermissionRequest(
      id = p.id.orEmpty(),
      sessionId = p.sessionID.orEmpty(),
      directory = directory,
      action = p.action.orEmpty(),
      detail = (p.resources ?: emptyList()).toString().ifBlank { json.obj("metadata").toString() },
      always = p.save ?: emptyList(),
      toolMessageId = if (isTool) source.str("messageID") else "",
      toolCallId = if (isTool) source.str("id") else ""
    )
  }
}
