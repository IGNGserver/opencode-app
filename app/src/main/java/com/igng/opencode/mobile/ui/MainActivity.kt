package com.igng.opencode.mobile.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.igng.opencode.mobile.core.MobileController
import com.igng.opencode.mobile.push.PushRegistration
import kotlinx.coroutines.CancellationException
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

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
      var globalMessageType by remember { mutableStateOf(LiquidToastType.INFO) }
      val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

      // 屏幕自适应：平板/宽屏使用 NavigationRail，手机使用悬浮 Liquid Glass 底栏
      val configuration = LocalConfiguration.current
      val isWideScreen = configuration.screenWidthDp >= 640

      LaunchedEffect(state.profiles.isEmpty()) {
        if (state.profiles.isEmpty()) showingServersSheet = true
      }

      LaunchedEffect(state.error) {
        val error = state.error ?: return@LaunchedEffect
        globalMessage = error
        globalMessageType = LiquidToastType.ERROR
        controller.clearError()
      }
      LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        globalMessage = message
        globalMessageType = LiquidToastType.SUCCESS
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

      // 统一的应用内返回栈：对话详情 → 所属标签页 → 工作台 → 交给系统退出。
      // 层级判定集中在 AppBackStack，便于无设备单测“侧滑只回上一级、不回桌面”。
      // 预见式返回的进度直接驱动详情层位移，实时预览上一级；提交后才切换状态。
      val canNavigateBack = AppBackStack.canGoBack(inChatDetail, currentTab)
      val backProgress = remember { Animatable(0f) }
      var gestureActive by remember { mutableStateOf(false) }

      // 非手势触发的开关（点返回按钮、打开会话）走补间动画；手势进行中由手势进度驱动。
      LaunchedEffect(inChatDetail, gestureActive) {
        if (!gestureActive) backProgress.animateTo(0f, tween(220))
      }

      PredictiveBackHandler(enabled = canNavigateBack) { progressFlow ->
        gestureActive = true
        try {
          progressFlow.collect { backEvent -> backProgress.snapTo(backEvent.progress.coerceIn(0f, 1f)) }
          // 手势提交：执行真正的层级返回。
          val (nextDetail, nextTab) = AppBackStack.back(inChatDetail, currentTab)
          backProgress.snapTo(0f)
          inChatDetail = nextDetail
          currentTab = nextTab
        } catch (e: CancellationException) {
          // 手势取消：物理回弹归零，不改变层级。
          backProgress.animateTo(
            targetValue = 0f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)
          )
          throw e
        } finally {
          gestureActive = false
        }
      }

      OpenCodeMiuixTheme(dark = dark) {
        val density = LocalDensity.current
        val imeInsets = WindowInsets.ime
        val keyboardOpen by remember(imeInsets, density) {
          derivedStateOf { imeInsets.getBottom(density) > 0 }
        }

        val homeScrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
        val navIcons = listOf(MiuixIcons.VerticalSplit, MiuixIcons.Tasks, MiuixIcons.Settings)
        val contentBackdrop = rememberLayerBackdrop()

        CompositionLocalProvider(LocalBackdrop provides contentBackdrop) {
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
                        cornerRadius = LiquidGlassTokens.CapsuleCornerRadius,
                        isDark = dark,
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
          bottomBar = {}
        ) { insets ->
          Box(Modifier.fillMaxSize()) {
            Row(
              Modifier
                .fillMaxSize()
                .padding(top = insets.calculateTopPadding())
                .layerBackdrop(contentBackdrop)
            ) {
              // 宽屏模式：左侧 NavigationRail
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

              // 主视图内容容器：基层常驻 + 详情层随返回手势滑动，才能实时预览上一级。
              Box(Modifier.weight(1f).fillMaxHeight()) {
                val progress = backProgress.value

                // 基层：当前标签页内容。
                Box(
                  Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                      // 详情层回退时基层轻微放大，形成 MIUIX 层次纵深。
                      if (inChatDetail) {
                        val scale = 1f - progress * 0.08f
                        scaleX = scale
                        scaleY = scale
                      }
                    }
                ) {
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

                // 详情层：对话详情覆盖在基层之上，随返回手势进度右移露出上一级。
                if (inChatDetail || gestureActive || progress > 0.01f) {
                  Box(
                    Modifier
                      .fillMaxSize()
                      .background(MiuixTheme.colorScheme.surface)
                      .graphicsLayer {
                        translationX = progress * size.width
                        alpha = 1f - progress * 0.15f
                      }
                  ) {
                    ChatScreen(
                      state = state,
                      controller = controller,
                      drafts = drafts,
                      onBack = { inChatDetail = false }
                    )
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

            // 移动端/窄屏：悬浮在整个滚动视图之上的光学 Liquid Glass 导航 Dock
            if (!isWideScreen && !inChatDetail && state.profiles.isNotEmpty() && !keyboardOpen) {
              Box(
                modifier = Modifier
                  .align(Alignment.BottomCenter)
                  .navigationBarsPadding()
                  .padding(bottom = 12.dp)
              ) {
                LiquidGlassDock(
                  selectedTab = currentTab,
                  onTabSelected = { currentTab = it },
                  isDark = dark,
                  backdrop = contentBackdrop
                )
              }
            }

            // 顶部实时运行状态灵动岛 (Live Task Island)
            if (!inChatDetail && state.sessionId != null) {
              val activeTask = state.tasks[state.sessionId]
              LiquidTaskIsland(
                task = activeTask,
                isDark = dark,
                backdrop = contentBackdrop,
                onClick = { inChatDetail = true },
                modifier = Modifier
                  .align(Alignment.TopCenter)
                  .statusBarsPadding()
                  .padding(top = 56.dp)
              )
            }

            // 全局液态玻璃悬浮轻提示 (Liquid Toast)
            LiquidToastHost(
              message = globalMessage,
              type = globalMessageType,
              isDark = dark,
              backdrop = contentBackdrop,
              onDismiss = { globalMessage = null },
              modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 16.dp)
            )
          }
        }

        // 服务器管理 BottomSheet（必须在 Scaffold 内容作用域内）。
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
