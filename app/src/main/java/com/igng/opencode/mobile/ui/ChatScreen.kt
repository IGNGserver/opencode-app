package com.igng.opencode.mobile.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.igng.opencode.mobile.core.*

private enum class DetailTab(val label: String) { CHAT("对话"), TODO("待办"), CHANGES("改动"), FILES("文件"), CHILDREN("子任务") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
  state: MobileState,
  controller: MobileController,
  onBack: () -> Unit
) {
  val session = state.session
  val availableTabs = remember(state.protocol) {
    if (state.protocol == ServerProtocol.V2) {
      listOf(DetailTab.CHAT, DetailTab.FILES, DetailTab.CHILDREN)
    } else {
      DetailTab.entries
    }
  }

  var tab by remember(state.sessionId) { mutableStateOf(DetailTab.CHAT) }
  var menuSheet by remember { mutableStateOf(false) }
  var rename by remember { mutableStateOf(false) }
  var title by remember(session?.id) { mutableStateOf(session?.title ?: "") }
  var delete by remember { mutableStateOf(false) }

  if (session == null) {
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("会话未找到或已关闭", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("请返回会话列表重新选择或创建任务。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(shape = RoundedCornerShape(8.dp), onClick = onBack) { Text("返回列表") }
      }
    }
    return
  }

  Column(Modifier.fillMaxSize()) {
    // 沉浸式顶部栏：返回、标题与路径、状态徽章、更多操作
    Surface(
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 1.dp,
      modifier = Modifier.fillMaxWidth()
    ) {
      Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        IconButton(onClick = onBack) {
          Text("‹", fontSize = 28.sp, fontWeight = FontWeight.Light, color = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f)) {
          Text(session.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
          Text(
            state.project?.name ?: session.directory.substringAfterLast('/'),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        state.tasks[session.id]?.let { StatePill(it.phase) }
        IconButton(
          onClick = { menuSheet = true },
          modifier = Modifier.semantics { contentDescription = "更多会话操作" }
        ) {
          Text("⋯", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    }

    // 动态 Tab 切换栏（V2 自适应隐藏无数据 Tab）
    Row(
      Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      availableTabs.forEach { item ->
        val count = when (item) {
          DetailTab.TODO -> state.todos.size
          DetailTab.CHANGES -> state.changes.size
          DetailTab.CHILDREN -> state.children.size
          else -> 0
        }
        val label = item.label + if (count > 0) " $count" else ""
        TabChip(label, tab == item) {
          tab = item
          if (item == DetailTab.FILES) controller.listFiles()
        }
      }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

    // 主视图内容
    when (tab) {
      DetailTab.CHAT -> Conversation(state, controller, Modifier.weight(1f))
      DetailTab.TODO -> TodoPanel(state.todos, state.protocol, Modifier.weight(1f))
      DetailTab.CHANGES -> ChangesPanel(state.changes, state.protocol, Modifier.weight(1f))
      DetailTab.FILES -> FilesPanel(state, controller, onInsertRef = { ref ->
        // 允许从文件浏览器一键插入到 Chat 输入流
        tab = DetailTab.CHAT
      }, Modifier.weight(1f))
      DetailTab.CHILDREN -> ChildrenPanel(state.children, controller, Modifier.weight(1f))
    }

    // 底部输入区域仅在 Chat Tab 下展示
    if (tab == DetailTab.CHAT) {
      Composer(state, controller)
    }
  }

  // 更多操作 Bottom Sheet
  if (menuSheet) {
    ModalBottomSheet(
      onDismissRequest = { menuSheet = false },
      dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
      Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("会话管理与快捷动作", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))

        if (state.protocol == ServerProtocol.V1) {
          ActionRow("✏️ 重命名会话") { menuSheet = false; title = session.title; rename = true }
          ActionRow("🌿 分支 / 分叉会话 (Fork)") { menuSheet = false; controller.fork() }
          ActionRow("🔗 分享链接") { menuSheet = false; controller.share() }
          ActionRow("🔒 取消分享") { menuSheet = false; controller.unshare() }
        }

        ActionRow("📦 压缩与总结上下文 (Summarize)") { menuSheet = false; controller.summarize() }
        ActionRow("↩️ 撤销到上一条消息 (Revert)") { menuSheet = false; state.messages.lastOrNull()?.let { controller.revert(it.id) } }
        ActionRow("↪️ 恢复撤销 (Unrevert)") { menuSheet = false; controller.unrevert() }

        if (state.protocol == ServerProtocol.V1) {
          HorizontalDivider(Modifier.padding(vertical = 4.dp))
          ActionRow("🗑️ 删除此会话", isDestructive = true) { menuSheet = false; delete = true }
        }
        Spacer(Modifier.height(12.dp))
      }
    }
  }

  if (rename) {
    AlertDialog(
      onDismissRequest = { rename = false },
      title = { Text("重命名会话") },
      text = { TextField(title, { title = it }, label = { Text("新标题") }, modifier = Modifier.fillMaxWidth()) },
      confirmButton = { TextButton(shape = RoundedCornerShape(6.dp), onClick = { controller.rename(title); rename = false }) { Text("保存") } },
      dismissButton = { TextButton(shape = RoundedCornerShape(6.dp), onClick = { rename = false }) { Text("取消") } }
    )
  }

  if (delete) {
    AlertDialog(
      onDismissRequest = { delete = false },
      title = { Text("确定删除此会话？") },
      text = { Text("将永久移除服务端上该会话的全部消息历史。") },
      confirmButton = { TextButton(shape = RoundedCornerShape(6.dp), onClick = { controller.deleteSession(); delete = false; onBack() }) { Text("彻底删除", color = MaterialTheme.colorScheme.error) } },
      dismissButton = { TextButton(shape = RoundedCornerShape(6.dp), onClick = { delete = false }) { Text("取消") } }
    )
  }
}

@Composable
private fun ActionRow(text: String, isDestructive: Boolean = false, onClick: () -> Unit) {
  Surface(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    shape = RoundedCornerShape(8.dp),
    color = if (isDestructive) MaterialTheme.colorScheme.error.copy(alpha = 0.08f) else Color.Transparent
  ) {
    Row(Modifier.padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(text, style = MaterialTheme.typography.bodyLarge, color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
  }
}

@Composable
private fun TabChip(text: String, selected: Boolean, onClick: () -> Unit) {
  TextButton(
    shape = RoundedCornerShape(8.dp),
    onClick = onClick,
    colors = ButtonDefaults.textButtonColors(
      containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    )
  ) {
    Text(
      text,
      color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
      fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
    )
  }
}

@Composable
private fun Conversation(state: MobileState, controller: MobileController, modifier: Modifier = Modifier) {
  val list = rememberLazyListState()
  val permissions = state.permissions.filter { it.sessionId == state.sessionId }
  val questions = state.questions.filter { it.sessionId == state.sessionId }
  val total = state.messages.size + permissions.size + questions.size
  // Cheap content signal: identity, part count and streaming text length of the last message.
  // Captures new messages and streaming updates in O(1), instead of hashing the whole list each
  // state emission just to decide whether to auto-scroll.
  val contentKey = state.messages.lastOrNull()?.let { last ->
    Triple(last.id, last.parts.size, last.parts.lastOrNull()?.text?.length ?: 0)
  }

  LaunchedEffect(state.sessionId, contentKey, total) {
    if (total > 0) list.animateScrollToItem(total - 1)
  }

  LazyColumn(
    modifier = modifier.fillMaxWidth(),
    state = list,
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp)
  ) {
    if (state.messages.isEmpty() && permissions.isEmpty() && questions.isEmpty()) {
      item {
        FluentCard {
          Text("会话就绪", style = MaterialTheme.typography.titleLarge)
          Spacer(Modifier.height(4.dp))
          Text("在下方直接输入你的指令，或通过快捷栏引用工程文件与工具命令。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
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
    Text(
      if (user) "你" else "OpenCode",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
    )
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = if (user) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    ) {
      Column(
        Modifier.fillMaxWidth(if (user) 0.92f else 1f).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
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
    "reasoning" -> ExpandablePart("💡 思考推理过程", part.text, defaultOpen = false)
    "tool" -> ExpandablePart(
      "🛠️ " + part.title.ifBlank { part.tool.ifBlank { "工具调用" } } + " · " + part.status,
      listOf(part.input.takeIf { it.isNotBlank() }?.let { "输入\n$it" }, part.output.takeIf { it.isNotBlank() }?.let { "输出\n$it" }).filterNotNull().joinToString("\n\n"),
      defaultOpen = false
    )
    "file" -> InfoPart("文件", part.path.ifBlank { part.text })
    "patch", "diff" -> ExpandablePart(
      "📝 代码变更",
      part.patch.ifBlank { part.text }.ifBlank { part.output }.ifBlank { part.files.joinToString("\n") },
      defaultOpen = false,
      isDiff = true
    )
    "agent", "subtask", "task" -> InfoPart("子任务", part.text.ifBlank { part.title }.ifBlank { part.path })
    "error" -> Text(part.error.ifBlank { part.text }.ifBlank { part.output }, color = MaterialTheme.colorScheme.error)
    "step-start", "step-finish", "snapshot", "compaction" -> Unit
    else -> InfoPart(part.type.ifBlank { "内容" }, part.text.ifBlank { part.title })
  }
}

@Composable
private fun InfoPart(label: String, content: String) {
  if (content.isBlank()) return
  Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(10.dp)) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    Text(content, style = MaterialTheme.typography.bodyMedium)
  }
}

@Composable
private fun ExpandablePart(label: String, content: String, defaultOpen: Boolean, isDiff: Boolean = false) {
  var open by remember(label, content.take(30)) { mutableStateOf(defaultOpen) }
  Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f), RoundedCornerShape(8.dp))) {
    Row(
      Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(if (open) "⌄" else "›", color = MaterialTheme.colorScheme.primary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
      Spacer(Modifier.width(8.dp))
      Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
    if (open) {
      HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
      if (isDiff) {
        DiffCodeBlock(content)
      } else {
        Text(
          content.ifBlank { "无详细输出" },
          modifier = Modifier.fillMaxWidth().padding(12.dp),
          style = MaterialTheme.typography.bodyMedium,
          fontFamily = FontFamily.Monospace
        )
      }
    }
  }
}

@Composable
private fun DiffCodeBlock(diffText: String) {
  val lines = remember(diffText) { diffText.lines() }
  Column(
    Modifier.fillMaxWidth()
      .horizontalScroll(rememberScrollState())
      .background(MaterialTheme.colorScheme.background)
      .padding(8.dp)
  ) {
    lines.forEach { line ->
      val (bg, fg) = when {
        line.startsWith("+") && !line.startsWith("+++") -> Fluent.green.copy(alpha = 0.15f) to Fluent.green
        line.startsWith("-") && !line.startsWith("---") -> Fluent.red.copy(alpha = 0.15f) to Fluent.red
        line.startsWith("@@") -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        else -> Color.Transparent to MaterialTheme.colorScheme.onSurface
      }
      Row(
        Modifier.fillMaxWidth().background(bg).padding(horizontal = 4.dp, vertical = 1.dp)
      ) {
        Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = fg, softWrap = false)
      }
    }
  }
}

@Composable
private fun MarkdownText(markdown: String) {
  val content = remember(markdown) {
    buildAnnotatedString {
      val lines = markdown.lines()
      val lastIndex = lines.lastIndex
      lines.forEachIndexed { index, line ->
        val heading = line.startsWith('#')
        val trimmed = if (heading) line.trimStart('#', ' ') else line
        if (heading) {
          withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp)) { append(trimmed) }
        } else {
          val chunks = trimmed.split("**")
          chunks.forEachIndexed { i, chunk ->
            if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(chunk) } else append(chunk)
          }
        }
        if (index != lastIndex) append('\n')
      }
    }
  }
  Text(content, style = MaterialTheme.typography.bodyMedium, lineHeight = 22.sp)
}

@Composable
fun PermissionPanel(request: PermissionRequest, controller: MobileController) {
  val isDangerous = request.action.contains("shell", true) || request.action.contains("write", true) || request.action.contains("delete", true)
  val borderColor = if (isDangerous) Fluent.amber else MaterialTheme.colorScheme.primary

  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.5.dp, borderColor.copy(alpha = 0.8f)),
    modifier = Modifier.fillMaxWidth()
  ) {
    Column(Modifier.padding(16.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        StatePill(TaskPhase.WAITING_PERMISSION, if (isDangerous) "需确认关键操作" else "等待操作授权")
        Spacer(Modifier.weight(1f))
        Text(request.action.ifBlank { "操作" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
      }
      Spacer(Modifier.height(10.dp))
      Text("OpenCode 正在请求执行以下动作：", style = MaterialTheme.typography.titleMedium)
      Spacer(Modifier.height(8.dp))
      Text(
        request.detail,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth()
          .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
          .padding(10.dp)
      )
      Spacer(Modifier.height(12.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(shape = RoundedCornerShape(6.dp), onClick = { controller.replyPermission(request, "reject") }) {
          Text("拒绝")
        }
        Button(
          shape = RoundedCornerShape(6.dp),
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
          onClick = { controller.replyPermission(request, "once") }
        ) {
          Text("允许一次")
        }
        TextButton(shape = RoundedCornerShape(6.dp), onClick = { controller.replyPermission(request, "always") }) {
          Text("当前会话记住")
        }
      }
    }
  }
}

@Composable
fun QuestionPanel(request: QuestionRequest, controller: MobileController) {
  var answers by remember(request.id) { mutableStateOf(List(request.questions.size) { emptyList<String>() }) }
  var custom by remember(request.id) { mutableStateOf(List(request.questions.size) { "" }) }

  FluentCard {
    StatePill(TaskPhase.WAITING_QUESTION, "需要你的回答")
    request.questions.forEachIndexed { index, question ->
      Spacer(Modifier.height(12.dp))
      Text(question.title, style = MaterialTheme.typography.titleMedium)
      question.options.forEach { option ->
        Row(
          Modifier.fillMaxWidth().clickable {
            answers = answers.toMutableList().also { list ->
              list[index] = if (question.multiple) {
                if (option.label in list[index]) list[index] - option.label else list[index] + option.label
              } else listOf(option.label)
            }
          }.padding(vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          if (question.multiple) Checkbox(option.label in answers[index], onCheckedChange = null)
          else RadioButton(option.label in answers[index], onClick = null)
          Spacer(Modifier.width(6.dp))
          Column {
            Text(option.label, style = MaterialTheme.typography.bodyMedium)
            if (option.description.isNotBlank()) Text(option.description, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
      }
      if (question.custom) {
        Spacer(Modifier.height(6.dp))
        TextField(
          value = custom[index],
          onValueChange = { value -> custom = custom.toMutableList().also { it[index] = value } },
          placeholder = { Text("输入自定义回答…") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true
        )
      }
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(shape = RoundedCornerShape(6.dp), onClick = { controller.rejectQuestion(request) }) { Text("取消") }
      Button(
        shape = RoundedCornerShape(6.dp),
        onClick = {
          controller.replyQuestion(request, answers.mapIndexed { index, list ->
            val value = custom[index].trim()
            if (value.isBlank()) list else if (request.questions[index].multiple) list + value else listOf(value)
          })
        },
        enabled = answers.indices.all { answers[it].isNotEmpty() || (request.questions[it].custom && custom[it].isNotBlank()) }
      ) { Text("提交回答") }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Composer(state: MobileState, controller: MobileController) {
  var draft by remember(state.sessionId) { mutableStateOf("") }
  var filePicker by remember { mutableStateOf(false) }
  var fileQuery by remember { mutableStateOf("") }
  var commandPicker by remember { mutableStateOf(false) }
  var modelSheet by remember { mutableStateOf(false) }
  var agentSheet by remember { mutableStateOf(false) }

  val task = state.tasks[state.sessionId]
  val waiting = task?.phase in TaskState.WAITING_PHASES
  val running = task?.phase in TaskState.RUNNING_PHASES

  Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 6.dp)) {
    if (!state.connected) {
      Text("离线缓存模式 · 重新连接后可继续对话", color = Fluent.amber, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 4.dp))
    }
    if (waiting) {
      Text("请先处理上方的确认请求", color = Fluent.amber, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 4.dp))
    }

    // 快捷工具栏（单手易用胶囊按钮）
    Row(
      Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      ActionChip("📎 @引用文件") { filePicker = true }
      if (state.commands.isNotEmpty()) {
        ActionChip("⚡ /快捷命令") { commandPicker = true }
      }
      ActionChip("🤖 " + (state.agent ?: "默认 Agent")) { agentSheet = true }
      ActionChip("🧠 " + (state.model?.label ?: "默认模型")) { modelSheet = true }
    }

    // 输入框卡片
    Surface(
      shape = RoundedCornerShape(12.dp),
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
      color = MaterialTheme.colorScheme.surface
    ) {
      Column(Modifier.padding(10.dp)) {
        Box(Modifier.fillMaxWidth().heightIn(min = 44.dp, max = 140.dp)) {
          if (draft.isEmpty()) {
            Text("输入任务描述或向 Agent 提问…", color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
          BasicTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            maxLines = 6
          )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
          if (running) {
            FilledTonalButton(shape = RoundedCornerShape(6.dp), onClick = controller::abort) {
              Text("停止", color = MaterialTheme.colorScheme.error)
            }
          } else {
            Button(
              shape = RoundedCornerShape(6.dp),
              onClick = {
                val text = draft.trim()
                controller.send(text) { draft = "" }
              },
              enabled = state.connected && !state.cached && !waiting && !running && draft.isNotBlank()
            ) {
              Text("发送 ↑")
            }
          }
        }
      }
    }
  }

  // 引用文件弹窗
  if (filePicker) {
    AlertDialog(
      onDismissRequest = { filePicker = false },
      title = { Text("引用文件到对话 (@file)") },
      text = {
        Column {
          TextField(
            value = fileQuery,
            onValueChange = {
              fileQuery = it
              if (it.length >= 2) controller.searchFiles(it)
            },
            placeholder = { Text("搜索工程文件名…") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
          )
          Spacer(Modifier.height(8.dp))
          LazyColumn(Modifier.heightIn(max = 240.dp)) {
            val items = if (fileQuery.isNotBlank()) state.searchResults else state.files.map { it.path }
            if (items.isEmpty()) item { Text("无匹配文件", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
            items(items.take(15)) { path ->
              TextButton(
                shape = RoundedCornerShape(6.dp),
                onClick = {
                  draft = if (draft.isBlank()) "@$path " else "$draft @$path "
                  filePicker = false
                },
                modifier = Modifier.fillMaxWidth()
              ) {
                Text(path, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
              }
            }
          }
        }
      },
      confirmButton = { TextButton(shape = RoundedCornerShape(6.dp), onClick = { filePicker = false }) { Text("关闭") } }
    )
  }

  // 快捷命令弹窗
  if (commandPicker) {
    AlertDialog(
      onDismissRequest = { commandPicker = false },
      title = { Text("选择斜杠命令") },
      text = {
        LazyColumn(Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          items(state.commands) { cmd ->
            FluentCard(onClick = {
              draft = "/${cmd.name} "
              commandPicker = false
            }) {
              Text("/${cmd.name}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
              if (cmd.description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(cmd.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }
          }
        }
      },
      confirmButton = { TextButton(shape = RoundedCornerShape(6.dp), onClick = { commandPicker = false }) { Text("取消") } }
    )
  }

  // 模型选择 Bottom Sheet
  if (modelSheet) {
    ModalBottomSheet(onDismissRequest = { modelSheet = false }, dragHandle = { BottomSheetDefaults.DragHandle() }) {
      Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).navigationBarsPadding()) {
        Text("选择 AI 模型", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          item {
            ActionRow("🌐 服务器默认设置") { controller.chooseModel(null); modelSheet = false }
          }
          items(state.models) { model ->
            ActionRow("${model.providerId} · ${model.label}") {
              controller.chooseModel(model)
              modelSheet = false
            }
          }
        }
      }
    }
  }

  // Agent 选择 Bottom Sheet
  if (agentSheet) {
    ModalBottomSheet(onDismissRequest = { agentSheet = false }, dragHandle = { BottomSheetDefaults.DragHandle() }) {
      Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp).navigationBarsPadding()) {
        Text("选择专用 Agent", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          item {
            ActionRow("🌐 服务器默认 Agent") { controller.chooseAgent(null); agentSheet = false }
          }
          items(state.agents) { agent ->
            ActionRow(agent.name + if (agent.description.isNotBlank()) " (${agent.description})" else "") {
              controller.chooseAgent(agent.name)
              agentSheet = false
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ActionChip(label: String, onClick: () -> Unit) {
  Surface(
    shape = RoundedCornerShape(6.dp),
    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
    modifier = Modifier.clickable(onClick = onClick)
  ) {
    Text(
      label,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSecondaryContainer,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
    )
  }
}

@Composable
private fun TodoPanel(todos: List<TodoItem>, protocol: ServerProtocol, modifier: Modifier = Modifier) {
  LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (todos.isEmpty()) item {
      Text(if (protocol == ServerProtocol.V2) "当前 OpenCode V2 未提供独立待办列表。" else "当前会话暂无待办事项。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    items(todos) { todo ->
      FluentCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(if (todo.status == "completed") "✓" else "○", color = if (todo.status == "completed") Fluent.green else Fluent.blue)
          Spacer(Modifier.width(10.dp))
          Text(todo.content, modifier = Modifier.weight(1f))
          Text(todo.priority, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    }
  }
}

@Composable
private fun ChangesPanel(changes: List<FileChange>, protocol: ServerProtocol, modifier: Modifier = Modifier) {
  LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (changes.isEmpty()) item {
      Text(if (protocol == ServerProtocol.V2) "当前 OpenCode V2 接口未暴露独立差异汇总。" else "暂无文件改动记录。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    items(changes) { change ->
      var open by remember(change.path) { mutableStateOf(false) }
      FluentCard(onClick = { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(change.path, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
          Text("+${change.additions}", color = Fluent.green)
          Spacer(Modifier.width(6.dp))
          Text("-${change.deletions}", color = Fluent.red)
        }
        if (open) {
          Spacer(Modifier.height(10.dp))
          DiffCodeBlock(change.patch.ifBlank { change.after }.ifBlank { "无改动详情" })
        }
      }
    }
  }
}

@Composable
private fun FilesPanel(
  state: MobileState,
  controller: MobileController,
  onInsertRef: (String) -> Unit,
  modifier: Modifier = Modifier
) {
  val clipboard = LocalClipboardManager.current
  var query by remember { mutableStateOf("") }

  Column(modifier.padding(16.dp)) {
    TextField(
      value = query,
      onValueChange = {
        query = it
        if (it.length >= 2) controller.searchFiles(it)
      },
      placeholder = { Text("搜索工程目录文件…") },
      modifier = Modifier.fillMaxWidth(),
      singleLine = true
    )
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
      TextButton(shape = RoundedCornerShape(6.dp), onClick = { controller.listFiles(state.filePath.substringBeforeLast('/', ".")) }) {
        Text("‹ 返回上级")
      }
      Spacer(Modifier.width(8.dp))
      Text(state.filePath, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
    }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))

    if (state.fileBinary) {
      Text("此文件为二进制文件，暂不支持预览。", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
    } else if (state.fileText != null) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(shape = RoundedCornerShape(6.dp), onClick = { onInsertRef(state.filePath) }) {
          Text("引用到当前对话")
        }
        OutlinedButton(shape = RoundedCornerShape(6.dp), onClick = { clipboard.setText(AnnotatedString(state.fileText ?: "")) }) {
          Text("复制全文")
        }
      }
      Spacer(Modifier.height(8.dp))
      Text(
        state.fileText ?: "",
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        style = MaterialTheme.typography.bodyMedium
      )
    } else {
      LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val results = if (query.isNotBlank()) state.searchResults.map { FileNode(it, "file") } else state.files
        items(results) { node ->
          FluentCard(onClick = {
            if (node.type == "directory") controller.listFiles(node.path) else controller.readFile(node.path)
          }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(if (node.type == "directory") "📁 " else "📄 ")
              Spacer(Modifier.width(6.dp))
              Text(node.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ChildrenPanel(children: List<Session>, controller: MobileController, modifier: Modifier = Modifier) {
  LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (children.isEmpty()) item {
      Text("当前会话没有派生子任务。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    items(children) { child ->
      FluentCard(onClick = { controller.selectSession(child.id) }) {
        Text(child.title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text("切换到此子会话 ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
      }
    }
  }
}
