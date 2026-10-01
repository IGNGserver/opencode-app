package com.igng.opencode.lagoon.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * MIUIX 全局主题提供器：
 * 采用 MIUIX 官方 ThemeController，支持跟随系统或强制明暗模式，
 * 内置 Material You / Monet 动态取色引擎，原生赋能 MIUIX Design System。
 */
@Composable
fun OpenCodeMiuixTheme(
  dark: Boolean,
  content: @Composable () -> Unit
) {
  val mode = if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light
  val controller = remember(dark) {
    ThemeController(
      colorSchemeMode = mode,
      isDark = dark
    )
  }

  MiuixTheme(
    controller = controller,
    content = content
  )
}
