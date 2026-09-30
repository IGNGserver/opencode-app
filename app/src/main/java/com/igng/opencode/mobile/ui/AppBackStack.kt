package com.igng.opencode.mobile.ui

/** Top-level destinations shown in the bottom navigation / wide-screen rail. */
internal enum class RootTab(val label: String) {
  HOME("工作台"), SESSIONS("会话"), SETTINGS("设置")
}

/** The predictive back transition type for in-app navigation. */
internal enum class BackStage {
  NONE,
  DETAIL_TO_TAB,
  TAB_TO_HOME
}

/**
 * The single in-app back stack shared by the UI and its unit tests.
 *
 * Order: chat detail → its owning tab → 工作台 (HOME) → system handles back (exit).
 * Keeping the transitions pure makes the "侧滑只回上一级、不回桌面" contract verifiable
 * without a device.
 */
internal object AppBackStack {
  /** Whether an in-app step exists; when false the system should handle back (leave the app). */
  fun canGoBack(detail: Boolean, tab: RootTab): Boolean = detail || tab != RootTab.HOME

  /** Identifies what stage the back gesture represents. */
  fun backStage(detail: Boolean, tab: RootTab): BackStage = when {
    detail -> BackStage.DETAIL_TO_TAB
    tab != RootTab.HOME -> BackStage.TAB_TO_HOME
    else -> BackStage.NONE
  }

  /**
   * Applies exactly one back step. Only call when [canGoBack] is true.
   * - detail open → close detail, keep the tab it was opened from
   * - non-HOME tab → return to 工作台
   * The returned detail flag is always `false`: one back step never opens a detail.
   */
  fun back(detail: Boolean, tab: RootTab): Pair<Boolean, RootTab> =
    false to if (detail) tab else RootTab.HOME

  /** Selecting a tab always leaves any open detail. */
  fun selectTab(tab: RootTab): Pair<Boolean, RootTab> = false to tab
}
