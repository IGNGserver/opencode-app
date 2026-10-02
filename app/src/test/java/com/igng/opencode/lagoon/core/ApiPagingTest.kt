package com.igng.opencode.lagoon.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ApiPagingTest {
  private fun api(server:MockWebServer)=OpenCodeApi(ServerProfile("server","Server",server.url("/").toString(),allowCleartext=true), "fixture")
  @Test fun blankV1TitleIsOmittedSoTheServerCanNameTheSession()=runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setBody("""{"healthy":true}"""))
      server.enqueue(MockResponse().setBody("""{"id":"s","directory":"/repo","title":"New session - 2026-10-03T00:00:00Z","time":{}}"""))
      val client=api(server);client.health();client.createSession("/repo", " ")
      server.takeRequest();val request=server.takeRequest()
      assertFalse(JSONObject(request.body.readUtf8()).has("title"))
      assertEquals("/repo",request.requestUrl!!.queryParameter("directory"))
    }
  }
  @Test fun nativePagesSendOrderOnlyOnFirstRequestAndKeepMessagesChronological()=runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse().setResponseCode(404));server.enqueue(MockResponse().setBody("""{"healthy":true}"""))
      server.enqueue(MockResponse().setBody("""{"data":[{"id":"s","location":{"directory":"/repo"},"time":{}}],"cursor":{"next":"session-next"}}"""))
      server.enqueue(MockResponse().setBody("""{"data":[],"cursor":{}}"""))
      server.enqueue(MockResponse().setBody("""{"data":[{"id":"m2","type":"user","time":{"created":2},"text":"second"},{"id":"m1","type":"user","time":{"created":1},"text":"first"}],"cursor":{"next":"message-next"}}"""))
      server.enqueue(MockResponse().setBody("""{"data":[],"cursor":{}}"""))
      val client=api(server);client.health()
      val sessions=client.sessionsPage("/repo");assertEquals("session-next",sessions.next)
      client.sessionsPage("/repo",sessions.next)
      val messages=client.messagesPage("s","/repo");assertEquals(listOf("m1","m2"),messages.items.map { it.id })
      client.messagesPage("s","/repo",messages.next)
      val requests=List(6) { server.takeRequest() }
      assertEquals("desc",requests[2].requestUrl!!.queryParameter("order"))
      assertNull(requests[3].requestUrl!!.queryParameter("order"));assertEquals("session-next",requests[3].requestUrl!!.queryParameter("cursor"))
      assertEquals("desc",requests[4].requestUrl!!.queryParameter("order"))
      assertNull(requests[5].requestUrl!!.queryParameter("order"));assertEquals("message-next",requests[5].requestUrl!!.queryParameter("cursor"))
    }
  }
}
