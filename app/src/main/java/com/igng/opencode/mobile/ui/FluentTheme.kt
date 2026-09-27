package com.igng.opencode.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.igng.opencode.mobile.core.TaskPhase

object Fluent {
  val blue = Color(0xFF0F6CBD)
  val blueDark = Color(0xFF115EA3)
  val blueLight = Color(0xFFEBF3FC)
  val green = Color(0xFF107C41)
  val amber = Color(0xFF9A6700)
  val red = Color(0xFFC50F1F)
  val ink = Color(0xFF242424)
  val sub = Color(0xFF616161)
  val canvas = Color(0xFFF7F8FA)
  val stroke = Color(0xFFE0E0E0)
  val card = Color.White
}

@Composable
fun FluentTheme(dark: Boolean = false, content: @Composable () -> Unit) {
  val scheme = if (dark) darkColorScheme(
    primary = Color(0xFF7FB7E6), onPrimary = Color(0xFF071C30), secondary = Color(0xFF7FB7E6),
    secondaryContainer = Color(0xFF243F58), onSecondaryContainer = Color(0xFFCEE6FA),
    background = Color(0xFF161A1F), surface = Color(0xFF20252B),
    surfaceVariant = Color(0xFF2A3037), onBackground = Color(0xFFF4F4F4),
    onSurface = Color(0xFFF4F4F4), outline = Color(0xFF555D66)
  ) else lightColorScheme(
    primary = Fluent.blue, onPrimary = Color.White, secondary = Fluent.blue,
    secondaryContainer = Fluent.blueLight, onSecondaryContainer = Fluent.blueDark,
    background = Fluent.canvas, surface = Fluent.card, surfaceVariant = Color(0xFFF3F3F3),
    onBackground = Fluent.ink, onSurface = Fluent.ink, outline = Fluent.stroke,
    error = Fluent.red
  )
  MaterialTheme(colorScheme = scheme, typography = Typography(
    displaySmall = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp),
    labelMedium = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
  ), content = content)
}

@Composable
fun FluentCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
  val shape = RoundedCornerShape(12.dp)
  val surface = MaterialTheme.colorScheme.surface
  val base = modifier.fillMaxWidth().background(surface, shape)
  val clickable = if (onClick != null) base.clickable(onClick = onClick) else base
  Surface(modifier = clickable, shape = shape, color = surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)), shadowElevation = 1.dp) {
    Column(Modifier.padding(16.dp), content = content)
  }
}

@Composable
fun SectionTitle(title: String, count: Int? = null, action: String? = null, onAction: (() -> Unit)? = null) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
    if (count != null) Text("$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
  }
}

@Composable
fun StatePill(phase: TaskPhase, detail: String? = null) {
  val color = when (phase) {
    TaskPhase.COMPLETED -> Fluent.green
    TaskPhase.WAITING_PERMISSION, TaskPhase.WAITING_QUESTION -> Fluent.amber
    TaskPhase.FAILED, TaskPhase.DISCONNECTED -> Fluent.red
    TaskPhase.ABORTED, TaskPhase.IDLE -> Fluent.sub
    else -> Fluent.blue
  }
  val label = detail ?: when (phase) {
    TaskPhase.IDLE -> "空闲"
    TaskPhase.THINKING -> "思考中"
    TaskPhase.TOOL -> "执行工具"
    TaskPhase.SUBAGENT -> "子任务"
    TaskPhase.TESTING -> "测试中"
    TaskPhase.WAITING_PERMISSION -> "等待授权"
    TaskPhase.WAITING_QUESTION -> "等待回答"
    TaskPhase.COMPLETED -> "已完成"
    TaskPhase.FAILED -> "失败"
    TaskPhase.ABORTED -> "已停止"
    TaskPhase.DISCONNECTED -> "已断开"
  }
  Row(Modifier.background(color.copy(alpha = 0.1f), RoundedCornerShape(6.dp)).padding(horizontal = 9.dp, vertical = 5.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Box(Modifier.size(6.dp).background(color, RoundedCornerShape(50)))
    Text(label, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
  }
}
