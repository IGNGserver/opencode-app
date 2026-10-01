package com.igng.opencode.lagoon.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskSummaryTest {
  private fun tasks(vararg phases: Pair<String, TaskPhase>): Map<String, TaskState> =
    phases.associate { (id, phase) -> id to TaskState(id, phase) }

  @Test fun emptySummaryHidesIsland() {
    assertTrue(TaskSummary.of(emptyMap()).isEmpty)
    assertNull(TaskSummary.of(emptyMap()).text)
    assertTrue(TaskSummary.of(tasks("a" to TaskPhase.IDLE, "b" to TaskPhase.DISCONNECTED)).isEmpty)
  }

  @Test fun alwaysShowsRunningAndCompletedBaseline() {
    val summary = TaskSummary.of(tasks(
      "a" to TaskPhase.THINKING,
      "b" to TaskPhase.TOOL,
      "c" to TaskPhase.COMPLETED
    ))
    assertEquals(2, summary.running)
    assertEquals(1, summary.completed)
    assertEquals(0, summary.waiting)
    assertEquals("2个运行中，1个已完成", summary.text)
  }

  @Test fun zeroCountsStillRenderBaselineWithoutWaitingOrFailed() {
    val summary = TaskSummary.of(tasks("a" to TaskPhase.COMPLETED))
    assertEquals("0个运行中，1个已完成", summary.text)
    assertFalse(summary.text!!.contains("待回复"))
    assertFalse(summary.text!!.contains("失败"))
  }

  @Test fun permissionAndQuestionBothCountAsWaiting() {
    val summary = TaskSummary.of(tasks(
      "a" to TaskPhase.WAITING_PERMISSION,
      "b" to TaskPhase.WAITING_QUESTION,
      "c" to TaskPhase.SUBAGENT,
      "d" to TaskPhase.TESTING
    ))
    assertEquals(2, summary.running)
    assertEquals(2, summary.waiting)
    assertEquals("2个运行中，0个已完成，2个待回复", summary.text)
  }

  @Test fun failedIsReportedSeparatelyFromCompleted() {
    val summary = TaskSummary.of(tasks(
      "a" to TaskPhase.FAILED,
      "b" to TaskPhase.COMPLETED
    ))
    assertEquals(0, summary.running)
    assertEquals(1, summary.completed)
    assertEquals(0, summary.waiting)
    assertEquals(1, summary.failed)
    assertEquals("0个运行中，1个已完成，1个失败", summary.text)
  }

  @Test fun acknowledgedTerminalResultsAreNotUnread() {
    val all = tasks("done" to TaskPhase.COMPLETED, "boom" to TaskPhase.FAILED)
    val summary = TaskSummary.of(all, acknowledged = setOf("done", "boom"))
    assertTrue(summary.isEmpty)
    assertNull(summary.text)
  }

  @Test fun acknowledgementIsPerSession() {
    val summary = TaskSummary.of(
      tasks("done" to TaskPhase.COMPLETED, "other" to TaskPhase.FAILED),
      acknowledged = setOf("done")
    )
    assertEquals(0, summary.completed)
    assertEquals(1, summary.failed)
    assertEquals("0个运行中，0个已完成，1个失败", summary.text)
  }

  @Test fun abortedIsNeitherCompletedNorFailed() {
    val summary = TaskSummary.of(tasks("a" to TaskPhase.ABORTED))
    assertTrue(summary.isEmpty)
  }

  @Test fun shortTextForStatusChip() {
    val summary = TaskSummary.of(tasks(
      "a" to TaskPhase.THINKING,
      "b" to TaskPhase.WAITING_QUESTION,
      "c" to TaskPhase.FAILED
    ))
    assertEquals("1跑·1待·1败", summary.shortText)
  }

  @Test fun fullSentenceOrderIsRunningCompletedWaitingFailed() {
    val summary = TaskSummary.of(tasks(
      "a" to TaskPhase.THINKING,
      "b" to TaskPhase.WAITING_PERMISSION,
      "c" to TaskPhase.COMPLETED,
      "d" to TaskPhase.FAILED
    ))
    assertEquals("1个运行中，1个已完成，1个待回复，1个失败", summary.text)
  }
}
