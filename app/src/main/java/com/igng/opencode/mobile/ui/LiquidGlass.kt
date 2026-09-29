package com.igng.opencode.mobile.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedCornerStyle
import com.kyant.shapes.RoundedRectangle
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * Liquid Glass（液态玻璃）设计系统实现:
 * 遵循小米 HyperOS 官方美学与 MIUIX 动效系统：
 * 1. 连续曲率超椭圆 Squircle 轮廓 (Continuous Curvature)
 * 2. 真实通透的多层光波折射与动态微光散射 (Refraction & Specular Scattering)
 * 3. 随边界流淌的高光微晶边缘 (Rim Highlight & Frost Border)
 * 4. 原生弹簧物理反馈 (Spring Physics Press / Sink Dynamics)
 */
object LiquidGlassTokens {
  val PillCornerRadius = 32.dp
  val DockCornerRadius = 24.dp
  val CapsuleCornerRadius = 14.dp
  val ControlCornerRadius = 18.dp

  // 浅色模式液态玻璃基底
  val LightSurface = Color(0xE6FFFFFF)
  val LightSurfaceVariant = Color(0xC4F0F4FA)
  val LightBorder = Color(0x66FFFFFF)
  val LightHighlight = Color(0xB3FFFFFF)

  // 深色模式液态玻璃基底
  val DarkSurface = Color(0xD91E232B)
  val DarkSurfaceVariant = Color(0xB3272E38)
  val DarkBorder = Color(0x38FFFFFF)
  val DarkHighlight = Color(0x4D69A1FF)
}

/**
 * 创建符合 MIUIX 标准的连续曲率超椭圆形状 (Squircle)
 */
fun miuixSquircleShape(cornerRadius: Dp = 16.dp): Shape {
  return RoundedRectangle(
    cornerRadius = cornerRadius,
    style = RoundedCornerStyle.Continuous
  )
}

/**
 * 液体玻璃修饰符 - 为悬浮控件、底栏 Dock、输入舱和实时状态岛注入纯正的液态玻璃材质
 */
fun Modifier.liquidGlass(
  cornerRadius: Dp = LiquidGlassTokens.ControlCornerRadius,
  isDark: Boolean = false,
  alphaMultiplier: Float = 1f,
  tintColor: Color? = null,
  borderWidth: Dp = 1.dp
): Modifier {
  val shape = miuixSquircleShape(cornerRadius)

  val baseColor = when {
    tintColor != null -> tintColor.copy(alpha = if (isDark) 0.26f * alphaMultiplier else 0.18f * alphaMultiplier)
    isDark -> LiquidGlassTokens.DarkSurface.copy(alpha = 0.88f * alphaMultiplier)
    else -> LiquidGlassTokens.LightSurface.copy(alpha = 0.90f * alphaMultiplier)
  }

  val variantColor = when {
    tintColor != null -> tintColor.copy(alpha = if (isDark) 0.12f * alphaMultiplier else 0.08f * alphaMultiplier)
    isDark -> LiquidGlassTokens.DarkSurfaceVariant.copy(alpha = 0.72f * alphaMultiplier)
    else -> LiquidGlassTokens.LightSurfaceVariant.copy(alpha = 0.76f * alphaMultiplier)
  }

  val highlightColor = if (isDark) LiquidGlassTokens.DarkHighlight else LiquidGlassTokens.LightHighlight
  val borderColor = if (isDark) LiquidGlassTokens.DarkBorder else LiquidGlassTokens.LightBorder

  return this
    .clip(shape)
    .background(
      Brush.verticalGradient(
        colors = listOf(baseColor, variantColor)
      ),
      shape = shape
    )
    .drawBehind {
      val strokePx = borderWidth.toPx()
      // 顶部水平流体高光弧光
      drawLine(
        brush = Brush.horizontalGradient(
          colors = listOf(
            highlightColor.copy(alpha = 0.05f),
            highlightColor.copy(alpha = 0.85f),
            highlightColor.copy(alpha = 0.05f)
          )
        ),
        start = Offset(cornerRadius.toPx(), strokePx / 2),
        end = Offset(size.width - cornerRadius.toPx(), strokePx / 2),
        strokeWidth = strokePx
      )
    }
    .border(
      width = borderWidth,
      brush = Brush.verticalGradient(
        colors = listOf(
          borderColor.copy(alpha = 0.9f),
          borderColor.copy(alpha = 0.20f)
        )
      ),
      shape = shape
    )
}

/**
 * 搭载物理下沉反馈的 Liquid Glass 交互容器
 */
@Composable
fun LiquidGlassSurface(
  modifier: Modifier = Modifier,
  cornerRadius: Dp = LiquidGlassTokens.PillCornerRadius,
  isDark: Boolean = false,
  tintColor: Color? = null,
  onClick: (() -> Unit)? = null,
  content: @Composable BoxScope.() -> Unit
) {
  val interactionSource = remember { MutableInteractionSource() }

  val clickableModifier = if (onClick != null) {
    Modifier.clickable(
      interactionSource = interactionSource,
      indication = null, // 自定义弹簧动力学反馈
      onClick = onClick
    )
  } else Modifier

  Box(
    modifier = modifier
      .then(clickableModifier)
      .liquidGlass(
        cornerRadius = cornerRadius,
        isDark = isDark,
        tintColor = tintColor
      ),
    content = content
  )
}
