package com.igng.opencode.lagoon.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.icon.extended.VerticalSplit
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 遵循小米 HyperOS / MIUIX 官方质感的经典悬浮药丸 Dock (Floating Navigation Dock)。
 * 
 * 1. 采用连续曲率超椭圆 Squircle 容器悬浮于底部，外圈微边框，彻底告别直角矩形与 Material 方形阴影。
 * 2. 选中项以药丸胶囊高亮背景平滑呈现，未选中项为半透明图标文本。
 * 3. 集成 MIUIX 物理弹性反馈（点击缩放反馈，弹簧动画）。
 */
@Composable
internal fun MiuixNavigationDock(
  selectedTab: RootTab,
  onTabSelected: (RootTab) -> Unit,
  isDark: Boolean,
  modifier: Modifier = Modifier
) {
  val icons = listOf(MiuixIcons.VerticalSplit, MiuixIcons.Tasks, MiuixIcons.Settings)
  val dockShape = remember { miuixSquircleShape(26.dp) }

  Box(
    modifier = modifier
      .fillMaxWidth()
      .navigationBarsPadding()
      .padding(horizontal = 24.dp, vertical = 10.dp),
    contentAlignment = Alignment.Center
  ) {
    Row(
      modifier = Modifier
        .clip(dockShape)
        .background(
          if (isDark) Color(0xFF1E1E22).copy(alpha = 0.96f)
          else Color(0xFFF7F7F8).copy(alpha = 0.96f)
        )
        .border(
          width = 0.8.dp,
          color = if (isDark) Color(0xFF323238) else Color(0xFFE4E4E8),
          shape = dockShape
        )
        .padding(horizontal = 6.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      RootTab.entries.forEachIndexed { index, tab ->
        MiuixDockItem(
          selected = selectedTab == tab,
          icon = icons[index],
          label = tab.label,
          isDark = isDark,
          onClick = { onTabSelected(tab) },
          modifier = Modifier.weight(1f)
        )
      }
    }
  }
}

@Composable
private fun MiuixDockItem(
  selected: Boolean,
  icon: ImageVector,
  label: String,
  isDark: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val interactionSource = remember { MutableInteractionSource() }
  val isPressed by interactionSource.collectIsPressedAsState()
  val itemShape = remember { miuixSquircleShape(20.dp) }

  val scale by animateFloatAsState(
    targetValue = if (isPressed) 0.92f else 1.0f,
    animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow),
    label = "dockScale"
  )

  val pillBackground by animateColorAsState(
    targetValue = when {
      selected && isDark -> Color(0xFF2C2C32)
      selected && !isDark -> Color(0xFFE6E6EB)
      else -> Color.Transparent
    },
    label = "pillBg"
  )

  val contentColor by animateColorAsState(
    targetValue = when {
      selected -> MiuixTheme.colorScheme.primary
      isDark -> Color(0xFF8E8E93)
      else -> Color(0xFF71717A)
    },
    label = "contentColor"
  )

  Box(
    modifier = modifier
      .scale(scale)
      .clip(itemShape)
      .background(pillBackground)
      .clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick
      )
      .padding(vertical = 8.dp),
    contentAlignment = Alignment.Center
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
      Icon(
        imageVector = icon,
        contentDescription = label,
        modifier = Modifier.size(22.dp),
        tint = contentColor
      )
      Text(
        text = label,
        style = MiuixTheme.textStyles.footnote2.copy(
          color = contentColor,
          fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
      )
    }
  }
}
