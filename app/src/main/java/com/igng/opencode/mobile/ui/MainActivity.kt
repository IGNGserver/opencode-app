package com.igng.opencode.mobile.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.igng.opencode.mobile.core.MobileController
import com.igng.opencode.mobile.push.PushRegistration
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
      val drafts = rememberSaveable(saver = androidx.compose.runtime.saveable.mapSaver(
        save = { it.toMap() },
        restore = { saved -> mutableStateMapOf<String, String>().apply { saved.forEach { (k, v) -> put(k, v as String) } } }
      )) { mutableStateMapOf<String, String>() }

      val preferences = remember { getSharedPreferences("ui", MODE_PRIVATE) }
      var dark by remember { mutableStateOf(preferences.getBoolean("dark", false)) }
      var currentTab by rememberSaveable { mutableStateOf(RootTab.HOME) }
      var inChatDetail by rememberSaveable { mutableStateOf(false) }
      var showingServersSheet by remember { mutableStateOf(false) }
      var globalMessage by remember { mutableStateOf<String?>(null) }
      val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

      // 屏幕自适应检测：平板/桌面宽屏使用侧边栏 NavigationRail，手机竖屏使用悬浮 Liquid Glass 底栏
      val configuration = LocalConfiguration.current
      val isWideScreen = configuration.screenWidthDp >= 640

      LaunchedEffect(state.profiles.isEmpty()) {
        if (state.profiles.isEmpty()) showingServersSheet = true
      }

      LaunchedEffect(state.error) {
        val error = state.error ?: return@LaunchedEffect
        globalMessage = error
        controller.clearError()
      }
      LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        globalMessage = message
        controller.clearMessage()
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

      BackHandler(enabled = inChatDetail) {
        inChatDetail = false
      }

      OpenCodeMiuixTheme(dark = dark) {
        val density = LocalDensity.current
        val imeInsets = WindowInsets.ime
        val keyboardOpen by remember(imeInsets, density) {
          derivedStateOf { imeInsets.getBottom(density) > 0 }
        }

        // 大标题滚动行为控制器
        val homeScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
        val navIcons = listOf(MiuixIcons.VerticalSplit, MiuixIcons.Tasks, MiuixIcons.Settings)

        // 顶层采用 MIUIX 官方 Scaffold 脚手架
        Scaffold(
          modifier = Modifier.imePadding(),
          topBar = {
            if (!inChatDetail) {
              when (currentTab) {
                RootTab.HOME -> {
                  TopAppBar(
                    title = "工作台",
                    largeTitle = "任务工作台",
                    scrollBehavior = homeScrollBehavior,
                    navigationIcon = {
                      // 顶部服务器切换胶囊 (Liquid Glass 质感)
                      LiquidGlassSurface(
                        modifier = Modifier.padding(start = 12.dp),
                        cornerRadius = 14.dp,
                        onClick = { showingServersSheet = true }
                      ) {
                        Row(
                          modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                          verticalAlignment = Alignment.CenterVertically,
                          horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                          Box(
                            Modifier.size(8.dp).background(
                              if (state.connected) MiuixColorTokens.Success else MiuixColorTokens.Warning,
                              shape = miuixSquircleShape(4.dp)
                            )
                          )
                          Text(
                            text = state.server?.name ?: "选择服务器",
                            style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.Medium)
                          )
                          Text("▾", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantActions)
                        }
                      }
                    },
                    actions = {
                      IconButton(onClick = controller::reload) {
                        Icon(imageVector = MiuixIcons.Refresh, contentDescription = "刷新")
                      }
                    }
                  )
                }
                RootTab.SESSIONS -> {
                  SmallTopAppBar(
                    title = "全部会话",
                    actions = {
                      IconButton(onClick = {
                        if (!state.protocol.supportsTitleOnCreate) {
                          controller.createSession("") { inChatDetail = true }
                        } else {
                          controller.createSession("新任务") { inChatDetail = true }
                        }
                      }) {
                        Icon(imageVector = MiuixIcons.Add, contentDescription = "新建会话")
                      }
                    }
                  )
                }
                RootTab.SETTINGS -> {
                  SmallTopAppBar(title = "设置")
                }
              }
            }
          },
          bottomBar = {
            // 移动端/窄屏：采用 Liquid Glass 悬浮导航栏 FloatingNavigationBar
            if (!isWideScreen && !inChatDetail && state.profiles.isNotEmpty() && !keyboardOpen) {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .navigationBarsPadding()
                  .padding(bottom = 12.dp),
                contentAlignment = Alignment.Center
              ) {
                LiquidGlassSurface(
                  cornerRadius = 28.dp
                ) {
                  Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    RootTab.entries.forEachIndexed { index, item ->
                      val isSelected = currentTab == item
                      val tint = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
                      Row(
                        modifier = Modifier
                          .clip(miuixSquircleShape(18.dp))
                          .clickable { currentTab = item }
                          .background(if (isSelected) MiuixColorTokens.PrimarySubtle else Color.Transparent)
                          .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                      ) {
                        Icon(
                          imageVector = navIcons[index],
                          contentDescription = item.label,
                          tint = tint,
                          modifier = Modifier.size(22.dp)
                        )
                        if (isSelected) {
                          Text(
                            text = item.label,
                            style = MiuixTheme.textStyles.footnote1.copy(
                              color = MiuixTheme.colorScheme.primary,
                              fontWeight = FontWeight.SemiBold
                            )
                          )
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        ) { insets ->
          Row(Modifier.fillMaxSize().padding(insets)) {
            // 宽屏模式：左侧 MIUIX 官方 NavigationRail 导航侧边栏
            if (isWideScreen && !inChatDetail && state.profiles.isNotEmpty()) {
              NavigationRail {
                RootTab.entries.forEachIndexed { index, item ->
                  NavigationRailItem(
                    selected = currentTab == item,
                    onClick = { currentTab = item },
                    icon = navIcons[index],
                    label = item.label
                  )
                }
              }
            }

            // 主视图内容容器
            Box(Modifier.weight(1f).fillMaxHeight()) {
              AnimatedContent(
                targetState = inChatDetail,
                label = "DetailTransition",
                transitionSpec = {
                  if (targetState) {
                    slideInHorizontally { width -> width } + fadeIn() togetherWith
                        slideOutHorizontally { width -> -width / 4 } + fadeOut()
                  } else {
                    slideInHorizontally { width -> -width / 4 } + fadeIn() togetherWith
                        slideOutHorizontally { width -> width } + fadeOut()
                  }
                }
              ) { isDetail ->
                if (isDetail) {
                  ChatScreen(
                    state = state,
                    controller = controller,
                    drafts = drafts,
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
                      onSessions = { currentTab = RootTab.SESSIONS },
                      scrollBehavior = homeScrollBehavior
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

              // 全局加载指示器 (MIUIX 风格)
              if (state.loading) {
                InfiniteProgressIndicator(
                  modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).height(3.dp)
                )
              }
            }
          }
        }

        // 全局消息提示弹窗 (MIUIX SuperDialog)
        val msg = globalMessage
        if (msg != null) {
          SuperDialog(
            title = "提示",
            show = true,
            onDismissRequest = { globalMessage = null }
          ) {
            Column(Modifier.padding(top = 8.dp)) {
              Text(msg, style = MiuixTheme.textStyles.body1)
              Spacer(Modifier.height(14.dp))
              Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                  text = "确定",
                  colors = ButtonDefaults.textButtonColorsPrimary(),
                  onClick = { globalMessage = null }
                )
              }
            }
          }
        }

        // 服务器管理 BottomSheet
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

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    parseDeepLink(intent)
  }

  private fun parseDeepLink(intent: Intent?) {
    val uri = intent?.data ?: return
    if (uri.scheme != "opencode-mobile" || uri.host != "server") return
    val parts = uri.pathSegments
    if (parts.size >= 3 && parts[1] == "session") deepLink = parts[0] to parts[2]
  }
}
