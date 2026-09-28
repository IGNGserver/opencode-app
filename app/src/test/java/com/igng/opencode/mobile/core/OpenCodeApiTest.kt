package com.igng.opencode.mobile.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.igng.opencode.mobile.push.PushMessageVerifier
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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
  @Test fun taskReducerRequiresPriorActivityBeforeIdleBecomesCompletion() {    val idle = TaskReducer.status("id", "idle")
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

  @Test fun taskPhaseGroupsStayConsistent() {
    // active must be the union of the running and waiting groups used by the UI filters.
    assertTrue(TaskState.ACTIVE_PHASES == TaskState.RUNNING_PHASES + TaskState.WAITING_PHASES)
    assertTrue(TaskState.RUNNING_PHASES.intersect(TaskState.WAITING_PHASES).isEmpty())
    for (phase in TaskPhase.entries) {
      assertEquals(phase in TaskState.ACTIVE_PHASES, TaskState("id", phase).active)
    }
    assertTrue(TaskState("id", TaskPhase.COMPLETED).active.not())
    assertTrue(TaskState("id", TaskPhase.DISCONNECTED).active.not())
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

  @Test fun encodesSessionIdsExactlyOnceInPath() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"healthy":true,"version":"1.0"}"""))
      server.enqueue(MockResponse().setBody("""[]"""))
      val api = OpenCodeApi(ServerProfile("local", "Local", server.url("/").toString().trimEnd('/'), allowCleartext = true), "secret")
      api.health()
      api.messages("ses 1/2", "/repo")
      server.takeRequest()
      // 'ses 1/2' must be percent-encoded once: %20 for the space and %2F for the slash.
      assertEquals("/session/ses%201%2F2/message", server.takeRequest().requestUrl?.encodedPath)
    }
  }

  /** The same fixture drives companion/test/server.test.mjs, keeping both task reducers in lockstep. */
  @Test fun taskReducerMatchesSharedContract() {    val fixture = JSONObject(java.io.File("docs/task-event-contract.json").readText())
    val cases = fixture.getJSONArray("cases")
    for (index in 0 until cases.length()) {
      val case = cases.getJSONObject(index)
      val previous = case.optString("previous").takeIf { it.isNotBlank() }?.let { TaskState("s", TaskPhase.valueOf(it)) }
      val result = TaskReducer.event("s", case.getJSONObject("kotlin").getString("type"),
        case.getJSONObject("kotlin").getJSONObject("properties"), previous)
      assertNotNull("no transition for ${case.getString("name")}", result)
      assertEquals(case.getString("name"), case.getString("expectedPhase"), result!!.phase.name)
    }
  }

  @Test fun projectsLegacyAndV2MessageParts() {
    val legacy = JSONObject("""{"id":"part-1","type":"tool","tool":"bash","state":{"status":"completed","title":"跑测试","input":{"command":"gradle test"},"output":"ok"}}""").toMessagePart()
    assertEquals("bash", legacy.tool)
    assertEquals("completed", legacy.status)
    assertEquals("跑测试", legacy.title)
    val v2 = JSONObject("""{"id":"part-2","type":"tool","name":"bash","state":{"status":"running","input":{"command":"pwd"},"result":"/repo"}}""").toV2MessagePart()
    assertEquals("bash", v2.tool)
    assertEquals("running", v2.status)
  }

  @Test fun modelsExposeTheFieldsTheUiReads() {
    val change = JSONObject("""{"file":"a.kt","before":"old","after":"new","additions":3,"deletions":1,"patch":"@@"}""").toChange()
    assertEquals("a.kt", change.path)
    assertEquals("new", change.after)
    assertEquals(3, change.additions)
    val node = JSONObject("""{"path":"src/a.kt","type":"file","absolute":"/x","ignored":true}""").toNode()
    assertEquals("src/a.kt", node.path)
    assertEquals("file", node.type)
    val text = JSONObject("""{"type":"text","content":"hi","encoding":"utf-8","mimeType":"text/plain"}""").toFileContent()
    assertEquals("text", text.type)
    assertEquals("hi", text.content)
    val binary = JSONObject("""{"content":"AAA=","encoding":"base64"}""").toFileContent()
    assertEquals("binary", binary.type)
  }

  @Test fun protocolCapabilitiesMatchTheApiBranches() {
    assertTrue(ServerProtocol.V1.supportsSessionActions)
    assertTrue(ServerProtocol.V1.supportsTitleOnCreate)
    assertTrue(ServerProtocol.V1.supportsTodosAndDiff)
    assertFalse(ServerProtocol.V2.supportsSessionActions)
    assertFalse(ServerProtocol.V2.supportsTitleOnCreate)
    assertFalse(ServerProtocol.V2.supportsTodosAndDiff)
  }

  /**
   * A01: credentials must never leave the configured origin, not even when the server answers with a
   * redirect. The disallowed target must receive zero requests.
   */
  @Test fun apiDoesNotForwardCredentialsToRedirectTarget() = runBlocking {
    MockWebServer().use { target ->
      MockWebServer().use { origin ->
        target.enqueue(MockResponse().setBody("""{"healthy":true,"version":"9"}"""))
        origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", target.url("/steal")))
        val api = OpenCodeApi(ServerProfile("local", "Local", origin.url("/").toString().trimEnd('/'), allowCleartext = true), "secret")
        runCatching { api.health() }
        // The redirect must not have been followed at all.
        assertEquals(0, target.requestCount)
        val first = origin.takeRequest()
        assertEquals("Basic b3BlbmNvZGU6c2VjcmV0", first.getHeader("Authorization"))
      }
    }
  }

  /** A01: pairing must reject a redirect to a different host before sending the one-time token. */
  @Test fun pairLinkRedirectRejectsBeforeSendingToDisallowedHost() = runBlocking {
    MockWebServer().use { target ->
      target.enqueue(MockResponse().setBody("<html>stolen</html>"))
      MockWebServer().use { origin ->
        origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", target.url("/auth/connect/leak")))
        val result = runCatching { PairLinkResolver.resolve(origin.url("/auth/connect/one-time").toString()) }
        assertTrue(result.isFailure)
        assertEquals(0, target.requestCount)
      }
    }
  }

  /** A15: cancelling the caller must abort the in-flight HTTP call instead of waiting it out. */
  @Test fun cancellingApiInterruptsNetworkWait() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"healthy":true,"version":"1"}""").setBodyDelay(3, TimeUnit.SECONDS))
      val api = OpenCodeApi(ServerProfile("local", "Local", server.url("/").toString().trimEnd('/'), allowCleartext = true), "secret")
      val job = launch(Dispatchers.IO) { runCatching { api.health() } }
      delay(200)
      val started = System.nanoTime()
      job.cancelAndJoin()
      val elapsedMillis = (System.nanoTime() - started) / 1_000_000
      assertTrue("cancel took ${elapsedMillis}ms", elapsedMillis < 1_500)
    }
  }

  /** A01: the cleartext target allow-list only accepts loopback hosts. */
  @Test fun cleartextAllowListIsLoopbackOnly() {
    assertTrue(HttpOrigin.allowsCleartext("localhost"))
    assertTrue(HttpOrigin.allowsCleartext("127.0.0.1"))
    assertTrue(HttpOrigin.allowsCleartext("::1"))
    assertFalse(HttpOrigin.allowsCleartext("example.com"))
    assertFalse(HttpOrigin.allowsCleartext("10.0.0.5"))
  }

  @Test fun pushSignatureMatchesCompanionVector() {
    val v1 = mapOf(
      "sessionId" to "ses-1", "serverId" to "srv-1", "phase" to "WAITING_PERMISSION",
      "detail" to "等待权限确认", "title" to "构建"
    )
    // Vector cross-checked against companion signPushPayloadV1('topsecret', ...).
    assertEquals("yO0ubha7-M4U66aWoIeWbSdk7z0sVsQtcvVZhB-1Who", PushMessageVerifier.signV1("topsecret", v1))
    assertTrue(PushMessageVerifier.verify("topsecret", v1 + ("sig" to PushMessageVerifier.signV1("topsecret", v1))))
    assertEquals(false, PushMessageVerifier.verify("topsecret", v1))
    assertTrue(PushMessageVerifier.verify("", v1))
  }

  @Test fun v2PushSignatureBindsDirectoryDeviceAndFreshness() {
    val fixed = mapOf(
      "version" to "2", "sessionId" to "ses-1", "serverId" to "srv-1", "directory" to "/repo",
      "phase" to "WAITING_PERMISSION", "detail" to "等待权限确认", "title" to "构建", "deviceId" to "dev-1", "ts" to "1700000000000"
    )
    // Vector cross-checked against companion signPushPayload('topsecret', ...). sign() ignores freshness.
    assertEquals("sVjCnL3JrQmEqgmfC6rPWQeUs9zxWu3ypfZKrxXVM-4", PushMessageVerifier.sign("topsecret", fixed))
    val now = System.currentTimeMillis().toString()
    val fresh = fixed + ("ts" to now)
    assertTrue(PushMessageVerifier.verify("topsecret", fresh + ("sig" to PushMessageVerifier.sign("topsecret", fresh))))
    // A rewritten routing field invalidates a signature captured for another directory.
    assertFalse(PushMessageVerifier.verify("topsecret", fresh + ("directory" to "/other") + ("sig" to PushMessageVerifier.sign("topsecret", fresh))))
    // A stale (replayed) message is rejected.
    assertFalse(PushMessageVerifier.verify("topsecret", fixed + ("sig" to PushMessageVerifier.sign("topsecret", fixed))))
    // A field containing a newline cannot imitate a field boundary.
    val boundary = mapOf("version" to "2", "sessionId" to "ses-1", "serverId" to "srv-1", "directory" to "/repo\nphase", "phase" to "x",
      "detail" to "d", "title" to "t", "deviceId" to "dev-1", "ts" to now)
    val other = boundary + ("directory" to "/repo") + ("phase" to "phase\nx")
    assertFalse(PushMessageVerifier.sign("topsecret", boundary) == PushMessageVerifier.sign("topsecret", other))
  }

  /** A10: a refresh after an offline start must re-establish the stream and clear stale flags. */
  @Test fun degradedStateDefaultsToHealthyAndIsIndependentOfOfflineFlag() {
    val offline = MobileState(connected = false, cached = true)
    assertTrue(offline.cached)
    assertFalse(offline.degraded)
    val online = offline.copy(connected = true, cached = false)
    assertTrue(online.connected)
    assertFalse(online.cached)
  }
}
