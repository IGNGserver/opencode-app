package com.igng.opencode.mobile.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * 契约忠实测试。每条断言对应 OpenAPI schema 的一个具体字段，而非"当前实现的输出"：
 * - V1 `Project.Info.worktree` / V2 `Project.canonical`（项目列表塌陷根因）
 * - V2 `Session.Info.title` 可选 + `projectID` 归属（标题/归属根因）
 * - V2 `ToolState.Completed.content: Tool.Content[]`（内容显示不全根因）
 * - V2 `session.prompt` 顶层 `text`、`permission reply` 的 `decision`（写操作根因）
 */
class OpenCodeApiTest {

  /* ---------- 项目：V1 用 worktree，V2 用 canonical ---------- */
  @Test fun v1ProjectReadsWorktreeField() {
    val project = V1Contract.project(JSONObject("""{"id":"pro-1","worktree":"/repo","name":"repo","time":{"created":1,"updated":2}}"""))
    assertEquals("pro-1", project.id)
    assertEquals("/repo", project.directory)   // V1 路径标识字段是 worktree
    assertEquals("repo", project.name)
    assertEquals(2L, project.timeUpdated)
  }

  @Test fun v2ProjectReadsCanonicalFieldNotWorktree() {
    // V2 Project schema 没有 worktree/directory，路径标识是 canonical
    val project = V2Contract.project(JSONObject("""{"id":"pro-1","canonical":"/repo","time":{"created":1,"updated":2},"sandboxes":[]}"""))
    assertEquals("pro-1", project.id)
    assertEquals("/repo", project.directory)   // 从 canonical 读取，不塌陷为空
    assertEquals(2L, project.timeUpdated)
  }

  /* ---------- 会话：title 可选、归属用 projectID ---------- */
  @Test fun v2SessionTitleIsOptionalAndOwnershipUsesProjectId() {
    // title 缺省（非 required）；归属字段是 projectID，目录在 location.directory
    val s = V2Contract.session(JSONObject("""{"id":"ses-1","projectID":"pro-1","location":{"directory":"/repo"},"time":{"created":1,"updated":2}}"""))
    assertEquals("ses-1", s.id)
    assertEquals("pro-1", s.projectId)          // 归属判定用 projectID
    assertEquals("/repo", s.directory)
    assertEquals("", s.title)                    // 缺省即空，不编造"未命名会话"
    assertTrue(s.titleIsDefault)

    val titled = V2Contract.session(JSONObject("""{"id":"ses-2","projectID":"pro-1","title":"修复构建","location":{"directory":"/repo"},"time":{"created":1,"updated":2}}"""))
    assertEquals("修复构建", titled.title)
    assertFalse(titled.titleIsDefault)
  }

  @Test fun v1SessionReadsTitleAndDirectory() {
    val s = V1Contract.session(JSONObject("""{"id":"ses-1","slug":"s","projectID":"pro-1","directory":"/repo","title":"任务","time":{"created":1,"updated":2}}"""))
    assertEquals("任务", s.title)
    assertEquals("/repo", s.directory)
    assertEquals("pro-1", s.projectId)
    assertFalse(s.titleIsDefault)
  }

  /* ---------- 消息内容：V2 工具输出取自 Tool.Content ---------- */
  @Test fun v2AssistantToolOutputComesFromToolContentBlocks() {
    val message = V2Contract.message(JSONObject("""{
      "id":"msg-2","type":"assistant","time":{"created":4},"agent":"build","content":[
        {"id":"part-1","type":"tool","name":"bash","state":{"status":"completed","input":{"command":"pwd"},"content":[
          {"type":"text","text":"/repo"},
          {"type":"file","uri":"file:///out.txt","mime":"text/plain","name":"out.txt"}
        ]}}
      ]
    }"""))
    val tool = message.parts.single()
    assertEquals("bash", tool.tool)
    assertEquals("completed", tool.status)
    assertEquals("/repo", tool.output)          // Tool.TextContent.text → output
    assertEquals(listOf("out.txt"), tool.files) // Tool.FileContent.name → files
  }

  @Test fun v2MessageCoversAllUnionVariantsWithoutSilentDrop() {
    // 11 种 tagged union 每种都必须产出一条可见消息（不得抛异常或返回空被丢弃）
    listOf("user", "assistant", "shell", "compaction", "system", "synthetic", "skill",
      "agent-switched", "model-selected", "location-switched", "idle").forEach { type ->
      val m = V2Contract.message(JSONObject("""{"id":"msg-1","type":"$type","time":{"created":1},"text":"hello"}"""))
      assertEquals("variant $type must be preserved", "msg-1", m.id)
    }
    // user 的文本必进 parts
    val user = V2Contract.message(JSONObject("""{"id":"msg-1","type":"user","time":{"created":1},"text":"hello"}"""))
    assertEquals("hello", user.parts.single().text)
    // 未知类型保留原始 type + 原文
    val unknown = V2Contract.message(JSONObject("""{"id":"msg-1","type":"future-type","time":{"created":1},"foo":"bar"}"""))
    assertEquals("future-type", unknown.parts.single().type)
    assertTrue(unknown.parts.single().text.contains("foo"))
  }

  @Test fun v1MessageReadsInfoPartsAndToolStateOutput() {
    val message = V1Contract.message(JSONObject("""{
      "info":{"id":"msg-1","role":"assistant","time":{"created":4}},
      "parts":[{"id":"prt-1","type":"tool","tool":"bash","state":{"status":"completed","input":{"command":"pwd"},"output":"/repo","title":"pwd"}}]
    }"""))
    assertEquals("assistant", message.role)
    val tool = message.parts.single()
    assertEquals("bash", tool.tool)
    assertEquals("/repo", tool.output)   // V1 ToolStateCompleted 有 output 字段
    assertEquals("pwd", tool.title)
    assertEquals("completed", tool.status)
  }

  /* ---------- 发消息 / 权限回复：写操作 body 字段名 ---------- */
  @Test fun authenticatesHealthAndSendsAsyncPromptInSelectedDirectory() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"healthy":true,"version":"1.0"}""").addHeader("Content-Type", "application/json"))
      server.enqueue(MockResponse().setResponseCode(204))
      val api = OpenCodeApi(ServerProfile("local", "Local", server.url("/").toString().trimEnd('/'), "opencode", allowCleartext = true), "secret")
      assertEquals("1.0", api.health())
      api.send(Session("session-1", "/repo with space", "Task", 0), "Fix the build", "build", ModelChoice("openai", "gpt", "GPT"))
      val health = server.takeRequest()
      assertEquals("/global/health", health.path)
      assertEquals("Basic b3BlbmNvZGU6c2VjcmV0", health.getHeader("Authorization"))
      val prompt = server.takeRequest()
      assertTrue(prompt.path!!.startsWith("/session/session-1/prompt_async?directory="))
      val json = JSONObject(prompt.body.readUtf8())
      assertEquals("Fix the build", json.getJSONArray("parts").getJSONObject(0).getString("text"))
      assertEquals("openai", json.getJSONObject("model").getString("providerID"))
    }
  }

  @Test fun v2PromptSendsTopLevelTextNotNestedPrompt() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(404))
      server.enqueue(MockResponse().setBody("""{"healthy":true}"""))
      server.enqueue(MockResponse().setResponseCode(204))
      val api = OpenCodeApi(ServerProfile("local", "Local", server.url("/").toString().trimEnd('/'), allowCleartext = true), "secret")
      api.health()
      api.send(Session("ses-1", "/repo", "Task", 0), "Fix it", null, null)
      server.takeRequest(); server.takeRequest()
      val prompt = server.takeRequest()
      assertEquals("/api/session/ses-1/prompt", prompt.requestUrl?.encodedPath)
      val body = JSONObject(prompt.body.readUtf8())
      assertEquals("Fix it", body.getString("text"))   // text 是顶层必填，非 {"prompt":{"text"}}
      assertFalse(body.has("prompt"))
    }
  }

  @Test fun v2PermissionReplyUsesDecisionField() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(404))
      server.enqueue(MockResponse().setBody("""{"healthy":true}"""))
      server.enqueue(MockResponse().setResponseCode(204))
      val api = OpenCodeApi(ServerProfile("local", "Local", server.url("/").toString().trimEnd('/'), allowCleartext = true), "secret")
      api.health()
      api.replyPermission(PermissionRequest("per-1", "ses-1", "/repo", "bash", ""), "allow")
      server.takeRequest(); server.takeRequest()
      val reply = server.takeRequest()
      assertEquals("/api/session/ses-1/permission/per-1/reply", reply.requestUrl?.encodedPath)
      assertEquals("once", JSONObject(reply.body.readUtf8()).getString("decision"))  // decision 字段
    }
  }

  /* ---------- 项目列表端点：V2 用 /api/project 而非 /api/location ---------- */
  @Test fun v2ProjectsUsesApiProjectEndpoint() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(404))
      server.enqueue(MockResponse().setBody("""{"healthy":true}"""))
      server.enqueue(MockResponse().setBody("""[{"id":"pro-1","canonical":"/repo","time":{"created":1,"updated":2},"sandboxes":[]}]"""))
      val api = OpenCodeApi(ServerProfile("local", "Local", server.url("/").toString().trimEnd('/'), allowCleartext = true), "secret")
      api.health()
      val projects = api.projects()
      assertEquals("/repo", projects.single().directory)
      server.takeRequest(); server.takeRequest()
      assertEquals("/api/project", server.takeRequest().requestUrl?.encodedPath)  // 不是 /api/location
    }
  }

  /* ---------- 分页：cursor 不与 order 混用 ---------- */
  @Test fun v2SessionListFollowsCursorAndRemovesMessageOrderOnNextPage() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(404))
      server.enqueue(MockResponse().setBody("""{"healthy":true}"""))
      server.enqueue(MockResponse().setBody("""{"data":[{"id":"ses-1","projectID":"p","title":"First","location":{"directory":"/repo"},"time":{"updated":1}}],"cursor":{"next":"cursor-1"}}"""))
      server.enqueue(MockResponse().setBody("""{"data":[{"id":"ses-2","projectID":"p","title":"Second","location":{"directory":"/repo"},"time":{"updated":2}}],"cursor":{}}"""))
      server.enqueue(MockResponse().setBody("""{"data":[{"id":"msg-1","type":"user","time":{"created":1},"text":"first"}],"cursor":{"next":"cursor-2"}}"""))
      server.enqueue(MockResponse().setBody("""{"data":[{"id":"msg-2","type":"user","time":{"created":2},"text":"second"}],"cursor":{}}"""))
      val api = OpenCodeApi(ServerProfile("local", "Local", server.url("/").toString().trimEnd('/'), allowCleartext = true), "secret")
      api.health()
      assertEquals(listOf("ses-1", "ses-2"), api.sessions("/repo", "p").map { it.id })
      assertEquals(listOf("msg-1", "msg-2"), api.messages("ses-1", "/repo").map { it.id })
      server.takeRequest(); server.takeRequest()
      val sessionPage = server.takeRequest()
      val sessionNext = server.takeRequest()
      val messagePage = server.takeRequest()
      val messageNext = server.takeRequest()
      assertEquals("desc", sessionPage.requestUrl?.queryParameter("order"))
      assertEquals("cursor-1", sessionNext.requestUrl?.queryParameter("cursor"))
      assertEquals(null, sessionNext.requestUrl?.queryParameter("order"))   // cursor 页不带 order
      assertEquals("asc", messagePage.requestUrl?.queryParameter("order"))
      assertEquals("cursor-2", messageNext.requestUrl?.queryParameter("cursor"))
      assertEquals(null, messageNext.requestUrl?.queryParameter("order"))
    }
  }

  /* ---------- 事件信封归一 ---------- */
  @Test fun normalizesLegacyAndV2EventShapes() {
    val legacy = """{"directory":"/repo","payload":{"type":"session.status","properties":{"sessionID":"ses-1","status":{"type":"busy"}}}}""".toServerEvent("legacy-id")
    assertEquals("legacy-id", legacy.id)
    assertEquals("/repo", legacy.directory)
    assertEquals("session.status", legacy.type)
    val v2 = """{"id":"evt-1","type":"permission.v2.asked","properties":{"id":"per-1","sessionID":"ses-1","action":"file.read","resources":["a.txt"],"save":[]}}""".toServerEvent("")
    assertEquals("evt-1", v2.id)
    assertEquals("permission.asked", v2.type)
    assertEquals("file.read", v2.properties.getString("permission"))
    assertEquals("ses-1", v2.properties.getString("sessionID"))
  }

  @Test fun taskReducerRequiresPriorActivityBeforeIdleBecomesCompletion() {
    val idle = TaskReducer.status("id", "idle")
    assertEquals(TaskPhase.IDLE, idle.phase)
    val busy = TaskReducer.status("id", "busy", idle)
    assertEquals(TaskPhase.THINKING, busy.phase)
    val tool = TaskReducer.event("id", "message.part.updated", JSONObject("""{"part":{"type":"tool","tool":"bash","state":{"title":"Run test","input":{"command":"gradle test"}}}}"""), busy)
    assertEquals(TaskPhase.TESTING, tool!!.phase)
    val completed = TaskReducer.status("id", "idle", tool)
    assertEquals(TaskPhase.COMPLETED, completed.phase)
  }
}
