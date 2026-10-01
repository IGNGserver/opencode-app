package com.igng.opencode.lagoon.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the app-level back stack: side-swipe/back must return one level
 * (chat → owning tab → 工作台) and only leave the app from 工作台.
 */
class AppBackStackTest {

  @Test
  fun backFromHomeDefersToSystem() {
    assertFalse(AppBackStack.canGoBack(detail = false, tab = RootTab.HOME))
    assertEquals(BackStage.NONE, AppBackStack.backStage(detail = false, tab = RootTab.HOME))
  }

  @Test
  fun nonHomeTabCanGoBack() {
    assertTrue(AppBackStack.canGoBack(detail = false, tab = RootTab.SESSIONS))
    assertEquals(BackStage.TAB_TO_HOME, AppBackStack.backStage(detail = false, tab = RootTab.SESSIONS))
    assertTrue(AppBackStack.canGoBack(detail = false, tab = RootTab.SETTINGS))
    assertEquals(BackStage.TAB_TO_HOME, AppBackStack.backStage(detail = false, tab = RootTab.SETTINGS))
  }

  @Test
  fun openDetailCanGoBack() {
    assertTrue(AppBackStack.canGoBack(detail = true, tab = RootTab.HOME))
    assertEquals(BackStage.DETAIL_TO_TAB, AppBackStack.backStage(detail = true, tab = RootTab.HOME))
  }

  @Test
  fun backFromDetailReturnsToOwningTab() {
    val (detail, tab) = AppBackStack.back(detail = true, tab = RootTab.SESSIONS)
    assertFalse(detail)
    assertEquals(RootTab.SESSIONS, tab)
  }

  @Test
  fun backFromNonHomeTabReturnsHome() {
    val (detail, tab) = AppBackStack.back(detail = false, tab = RootTab.SETTINGS)
    assertFalse(detail)
    assertEquals(RootTab.HOME, tab)
  }

  @Test
  fun repeatedBackReachesHomeThenStops() {
    // Simulate: SETTINGS → back → HOME → (no in-app step).
    var tab = RootTab.SETTINGS
    var detail = false
    assertTrue(AppBackStack.canGoBack(detail, tab))
    val first = AppBackStack.back(detail, tab)
    detail = first.first
    tab = first.second
    assertEquals(RootTab.HOME, tab)
    assertFalse(AppBackStack.canGoBack(detail, tab))
  }

  @Test
  fun selectTabClosesDetail() {
    val (detail, tab) = AppBackStack.selectTab(RootTab.SETTINGS)
    assertFalse(detail)
    assertEquals(RootTab.SETTINGS, tab)
  }
}
