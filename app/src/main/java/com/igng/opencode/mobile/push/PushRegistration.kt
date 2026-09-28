package com.igng.opencode.mobile.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.igng.opencode.mobile.core.ServerProfile
import com.igng.opencode.mobile.core.ServerStore
import com.igng.opencode.mobile.core.Session
import com.igng.opencode.mobile.core.TaskPhase
import com.igng.opencode.mobile.core.TaskState
import com.igng.opencode.mobile.system.TaskNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI

class PushRegistration(private val context: Context) {
  fun available(): Boolean = FirebaseApp.getApps(context).isNotEmpty()
  fun enableFor(profile: ServerProfile, password: String, deviceId: String) {
    if (!available() || profile.companionUrl.isBlank() || !profile.notifications) return
    FirebaseMessaging.getInstance().isAutoInitEnabled = true
    FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
      CoroutineScope(Dispatchers.IO).launch { runCatching { register(profile, password, token, deviceId) } }
    }
  }
  fun register(profile: ServerProfile, password: String, token: String, deviceId: String) {
    if (profile.companionUrl.isBlank()) return
    val endpoint = URI(profile.companionUrl.trimEnd('/') + "/v1/devices")
    require(endpoint.scheme == "https" || endpoint.scheme == "http" && endpoint.host in setOf("localhost", "127.0.0.1")) {
      "推送伴随服务需要 HTTPS 地址"
    }
    val body = JSONObject().put("deviceId", deviceId).put("serverId", profile.id).put("token", token).toString()
      .toRequestBody("application/json".toMediaType())
    val request = Request.Builder().url(endpoint.toString()).post(body)
      .header("Authorization", Credentials.basic(profile.username.ifBlank { "opencode" }, password)).build()
    OkHttpClient().newCall(request).execute().use { response ->
      if (!response.isSuccessful) error("推送设备注册失败：HTTP ${response.code}")
    }
  }
}

class MobileMessagingService : FirebaseMessagingService() {
  override fun onNewToken(token: String) {
    val store = ServerStore(this)
    CoroutineScope(Dispatchers.IO).launch {
      store.profiles().filter { it.notifications && it.companionUrl.isNotBlank() }.forEach { profile ->
        runCatching { PushRegistration(this@MobileMessagingService).register(profile, store.password(profile.id), token, store.deviceId()) }
      }
    }
  }
  override fun onMessageReceived(message: RemoteMessage) {
    val data = message.data
    val profile = ServerStore(this).profiles().firstOrNull { it.id == data["serverId"] } ?: return
    if (!profile.notifications) return
    val sessionId = data["sessionId"] ?: return
    val phase = runCatching { TaskPhase.valueOf(data["phase"] ?: "") }.getOrNull() ?: return
    val session = Session(sessionId, data["directory"].orEmpty(), data["title"].orEmpty().ifBlank { "OpenCode 任务" }, 0)
    TaskNotifications(this).show(profile, session, TaskState(sessionId, phase, data["detail"].orEmpty()))
  }
}
