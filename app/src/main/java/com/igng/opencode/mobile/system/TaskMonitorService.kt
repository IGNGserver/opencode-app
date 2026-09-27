package com.igng.opencode.mobile.system

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.igng.opencode.mobile.core.MobileController
import com.igng.opencode.mobile.core.ServerStore
import com.igng.opencode.mobile.core.Session
import com.igng.opencode.mobile.core.TaskPhase
import com.igng.opencode.mobile.core.TaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class TaskMonitorService : Service() {
  companion object {
    fun start(context: Context, serverId: String, sessionId: String) {
      val intent = Intent(context, TaskMonitorService::class.java).putExtra("serverId", serverId).putExtra("sessionId", sessionId)
      androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }
  }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var monitor: Job? = null
  override fun onBind(intent: Intent?): IBinder? = null
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val serverId = intent?.getStringExtra("serverId") ?: return START_NOT_STICKY
    val sessionId = intent.getStringExtra("sessionId") ?: return START_NOT_STICKY
    val profile = ServerStore(this).profiles().firstOrNull { it.id == serverId } ?: return START_NOT_STICKY
    val notifications = TaskNotifications(this)
    val placeholder = Session(sessionId, "", "OpenCode 任务", 0)
    val initial = notifications.build(profile, placeholder, TaskState(sessionId, TaskPhase.THINKING, "正在连接任务状态"))
    if (Build.VERSION.SDK_INT >= 29) startForeground(TaskNotifications.notificationId(sessionId), initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    else startForeground(TaskNotifications.notificationId(sessionId), initial)
    val controller = MobileController.get(this)
    if (controller.state.value.serverId != serverId) controller.connect(serverId)
    monitor?.cancel()
    monitor = scope.launch {
      controller.state.collectLatest { state ->
        val session = state.sessions.firstOrNull { it.id == sessionId } ?: placeholder
        val task = state.tasks[sessionId] ?: return@collectLatest
        if (task.phase in setOf(TaskPhase.COMPLETED, TaskPhase.FAILED, TaskPhase.ABORTED)) {
          notifications.show(profile, session, task)
          if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH) else @Suppress("DEPRECATION") stopForeground(false)
          stopSelf()
        } else if (task.active) {
          notifications.show(profile, session, task, state.permissions.firstOrNull { it.sessionId == sessionId })
        }
      }
    }
    return START_REDELIVER_INTENT
  }
  override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }
  override fun onDestroy() { monitor?.cancel(); super.onDestroy() }
}
