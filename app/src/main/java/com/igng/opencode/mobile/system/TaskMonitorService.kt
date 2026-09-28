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
    private const val FOREGROUND_ID = 1001
    fun start(context: Context, serverId: String, sessionId: String) {
      val intent = Intent(context, TaskMonitorService::class.java).putExtra("serverId", serverId).putExtra("sessionId", sessionId)
      androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }
  }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var monitor: Job? = null
  private val tracked = linkedSetOf<Pair<String, String>>()
  override fun onBind(intent: Intent?): IBinder? = null
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val serverId = intent?.getStringExtra("serverId") ?: return START_NOT_STICKY
    val sessionId = intent.getStringExtra("sessionId") ?: return START_NOT_STICKY
    val profile = ServerStore(this).profiles().firstOrNull { it.id == serverId } ?: return START_NOT_STICKY
    val notifications = TaskNotifications(this)
    val placeholder = Session(sessionId, "", "OpenCode 任务", 0)
    val initial = notifications.build(profile, placeholder, TaskState(sessionId, TaskPhase.THINKING, "正在连接任务状态"))
    tracked += serverId to sessionId
    if (Build.VERSION.SDK_INT >= 29) startForeground(FOREGROUND_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    else startForeground(FOREGROUND_ID, initial)
    val controller = MobileController.get(this)
    if (controller.state.value.serverId != serverId) controller.connect(serverId)
    if (monitor == null) {
      monitor = scope.launch {
        controller.state.collectLatest { state ->
          tracked.toList().forEach { (trackedServerId, trackedSessionId) ->
            if (trackedServerId != state.serverId) return@forEach
            val trackedProfile = state.profiles.firstOrNull { it.id == trackedServerId } ?: profile
            val session = state.sessions.firstOrNull { it.id == trackedSessionId } ?: Session(trackedSessionId, "", "OpenCode 任务", 0)
            val task = state.tasks[trackedSessionId] ?: return@forEach
            if (task.phase in setOf(TaskPhase.COMPLETED, TaskPhase.FAILED, TaskPhase.ABORTED)) {
              notifications.show(trackedProfile, session, task)
              tracked.remove(trackedServerId to trackedSessionId)
            } else if (task.active) {
              notifications.show(trackedProfile, session, task, state.permissions.firstOrNull { it.sessionId == trackedSessionId })
            }
          }
          if (tracked.isEmpty()) {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH) else @Suppress("DEPRECATION") stopForeground(false)
            stopSelf()
          }
        }
      }
    }
    return START_REDELIVER_INTENT
  }
  override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }
  override fun onDestroy() { tracked.clear(); monitor?.cancel(); super.onDestroy() }
}
