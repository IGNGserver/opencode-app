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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class TaskMonitorService : Service() {
  companion object {
    private const val FOREGROUND_ID = 1001
    private val TERMINAL_PHASES = setOf(TaskPhase.COMPLETED, TaskPhase.FAILED, TaskPhase.ABORTED)
    fun start(context: Context, serverId: String, sessionId: String) {
      val intent = Intent(context, TaskMonitorService::class.java).putExtra("serverId", serverId).putExtra("sessionId", sessionId)
      androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }
  }
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var monitor: Job? = null
  private val tracked = linkedSetOf<Pair<String, String>>()
  private val lastShown = HashMap<Pair<String, String>, Pair<TaskPhase, String>>()
  override fun onBind(intent: Intent?): IBinder? = null
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val serverId = intent?.getStringExtra("serverId") ?: run { stopSelf(); return START_NOT_STICKY }
    val sessionId = intent.getStringExtra("sessionId") ?: run { stopSelf(); return START_NOT_STICKY }
    val profile = ServerStore(this).profiles().firstOrNull { it.id == serverId } ?: run { stopSelf(); return START_NOT_STICKY }
    val notifications = TaskNotifications(this)
    tracked += serverId to sessionId
    // A dedicated monitoring notification; kept separate from per-session results so stopping the
    // foreground state never cancels a real completion/failure notification, and so the foreground
    // placeholder is not left behind under a session-specific id.
    val controller = MobileController.get(this)
    // A dedicated monitoring notification whose text mirrors the island summary; kept separate from
    // per-session results so stopping the foreground state never cancels a real completion/failure
    // notification, and so the foreground placeholder is not left behind under a session-specific id.
    if (Build.VERSION.SDK_INT >= 29) startForeground(FOREGROUND_ID, notifications.buildMonitoring(controller.state.value.summary), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    else startForeground(FOREGROUND_ID, notifications.buildMonitoring(controller.state.value.summary))
    if (controller.state.value.serverId != serverId || !profile.notifications) {
      tracked.remove(serverId to sessionId)
      if (tracked.isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
      return START_NOT_STICKY
    }
    if (monitor == null) {
      monitor = scope.launch {
        // Plain collect (not collectLatest): emissions are frequent during streaming, and cancelling
        // the previous handler on every emission needlessly restarted notification work.
        controller.state.collect { state ->
          tracked.toList().forEach { key ->
            val (trackedServerId, trackedSessionId) = key
            // This service monitors only the current connection. Switching or removing a profile
            // drops its local tracking; independent companion pushes remain available.
            if (trackedServerId != state.serverId || state.profiles.none { it.id == trackedServerId && it.notifications } ||
              state.connected && !state.degraded && state.sessions.none { it.id == trackedSessionId }) {
              tracked.remove(key); lastShown.remove(key); notifications.cancelLocal(trackedServerId, trackedSessionId)
              return@forEach
            }
            val trackedProfile = state.profiles.firstOrNull { it.id == trackedServerId } ?: profile
            val session = state.sessions.firstOrNull { it.id == trackedSessionId } ?: Session(trackedSessionId, "", "OpenCode 任务", 0)
            val task = state.tasks[trackedSessionId] ?: return@forEach
            val terminal = task.phase in TERMINAL_PHASES
            // state emits very frequently while streaming; only rebuild and re-post a notification
            // when the phase or detail actually changed since the last post for this session.
            val signature = task.phase to task.detail
            val changed = lastShown[key] != signature
            if (terminal) {
              if (changed) { notifications.show(trackedProfile, session, task); lastShown.remove(key) }
              tracked.remove(key)
            } else if (task.active) {
              if (changed) {
                notifications.show(trackedProfile, session, task, state.permissions.firstOrNull { it.sessionId == trackedSessionId })
                lastShown[key] = signature
              }
            }
          }
          if (tracked.isEmpty()) {
            // DETACH would leave the monitoring notification behind; remove it and then stop.
            // minSdk is 26, so STOP_FOREGROUND_REMOVE is always available.
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
          }
        }
      }
    }
    return START_REDELIVER_INTENT
  }
  override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }
  override fun onDestroy() { tracked.clear(); lastShown.clear(); monitor?.cancel(); super.onDestroy() }
}
