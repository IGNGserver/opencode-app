package com.igng.opencode.mobile.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
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
    .build()
  fun available(): Boolean = FirebaseApp.getApps(context).isNotEmpty()
  fun enableFor(profile: ServerProfile, password: String, deviceId: String) = enableFor(profile, ServerCredentials(profile.username, password), deviceId)
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
  suspend fun register(profile: ServerProfile, password: String, token: String, deviceId: String) =
    register(profile, ServerCredentials(profile.username, password), token, deviceId)
  suspend fun register(profile: ServerProfile, credentials: ServerCredentials, token: String, deviceId: String) = withContext(Dispatchers.IO) {
    if (profile.companionUrl.isBlank()) return@withContext
    val endpoint = URI(profile.companionUrl.trimEnd('/') + "/v1/devices")
    require(endpoint.scheme == "https" || endpoint.scheme == "http" && endpoint.host in setOf("localhost", "127.0.0.1")) {
      "推送伴随服务需要 HTTPS 地址"
    }
    val serverKey = profile.url.trimEnd('/')
    val body = JSONObject()
      .put("deviceId", deviceId)
      .put("serverId", profile.id)
      .put("profileId", profile.id)
      .put("serverKey", serverKey)
      .put("token", token)
      .toString()
      .toRequestBody("application/json".toMediaType())
    val requestBuilder = Request.Builder().url(endpoint.toString()).post(body)
    if (credentials.password.isNotBlank()) {
      requestBuilder.header("Authorization", Credentials.basic(credentials.username.ifBlank { profile.username.ifBlank { "opencode" } }, credentials.password))
    }
    if (credentials.cookie.isNotBlank()) requestBuilder.header("Cookie", credentials.cookie)
    val request = requestBuilder.build()
    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) error("推送设备注册失败：HTTP ${response.code}")
    }
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
    val profile = ServerStore(this).profiles().firstOrNull { it.id == data["serverId"] } ?: return
    if (!profile.notifications) return
    if (!PushMessageVerifier.verify(ServerStore(this), profile.id, data)) return
    val sessionId = data["sessionId"] ?: return
    val phase = runCatching { TaskPhase.valueOf(data["phase"] ?: "") }.getOrNull() ?: return
    val session = Session(sessionId, data["directory"].orEmpty(), data["title"].orEmpty().ifBlank { "OpenCode 任务" }, 0)
    TaskNotifications(this).show(profile, session, TaskState(sessionId, phase, data["detail"].orEmpty()))
  }
}
