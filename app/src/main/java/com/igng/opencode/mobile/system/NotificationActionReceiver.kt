package com.igng.opencode.mobile.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.igng.opencode.mobile.core.OpenCodeApi
import com.igng.opencode.mobile.core.PermissionRequest
import com.igng.opencode.mobile.core.ServerStore
import com.igng.opencode.mobile.core.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationActionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val store = ServerStore(context)
        val serverId = intent.getStringExtra("serverId") ?: return@launch
        val sessionId = intent.getStringExtra("sessionId") ?: return@launch
        val directory = intent.getStringExtra("directory") ?: return@launch
        val profile = store.profiles().firstOrNull { it.id == serverId } ?: return@launch
        val client = OpenCodeApi(profile, store.password(serverId))
        when (intent.action) {
          "abort" -> client.abort(Session(sessionId, directory, "", 0))
          "reject", "once" -> {
            val permissionId = intent.getStringExtra("permissionId") ?: return@launch
            val permissionDirectory = intent.getStringExtra("permissionDirectory") ?: directory
            client.replyPermission(PermissionRequest(permissionId, sessionId, permissionDirectory, "", ""), intent.action!!)
          }
        }
        TaskNotifications(context).cancel(sessionId)
      } catch (_: Exception) { /* UI and SSE show the request again if server rejected action. */ }
      finally { pending.finish() }
    }
  }
}
