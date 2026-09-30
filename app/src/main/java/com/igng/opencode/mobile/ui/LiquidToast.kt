package com.igng.opencode.mobile.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class LiquidToastType {
  INFO, SUCCESS, ERROR
}

/**
 * 具有高斯模糊与透镜折射质感的悬浮轻提示 (Liquid Toast)
 */
@Composable
fun LiquidToastHost(
  message: String?,
  type: LiquidToastType = LiquidToastType.INFO,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  isDark: Boolean = false,
  backdrop: Backdrop? = LocalBackdrop.current
) {
  LaunchedEffect(message) {
    if (message != null) {
      delay(2600)
      onDismiss()
    }
  }

  AnimatedVisibility(
    visible = message != null,
    enter = fadeIn(spring(stiffness = Spring.StiffnessMedium)) +
      slideInVertically(spring(dampingRatio = 0.78f, stiffness = 350f)) { -it },
    exit = fadeOut(spring(stiffness = Spring.StiffnessHigh)) +
      slideOutVertically(spring(stiffness = Spring.StiffnessHigh)) { -it },
    modifier = modifier
  ) {
    if (message != null) {
      val pillShape = remember { miuixSquircleShape(LiquidGlassTokens.CapsuleCornerRadius) }
      val iconVector: ImageVector = when (type) {
        LiquidToastType.SUCCESS -> MiuixIcons.Ok
        LiquidToastType.ERROR -> MiuixIcons.Close
        LiquidToastType.INFO -> MiuixIcons.Info
      }
      val iconColor: Color = when (type) {
        LiquidToastType.SUCCESS -> MiuixColorTokens.Success
        LiquidToastType.ERROR -> MiuixColorTokens.Error
        LiquidToastType.INFO -> MiuixColorTokens.Primary
      }

      Box(
        modifier = Modifier
          .padding(horizontal = 24.dp, vertical = 8.dp)
          .shadow(
            elevation = 16.dp,
            shape = pillShape,
            spotColor = if (isDark) Color(0x66000000) else Color(0x2E000000),
            ambientColor = Color.Transparent
          )
          .liquidGlass(
            cornerRadius = LiquidGlassTokens.CapsuleCornerRadius,
            backdrop = backdrop,
            isDark = isDark,
            tintColor = if (type == LiquidToastType.ERROR) MiuixColorTokens.Error else null
          )
          .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onDismiss
          )
          .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Icon(
            imageVector = iconVector,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(18.dp)
          )
          Text(
            text = message,
            style = MiuixTheme.textStyles.body2.copy(
              fontWeight = FontWeight.Medium,
              color = MiuixTheme.colorScheme.onSurface
            ),
            maxLines = 2
          )
        }
      }
    }
  }
}
