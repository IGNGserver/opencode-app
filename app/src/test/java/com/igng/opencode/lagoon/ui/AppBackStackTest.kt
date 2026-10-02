package com.igng.opencode.lagoon.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the app-level back stack: side-swipe/back must return one level
 * (chat → owning tab → 会话 root) and only leave the app from the 会话 root tab.
 */
class AppBackStackTest {

  @Test
  fun backFromRootDefersToSystem() {
    assertFalse(AppBackStack.canGoBack(detail = false, tab = RootTab.SESSIONS))
    assertEquals(BackStage.NONE, AppBackStack.backStage(detail = false, tab = RootTab.SESSIONS))
  }

  @Test
  fun nonRootTabCanGoBack() {
    assertTrue(AppBackStack.canGoBack(detail = false, tab = RootTab.ACTIVITY))
    assertEquals(BackStage.TAB_TO_ROOT, AppBackStack.backStage(detail = false, tab = RootTab.ACTIVITY))
    assertTrue(AppBackStack.canGoBack(detail = false, tab = RootTab.SETTINGS))
    assertEquals(BackStage.TAB_TO_ROOT, AppBackStack.backStage(detail = false, tab = RootTab.SETTINGS))
  }

  @Test
  fun openDetailCanGoBack() {
    assertTrue(AppBackStack.canGoBack(detail = true, tab = RootTab.SESSIONS))
    assertEquals(BackStage.DETAIL_TO_TAB, AppBackStack.backStage(detail = true, tab = RootTab.SESSIONS))
  }

  @Test
  fun backFromDetailReturnsToOwningTab() {
    val (detail, tab) = AppBackStack.back(detail = true, tab = RootTab.ACTIVITY)
    assertFalse(detail)
    assertEquals(RootTab.ACTIVITY, tab)
  }

  @Test
  fun backFromNonRootTabReturnsRoot() {
    val (detail, tab) = AppBackStack.back(detail = false, tab = RootTab.SETTINGS)
    assertFalse(detail)
    assertEquals(RootTab.SESSIONS, tab)
  }

  @Test
  fun repeatedBackReachesRootThenStops() {
    // Simulate: SETTINGS → back → 会话 → (no in-app step).
    var tab = RootTab.SETTINGS
    var detail = false
    assertTrue(AppBackStack.canGoBack(detail, tab))
    val first = AppBackStack.back(detail, tab)
    detail = first.first
    tab = first.second
    assertEquals(RootTab.SESSIONS, tab)
    assertFalse(AppBackStack.canGoBack(detail, tab))
  }

  @Test
  fun selectTabClosesDetail() {
    val (detail, tab) = AppBackStack.selectTab(RootTab.SETTINGS)
    assertFalse(detail)
    assertEquals(RootTab.SETTINGS, tab)
  }
}
