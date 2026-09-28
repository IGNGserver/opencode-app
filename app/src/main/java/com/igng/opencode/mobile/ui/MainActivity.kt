package com.igng.opencode.mobile.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import com.igng.opencode.mobile.core.MobileController
import com.igng.opencode.mobile.core.MobileState
import com.igng.opencode.mobile.push.PushRegistration

private enum class Page(val label: String) {
  HOME("首页"), SESSIONS("会话"), CHAT("对话"), SERVERS("服务器"), SETTINGS("设置")
}

class MainActivity : ComponentActivity() {
  private var deepLink by mutableStateOf<Pair<String, String>?>(null)
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    parseDeepLink(intent)
    val controller = MobileController.get(this)
    setContent {
      val state by controller.state.collectAsState()
      val preferences = remember { getSharedPreferences("ui", MODE_PRIVATE) }
      var dark by remember { mutableStateOf(preferences.getBoolean("dark", false)) }
      var page by remember { mutableStateOf(if (state.profiles.isEmpty()) Page.SERVERS else Page.HOME) }
      val snackbar = remember { SnackbarHostState() }
      val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
      LaunchedEffect(state.error) {
        val error = state.error ?: return@LaunchedEffect
        snackbar.showSnackbar(error)
        controller.clearError()
      }
      LaunchedEffect(state.serverId) {
        val profile = state.server ?: return@LaunchedEffect
        PushRegistration(this@MainActivity).enableFor(profile, controller.credentials(profile.id), controller.deviceId())
      }
      LaunchedEffect(deepLink, state.sessions) {
        val (serverId, sessionId) = deepLink ?: return@LaunchedEffect
        if (state.serverId != serverId) controller.connect(serverId)
        else if (state.sessions.any { it.id == sessionId }) {
          controller.selectSession(sessionId)
          page = Page.CHAT
          deepLink = null
        }
      }
      FluentTheme(dark) {
        val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        Scaffold(modifier = Modifier.imePadding(), snackbarHost = { SnackbarHost(snackbar) },
          bottomBar = {
            if (state.profiles.isNotEmpty() && !keyboardOpen) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
              Page.entries.forEach { item ->
                NavigationBarItem(selected = page == item, onClick = { page = item },
                  icon = { FluentNavIcon(item.name, page == item) }, label = { Text(item.label) }, alwaysShowLabel = true)
              }
            }
          }) { insets ->
          Box(Modifier.fillMaxSize().padding(insets)) {
            when (page) {
              Page.HOME -> HomeScreen(state, controller, onOpen = { controller.selectSession(it); page = Page.CHAT },
                onServers = { page = Page.SERVERS }, onSessions = { page = Page.SESSIONS })
              Page.SESSIONS -> SessionsScreen(state, controller, onOpen = { controller.selectSession(it); page = Page.CHAT })
              Page.CHAT -> ChatScreen(state, controller, onSessions = { page = Page.SESSIONS })
              Page.SERVERS -> ServersScreen(state, controller, onConnected = { page = Page.HOME })
              Page.SETTINGS -> SettingsScreen(state, controller, dark, onDark = {
                dark = it
                preferences.edit().putBoolean("dark", it).apply()
              }, onNotifications = {
                if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
              })
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
          }
        }
      }
    }
  }
  override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); parseDeepLink(intent) }
  private fun parseDeepLink(intent: Intent?) {
    val uri = intent?.data ?: return
    if (uri.scheme != "opencode-mobile" || uri.host != "server") return
    val parts = uri.pathSegments
    if (parts.size >= 3 && parts[1] == "session") deepLink = parts[0] to parts[2]
  }
}

@Composable
private fun FluentNavIcon(name: String, selected: Boolean) {
  val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
  Canvas(Modifier.size(22.dp)) {
    val u = size.width / 24f
    val stroke = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun point(x: Float, y: Float) = Offset(x * u, y * u)
    when (name) {
      "HOME" -> {
        val roof = Path().apply { moveTo(3*u, 11*u); lineTo(12*u, 3*u); lineTo(21*u, 11*u) }
        drawPath(roof, color, style = stroke)
        val base = Path().apply { moveTo(5*u, 10*u); lineTo(5*u, 21*u); lineTo(19*u, 21*u); lineTo(19*u, 10*u) }
        drawPath(base, color, style = stroke)
        drawLine(color, point(10f, 21f), point(10f, 15f), strokeWidth = 2*u)
        drawLine(color, point(14f, 15f), point(14f, 21f), strokeWidth = 2*u)
      }
      "SESSIONS" -> {
        drawRoundRect(color, point(3f, 3f), androidx.compose.ui.geometry.Size(18*u, 18*u), CornerRadius(2*u), style = stroke)
        for (y in listOf(8f, 12f, 16f)) {
          drawCircle(color, 0.8f*u, point(7f, y))
          drawLine(color, point(10f, y), point(17f, y), strokeWidth = 1.8f*u, cap = StrokeCap.Round)
        }
      }
      "CHAT" -> {
        drawRoundRect(color, point(3f, 4f), androidx.compose.ui.geometry.Size(18*u, 14*u), CornerRadius(3*u), style = stroke)
        val tail = Path().apply { moveTo(8*u, 18*u); lineTo(7*u, 22*u); lineTo(13*u, 18*u) }
        drawPath(tail, color, style = stroke)
        drawCircle(color, 0.8f*u, point(8f, 11f)); drawCircle(color, 0.8f*u, point(12f, 11f)); drawCircle(color, 0.8f*u, point(16f, 11f))
      }
      "SERVERS" -> {
        drawRoundRect(color, point(3f, 4f), androidx.compose.ui.geometry.Size(18*u, 7*u), CornerRadius(2*u), style = stroke)
        drawRoundRect(color, point(3f, 13f), androidx.compose.ui.geometry.Size(18*u, 7*u), CornerRadius(2*u), style = stroke)
        drawCircle(color, 1*u, point(7f, 7.5f)); drawCircle(color, 1*u, point(7f, 16.5f))
        drawLine(color, point(11f, 7.5f), point(17f, 7.5f), strokeWidth = 1.5f*u)
        drawLine(color, point(11f, 16.5f), point(17f, 16.5f), strokeWidth = 1.5f*u)
      }
      "SETTINGS" -> {
        drawCircle(color, 6.5f*u, point(12f, 12f), style = stroke)
        drawCircle(color, 2.2f*u, point(12f, 12f), style = stroke)
        for (index in 0 until 8) {
          val angle = index * Math.PI / 4.0
          val dx = kotlin.math.cos(angle).toFloat(); val dy = kotlin.math.sin(angle).toFloat()
          drawLine(color, point(12f + dx*8f, 12f + dy*8f), point(12f + dx*10f, 12f + dy*10f), strokeWidth = 2*u, cap = StrokeCap.Round)
        }
      }
    }
  }
}
