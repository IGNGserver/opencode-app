package com.igng.opencode.lagoon.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.RoundedCornerStyle
import com.kyant.shapes.RoundedRectangle
import top.yukonga.miuix.kmp.theme.MiuixTheme

val LocalBackdrop = compositionLocalOf<Backdrop?> { null }

/**
 * iOS 原生液态玻璃外观模式 (Liquid Glass Appearance)
 * - CLEAR: 通透透镜质感，极致的透光与环境折射
 * - TINTED: 微磨砂材质，增强复杂背景下的色彩对比与文字可读性
 */
enum class IosGlassMode {
  CLEAR,
  TINTED
}

object LiquidGlassTokens {
  val PillCornerRadius = 32.dp
  val DockCornerRadius = 26.dp
  val CapsuleCornerRadius = 14.dp
  val ControlCornerRadius = 18.dp

  // iOS 原生浅色液态玻璃色令
  val LightClearSurface = Color(0x2EFFFFFF)
  val LightClearVariant = Color(0x18FFFFFF)
  val LightTintedSurface = Color(0x8CFFFFFF)
  val LightTintedVariant = Color(0x66E5EEFA)
  val LightBorder = Color(0x59FFFFFF)
  val LightHighlight = Color(0xE6FFFFFF)

  // iOS 原生深色液态玻璃色令
  val DarkClearSurface = Color(0x3812151B)
  val DarkClearVariant = Color(0x221B2028)
  val DarkTintedSurface = Color(0x94161B22)
  val DarkTintedVariant = Color(0x75212936)
  val DarkBorder = Color(0x40FFFFFF)
  val DarkHighlight = Color(0x8069A1FF)

  // 兼容既有代码调用
  val LightSurface = LightTintedSurface
  val LightSurfaceVariant = LightTintedVariant
  val DarkSurface = DarkTintedSurface
  val DarkSurfaceVariant = DarkTintedVariant
}

fun miuixSquircleShape(cornerRadius: Dp = 16.dp): Shape {
  return RoundedRectangle(
    cornerRadius = cornerRadius,
    style = RoundedCornerStyle.Continuous
  )
}

/**
 * 严格按照 iOS 26+ Liquid Glass 光学折射、色散、色彩增艳与连续曲率设计的修饰符
 */
fun Modifier.liquidGlass(
  cornerRadius: Dp = LiquidGlassTokens.ControlCornerRadius,
  backdrop: Backdrop? = null,
  isDark: Boolean = false,
  mode: IosGlassMode = IosGlassMode.TINTED,
  alphaMultiplier: Float = 1f,
  tintColor: Color? = null,
  borderWidth: Dp = 0.5.dp
): Modifier {
  val shape = miuixSquircleShape(cornerRadius)

  val (rawBase, rawVariant) = when (mode) {
    IosGlassMode.CLEAR -> if (isDark) {
      LiquidGlassTokens.DarkClearSurface to LiquidGlassTokens.DarkClearVariant
    } else {
      LiquidGlassTokens.LightClearSurface to LiquidGlassTokens.LightClearVariant
    }
    IosGlassMode.TINTED -> if (isDark) {
      LiquidGlassTokens.DarkTintedSurface to LiquidGlassTokens.DarkTintedVariant
    } else {
      LiquidGlassTokens.LightTintedSurface to LiquidGlassTokens.LightTintedVariant
    }
  }

  val baseColor = when {
    tintColor != null -> tintColor.copy(alpha = if (isDark) 0.32f * alphaMultiplier else 0.22f * alphaMultiplier)
    else -> rawBase.copy(alpha = rawBase.alpha * alphaMultiplier)
  }

  val variantColor = when {
    tintColor != null -> tintColor.copy(alpha = if (isDark) 0.16f * alphaMultiplier else 0.10f * alphaMultiplier)
    else -> rawVariant.copy(alpha = rawVariant.alpha * alphaMultiplier)
  }

  val highlightColor = if (isDark) LiquidGlassTokens.DarkHighlight else LiquidGlassTokens.LightHighlight
  val borderColor = if (isDark) LiquidGlassTokens.DarkBorder else LiquidGlassTokens.LightBorder

  val baseModifier = this.clip(shape)

  return if (backdrop != null) {
    baseModifier
      .drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
          vibrancy() // 1.5x 色彩增艳饱和度，iOS 标志性透亮
          blur(16.dp.toPx()) // 纯正深度高斯模糊
          lens(
            refractionHeight = 18.dp.toPx(),
            refractionAmount = 28.dp.toPx(),
            depthEffect = true,
            chromaticAberration = true // 物理真实 RGB 彩虹色散边缘
          )
        },
        highlight = { Highlight.Default.copy(alpha = if (isDark) 0.75f else 0.95f) },
        innerShadow = {
          InnerShadow(
            radius = 10.dp,
            color = if (isDark) Color.White.copy(alpha = 0.09f) else Color.White.copy(alpha = 0.40f),
            alpha = 1f
          )
        },
        onDrawSurface = {
          drawRect(
            Brush.verticalGradient(listOf(baseColor, variantColor))
          )
        }
      )
      .border(
        width = borderWidth,
        brush = Brush.verticalGradient(
          listOf(
            borderColor.copy(alpha = if (isDark) 0.75f else 0.85f),
            borderColor.copy(alpha = if (isDark) 0.15f else 0.25f)
          )
        ),
        shape = shape
      )
  } else {
    baseModifier
      .background(
        Brush.verticalGradient(listOf(baseColor, variantColor)),
        shape = shape
      )
      .drawBehind {
        val strokePx = borderWidth.toPx()
        drawLine(
          brush = Brush.horizontalGradient(
            listOf(
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
          listOf(
            borderColor.copy(alpha = 0.85f),
            borderColor.copy(alpha = 0.25f)
          )
        ),
        shape = shape
      )
  }
}

/**
 * 搭载 iOS 原生流体弹簧动力学与背景隔离采样的 Liquid Glass 容器
 */
@Composable
fun LiquidGlassSurface(
  modifier: Modifier = Modifier,
  cornerRadius: Dp = LiquidGlassTokens.PillCornerRadius,
  backdrop: Backdrop? = LocalBackdrop.current,
  isDark: Boolean = false,
  mode: IosGlassMode = IosGlassMode.TINTED,
  tintColor: Color? = null,
  onClick: (() -> Unit)? = null,
  content: @Composable BoxScope.() -> Unit
) {
  var isPressed by remember { mutableStateOf(false) }
  val pressProgress by animateFloatAsState(
    targetValue = if (isPressed && onClick != null) 1f else 0f,
    animationSpec = spring(
      dampingRatio = 0.72f, // iOS 原生弹簧阻尼
      stiffness = 380f // 舒适跟手弹性
    ),
    label = "liquidGlassSpring"
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

  val shape = miuixSquircleShape(cornerRadius)

  val (rawBase, rawVariant) = when (mode) {
    IosGlassMode.CLEAR -> if (isDark) {
      LiquidGlassTokens.DarkClearSurface to LiquidGlassTokens.DarkClearVariant
    } else {
      LiquidGlassTokens.LightClearSurface to LiquidGlassTokens.LightClearVariant
    }
    IosGlassMode.TINTED -> if (isDark) {
      LiquidGlassTokens.DarkTintedSurface to LiquidGlassTokens.DarkTintedVariant
    } else {
      LiquidGlassTokens.LightTintedSurface to LiquidGlassTokens.LightTintedVariant
    }
  }

  val baseColor = when {
    tintColor != null -> tintColor.copy(alpha = if (isDark) 0.32f else 0.22f)
    else -> rawBase
  }

  val variantColor = when {
    tintColor != null -> tintColor.copy(alpha = if (isDark) 0.16f else 0.10f)
    else -> rawVariant
  }

  val highlightColor = if (isDark) LiquidGlassTokens.DarkHighlight else LiquidGlassTokens.LightHighlight
  val borderColor = if (isDark) LiquidGlassTokens.DarkBorder else LiquidGlassTokens.LightBorder

  if (backdrop != null) {
    Box(
      modifier = modifier
        .then(interactionModifier)
        .drawBackdrop(
          backdrop = backdrop,
          shape = { shape },
          effects = {
            vibrancy()
            blur(16.dp.toPx())
            lens(
              refractionHeight = 18.dp.toPx(),
              refractionAmount = 28.dp.toPx(),
              depthEffect = true,
              chromaticAberration = true
            )
          },
          highlight = { Highlight.Default.copy(alpha = if (isDark) 0.75f else 0.95f) },
          innerShadow = {
            InnerShadow(
              radius = 10.dp,
              color = if (isDark) Color.White.copy(alpha = 0.09f) else Color.White.copy(alpha = 0.40f),
              alpha = 1f
            )
          },
          layerBlock = {
            // 背景绝对静止！仅前景透镜产生 iOS 拟物微果冻形变 (Squish)
            val scale = lerp(1f, 0.955f, pressProgress)
            scaleX = scale
            scaleY = scale
          },
          onDrawSurface = {
            drawRect(
              Brush.verticalGradient(listOf(baseColor, variantColor))
            )
          }
        )
        .border(
          width = 0.5.dp,
          brush = Brush.verticalGradient(
            listOf(
              borderColor.copy(alpha = if (isDark) 0.75f else 0.85f),
              borderColor.copy(alpha = if (isDark) 0.15f else 0.25f)
            )
          ),
          shape = shape
        )
        .clip(shape),
      content = content
    )
  } else {
    val scale = lerp(1f, 0.955f, pressProgress)
    Box(
      modifier = modifier
        .graphicsLayer {
          scaleX = scale
          scaleY = scale
        }
        .then(interactionModifier)
        .clip(shape)
        .background(
          Brush.verticalGradient(listOf(baseColor, variantColor)),
          shape = shape
        )
        .drawBehind {
          val strokePx = 0.5.dp.toPx()
          drawLine(
            brush = Brush.horizontalGradient(
              listOf(
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
          width = 0.5.dp,
          brush = Brush.verticalGradient(
            listOf(
              borderColor.copy(alpha = 0.85f),
              borderColor.copy(alpha = 0.25f)
            )
          ),
          shape = shape
        ),
      content = content
    )
  }
}
