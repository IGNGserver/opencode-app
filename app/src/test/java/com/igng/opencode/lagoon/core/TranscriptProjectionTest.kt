package com.igng.opencode.lagoon.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TranscriptProjectionTest {
  private fun event(type: String, data: String) = ServerEvent(directory = "/p", type = type, properties = JSONObject(data))
  @Test fun legacyDeltaAppendsOnceAndFullSnapshotReplacesItsText() {
    val initial = listOf(Message("m", "assistant", 1, listOf(MessagePart("p", "text", text = "你"))))
    val delta = TranscriptProjection.apply(initial, event("message.part.delta", """{"messageID":"m","partID":"p","field":"text","delta":"好"}"""), ServerProtocol.V1)!!
    assertEquals("你好", delta.single().parts.single().text)
    val snapshot = TranscriptProjection.apply(delta, event("message.part.updated", """{"part":{"id":"p","messageID":"m","type":"text","text":"你好！"}}"""), ServerProtocol.V1)!!
    assertEquals("你好！", snapshot.single().parts.single().text)
    assertEquals(1, snapshot.single().parts.size)
  }
  @Test fun nativeDeltaCanPrecedeTheMessageSnapshotAndEndedDoesNotDuplicateText() {
    val delta = TranscriptProjection.apply(emptyList(), event("session.next.text.delta", """{"sessionID":"s","assistantMessageID":"m","textID":"t","timestamp":1,"delta":"hello"}"""), ServerProtocol.V2)!!
    val ended = TranscriptProjection.apply(delta, event("session.next.text.ended", """{"sessionID":"s","assistantMessageID":"m","textID":"t","timestamp":2,"text":"hello world"}"""), ServerProtocol.V2)!!
    assertEquals("hello world", ended.single().parts.single().text)
    assertEquals(1, ended.size)
  }
  @Test fun unknownLegacyPartRequestsReconciliationRatherThanInventingAUserMessage() {
    assertNull(TranscriptProjection.apply(emptyList(), event("message.part.delta", """{"messageID":"m","partID":"p","delta":"lost"}"""), ServerProtocol.V1))
  }
  @Test fun nativeToolResultProjectsTextAndOutputFilesWithoutJsonNoise() {
    val result = TranscriptProjection.apply(emptyList(), event("session.next.tool.success", """{"assistantMessageID":"m","callID":"c","tool":"write","content":[{"type":"text","text":"saved"}],"outputPaths":["src/main.kt"]}"""), ServerProtocol.V2)!!
    val tool = result.single().parts.single()
    assertEquals("completed", tool.status); assertEquals("saved", tool.output)
    assertEquals(listOf("src/main.kt"), tool.files)
  }
}
