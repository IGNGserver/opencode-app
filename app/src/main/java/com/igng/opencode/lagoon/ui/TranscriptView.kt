package com.igng.opencode.lagoon.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.igng.opencode.lagoon.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class TranscriptRow(val key: String, val kind: String, val label: String = "", val block: MarkdownBlock? = null,
  val attachment: Attachment? = null, val target: String = "", val user: Boolean = false, val copyText: String? = null)
private val markdownCache = object : LinkedHashMap<String, Pair<String, List<MarkdownBlock>>>(32, 0.75f, true) {
  override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, List<MarkdownBlock>>>?) = size > 32
}
private fun parsed(key: String, text: String): List<MarkdownBlock> = synchronized(markdownCache) {
  markdownCache[key]?.takeIf { it.first == text }?.second ?: MarkdownBlocks.parse(text).also { markdownCache[key] = text to it }
}

@Composable
internal fun Conversation(state: LagoonState, controller: LagoonController, modifier: Modifier = Modifier, onFile: (String) -> Unit, onModal: (Boolean) -> Unit) {
  val list = rememberLazyListState()
  val scope = rememberCoroutineScope()
  val expanded = rememberSaveable(saver = mapSaver(save = { it.toMap() }, restore = { map -> mutableStateMapOf<String, Boolean>().apply { map.forEach { (k,v) -> put(k, v as Boolean) } } })) { mutableStateMapOf<String, Boolean>() }
  val visible = state.messages.filter { it.isDisplayable }
  val expansions = expanded.toMap()
  val rows by produceState(emptyList<TranscriptRow>(), visible, expansions) {
    delay(32)
    value = withContext(Dispatchers.Default) {
      buildList {
        visible.forEach { message ->
          val user = message.role == "user"
          val text = message.parts.filter { it.type == "text" }.joinToString("\n") { it.text }
          add(TranscriptRow("${message.id}:header", "header", if (user) "你" else message.agent ?: "OpenCode", user = user, copyText = text))
          message.parts.filter { it.isDisplayable && it.type != "tool" }.forEach { part ->
            val key = "${message.id}:${part.id}"
            when (part.type) {
              "file" -> add(TranscriptRow(key, "attachment", attachment = Attachment(part.path, part.mime, part.title), user = user))
              "reasoning", "patch", "diff" -> {
                add(TranscriptRow("$key:toggle", "toggle", if (part.type == "reasoning") "思考过程" else "代码改动", target = key))
                if (expansions[key] == true) MarkdownBlocks.chunks(part.patch.ifBlank { part.text }.ifBlank { part.output }.ifBlank { part.files.joinToString("\n") }).forEachIndexed { i, content ->
                  add(TranscriptRow("$key:$i", "markdown", block = MarkdownBlock("code", listOf(MarkdownSpan(content, code = true)))))
                }
              }
              else -> parsed(key, part.text.ifBlank { part.title }.ifBlank { part.error }).forEachIndexed { i, block -> add(TranscriptRow("$key:$i", "markdown", block = block, user = user)) }
            }
          }
          val tools = message.parts.filter { it.type == "tool" && it.isDisplayable }
          if (tools.isNotEmpty()) {
            val group = "${message.id}:tools"
            val running = tools.count { it.status in setOf("running", "pending") }
            add(TranscriptRow("$group:toggle", "toggle", "${tools.size} 次工具调用${if (running > 0) " · $running 项执行中" else ""}", target = group))
            if (expansions[group] == true) tools.forEach { part ->
              val key = "${message.id}:${part.id}"
              add(TranscriptRow("$key:toggle", "toggle", "${part.tool} · ${part.status.ifBlank { "等待结果" }}${if (part.error.isNotBlank()) " · 失败" else ""}", target = key))
              if (expansions[key] == true) {
                listOf("输入" to part.input, "输出" to part.output, "错误" to part.error).forEach { (name, content) ->
                  MarkdownBlocks.chunks(content).forEachIndexed { i, chunk -> add(TranscriptRow("$key:$name:$i", "markdown", block = MarkdownBlock("code", listOf(MarkdownSpan(chunk, code = true)), prefix = if (i == 0) name else "", copyText = content.takeIf { i == 0 }))) }
                }
                part.files.forEachIndexed { i, path -> add(TranscriptRow("$key:file:$i", "attachment", attachment = Attachment(path, "", path.substringAfterLast('/')))) }
                part.attachments.forEachIndexed { i, attachment -> add(TranscriptRow("$key:attachment:$i", "attachment", attachment = attachment)) }
              }
            }
          }
          message.error?.let { add(TranscriptRow("${message.id}:error", "error", it)) }
        }
      }
    }
  }
  var follow by rememberSaveable { mutableStateOf(list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0) }
  var newContent by remember { mutableStateOf(false) }
  var autoScroll by remember { mutableStateOf(false) }
  val dragged by list.interactionSource.collectIsDraggedAsState()
  val nearBottom by remember { derivedStateOf {
    val info = list.layoutInfo; val last = info.visibleItemsInfo.lastOrNull()
    last != null && last.index == info.totalItemsCount - 1 && last.offset + last.size <= info.viewportEndOffset + 40
  } }
  LaunchedEffect(dragged, nearBottom) { if (dragged && !autoScroll) follow = nearBottom }
  val contentKey = visible.lastOrNull()?.let { it.id to it.parts.sumOf { part -> part.text.length + part.output.length } }
  suspend fun scrollBottom() {
    val count = list.layoutInfo.totalItemsCount
    if (count > 0) { autoScroll = true; list.scrollToItem(count - 1); list.scrollBy(list.layoutInfo.viewportEndOffset.toFloat()); autoScroll = false }
  }
  LaunchedEffect(contentKey, rows.size, state.pending("send")) {
    if (state.pending("send")) follow = true
    if (follow) { scrollBottom(); newContent = false } else if (rows.isNotEmpty()) newContent = true
  }
  val permissions = state.permissions.filter { it.sessionId == state.sessionId }
  val questions = state.questions.filter { it.sessionId == state.sessionId }
  Box(modifier) {
    LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      if (state.cached) item { Text(if (state.cacheComplete) if (state.connected) "会话缓存 · 消息待同步" else "离线缓存 · 恢复连接后更新" else "离线缓存已截断，部分历史与长输出未保留", color = MiuixColorTokens.Warning, style = MiuixTheme.textStyles.footnote1) }
      if (state.messagesCursor != null) item { TextButton(text = if (state.pending("messages-more")) "正在加载…" else "加载更早消息", enabled = !state.pending("messages-more"), onClick = { follow = false; controller.loadOlderMessages() }) }
      if (rows.isEmpty()) item { ResourceHint(state.resource("messages"), "会话尚无消息", "在下方输入第一项任务。", controller::reload) }
      items(rows, key = { it.key }, contentType = { it.kind }) { row ->
        when (row.kind) {
          "header" -> Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(row.label, style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onSurfaceVariantSummary), modifier = Modifier.weight(1f))
            if (!row.copyText.isNullOrBlank()) CopyButton(row.copyText)
          }
          "toggle" -> TextButton(text = (if (expanded[row.target] == true) "收起 · " else "展开 · ") + row.label, modifier = Modifier.fillMaxWidth(), onClick = { expanded[row.target] = expanded[row.target] != true })
          "attachment" -> row.attachment?.let { AttachmentView(it, onFile) }
          "error" -> SelectionContainer { Text(row.label, color = MiuixColorTokens.Error) }
          "markdown" -> row.block?.let { block ->
            Box(Modifier.fillMaxWidth().then(if (row.user) Modifier.background(MiuixTheme.colorScheme.secondaryContainer, miuixSquircleShape(12.dp)).padding(12.dp) else Modifier)) { MarkdownBlockView(block) }
          }
        }
      }
      items(permissions, key = { "permission:${it.id}" }) { MiuixPermissionCard(it, controller, state.supportsSavedPermissions, onModal) }
      items(questions, key = { "question:${it.id}" }) { MiuixQuestionCard(it, controller) }
    }
    AnimatedVisibility(!nearBottom && rows.isNotEmpty(), modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
      Button(onClick = { follow = true; newContent = false; scope.launch { scrollBottom() } }, colors = ButtonDefaults.buttonColors()) { Text(if (newContent) "新消息 ↓" else "回到底部 ↓") }
    }
  }
}

@Composable
internal fun ResourceHint(status: ResourceStatus, empty: String, explanation: String = "", retry: () -> Unit) {
  when (status.state) {
    ResourceState.LOADING, ResourceState.NOT_LOADED -> Text("正在加载…", style = MiuixTheme.textStyles.footnote1)
    ResourceState.ERROR -> Column { Text(status.error ?: "加载失败", color = MiuixColorTokens.Error); TextButton(text = "重试", onClick = retry) }
    ResourceState.UNSUPPORTED -> Text("此服务器不提供该功能", style = MiuixTheme.textStyles.footnote1)
    ResourceState.STALE -> Column { Text("保留上次数据 · ${status.error.orEmpty()}", color = MiuixColorTokens.Warning); TextButton(text = "重新同步", onClick = retry) }
    ResourceState.EMPTY -> Column { Text(empty, style = MiuixTheme.textStyles.headline2); if (explanation.isNotBlank()) Text(explanation, style = MiuixTheme.textStyles.footnote1) }
    else -> Unit
  }
}

@Composable
private fun CopyButton(text: String) {
  val clipboard = LocalClipboardManager.current
  TextButton(text = "复制", onClick = { clipboard.setText(AnnotatedString(text)) })
}
@Composable
private fun annotated(spans: List<MarkdownSpan>): AnnotatedString {
  val primary = MiuixTheme.colorScheme.primary
  return buildAnnotatedString {
    spans.forEach { span ->
      val style = SpanStyle(fontWeight = if (span.bold) FontWeight.Bold else null, fontStyle = if (span.italic) FontStyle.Italic else null,
        fontFamily = if (span.code) FontFamily.Monospace else null, textDecoration = if (span.strike) TextDecoration.LineThrough else null)
      if (span.href != null) withLink(LinkAnnotation.Url(span.href, TextLinkStyles(SpanStyle(color = primary, textDecoration = TextDecoration.Underline)))) { withStyle(style) { append(span.text) } }
      else withStyle(style) { append(span.text) }
    }
  }
}
@Composable
internal fun MarkdownBlockView(block: MarkdownBlock) {
  val base = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface, lineHeight = 26.sp)
  when (block.kind) {
    "rule" -> HorizontalDivider()
    "code" -> Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.secondaryContainer, miuixSquircleShape(10.dp)).padding(10.dp)) {
      if (block.copyText != null || block.prefix.isNotBlank()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(block.prefix.ifBlank { "代码" }, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.footnote2); block.copyText?.let { CopyButton(it) } }
      SelectionContainer { BasicText(block.text, Modifier.horizontalScroll(rememberScrollState()), style = base.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp)) }
    }
    "table-head", "table-row" -> Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(MiuixTheme.colorScheme.secondaryContainer).padding(8.dp)) {
      block.cells.forEach { cell -> SelectionContainer { BasicText(annotated(cell), Modifier.width(180.dp).padding(end = 12.dp), style = base.copy(fontWeight = if (block.kind == "table-head") FontWeight.SemiBold else FontWeight.Normal)) } }
    }
    else -> SelectionContainer { BasicText(buildAnnotatedString { append(block.prefix); append(annotated(block.spans)) },
      style = if (block.kind == "heading") base.copy(fontSize = when (block.level) { 1 -> 25.sp; 2 -> 21.sp; else -> 18.sp }, fontWeight = FontWeight.SemiBold) else base) }
  }
}
@Composable
internal fun VirtualText(text: String, modifier: Modifier = Modifier) {
  val chunks by produceState(emptyList<String>(), text) { value = withContext(Dispatchers.Default) { MarkdownBlocks.chunks(text) } }
  LazyColumn(modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    items(chunks.size) { i -> MarkdownBlockView(MarkdownBlock("code", listOf(MarkdownSpan(chunks[i], code = true)))) }
  }
}
@Composable
private fun AttachmentView(attachment: Attachment, onFile: (String) -> Unit) {
  val uriHandler = LocalUriHandler.current
  val dataImage = attachment.url.startsWith("data:image/") && attachment.url.length < 8_000_000
  val bitmap by produceState<android.graphics.Bitmap?>(null, attachment.url) {
    if (dataImage) value = withContext(Dispatchers.IO) { runCatching {
      val bytes = android.util.Base64.decode(attachment.url.substringAfter(','), android.util.Base64.DEFAULT)
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
      val options = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 1024).coerceAtLeast(1) }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }.getOrNull() }
  }
  bitmap?.let { Image(it.asImageBitmap(), attachment.name.ifBlank { "图片附件" }, Modifier.fillMaxWidth().heightIn(max = 280.dp)) }
  TextButton(text = attachment.name.ifBlank { if (dataImage) "图片附件" else attachment.url.substringAfterLast('/').take(100).ifBlank { "附件" } }, onClick = {
    when { attachment.url.startsWith("http://") || attachment.url.startsWith("https://") -> runCatching { uriHandler.openUri(attachment.url) }
      !attachment.url.startsWith("data:") -> onFile(runCatching { java.net.URI(attachment.url).path }.getOrNull() ?: attachment.url) }
  })
}
