package com.igng.opencode.mobile.system

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.igng.opencode.mobile.R
import com.igng.opencode.mobile.core.PermissionRequest
import com.igng.opencode.mobile.core.ServerProfile
import com.igng.opencode.mobile.core.Session
import com.igng.opencode.mobile.core.TaskPhase
import com.igng.opencode.mobile.core.TaskState
import com.igng.opencode.mobile.ui.MainActivity
import org.json.JSONObject

class TaskNotifications(private val context: Context) {
  private val manager = context.getSystemService(NotificationManager::class.java)
  companion object {
    const val RUNNING = "task_running"
    const val ATTENTION = "task_attention"
    const val COMPLETED = "task_completed"
    fun notificationId(sessionId: String): Int = sessionId.hashCode() and 0x7fffffff
  }
  init {
    manager.createNotificationChannel(NotificationChannel(RUNNING, "正在运行", NotificationManager.IMPORTANCE_DEFAULT))
    manager.createNotificationChannel(NotificationChannel(ATTENTION, "需要处理", NotificationManager.IMPORTANCE_HIGH))
    manager.createNotificationChannel(NotificationChannel(COMPLETED, "任务结果", NotificationManager.IMPORTANCE_DEFAULT))
  }
  private fun allowed(): Boolean = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
  private fun open(serverId: String, sessionId: String): PendingIntent {
    val uri = Uri.parse("opencode-mobile://server/${Uri.encode(serverId)}/session/${Uri.encode(sessionId)}")
    val intent = Intent(Intent.ACTION_VIEW, uri, context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    return PendingIntent.getActivity(context, notificationId(sessionId), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }
  private fun action(name: String, profile: ServerProfile, session: Session, permission: PermissionRequest? = null): PendingIntent {
    val intent = Intent(context, NotificationActionReceiver::class.java).apply {
      this.action = name
      data = Uri.parse("opencode-mobile://action/${Uri.encode(profile.id)}/${Uri.encode(session.id)}/$name")
      putExtra("serverId", profile.id)
      putExtra("sessionId", session.id)
      putExtra("directory", session.directory)
      permission?.let { putExtra("permissionId", it.id); putExtra("permissionDirectory", it.directory) }
      addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
    }
    return PendingIntent.getBroadcast(context, notificationId(session.id) xor name.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }
  fun build(profile: ServerProfile, session: Session, state: TaskState, permission: PermissionRequest? = null): Notification {
    val waiting = state.phase in setOf(TaskPhase.WAITING_PERMISSION, TaskPhase.WAITING_QUESTION)
    val running = state.phase in setOf(TaskPhase.THINKING, TaskPhase.TOOL, TaskPhase.SUBAGENT, TaskPhase.TESTING)
    val channel = if (waiting) ATTENTION else if (running) RUNNING else COMPLETED
    val title = when (state.phase) {
      TaskPhase.COMPLETED -> "已完成 · ${session.title}"
      TaskPhase.FAILED -> "执行失败 · ${session.title}"
      TaskPhase.ABORTED -> "已停止 · ${session.title}"
      TaskPhase.WAITING_PERMISSION -> "需要授权 · ${session.title}"
      TaskPhase.WAITING_QUESTION -> "需要回答 · ${session.title}"
      else -> "运行中 · ${session.title}"
    }
    val builder = NotificationCompat.Builder(context, channel)
      .setSmallIcon(R.drawable.ic_app).setContentTitle(title).setContentText(state.detail)
      .setStyle(NotificationCompat.BigTextStyle().bigText(state.detail))
      .setContentIntent(open(profile.id, session.id)).setAutoCancel(!state.active)
      .setOnlyAlertOnce(running).setOngoing(running)
      .setCategory(if (waiting) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_PROGRESS)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
    if (running) {
      builder.setRequestPromotedOngoing(true)
      builder.addAction(0, "停止", action("abort", profile, session))
    }
    if (state.phase == TaskPhase.WAITING_PERMISSION && permission != null) {
      builder.addAction(0, "拒绝", action("reject", profile, session, permission))
      builder.addAction(0, "允许一次", action("once", profile, session, permission))
    }
    val notification = builder.build()
    XiaomiIslandAdapter(context).extend(notification, title, state.detail, running)
    return notification
  }
  fun show(profile: ServerProfile, session: Session, state: TaskState, permission: PermissionRequest? = null) {
    if (!allowed()) return
    if (state.phase == TaskPhase.IDLE || state.phase == TaskPhase.DISCONNECTED) return
    manager.notify(notificationId(session.id), build(profile, session, state, permission))
  }
  fun cancel(sessionId: String) = manager.cancel(notificationId(sessionId))
}

internal class XiaomiIslandAdapter(private val context: Context) {
  fun extend(notification: Notification, title: String, detail: String, running: Boolean) {
    val version = runCatching { Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0) }.getOrDefault(0)
    if (version < 3) return
    val iconKey = "miui.focus.pic_app"
    val pics = Bundle().apply { putParcelable(iconKey, Icon.createWithResource(context, R.drawable.ic_app)) }
    val parameters = JSONObject().put("param_v2", JSONObject()
      .put("protocol", 1).put("business", "app").put("updatable", running)
      .put("ticker", detail.take(32)).put("aodTitle", title.take(32))
      .put("param_island", JSONObject()
        .put("islandProperty", 1)
        .put("smallIslandArea", JSONObject().put("picInfo", JSONObject().put("type", 1).put("pic", iconKey)))
        .put("bigIslandArea", JSONObject()
          .put("imageTextInfoLeft", JSONObject().put("type", 1)
            .put("picInfo", JSONObject().put("type", 1).put("pic", iconKey))
            .put("miui.focus.paramtextInfo", JSONObject().put("frontTitle", "OpenCode")
              .put("title", title.take(24)).put("content", detail.take(32)))))))
    notification.extras.putBundle("miui.focus.pics", pics)
    notification.extras.putString("miui.focus.param", parameters.toString())
  }
}
