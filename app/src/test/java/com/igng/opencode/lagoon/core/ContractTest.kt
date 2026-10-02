package com.igng.opencode.lagoon.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 四症状修复的契约测试。每条断言对应 OpenAPI schema 的具体字段，覆盖 V1/V2 双协议：
 * - 症状1 项目列表：V1 路径字段 `worktree`，V2 是 `canonical`（无 worktree/directory）
 * - 症状2 归属：`projectID`（两代都有）
 * - 症状3 标题：V2 `title` 可缺省 + 默认标题检测
 * - 症状4 内容：V2 工具输出取自 `ToolState.Completed.content: Tool.Content[]`，11 种消息变体不丢
 */
class ContractTest {

  /* ---- 症状1：项目路径字段 ---- */
  @Test fun v1ProjectReadsWorktree() {
    val p = JSONObject("""{"id":"pro-1","worktree":"/repo","name":"repo","time":{"updated":2}}""").toProject()
    assertEquals("/repo", p.directory)   // V1 worktree
    assertEquals(2L, p.timeUpdated)
  }
  @Test fun v2ProjectReadsCanonicalNotWorktree() {
    // V2 Project 无 worktree/directory，路径标识是 canonical
    val p = JSONObject("""{"id":"pro-1","canonical":"/repo","time":{"updated":2},"sandboxes":[]}""").toProject()
    assertEquals("/repo", p.directory)   // 不塌陷为空 → 项目列表不再被过滤光
    assertEquals(2L, p.timeUpdated)
  }

  /* ---- 症状2/3：会话归属 + 标题 ---- */
  @Test fun v2SessionOwnershipAndDefaultTitle() {
    val s = JSONObject("""{"id":"ses-1","projectID":"pro-1","location":{"directory":"/repo"},"time":{"updated":2}}""").toSession()
    assertEquals("pro-1", s.projectId)   // 归属用 projectID
    assertEquals("/repo", s.directory)
    assertEquals("", s.title)            // V2 title 可缺省，不编造"未命名会话"
    assertTrue(s.titleIsDefault)
  }
  @Test fun v1SessionReadsTitleAndDirectory() {
    val s = JSONObject("""{"id":"ses-1","projectID":"pro-1","directory":"/repo","title":"修复构建","time":{"updated":2}}""").toSession()
    assertEquals("修复构建", s.title)
    assertEquals("pro-1", s.projectId)
    assertFalse(s.titleIsDefault)
  }

  /* ---- 症状4：V2 工具输出取自 Tool.Content，消息变体不丢 ---- */
  @Test fun v2ToolOutputComesFromContentBlocks() {
    val part = JSONObject("""{
      "id":"prt-1","type":"tool","name":"bash",
      "state":{"status":"completed","input":{"command":"pwd"},"content":[
        {"type":"text","text":"/repo"},
        {"type":"file","uri":"file:///out.txt","mime":"text/plain","name":"out.txt"}
      ]}
    }""").toV2MessagePart()
    assertEquals("bash", part.tool)
    assertEquals("completed", part.status)
    assertEquals("/repo", part.output)            // Tool.TextContent.text → output（非 state.output）
    assertEquals(listOf("out.txt"), part.files)   // Tool.FileContent.name → files
  }

  @Test fun v2MessageCoversAllUnionVariantsWithoutSilentDrop() {
    listOf("user", "assistant", "shell", "compaction", "system", "synthetic", "skill",
      "agent-switched", "model-selected", "location-switched", "idle").forEach { type ->
      val m = JSONObject("""{"id":"m","type":"$type","time":{"created":1},"text":"hello"}""").toMessage()
      assertEquals("variant $type must be preserved", "m", m.id)
    }
    // 未知类型保留原文
    val unknown = JSONObject("""{"id":"m","type":"future-type","time":{"created":1},"foo":"bar"}""").toMessage()
    assertEquals("future-type", unknown.parts.single().type)
    assertTrue(unknown.parts.single().text.contains("foo"))
    // user 文本映射为 text part（渲染为 markdown）
    val user = JSONObject("""{"id":"m","type":"user","time":{"created":1},"text":"hi"}""").toMessage()
    assertEquals("user", user.role)
    assertEquals("hi", user.parts.single().text)
  }

  /* ---- V1 工具输出仍取 state.output（V1 ToolStateCompleted 有该字段） ---- */
  @Test fun v1ToolOutputReadsStateOutput() {
    val m = JSONObject("""{
      "info":{"id":"m","role":"assistant","time":{"created":1}},
      "parts":[{"id":"p","type":"tool","tool":"bash","state":{"status":"completed","input":{"command":"pwd"},"output":"/repo","title":"pwd"}}]
    }""").toMessage()
    val tool = m.parts.single()
    assertEquals("/repo", tool.output)   // V1 state.output
    assertEquals("pwd", tool.title)
  }
}
