package com.igng.opencode.lagoon.core

import com.igng.opencode.lagoon.core.generated.Project as GProject
import com.igng.opencode.lagoon.core.generated.Session_Info as GSessionInfo
import com.igng.opencode.lagoon.core.generated.Session_Message_Info
import com.igng.opencode.lagoon.core.generated.Session_Message_Info_user
import com.igng.opencode.lagoon.core.generated.Session_Message_Info_assistant
import com.igng.opencode.lagoon.core.generated.Permission_Request as GPermissionRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生成类型（tools/gen_v2_models.py 从 openapi-v2.json 生成）的 spec-conformance 测试。
 * 作为 V2 契约 oracle：证明生成模型正确解析 OpenAPI schema 形状（含 anyOf union 判别分发），
 * 并与手写双协议解析（Models.kt）共享同一批样本以捕捉 spec 漂移。
 */
class V2WireTest {

  @Test fun generatedProjectReadsCanonical() {
    val p = GProject.fromJson(JSONObject("""{"id":"pro-1","canonical":"/repo","name":"repo","time":{"created":1,"updated":2},"sandboxes":[]}"""))
    assertEquals("pro-1", p.id)
    assertEquals("/repo", p.canonical)
    assertEquals(2L, (p.time?.updated ?: 0.0).toLong())
  }

  @Test fun generatedSessionUsesLocationDirectoryAndProjectId() {
    val s = GSessionInfo.fromJson(JSONObject("""{"id":"ses-1","projectID":"pro-1","location":{"directory":"/repo"},"time":{"created":1,"updated":2}}"""))
    assertEquals("pro-1", s.projectID)
    assertEquals("/repo", s.location?.directory)
    assertNull(s.title)                 // title 缺省即 null，不编造
  }

  @Test fun generatedMessageUnionDispatchesOnTypeDiscriminator() {
    val user = Session_Message_Info.fromJson(JSONObject("""{"id":"m","type":"user","time":{"created":1},"text":"hi"}"""))
    assertTrue("user branch", user is Session_Message_Info_user)
    val assistant = Session_Message_Info.fromJson(JSONObject("""{"id":"m","type":"assistant","time":{"created":1},"agent":"a","model":{"providerID":"p","id":"i"},"content":[]}"""))
    assertTrue("assistant branch", assistant is Session_Message_Info_assistant)
    // 未知判别值 → null（不猜测、不静默造类型）
    assertNull(Session_Message_Info.fromJson(JSONObject("""{"id":"m","type":"future-variant"}""")))
  }

  @Test fun generatedPermissionReadsActionResourcesSave() {
    val p = GPermissionRequest.fromJson(JSONObject("""{"id":"per-1","sessionID":"ses-1","action":"bash","resources":["a.txt"],"save":["always"]}"""))
    assertEquals("bash", p.action)
    assertEquals(listOf("a.txt"), p.resources)
    assertEquals(listOf("always"), p.save)
  }
}
