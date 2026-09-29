package com.igng.opencode.mobile.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.messaging.FirebaseMessaging
import com.igng.opencode.mobile.core.*
import com.igng.opencode.mobile.push.PushRegistration
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.DateFormat
import java.util.Date
import java.util.UUID

@Composable
fun PageHeader(eyebrow: String, title: String, action: String? = null, onAction: (() -> Unit)? = null) {
  Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(3.dp))
        Text(title, style = MaterialTheme.typography.displaySmall)
      }
      if (action != null && onAction != null) {
        Button(
          shape = RoundedCornerShape(8.dp),
          onClick = onAction,
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
          Text(action)
        }
      }
    }
  }
}

@Composable
fun HomeScreen(
  state: MobileState,
  controller: MobileController,
  onOpen: (String) -> Unit,
  onServers: () -> Unit,
  onSessions: () -> Unit
) {
  // Derive the three sections once per session/task change instead of re-filtering every session
  // three times on each recomposition, and use sets so membership stays O(1) rather than O(n).
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

  LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
    item {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
          Text("OC", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
          Text("OpenCode Mobile", style = MaterialTheme.typography.titleMedium)
          Text(
            if (state.connected) "已连接 · ${state.version.ifBlank { "V2" }}" else if (state.cached) "离线缓存模式" else "未连接",
            style = MaterialTheme.typography.labelMedium,
            color = if (state.connected) Fluent.green else if (state.cached) Fluent.amber else MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = MaterialTheme.colorScheme.secondaryContainer,
          modifier = Modifier.clickable(onClick = onServers)
        ) {
          Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            Text(state.server?.name ?: "选择服务器", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text("▾", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSecondaryContainer)
          }
        }
      }
    }

    item {
      PageHeader("WORKSPACE", "任务工作台", "刷新", controller::reload)
      if (state.degraded) {
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = Fluent.amber.copy(alpha = 0.12f),
          border = BorderStroke(1.dp, Fluent.amber.copy(alpha = 0.4f)),
          modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        ) {
          Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("⚠️ 部分服务器数据本次读取失败，相关状态可能为上一次的已知值。", style = MaterialTheme.typography.bodyMedium, color = Fluent.amber)
          }
        }
      }
      if (state.cached) {
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = Fluent.amber.copy(alpha = 0.12f),
          border = BorderStroke(1.dp, Fluent.amber.copy(alpha = 0.4f)),
          modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        ) {
          Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("⚡ 当前处于离线缓存状态，重新连接服务器后可继续发送任务与同步事件。", style = MaterialTheme.typography.bodyMedium, color = Fluent.amber)
          }
        }
      }
    }

    item {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricCard("运行中", running.size.toString(), Fluent.blue, Modifier.weight(1f))
        MetricCard("待处理", waiting.size.toString(), Fluent.amber, Modifier.weight(1f))
        MetricCard("会话总数", state.sessions.size.toString(), Fluent.green, Modifier.weight(1f))
      }
    }

    // 快捷开始新任务卡片 (Zero-state or quick CTA)
    item {
      FluentCard(onClick = {
        if (state.connected) {
          // Navigate only once the created session is known; do not read the stale sessionId.
          controller.createSession(if (state.protocol.supportsTitleOnCreate) "新任务" else "") { onOpen(it.id) }
        } else {
          onServers()
        }
      }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Text("＋", color = MaterialTheme.colorScheme.primary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
          }
          Spacer(Modifier.width(12.dp))
          Column(Modifier.weight(1f)) {
            Text(if (state.connected) "开启一项新任务" else "添加并连接服务器", style = MaterialTheme.typography.titleMedium)
            Text(
              if (state.connected) "在当前项目 [${state.project?.name ?: "默认目录"}] 快速创建会话" else "连接你的 OpenCode 实例即可开始",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
          Text("立刻开始 ›", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        }
      }
    }

    item { SectionTitle("正在运行", running.size) }
    if (running.isEmpty()) item { EmptyCard("目前没有运行中的任务", "发送指令后，执行状态与进度条会在此集中呈现。") }
    items(running, key = { "running-${it.id}" }) { session -> TaskCard(session, state.tasks[session.id], onClick = { onOpen(session.id) }) }

    item { SectionTitle("需要处理", waiting.size) }
    if (waiting.isEmpty()) item { EmptyCard("没有待处理事项", "环境权限申请和提问交互会在此优先置顶。") }
    items(waiting, key = { "waiting-${it.id}" }) { session ->
      FluentCard(onClick = { onOpen(session.id) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(session.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(5.dp))
            Text(state.tasks[session.id]?.detail ?: "需要你的授权或回答", color = Fluent.amber, fontWeight = FontWeight.Medium)
          }
          Surface(shape = RoundedCornerShape(6.dp), color = Fluent.amber.copy(alpha = 0.15f)) {
            Text("立刻处理 ›", color = Fluent.amber, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
          }
        }
      }
    }

    item { SectionTitle("最近会话", recent.size, "查看全部", onSessions) }
    items(recent, key = { "recent-${it.id}" }) { session -> SessionRow(session, state.tasks[session.id], { onOpen(session.id) }) }
  }
}

@Composable
private fun MetricCard(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
  Surface(modifier = modifier, shape = RoundedCornerShape(10.dp), color = color.copy(alpha = 0.1f)) {
    Column(Modifier.padding(12.dp)) {
      Text(value, color = color, style = MaterialTheme.typography.headlineSmall)
      Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
    }
  }
}

@Composable
fun EmptyCard(title: String, subtitle: String, onClick: (() -> Unit)? = null) {
  FluentCard(onClick = onClick) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

@Composable
private fun TaskCard(session: Session, task: TaskState?, onClick: () -> Unit) {
  FluentCard(onClick = onClick) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(session.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
      if (task != null) StatePill(task.phase)
    }
    Spacer(Modifier.height(9.dp))
    Text(task?.detail ?: "正在运行", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    if (task != null) {
      Spacer(Modifier.height(12.dp))
      LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
    }
  }
}

@Composable
fun SessionRow(session: Session, task: TaskState?, onClick: () -> Unit) {
  FluentCard(onClick = onClick) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(session.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(3.dp))
        Text(session.directory.substringAfterLast('/'), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      Column(horizontalAlignment = Alignment.End) {
        if (task != null && task.phase != TaskPhase.IDLE) StatePill(task.phase)
        Spacer(Modifier.height(3.dp))
        Text(formatDate(session.updated), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
  }
}
// DateFormat instances are not thread-safe, but Compose UI calls this on the main thread only.
// Reuse one formatter per locale instead of allocating a fresh one for every visible list row on
// every recomposition; the locale key keeps a system locale change reflected.
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
fun SessionsScreen(state: MobileState, controller: MobileController, onOpen: (String) -> Unit) {
  var query by remember { mutableStateOf("") }
  var createDialog by remember { mutableStateOf(false) }
  var newTitle by remember { mutableStateOf("") }
  val visible = state.sessions.filter { state.project == null || it.directory == state.project?.directory }
    .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }

  Column(Modifier.fillMaxSize()) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
      PageHeader("PROJECTS & SESSIONS", "会话列表", "新建会话") {
        if (!state.protocol.supportsTitleOnCreate) {
          // V2 协议无需弹窗输入标题，直接一键新建并打开
          controller.createSession("") { onOpen(it.id) }
        } else {
          createDialog = true
        }
      }
      if (state.projects.isNotEmpty()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          state.projects.forEach { project ->
            FilterChip(
              selected = project.id == state.projectId,
              onClick = { controller.selectProject(project.id) },
              label = { Text(project.name) },
              shape = RoundedCornerShape(8.dp)
            )
          }
        }
        Spacer(Modifier.height(10.dp))
      }
      TextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("搜索会话标题…") },
        singleLine = true,
        shape = RoundedCornerShape(8.dp),
        colors = TextFieldDefaults.colors(
          focusedIndicatorColor = Color.Transparent,
          unfocusedIndicatorColor = Color.Transparent
        )
      )
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
      if (visible.isEmpty()) item {
        EmptyCard("尚无匹配的会话", "点击右上角新建会话，开始处理你的开发任务。")
      }
      items(visible, key = { it.id }) { SessionRow(it, state.tasks[it.id], { onOpen(it.id) }) }
    }
  }

  // 针对 V1 服务器的标题输入弹窗
  if (createDialog) {
    AlertDialog(
      onDismissRequest = { createDialog = false },
      title = { Text("新建会话") },
      text = {
        TextField(newTitle, { newTitle = it }, placeholder = { Text("输入会话标题（留空自动命名）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
      },
      confirmButton = {
        TextButton(shape = RoundedCornerShape(6.dp), onClick = {
          controller.createSession(newTitle.ifBlank { "新任务" }) { onOpen(it.id) }
          newTitle = ""
          createDialog = false
        }, enabled = state.connected && state.project != null) { Text("创建") }
      },
      dismissButton = {
        TextButton(shape = RoundedCornerShape(6.dp), onClick = { createDialog = false }) { Text("取消") }
      }
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
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

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    dragHandle = { BottomSheetDefaults.DragHandle() },
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ) {
    Box(Modifier.fillMaxWidth().fillMaxHeight(0.88f)) {
      if (showForm) {
        ServerForm(editing, onCancel = {
          if (state.profiles.isNotEmpty()) {
            showForm = false
            editing = null
          } else {
            onDismiss()
          }
        }, onSave = { profile, password, done ->
          scope.launch {
            try {
              val pair = PairLinkResolver.isPairLink(profile.url)
              val resolved = if (pair) PairLinkResolver.resolve(profile.url) else null
              val actualProfile = if (resolved == null) profile else profile.copy(url = resolved.serverUrl, username = resolved.credentials.username)
              val savedUrl = state.profiles.firstOrNull { it.id == profile.id }?.url
              val credentials = resolved?.credentials ?: profileCredentials(savedUrl, actualProfile.url,
                controller.credentials(profile.id), actualProfile.username, password)
              val version = controller.testServer(actualProfile, credentials)
              controller.saveServer(actualProfile, credentials.password, credentials.cookie, credentials.username)
              done("已连接 OpenCode $version")
              showForm = false
              editing = null
              onConnected()
            } catch (error: Exception) { done(error.message ?: "连接失败") }
          }
        })
      } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          item {
            PageHeader("CONNECTIONS", "服务器连接", "添加新服务器") {
              editing = null
              showForm = true
            }
          }
          item {
            Text("连接到私有 OpenCode 实例。凭据受 Android Keystore 安全硬件保护。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
          }
          items(state.profiles, key = { it.id }) { profile ->
            FluentCard {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(Fluent.blueLight, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                  Text("▧", color = Fluent.blue, fontSize = 20.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                  Text(profile.name, style = MaterialTheme.typography.titleMedium)
                  Text(profile.url, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (profile.id == state.serverId) {
                  StatePill(if (state.connected) TaskPhase.COMPLETED else TaskPhase.DISCONNECTED, if (state.connected) "活跃" else "离线")
                }
              }
              Spacer(Modifier.height(12.dp))
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                  shape = RoundedCornerShape(6.dp),
                  onClick = { controller.connect(profile.id); onConnected() },
                  enabled = profile.id != state.serverId || !state.connected
                ) { Text("连接使用") }
                OutlinedButton(shape = RoundedCornerShape(6.dp), onClick = { editing = profile; showForm = true }) { Text("编辑") }
                TextButton(shape = RoundedCornerShape(6.dp), onClick = { deleting = profile }) { Text("删除", color = MaterialTheme.colorScheme.error) }
              }
            }
          }
        }
      }
    }
  }

  deleting?.let { profile ->
    AlertDialog(
      onDismissRequest = { deleting = null },
      title = { Text("移除 ${profile.name}？") },
      text = { Text("仅从此设备删除连接记录和存储凭据，不会对远程服务器数据产生任何影响。") },
      confirmButton = { TextButton(shape = RoundedCornerShape(6.dp), onClick = { controller.deleteServer(profile.id); deleting = null }) { Text("确认删除", color = MaterialTheme.colorScheme.error) } },
      dismissButton = { TextButton(shape = RoundedCornerShape(6.dp), onClick = { deleting = null }) { Text("取消") } }
    )
  }
}

@Composable
private fun ServerForm(
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

  // 剪贴板自动识别 opencode pair 或 URL
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

  LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
    item { PageHeader("SERVER SETUP", if (existing == null) "添加服务器" else "编辑服务器", "取消", onCancel) }

    // 智能配对提示条
    item {
      Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth()
      ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
          Text("💡 推荐：终端运行 opencode pair 并直接将配对链接粘贴在下方，即可免密码一键配置认证。", style = MaterialTheme.typography.bodyMedium)
        }
      }
    }

    item {
      TextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("名称标识") },
        placeholder = { Text("例如：家用台式机 / 办公室 VPS") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
      )
    }

    item {
      TextField(
        value = url,
        onValueChange = {
          url = it
          if (name.isBlank() && it.isNotBlank()) name = "我的 OpenCode"
        },
        label = { Text("服务器地址 或 配对链接") },
        placeholder = { Text("https:// 或 opencode pair 输出的链接") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
      )
    }

    if (!isPair) {
      item {
        TextField(
          value = username,
          onValueChange = { username = it },
          label = { Text("用户名 (Basic Auth)") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true
        )
      }
      item {
        TextField(
          value = password,
          onValueChange = { password = it },
          label = { Text(if (existing == null) "访问密码" else "新密码（留空则保持不变）") },
          visualTransformation = PasswordVisualTransformation(),
          modifier = Modifier.fillMaxWidth(),
          singleLine = true
        )
      }
    } else {
      item {
        Surface(shape = RoundedCornerShape(8.dp), color = Fluent.green.copy(alpha = 0.12f)) {
          Text("已识别为官方配对链接 (Pair Link)。密码将自动安全换取并存储。", modifier = Modifier.padding(12.dp), color = Fluent.green, style = MaterialTheme.typography.bodyMedium)
        }
      }
    }

    item {
      Row(Modifier.fillMaxWidth().clickable { showAdvanced = !showAdvanced }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("高级与推送设置", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(if (showAdvanced) "收起 ▴" else "展开 ▾", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
      }
    }

    if (showAdvanced) {
      item {
        TextField(
          value = companion,
          onValueChange = { companion = it },
          label = { Text("离线推送插件地址（可选）") },
          placeholder = { Text("https://push.example.com") },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
          modifier = Modifier.fillMaxWidth(),
          singleLine = true
        )
      }
      item {
        TextField(
          value = pluginSecret,
          onValueChange = { pluginSecret = it },
          label = { Text("推送验证密钥（OPENCODE_MOBILE_PUSH_SECRET）") },
          visualTransformation = PasswordVisualTransformation(),
          modifier = Modifier.fillMaxWidth(),
          singleLine = true
        )
      }
      item {
        Text("填写服务器独立的 OPENCODE_MOBILE_PUSH_SECRET。留空将拒绝后台推送；不要使用插件入站认证密钥。",
          style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      item { SwitchRow("自动连接此服务器", autoConnect, { autoConnect = it }) }
      item { SwitchRow("接收任务完成与等待通知", notifications, { notifications = it }) }
      if (normalizedInput.startsWith("http://", true)) {
        item {
          SwitchRow("允许局域网明文 HTTP", allowHttp, { allowHttp = it })
          Text("警告：明文连接会在网络中暴露访问密码，仅限受信任的局域网或内网穿透环境使用。", color = Fluent.amber, style = MaterialTheme.typography.labelMedium)
        }
      }
    }

    if (result.isNotBlank()) {
      item {
        Text(
          result,
          color = if (result.startsWith("已连接")) Fluent.green else MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodyMedium
        )
      }
    }

    item {
      Button(
        shape = RoundedCornerShape(8.dp),
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
        modifier = Modifier.fillMaxWidth()
      ) {
        Text(if (working) "正在测试连接…" else "保存并连接")
      }
    }
  }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    Switch(checked, onCheckedChange = onChange)
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

  LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    item { PageHeader("PREFERENCES", "设置") }

    item { SectionTitle("当前连接与服务器") }
    item {
      FluentCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(state.server?.name ?: "未连接服务器", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(3.dp))
            Text(
              if (state.connected) "OpenCode ${state.version.ifBlank { "V2" }} · 状态正常" else "离线或未连接",
              color = if (state.connected) Fluent.green else MaterialTheme.colorScheme.onSurfaceVariant,
              style = MaterialTheme.typography.bodyMedium
            )
          }
          Button(shape = RoundedCornerShape(8.dp), onClick = onManageServers) {
            Text("管理服务器")
          }
        }
      }
    }

    item { SectionTitle("界面外观") }
    item {
      FluentCard {
        SwitchRow("深色主题 (Dark Mode)", dark, onDark)
      }
    }

    item { SectionTitle("移动通知与实时状态") }
    item {
      FluentCard {
        Text("系统通知与灵动岛展示", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text("支持 Android 16 实时进度条与小米 HyperOS 超级岛显示。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(shape = RoundedCornerShape(6.dp), onClick = onNotifications) {
          Text("检查 / 授予通知权限")
        }
      }
    }

    item { SectionTitle("离线后台推送 (可选)") }
    item {
      FluentCard {
        Text(if (pushAvailable) "Firebase FCM 推送环境已就绪" else "后台推送未配置（仅在前台活跃时通知）", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
          "若希望完全关闭 App 后仍能收到任务完成与待授权通知，需配置 companion 伴随插件与 Firebase 凭据。",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        Button(
          shape = RoundedCornerShape(6.dp),
          onClick = { FirebaseMessaging.getInstance().token.addOnSuccessListener(controller::registerPush) },
          enabled = pushAvailable && state.server?.companionUrl?.isNotBlank() == true
        ) {
          Text("注册当前设备到此服务器")
        }
      }
    }
  }
}
