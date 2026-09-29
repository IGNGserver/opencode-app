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
    fun notificationId(serverId: String, sessionId: String): Int = "${serverId}:$sessionId".hashCode() and 0x7fffffff
    /** Separate id space for server-pushed notifications so they never cancel an in-app one. */
    fun pushNotificationId(serverId: String, sessionId: String): Int = "push:$serverId:$sessionId".hashCode() and 0x7fffffff
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
    return PendingIntent.getActivity(context, notificationId(serverId, sessionId), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }
  private fun action(name: String, profile: ServerProfile, session: Session, permission: PermissionRequest? = null): PendingIntent {
    val intent = Intent(context, NotificationActionReceiver::class.java).apply {
      this.action = name
      data = Uri.parse("opencode-mobile://action/${Uri.encode(profile.id)}/${Uri.encode(session.id)}/$name")
      putExtra("serverId", profile.id)
      putExtra("profileUrl", profile.url)
      putExtra("sessionId", session.id)
      putExtra("directory", session.directory)
      permission?.let { putExtra("permissionId", it.id); putExtra("permissionDirectory", it.directory) }
      addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
    }
    return PendingIntent.getBroadcast(context, notificationId(profile.id, session.id) xor name.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  }
  fun build(profile: ServerProfile, session: Session, state: TaskState, permission: PermissionRequest? = null): Notification {
    val waiting = state.phase in TaskState.WAITING_PHASES
    val running = state.phase in TaskState.RUNNING_PHASES
    val channel = if (waiting) ATTENTION else if (running) RUNNING else COMPLETED
    val title = when (state.phase) {
      TaskPhase.COMPLETED -> "已完成 · ${session.title}"
      TaskPhase.FAILED -> "执行失败 · ${session.title}"
      TaskPhase.ABORTED -> "已停止 · ${session.title}"
      TaskPhase.WAITING_PERMISSION -> "需要授权 · ${session.title}"
      TaskPhase.WAITING_QUESTION -> "需要回答 · ${session.title}"
      else -> "运行中 · ${session.title}"
    }
    // When a permission is pending, show what the agent actually wants to run (action + patterns)
    // instead of the fixed "等待权限确认" so the user can decide knowingly (A16).
    val body = permission?.let(::permissionSummary) ?: state.detail
    val builder = NotificationCompat.Builder(context, channel)
      .setSmallIcon(R.drawable.ic_app).setContentTitle(title).setContentText(body.take(200))
      .setStyle(NotificationCompat.BigTextStyle().bigText(body))
      .setContentIntent(open(profile.id, session.id)).setAutoCancel(!state.active)
      .setOnlyAlertOnce(running).setOngoing(running)
      .setCategory(if (waiting) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_PROGRESS)
      .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
    if (running) {
      builder.setRequestPromotedOngoing(true)
      builder.addAction(0, "停止", action("abort", profile, session))
    }
    if (state.phase == TaskPhase.WAITING_PERMISSION && permission != null && permission.action.isNotBlank() && permission.detail !in setOf("", "{}", "[]")) {
      builder.addAction(0, "拒绝", action("reject", profile, session, permission))
      builder.addAction(0, "允许一次", action("once", profile, session, permission))

    }
    if (state.phase == TaskPhase.WAITING_QUESTION) {
      builder.addAction(0, "回答", open(profile.id, session.id))
    }
    val notification = builder.build()
    XiaomiIslandAdapter(context).extend(notification, title, body, running)
    return notification
  }
  private fun permissionSummary(permission: PermissionRequest): String = buildString {
    append(permission.action.ifBlank { "操作请求" })
    if (permission.detail.isNotBlank()) append("\n").append(permission.detail.take(600))
    if (permission.always.isNotEmpty()) append("\n记住规则：").append(permission.always.joinToString(", ").take(200))
  }

  /** Minimal, persistent notification for the monitoring foreground service (A09). It is separate
   *  from the per-session result notifications so removing the foreground state never removes a
   *  real task result. */
  fun buildMonitoring(): Notification = NotificationCompat.Builder(context, RUNNING)
    .setSmallIcon(R.drawable.ic_app).setContentTitle("OpenCode 任务监控中").setContentText("仅跟踪当前服务器；切换后结束本地监控")
    .setOngoing(true).setShowWhen(false).setCategory(NotificationCompat.CATEGORY_SERVICE)
    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
    .build()

  fun show(profile: ServerProfile, session: Session, state: TaskState, permission: PermissionRequest? = null) {
    if (!allowed()) return
    if (!profile.notifications) return
    if (state.phase == TaskPhase.IDLE || state.phase == TaskPhase.DISCONNECTED) return
    manager.notify(notificationId(profile.id, session.id), build(profile, session, state, permission))
  }
  fun showPush(notificationId: Int, profile: ServerProfile, session: Session, state: TaskState) {
    if (!allowed() || !profile.notifications) return
    if (state.phase == TaskPhase.IDLE || state.phase == TaskPhase.DISCONNECTED) return
    manager.notify(notificationId, build(profile, session, state))
  }
  fun cancelLocal(serverId: String, sessionId: String) = manager.cancel(notificationId(serverId, sessionId))
  fun cancel(serverId: String, sessionId: String) {
    manager.cancel(notificationId(serverId, sessionId))
    manager.cancel(pushNotificationId(serverId, sessionId))
  }
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
