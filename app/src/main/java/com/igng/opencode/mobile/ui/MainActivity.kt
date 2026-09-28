package com.igng.opencode.mobile.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
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

private enum class RootTab(val label: String) {
  HOME("工作台"), SESSIONS("会话"), SETTINGS("设置")
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
      var currentTab by remember { mutableStateOf(RootTab.HOME) }
      var inChatDetail by remember { mutableStateOf(false) }
      var showingServersSheet by remember { mutableStateOf(false) }
      val snackbar = remember { SnackbarHostState() }
      val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

      LaunchedEffect(state.profiles.isEmpty()) {
        if (state.profiles.isEmpty()) showingServersSheet = true
      }

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
          inChatDetail = true
          deepLink = null
        }
      }

      // Android back button handling for chat detail screen
      BackHandler(enabled = inChatDetail) {
        inChatDetail = false
      }

      FluentTheme(dark) {
        val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        Scaffold(
          modifier = Modifier.imePadding(),
          snackbarHost = { SnackbarHost(snackbar) },
          bottomBar = {
            if (!inChatDetail && state.profiles.isNotEmpty() && !keyboardOpen) {
              NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                RootTab.entries.forEach { item ->
                  NavigationBarItem(
                    selected = currentTab == item,
                    onClick = { currentTab = item },
                    icon = { FluentNavIcon(item.name, currentTab == item) },
                    label = { Text(item.label) },
                    alwaysShowLabel = true
                  )
                }
              }
            }
          }
        ) { insets ->
          Box(Modifier.fillMaxSize().padding(insets)) {
            AnimatedContent(
              targetState = inChatDetail,
              label = "ChatDetailTransition",
              transitionSpec = {
                if (targetState) {
                  slideInHorizontally { width -> width } + fadeIn() togetherWith
                      slideOutHorizontally { width -> -width / 3 } + fadeOut()
                } else {
                  slideInHorizontally { width -> -width / 3 } + fadeIn() togetherWith
                      slideOutHorizontally { width -> width } + fadeOut()
                }
              }
            ) { isDetail ->
              if (isDetail) {
                ChatScreen(
                  state = state,
                  controller = controller,
                  onBack = { inChatDetail = false }
                )
              } else {
                when (currentTab) {
                  RootTab.HOME -> HomeScreen(
                    state = state,
                    controller = controller,
                    onOpen = { sessionId ->
                      controller.selectSession(sessionId)
                      inChatDetail = true
                    },
                    onServers = { showingServersSheet = true },
                    onSessions = { currentTab = RootTab.SESSIONS }
                  )
                  RootTab.SESSIONS -> SessionsScreen(
                    state = state,
                    controller = controller,
                    onOpen = { sessionId ->
                      controller.selectSession(sessionId)
                      inChatDetail = true
                    }
                  )
                  RootTab.SETTINGS -> SettingsScreen(
                    state = state,
                    controller = controller,
                    dark = dark,
                    onDark = {
                      dark = it
                      preferences.edit().putBoolean("dark", it).apply()
                    },
                    onNotifications = {
                      if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    onManageServers = { showingServersSheet = true }
                  )
                }
              }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
          }
        }

        if (showingServersSheet) {
          ServersModal(
            state = state,
            controller = controller,
            onDismiss = { showingServersSheet = false },
            onConnected = {
              showingServersSheet = false
              currentTab = RootTab.HOME
            }
          )
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
