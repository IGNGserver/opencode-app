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
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.RoundedCornerStyle
import com.kyant.shapes.RoundedRectangle
import top.yukonga.miuix.kmp.theme.MiuixTheme

val LocalBackdrop = compositionLocalOf<Backdrop?> { null }

object LiquidGlassTokens {
  val PillCornerRadius = 32.dp
  val DockCornerRadius = 26.dp
  val CapsuleCornerRadius = 14.dp
  val ControlCornerRadius = 18.dp

  val LightSurface = Color(0x73FFFFFF)
  val LightSurfaceVariant = Color(0x40E0E8F5)
  val LightBorder = Color(0x80FFFFFF)
  val LightHighlight = Color(0xE6FFFFFF)

  val DarkSurface = Color(0x661E232B)
  val DarkSurfaceVariant = Color(0x402A323D)
  val DarkBorder = Color(0x40FFFFFF)
  val DarkHighlight = Color(0x8069A1FF)
}

fun miuixSquircleShape(cornerRadius: Dp = 16.dp): Shape {
  return RoundedRectangle(
    cornerRadius = cornerRadius,
    style = RoundedCornerStyle.Continuous
  )
}

fun Modifier.liquidGlass(
  cornerRadius: Dp = LiquidGlassTokens.ControlCornerRadius,
  backdrop: Backdrop? = null,
  isDark: Boolean = false,
  alphaMultiplier: Float = 1f,
  tintColor: Color? = null,
  borderWidth: Dp = 1.dp
): Modifier {
  val shape = miuixSquircleShape(cornerRadius)

  val baseColor = when {
    tintColor != null -> tintColor.copy(alpha = if (isDark) 0.35f * alphaMultiplier else 0.25f * alphaMultiplier)
    isDark -> LiquidGlassTokens.DarkSurface.copy(alpha = 0.55f * alphaMultiplier)
    else -> LiquidGlassTokens.LightSurface.copy(alpha = 0.65f * alphaMultiplier)
  }

  val variantColor = when {
    tintColor != null -> tintColor.copy(alpha = if (isDark) 0.18f * alphaMultiplier else 0.12f * alphaMultiplier)
    isDark -> LiquidGlassTokens.DarkSurfaceVariant.copy(alpha = 0.40f * alphaMultiplier)
    else -> LiquidGlassTokens.LightSurfaceVariant.copy(alpha = 0.45f * alphaMultiplier)
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
          blur(24f)
          lens(16f, 24f)
        },
        highlight = { Highlight.Default.copy(alpha = if (isDark) 0.7f else 0.9f) },
        innerShadow = {
          InnerShadow(
            radius = 8.dp,
            color = if (isDark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.35f),
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
            borderColor.copy(alpha = 0.85f),
            borderColor.copy(alpha = 0.25f)
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

@Composable
fun LiquidGlassSurface(
  modifier: Modifier = Modifier,
  cornerRadius: Dp = LiquidGlassTokens.PillCornerRadius,
  backdrop: Backdrop? = LocalBackdrop.current,
  isDark: Boolean = false,
  tintColor: Color? = null,
  onClick: (() -> Unit)? = null,
  content: @Composable BoxScope.() -> Unit
) {
  var isPressed by remember { mutableStateOf(false) }
  val scale by animateFloatAsState(
    targetValue = if (isPressed && onClick != null) 0.96f else 1.0f,
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
        backdrop = backdrop,
        isDark = isDark,
        tintColor = tintColor
      ),
    content = content
  )
}
