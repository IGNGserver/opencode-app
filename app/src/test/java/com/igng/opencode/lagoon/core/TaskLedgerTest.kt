package com.igng.opencode.lagoon.core

import org.junit.Assert.*
import org.junit.Test

class TaskLedgerTest {
  private fun store() = ServerStore(memoryPreferences(), memoryPreferences(), { it }, { it })
  @Test fun readMarkerSurvivesReplayButDoesNotHideTheNextRun() {
    val store = store()
    store.rememberTask("server", TaskState("s", TaskPhase.THINKING, since = 100), observedAt = 100)
    store.rememberTask("server", TaskState("s", TaskPhase.COMPLETED, since = 100, finishedAt = 150), observedAt = 150)
    store.acknowledgeTask("server", "s")
    store.rememberTask("server", TaskState("s", TaskPhase.COMPLETED, since = 100, finishedAt = 150), observedAt = 151)
    assertTrue("s" in store.acknowledgedTasks("server"))
    store.rememberTask("server", TaskState("s", TaskPhase.THINKING, since = 200), observedAt = 200)
    store.rememberTask("server", TaskState("s", TaskPhase.COMPLETED, since = 200, finishedAt = 250), observedAt = 250)
    assertFalse("s" in store.acknowledgedTasks("server"))
  }
  @Test fun delayedPushCannotOverwriteANewerForegroundRun() {
    val store = store()
    store.rememberTask("server", TaskState("s", TaskPhase.THINKING, since = 200), observedAt = 200)
    val next = store.rememberTask("server", TaskState("s", TaskPhase.COMPLETED, since = 100), observedAt = 150)
    assertEquals(TaskPhase.THINKING, next.phase)
    assertEquals(200L, store.taskStates("server")["s"]?.since)
  }
  @Test fun bothPublishersClaimOneNotificationPerPhaseAndRun() {
    val store = store(); val task = TaskState("s", TaskPhase.COMPLETED, "done", since = 100)
    assertTrue(store.claimNotification("server", task, "done"))
    assertFalse(store.claimNotification("server", task, "done"))
    assertTrue(store.claimNotification("server", task.copy(since = 200), "done"))
    assertTrue(store.claimNotification("other", task, "done"))
  }
}
