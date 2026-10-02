package com.igng.opencode.mobile.core

/**
 * 消息 reconcile 存储。对应官方 `packages/tui/src/context/sync.tsx` 的缓存语义：
 * - 消息按 `messageID` 归并（replace/upsert），部分按 `partID` 归并；
 * - 事件驱动的细粒度更新**就地改单条**，不整表覆盖；
 * - 抓取合并时"空文本不覆盖本地已有文本"（流式期间本地更新）。
 *
 * 这样 V1 的 `message.part.updated` / `message.part.delta`，以及 V2 的 `session.next.*`
 * 增量都能平滑反映，无需每次全量刷新。
 */
internal class MessageStore {
  private val bySession = LinkedHashMap<String, MutableList<Message>>()

  @Synchronized fun snapshot(sessionId: String): List<Message> = bySession[sessionId]?.toList().orEmpty()

  @Synchronized fun replace(sessionId: String, messages: List<Message>) {
    bySession[sessionId] = messages.sortedBy { it.created }.toMutableList()
  }

  /** 抓取结果与本地合并：按 messageID 归并，text/reasoning 空文本不覆盖本地已有的流式文本。 */
  @Synchronized fun mergeFetched(sessionId: String, fetched: List<Message>) {
    val existing = bySession[sessionId].orEmpty().associateBy { it.id }
    val merged = fetched.sortedBy { it.created }.map { incoming ->
      val local = existing[incoming.id] ?: return@map incoming
      incoming.copy(parts = incoming.parts.map { part ->
        val localPart = local.parts.firstOrNull { it.id == part.id } ?: return@map part
        if (part.type in setOf("text", "reasoning") && part.text.isBlank() && localPart.text.isNotBlank())
          part.copy(text = localPart.text)
        else part
      })
    }
    bySession[sessionId] = merged.toMutableList()
  }

  /** 用一条消息 info（如 `message.updated`）替换/插入该条；info 无 parts 时保留已有 parts。 */
  @Synchronized fun upsertMessage(sessionId: String, message: Message) {
    val list = bySession.getOrPut(sessionId) { mutableListOf() }
    val index = list.indexOfFirst { it.id == message.id }
    if (index >= 0) {
      val existing = list[index]
      val merged = if (message.parts.isEmpty() && existing.parts.isNotEmpty()) message.copy(parts = existing.parts) else message
      list[index] = merged
    } else list.add(message)
    list.sortBy { it.created }
  }

  /** 用一个完整 part（如 `message.part.updated`）替换/插入该 part。 */
  @Synchronized fun upsertPart(sessionId: String, messageId: String, part: MessagePart) {
    val list = bySession.getOrPut(sessionId) { mutableListOf() }
    val msgIndex = list.indexOfFirst { it.id == messageId }
    if (msgIndex < 0) return
    val msg = list[msgIndex]
    val parts = msg.parts.toMutableList()
    val pIndex = parts.indexOfFirst { it.id == part.id }
    if (pIndex >= 0) parts[pIndex] = part else parts.add(part)
    list[msgIndex] = msg.copy(parts = parts)
  }

  /** 就地改单个 part（如 `message.part.delta` 追加文本）；缺失时用 [base] 创建，避免流式重连丢 delta。 */
  @Synchronized fun patchPart(sessionId: String, messageId: String, partId: String, base: MessagePart, transform: (MessagePart) -> MessagePart) {
    val list = bySession.getOrPut(sessionId) { mutableListOf() }
    var msgIndex = list.indexOfFirst { it.id == messageId }
    if (msgIndex < 0) {
      list.add(Message(messageId, "assistant", System.currentTimeMillis(), emptyList()))
      list.sortBy { it.created }
      msgIndex = list.indexOfFirst { it.id == messageId }
    }
    val msg = list[msgIndex]
    val parts = msg.parts.toMutableList()
    val pIndex = parts.indexOfFirst { it.id == partId }
    val updated = transform(if (pIndex >= 0) parts[pIndex] else base)
    if (pIndex >= 0) parts[pIndex] = updated else parts.add(updated)
    list[msgIndex] = msg.copy(parts = parts)
  }

  @Synchronized fun removePart(sessionId: String, messageId: String, partId: String) {
    val list = bySession[sessionId] ?: return
    val msgIndex = list.indexOfFirst { it.id == messageId }
    if (msgIndex < 0) return
    list[msgIndex] = list[msgIndex].copy(parts = list[msgIndex].parts.filterNot { it.id == partId })
  }

  @Synchronized fun removeMessage(sessionId: String, messageId: String) {
    bySession[sessionId]?.removeAll { it.id == messageId }
  }
}
