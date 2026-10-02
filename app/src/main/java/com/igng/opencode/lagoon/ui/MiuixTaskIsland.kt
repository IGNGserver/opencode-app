package com.igng.opencode.lagoon.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.igng.opencode.lagoon.core.TaskSummary
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 遵循 MIUIX 设计规范的实时任务状态指示胶囊。
 *
 * 内容为全服务器范围的统一摘要（运行中 / 未读已完成 / 待回复 / 失败），与系统通知、小米超级岛
 * 共用同一份口径（见 `core/TaskSummary.kt`）。使用 MIUIX 纯正卡片与实体微投影，彻底替代液态折射。
 */
@Composable
fun MiuixTaskIsland(
  summary: TaskSummary?,
  modifier: Modifier = Modifier,
  onClick: (() -> Unit)? = null
) {
  val isVisible = summary != null && !summary.isEmpty

  AnimatedVisibility(
    visible = isVisible,
    enter = fadeIn(spring(stiffness = Spring.StiffnessMedium)) +
      slideInVertically(spring(dampingRatio = 0.75f, stiffness = 320f)) { -it },
    exit = fadeOut(spring(stiffness = Spring.StiffnessHigh)) +
      slideOutVertically(spring(stiffness = Spring.StiffnessHigh)) { -it },
    modifier = modifier
  ) {
    if (summary != null) {
      val isRunning = summary.running > 0
      val isWaiting = summary.waiting > 0
      val isFailed = summary.failed > 0

      val infiniteTransition = rememberInfiniteTransition(label = "pulse")
      val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
          animation = tween(900, easing = FastOutSlowInEasing),
          repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
      )

      val statusColor: Color = when {
        isFailed -> MiuixColorTokens.Error
        isWaiting -> MiuixColorTokens.Warning
        isRunning -> MiuixTheme.colorScheme.primary
        else -> MiuixColorTokens.Success
      }

      val bgColor = when {
        isFailed -> MiuixColorTokens.ErrorSubtle
        isWaiting -> MiuixColorTokens.WarningSubtle
        else -> MiuixTheme.colorScheme.surfaceContainerHigh
      }

      Card(
        modifier = Modifier
          .padding(horizontal = 16.dp, vertical = 6.dp)
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = { onClick?.invoke() }
          ),
        cornerRadius = 14.dp,
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.defaultColors(color = bgColor)
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Box(
            modifier = Modifier
              .size(8.dp)
              .background(
                color = statusColor.copy(alpha = if (isRunning) pulseAlpha else 1f),
                shape = miuixSquircleShape(4.dp)
              )
          )
          Text(
            text = summary.text.orEmpty(),
            style = MiuixTheme.textStyles.footnote2.copy(
              fontWeight = FontWeight.Medium,
              color = if (isFailed) MiuixColorTokens.Error else if (isWaiting) MiuixColorTokens.Warning else MiuixTheme.colorScheme.onSurface
            ),
            maxLines = 1
          )
        }
      }
    }
  }
}
