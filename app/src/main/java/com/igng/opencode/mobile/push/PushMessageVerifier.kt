package com.igng.opencode.mobile.push

import com.igng.opencode.mobile.core.Diagnostics
import com.igng.opencode.mobile.core.ServerStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticates companion FCM data messages with an HMAC-SHA256 over their canonical fields, keyed by
 * the per-profile `pluginSecret` that both the OpenCode plugin and the app already hold.
 *
 * This prevents a party that can inject into the Firebase project (leaked FCM credential, over-broad
 * IAM, or a stolen registration token) from spoofing task notifications. Messages that cannot be
 * authenticated are ignored; if no secret is configured for the profile the check is skipped so that
 * existing setups keep working (the companion must be updated to send the signature).
 */
internal object PushMessageVerifier {
  private const val ALGORITHM = "HmacSHA256"
  private const val SIGNATURE_FIELD = "sig"
  private val SIGNED_FIELDS = listOf("sessionId", "serverId", "phase", "detail", "title")

  /** Canonical signed payload; must stay byte-identical to the companion's `signPushPayload`. */
  fun payload(data: Map<String, String>): String = SIGNED_FIELDS.joinToString("\n") { data[it].orEmpty() }

  fun sign(secret: String, data: Map<String, String>): String {
    val mac = Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), ALGORITHM)) }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload(data).toByteArray(Charsets.UTF_8)))
  }

  fun verify(secret: String, data: Map<String, String>): Boolean {
    if (secret.isBlank()) return true
    val provided = data[SIGNATURE_FIELD] ?: return false
    val expected = runCatching { sign(secret, data) }.getOrNull() ?: return false
    // Constant-time comparison.
    return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), provided.toByteArray(Charsets.UTF_8))
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
}
