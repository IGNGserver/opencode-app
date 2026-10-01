package com.igng.opencode.mobile.core

/**
 * 全服务器范围的任务计数，供灵动岛 / 超级岛 / 实时通知复用同一份口径。
 *
 * 计数规则（已确认）：
 * - [running]：正在执行（THINKING / TOOL / SUBAGENT / TESTING）。
 * - [completed]：已完成但用户尚未查看（“未读的已完成”），用户打开过对应会话后不再计入。
 * - [waiting]：需要用户处理，权限确认与问题回答合并显示为“待回复”。
 * - [failed]：执行失败，单独显示，不与已完成合并。
 */
data class TaskSummary(
  val running: Int = 0,
  val completed: Int = 0,
  val waiting: Int = 0,
  val failed: Int = 0
) {
  val isEmpty: Boolean get() = running == 0 && completed == 0 && waiting == 0 && failed == 0

  /**
   * 统一显示文案：
   * - 始终显示“xx个运行中，xx个已完成”；
   * - 存在需要回答/确认的任务时，额外显示“xx个待回复”；
   * - 存在失败任务时，额外单独显示“xx个失败”。
   */
  val text: String?
    get() {
      if (isEmpty) return null
      return buildString {
        append("${running}个运行中，${completed}个已完成")
        if (waiting > 0) append("，${waiting}个待回复")
        if (failed > 0) append("，${failed}个失败")
      }
    }

  /** 状态栏 chip 用的极短文案，无等待/失败时退化为“n跑”。 */
  val shortText: String
    get() = buildString {
      append("${running}跑")
      if (waiting > 0) append("·${waiting}待")
      if (failed > 0) append("·${failed}败")
    }

  companion object {
    val EMPTY = TaskSummary()

    /** Builds counts from a session id to phase-name map, ignoring phases this build does not know. */
    fun fromPhaseNames(phases: Map<String, String>, acknowledged: Set<String> = emptySet()): TaskSummary = of(
      phases.mapNotNull { (id, name) ->
        val phase = runCatching { TaskPhase.valueOf(name) }.getOrNull() ?: return@mapNotNull null
        id to TaskState(id, phase)
      }.toMap(),
      acknowledged
    )

    /**
     * @param acknowledged 已被用户查看过、不再计入“未读已完成/失败”的会话 id。
     */
    fun of(tasks: Map<String, TaskState>, acknowledged: Set<String> = emptySet()): TaskSummary {
      var running = 0
      var completed = 0
      var waiting = 0
      var failed = 0
      for (task in tasks.values) {
        when (task.phase) {
          in TaskState.RUNNING_PHASES -> running += 1
          TaskPhase.WAITING_PERMISSION, TaskPhase.WAITING_QUESTION -> waiting += 1
          TaskPhase.COMPLETED -> if (task.sessionId !in acknowledged) completed += 1
          TaskPhase.FAILED -> if (task.sessionId !in acknowledged) failed += 1
          else -> Unit
        }
      }
      return TaskSummary(running = running, completed = completed, waiting = waiting, failed = failed)
    }
  }
}
