package com.igng.opencode.lagoon.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ApiCapabilitiesTest {
  @Test fun documentedNativeForkAndPromptEnvelopeAreDetectedIndependently() {
    val document = JSONObject("""{"paths":{
      "/api/session/{sessionID}/fork":{"post":{"requestBody":{"content":{"application/json":{"schema":{"type":"object","properties":{"messageID":{"type":"string"}}}}}}}},
      "/api/session/{sessionID}/prompt":{"post":{"requestBody":{"content":{"application/json":{"schema":{"type":"object","properties":{"prompt":{"${'$'}ref":"#/components/schemas/Prompt"}},"required":["prompt"]}}}}}}
    },"components":{"schemas":{"Prompt":{"type":"object","properties":{"text":{"type":"string"},"files":{"type":"array","items":{"type":"object","properties":{"uri":{"type":"string"},"mime":{"type":"string"}}}}}}}}}""")
    val caps = ApiCapabilities.fromDocument(ServerProtocol.V2, document, true)
    assertTrue(caps.supports(SessionAction.FORK)); assertFalse(caps.supports(SessionAction.RENAME))
    assertTrue(caps.promptEnvelope); assertTrue(caps.fileReferences); assertEquals("uri", caps.fileUriField)
  }
  @Test fun unknownRequiredWriteFieldsKeepTheActionUnavailable() {
    val doc = JSONObject("""{"paths":{"/api/session/{id}/fork":{"post":{"requestBody":{"content":{"application/json":{"schema":{"type":"object","required":["unknown"]}}}}}}}}""")
    assertFalse(ApiCapabilities.fromDocument(ServerProtocol.V2, doc).supports(SessionAction.FORK))
  }
}
