package com.igng.opencode.lagoon.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.igng.opencode.lagoon.core.TaskPhase
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * MIUIX 语义色彩系统：
 * 主色采用小米 HyperOS 官方经典科技蓝 #3482FF，搭配全套层次色阶。
 */
object MiuixColorTokens {
  val Primary = Color(0xFF3482FF)
  val PrimaryVariant = Color(0xFF277AF7)
  val PrimarySubtle = Color(0x1F3482FF)

  val Success = Color(0xFF34C759)
  val Warning = Color(0xFFFF9500)
  val Error = Color(0xFFF04438)
  val Info = Color(0xFF007AFF)

  // 状态背景柔色
  val SuccessSubtle = Color(0x1F34C759)
  val WarningSubtle = Color(0x1FFFF9500)
  val ErrorSubtle = Color(0x1FF04438)
  val NeutralSubtle = Color(0x14000000)
  val NeutralSubtleDark = Color(0x26FFFFFF)
}

/**
 * MIUIX 风格状态胶囊 (State Badge / Pill)
 * 采用连续曲率胶囊形态与语义呼吸圆点
 */
@Composable
fun MiuixStatePill(
  phase: TaskPhase,
  detail: String? = null,
  modifier: Modifier = Modifier
) {
  val (color, subtleBg) = when (phase) {
    TaskPhase.COMPLETED -> MiuixColorTokens.Success to MiuixColorTokens.SuccessSubtle
    TaskPhase.WAITING_PERMISSION, TaskPhase.WAITING_QUESTION -> MiuixColorTokens.Warning to MiuixColorTokens.WarningSubtle
    TaskPhase.FAILED, TaskPhase.DISCONNECTED -> MiuixColorTokens.Error to MiuixColorTokens.ErrorSubtle
    TaskPhase.ABORTED, TaskPhase.IDLE -> MiuixTheme.colorScheme.onSurfaceVariantSummary to MiuixTheme.colorScheme.secondaryContainer
    else -> MiuixColorTokens.Primary to MiuixColorTokens.PrimarySubtle
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

  Row(
    modifier = modifier
      .background(subtleBg, miuixSquircleShape(8.dp))
      .padding(horizontal = 8.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp)
  ) {
    Box(
      Modifier
        .size(6.dp)
        .background(color, CircleShape)
    )
    Text(
      text = label,
      style = MiuixTheme.textStyles.footnote2.copy(
        fontWeight = FontWeight.Medium,
        color = color
      ),
      maxLines = 1,
      overflow = TextOverflow.Ellipsis
    )
  }
}

/**
 * MIUIX 规范分节标题 (Section Title)
 */
@Composable
fun MiuixSectionHeader(
  title: String,
  count: Int? = null,
  action: String? = null,
  onAction: (() -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 4.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = title,
      style = MiuixTheme.textStyles.headline2.copy(
        fontWeight = FontWeight.Bold,
        color = MiuixTheme.colorScheme.onSurface
      ),
      modifier = Modifier.weight(1f)
    )
    if (count != null) {
      Text(
        text = "$count",
        style = MiuixTheme.textStyles.footnote1.copy(
          color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
          fontWeight = FontWeight.SemiBold
        ),
        modifier = Modifier.padding(end = 8.dp)
      )
    }
    if (action != null && onAction != null) {
      TextButton(
        text = action,
        onClick = onAction,
        colors = ButtonDefaults.textButtonColorsPrimary()
      )
    }
  }
}
