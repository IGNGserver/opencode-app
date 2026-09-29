package com.igng.opencode.mobile.push

import com.igng.opencode.mobile.core.Diagnostics
import com.igng.opencode.mobile.core.ServerStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Strict v3 push verification. Companion upgrades and an independent push key are required.
 * Device matching and persistent sequence acceptance are part of verification, before display. */
internal object PushMessageVerifier {
  private const val ALGORITHM = "HmacSHA256"
  private const val SIGNATURE_FIELD = "sig"
  private const val VERSION_FIELD = "version"
  // FCM stores and forwards: a notification can legitimately arrive hours after it was sent while the
  // device was offline, so the window only bounds indefinite replay rather than normal delivery delay.
  private const val MAX_AGE_MILLIS = 24 * 60 * 60 * 1000L
  private val SIGNED_FIELDS_V1 = listOf("sessionId", "serverId", "phase", "detail", "title")
  private val SIGNED_FIELDS_V3 = listOf("version", "sessionId", "serverId", "directory", "phase", "detail", "title", "deviceId", "ts", "sequence")

  fun payloadV1(data: Map<String, String>): String = SIGNED_FIELDS_V1.joinToString("\n") { data[it].orEmpty() }

  /** Must stay byte-identical to the companion's `signPushPayload`. */
  fun payload(data: Map<String, String>): String {
    val fields = SIGNED_FIELDS_V3.map { data[it].orEmpty() }
    return "3|" + fields.joinToString("|") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }
  }

  fun sign(secret: String, data: Map<String, String>): String = hmac(secret, payload(data))

  fun signV1(secret: String, data: Map<String, String>): String = hmac(secret, payloadV1(data))

  private fun hmac(secret: String, canonical: String): String {
    val mac = Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), ALGORITHM)) }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(canonical.toByteArray(Charsets.UTF_8)))
  }

  fun verify(secret: String, data: Map<String, String>): Boolean {
    if (secret.isBlank() || data[VERSION_FIELD] != "3" || data["sessionId"].isNullOrBlank() || data["serverId"].isNullOrBlank() || data["deviceId"].isNullOrBlank()) return false
    if ((data["sequence"]?.toLongOrNull() ?: 0L) <= 0L) return false
    val provided = data[SIGNATURE_FIELD] ?: return false
    val expected = runCatching { sign(secret, data) }.getOrNull() ?: return false
    return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), provided.toByteArray(Charsets.UTF_8)) && isFresh(data)
  }
  fun verify(store: ServerStore, profileId: String, data: Map<String, String>): Boolean {
    if (data["serverId"] != profileId || data["deviceId"] != store.deviceId()) return false
    if (!verify(store.pluginSecret(profileId), data)) return false
    if (runCatching { com.igng.opencode.mobile.core.TaskPhase.valueOf(data["phase"].orEmpty()) }.isFailure) return false
    return store.acceptPush(profileId, data.getValue("sessionId"), data.getValue("sequence").toLong(), data.getValue("ts").toLong())
  }

  private fun isFresh(data: Map<String, String>): Boolean {
    val ts = data["ts"]?.toLongOrNull() ?: return false
    val now = System.currentTimeMillis()
    return ts >= now - MAX_AGE_MILLIS && ts <= now + 60_000L
  }
}
