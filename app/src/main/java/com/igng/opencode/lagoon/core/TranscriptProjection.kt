package com.igng.opencode.lagoon.core

import org.json.JSONObject

/** Pure message projection shared by streaming and regression fixtures. Null requests reconciliation. */
object TranscriptProjection {
  fun apply(messages: List<Message>, event: ServerEvent, protocol: ServerProtocol): List<Message>? {
    val p = event.properties
    val type = event.type
    val native = type.startsWith("session.next.")
    val messageId = p.str("messageID").ifBlank { p.str("assistantMessageID") }
    fun update(id: String, role: String = "assistant", change: (Message) -> Message): List<Message>? {
      if (id.isBlank()) return null
      val old = messages.firstOrNull { it.id == id }
      if (old == null && !native) return null
      val message = change(old ?: Message(id, role, p.optLong("timestamp", System.currentTimeMillis()), emptyList()))
      return if (old == null) messages + message else messages.map { if (it.id == id) message else it }
    }
    fun part(id: String, partType: String, change: (MessagePart) -> MessagePart): List<Message>? {
      if (id.isBlank()) return null
      return update(messageId) { message ->
      val old = message.parts.firstOrNull { it.id == id }
      val next = change(old ?: MessagePart(id, partType))
      message.copy(parts = if (old == null) message.parts + next else message.parts.map { if (it.id == id) next else it })
    }
    }
    return when (type) {
      "message.updated" -> {
        val info = p.obj("info")
        val id = info.str("id")
        if (id.isBlank()) null else {
          val previous = messages.firstOrNull { it.id == id }
          val next = JSONObject().put("info", info).toMessage().copy(parts = previous?.parts.orEmpty())
          if (previous == null) messages + next else messages.map { if (it.id == id) next else it }
        }
      }
      "message.removed" -> messages.filterNot { it.id == messageId }
      "message.part.removed" -> update(messageId) { it.copy(parts = it.parts.filterNot { part -> part.id == p.str("partID") }) }
      "message.part.updated" -> {
        val json = p.optJSONObject("part") ?: return null
        val id = json.str("id")
        val target = messageId.ifBlank { json.str("messageID") }
        if (id.isBlank()) null else update(target) { message ->
          val next = if (protocol == ServerProtocol.V2) json.toV2MessagePart() else json.toMessagePart()
          message.copy(parts = if (message.parts.none { it.id == id }) message.parts + next else message.parts.map { if (it.id == id) next else it })
        }
      }
      "message.part.delta" -> {
        val id = p.str("partID")
        val existing = messages.firstOrNull { it.id == messageId }?.parts?.firstOrNull { it.id == id } ?: return null
        if (p.str("field") !in setOf("", "text")) return null
        part(id, existing.type) { it.copy(text = it.text + p.str("delta")) }
      }
      "session.next.prompted", "session.next.prompt.admitted" -> update(messageId, "user") { message ->
        val prompt = p.obj("prompt")
        val text = prompt.str("text")
        message.copy(parts = listOfNotNull(text.takeIf { it.isNotBlank() }?.let { MessagePart("$messageId:text", "text", text = it) }) +
          prompt.arr("files").toAttachments().mapIndexed { index, attachment ->
            MessagePart("$messageId:file:$index", "file", path = attachment.url, title = attachment.name, mime = attachment.mime)
          })
      }
      "session.next.step.started" -> update(messageId) { it.copy(agent = p.str("agent").ifBlank { it.agent }, model = p.obj("model").toModelChoice() ?: it.model) }
      "session.next.step.ended" -> update(messageId) { it.copy(completedAt = p.optLong("timestamp", System.currentTimeMillis()), finish = p.str("finish").ifBlank { "stop" }) }
      "session.next.step.failed" -> update(messageId) { it.copy(error = p.obj("error").str("message").ifBlank { "执行失败" }) }
      "session.next.text.started", "session.next.reasoning.started" -> {
        val reasoning = type.contains("reasoning")
        part(p.str(if (reasoning) "reasoningID" else "textID"), if (reasoning) "reasoning" else "text") { it }
      }
      "session.next.text.delta", "session.next.reasoning.delta", "session.next.text.ended", "session.next.reasoning.ended" -> {
        val reasoning = type.contains("reasoning")
        val id = p.str(if (reasoning) "reasoningID" else "textID")
        if (id.isBlank()) return null
        part(id, if (reasoning) "reasoning" else "text") {
          it.copy(text = if (type.endsWith("delta")) it.text + p.str("delta") else p.str("text"))
        }
      }
      "session.next.tool.input.started", "session.next.tool.input.delta", "session.next.tool.input.ended" -> {
        val id = p.str("callID")
        if (id.isBlank()) return null
        part(id, "tool") { it.copy(tool = p.str("name").ifBlank { it.tool }, status = "pending",
          input = when { type.endsWith("delta") -> it.input + p.str("delta"); type.endsWith("ended") -> p.str("text"); else -> it.input }) }
      }
      "session.next.tool.called", "session.next.tool.progress", "session.next.tool.success", "session.next.tool.failed" -> {
        val id = p.str("callID")
        if (id.isBlank()) return null
        part(id, "tool") { previous -> previous.copy(
          tool = p.str("tool").ifBlank { previous.tool },
          status = when { type.endsWith("success") -> "completed"; type.endsWith("failed") -> "error"; else -> "running" },
          input = if (p.has("input")) p.valueText("input") else previous.input,
          output = p.arr("content").contentText().ifBlank { if (p.has("result")) p.valueText("result") else previous.output },
          error = p.obj("error").str("message"),
          files = if (p.has("outputPaths")) (0 until p.arr("outputPaths").length()).map { p.arr("outputPaths").optString(it) } else previous.files,
          attachments = p.arr("content").toAttachments().ifEmpty { previous.attachments }
        ) }
      }
      "session.next.shell.started" -> part(p.str("callID"), "tool") { it.copy(tool = "shell", status = "running", input = p.str("command")) }
      // Shell ended has no message ID: locate its original call without creating a new message.
      "session.next.shell.ended" -> {
        val id = p.str("callID")
        if (messages.none { message -> message.parts.any { it.id == id } }) null
        else messages.map { message -> message.copy(parts = message.parts.map { if (it.id == id) it.copy(output = p.str("output"), status = "completed") else it }) }
      }
      else -> null
    }
  }
}
