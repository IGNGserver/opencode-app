package com.igng.opencode.mobile.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class OpenCodeApiTest {
  @Test fun authenticatesHealthAndSendsAsyncPromptInSelectedDirectory() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"healthy":true,"version":"1.0"}""").addHeader("Content-Type", "application/json"))
      server.enqueue(MockResponse().setResponseCode(204))
      val api = OpenCodeApi(ServerProfile("local", "Local", server.url("/").toString().trimEnd('/'), "opencode"), "secret")
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
}
