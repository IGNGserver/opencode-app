package com.igng.opencode.mobile.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.igng.opencode.mobile.core.Diagnostics
import com.igng.opencode.mobile.core.MobileController
import com.igng.opencode.mobile.core.OpenCodeApi
import com.igng.opencode.mobile.core.PermissionRequest
import com.igng.opencode.mobile.core.ServerStore
import com.igng.opencode.mobile.core.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Handles notification actions (abort / permission reply).
 *
 * These commands must not depend on the in-app connection having survived: a notification can be
 * tapped on a cold start, when auto-connect is off, or for a profile that is no longer selected.
 * When the asked-about server is already the connected one we route through [MobileController] so the
 * UI stays in sync; otherwise we build a short-lived client for that exact profile and act on it
 * without disturbing whatever connection the UI currently holds. The notification is only dismissed
 * once the server actually accepted the decision.
 */
class NotificationActionReceiver : BroadcastReceiver() {
  companion object {
    /** goAsync() extends the receiver for ~10s; keep the whole action inside that budget. */
    private const val ACTION_TIMEOUT_MILLIS = 8_000L
  }

  override fun onReceive(context: Context, intent: Intent) {
    val pending = goAsync()
    val appContext = context.applicationContext
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
      try {
        val serverId = intent.getStringExtra("serverId") ?: return@launch
        val sessionId = intent.getStringExtra("sessionId") ?: return@launch
        val directory = intent.getStringExtra("directory") ?: return@launch
        val action = intent.action ?: return@launch
        val permissionId = intent.getStringExtra("permissionId")
        val permissionDirectory = intent.getStringExtra("permissionDirectory") ?: directory
        val succeeded = withTimeoutOrNull(ACTION_TIMEOUT_MILLIS) {
          runAction(appContext, serverId, sessionId, directory, action, permissionId, permissionDirectory)
        } ?: false
        if (succeeded) TaskNotifications(appContext).cancel(serverId, sessionId)
        else Diagnostics.warn("NotificationAction", "操作未在时限内确认，保留通知以便重试：$action")
      } catch (error: Exception) {
        Diagnostics.warn("NotificationAction", "通知操作失败", error)
      } finally {
        pending.finish()
      }
    }
  }

  private suspend fun runAction(
    context: Context,
    serverId: String,
    sessionId: String,
    directory: String,
    action: String,
    permissionId: String?,
    permissionDirectory: String
  ): Boolean {
    val store = ServerStore(context)
    val profile = store.profiles().firstOrNull { it.id == serverId } ?: return false
    val controller = runCatching { MobileController.get(context) }.getOrNull()
    // Only reuse the live controller when it is genuinely connected to this exact server; otherwise a
    // dedicated client carries the full target and cannot be rerouted by a concurrent server switch,
    // and works on a cold start or with auto-connect disabled.
    val onCurrentConnection = controller?.state?.value?.serverId == serverId && controller.state.value.connected
    // A dedicated client is used when this is not the live connection: it carries the full target
    // (server + directory + session) and cannot be rerouted by a concurrent server switch.
    val client = if (onCurrentConnection) null else OpenCodeApi(profile, store.credentials(serverId), callTimeoutSeconds = 8)
    return try {
      when (action) {
        "abort" -> if (client != null) client.abort(Session(sessionId, directory, "", 0)) else controller!!.abortSessionNow(sessionId, directory)
        "reject", "once", "always" -> {
          val request = PermissionRequest(permissionId ?: error("缺少权限 ID"), sessionId, permissionDirectory, "", "")
          if (client != null) client.replyPermission(request, action)
          else controller!!.replyPermissionNow(permissionId!!, sessionId, permissionDirectory, action)
        }
        else -> return false
      }
      true
    } catch (error: Exception) {
      Diagnostics.warn("NotificationAction", "服务器未确认通知操作：$action", error)
      false
    }
  }
}
