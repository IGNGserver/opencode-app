package com.igng.opencode.lagoon.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.icon.extended.VerticalSplit
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 具有真正光学背景采样、透镜折射、高斯模糊与平滑流体水滴质感的 Liquid Glass 悬浮导航 Dock
 */
@Composable
internal fun LiquidGlassDock(
  selectedTab: RootTab,
  onTabSelected: (RootTab) -> Unit,
  isDark: Boolean,
  modifier: Modifier = Modifier,
  backdrop: Backdrop? = LocalBackdrop.current
) {
  val tabs = RootTab.entries
  val icons = listOf(MiuixIcons.VerticalSplit, MiuixIcons.Tasks, MiuixIcons.Settings)
  val pillShape = remember { miuixSquircleShape(LiquidGlassTokens.DockCornerRadius) }
  val activePillShape = remember { miuixSquircleShape(18.dp) }

  val targetIndex = tabs.indexOf(selectedTab).coerceAtLeast(0)
  val animatedIndex = remember { Animatable(targetIndex.toFloat()) }

  LaunchedEffect(targetIndex) {
    animatedIndex.animateTo(
      targetValue = targetIndex.toFloat(),
      animationSpec = spring(dampingRatio = 0.82f, stiffness = 380f)
    )
  }

  val baseColor = if (isDark) LiquidGlassTokens.DarkSurface else LiquidGlassTokens.LightSurface
  val variantColor = if (isDark) LiquidGlassTokens.DarkSurfaceVariant else LiquidGlassTokens.LightSurfaceVariant
  val borderColor = if (isDark) LiquidGlassTokens.DarkBorder else LiquidGlassTokens.LightBorder

  Box(
    modifier = modifier
      .shadow(
        elevation = 20.dp,
        shape = pillShape,
        spotColor = if (isDark) Color(0x80000000) else Color(0x38000000),
        ambientColor = Color.Transparent
      )
      .then(
        if (backdrop != null) {
          Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { pillShape },
            effects = {
              blur(30f)
              lens(20f, 32f)
            },
            highlight = { Highlight.Default.copy(alpha = if (isDark) 0.85f else 1.0f) },
            innerShadow = {
              InnerShadow(
                radius = 12.dp,
                color = if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.45f),
                alpha = 1f
              )
            },
            onDrawSurface = {
              drawRect(
                Brush.verticalGradient(
                  listOf(
                    baseColor.copy(alpha = if (isDark) 0.45f else 0.55f),
                    variantColor.copy(alpha = if (isDark) 0.30f else 0.35f)
                  )
                )
              )
            }
          )
        } else {
          Modifier.background(
            Brush.verticalGradient(
              listOf(
                baseColor.copy(alpha = if (isDark) 0.88f else 0.92f),
                variantColor.copy(alpha = if (isDark) 0.75f else 0.80f)
              )
            ),
            shape = pillShape
          )
        }
      )
      .clip(pillShape)
      .padding(horizontal = 8.dp, vertical = 6.dp)
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
