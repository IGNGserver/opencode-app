package com.igng.opencode.mobile.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.firebase.messaging.FirebaseMessaging
import com.igng.opencode.mobile.core.*
import com.igng.opencode.mobile.push.PushRegistration
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.UUID

@Composable
private fun PageHeader(eyebrow: String, title: String, action: String? = null, onAction: (() -> Unit)? = null) {
  Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(3.dp))
        Text(title, style = MaterialTheme.typography.displaySmall)
      }
      if (action != null && onAction != null) TextButton(shape = RoundedCornerShape(6.dp), onClick = onAction) { Text(action) }
    }
  }
}

@Composable
fun HomeScreen(state: MobileState, controller: MobileController, onOpen: (String) -> Unit, onServers: () -> Unit, onSessions: () -> Unit) {
  val running = state.sessions.filter { state.tasks[it.id]?.phase in setOf(TaskPhase.THINKING, TaskPhase.TOOL, TaskPhase.SUBAGENT, TaskPhase.TESTING) }
  val waiting = state.sessions.filter { state.tasks[it.id]?.phase in setOf(TaskPhase.WAITING_PERMISSION, TaskPhase.WAITING_QUESTION) }
  val recent = state.sessions.filterNot { it in running || it in waiting }.take(8)
  LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
    item {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
          Text("OC", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.width(10.dp))
        Text("OpenCode Mobile", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(shape = RoundedCornerShape(6.dp), onClick = onServers) { Text(state.server?.name ?: "选择服务器") }
      }
    }
    item {
      PageHeader("WORKSPACE", "随时掌握任务", "刷新", controller::reload)
      val connection = when {
        state.connected -> "已连接 · OpenCode ${state.version}"
        state.cached -> "离线 · 正在显示本机缓存"
        state.loading -> "正在连接服务器…"
        state.server != null -> "未连接 · 请检查服务器后刷新"
        else -> "尚未添加服务器"
      }
      Text(connection, color = if (state.cached) Fluent.amber else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium)
    }
    item {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricCard("进行中", running.size.toString(), Fluent.blue, Modifier.weight(1f))
        MetricCard("待处理", waiting.size.toString(), Fluent.amber, Modifier.weight(1f))
        MetricCard("会话", state.sessions.size.toString(), Fluent.green, Modifier.weight(1f))
      }
    }
    item { SectionTitle("正在运行", running.size) }
    if (running.isEmpty()) item { EmptyCard("目前没有运行中的任务", "从会话发送任务后，进度会显示在这里。", onSessions) }
    items(running, key = { "running-${it.id}" }) { session -> TaskCard(session, state.tasks[session.id], onClick = { onOpen(session.id) }) }
    item { SectionTitle("需要处理", waiting.size) }
    if (waiting.isEmpty()) item { EmptyCard("没有待处理事项", "授权和问题会在此集中显示。") }
    items(waiting, key = { "waiting-${it.id}" }) { session ->
      FluentCard(onClick = { onOpen(session.id) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Column(Modifier.weight(1f)) {
            Text(session.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(5.dp))
            Text(state.tasks[session.id]?.detail ?: "需要你的操作", color = Fluent.amber)
          }
          Text("打开 ›", color = MaterialTheme.colorScheme.primary)
        }
      }
    }
    item { SectionTitle("最近会话", recent.size, "全部", onSessions) }
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
private fun EmptyCard(title: String, subtitle: String, onClick: (() -> Unit)? = null) {
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
private fun SessionRow(session: Session, task: TaskState?, onClick: () -> Unit) {
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
private fun formatDate(timestamp: Long): String = if (timestamp <= 0) "" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))

@Composable
fun SessionsScreen(state: MobileState, controller: MobileController, onOpen: (String) -> Unit) {
  var query by remember { mutableStateOf("") }
  var create by remember { mutableStateOf(false) }
  var newTitle by remember { mutableStateOf("") }
  val visible = state.sessions.filter { state.project == null || it.directory == state.project?.directory }
    .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
  Column(Modifier.fillMaxSize()) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
      PageHeader("PROJECTS & SESSIONS", "会话", "新建", { create = true })
      if (state.projects.isNotEmpty()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          state.projects.forEach { project ->
            FilterChip(selected = project.id == state.projectId, onClick = { controller.selectProject(project.id) },
              label = { Text(project.name) })
          }
        }
        Spacer(Modifier.height(10.dp))
      }
      TextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
        label = { Text("搜索会话") }, singleLine = true, shape = RoundedCornerShape(8.dp))
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
      if (visible.isEmpty()) item { EmptyCard("尚无会话", "点击右上角新建，开始第一项任务。") }
      items(visible, key = { it.id }) { SessionRow(it, state.tasks[it.id], { onOpen(it.id) }) }
    }
  }
  if (create) AlertDialog(onDismissRequest = { create = false }, title = { Text("新建会话") }, text = {
    if (state.protocol == ServerProtocol.V2) {
      Text("OpenCode V2 不提供创建时设置标题的接口；发送第一条任务后，服务器会生成会话标题。")
    } else {
      TextField(newTitle, { newTitle = it }, label = { Text("会话标题") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    }
  }, confirmButton = { TextButton(shape = RoundedCornerShape(6.dp), onClick = {
    controller.createSession(if (state.protocol == ServerProtocol.V2) "" else newTitle.ifBlank { "新任务" }); newTitle = ""; create = false
  }, enabled = state.connected && state.project != null) { Text("创建") } }, dismissButton = { TextButton(shape = RoundedCornerShape(6.dp), onClick = { create = false }) { Text("取消") } })
}

@Composable
fun ServersScreen(state: MobileState, controller: MobileController, onConnected: () -> Unit) {
  var editing by remember { mutableStateOf<ServerProfile?>(null) }
  var showForm by remember { mutableStateOf(state.profiles.isEmpty()) }
  var deleting by remember { mutableStateOf<ServerProfile?>(null) }
  val scope = rememberCoroutineScope()
  if (showForm) {
    ServerForm(editing, onCancel = { showForm = false; editing = null }, onSave = { profile, password, done ->
      scope.launch {
        try {
          val pair = PairLinkResolver.isPairLink(profile.url)
          val resolved = if (pair) PairLinkResolver.resolve(profile.url) else null
          val actualProfile = if (resolved == null) profile else profile.copy(url = resolved.serverUrl, username = resolved.credentials.username)
          val savedCredentials = controller.credentials(profile.id)
          val credentials = resolved?.credentials ?: ServerCredentials(
            username = actualProfile.username,
            password = password ?: savedCredentials.password,
            cookie = savedCredentials.cookie
          )
          val version = controller.testServer(actualProfile, credentials)
          if (resolved == null) controller.saveServer(actualProfile, password, credentialUsername = actualProfile.username)
          else controller.saveServer(actualProfile, resolved.credentials.password, resolved.credentials.cookie, resolved.credentials.username)
          done("已连接 OpenCode $version")
          showForm = false; editing = null; onConnected()
        } catch (error: Exception) { done(error.message ?: "连接失败") }
      }
    })
  } else {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      item { PageHeader("CONNECTIONS", "服务器", "添加", { editing = null; showForm = true }) }
      item { Text("连接自己的 OpenCode 实例。凭据仅保存在此设备的 Android Keystore 中。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
      items(state.profiles, key = { it.id }) { profile ->
        FluentCard {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).background(Fluent.blueLight, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) { Text("▧", color = Fluent.blue) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
              Text(profile.name, style = MaterialTheme.typography.titleMedium)
              Text(profile.url, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (profile.id == state.serverId) StatePill(if (state.connected) TaskPhase.COMPLETED else TaskPhase.DISCONNECTED,
              if (state.connected) "已连接" else "离线")
          }
          Spacer(Modifier.height(12.dp))
          Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(shape = RoundedCornerShape(6.dp), onClick = { controller.connect(profile.id); onConnected() }) { Text("连接") }
            TextButton(shape = RoundedCornerShape(6.dp), onClick = { editing = profile; showForm = true }) { Text("编辑") }
            TextButton(shape = RoundedCornerShape(6.dp), onClick = { deleting = profile }) { Text("删除", color = MaterialTheme.colorScheme.error) }
          }
        }
      }
    }
  }
  deleting?.let { profile -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除 ${profile.name}？") },
    text = { Text("仅移除此设备的连接资料和凭据，不会删除服务器数据。") },
    confirmButton = { TextButton(shape = RoundedCornerShape(6.dp), onClick = { controller.deleteServer(profile.id); deleting = null }) { Text("删除") } },
    dismissButton = { TextButton(shape = RoundedCornerShape(6.dp), onClick = { deleting = null }) { Text("取消") } }) }
}

@Composable
private fun ServerForm(existing: ServerProfile?, onCancel: () -> Unit, onSave: (ServerProfile, String?, (String) -> Unit) -> Unit) {
  val id = remember(existing?.id) { existing?.id ?: UUID.randomUUID().toString() }
  var name by remember(existing?.id) { mutableStateOf(existing?.name ?: "") }
  var url by remember(existing?.id) { mutableStateOf(existing?.url ?: "") }
  var username by remember(existing?.id) { mutableStateOf(existing?.username ?: "opencode") }
  var password by remember(existing?.id) { mutableStateOf("") }
  var companion by remember(existing?.id) { mutableStateOf(existing?.companionUrl ?: "") }
  var autoConnect by remember(existing?.id) { mutableStateOf(existing?.autoConnect ?: true) }
  var notifications by remember(existing?.id) { mutableStateOf(existing?.notifications ?: true) }
  var allowHttp by remember(existing?.id) { mutableStateOf(existing?.allowCleartext == true) }
  var result by remember { mutableStateOf("") }
  var working by remember { mutableStateOf(false) }
  val normalizedInput = url.trim()
  LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    item { PageHeader("NEW CONNECTION", if (existing == null) "添加服务器" else "编辑服务器", "取消", onCancel) }
    item { Text("可直接填写 OpenCode Server 地址，也可粘贴官方 opencode pair 链接。配对链接会被一次性解析并把凭据安全保存在 Android Keystore 中。",
      style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    item { TextField(name, { name = it }, label = { Text("名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
    item { TextField(url, { url = it }, label = { Text("服务器地址") }, placeholder = { Text("https://dev.example.com") },
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(), singleLine = true) }
    item { TextField(username, { username = it }, label = { Text("用户名") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
    item { TextField(password, { password = it }, label = { Text(if (existing == null) "密码" else "新密码（留空则保留）") },
      visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true) }
    item { TextField(companion, { companion = it }, label = { Text("推送伴随服务（可选）") }, placeholder = { Text("https://push.example.com") },
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(), singleLine = true) }
    item { SwitchRow("自动连接", autoConnect, { autoConnect = it }) }
    item { SwitchRow("任务通知", notifications, { notifications = it }) }
    if (normalizedInput.startsWith("http://", true)) item {
      SwitchRow("允许明文 HTTP 连接", allowHttp, { allowHttp = it })
      Text("HTTP 会暴露会话和 Basic Auth 凭据。请仅在受信任的网络使用。", color = Fluent.amber, style = MaterialTheme.typography.labelMedium)
    }
    if (result.isNotBlank()) item { Text(result, color = if (result.startsWith("已连接")) Fluent.green else MaterialTheme.colorScheme.error) }
    item {
      Button(shape = RoundedCornerShape(6.dp), onClick = {
        working = true
        val normalizedUrl = normalizedInput.trimEnd('/')
        val profile = ServerProfile(id, name.trim(), normalizedUrl, username.trim().ifBlank { "opencode" }, autoConnect, notifications,
          companion.trim().trimEnd('/'), allowCleartext = normalizedUrl.startsWith("http://", true) && allowHttp)
        onSave(profile, password.takeIf { it.isNotBlank() || existing == null }) { message -> result = message; working = false }
      }, enabled = !working && name.isNotBlank() &&
        (PairLinkResolver.isPairLink(normalizedInput) || existing != null || password.isNotBlank()) &&
        ((normalizedInput.startsWith("https://", true) || normalizedInput.startsWith("http://", true) && allowHttp)),
        modifier = Modifier.fillMaxWidth()) { Text(if (working) "正在测试连接…" else "测试并保存") }
    }
  }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    Switch(checked, onCheckedChange = onChange)
  }
}

@Composable
fun SettingsScreen(state: MobileState, controller: MobileController, dark: Boolean, onDark: (Boolean) -> Unit, onNotifications: () -> Unit) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val push = remember(state.serverId) { PushRegistration(context).available() }
  LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    item { PageHeader("PREFERENCES", "设置") }
    item { SectionTitle("外观") }
    item { FluentCard { SwitchRow("深色模式", dark, onDark) } }
    item { SectionTitle("任务通知") }
    item { FluentCard {
      Text("运行状态、待处理事项和任务结果", style = MaterialTheme.typography.titleMedium)
      Spacer(Modifier.height(4.dp))
      Text("Android 16 可显示 Live Updates；支持的小米系统可显示超级岛。", color = MaterialTheme.colorScheme.onSurfaceVariant)
      TextButton(shape = RoundedCornerShape(6.dp), onClick = onNotifications) { Text("授予通知权限") }
    } }
    item { SectionTitle("可靠推送") }
    item { FluentCard {
      Text(if (push) "Firebase 已配置" else "Firebase 尚未配置", style = MaterialTheme.typography.titleMedium)
      Spacer(Modifier.height(4.dp))
      Text("关闭 App 后的状态通知需要 Firebase 项目、OpenCode 插件和伴随服务。", color = MaterialTheme.colorScheme.onSurfaceVariant)
      TextButton(shape = RoundedCornerShape(6.dp), onClick = {
        FirebaseMessaging.getInstance().token.addOnSuccessListener(controller::registerPush)
      }, enabled = push && state.server?.companionUrl?.isNotBlank() == true) { Text("注册此设备") }
    } }
    item { SectionTitle("连接信息") }
    item { FluentCard {
      Text("当前服务器", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      Text(state.server?.name ?: "未连接", style = MaterialTheme.typography.titleMedium)
      Text("OpenCode ${state.version.ifBlank { "—" }}", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } }
  }
}
