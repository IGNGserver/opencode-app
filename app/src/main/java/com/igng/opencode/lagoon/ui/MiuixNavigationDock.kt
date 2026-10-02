package com.igng.opencode.lagoon.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.icon.extended.VerticalSplit
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 遵循小米 HyperOS / MIUIX 设计规范的悬浮导航 Dock。
 *
 * 采用纯正的实体 Squircle 卡片设计与层次背景色，完全移除背景采样滤镜与折射。
 */
@Composable
internal fun MiuixNavigationDock(
  selectedTab: RootTab,
  onTabSelected: (RootTab) -> Unit,
  isDark: Boolean,
  modifier: Modifier = Modifier
) {
  val tabs = RootTab.entries
  val icons = listOf(MiuixIcons.VerticalSplit, MiuixIcons.Tasks, MiuixIcons.Settings)
  val activePillShape = remember { miuixSquircleShape(18.dp) }

  Card(
    modifier = modifier,
    cornerRadius = 24.dp,
    insideMargin = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
    colors = CardDefaults.defaultColors(
      color = MiuixTheme.colorScheme.surfaceContainerHighest
    )
  ) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      tabs.forEachIndexed { index, tab ->
        val isSelected = selectedTab == tab
        val tint = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary

        Row(
          modifier = Modifier
            .clip(activePillShape)
            .clickable(
              interactionSource = remember { MutableInteractionSource() },
              indication = null,
              onClick = { onTabSelected(tab) }
            )
            .background(
              if (isSelected) {
                if (isDark) MiuixColorTokens.Primary.copy(alpha = 0.25f)
                else MiuixColorTokens.PrimarySubtle.copy(alpha = 0.85f)
              } else Color.Transparent
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Icon(
            imageVector = icons[index],
            contentDescription = tab.label,
            tint = tint,
            modifier = Modifier.size(22.dp)
          )
          if (isSelected) {
            Text(
              text = tab.label,
              style = MiuixTheme.textStyles.footnote1.copy(
                color = MiuixTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
              )
            )
          }
        }
      }
    }
  }
}
