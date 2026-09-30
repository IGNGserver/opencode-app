package com.igng.opencode.mobile.ui

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
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
import kotlinx.coroutines.launch
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.SuperBottomSheet
import top.yukonga.miuix.kmp.extra.SuperDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

private enum class DetailTab(val label: String) { CHAT("对话"), TODO("待办"), CHANGES("改动"), FILES("文件"), CHILDREN("子任务") }

@Composable
fun ChatScreen(
  state: MobileState,
  controller: MobileController,
  drafts: MutableMap<String, String>,
  onBack: () -> Unit
) {
  val session = state.session
  val availableTabs = remember(state.protocol) {
    if (!state.protocol.supportsTodosAndDiff) {
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

  state.savedPermissions?.let { rules ->
    SuperDialog(
      title = "已保存的项目权限",
      show = true,
      onDismissRequest = controller::closeSavedPermissions
    ) {
      Column(Modifier.padding(top = 8.dp)) {
        if (rules.isEmpty()) {
          Text("没有已保存规则", style = MiuixTheme.textStyles.body2)
        }
        rules.forEach { rule ->
          Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(Modifier.weight(1f)) {
              Text(rule.action, style = MiuixTheme.textStyles.headline2)
              Text(rule.resource, style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
            }
            TextButton(
              text = "撤销",
              colors = ButtonDefaults.textButtonColors(
                textColor = MiuixColorTokens.Error
              ),
              onClick = { controller.revokeSavedPermission(rule) }
            )
          }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
          TextButton(text = "关闭", onClick = controller::closeSavedPermissions)
        }
      }
    }
  }

  if (session == null) {
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("会话未找到或已关闭", style = MiuixTheme.textStyles.title2)
        Spacer(Modifier.height(8.dp))
        Text("请返回会话列表重新选择或创建任务。", style = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
        Spacer(Modifier.height(16.dp))
        Button(
          onClick = onBack,
          colors = ButtonDefaults.buttonColorsPrimary()
        ) {
          Text("返回列表")
        }
      }
    }
    return
  }

  Column(Modifier.fillMaxSize()) {
    // 顶部小标题栏：使用 MIUIX SmallTopAppBar 规范组件
    SmallTopAppBar(
      title = session.title,
      navigationIcon = {
        IconButton(onClick = onBack) {
          Icon(
            imageVector = MiuixIcons.Back,
            contentDescription = "返回",
            tint = MiuixTheme.colorScheme.onSurface
          )
        }
      },
      actions = {
        state.tasks[session.id]?.let {
          MiuixStatePill(it.phase, modifier = Modifier.padding(end = 4.dp))
        }
        IconButton(
          onClick = { menuSheet = true },
          modifier = Modifier.semantics { contentDescription = "更多操作" }
        ) {
          Icon(
            imageVector = MiuixIcons.More,
            contentDescription = "更多",
            tint = MiuixTheme.colorScheme.onSurface
          )
        }
      }
    )

    // 分段 TabRow 导航条
    val tabNames = availableTabs.map { item ->
      val count = when (item) {
        DetailTab.TODO -> state.todos.size
        DetailTab.CHANGES -> state.changes.size
        DetailTab.CHILDREN -> state.children.size
        else -> 0
      }
      item.label + if (count > 0) " ($count)" else ""
    }
    val currentTabIdx = availableTabs.indexOf(tab).coerceAtLeast(0)
    Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
      TabRow(
        tabs = tabNames,
        selectedTabIndex = currentTabIdx,
        onTabSelected = { index ->
          val selected = availableTabs[index]
          tab = selected
          if (selected == DetailTab.FILES) controller.listFiles()
        }
      )
    }

    // 主内容面板与底部输入舱
    val chatBackdrop = rememberLayerBackdrop()
    CompositionLocalProvider(LocalBackdrop provides chatBackdrop) {
      Box(Modifier.weight(1f).fillMaxWidth()) {
        Box(
          Modifier
            .fillMaxSize()
            .layerBackdrop(chatBackdrop)
        ) {
          when (tab) {
            DetailTab.CHAT -> Conversation(
              state,
              controller,
              Modifier.fillMaxSize(),
              contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 140.dp)
            )
            DetailTab.TODO -> TodoPanel(state.todos, Modifier.fillMaxSize())
            DetailTab.CHANGES -> ChangesPanel(state.changes, Modifier.fillMaxSize())
            DetailTab.FILES -> FilesPanel(state, controller, onInsertRef = { ref ->
              val key = "${state.serverId}:${state.sessionId}"
              val current = drafts[key].orEmpty()
              editDraft(drafts, key, if (current.isBlank()) "@$ref " else "$current @$ref ")
              tab = DetailTab.CHAT
            }, Modifier.fillMaxSize())
            DetailTab.CHILDREN -> ChildrenPanel(state.children, controller, Modifier.fillMaxSize())
          }
        }

        // 底部输入区域仅在 Chat Tab 下展示 (悬浮 Liquid Glass 交互舱)
        if (tab == DetailTab.CHAT) {
          Box(
            modifier = Modifier
              .align(Alignment.BottomCenter)
              .fillMaxWidth()
          ) {
            MiuixLiquidComposer(state, controller, drafts, backdrop = chatBackdrop)
          }
        }
      }
    }
  }

  // 会话管理操作底栏
  if (menuSheet) {
    SuperBottomSheet(
      title = "会话管理",
      show = menuSheet,
      onDismissRequest = { menuSheet = false }
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp)
          .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        if (state.protocol.supportsSessionActions) {
          MiuixMenuActionItem("重命名会话", MiuixIcons.Edit) {
            menuSheet = false
            title = session.title
            rename = true
          }
          if (state.supportsSavedPermissions) {
            MiuixMenuActionItem("已保存权限", MiuixIcons.Lock) {
              menuSheet = false
              controller.loadSavedPermissions()
            }
          }
          MiuixMenuActionItem("分支会话 (Fork)", MiuixIcons.VerticalSplit) {
            menuSheet = false
            controller.fork()
          }
          MiuixMenuActionItem("分享链接", MiuixIcons.Share) {
            menuSheet = false
            controller.share()
          }
        }

        MiuixMenuActionItem("压缩与总结上下文", MiuixIcons.CloudFill) {
          menuSheet = false
          controller.summarize()
        }
        MiuixMenuActionItem("撤销到上一条消息", MiuixIcons.Undo) {
          menuSheet = false
          state.messages.lastOrNull()?.let { controller.revert(it.id) }
        }
        MiuixMenuActionItem("恢复撤销", MiuixIcons.Redo) {
          menuSheet = false
          controller.unrevert()
        }

        if (state.protocol.supportsSessionActions) {
          Spacer(Modifier.height(4.dp))
          MiuixMenuActionItem("删除会话", MiuixIcons.Delete, isDestructive = true) {
            menuSheet = false
            delete = true
          }
        }
      }
    }
  }

  if (rename) {
    SuperDialog(
      title = "重命名会话",
      show = rename,
      onDismissRequest = { rename = false }
    ) {
      Column(Modifier.padding(top = 8.dp)) {
        TextField(
          value = title,
          onValueChange = { title = it },
          label = "新标题",
          modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
          TextButton(text = "取消", onClick = { rename = false })
          Spacer(Modifier.width(8.dp))
          TextButton(
            text = "保存",
            colors = ButtonDefaults.textButtonColorsPrimary(),
            onClick = {
              controller.rename(title)
              rename = false
            }
          )
        }
      }
    }
  }

  if (delete) {
    SuperDialog(
      title = "确定删除此会话？",
      show = delete,
      onDismissRequest = { delete = false }
    ) {
      Column(Modifier.padding(top = 8.dp)) {
        Text("将从服务端永久移除该会话的所有消息历史记录与上下文。", style = MiuixTheme.textStyles.body1)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
          TextButton(text = "取消", onClick = { delete = false })
          Spacer(Modifier.width(8.dp))
          TextButton(
            text = "彻底删除",
            colors = ButtonDefaults.textButtonColors(textColor = MiuixColorTokens.Error),
            onClick = {
              controller.deleteSession()
              delete = false
              onBack()
            }
          )
        }
      }
    }
  }
}

@Composable
private fun MiuixMenuActionItem(
  title: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  isDestructive: Boolean = false,
  onClick: () -> Unit
) {
  val contentColor = if (isDestructive) MiuixColorTokens.Error else MiuixTheme.colorScheme.onSurface
  Card(
    modifier = Modifier.fillMaxWidth(),
    pressFeedbackType = PressFeedbackType.Sink,
    showIndication = true,
    insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    colors = CardDefaults.defaultColors(
      color = if (isDestructive) MiuixColorTokens.ErrorSubtle else Color.Transparent
    ),
    onClick = onClick
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = contentColor,
        modifier = Modifier.size(20.dp)
      )
      Spacer(Modifier.width(12.dp))
      Text(
        text = title,
        style = MiuixTheme.textStyles.headline2.copy(
          color = contentColor,
          fontWeight = if (isDestructive) FontWeight.SemiBold else FontWeight.Normal
        )
      )
    }
  }
}

@Composable
private fun Conversation(
  state: MobileState,
  controller: MobileController,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)
) {
  val list = rememberLazyListState()
  val scope = rememberCoroutineScope()
  val permissions = state.permissions.filter { it.sessionId == state.sessionId }
  val questions = state.questions.filter { it.sessionId == state.sessionId }
  val total = state.messages.size + permissions.size + questions.size

  val contentKey = state.messages.lastOrNull()?.let { last ->
    Triple(last.id, last.parts.size, last.parts.lastOrNull()?.text?.length ?: 0)
  }

  LaunchedEffect(state.sessionId, contentKey, total) {
    if (total > 0) list.animateScrollToItem(total - 1)
  }

  val showScrollToBottom by remember {
    derivedStateOf {
      val layoutInfo = list.layoutInfo
      val visibleItems = layoutInfo.visibleItemsInfo
      if (visibleItems.isEmpty() || total <= 2) false
      else visibleItems.last().index < total - 2
    }
  }

  Box(modifier = modifier) {
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .overScrollVertical(),
      state = list,
      contentPadding = contentPadding,
      verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
      if (state.messages.isEmpty() && permissions.isEmpty() && questions.isEmpty()) {
        item {
          Card(
            insideMargin = PaddingValues(20.dp),
            colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
          ) {
            Text("会话就绪", style = MiuixTheme.textStyles.title2.copy(fontWeight = FontWeight.Bold))
            Spacer(Modifier.height(6.dp))
            Text(
              "在下方输入指令开始工作，或引用工程文件、使用快捷命令与专用 Agent。",
              style = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            )
          }
        }
      }
      items(state.messages, key = { "message-${it.id}" }) { message -> MiuixMessageCard(message) }
      items(permissions, key = { "permission-${it.id}" }) { MiuixPermissionCard(it, controller, state.supportsSavedPermissions) }
      items(questions, key = { "question-${it.id}" }) { MiuixQuestionCard(it, controller) }
    }

    // 悬浮水滴下滑 FAB 按钮 (Liquid Scroll-to-Bottom FAB)
    androidx.compose.animation.AnimatedVisibility(
      visible = showScrollToBottom,
      enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(spring(stiffness = Spring.StiffnessMedium)),
      exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(),
      modifier = Modifier
        .align(Alignment.BottomEnd)
        .padding(end = 16.dp, bottom = 148.dp)
    ) {
      LiquidGlassSurface(
        modifier = Modifier.size(42.dp),
        cornerRadius = 21.dp,
        onClick = {
          if (total > 0) {
            scope.launch { list.animateScrollToItem(total - 1) }
          }
        }
      ) {
        Icon(
          imageVector = MiuixIcons.Back,
          contentDescription = "回到底部",
          tint = MiuixTheme.colorScheme.primary,
          modifier = Modifier.size(20.dp).align(Alignment.Center).rotate(-90f)
        )
      }
    }
  }
}

@Composable
private fun MiuixMessageCard(message: Message) {
  val isUser = message.role == "user"
  Column(
    modifier = Modifier.fillMaxWidth(),
    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
  ) {
    Text(
      text = if (isUser) "你" else "OpenCode",
      style = MiuixTheme.textStyles.footnote2.copy(
        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
        fontWeight = FontWeight.Medium
      ),
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
    )

    Card(
      modifier = Modifier.fillMaxWidth(if (isUser) 0.90f else 1f),
      cornerRadius = 16.dp,
      insideMargin = PaddingValues(14.dp),
      colors = CardDefaults.defaultColors(
        color = if (isUser) MiuixColorTokens.PrimarySubtle else MiuixTheme.colorScheme.surfaceContainer
      )
    ) {
      message.parts.forEach { part ->
        MiuixMessagePart(part)
      }
      message.error?.let {
        Text(
          text = it,
          color = MiuixColorTokens.Error,
          style = MiuixTheme.textStyles.body2
        )
      }
    }
  }
}

@Composable
private fun MiuixMessagePart(part: MessagePart) {
  when (part.type) {
    "text" -> MarkdownText(part.text)
    "reasoning" -> MiuixExpandableCard("💡 深度思考推演", part.text, defaultOpen = false)
    "tool" -> MiuixExpandableCard(
      "🛠️ " + part.title.ifBlank { part.tool.ifBlank { "工具调用" } } + " · " + part.status,
      listOf(part.input.takeIf { it.isNotBlank() }?.let { "输入\n$it" }, part.output.takeIf { it.isNotBlank() }?.let { "输出\n$it" }).filterNotNull().joinToString("\n\n"),
      defaultOpen = false
    )
    "file" -> MiuixInfoChip("文件路径", part.path.ifBlank { part.text })
    "patch", "diff" -> MiuixExpandableCard(
      "📝 代码变更",
      part.patch.ifBlank { part.text }.ifBlank { part.output }.ifBlank { part.files.joinToString("\n") },
      defaultOpen = false,
      isDiff = true
    )
    "agent", "subtask", "task" -> MiuixInfoChip("子任务", part.text.ifBlank { part.title }.ifBlank { part.path })
    "error" -> Text(part.error.ifBlank { part.text }.ifBlank { part.output }, color = MiuixColorTokens.Error)
    "step-start", "step-finish", "snapshot", "compaction" -> Unit
    else -> MiuixInfoChip(part.type.ifBlank { "内容" }, part.text.ifBlank { part.title })
  }
}

@Composable
private fun MiuixInfoChip(label: String, content: String) {
  if (content.isBlank()) return
  Surface(
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    shape = miuixSquircleShape(10.dp),
    color = MiuixTheme.colorScheme.secondaryContainer
  ) {
    Column(Modifier.padding(10.dp)) {
      Text(label, style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Bold))
      Text(content, style = MiuixTheme.textStyles.body2)
    }
  }
}

@Composable
private fun MiuixExpandableCard(title: String, content: String, defaultOpen: Boolean, isDiff: Boolean = false) {
  var open by remember(title, content.take(30)) { mutableStateOf(defaultOpen) }
  Card(
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    pressFeedbackType = PressFeedbackType.Sink,
    showIndication = true,
    cornerRadius = 12.dp,
    insideMargin = PaddingValues(0.dp),
    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clickable { open = !open }
        .padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = if (open) "▾" else "▸",
        color = MiuixTheme.colorScheme.primary,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold
      )
      Spacer(Modifier.width(8.dp))
      Text(
        text = title,
        style = MiuixTheme.textStyles.headline2.copy(fontWeight = FontWeight.Medium),
        modifier = Modifier.weight(1f)
      )
    }
    if (open) {
      HorizontalDivider(color = MiuixTheme.colorScheme.dividerLine)
      if (isDiff) {
        MiuixDiffBlock(content)
      } else {
        Text(
          text = content.ifBlank { "无输出详情" },
          modifier = Modifier.fillMaxWidth().padding(12.dp),
          style = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace)
        )
      }
    }
  }
}

@Composable
private fun MiuixDiffBlock(diffText: String) {
  val lines = remember(diffText) { diffText.lines() }
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .horizontalScroll(rememberScrollState())
      .background(MiuixTheme.colorScheme.background)
      .padding(8.dp)
  ) {
    lines.forEach { line ->
      val (bg, fg) = when {
        line.startsWith("+") && !line.startsWith("+++") -> MiuixColorTokens.SuccessSubtle to MiuixColorTokens.Success
        line.startsWith("-") && !line.startsWith("---") -> MiuixColorTokens.ErrorSubtle to MiuixColorTokens.Error
        line.startsWith("@@") -> MiuixColorTokens.PrimarySubtle to MiuixTheme.colorScheme.primary
        else -> Color.Transparent to MiuixTheme.colorScheme.onSurface
      }
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(bg)
          .padding(horizontal = 6.dp, vertical = 1.dp)
      ) {
        Text(
          text = line,
          fontFamily = FontFamily.Monospace,
          fontSize = 12.sp,
          color = fg,
          softWrap = false
        )
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
  Text(
    text = content,
    style = MiuixTheme.textStyles.paragraph.copy(
      color = MiuixTheme.colorScheme.onSurface
    )
  )
}

@Composable
fun MiuixPermissionCard(request: PermissionRequest, controller: MobileController, canSave: Boolean) {
  var confirmSave by remember(request.id) { mutableStateOf(false) }
  if (confirmSave) {
    SuperDialog(
      title = "保存项目权限规则",
      show = confirmSave,
      onDismissRequest = { confirmSave = false }
    ) {
      Column(Modifier.padding(top = 8.dp)) {
        Text(
          "规则将保存在服务器项目中，适用于后续任务，可在会话菜单“已保存权限”中撤销。\n" + request.always.joinToString("\n"),
          style = MiuixTheme.textStyles.body2
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
          TextButton(text = "取消", onClick = { confirmSave = false })
          Spacer(Modifier.width(8.dp))
          TextButton(
            text = "保存并允许",
            colors = ButtonDefaults.textButtonColorsPrimary(),
            onClick = {
              confirmSave = false
              controller.replyPermission(request, "always")
            }
          )
        }
      }
    }
  }

  val isDangerous = request.action.contains("shell", true) || request.action.contains("write", true) || request.action.contains("delete", true)

  Card(
    modifier = Modifier.fillMaxWidth(),
    insideMargin = PaddingValues(16.dp),
    colors = CardDefaults.defaultColors(
      color = if (isDangerous) MiuixColorTokens.WarningSubtle else MiuixTheme.colorScheme.surfaceContainer
    )
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      MiuixStatePill(TaskPhase.WAITING_PERMISSION, if (isDangerous) "需确认关键操作" else "等待操作授权")
      Spacer(Modifier.weight(1f))
      Text(
        request.action.ifBlank { "操作" },
        style = MiuixTheme.textStyles.footnote2.copy(
          color = MiuixTheme.colorScheme.primary,
          fontWeight = FontWeight.Bold
        )
      )
    }
    Spacer(Modifier.height(10.dp))
    Text("OpenCode 正在请求执行动作：", style = MiuixTheme.textStyles.headline2.copy(fontWeight = FontWeight.SemiBold))
    Spacer(Modifier.height(8.dp))
    Text(
      text = request.detail,
      fontFamily = FontFamily.Monospace,
      style = MiuixTheme.textStyles.body2,
      modifier = Modifier
        .fillMaxWidth()
        .background(MiuixTheme.colorScheme.secondaryContainer, miuixSquircleShape(8.dp))
        .padding(10.dp)
    )
    Spacer(Modifier.height(12.dp))
    if (request.always.isNotEmpty()) {
      Text(
        text = "选择“始终允许”将在服务器保存规则：" + request.always.joinToString(", "),
        style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
      )
      Spacer(Modifier.height(10.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(
        onClick = { controller.replyPermission(request, "reject") },
        colors = ButtonDefaults.buttonColors()
      ) {
        Text("拒绝")
      }
      Button(
        onClick = { controller.replyPermission(request, "once") },
        colors = ButtonDefaults.buttonColorsPrimary()
      ) {
        Text("允许一次")
      }
      if (canSave && request.always.isNotEmpty()) {
        TextButton(
          text = "始终允许",
          onClick = { confirmSave = true }
        )
      }
    }
  }
}

@Composable
fun MiuixQuestionCard(request: QuestionRequest, controller: MobileController) {
  var answers by remember(request) { mutableStateOf(request.questions.map { question -> question.defaultAnswers().filter { value -> question.options.any { it.value == value } } }) }
  var custom by remember(request) { mutableStateOf(request.questions.map { question ->
    question.defaultAnswers().firstOrNull()?.takeIf { value -> question.options.none { it.value == value } }.orEmpty()
  }) }
  val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
  val answerRows = answers.mapIndexed { index, list -> custom[index].trim().takeIf { it.isNotEmpty() }?.let { if (request.questions[index].multiple) list + it else listOf(it) } ?: list }
  val fieldValues = if (request.form) request.questions.mapIndexed { i, q -> org.json.JSONObject(q.field).str("key") to answerRows[i] }.toMap() else emptyMap()

  Card(
    modifier = Modifier.fillMaxWidth(),
    insideMargin = PaddingValues(16.dp),
    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceContainer)
  ) {
    MiuixStatePill(TaskPhase.WAITING_QUESTION, "需要你的回答")
    request.questions.forEachIndexed { index, question ->
      val field = question.field.takeIf { it.isNotBlank() }?.let { org.json.JSONObject(it) }
      if (field != null && !formVisible(field, fieldValues)) return@forEachIndexed
      if (field?.str("type") == "external") {
        Text(question.title, style = MiuixTheme.textStyles.headline2)
        val url = field.str("url")
        if (url.startsWith("https://")) {
          TextButton(text = "打开外部表单页面", onClick = { uriHandler.openUri(url) })
        } else {
          Text("外部页面仅支持 HTTPS，请在服务器确认此项", style = MiuixTheme.textStyles.footnote2.copy(color = MiuixColorTokens.Warning))
        }
        return@forEachIndexed
      }
      Spacer(Modifier.height(10.dp))
      Text(question.title, style = MiuixTheme.textStyles.headline2.copy(fontWeight = FontWeight.SemiBold))
      question.options.forEach { option ->
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clickable {
              answers = answers.toMutableList().also { list ->
                list[index] = if (question.multiple) {
                  if (option.value in list[index]) list[index] - option.value else list[index] + option.value
                } else listOf(option.value)
              }
              if (!question.multiple) custom = custom.toMutableList().also { it[index] = "" }
            }
            .padding(vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          if (question.multiple) {
            Checkbox(checked = option.value in answers[index], onCheckedChange = { isChecked ->
              answers = answers.toMutableList().also { list ->
                list[index] = if (isChecked) list[index] + option.value else list[index] - option.value
              }
            })
          } else {
            RadioButton(selected = option.value in answers[index], onClick = {
              answers = answers.toMutableList().also { list ->
                list[index] = listOf(option.value)
              }
              custom = custom.toMutableList().also { it[index] = "" }
            })
          }
          Spacer(Modifier.width(10.dp))
          Column {
            Text(option.label, style = MiuixTheme.textStyles.body2)
            if (option.description.isNotBlank()) {
              Text(option.description, style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
            }
          }
        }
      }
      if (question.custom) {
        Spacer(Modifier.height(6.dp))
        TextField(
          value = custom[index],
          onValueChange = { value: String -> custom = custom.toMutableList().also { it[index] = value } },
          useLabelAsPlaceholder = true,
          label = "输入自定义回答…",
          modifier = Modifier.fillMaxWidth()
        )
      }
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(
        onClick = { controller.rejectQuestion(request) },
        colors = ButtonDefaults.buttonColors()
      ) {
        Text("取消")
      }
      Button(
        onClick = {
          controller.replyQuestion(request, answers.mapIndexed { index, list ->
            val value = custom[index].trim()
            if (value.isBlank()) list else if (request.questions[index].multiple) list + value else listOf(value)
          })
        },
        enabled = if (request.form) runCatching { formAnswer(request, answerRows) }.isSuccess else answers.indices.all { answers[it].isNotEmpty() || (request.questions[it].custom && custom[it].isNotBlank()) },
        colors = ButtonDefaults.buttonColorsPrimary()
      ) {
        Text("提交回答")
      }
    }
  }
}

/**
 * 搭载 Liquid Glass 悬浮质感的对话输入舱 (Composer Dock)
 */
@Composable
private fun MiuixLiquidComposer(
  state: MobileState,
  controller: MobileController,
  drafts: MutableMap<String, String>,
  backdrop: com.kyant.backdrop.Backdrop? = LocalBackdrop.current
) {
  val draftKey = "${state.serverId}:${state.sessionId}"
  var filePicker by remember { mutableStateOf(false) }
  var fileQuery by remember { mutableStateOf("") }
  var commandPicker by remember { mutableStateOf(false) }
  var modelSheet by remember { mutableStateOf(false) }
  var agentSheet by remember { mutableStateOf(false) }

  val task = state.tasks[state.sessionId]
  val waiting = task?.phase in TaskState.WAITING_PHASES
  val running = task?.phase in TaskState.RUNNING_PHASES
  val draft = drafts[draftKey].orEmpty()

  // 悬浮在底部的 Liquid Glass 交互舱
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 14.dp, vertical = 6.dp)
      .navigationBarsPadding()
  ) {
    if (!state.connected) {
      Text(
        "离线模式 · 重新连接后将恢复交互",
        color = MiuixColorTokens.Warning,
        style = MiuixTheme.textStyles.footnote2,
        modifier = Modifier.padding(bottom = 4.dp, start = 4.dp)
      )
    }
    if (waiting) {
      Text(
        "请先审批上方的操作请求或回答问题",
        color = MiuixColorTokens.Warning,
        style = MiuixTheme.textStyles.footnote2,
        modifier = Modifier.padding(bottom = 4.dp, start = 4.dp)
      )
    }

    // 快捷胶囊工具栏
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .padding(bottom = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      LiquidActionCapsule("📎 @文件", backdrop = backdrop) { filePicker = true }
      if (state.commands.isNotEmpty()) {
        LiquidActionCapsule("⚡ /命令", backdrop = backdrop) { commandPicker = true }
      }
      LiquidActionCapsule("🤖 " + (state.agent ?: "默认 Agent"), backdrop = backdrop) { agentSheet = true }
      LiquidActionCapsule("🧠 " + (state.model?.label ?: "默认模型"), backdrop = backdrop) { modelSheet = true }
    }

    // 输入舱核心 Surface (液态玻璃质感)
    LiquidGlassSurface(
      modifier = Modifier.fillMaxWidth(),
      cornerRadius = 22.dp,
      backdrop = backdrop
    ) {
      Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
        Box(Modifier.fillMaxWidth().heightIn(min = 40.dp, max = 130.dp)) {
          if (draft.isEmpty()) {
            Text(
              "输入任务描述或向 Agent 提问…",
              style = MiuixTheme.textStyles.body1.copy(
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
              )
            )
          }
          BasicTextField(
            value = draft,
            onValueChange = { editDraft(drafts, draftKey, it) },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MiuixTheme.colorScheme.primary),
            maxLines = 5
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.End
        ) {
          if (running) {
            Button(
              onClick = controller::abort,
              colors = ButtonDefaults.buttonColors(color = MiuixColorTokens.ErrorSubtle)
            ) {
              Text("停止", color = MiuixColorTokens.Error)
            }
          } else {
            Button(
              onClick = {
                val text = draft.trim()
                val submittedRevision = drafts["revision:$draftKey"]
                controller.send(text) {
                  if (drafts["revision:$draftKey"] == submittedRevision) editDraft(drafts, draftKey, "")
                }
              },
              enabled = state.connected && !state.cached && !waiting && !running && draft.isNotBlank(),
              colors = ButtonDefaults.buttonColorsPrimary()
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
    SuperDialog(
      title = "引用工程文件 (@file)",
      show = filePicker,
      onDismissRequest = { filePicker = false }
    ) {
      Column(Modifier.padding(top = 8.dp)) {
        TextField(
          value = fileQuery,
          onValueChange = { text: String ->
            fileQuery = text
            if (text.length >= 2) controller.searchFiles(text)
          },
          useLabelAsPlaceholder = true,
          label = "搜索工程文件名…",
          modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.heightIn(max = 240.dp)) {
          val items = if (fileQuery.isNotBlank()) state.searchResults else state.files.map { it.path }
          if (items.isEmpty()) {
            item {
              Text("无匹配文件", style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary), modifier = Modifier.padding(8.dp))
            }
          }
          items(items.take(15)) { path ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clickable {
                  editDraft(drafts, draftKey, if (draft.isBlank()) "@$path " else "$draft @$path ")
                  filePicker = false
                }
                .padding(vertical = 10.dp, horizontal = 6.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(imageVector = MiuixIcons.File, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(8.dp))
              Text(path, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MiuixTheme.textStyles.body2)
            }
          }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
          TextButton(text = "关闭", onClick = { filePicker = false })
        }
      }
    }
  }

  // 快捷命令弹窗
  if (commandPicker) {
    SuperDialog(
      title = "选择快捷命令",
      show = commandPicker,
      onDismissRequest = { commandPicker = false }
    ) {
      LazyColumn(Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(state.commands) { cmd ->
          Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(10.dp),
            onClick = {
              editDraft(drafts, draftKey, "/${cmd.name} ")
              commandPicker = false
            }
          ) {
            Text("/${cmd.name}", style = MiuixTheme.textStyles.headline2.copy(color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Bold))
            if (cmd.description.isNotBlank()) {
              Text(cmd.description, style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
            }
          }
        }
      }
    }
  }

  // 模型选择
  if (modelSheet) {
    SuperBottomSheet(
      title = "选择 AI 模型",
      show = modelSheet,
      onDismissRequest = { modelSheet = false }
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp)
          .navigationBarsPadding()
      ) {
        LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          item {
            MiuixMenuActionItem("服务器默认模型", MiuixIcons.All) {
              controller.chooseModel(null)
              modelSheet = false
            }
          }
          items(state.models) { model ->
            MiuixMenuActionItem("${model.providerId} · ${model.label}", MiuixIcons.MindMap) {
              controller.chooseModel(model)
              modelSheet = false
            }
          }
        }
      }
    }
  }

  // Agent 选择
  if (agentSheet) {
    SuperBottomSheet(
      title = "选择专用 Agent",
      show = agentSheet,
      onDismissRequest = { agentSheet = false }
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp)
          .navigationBarsPadding()
      ) {
        LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          item {
            MiuixMenuActionItem("默认 Agent 体系", MiuixIcons.All) {
              controller.chooseAgent(null)
              agentSheet = false
            }
          }
          items(state.agents) { agent ->
            MiuixMenuActionItem(agent.name + if (agent.description.isNotBlank()) " (${agent.description})" else "", MiuixIcons.Tasks) {
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
private fun LiquidActionCapsule(
  label: String,
  backdrop: com.kyant.backdrop.Backdrop? = LocalBackdrop.current,
  onClick: () -> Unit
) {
  LiquidGlassSurface(
    cornerRadius = 14.dp,
    backdrop = backdrop,
    onClick = onClick
  ) {
    Text(
      text = label,
      style = MiuixTheme.textStyles.footnote2.copy(
        color = MiuixTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Medium
      ),
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
    )
  }
}

@Composable
private fun TodoPanel(todos: List<TodoItem>, modifier: Modifier = Modifier) {
  LazyColumn(
    modifier = modifier.overScrollVertical(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    if (todos.isEmpty()) {
      item {
        MiuixEmptyStateCard("暂无待办事项", "当前会话尚未生成待办计划或执行任务。")
      }
    }
    items(todos) { todo ->
      Card(insideMargin = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = if (todo.status == "completed") MiuixIcons.Ok else MiuixIcons.Alarm,
            contentDescription = null,
            tint = if (todo.status == "completed") MiuixColorTokens.Success else MiuixTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
          )
          Spacer(Modifier.width(10.dp))
          Text(todo.content, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body2)
          Text(
            todo.priority,
            style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
          )
        }
      }
    }
  }
}

@Composable
private fun ChangesPanel(changes: List<FileChange>, modifier: Modifier = Modifier) {
  LazyColumn(
    modifier = modifier.overScrollVertical(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    if (changes.isEmpty()) {
      item {
        MiuixEmptyStateCard("暂无文件改动", "任务执行中的代码变更与补丁差异将在此记录。")
      }
    }
    items(changes) { change ->
      var open by remember(change.path) { mutableStateOf(false) }
      Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(14.dp),
        onClick = { open = !open }
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            change.path,
            modifier = Modifier.weight(1f),
            style = MiuixTheme.textStyles.headline2.copy(fontWeight = FontWeight.Medium)
          )
          Text("+${change.additions}", style = MiuixTheme.textStyles.footnote1.copy(color = MiuixColorTokens.Success))
          Spacer(Modifier.width(6.dp))
          Text("-${change.deletions}", style = MiuixTheme.textStyles.footnote1.copy(color = MiuixColorTokens.Error))
        }
        if (open) {
          Spacer(Modifier.height(10.dp))
          MiuixDiffBlock(change.patch.ifBlank { change.after }.ifBlank { "无改动详情" })
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
      onValueChange = { text: String ->
        query = text
        if (text.length >= 2) controller.searchFiles(text)
      },
      useLabelAsPlaceholder = true,
      label = "搜索工程目录文件…",
      modifier = Modifier.fillMaxWidth(),
      leadingIcon = {
        Icon(imageVector = MiuixIcons.Search, contentDescription = null, modifier = Modifier.padding(horizontal = 8.dp))
      }
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
      TextButton(
        text = "‹ 返回上级",
        onClick = { controller.listFiles(state.filePath.substringBeforeLast('/', ".")) }
      )
      Spacer(Modifier.width(8.dp))
      Text(
        state.filePath,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
      )
    }
    HorizontalDivider(color = MiuixTheme.colorScheme.dividerLine, modifier = Modifier.padding(vertical = 4.dp))

    if (state.fileBinary) {
      Text(
        "此文件为二进制格式，暂不支持在移动端直接预览。",
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.body2,
        modifier = Modifier.padding(vertical = 16.dp)
      )
    } else if (state.fileText != null) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = { onInsertRef(state.filePath) },
          colors = ButtonDefaults.buttonColorsPrimary()
        ) {
          Text("引用到对话")
        }
        Button(
          onClick = {
            clipboard.setText(AnnotatedString(state.fileText ?: ""))
          },
          colors = ButtonDefaults.buttonColors()
        ) {
          Text("复制全文")
        }
      }
      Spacer(Modifier.height(10.dp))
      Text(
        text = state.fileText ?: "",
        modifier = Modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .horizontalScroll(rememberScrollState()),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        style = MiuixTheme.textStyles.body2
      )
    } else {
      LazyColumn(
        modifier = Modifier.overScrollVertical(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        val results = if (query.isNotBlank()) state.searchResults.map { FileNode(it, "file") } else state.files
        items(results) { node ->
          Card(
            insideMargin = PaddingValues(12.dp),
            onClick = {
              if (node.type == "directory") controller.listFiles(node.path) else controller.readFile(node.path)
            }
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                imageVector = if (node.type == "directory") MiuixIcons.Folder else MiuixIcons.File,
                contentDescription = null,
                tint = if (node.type == "directory") MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantActions,
                modifier = Modifier.size(20.dp)
              )
              Spacer(Modifier.width(10.dp))
              Text(
                node.path.substringAfterLast('/'),
                style = MiuixTheme.textStyles.body2,
                modifier = Modifier.weight(1f)
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ChildrenPanel(children: List<Session>, controller: MobileController, modifier: Modifier = Modifier) {
  LazyColumn(
    modifier = modifier.overScrollVertical(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    if (children.isEmpty()) {
      item {
        MiuixEmptyStateCard("无派生子任务", "当前会话尚未衍生出并行的 Subagent 子任务。")
      }
    }
    items(children) { child ->
      Card(
        insideMargin = PaddingValues(14.dp),
        onClick = { controller.selectSession(child.id) }
      ) {
        Text(child.title, style = MiuixTheme.textStyles.headline2.copy(fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.height(4.dp))
        Text(
          "切换到此子任务会话 ›",
          style = MiuixTheme.textStyles.footnote2.copy(color = MiuixTheme.colorScheme.primary)
        )
      }
    }
  }
}

internal fun editDraft(drafts: MutableMap<String, String>, key: String, value: String) {
  drafts[key] = value
  drafts["revision:$key"] = ((drafts["revision:$key"]?.toLongOrNull() ?: 0L) + 1).toString()
}
