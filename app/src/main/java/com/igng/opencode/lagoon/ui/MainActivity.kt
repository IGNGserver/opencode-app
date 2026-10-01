package com.igng.opencode.lagoon.ui

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
import com.igng.opencode.lagoon.core.LagoonController
import com.igng.opencode.lagoon.push.PushRegistration
import kotlinx.coroutines.CancellationException
import top.yukonga.miuix.kmp.basic.*
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
    val controller = LagoonController.get(this)

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
          inChatDetail = nextDetail
          currentTab = nextTab
          backProgress.snapTo(0f)
        } catch (e: CancellationException) {
          // 手势取消：物理回弹归零，不改变层级。
          backProgress.animateTo(
            targetValue = 0f,
            animationSpec = spring(dampingRatio = 0.85f, stiffness = 400f)
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
          val stage = AppBackStack.backStage(inChatDetail, currentTab)
          val progress = backProgress.value
          val visualProgress = androidx.compose.animation.core.FastOutSlowInEasing.transform(progress)

          // 顶层采用 MIUIX 官方 Scaffold 脚手架
          Scaffold(
            modifier = Modifier.imePadding(),
            topBar = {
              if (!inChatDetail) {
                Box(Modifier.fillMaxWidth()) {
                  // 在从二级标签页向工作台预见式返回时，工作台的 TopBar 视差淡入露出
                  if (stage == BackStage.TAB_TO_HOME && (gestureActive || progress > 0.01f)) {
                    Box(
                      Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                          alpha = visualProgress
                          translationX = -(1f - visualProgress) * 40.dp.toPx()
                        }
                    ) {
                      TopAppBar(
                        title = "工作台",
                        largeTitle = "任务工作台",
                        scrollBehavior = homeScrollBehavior,
                        navigationIcon = {
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
                  }

                  // 当前 Tab 的 TopBar，随手势向右滑动并淡出
                  Box(
                    Modifier
                      .fillMaxWidth()
                      .graphicsLayer {
                        if (stage == BackStage.TAB_TO_HOME) {
                          alpha = 1f - visualProgress
                          translationX = visualProgress * size.width * 0.85f
                        }
                      }
                  ) {
                    when (currentTab) {
                      RootTab.HOME -> {
                        TopAppBar(
                          title = "工作台",
                          largeTitle = "任务工作台",
                          scrollBehavior = homeScrollBehavior,
                          navigationIcon = {
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

              // 主视图内容容器：通过双层舞台结构实现无缝视差与预见式返回
              Box(Modifier.weight(1f).fillMaxHeight()) {
                val stage = AppBackStack.backStage(inChatDetail, currentTab)
                val progress = backProgress.value
                val visualProgress = androidx.compose.animation.core.FastOutSlowInEasing.transform(progress)

                // 底层/目标舞台：
                // 1. 如果在二级标签页向工作台回退，底层预先渲染 HomeScreen 供用户预览
                // 2. 如果在详情页向所属标签页回退，底层渲染当前标签页，随手势微量缩放放大
                if (stage == BackStage.TAB_TO_HOME && (gestureActive || progress > 0.01f)) {
                  Box(
                    Modifier
                      .fillMaxSize()
                      .graphicsLayer {
                        val scale = 0.94f + visualProgress * 0.06f
                        scaleX = scale
                        scaleY = scale
                        alpha = 0.85f + visualProgress * 0.15f
                        translationX = -(1f - visualProgress) * 48.dp.toPx()
                      }
                  ) {
                    HomeScreen(
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
                  }
                }

                // 基层：当前标签页内容。
                // 只有当不在 TAB_TO_HOME 手势或作为手势顶层卡片时展示
                Box(
                  Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                      if (inChatDetail) {
                        // 详情层回退时基层轻微放大，形成 MIUIX 层次纵深
                        val scale = 0.92f + visualProgress * 0.08f
                        scaleX = scale
                        scaleY = scale
                        alpha = 0.85f + visualProgress * 0.15f
                      } else if (stage == BackStage.TAB_TO_HOME) {
                        // 在 TAB_TO_HOME 手势中，当前 Tab 作为一个卡片向右滑出
                        translationX = visualProgress * size.width * 0.90f
                        val scale = 1f - visualProgress * 0.06f
                        scaleX = scale
                        scaleY = scale
                        alpha = 1f - visualProgress * 0.2f
                        shadowElevation = visualProgress * 16.dp.toPx()
                      }
                    }
                    .then(
                      if (stage == BackStage.TAB_TO_HOME && (gestureActive || progress > 0.01f)) {
                        Modifier.clip(miuixSquircleShape((visualProgress * 24).dp))
                      } else {
                        Modifier
                      }
                    )
                    .background(MiuixTheme.colorScheme.background)
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

                // 详情层：只有在确实处于详情页或详情页手势中时才允许展示与渲染，绝不在非详情页时泄露
                if (inChatDetail || (stage == BackStage.DETAIL_TO_TAB && (gestureActive || progress > 0.01f))) {
                  val cornerRadius = (visualProgress * 24).dp
                  Box(
                    Modifier
                      .fillMaxSize()
                      .graphicsLayer {
                        translationX = visualProgress * size.width * 0.90f
                        val scale = 1f - visualProgress * 0.06f
                        scaleX = scale
                        scaleY = scale
                        alpha = 1f - visualProgress * 0.15f
                        shadowElevation = (visualProgress * 16.dp.toPx())
                      }
                      .clip(miuixSquircleShape(cornerRadius))
                      .background(MiuixTheme.colorScheme.surface)
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
            if (!isWideScreen && state.profiles.isNotEmpty() && !keyboardOpen) {
              val stage = AppBackStack.backStage(inChatDetail, currentTab)
              val progress = backProgress.value
              val visualProgress = androidx.compose.animation.core.FastOutSlowInEasing.transform(progress)

              // 在详情页时，如果正处于返回所属标签页的手势中，Dock 同步优雅淡入
              val showDock = !inChatDetail || (stage == BackStage.DETAIL_TO_TAB && (gestureActive || progress > 0.01f))
              if (showDock) {
                Box(
                  modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp)
                    .graphicsLayer {
                      if (inChatDetail) {
                        alpha = visualProgress
                        translationY = (1f - visualProgress) * 30.dp.toPx()
                      }
                    }
                ) {
                  LiquidGlassDock(
                    selectedTab = currentTab,
                    onTabSelected = { currentTab = it },
                    isDark = dark,
                    backdrop = contentBackdrop
                  )
                }
              }
            }

            // 顶部实时运行状态灵动岛 (Live Task Island)
            if (!state.summary.isEmpty) {
              val stage = AppBackStack.backStage(inChatDetail, currentTab)
              val progress = backProgress.value
              val visualProgress = androidx.compose.animation.core.FastOutSlowInEasing.transform(progress)
              val showIsland = !inChatDetail || (stage == BackStage.DETAIL_TO_TAB && (gestureActive || progress > 0.01f))

              if (showIsland) {
                val summaryTarget = state.summaryTargetId
                LiquidTaskIsland(
                  summary = state.summary,
                  isDark = dark,
                  backdrop = contentBackdrop,
                  onClick = {
                    // 优先跳转到未读的已完成/失败会话；否则进入当前会话详情。
                    summaryTarget?.let(controller::selectSession)
                    inChatDetail = true
                  },
                  modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 56.dp)
                    .graphicsLayer {
                      if (inChatDetail) {
                        alpha = visualProgress
                        translationY = -(1f - visualProgress) * 20.dp.toPx()
                      }
                    }
                )
              }
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

            // 服务器管理 BottomSheet：必须留在 Scaffold 内容作用域内。
            // MIUIX 的 SuperDialog/SuperBottomSheet 只注册进 Scaffold 内部 provide 的弹层列表，
            // 并只由 Scaffold 自带的 MiuixPopupHost 渲染；声明在 Scaffold 之外会静默落进无人渲染
            // 的默认列表，表现为“点击添加服务器没有任何反应”（#15 修过，#21 重构 Dock 时又移了出去）。
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
  }
}

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    parseDeepLink(intent)
  }

  private fun parseDeepLink(intent: Intent?) {
    val uri = intent?.data ?: return
    if (uri.scheme != "opencode-lagoon" || uri.host != "server") return
    val parts = uri.pathSegments
    if (parts.size >= 3 && parts[1] == "session") deepLink = parts[0] to parts[2]
  }
}
