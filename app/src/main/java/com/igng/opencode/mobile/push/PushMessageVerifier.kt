package com.igng.opencode.mobile.push

import com.igng.opencode.mobile.core.Diagnostics
import com.igng.opencode.mobile.core.ServerStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticates companion FCM data messages with an HMAC-SHA256 over a canonical, unambiguous set of
 * fields, keyed by the per-profile `pluginSecret` that both the OpenCode plugin and the app hold.
 *
 * Version 2 (current) length-prefixes every signed field, signs the routing directory and device id,
 * and covers a timestamp so a captured message cannot be replayed indefinitely. Version 1 (legacy)
 * is still accepted so an app update does not silently drop every push during migration; it only
 * signs sessionId/serverId/phase/detail/title (A07).
 */
internal object PushMessageVerifier {
  private const val ALGORITHM = "HmacSHA256"
  private const val SIGNATURE_FIELD = "sig"
  private const val VERSION_FIELD = "version"
  // FCM stores and forwards: a notification can legitimately arrive hours after it was sent while the
  // device was offline, so the window only bounds indefinite replay rather than normal delivery delay.
  private const val MAX_AGE_MILLIS = 24 * 60 * 60 * 1000L
  private val SIGNED_FIELDS_V1 = listOf("sessionId", "serverId", "phase", "detail", "title")
  private val SIGNED_FIELDS_V2 = listOf("version", "sessionId", "serverId", "directory", "phase", "detail", "title", "deviceId", "ts")

  fun payloadV1(data: Map<String, String>): String = SIGNED_FIELDS_V1.joinToString("\n") { data[it].orEmpty() }

  /** Must stay byte-identical to the companion's `signPushPayload`. */
  fun payload(data: Map<String, String>): String {
    val fields = SIGNED_FIELDS_V2.map { data[it].orEmpty() }
    return "2|" + fields.joinToString("|") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }
  }

  fun sign(secret: String, data: Map<String, String>): String = hmac(secret, payload(data))

  fun signV1(secret: String, data: Map<String, String>): String = hmac(secret, payloadV1(data))

  private fun hmac(secret: String, canonical: String): String {
    val mac = Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), ALGORITHM)) }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(canonical.toByteArray(Charsets.UTF_8)))
  }

  fun verify(secret: String, data: Map<String, String>): Boolean {
    if (secret.isBlank()) return true
    val provided = data[SIGNATURE_FIELD] ?: return false
    val v2 = data[VERSION_FIELD] == "2"
    val expected = runCatching { if (v2) sign(secret, data) else signV1(secret, data) }.getOrNull() ?: return false
    if (!MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), provided.toByteArray(Charsets.UTF_8))) return false
    // v2 also enforces freshness so a captured-but-valid message cannot be replayed later.
    if (v2 && !isFresh(data)) return false
    return true
  }

  fun verify(store: ServerStore, profileId: String, data: Map<String, String>): Boolean {
    val secret = store.pluginSecret(profileId)
    if (secret.isBlank()) return true
    if (data[SIGNATURE_FIELD].isNullOrBlank()) {
      Diagnostics.warn("Push", "缺少签名，忽略推送消息")
      return false
    }
    return verify(secret, data)
  }

  private fun isFresh(data: Map<String, String>): Boolean {
    val ts = data["ts"]?.toLongOrNull() ?: return false
    return kotlin.math.abs(System.currentTimeMillis() - ts) <= MAX_AGE_MILLIS
  }
}
