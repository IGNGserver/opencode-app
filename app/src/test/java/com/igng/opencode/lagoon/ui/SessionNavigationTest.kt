package com.igng.opencode.lagoon.ui

import org.junit.Assert.*
import org.junit.Test

class SessionNavigationTest {
  @Test fun childBackRestoresItsParentBeforeTheOriginatingTab() {
    val nav = SessionNavigation(RootTab.ACTIVITY).open("parent").open("child", child = true).open("grandchild", child = true)
    assertEquals(listOf("parent", "child"), nav.back().sessions)
    assertEquals(listOf("parent"), nav.back().back().sessions)
    assertEquals(RootTab.ACTIVITY, nav.back().back().back().tab)
    assertEquals(RootTab.SESSIONS, nav.back().back().back().back().tab)
    assertFalse(nav.back().back().back().back().canGoBack)
  }
  @Test fun unrelatedSessionDoesNotInheritAnotherSessionsBackChain() {
    val nav = SessionNavigation().open("parent").open("child", true).open("other")
    assertEquals(listOf("other"), nav.sessions)
    assertTrue(nav.back().sessions.isEmpty())
  }
  @Test fun reopeningAnAncestorDoesNotDuplicateOrCycleTheStack() {
    val nav = SessionNavigation().open("parent").open("child", true).open("parent", true)
    assertEquals(listOf("parent"), nav.sessions)
  }
}
