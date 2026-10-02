package com.igng.opencode.lagoon.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.igng.opencode.lagoon.core.ApiException
import com.igng.opencode.lagoon.core.HttpOrigin
import com.igng.opencode.lagoon.core.ServerCredentials
import com.igng.opencode.lagoon.core.Diagnostics
import com.igng.opencode.lagoon.core.ServerProfile
import com.igng.opencode.lagoon.core.ServerStore
import com.igng.opencode.lagoon.core.Session
import com.igng.opencode.lagoon.core.SharedHttp
import com.igng.opencode.lagoon.core.TaskPhase
import com.igng.opencode.lagoon.core.TaskState
import com.igng.opencode.lagoon.core.TaskSummary
import com.igng.opencode.lagoon.system.TaskNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
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
    PushRevocations.serial.withLock {
      check(PushRevocations(context).flushLocked(profile)) { "旧注册尚未撤销，稍后重试" }
      // A Firebase callback may have arrived after deletion or a configuration edit.
      if (ServerStore(context).profiles().none { it == profile && it.notifications }) return@withLock
      require(profile.pluginSecret.isNotBlank()) { "请先配置独立的推送验证密钥" }
      post(profile, credentials, "v1/devices", body, "推送设备注册失败")
    }
  }

  /** Withdraws this device from the companion so a deleted profile stops receiving pushes before its
   *  TTL expires (A08). Any failure remains in the durable withdrawal queue for retry. */
  suspend fun unregister(profile: ServerProfile, credentials: ServerCredentials, deviceId: String) = withContext(Dispatchers.IO) {
    if (profile.companionUrl.isBlank()) return@withContext
    val body = JSONObject()
      .put("deviceId", deviceId)
      .put("serverKey", profile.url.trimEnd('/'))
      .toString()
      .toRequestBody(JSON)
    post(profile, credentials, "v1/devices/unregister", body, "推送设备注销失败")
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

class LagoonMessagingService : FirebaseMessagingService() {
  override fun onNewToken(token: String) {
    val store = ServerStore(this)
    CoroutineScope(Dispatchers.IO).launch {
      store.profiles().filter { it.notifications && it.companionUrl.isNotBlank() }.forEach { profile ->
        try {
          PushRegistration(this@LagoonMessagingService).register(profile, store.credentials(profile.id), token, store.deviceId())
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
    val timestamp = data["ts"]?.toLongOrNull() ?: System.currentTimeMillis()
    val previous = store.taskStates(profile.id)[sessionId]
    val since = if (phase in TaskState.RUNNING_PHASES && previous?.active == false) timestamp else previous?.since ?: timestamp
    val state = store.rememberTask(profile.id, TaskState(sessionId, phase, data["detail"].orEmpty(), since,
      timestamp.takeIf { phase in setOf(TaskPhase.COMPLETED, TaskPhase.FAILED, TaskPhase.ABORTED) }), observedAt = timestamp, durable = true)
    val notifications = TaskNotifications(this)
    notifications.show(profile, session, state)
    val summary = TaskSummary.of(store.taskStates(profile.id), store.acknowledgedTasks(profile.id), store.taskParents(profile.id))
    notifications.showSummary(profile, summary, if (phase in TaskState.WAITING_PHASES) sessionId else null)
  }
}
