package com.igng.opencode.mobile.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.igng.opencode.mobile.core.TaskPhase
import com.igng.opencode.mobile.core.TaskState
import com.kyant.backdrop.Backdrop
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 类似于灵动岛/超级岛的液态玻璃实时任务状态指示舱
 */
@Composable
fun LiquidTaskIsland(
  task: TaskState?,
  modifier: Modifier = Modifier,
  isDark: Boolean = false,
  backdrop: Backdrop? = LocalBackdrop.current,
  onClick: (() -> Unit)? = null
) {
  val isVisible = task != null && task.phase != TaskPhase.IDLE

  AnimatedVisibility(
    visible = isVisible,
    enter = fadeIn(spring(stiffness = Spring.StiffnessMedium)) +
      slideInVertically(spring(dampingRatio = 0.75f, stiffness = 320f)) { -it },
    exit = fadeOut(spring(stiffness = Spring.StiffnessHigh)) +
      slideOutVertically(spring(stiffness = Spring.StiffnessHigh)) { -it },
    modifier = modifier
  ) {
    if (task != null) {
      val pillShape = remember { miuixSquircleShape(LiquidGlassTokens.CapsuleCornerRadius) }
      val isRunning = task.phase in TaskState.RUNNING_PHASES
      val isWaiting = task.phase in TaskState.WAITING_PHASES

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
        isRunning -> MiuixTheme.colorScheme.primary
        isWaiting -> MiuixColorTokens.Warning
        task.phase == TaskPhase.COMPLETED -> MiuixColorTokens.Success
        task.phase == TaskPhase.FAILED -> MiuixColorTokens.Error
        else -> MiuixTheme.colorScheme.primary
      }

      val statusText: String = when {
        isRunning -> task.detail.ifBlank { "Agent 正在执行任务…" }
        isWaiting -> "等待用户审批或应答"
        task.phase == TaskPhase.COMPLETED -> "任务执行完成"
        task.phase == TaskPhase.FAILED -> "任务执行失败"
        else -> "就绪"
      }

      Box(
        modifier = Modifier
          .padding(horizontal = 16.dp, vertical = 6.dp)
          .shadow(
            elevation = 14.dp,
            shape = pillShape,
            spotColor = if (isDark) Color(0x66000000) else Color(0x30000000),
            ambientColor = Color.Transparent
          )
          .liquidGlass(
            cornerRadius = LiquidGlassTokens.CapsuleCornerRadius,
            backdrop = backdrop,
            isDark = isDark,
            tintColor = if (isWaiting) MiuixColorTokens.Warning else null
          )
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = { onClick?.invoke() }
          )
          .padding(horizontal = 12.dp, vertical = 6.dp)
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
            text = statusText,
            style = MiuixTheme.textStyles.footnote2.copy(
              fontWeight = FontWeight.Medium,
              color = MiuixTheme.colorScheme.onSurface
            ),
            maxLines = 1
          )
        }
      }
    }
  }
}
