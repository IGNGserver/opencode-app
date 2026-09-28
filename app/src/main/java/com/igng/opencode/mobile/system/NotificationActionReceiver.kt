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
  companion object {
    /** goAsync() only extends the receiver for ~10s; keep the whole reply inside that budget and
     *  retry once on a fresh connection so a slow/hung server cannot silently drop the decision. */
    private const val ACTION_TIMEOUT_SECONDS = 8L
  }
  override fun onReceive(context: Context, intent: Intent) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val store = ServerStore(context)
        val serverId = intent.getStringExtra("serverId") ?: return@launch
        val sessionId = intent.getStringExtra("sessionId") ?: return@launch
        val directory = intent.getStringExtra("directory") ?: return@launch
        val profile = store.profiles().firstOrNull { it.id == serverId } ?: return@launch
        val action = intent.action
        val send: suspend (OpenCodeApi) -> Unit = { client ->
          when (action) {
            "abort" -> client.abort(Session(sessionId, directory, "", 0))
            "reject", "once", "always" -> {
              val permissionId = intent.getStringExtra("permissionId") ?: error("缺少权限 ID")
              val permissionDirectory = intent.getStringExtra("permissionDirectory") ?: directory
              client.replyPermission(PermissionRequest(permissionId, sessionId, permissionDirectory, "", ""), action)
            }
            else -> Unit
          }
        }
        try {
          send(OpenCodeApi(profile, store.credentials(serverId), callTimeoutSeconds = ACTION_TIMEOUT_SECONDS))
          TaskNotifications(context).cancel(serverId, sessionId)
        } catch (_: Exception) {
          // A fresh OpenCodeApi rebuilds the connection; the server dedupes identical permission replies.
          runCatching { send(OpenCodeApi(profile, store.credentials(serverId), callTimeoutSeconds = ACTION_TIMEOUT_SECONDS)) }
          TaskNotifications(context).cancel(serverId, sessionId)
        }
      } catch (_: Exception) { /* UI and SSE show the request again if server rejected action. */ }
      finally { pending.finish() }
    }
  }
}
