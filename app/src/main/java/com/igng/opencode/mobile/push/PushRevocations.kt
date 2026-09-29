package com.igng.opencode.mobile.push

import android.content.Context
import com.igng.opencode.mobile.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

/** Durable encrypted withdrawal intents survive profile/credential deletion and process restart. */
internal class PushRevocations(private val context: Context) {
  private val preferences = context.getSharedPreferences("push_revocations", Context.MODE_PRIVATE)
  private val cipher = KeystoreCipher("opencode-mobile-push-revocations")
  fun enqueue(profile: ServerProfile, credentials: ServerCredentials, deviceId: String) {
    if (profile.companionUrl.isBlank()) return
    val data = JSONObject().put("id", profile.id).put("url", profile.url).put("companion", profile.companionUrl)
      .put("username", credentials.username).put("password", credentials.password).put("cookie", credentials.cookie).put("device", deviceId)
    check(preferences.edit().putString(UUID.randomUUID().toString(), cipher.encrypt(data.toString())).commit()) { "无法保存设备注销请求，请重试" }
    retry()
  }
  /** Caller owns serial, shared with registration so a late revoke cannot delete a new registration. */
  suspend fun flushLocked(target: ServerProfile? = null): Boolean {
    var complete = true
    for ((id, encoded) in preferences.all) {
      try {
        val data = JSONObject(cipher.decrypt(encoded as String))
        if (target != null && (target.url.trimEnd('/') != data.str("url").trimEnd('/') || target.companionUrl.trimEnd('/') != data.str("companion").trimEnd('/'))) continue
        val profile = ServerProfile(data.str("id"), "", data.str("url"), companionUrl = data.str("companion"))
        PushRegistration(context).unregister(profile, ServerCredentials(data.str("username"), data.str("password"), data.str("cookie")), data.str("device"))
        check(preferences.edit().remove(id).commit())
      } catch (cancel: CancellationException) { throw cancel }
      catch (error: Exception) { complete = false; Diagnostics.warn("Push", "设备注销待重试", error) }
    }
    return complete
  }
  fun retry() = synchronized(jobLock) {
    if (retryJob?.isActive == true) return@synchronized
    retryJob = scope.launch {
      var backoff = 2_000L
      while (isActive) {
        serial.withLock { flushLocked() }
        synchronized(jobLock) { if (preferences.all.isEmpty()) { retryJob = null; return@launch } }
        delay(backoff); backoff = (backoff * 2).coerceAtMost(60_000L)
      }
    }
  }
  companion object {
    val serial = Mutex()
    private val jobLock = Any()
    private var retryJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  }
}
