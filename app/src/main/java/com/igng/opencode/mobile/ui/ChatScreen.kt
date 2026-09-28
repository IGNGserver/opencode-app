package com.igng.opencode.mobile.ui

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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.igng.opencode.mobile.core.*

private enum class DetailTab(val label: String) { CHAT("对话"), TODO("待办"), CHANGES("改动"), FILES("文件"), CHILDREN("子任务") }

@Composable
fun ChatScreen(state: MobileState, controller: MobileController, onSessions: () -> Unit) {
  val session = state.session
  var tab by remember(state.sessionId) { mutableStateOf(DetailTab.CHAT) }
  var menu by remember { mutableStateOf(false) }
  var rename by remember { mutableStateOf(false) }
  var title by remember(session?.id) { mutableStateOf(session?.title ?: "") }
  var delete by remember { mutableStateOf(false) }
  if (session == null) {
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("选择一个会话", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("从会话列表进入，或先创建一项任务。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Button(shape = RoundedCornerShape(6.dp), onClick = onSessions) { Text("打开会话") }
      }
    }
    return
  }
  Column(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
      TextButton(shape = RoundedCornerShape(6.dp), onClick = onSessions) { Text("‹ 会话") }
      Column(Modifier.weight(1f)) {
        Text(session.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(state.project?.name ?: session.directory.substringAfterLast('/'), style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      state.tasks[session.id]?.let { StatePill(it.phase) }
      Box {
        TextButton(shape = RoundedCornerShape(6.dp), onClick = { menu = true }, modifier = Modifier.semantics {
          contentDescription = "更多会话操作"
        }) { Text("⋯") }
        DropdownMenu(menu, { menu = false }) {
          if (state.protocol == ServerProtocol.V1) {
            DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; title = session.title; rename = true })
            DropdownMenuItem(text = { Text("Fork 会话") }, onClick = { menu = false; controller.fork() })
            DropdownMenuItem(text = { Text("分享链接") }, onClick = { menu = false; controller.share() })
            DropdownMenuItem(text = { Text("取消分享") }, onClick = { menu = false; controller.unshare() })
          }
          DropdownMenuItem(text = { Text("总结会话") }, onClick = { menu = false; controller.summarize() })
          DropdownMenuItem(text = { Text("撤销最后一条消息") }, onClick = { menu = false; state.messages.lastOrNull()?.let { controller.revert(it.id) } })
          DropdownMenuItem(text = { Text("恢复撤销") }, onClick = { menu = false; controller.unrevert() })
          if (state.protocol == ServerProtocol.V1) DropdownMenuItem(text = { Text("删除会话", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; delete = true })
        }
      }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
      DetailTab.entries.forEach { item ->
        val count = when (item) { DetailTab.TODO -> state.todos.size; DetailTab.CHANGES -> state.changes.size; DetailTab.CHILDREN -> state.children.size; else -> 0 }
        val label = item.label + if (count > 0) " $count" else ""
        TabChip(label, tab == item) { tab = item; if (item == DetailTab.FILES) controller.listFiles() }
      }
    }
    HorizontalDivider()
    when (tab) {
      DetailTab.CHAT -> Conversation(state, controller, Modifier.weight(1f))
      DetailTab.TODO -> TodoPanel(state.todos, state.protocol, Modifier.weight(1f))
      DetailTab.CHANGES -> ChangesPanel(state.changes, state.protocol, Modifier.weight(1f))
      DetailTab.FILES -> FilesPanel(state, controller, Modifier.weight(1f))
      DetailTab.CHILDREN -> ChildrenPanel(state.children, controller, Modifier.weight(1f))
    }
    if (tab == DetailTab.CHAT) Composer(state, controller)
  }
  if (rename) AlertDialog(onDismissRequest = { rename = false }, title = { Text("重命名会话") },
    text = { TextField(title, { title = it }, label = { Text("标题") }) },
    confirmButton = { TextButton(shape = RoundedCornerShape(6.dp), onClick = { controller.rename(title); rename = false }) { Text("保存") } },
    dismissButton = { TextButton(shape = RoundedCornerShape(6.dp), onClick = { rename = false }) { Text("取消") } })
  if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("删除会话？") },
    text = { Text("此操作会删除服务器上的会话和消息。") },
    confirmButton = { TextButton(shape = RoundedCornerShape(6.dp), onClick = { controller.deleteSession(); delete = false }) { Text("删除") } },
    dismissButton = { TextButton(shape = RoundedCornerShape(6.dp), onClick = { delete = false }) { Text("取消") } })
}

@Composable
private fun TabChip(text: String, selected: Boolean, onClick: () -> Unit) {
  TextButton(shape = RoundedCornerShape(6.dp), onClick = onClick) {
    Text(text, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
      fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
  }
}

@Composable
private fun Conversation(state: MobileState, controller: MobileController, modifier: Modifier = Modifier) {
  val list = rememberLazyListState()
  val permissions = state.permissions.filter { it.sessionId == state.sessionId }
  val questions = state.questions.filter { it.sessionId == state.sessionId }
  val total = state.messages.size + permissions.size + questions.size
  val contentVersion = remember(state.messages) { state.messages.hashCode() }
  LaunchedEffect(state.sessionId, total, contentVersion) { if (total > 0) list.animateScrollToItem(total - 1) }
  LazyColumn(modifier.fillMaxWidth(), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
    if (state.messages.isEmpty() && permissions.isEmpty() && questions.isEmpty()) item {
      FluentCard {
        Text("开始这项任务", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text("在下方输入任务，或输入 / 使用服务器提供的命令。", color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }
    items(state.messages, key = { "message-${it.id}" }) { message -> MessageCard(message) }
    items(permissions, key = { "permission-${it.id}" }) { PermissionPanel(it, controller) }
    items(questions, key = { "question-${it.id}" }) { QuestionPanel(it, controller) }
  }
}

@Composable
private fun MessageCard(message: Message) {
  val user = message.role == "user"
  Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
    Text(if (user) "你" else "OpenCode", style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 5.dp, vertical = 4.dp))
    Surface(shape = RoundedCornerShape(12.dp), color = if (user) MaterialTheme.colorScheme.primary.copy(alpha = 0.09f) else MaterialTheme.colorScheme.surface,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))) {
      Column(Modifier.fillMaxWidth(if (user) 0.92f else 1f).padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        message.parts.forEach { part -> MessagePartView(part) }
        message.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
      }
    }
  }
}

@Composable
private fun MessagePartView(part: MessagePart) {
  when (part.type) {
    "text" -> MarkdownText(part.text)
    "reasoning" -> ExpandablePart("思考过程", part.text, defaultOpen = false)
    "tool" -> ExpandablePart(part.title.ifBlank { part.tool.ifBlank { "工具调用" } } + " · " + part.status,
      listOf(part.input.takeIf { it.isNotBlank() }?.let { "输入\n$it" }, part.output.takeIf { it.isNotBlank() }?.let { "输出\n$it" }).filterNotNull().joinToString("\n\n"), false)
    "file" -> InfoPart("文件", part.path.ifBlank { part.text })
    "patch", "diff" -> ExpandablePart("代码改动", part.patch.ifBlank { part.text }.ifBlank { part.output }.ifBlank { part.files.joinToString("\n") }, false)
    "agent", "subtask", "task" -> InfoPart("子任务", part.text.ifBlank { part.title }.ifBlank { part.path })
    "error" -> Text(part.error.ifBlank { part.text }.ifBlank { part.output }, color = MaterialTheme.colorScheme.error)
    "step-start", "step-finish", "snapshot", "compaction" -> Unit
    else -> InfoPart(part.type.ifBlank { "内容" }, part.text.ifBlank { part.title })
  }
}

@Composable
private fun InfoPart(label: String, content: String) {
  if (content.isBlank()) return
  Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(7.dp)).padding(10.dp)) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    Text(content, style = MaterialTheme.typography.bodyMedium)
  }
}

@Composable
private fun ExpandablePart(label: String, content: String, defaultOpen: Boolean) {
  var open by remember(label, content.take(30)) { mutableStateOf(defaultOpen) }
  Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(7.dp))) {
    Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(if (open) "⌄" else "›", color = MaterialTheme.colorScheme.primary)
      Spacer(Modifier.width(7.dp))
      Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
    if (open) {
      HorizontalDivider()
      Text(content.ifBlank { "无详细输出" }, modifier = Modifier.fillMaxWidth().padding(10.dp),
        style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
  }
}

@Composable
private fun MarkdownText(markdown: String) {
  val content = remember(markdown) { buildAnnotatedString {
    markdown.lines().forEachIndexed { index, line ->
      val heading = line.startsWith('#')
      val trimmed = if (heading) line.trimStart('#', ' ') else line
      val chunks = trimmed.split("**")
      if (heading) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(trimmed) }
      else chunks.forEachIndexed { i, chunk ->
        if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(chunk) } else append(chunk)
      }
      if (index != markdown.lines().lastIndex) append('\n')
    }
  } }
  Text(content, style = MaterialTheme.typography.bodyMedium)
}

@Composable
fun PermissionPanel(request: PermissionRequest, controller: MobileController) {
  var confirmAlways by remember { mutableStateOf(false) }
  FluentCard {
    StatePill(TaskPhase.WAITING_PERMISSION)
    Spacer(Modifier.height(10.dp))
    Text("${request.action.ifBlank { "操作" }} 请求授权", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(request.detail, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)).padding(10.dp))
    Spacer(Modifier.height(10.dp))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      OutlinedButton(shape = RoundedCornerShape(6.dp), onClick = { controller.replyPermission(request, "reject") }) { Text("拒绝") }
      Button(shape = RoundedCornerShape(6.dp), onClick = { controller.replyPermission(request, "once") }) { Text("允许一次") }
      TextButton(shape = RoundedCornerShape(6.dp), onClick = { confirmAlways = true }) { Text("始终允许") }
      }
    }
  }
  if (confirmAlways) AlertDialog(onDismissRequest = { confirmAlways = false }, title = { Text("始终允许此操作？") },
    text = { Text("此规则将在当前 OpenCode 会话后续请求中继续生效。") },
    confirmButton = { TextButton(shape = RoundedCornerShape(6.dp), onClick = { controller.replyPermission(request, "always"); confirmAlways = false }) { Text("始终允许") } },
    dismissButton = { TextButton(shape = RoundedCornerShape(6.dp), onClick = { confirmAlways = false }) { Text("取消") } })
}

@Composable
fun QuestionPanel(request: QuestionRequest, controller: MobileController) {
  var answers by remember(request.id) { mutableStateOf(List(request.questions.size) { emptyList<String>() }) }
  var custom by remember(request.id) { mutableStateOf(List(request.questions.size) { "" }) }
  FluentCard {
    StatePill(TaskPhase.WAITING_QUESTION)
    request.questions.forEachIndexed { index, question ->
      Spacer(Modifier.height(12.dp))
      Text(question.title, style = MaterialTheme.typography.titleMedium)
      question.options.forEach { option ->
        Row(Modifier.fillMaxWidth().clickable {
          answers = answers.toMutableList().also { list ->
            list[index] = if (question.multiple) {
              if (option.label in list[index]) list[index] - option.label else list[index] + option.label
            } else listOf(option.label)
          }
        }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
          if (question.multiple) Checkbox(option.label in answers[index], onCheckedChange = null)
          else RadioButton(option.label in answers[index], onClick = null)
          Column {
            Text(option.label, style = MaterialTheme.typography.bodyMedium)
            if (option.description.isNotBlank()) Text(option.description, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }
      if (question.custom) {
        TextField(custom[index], { value -> custom = custom.toMutableList().also { it[index] = value } },
          label = { Text("自定义回答") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
      }
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(shape = RoundedCornerShape(6.dp), onClick = { controller.rejectQuestion(request) }) { Text("取消") }
      Button(shape = RoundedCornerShape(6.dp), onClick = {
        controller.replyQuestion(request, answers.mapIndexed { index, list ->
          val value = custom[index].trim()
          if (value.isBlank()) list else if (request.questions[index].multiple) list + value else listOf(value)
        })
      }, enabled = answers.indices.all { answers[it].isNotEmpty() || (request.questions[it].custom && custom[it].isNotBlank()) }) { Text("提交回答") }
    }
  }
}

@Composable
private fun Composer(state: MobileState, controller: MobileController) {
  var draft by remember(state.sessionId) { mutableStateOf("") }
  var agentMenu by remember { mutableStateOf(false) }
  var modelMenu by remember { mutableStateOf(false) }
  var files by remember { mutableStateOf(false) }
  var fileQuery by remember { mutableStateOf("") }
  val task = state.tasks[state.sessionId]
  val waiting = task?.phase in setOf(TaskPhase.WAITING_PERMISSION, TaskPhase.WAITING_QUESTION)
  val running = task?.phase in setOf(TaskPhase.THINKING, TaskPhase.TOOL, TaskPhase.SUBAGENT, TaskPhase.TESTING)
  Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 8.dp)) {
    if (!state.connected) Text("离线缓存 · 重新连接后可发送任务", color = Fluent.amber,
      style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 6.dp))
    if (waiting) Text("请先处理当前会话的待处理事项", color = Fluent.amber,
      style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 6.dp))
    if (draft.startsWith('/')) {
      val command = draft.substringAfter('/').substringBefore(' ')
      state.commands.filter { it.name.startsWith(command, true) }.take(4).forEach { suggestion ->
        TextButton(shape = RoundedCornerShape(6.dp), onClick = { draft = "/${suggestion.name} " }) { Text("/${suggestion.name}  ${suggestion.description}") }
      }
    }
    Surface(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), color = MaterialTheme.colorScheme.surface) {
      Column(Modifier.padding(10.dp)) {
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp, max = 150.dp)) {
          if (draft.isEmpty()) Text("输入任务…", color = MaterialTheme.colorScheme.onSurfaceVariant)
          BasicTextField(draft, { draft = it }, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface), maxLines = 6)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
          TextButton(shape = RoundedCornerShape(6.dp), onClick = { files = true }) { Text("+") }
          Box {
            TextButton(shape = RoundedCornerShape(6.dp), onClick = { agentMenu = true }) { Text(state.agent ?: "Agent") }
            DropdownMenu(agentMenu, { agentMenu = false }) {
              DropdownMenuItem(text = { Text("服务器默认") }, onClick = { controller.chooseAgent(null); agentMenu = false })
              state.agents.forEach { agent -> DropdownMenuItem(text = { Text(agent.name) }, onClick = { controller.chooseAgent(agent.name); agentMenu = false }) }
            }
          }
          Box(Modifier.weight(1f)) {
            TextButton(shape = RoundedCornerShape(6.dp), onClick = { modelMenu = true }) { Text(state.model?.label ?: "默认模型", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            DropdownMenu(modelMenu, { modelMenu = false }) {
              DropdownMenuItem(text = { Text("服务器默认") }, onClick = { controller.chooseModel(null); modelMenu = false })
              state.models.forEach { model -> DropdownMenuItem(text = { Text("${model.providerId} · ${model.label}") }, onClick = { controller.chooseModel(model); modelMenu = false }) }
            }
          }
          if (running) FilledTonalButton(shape = RoundedCornerShape(6.dp), onClick = controller::abort) { Text("停止") }
          else Button(shape = RoundedCornerShape(6.dp), onClick = { val text = draft.trim(); controller.send(text) { draft = "" } },
            enabled = state.connected && !state.cached && !waiting && !running && draft.isNotBlank()) { Text("发送 ↑") }
        }
      }
    }
  }
  if (files) AlertDialog(onDismissRequest = { files = false }, title = { Text("引用文件") }, text = {
    Column {
      TextField(fileQuery, { fileQuery = it; if (it.length >= 2) controller.searchFiles(it) }, label = { Text("文件名") })
      state.searchResults.take(10).forEach { path -> TextButton(shape = RoundedCornerShape(6.dp), onClick = { draft += " @$path"; files = false }) { Text(path, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
    }
  }, confirmButton = { TextButton(shape = RoundedCornerShape(6.dp), onClick = { files = false }) { Text("关闭") } })
}

@Composable
private fun TodoPanel(todos: List<TodoItem>, protocol: ServerProtocol, modifier: Modifier = Modifier) {
  LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (todos.isEmpty()) item { Text(if (protocol == ServerProtocol.V2) "当前 OpenCode V2 接口未提供待办数据。" else "当前会话没有待办事项。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    items(todos) { todo -> FluentCard {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (todo.status == "completed") "✓" else "○", color = if (todo.status == "completed") Fluent.green else Fluent.blue)
        Spacer(Modifier.width(10.dp))
        Text(todo.content, modifier = Modifier.weight(1f))
        Text(todo.priority, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    } }
  }
}

@Composable
private fun ChangesPanel(changes: List<FileChange>, protocol: ServerProtocol, modifier: Modifier = Modifier) {
  LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (changes.isEmpty()) item { Text(if (protocol == ServerProtocol.V2) "当前 OpenCode V2 接口未提供会话改动数据。" else "目前没有改动。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    items(changes) { change ->
      var open by remember(change.path) { mutableStateOf(false) }
      FluentCard(onClick = { open = !open }) {
        Row {
          Text(change.path, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
          Text("+${change.additions}", color = Fluent.green)
          Spacer(Modifier.width(6.dp))
          Text("-${change.deletions}", color = Fluent.red)
        }
        if (open) {
          Spacer(Modifier.height(10.dp))
          Text("改动", style = MaterialTheme.typography.labelMedium)
          Text(change.patch.ifBlank { change.after }.ifBlank { "无内容" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        }
      }
    }
  }
}

@Composable
private fun FilesPanel(state: MobileState, controller: MobileController, modifier: Modifier = Modifier) {
  val clipboard = LocalClipboardManager.current
  var query by remember { mutableStateOf("") }
  Column(modifier.padding(16.dp)) {
    TextField(query, { query = it; if (it.length >= 2) controller.searchFiles(it) }, label = { Text("搜索文件") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    Row(verticalAlignment = Alignment.CenterVertically) {
      TextButton(shape = RoundedCornerShape(6.dp), onClick = { controller.listFiles(state.filePath.substringBeforeLast('/', ".")) }) { Text("‹ 上级") }
      Text(state.filePath, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    HorizontalDivider()
    if (state.fileBinary) {
      Text("这是二进制文件，当前仅支持查看文件列表。", color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 16.dp))
    } else if (state.fileText != null) {
      TextButton(shape = RoundedCornerShape(6.dp), onClick = { clipboard.setText(AnnotatedString(state.fileText)) }) { Text("复制内容") }
      Text(state.fileText, modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodyMedium)
    } else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      val results = if (query.isNotBlank()) state.searchResults.map { FileNode(it, "file") } else state.files
      items(results) { node -> FluentCard(onClick = { if (node.type == "directory") controller.listFiles(node.path) else controller.readFile(node.path) }) {
        Text((if (node.type == "directory") "▣  " else "▤  ") + node.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium)
      } }
    }
  }
}

@Composable
private fun ChildrenPanel(children: List<Session>, controller: MobileController, modifier: Modifier = Modifier) {
  LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (children.isEmpty()) item { Text("当前会话没有子任务。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    items(children) { child -> FluentCard(onClick = { controller.selectSession(child.id) }) {
      Text(child.title, style = MaterialTheme.typography.titleMedium)
      Text("打开子会话 ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    } }
  }
}
