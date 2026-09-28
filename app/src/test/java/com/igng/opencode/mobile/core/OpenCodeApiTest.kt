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

class OpenCodeApiTest {
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
  @Test fun taskReducerRequiresPriorActivityBeforeIdleBecomesCompletion() {
    val idle = TaskReducer.status("id", "idle")
    assertEquals(TaskPhase.IDLE, idle.phase)
    val busy = TaskReducer.status("id", "busy", idle)
    assertEquals(TaskPhase.THINKING, busy.phase)
    val tool = TaskReducer.event("id", "message.part.updated", JSONObject("""{"part":{"type":"tool","tool":"bash","state":{"title":"Run test","input":{"command":"gradle test"}}}}"""), busy)
    assertEquals(TaskPhase.TESTING, tool!!.phase)
    val completed = TaskReducer.status("id", "idle", tool)
    assertEquals(TaskPhase.COMPLETED, completed.phase)
    assertEquals(TaskPhase.COMPLETED, TaskReducer.status("id", "idle", completed).phase)
    val restarted = TaskReducer.status("id", "busy", completed)
    assertEquals(TaskPhase.THINKING, restarted.phase)
    assertEquals("正在处理", restarted.detail)
  }

  @Test fun detectsV2AndMapsLocationSessionsStatusAndMessages() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(404))
      server.enqueue(MockResponse().setBody("""{"healthy":true}""").addHeader("Content-Type", "application/json"))
      server.enqueue(MockResponse().setBody("""{"directory":"/repo","project":{"id":"project-1","directory":"/repo"}}"""))
      server.enqueue(MockResponse().setBody("""{"data":[{"id":"ses-1","projectID":"project-1","title":"Task","location":{"directory":"/repo"},"time":{"created":1,"updated":2}}],"cursor":{}}"""))
      server.enqueue(MockResponse().setBody("""{"data":{"ses-1":{"type":"running"}}}"""))
      server.enqueue(MockResponse().setBody("""{"data":[
        {"id":"msg-1","type":"user","time":{"created":3},"text":"hello"},
        {"id":"msg-2","type":"assistant","time":{"created":4},"agent":"build","model":{"providerID":"openai","id":"gpt"},"content":[{"id":"part-1","type":"text","text":"done"}]}
      ],"cursor":{}}"""))
      val api = OpenCodeApi(ServerProfile("local", "Local", server.url("/").toString().trimEnd('/'), allowCleartext = true), "secret")
      assertEquals("OpenCode V2", api.health())
      assertEquals("/repo", api.projects().single().directory)
      assertEquals("Task", api.sessions("/repo").single().title)
      assertEquals("running", api.status("/repo")["ses-1"])
      val messages = api.messages("ses-1", "/repo")
      assertEquals(listOf("user", "assistant"), messages.map { it.role })
      assertEquals("hello", messages[0].parts.single().text)
      assertEquals("done", messages[1].parts.single().text)
      val requests = List(6) { server.takeRequest() }
      assertEquals("/api/location", requests[2].requestUrl?.encodedPath)
      assertEquals("/repo", requests[3].requestUrl?.queryParameter("directory") ?: "")
    }
  }

  @Test fun v2PromptSwitchesSelectedAgentAndModelBeforeSending() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(404))
      server.enqueue(MockResponse().setBody("""{"healthy":true}"""))
      server.enqueue(MockResponse().setResponseCode(204))
      server.enqueue(MockResponse().setResponseCode(204))
      server.enqueue(MockResponse().setResponseCode(204))
      val api = OpenCodeApi(ServerProfile("local", "Local", server.url("/").toString().trimEnd('/'), allowCleartext = true), "secret")
      api.health()
      server.takeRequest()
      server.takeRequest()
      api.send(Session("ses-1", "/repo", "Task", 0), "Fix it", "build", ModelChoice("openai", "gpt", "GPT"))
      val agentRequest = server.takeRequest()
      assertEquals("/api/session/ses-1/agent", agentRequest.requestUrl?.encodedPath)
      assertEquals("build", JSONObject(agentRequest.body.readUtf8()).getString("agent"))
      val modelRequest = server.takeRequest()
      val model = JSONObject(modelRequest.body.readUtf8()).getJSONObject("model")
      assertEquals("openai", model.getString("providerID"))
      assertEquals("/api/session/ses-1/model", modelRequest.requestUrl?.encodedPath)
      assertEquals("/api/session/ses-1/prompt", server.takeRequest().requestUrl?.encodedPath)
    }
  }

  @Test fun v2SessionListFollowsCursorAndRemovesMessageOrderOnNextPage() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(404))
      server.enqueue(MockResponse().setBody("""{"healthy":true}"""))
      server.enqueue(MockResponse().setBody("""{"data":[{"id":"ses-1","title":"First","location":{"directory":"/repo"},"time":{"updated":1}}],"cursor":{"next":"cursor-1"}}"""))
      server.enqueue(MockResponse().setBody("""{"data":[{"id":"ses-2","title":"Second","location":{"directory":"/repo"},"time":{"updated":2}}],"cursor":{}}"""))
      server.enqueue(MockResponse().setBody("""{"data":[{"id":"msg-1","type":"user","time":{"created":1},"text":"first"}],"cursor":{"next":"cursor-2"}}"""))
      server.enqueue(MockResponse().setBody("""{"data":[{"id":"msg-2","type":"user","time":{"created":2},"text":"second"}],"cursor":{}}"""))
      val api = OpenCodeApi(ServerProfile("local", "Local", server.url("/").toString().trimEnd('/'), allowCleartext = true), "secret")
      api.health()
      assertEquals(listOf("ses-1", "ses-2"), api.sessions("/repo").map { it.id })
      assertEquals(listOf("msg-1", "msg-2"), api.messages("ses-1", "/repo").map { it.id })
      server.takeRequest()
      server.takeRequest()
      val sessionPage = server.takeRequest()
      val sessionNext = server.takeRequest()
      val messagePage = server.takeRequest()
      val messageNext = server.takeRequest()
      assertEquals("cursor-1", sessionNext.requestUrl?.queryParameter("cursor"))
      assertEquals("desc", sessionPage.requestUrl?.queryParameter("order"))
      assertEquals("asc", messagePage.requestUrl?.queryParameter("order"))
      assertEquals("cursor-2", messageNext.requestUrl?.queryParameter("cursor"))
      assertEquals(null, messageNext.requestUrl?.queryParameter("order"))
    }
  }

  @Test fun mapsV2AssistantToolAndPairToken() = runBlocking {
    val message = JSONObject("""{
      "id":"msg-2","type":"assistant","time":{"created":4},"content":[
        {"id":"part-1","type":"tool","name":"bash","state":{"status":"completed","input":{"command":"pwd"},"result":"/repo","outputPaths":["out.txt"]}}
      ]
    }""").toMessage()
    assertEquals("bash", message.parts.single().tool)
    assertEquals("completed", message.parts.single().status)
    assertEquals(listOf("out.txt"), message.parts.single().files)
    val token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("opencode:secret".toByteArray())
    val pair = PairLinkResolver.resolve("https://example.test/auth/connect/abc?auth_token=$token")
    assertEquals("https://example.test", pair.serverUrl)
    assertEquals("opencode", pair.credentials.username)
    assertEquals("secret", pair.credentials.password)
    assertTrue(PairLinkResolver.isPairLink("https://example.test/auth/connect/abc"))
    assertFalse(PairLinkResolver.isPairLink("https://example.test/"))
    assertNotNull(pair.credentials)
  }

  @Test fun resolvesOfficialPairRedirectAndPersistsSessionCookie() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/"))
      server.enqueue(MockResponse().setBody("<html>OpenCode</html>").addHeader("Set-Cookie", "opencode-session=session-value; Path=/; HttpOnly"))
      val pair = PairLinkResolver.resolve(server.url("/auth/connect/one-time").toString())
      assertEquals(server.url("/").toString().trimEnd('/'), pair.serverUrl)
      assertEquals("opencode-session=session-value", pair.credentials.cookie)
    }
  }

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
}
