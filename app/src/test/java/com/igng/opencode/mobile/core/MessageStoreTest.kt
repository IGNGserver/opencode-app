package com.igng.opencode.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MessageStore 细粒度 reconcile 语义测试（官方 sync.tsx 缓存规则）：
 * - part 按 partID 归并、消息按 messageID 归并；
 * - delta 就地追加、缺失时创建（流式重连不丢）；
 * - 抓取合并时"空文本不覆盖本地已有文本"。
 */
class MessageStoreTest {

  @Test fun upsertPartReplacesByIdAndAppendsNew() {
    val store = MessageStore()
    store.upsertMessage("ses", Message("msg", "assistant", 1, emptyList()))
    store.upsertPart("ses", "msg", MessagePart("p1", "text", text = "hello"))
    store.upsertPart("ses", "msg", MessagePart("p1", "text", text = "hello world"))
    store.upsertPart("ses", "msg", MessagePart("p2", "tool", tool = "bash"))
    val parts = store.snapshot("ses").single().parts
    assertEquals(listOf("p1", "p2"), parts.map { it.id })
    assertEquals("hello world", parts.first { it.id == "p1" }.text)   // 按 partID 归并
  }

  @Test fun deltaAppendsAndCreatesMissingPart() {
    val store = MessageStore()
    // 断线重连：delta 先于 started 到达，patchPart 应创建 message+part 而非丢弃
    store.patchPart("ses", "msg", "p1", MessagePart("p1", "text")) { it.copy(text = it.text + "a") }
    store.patchPart("ses", "msg", "p1", MessagePart("p1", "text")) { it.copy(text = it.text + "b") }
    assertEquals("ab", store.snapshot("ses").single().parts.single().text)
  }

  @Test fun mergeFetchedKeepsLocalStreamingTextWhenIncomingBlank() {
    val store = MessageStore()
    store.upsertMessage("ses", Message("msg", "assistant", 1, listOf(MessagePart("p1", "text", text = "streamed"))))
    // 抓取返回的 p1 文本为空（尚未落库），不应覆盖本地流式文本
    store.mergeFetched("ses", listOf(Message("msg", "assistant", 1, listOf(MessagePart("p1", "text", text = "")))))
    assertEquals("streamed", store.snapshot("ses").single().parts.single().text)
  }

  @Test fun upsertMessageInfoPreservesExistingParts() {
    val store = MessageStore()
    store.upsertMessage("ses", Message("msg", "assistant", 1, listOf(MessagePart("p1", "text", text = "keep"))))
    // message.updated 只带 info（无 parts），不应清空已有 parts
    store.upsertMessage("ses", Message("msg", "assistant", 2, emptyList(), error = "boom"))
    val msg = store.snapshot("ses").single()
    assertEquals("boom", msg.error)
    assertEquals("keep", msg.parts.single().text)
  }

  @Test fun removePartAndMessage() {
    val store = MessageStore()
    store.upsertMessage("ses", Message("msg", "assistant", 1, listOf(MessagePart("p1", "text"), MessagePart("p2", "text"))))
    store.removePart("ses", "msg", "p1")
    assertEquals(listOf("p2"), store.snapshot("ses").single().parts.map { it.id })
    store.removeMessage("ses", "msg")
    assertTrue(store.snapshot("ses").isEmpty())
  }
}
