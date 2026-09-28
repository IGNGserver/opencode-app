package com.igng.opencode.mobile.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.igng.opencode.mobile.core.Diagnostics
import com.igng.opencode.mobile.core.MobileController

/**
 * Handles the notification actions (abort / permission reply) through [MobileController] so the
 * in-app state is updated immediately instead of waiting for the next SSE refresh.
 *
 * The controller posts to its own scope, which stays inside the short lifetime Android grants a
 * BroadcastReceiver, so `goAsync` is not needed here.
 */
class NotificationActionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val controller = runCatching { MobileController.get(context.applicationContext) }.getOrElse {
      Diagnostics.warn("NotificationAction", "无法获取控制器，忽略操作", it)
      return
    }
    val serverId = intent.getStringExtra("serverId") ?: return
    val sessionId = intent.getStringExtra("sessionId") ?: return
    val directory = intent.getStringExtra("directory") ?: return
    if (controller.state.value.serverId != serverId) controller.connect(serverId)
    when (intent.action) {
      "abort" -> controller.abortSession(sessionId)
      "reject", "once", "always" -> {
        val permissionId = intent.getStringExtra("permissionId") ?: return
        val permissionDirectory = intent.getStringExtra("permissionDirectory") ?: directory
        controller.replyPermission(permissionId, sessionId, permissionDirectory, intent.action!!)
      }
    }
    TaskNotifications(context).cancel(serverId, sessionId)
  }
}
