package com.igng.opencode.mobile.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedCornerStyle
import com.kyant.shapes.RoundedRectangle

/**
 * Liquid Glass 设计实现:
 * 作为 Navigation / Floating Controls / Live Task Surface 的高阶动态材料。
 * 包含：
 * 1. 连续曲率 Squircle 几何形 (Continuous Curvature)
 * 2. 多重透光与微光散射梯度 (Optical Translucency & Specular Refraction)
 * 3. 动态高光边缘轮廓 (Glass Highlights & Rim Light)
 * 4. 弹性物理交互反馈 (Liquid Spring Motion: press scale + sink feedback)
 */
object LiquidGlassTokens {
  val PillCornerRadius = 32.dp
  val ControlCornerRadius = 18.dp
  val FloatingElevation = 8.dp

  // 亮色液体玻璃基底与散射
  val LightSurface = Color(0xD9FFFFFF)
  val LightSurfaceVariant = Color(0xB3F0F4F8)
  val LightBorder = Color(0x66FFFFFF)
  val LightHighlight = Color(0x99FFFFFF)

  // 暗色液体玻璃基底与散射
  val DarkSurface = Color(0xCC1A1E24)
  val DarkSurfaceVariant = Color(0x99252B33)
  val DarkBorder = Color(0x33FFFFFF)
  val DarkHighlight = Color(0x4D8EAFCE)
}

/**
 * 创建符合 MIUIX 标准的连续曲率圆角形状 (Squircle)
 */
fun miuixSquircleShape(cornerRadius: Dp = 16.dp): Shape {
  return RoundedRectangle(
    cornerRadius = cornerRadius,
    style = RoundedCornerStyle.Continuous
  )
}

/**
 * 液体玻璃修饰符 - 为悬浮控件、活动任务岛和导航底栏提供真实的流体玻璃材质感
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
    tintColor != null -> tintColor.copy(alpha = if (isDark) 0.28f * alphaMultiplier else 0.22f * alphaMultiplier)
    isDark -> LiquidGlassTokens.DarkSurface.copy(alpha = 0.85f * alphaMultiplier)
    else -> LiquidGlassTokens.LightSurface.copy(alpha = 0.88f * alphaMultiplier)
  }

  val highlightColor = if (isDark) LiquidGlassTokens.DarkHighlight else LiquidGlassTokens.LightHighlight
  val borderColor = if (isDark) LiquidGlassTokens.DarkBorder else LiquidGlassTokens.LightBorder

  return this
    .clip(shape)
    .background(
      Brush.verticalGradient(
        colors = listOf(
          baseColor,
          if (isDark) LiquidGlassTokens.DarkSurfaceVariant.copy(alpha = 0.70f * alphaMultiplier)
          else LiquidGlassTokens.LightSurfaceVariant.copy(alpha = 0.75f * alphaMultiplier)
        )
      ),
      shape = shape
    )
    .drawBehind {
      val strokePx = borderWidth.toPx()
      drawLine(
        brush = Brush.horizontalGradient(
          colors = listOf(
            highlightColor.copy(alpha = 0.1f),
            highlightColor.copy(alpha = 0.8f),
            highlightColor.copy(alpha = 0.1f)
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
          borderColor.copy(alpha = 0.25f)
        )
      ),
      shape = shape
    )
}

/**
 * 带弹簧物理反馈的可交互 Liquid Glass 容器
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
  var isPressed by remember { mutableStateOf(false) }
  val scale by animateFloatAsState(
    targetValue = if (isPressed && onClick != null) 0.965f else 1.0f,
    animationSpec = spring(
      dampingRatio = Spring.DampingRatioMediumBouncy,
      stiffness = Spring.StiffnessMedium
    ),
    label = "liquidGlassScale"
  )

  val interactionModifier = if (onClick != null) {
    Modifier.pointerInput(onClick) {
      detectTapGestures(
        onPress = {
          isPressed = true
          tryAwaitRelease()
          isPressed = false
        },
        onTap = { onClick() }
      )
    }
  } else Modifier

  Box(
    modifier = modifier
      .graphicsLayer {
        scaleX = scale
        scaleY = scale
      }
      .then(interactionModifier)
      .liquidGlass(
        cornerRadius = cornerRadius,
        isDark = isDark,
        tintColor = tintColor
      ),
    content = content
  )
}
