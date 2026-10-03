package com.igng.opencode.lagoon.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.igng.opencode.lagoon.core.TaskSummary
import top.yukonga.miuix.kmp.basic.Text

/**
 * 遵循小米 HyperOS 灵动岛 / 超级岛设计规范的实时任务状态胶囊。
 *
 * 核心设计准则：
 * 物理打孔与系统灵动岛始终为纯黑色盲区，因此灵动岛无论在系统浅色或深色模式下，
 * 展开和常驻均必须保持深曜黑底色（#121214）配合精细微高光描边（#2A2A2E），
 * 与前摄打孔浑然一体，绝不跟随浅色模式变浅。
 */
@Composable
internal fun MiuixTaskIsland(
  summary: TaskSummary,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val isVisible = !summary.isEmpty

  AnimatedVisibility(
    visible = isVisible,
    enter = fadeIn(spring(stiffness = Spring.StiffnessMedium)) +
      slideInVertically(spring(dampingRatio = 0.75f, stiffness = 320f)) { -it },
    exit = fadeOut(spring(stiffness = Spring.StiffnessHigh)) +
      slideOutVertically(spring(stiffness = Spring.StiffnessHigh)) { -it },
    modifier = modifier
  ) {
    if (!summary.isEmpty) {
      val isRunning = summary.running > 0
      val isWaiting = summary.waiting > 0
      val isFailed = summary.failed > 0

      val infiniteTransition = rememberInfiniteTransition(label = "pulse")
      val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
          animation = tween(900, easing = FastOutSlowInEasing),
          repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
      )

      val dotColor = when {
        isFailed -> Color(0xFFF87171)
        isWaiting -> Color(0xFFFBBF24)
        isRunning -> Color(0xFF38BDF8)
        else -> Color(0xFF4ADE80)
      }

      val capsuleShape = remember { miuixSquircleShape(18.dp) }

      Box(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
      ) {
        Row(
          modifier = Modifier
            .clip(capsuleShape)
            .background(Color(0xFF121214))
            .border(width = 0.8.dp, color = Color(0xFF2A2A2E), shape = capsuleShape)
            .clickable(
              interactionSource = remember { MutableInteractionSource() },
              indication = null,
              onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 7.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Box(
            modifier = Modifier
              .size(7.dp)
              .background(
                color = dotColor.copy(alpha = if (isRunning) pulseAlpha else 1f),
                shape = CircleShape
              )
          )
          Text(
            text = summary.text.orEmpty(),
            fontSize = 12.sp,
            color = Color(0xFFF4F4F5),
            fontWeight = FontWeight.Medium,
            maxLines = 1
          )
        }
      }
    }
  }
}
