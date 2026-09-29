package com.igng.opencode.mobile.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.igng.opencode.mobile.core.*
import com.igng.opencode.mobile.push.PushRegistration
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.*
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.text.DateFormat
import java.util.Date
import java.util.UUID

@Composable
fun HomeScreen(
  state: MobileState,
  controller: MobileController,
  onOpen: (String) -> Unit,
  onServers: () -> Unit,
  onSessions: () -> Unit,
  scrollBehavior: ScrollBehavior? = null
) {
  val sections = remember(state.sessions, state.tasks) {
    val running = ArrayList<Session>()
    val waiting = ArrayList<Session>()
    val recent = ArrayList<Session>()
    for (session in state.sessions) {
      val phase = state.tasks[session.id]?.phase
      when (phase) {
        in TaskState.RUNNING_PHASES -> running += session
        in TaskState.WAITING_PHASES -> waiting += session
        else -> if (recent.size < 8) recent += session
      }
    }
    Triple(running, waiting, recent)
  }
  val running = sections.first
  val waiting = sections.second
  val recent = sections.third

  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .overScrollVertical(),
    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp)
  ) {
    // 状态提示条：降级或离线缓存
    if (state.degraded || state.cached) {
      item {
        Card(
          colors = CardDefaults.defaultColors(
            color = MiuixColorTokens.WarningSubtle
          ),
          insideMargin = PaddingValues(14.dp)
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = MiuixIcons.Info,
              contentDescription = null,
              tint = MiuixColorTokens.Warning,
              modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
              text = if (state.degraded) "部分服务器数据读取失败，相关状态可能为上一次已知值。"
                     else "当前处于离线缓存模式，重新连接后将自动恢复同步。",
              style = MiuixTheme.textStyles.body2.copy(color = MiuixColorTokens.Warning)
            )
          }
        }
      }
    }

    // 指标概览区域：采用 MIUIX 风格卡片网格
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        MetricTile("运行中", running.size.toString(), MiuixColorTokens.Primary, Modifier.weight(1f))
        MetricTile("待处理", waiting.size.toString(), MiuixColorTokens.Warning, Modifier.weight(1f))
        MetricTile("全部会话", state.sessions.size.toString(), MiuixColorTokens.Success, Modifier.weight(1f))
      }
    }

    // 快捷开始新任务卡片 (带按压反馈)
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        pressFeedbackType = PressFeedbackType.Sink,
        showIndication = true,
        insideMargin = PaddingValues(16.dp),
        onClick = {
          if (state.connected) {
            controller.createSession(if (state.protocol.supportsTitleOnCreate) "新任务" else "") { onOpen(it.id) }
          } else {
            onServers()
          }
        }
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier
              .size(44.dp)
              .background(MiuixColorTokens.PrimarySubtle, miuixSquircleShape(12.dp)),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = MiuixIcons.Add,
              contentDescription = "新建",
              tint = MiuixTheme.colorScheme.primary,
              modifier = Modifier.size(24.dp)
            )
          }
          Spacer(Modifier.width(14.dp))
          Column(Modifier.weight(1f)) {
            Text(
              text = if (state.connected) "开启新任务" else "添加并连接服务器",
              style = MiuixTheme.textStyles.headline1.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(Modifier.height(2.dp))
            Text(
              text = if (state.connected) "项目 [${state.project?.name ?: "默认目录"}] · 一键唤起智能工作流"
                     else "连接私有 OpenCode 服务以开始协作",
              style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
          Icon(
            imageVector = MiuixIcons.ChevronForward,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
            modifier = Modifier.size(16.dp)
          )
        }
      }
    }

    // 运行中任务
    item { MiuixSectionHeader("正在运行", running.size) }
    if (running.isEmpty()) {
      item { MiuixEmptyStateCard("目前无运行中的任务", "发送开发任务或启动 Agent 后，实时状态与进展将在此呈现。") }
    } else {
      items(running, key = { "running-${it.id}" }) { session ->
        TaskExecutionCard(session, state.tasks[session.id], onClick = { onOpen(session.id) })
      }
    }

    // 待处理任务 (权限与交互)
    item { MiuixSectionHeader("需要处理", waiting.size) }
    if (waiting.isEmpty()) {
      item { MiuixEmptyStateCard("没有待处理事项", "环境权限审批与关键问题提问将在此置顶显示。") }
    } else {
      items(waiting, key = { "waiting-${it.id}" }) { session ->
        ActionRequiredCard(session, state.tasks[session.id], onClick = { onOpen(session.id) })
      }
    }

    // 最近会话列表
    item {
      MiuixSectionHeader("最近会话", recent.size, action = "查看全部", onAction = onSessions)
    }
    if (recent.isEmpty()) {
      item { MiuixEmptyStateCard("暂无历史会话", "创建新任务后将在此保留历史记录。") }
    } else {
      items(recent, key = { "recent-${it.id}" }) { session ->
        MiuixSessionItemCard(session, state.tasks[session.id], onClick = { onOpen(session.id) })
      }
    }
  }
}

@Composable
private fun MetricTile(label: String, value: String, accentColor: Color, modifier: Modifier = Modifier) {
  Card(
    modifier = modifier,
    insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    colors = CardDefaults.defaultColors(
      color = MiuixTheme.colorScheme.surfaceContainer
    )
  ) {
    Text(
      text = value,
      style = MiuixTheme.textStyles.title2.copy(
        fontWeight = FontWeight.Bold,
        color = accentColor
      )
    )
    Spacer(Modifier.height(2.dp))
    Text(
      text = label,
      style = MiuixTheme.textStyles.footnote2.copy(
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
      )
    )
  }
}

@Composable
fun MiuixEmptyStateCard(title: String, subtitle: String) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    insideMargin = PaddingValues(20.dp),
    colors = CardDefaults.defaultColors(
      color = MiuixTheme.colorScheme.surfaceContainer
    )
  ) {
    Text(
      text = title,
      style = MiuixTheme.textStyles.headline2.copy(
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onSurface
      )
    )
    Spacer(Modifier.height(4.dp))
    Text(
      text = subtitle,
      style = MiuixTheme.textStyles.footnote1.copy(
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
      )
    )
  }
}

@Composable
private fun TaskExecutionCard(session: Session, task: TaskState?, onClick: () -> Unit) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    pressFeedbackType = PressFeedbackType.Sink,
    showIndication = true,
    insideMargin = PaddingValues(16.dp),
    onClick = onClick
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = session.title,
        style = MiuixTheme.textStyles.headline1.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.weight(1f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      if (task != null) {
        MiuixStatePill(task.phase)
      }
    }
    Spacer(Modifier.height(8.dp))
    Text(
      text = task?.detail?.ifBlank { "正在执行…" } ?: "正在运行",
      style = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
      maxLines = 2,
      overflow = TextOverflow.Ellipsis
    )
    Spacer(Modifier.height(12.dp))
    InfiniteProgressIndicator(
      modifier = Modifier.fillMaxWidth().height(4.dp)
    )
  }
}

@Composable
private fun ActionRequiredCard(session: Session, task: TaskState?, onClick: () -> Unit) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    pressFeedbackType = PressFeedbackType.Sink,
    showIndication = true,
    insideMargin = PaddingValues(16.dp),
    colors = CardDefaults.defaultColors(
      color = MiuixColorTokens.WarningSubtle
    ),
    onClick = onClick
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = session.title,
            style = MiuixTheme.textStyles.headline1.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
        Spacer(Modifier.height(4.dp))
        Text(
          text = task?.detail ?: "需要你的权限授权或选项回答",
          style = MiuixTheme.textStyles.footnote1.copy(
            color = MiuixColorTokens.Warning,
            fontWeight = FontWeight.Medium
          )
        )
      }
      Box(
        modifier = Modifier
          .background(MiuixColorTokens.Warning, miuixSquircleShape(8.dp))
          .padding(horizontal = 10.dp, vertical = 6.dp)
      ) {
        Text(
          text = "立刻处理",
          style = MiuixTheme.textStyles.footnote2.copy(
            color = Color.White,
            fontWeight = FontWeight.Bold
          )
        )
      }
    }
  }
}

@Composable
fun MiuixSessionItemCard(session: Session, task: TaskState?, onClick: () -> Unit) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    pressFeedbackType = PressFeedbackType.Sink,
    showIndication = true,
    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    onClick = onClick
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(
          text = session.title,
          style = MiuixTheme.textStyles.headline2.copy(fontWeight = FontWeight.Medium),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(3.dp))
        Text(
          text = session.directory.substringAfterLast('/'),
          style = MiuixTheme.textStyles.footnote2.copy(
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
          )
        )
      }
      Column(horizontalAlignment = Alignment.End) {
        if (task != null && task.phase != TaskPhase.IDLE) {
          MiuixStatePill(task.phase)
          Spacer(Modifier.height(4.dp))
        }
        Text(
          text = formatDate(session.updated),
          style = MiuixTheme.textStyles.footnote2.copy(
            color = MiuixTheme.colorScheme.onSurfaceVariantActions
          )
        )
      }
    }
  }
}

private class DateFormatterCache {
  private var locale: java.util.Locale? = null
  private var formatter: DateFormat? = null
  fun get(current: java.util.Locale): DateFormat {
    val cached = formatter
    if (cached != null && locale == current) return cached
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, current).also {
      locale = current
      formatter = it
    }
  }
}
private val sessionDateFormatters = DateFormatterCache()
private fun formatDate(timestamp: Long): String =
  if (timestamp <= 0) "" else sessionDateFormatters.get(java.util.Locale.getDefault()).format(Date(timestamp))

@Composable
fun SessionsScreen(
  state: MobileState,
  controller: MobileController,
  onOpen: (String) -> Unit
) {
  var query by remember { mutableStateOf("") }
  var createDialog by remember { mutableStateOf(false) }
  var newTitle by remember { mutableStateOf("") }

  val visible = state.sessions
    .filter { state.project == null || it.directory == state.project?.directory }
    .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }

  Column(Modifier.fillMaxSize()) {
    // 搜索框与项目过滤栏
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
      TextField(
        value = query,
        onValueChange = { text: String -> query = text },
        useLabelAsPlaceholder = true,
        label = "搜索会话标题…",
        modifier = Modifier.fillMaxWidth(),
        leadingIcon = {
          Icon(
            imageVector = MiuixIcons.Search,
            contentDescription = "搜索",
            modifier = Modifier.padding(horizontal = 10.dp),
            tint = MiuixTheme.colorScheme.onSecondaryContainer
          )
        }
      )

      if (state.projects.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        val projectTabs = listOf("全部项目") + state.projects.map { it.name }
        val selectedIdx = if (state.projectId == null) 0 else (state.projects.indexOfFirst { it.id == state.projectId } + 1).coerceAtLeast(0)
        TabRow(
          tabs = projectTabs,
          selectedTabIndex = selectedIdx,
          onTabSelected = { index ->
            if (index == 0) controller.selectProject("")
            else controller.selectProject(state.projects[index - 1].id)
          }
        )
      }
    }

    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .overScrollVertical(),
      contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      if (visible.isEmpty()) {
        item {
          MiuixEmptyStateCard("尚无匹配会话", "点击右上角新建会话即可开启开发新任务。")
        }
      }
      items(visible, key = { it.id }) { session ->
        MiuixSessionItemCard(session, state.tasks[session.id], onClick = { onOpen(session.id) })
      }
    }
  }

  // V1 兼容标题弹窗
  if (createDialog) {
    SuperDialog(
      title = "新建会话",
      show = createDialog,
      onDismissRequest = { createDialog = false }
    ) {
      Column(Modifier.padding(top = 10.dp)) {
        TextField(
          value = newTitle,
          onValueChange = { text: String -> newTitle = text },
          useLabelAsPlaceholder = true,
          label = "输入会话标题（留空自动命名）",
          modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
          TextButton(text = "取消", onClick = { createDialog = false })
          Spacer(Modifier.width(10.dp))
          TextButton(
            text = "创建",
            colors = ButtonDefaults.textButtonColorsPrimary(),
            onClick = {
              controller.createSession(newTitle.ifBlank { "新任务" }) { onOpen(it.id) }
              newTitle = ""
              createDialog = false
            }
          )
        }
      }
    }
  }
}

@Composable
fun ServersModal(
  state: MobileState,
  controller: MobileController,
  onDismiss: () -> Unit,
  onConnected: () -> Unit
) {
  var editing by remember { mutableStateOf<ServerProfile?>(null) }
  var showForm by remember { mutableStateOf(state.profiles.isEmpty()) }
  var deleting by remember { mutableStateOf<ServerProfile?>(null) }
  val scope = rememberCoroutineScope()

  SuperBottomSheet(
    title = if (showForm) (if (editing == null) "添加新服务器" else "编辑服务器") else "服务器连接管理",
    show = true,
    onDismissRequest = onDismiss
  ) {
    Box(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
      if (showForm) {
        MiuixServerForm(
          existing = editing,
          onCancel = {
            if (state.profiles.isNotEmpty()) {
              showForm = false
              editing = null
            } else {
              onDismiss()
            }
          },
          onSave = { profile, password, done ->
            scope.launch {
              try {
                val pair = PairLinkResolver.isPairLink(profile.url)
                val resolved = if (pair) PairLinkResolver.resolve(profile.url) else null
                val actualProfile = if (resolved == null) profile else profile.copy(url = resolved.serverUrl, username = resolved.credentials.username)
                val savedUrl = state.profiles.firstOrNull { it.id == profile.id }?.url
                val credentials = resolved?.credentials ?: profileCredentials(
                  savedUrl, actualProfile.url, controller.credentials(profile.id), actualProfile.username, password
                )
                val version = controller.testServer(actualProfile, credentials)
                controller.saveServer(actualProfile, credentials.password, credentials.cookie, credentials.username)
                done("已连接 OpenCode $version")
                showForm = false
                editing = null
                onConnected()
              } catch (error: Exception) {
                done(error.message ?: "连接失败")
              }
            }
          }
        )
      } else {
        LazyColumn(
          modifier = Modifier.fillMaxSize().overScrollVertical(),
          contentPadding = PaddingValues(16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          item {
            Button(
              onClick = { editing = null; showForm = true },
              modifier = Modifier.fillMaxWidth(),
              colors = ButtonDefaults.buttonColorsPrimary()
            ) {
              Text("＋ 添加新服务器")
            }
          }
          items(state.profiles, key = { it.id }) { profile ->
            Card(insideMargin = PaddingValues(16.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                  modifier = Modifier
                    .size(40.dp)
                    .background(MiuixTheme.colorScheme.primaryVariant.copy(alpha = 0.15f), miuixSquircleShape(10.dp)),
                  contentAlignment = Alignment.Center
                ) {
                  Icon(
                    imageVector = MiuixIcons.Sort,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                  )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                  Text(profile.name, style = MiuixTheme.textStyles.headline2.copy(fontWeight = FontWeight.SemiBold))
                  Text(
                    profile.url,
                    style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                }
                if (profile.id == state.serverId) {
                  MiuixStatePill(if (state.connected) TaskPhase.COMPLETED else TaskPhase.DISCONNECTED, if (state.connected) "活跃" else "离线")
                }
              }
              Spacer(Modifier.height(12.dp))
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                  onClick = { controller.connect(profile.id); onConnected() },
                  enabled = profile.id != state.serverId || !state.connected,
                  colors = ButtonDefaults.buttonColorsPrimary()
                ) {
                  Text("连接")
                }
                Button(
                  onClick = { editing = profile; showForm = true },
                  colors = ButtonDefaults.buttonColors()
                ) {
                  Text("编辑")
                }
                TextButton(
                  text = "删除",
                  onClick = { deleting = profile }
                )
              }
            }
          }
        }
      }
    }
  }

  deleting?.let { profile ->
    SuperDialog(
      title = "移除 ${profile.name}？",
      show = deleting != null,
      onDismissRequest = { deleting = null }
    ) {
      Column(Modifier.padding(top = 8.dp)) {
        Text(
          "仅从此设备删除连接记录和存储凭据，不会对远程服务器数据产生任何影响。",
          style = MiuixTheme.textStyles.body1
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
          TextButton(text = "取消", onClick = { deleting = null })
          Spacer(Modifier.width(10.dp))
          TextButton(
            text = "确认删除",
            colors = ButtonDefaults.textButtonColors(textColor = MiuixColorTokens.Error),
            onClick = {
              controller.deleteServer(profile.id)
              deleting = null
            }
          )
        }
      }
    }
  }
}

@Composable
private fun MiuixServerForm(
  existing: ServerProfile?,
  onCancel: () -> Unit,
  onSave: (ServerProfile, String?, (String) -> Unit) -> Unit
) {
  val clipboard = LocalClipboardManager.current
  val id = remember(existing?.id) { existing?.id ?: UUID.randomUUID().toString() }
  var name by remember(existing?.id) { mutableStateOf(existing?.name ?: "") }
  var url by remember(existing?.id) { mutableStateOf(existing?.url ?: "") }
  var username by remember(existing?.id) { mutableStateOf(existing?.username ?: "opencode") }
  var password by remember(existing?.id) { mutableStateOf("") }
  var companion by remember(existing?.id) { mutableStateOf(existing?.companionUrl ?: "") }
  var pluginSecret by remember(existing?.id) { mutableStateOf(existing?.pluginSecret ?: "") }
  var autoConnect by remember(existing?.id) { mutableStateOf(existing?.autoConnect ?: true) }
  var notifications by remember(existing?.id) { mutableStateOf(existing?.notifications ?: true) }
  var allowHttp by remember(existing?.id) { mutableStateOf(existing?.allowCleartext == true) }
  var showAdvanced by remember { mutableStateOf(existing?.companionUrl?.isNotBlank() == true || existing?.allowCleartext == true) }
  var result by remember { mutableStateOf("") }
  var working by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    if (existing == null && url.isBlank()) {
      val clipText = clipboard.getText()?.text?.trim().orEmpty()
      if (clipText.isNotBlank() && (PairLinkResolver.isPairLink(clipText) || clipText.startsWith("http://") || clipText.startsWith("https://"))) {
        url = clipText
        if (name.isBlank()) name = "我的服务器"
      }
    }
  }

  val normalizedInput = url.trim()
  val isPair = PairLinkResolver.isPairLink(normalizedInput)

  LazyColumn(
    modifier = Modifier.fillMaxSize().overScrollVertical(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    item {
      Card(
        colors = CardDefaults.defaultColors(color = MiuixColorTokens.PrimarySubtle),
        insideMargin = PaddingValues(12.dp)
      ) {
        Text(
          "💡 推荐在电脑端运行 opencode pair 并将配对链接直接粘贴到下方，即可免密码一键配置认证。",
          style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.primary)
        )
      }
    }

    item {
      TextField(
        value = name,
        onValueChange = { text: String -> name = text },
        label = "服务器名称标识",
        modifier = Modifier.fillMaxWidth()
      )
    }

    item {
      TextField(
        value = url,
        onValueChange = { text: String ->
          url = text
          if (name.isBlank() && text.isNotBlank()) name = "我的 OpenCode"
        },
        label = "服务器地址 或 配对链接",
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth()
      )
    }

    if (!isPair) {
      item {
        TextField(
          value = username,
          onValueChange = { text: String -> username = text },
          label = "用户名 (Basic Auth)",
          modifier = Modifier.fillMaxWidth()
        )
      }
      item {
        TextField(
          value = password,
          onValueChange = { text: String -> password = text },
          label = if (existing == null) "访问密码" else "新密码（留空保持不变）",
          visualTransformation = PasswordVisualTransformation(),
          modifier = Modifier.fillMaxWidth()
        )
      }
    }

    item {
      SuperArrow(
        title = "高级与推送设置",
        summary = if (showAdvanced) "收起额外配置" else "伴随插件与网络模式",
        onClick = { showAdvanced = !showAdvanced }
      )
    }

    if (showAdvanced) {
      item {
        TextField(
          value = companion,
          onValueChange = { text: String -> companion = text },
          label = "离线推送插件地址 (可选)",
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
          modifier = Modifier.fillMaxWidth()
        )
      }
      item {
        TextField(
          value = pluginSecret,
          onValueChange = { text: String -> pluginSecret = text },
          label = "推送验证密钥 (OPENCODE_MOBILE_PUSH_SECRET)",
          visualTransformation = PasswordVisualTransformation(),
          modifier = Modifier.fillMaxWidth()
        )
      }
      item {
        SuperSwitch(
          title = "自动连接此服务器",
          checked = autoConnect,
          onCheckedChange = { autoConnect = it }
        )
      }
      item {
        SuperSwitch(
          title = "接收任务完成与等待通知",
          checked = notifications,
          onCheckedChange = { notifications = it }
        )
      }
      if (normalizedInput.startsWith("http://", true)) {
        item {
          SuperSwitch(
            title = "允许局域网明文 HTTP",
            summary = "仅建议在受信任局域网环境下使用",
            checked = allowHttp,
            onCheckedChange = { allowHttp = it }
          )
        }
      }
    }

    if (result.isNotBlank()) {
      item {
        Text(
          text = result,
          color = if (result.startsWith("已连接")) MiuixColorTokens.Success else MiuixColorTokens.Error,
          style = MiuixTheme.textStyles.body2
        )
      }
    }

    item {
      Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        TextButton(
          text = "取消",
          onClick = onCancel,
          modifier = Modifier.weight(1f)
        )
        Button(
          onClick = {
            working = true
            val normalizedUrl = normalizedInput.trimEnd('/')
            val profile = ServerProfile(
              id, name.trim().ifBlank { "OpenCode" }, normalizedUrl, username.trim().ifBlank { "opencode" }, autoConnect, notifications,
              companion.trim().trimEnd('/'), allowCleartext = normalizedUrl.startsWith("http://", true) && allowHttp, pluginSecret = pluginSecret.trim()
            )
            onSave(profile, password.takeIf { it.isNotBlank() || existing == null }) { message ->
              result = message
              working = false
            }
          },
          enabled = !working && name.isNotBlank() &&
              (isPair || existing != null || password.isNotBlank()) &&
              (normalizedInput.startsWith("https://", true) || (normalizedInput.startsWith("http://", true) && allowHttp)),
          modifier = Modifier.weight(2f),
          colors = ButtonDefaults.buttonColorsPrimary()
        ) {
          Text(if (working) "正在测试…" else "保存并连接")
        }
      }
    }
  }
}

@Composable
fun SettingsScreen(
  state: MobileState,
  controller: MobileController,
  dark: Boolean,
  onDark: (Boolean) -> Unit,
  onNotifications: () -> Unit,
  onManageServers: () -> Unit
) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val pushAvailable = remember(state.serverId) { PushRegistration(context).available() }

  LazyColumn(
    modifier = Modifier
      .fillMaxSize()
      .overScrollVertical(),
    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp)
  ) {
    item { MiuixSectionHeader("当前连接") }
    item {
      Card(insideMargin = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(
              state.server?.name ?: "未连接服务器",
              style = MiuixTheme.textStyles.headline1.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(Modifier.height(3.dp))
            Text(
              if (state.connected) "OpenCode ${state.version.ifBlank { "V2" }} · 正常通信中" else "离线或尚未连接",
              style = MiuixTheme.textStyles.footnote1.copy(
                color = if (state.connected) MiuixColorTokens.Success else MiuixTheme.colorScheme.onSurfaceVariantSummary
              )
            )
          }
          Button(
            onClick = onManageServers,
            colors = ButtonDefaults.buttonColorsPrimary()
          ) {
            Text("管理服务器")
          }
        }
      }
    }

    item { MiuixSectionHeader("界面与系统") }
    item {
      Card {
        SuperSwitch(
          title = "深色模式 (Dark Theme)",
          summary = "开启符合 MIUIX 规范的高对比深色表面与液体透光",
          checked = dark,
          onCheckedChange = onDark
        )
      }
    }

    item { MiuixSectionHeader("通知与实时岛") }
    item {
      Card(insideMargin = PaddingValues(16.dp)) {
        Text("系统通知与灵动岛 / 超级岛展示", style = MiuixTheme.textStyles.headline2.copy(fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.height(4.dp))
        Text(
          "支持 Android 实时进度条与小米 HyperOS 超级岛 / 流体胶囊显示。",
          style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        )
        Spacer(Modifier.height(10.dp))
        Button(
          onClick = onNotifications,
          colors = ButtonDefaults.buttonColors()
        ) {
          Text("检查 / 授予通知权限")
        }
      }
    }

    item { MiuixSectionHeader("离线后台推送 (FCM)") }
    item {
      Card(insideMargin = PaddingValues(16.dp)) {
        Text(
          if (pushAvailable) "Firebase FCM 推送环境已就绪" else "后台推送未配置（前台活跃时正常通知）",
          style = MiuixTheme.textStyles.headline2.copy(fontWeight = FontWeight.SemiBold)
        )
        Spacer(Modifier.height(4.dp))
        Text(
          "若希望完全关闭 App 后仍能及时收到任务完成或权限等待通知，需配置伴随插件 companion 与 Firebase 凭据。",
          style = MiuixTheme.textStyles.footnote1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        )
        Spacer(Modifier.height(10.dp))
        Button(
          onClick = { FirebaseMessaging.getInstance().token.addOnSuccessListener(controller::registerPush) },
          enabled = pushAvailable && state.server?.companionUrl?.isNotBlank() == true,
          colors = ButtonDefaults.buttonColorsPrimary()
        ) {
          Text("注册当前设备到此服务器")
        }
      }
    }
  }
}
