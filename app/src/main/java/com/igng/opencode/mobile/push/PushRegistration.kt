package com.igng.opencode.mobile.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.igng.opencode.mobile.core.ApiException
import com.igng.opencode.mobile.core.HttpOrigin
import com.igng.opencode.mobile.core.ServerCredentials
import com.igng.opencode.mobile.core.Diagnostics
import com.igng.opencode.mobile.core.ServerProfile
import com.igng.opencode.mobile.core.ServerStore
import com.igng.opencode.mobile.core.Session
import com.igng.opencode.mobile.core.SharedHttp
import com.igng.opencode.mobile.core.TaskPhase
import com.igng.opencode.mobile.core.TaskState
import com.igng.opencode.mobile.system.TaskNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

class PushRegistration(private val context: Context) {
  private val client = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(12, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS)
    .connectionPool(SharedHttp.connectionPool)
    .dispatcher(SharedHttp.dispatcher)
    // The device is registered with the same Basic/Cookie credentials as the server profile; a
    // redirect must never carry them to a different origin.
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

  fun available(): Boolean = FirebaseApp.getApps(context).isNotEmpty()

  fun enableFor(profile: ServerProfile, credentials: ServerCredentials, deviceId: String) {
    if (!available() || profile.companionUrl.isBlank() || !profile.notifications) return
    FirebaseMessaging.getInstance().isAutoInitEnabled = true
    FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
      CoroutineScope(Dispatchers.IO).launch {
        try { register(profile, credentials, token, deviceId) } catch (error: Exception) {
          Diagnostics.warn("Push", "自动注册设备失败", error)
        }
      }
    }
  }

  suspend fun register(profile: ServerProfile, credentials: ServerCredentials, token: String, deviceId: String) = withContext(Dispatchers.IO) {
    require(profile.companionUrl.isNotBlank()) { "未配置推送伴随服务" }
    val body = JSONObject()
      .put("deviceId", deviceId)
      .put("serverId", profile.id)
      .put("profileId", profile.id)
      .put("serverKey", profile.url.trimEnd('/'))
      .put("token", token)
      .toString()
      .toRequestBody(JSON)
    post(profile, credentials, "v1/devices", body, "推送设备注册失败")
  }

  /** Withdraws this device from the companion so a deleted profile stops receiving pushes before its
   *  TTL expires (A08). Best-effort: a companion without the route returns 404/405 and is ignored. */
  suspend fun unregister(profile: ServerProfile, credentials: ServerCredentials, deviceId: String) = withContext(Dispatchers.IO) {
    if (profile.companionUrl.isBlank()) return@withContext
    val body = JSONObject()
      .put("deviceId", deviceId)
      .put("serverKey", profile.url.trimEnd('/'))
      .toString()
      .toRequestBody(JSON)
    try {
      post(profile, credentials, "v1/devices/unregister", body, "推送设备注销失败")
    } catch (error: ApiException) {
      if (error.status != 404 && error.status != 405) throw error
    }
  }

  private fun post(profile: ServerProfile, credentials: ServerCredentials, path: String, body: RequestBody, failure: String) {
    val endpoint = URI(profile.companionUrl.trimEnd('/') + "/$path")
    require(endpoint.scheme == "https" || endpoint.scheme == "http" && HttpOrigin.allowsCleartext(endpoint.host)) {
      "推送伴随服务需要 HTTPS 地址"
    }
    val origin = requireNotNull(endpoint.toString().toHttpUrlOrNull()) { "推送伴随服务地址无效" }.let(HttpOrigin::of)
    val builder = Request.Builder().url(endpoint.toString()).post(body)
    if (credentials.password.isNotBlank()) {
      builder.header("Authorization", Credentials.basic(credentials.username.ifBlank { profile.username.ifBlank { "opencode" } }, credentials.password))
    }
    if (credentials.cookie.isNotBlank()) builder.header("Cookie", credentials.cookie)
    val request = builder.build()
    require(HttpOrigin.of(request.url) == origin) { "拒绝向非授权地址发送凭据" }
    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw ApiException(response.code, "$failure：HTTP ${response.code}")
    }
  }

  private companion object {
    val JSON = "application/json".toMediaType()
  }
}

class MobileMessagingService : FirebaseMessagingService() {
  override fun onNewToken(token: String) {
    val store = ServerStore(this)
    CoroutineScope(Dispatchers.IO).launch {
      store.profiles().filter { it.notifications && it.companionUrl.isNotBlank() }.forEach { profile ->
        try {
          PushRegistration(this@MobileMessagingService).register(profile, store.credentials(profile.id), token, store.deviceId())
        } catch (error: Exception) {
          Diagnostics.warn("Push", "刷新设备 Token 失败", error)
        }
      }
    }
  }

  override fun onMessageReceived(message: RemoteMessage) {
    val data = message.data
    val store = ServerStore(this)
    val profile = store.profiles().firstOrNull { it.id == data["serverId"] } ?: return
    if (!profile.notifications) return
    if (!PushMessageVerifier.verify(store, profile.id, data)) return
    val sessionId = data["sessionId"] ?: return
    val phase = runCatching { TaskPhase.valueOf(data["phase"] ?: "") }.getOrNull() ?: return
    val session = Session(sessionId, data["directory"].orEmpty(), data["title"].orEmpty().ifBlank { "OpenCode 任务" }, 0)
    val state = TaskState(sessionId, phase, data["detail"].orEmpty())
    // Push notifications claim a separate id namespace so a server-delivered completion cannot be
    // cancelled by the foreground service's per-session notification (and vice versa).
    TaskNotifications(this).showPush(TaskNotifications.pushNotificationId(profile.id, sessionId), profile, session, state)
  }
}
