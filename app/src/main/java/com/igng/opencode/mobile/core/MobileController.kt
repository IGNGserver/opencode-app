package com.igng.opencode.mobile.core

import android.content.Context
import com.igng.opencode.mobile.system.TaskNotifications
import com.igng.opencode.mobile.system.TaskMonitorService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

data class MobileState(
  val profiles: List<ServerProfile> = emptyList(), val serverId: String? = null, val version: String = "", val protocol: ServerProtocol = ServerProtocol.UNKNOWN,
  val connected: Boolean = false, val cached: Boolean = false, val loading: Boolean = false, val error: String? = null,
  /** True when the last catalog refresh could not read every project; some values are last-known. */
  val degraded: Boolean = false,
  val staleDirectories: Set<String> = emptySet(), val streamConnected: Boolean = false,
  /** Non-error feedback (e.g. a share link); kept separate so it is not rendered as a failure. */
  val message: String? = null,
  val projects: List<Project> = emptyList(), val projectId: String? = null,
  val sessions: List<Session> = emptyList(), val sessionId: String? = null,
  val messages: List<Message> = emptyList(), val tasks: Map<String, TaskState> = emptyMap(),
  val permissions: List<PermissionRequest> = emptyList(), val questions: List<QuestionRequest> = emptyList(),
  val todos: List<TodoItem> = emptyList(), val children: List<Session> = emptyList(), val changes: List<FileChange> = emptyList(),
  val agents: List<AgentChoice> = emptyList(), val models: List<ModelChoice> = emptyList(), val commands: List<CommandChoice> = emptyList(),
  val agent: String? = null, val model: ModelChoice? = null,
  val files: List<FileNode> = emptyList(), val filePath: String = ".", val fileText: String? = null, val fileBinary: Boolean = false,
  val searchResults: List<String> = emptyList(),
  val supportsSavedPermissions: Boolean = false, val savedPermissions: List<SavedPermission>? = null,
  /** 已被用户查看过、不再计入“未读已完成/失败”的会话 id（本会话内有效）。 */
  val acknowledged: Set<String> = emptySet(),
  /** 全服务器范围的任务计数，由 [tasks] 与 [acknowledged] 派生，供灵动岛与系统通知复用。 */
  val summary: TaskSummary = TaskSummary.EMPTY,
  /** 存在未读已完成/失败时，灵动岛点击应跳转的会话 id。 */
  val summaryTargetId: String? = null
) {
  val server: ServerProfile? get() = profiles.firstOrNull { it.id == serverId }
  val project: Project? get() = projects.firstOrNull { it.id == projectId }
  val session: Session? get() = sessions.firstOrNull { it.id == sessionId }
}

class MobileController private constructor(private val appContext: Context) {
  companion object {
    private val CATALOG_REFRESH_EVENTS = setOf(
      "session.created", "session.updated", "session.deleted", "session.idle", "session.error",
      "permission.asked", "question.asked", "permission.replied", "permission.rejected",
      "question.replied", "question.rejected"
    )
    private val TERMINAL_PHASES = setOf(TaskPhase.COMPLETED, TaskPhase.FAILED)
    @Volatile private var instance: MobileController? = null
    fun get(context: Context): MobileController = instance ?: synchronized(this) {
      instance ?: MobileController(context.applicationContext).also { instance = it }
    }
    /** Control-plane reconciliation cadence while the SSE stream is up. */
    private const val RECONCILE_INTERVAL_MILLIS = 45_000L
  }
  /**
   * Recomputes the server-wide island summary. A session's unread terminal state keeps counting until
   * the user opens that session ([selectSession] acknowledges it), so “已完成/失败” means “未读”。
   */
  private fun withSummary(state: MobileState): MobileState = state.copy(
    summary = TaskSummary.of(state.tasks, state.acknowledged),
    // Prefer a session that needs a reply; otherwise jump to the first unread terminal result.
    summaryTargetId = state.tasks.values.firstOrNull { it.phase in TaskState.WAITING_PHASES }?.sessionId
      ?: state.tasks.values.firstOrNull { it.phase in TERMINAL_PHASES && it.sessionId !in state.acknowledged }?.sessionId
  )
  private val store = ServerStore(appContext)
  private val cache = OfflineCache(appContext)
  private val notifications = TaskNotifications(appContext)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  // Writing operations that must survive the UI leaving the foreground (permission replies, aborts)
  // run here rather than in a composer-scoped coroutine.
  private val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val mutable = MutableStateFlow(MobileState(profiles = store.profiles(), serverId = store.selectedId()))
  val state: StateFlow<MobileState> = mutable
  private var api: OpenCodeApi? = null
  private var stream: Job? = null
  private var generation = 0
  private var refresh: Job? = null
  private var eventRefresh: Job? = null
  private var messageRefresh: Job? = null
  private var lastEventId = ""
  private val sendMutex = Mutex()
  private var searchSequence = 0L
  private var fileSequence = 0L
  private var selectionRevision = 0L
  private var catalogSequence = 0L
  private var messageSequence = 0L
  private var reconcile: Job? = null

  init {
    com.igng.opencode.mobile.push.PushRevocations(appContext).retry()
    // Single publisher for the server-wide island summary, so the system notification never drifts
    // from the in-app island: both read the same derived `summary` on every state emission.
    scope.launch { state.collect { publishSummary(it) } }
    if (mutable.value.profiles.any { it.id == mutable.value.serverId && it.autoConnect }) connect(mutable.value.serverId!!)
  }
  private fun publishSummary(state: MobileState) {
    val profile = state.server ?: return
    // showSummary cancels the notification itself when the summary is empty.
    notifications.showSummary(profile, state.summary, state.summaryTargetId)
  }
  fun credentials(serverId: String): ServerCredentials = store.credentials(serverId)
  fun deviceId(): String = store.deviceId()
  suspend fun testServer(profile: ServerProfile, credentials: ServerCredentials): String {
    val client = OpenCodeApi(profile, credentials)
    val version = client.health()
    client.projects()
    return version
  }
  fun clearError() = mutable.update { it.copy(error = null) }
  fun clearMessage() = mutable.update { it.copy(message = null) }

  fun saveServer(profile: ServerProfile, password: String?, cookie: String? = null, credentialUsername: String? = null, connect: Boolean = true) {
    val old = store.profiles().firstOrNull { it.id == profile.id }
    if (old != null && old.companionUrl.isNotBlank() &&
      (!profile.notifications || old.url != profile.url || old.companionUrl != profile.companionUrl || old.pluginSecret != profile.pluginSecret)) {
      com.igng.opencode.mobile.push.PushRevocations(appContext).enqueue(old, store.credentials(old.id), store.deviceId())
    }
    store.save(profile, password, cookie, credentialUsername)
    mutable.update { it.copy(profiles = store.profiles()) }
    if (connect) connect(profile.id)
  }
  /** 持久化待合作灵动岛通道开关（荣耀 / OPPO 流体云），不触发重连。 */
  fun setIslandVendor(profile: ServerProfile) {
    store.updateIslandVendor(profile)
    mutable.update { it.copy(profiles = store.profiles()) }
  }
  fun deleteServer(id: String) {
    // Capture the profile before deletion so the companion can be told to stop delivering (A08).
    val leaving = store.profiles().firstOrNull { it.id == id }
    if (mutable.value.serverId == id) {
      stream?.cancel()
      refresh?.cancel()
      eventRefresh?.cancel()
      messageRefresh?.cancel()
      reconcile?.cancel()
      generation += 1
      api = null
      lastEventId = ""
    }
    leaving?.let { com.igng.opencode.mobile.push.PushRevocations(appContext).enqueue(it, store.credentials(id), store.deviceId()) }
    store.delete(id)
    cache.delete(id)

    if (mutable.value.serverId == id) {
      mutable.update { MobileState(profiles = store.profiles(), serverId = store.profiles().firstOrNull()?.id) }
      mutable.value.serverId?.let(::connect)
    } else mutable.update { it.copy(profiles = store.profiles()) }
  }
  fun connect(id: String) {
    val profile = store.profiles().firstOrNull { it.id == id } ?: return
    val leaving = state.value.serverId?.takeIf { it != id && state.value.tasks.values.any { task -> task.active } }
    stream?.cancel()
    refresh?.cancel()
    eventRefresh?.cancel()
    messageRefresh?.cancel()
    reconcile?.cancel()
    generation += 1
    val token = generation
    lastEventId = ""
    api = OpenCodeApi(profile, store.credentials(id))
    val rememberedProject = if (store.selectedId() == id) store.selectedProject() else null
    val rememberedSession = if (store.selectedId() == id) store.selectedSession() else null
    store.select(id, rememberedProject, rememberedSession)
    mutable.update { MobileState(profiles = store.profiles(), serverId = id, loading = true, acknowledged = store.acknowledgedTasks(id),
      message = if (leaving != null) "已结束上一服务器的本地监控；远端任务继续运行。" else null) }
    scope.launch {
      try {
        loadAll(token)
        if (token == generation) {
          startStream(token)
          registerPushIfConfigured(profile)
        }
      } catch (error: Exception) {
        if (error is CancellationException) throw error
        if (token != generation) return@launch
        showOffline(id, token, error.message ?: "连接失败")
        // A refresh while offline must be able to recover: retry the connection with backoff instead
        // of leaving the user on a stale snapshot with no path back to a live stream (A10).
        scheduleReconnect(id, token)
      }
    }
  }
  private fun scheduleReconnect(id: String, token: Int, attempt: Int = 0) {
    if (token != generation || state.value.serverId != id) return
    val delayMillis = (2_000L shl attempt.coerceAtMost(4)).coerceAtMost(60_000L)
    scope.launch {
      delay(delayMillis)
      if (token == generation && state.value.serverId == id && !state.value.connected) reconnect(id, token, attempt)
    }
  }
  private suspend fun reconnect(id: String, token: Int, attempt: Int) {
    val profile = store.profiles().firstOrNull { it.id == id } ?: return
    api = OpenCodeApi(profile, store.credentials(id))
    try {
      loadAll(token)
      if (token != generation) return
      startStream(token)
    } catch (error: Exception) {
      if (error is CancellationException) throw error
      if (token == generation) showOffline(id, token, "连接失败，正在重试")
      scheduleReconnect(id, token, attempt + 1)
    }
  }
  private suspend fun showOffline(id: String, token: Int, reason: String) {
    val snapshot = kotlinx.coroutines.withContext(Dispatchers.IO) { cache.catalog(id) }
    if (token != generation || state.value.serverId != id) return
    if (snapshot == null) mutable.update { it.copy(loading = false, connected = false, error = reason) }
    else {
      val (projects, sessions) = snapshot
      val sessionId = store.selectedSession()?.takeIf { selected -> sessions.any { it.id == selected } }
      mutable.update { it.copy(loading = false, connected = false, cached = true, error = "离线缓存：$reason",
        projects = projects, sessions = sessions, projectId = store.selectedProject() ?: projects.firstOrNull()?.id,
        sessionId = sessionId, messages = emptyList()) }
      if (sessionId != null) {
        val messages = kotlinx.coroutines.withContext(Dispatchers.IO) { cache.messages(id, sessionId) }
        if (token == generation && state.value.serverId == id && state.value.sessionId == sessionId) mutable.update { it.copy(messages = messages) }
      }
    }
  }
  private suspend fun loadAll(token: Int, controlOnly: Boolean = false) {
    val client = api ?: return
    val requestSequence = ++catalogSequence
    val version = client.health()
    val projects = client.projects()
    // Sessions, statuses, permissions and questions are independent per project; fetch them in
    // parallel so a multi-project server resolves in roughly one round trip instead of 4*N.
    //
    // Failures are tracked per resource rather than converted to an empty result: a failed status,
    // permission or question read must not be mistaken for an authoritative "nothing to see", which
    // used to turn a running task into COMPLETED and silently clear pending approvals (A05).
    val sessionResults = coroutineScope {
      projects.map { project -> async { project to attempt { client.sessions(project.directory) } } }.awaitAll()
    }
    if (sessionResults.isNotEmpty() && sessionResults.all { it.second.isFailure }) throw sessionResults.first().second.exceptionOrNull()!!
    val failedSessionDirs = sessionResults.filter { it.second.isFailure }.map { it.first.directory }.toSet()
    val sessions = sessionResults.flatMap { it.second.getOrDefault(emptyList()) }.distinctBy { it.id }.sortedByDescending { it.updated }
    val statusResults = coroutineScope {
      projects.map { project -> async { project.directory to attempt { client.status(project.directory) } } }.awaitAll()
    }
    val failedStatusDirs = statusResults.filter { it.second.isFailure }.map { it.first }.toSet()
    val statuses = statusResults.mapNotNull { it.second.getOrNull() }.flatMap { it.entries }.associate { it.key to it.value }
    val permissionResults = coroutineScope {
      projects.map { project -> async { project.directory to attempt { client.permissions(project.directory) } } }.awaitAll()
    }
    val failedPermissionDirs = permissionResults.filter { it.second.isFailure }.map { it.first }.toSet()
    val permissions = permissionResults.mapNotNull { it.second.getOrNull() }.flatten().distinctBy { it.id }
    val questionResults = coroutineScope {
      projects.map { project -> async { project.directory to attempt { client.questions(project.directory) } } }.awaitAll()
    }
    val failedQuestionDirs = questionResults.filter { it.second.isFailure }.map { it.first }.toSet()
    val questions = questionResults.mapNotNull { it.second.getOrNull() }.flatten().distinctBy { it.id }
    if (token != generation || requestSequence != catalogSequence) return
    val degraded = failedSessionDirs + failedStatusDirs + failedPermissionDirs + failedQuestionDirs
    if (degraded.isNotEmpty()) Diagnostics.warn("MobileController", "部分数据读取失败，保留上次可信状态：$degraded")

    mutable.update { previous ->
      // A session whose project failed to report status keeps its previous phase; only an actual
      // authoritative status (or its absence from a successful response) may reduce it.
      val nextSessions = (sessions + previous.sessions.filter { it.directory in failedSessionDirs }).distinctBy { it.id }.sortedByDescending { it.updated }
      val degradedDirs = failedStatusDirs + failedSessionDirs
      val states = mutableMapOf<String, TaskState>()
      nextSessions.forEach { session ->
        val authoritative = session.directory !in degradedDirs
        val next = if (!authoritative) previous.tasks[session.id]
        else TaskReducer.status(session.id, statuses[session.id] ?: "idle", previous.tasks[session.id])
        if (next != null) states[session.id] = next
      }
      // A fully successful read is authoritative; otherwise merge fetched entries with the previous
      // ones for the failed directories so a pending approval is never silently dropped.
      val keptPermissions = previous.permissions.filter { it.directory in failedPermissionDirs }
      val nextPermissions = (permissions + keptPermissions).distinctBy { it.id }
      val keptQuestions = previous.questions.filter { it.directory in failedQuestionDirs }
      val nextQuestions = (questions + keptQuestions).distinctBy { it.id }
      nextPermissions.forEach { states[it.sessionId] = TaskState(it.sessionId, TaskPhase.WAITING_PERMISSION, "等待权限确认", previous.tasks[it.sessionId]?.since ?: System.currentTimeMillis()) }
      nextQuestions.forEach { states[it.sessionId] = TaskState(it.sessionId, TaskPhase.WAITING_QUESTION, "等待你的回答", previous.tasks[it.sessionId]?.since ?: System.currentTimeMillis()) }
      withSummary(previous.copy(version = version, protocol = client.detectedProtocol(), supportsSavedPermissions = client.supportsSavedPermissions(), connected = true, cached = previous.cached && previous.sessionId != null, loading = false,
        error = null, degraded = degraded.isNotEmpty(), staleDirectories = degraded,
        projects = projects, sessions = nextSessions, tasks = states,
        permissions = nextPermissions, questions = nextQuestions,
        projectId = (previous.projectId ?: store.selectedProject())?.takeIf { id -> projects.any { it.id == id } } ?: projects.firstOrNull()?.id,
        sessionId = (previous.sessionId ?: store.selectedSession())?.takeIf { id -> nextSessions.any { it.id == id } }))
    }
    val current = mutable.value
    current.serverId?.let { cache.saveCatalog(it, current.projects, current.sessions) }
    if (controlOnly) return
    current.project?.let { project -> loadChoices(project.directory, token) }
    current.session?.let { loadSession(it, token = token) }
  }
  fun reload() {
    val token = generation
    refresh?.cancel()
    refresh = scope.launch {
      try {
        loadAll(token)
        // A refresh after an offline start must also (re)establish the live event stream, otherwise
        // the UI shows connected=true with no realtime updates (A10).
        if (token == generation && stream?.isActive != true) startStream(token)
      } catch (error: Exception) {
        if (token == generation) mutable.update { state -> state.copy(error = error.message) }
      }
    }
  }
  private fun startStream(token: Int) {
    stream?.cancel()
    stream = scope.launch {
      var retry = 1_000L
      while (isActive && token == generation) {
          api?.events(lastEventId, onOpen = { scope.launch {
            if (token == generation) mutable.update { it.copy(streamConnected = true) }
          } })?.catch { cause ->
            if (token == generation) mutable.update { it.copy(connected = false, streamConnected = false,
              error = "实时连接断开，正在重连：${cause.message}") }
          }?.collect { event ->
            if (token == generation) {
              if (event.id.isNotBlank()) lastEventId = event.id
              handleEvent(event)
            }
            // An event arrived: the stream is healthy, so restore the short reconnect delay. A stream
            // that fails without ever delivering keeps doubling its backoff.
            retry = 1_000L
        }
        if (isActive && token == generation) {
          mutable.update { it.copy(streamConnected = false) }
          delay(retry)
          retry = (retry * 2).coerceAtMost(30_000L)
          attempt { loadAll(token) }.onFailure { error -> if (token == generation) mutable.update { it.copy(connected = false, degraded = true, error = "重新同步失败：${error.message.orEmpty()}") } }
        }
      }
    }
    // Standing reconciliation: while the stream is nominally up, periodically re-read the control
    // plane (catalog/status/permissions/questions). This converges state even if the server drops an
    // event without the client seeing a stream failure, so a missed permission or terminal state is
    // repaired rather than waiting for the user to notice (A04/A10).
    reconcile?.cancel()
    reconcile = scope.launch {
      while (isActive && token == generation) {
        delay(RECONCILE_INTERVAL_MILLIS)
        // Skip while a manual refresh is running or while the stream is already down: the stream
        // loop performs a full loadAll on every reconnect, so reconciling there would only fight the
        // disconnected state.
        if (token != generation || refresh?.isActive == true || !state.value.connected) continue
        attempt { loadAll(token, controlOnly = true) }.onFailure { error -> if (token == generation) mutable.update { it.copy(degraded = true, error = "状态同步失败：${error.message}") } }
      }
    }
  }
  private fun handleEvent(event: ServerEvent) {
    val props = event.properties
    val sessionId = props.str("sessionID").ifBlank { props.obj("part").str("sessionID") }.ifBlank { props.obj("info").str("sessionID") }
    val directory = event.directory.ifBlank { mutable.value.sessions.firstOrNull { it.id == sessionId }?.directory.orEmpty() }
    if (sessionId.isNotBlank()) {
      val before = mutable.value.tasks[sessionId]
      var after = TaskReducer.event(sessionId, event.type, props, before)
      if (after != null) {
        val resolved = props.str("requestID").ifBlank { props.str("id") }
        val pendingPermission = mutable.value.permissions.any { it.sessionId == sessionId && !(event.type in setOf("permission.replied", "permission.rejected") && it.id == resolved) }
        val pendingQuestion = mutable.value.questions.any { it.sessionId == sessionId && !(event.type in setOf("question.replied", "question.rejected") && it.id == resolved) }
        val pending = pendingPermission || pendingQuestion
        if (pending && after.phase !in setOf(TaskPhase.FAILED, TaskPhase.ABORTED)) after = TaskState(sessionId,
          if (pendingPermission) TaskPhase.WAITING_PERMISSION else TaskPhase.WAITING_QUESTION,
          if (pendingPermission) "等待权限确认" else "等待你的回答", before?.since ?: System.currentTimeMillis())
        val next = after
        if (!(next.phase == TaskPhase.COMPLETED && pending)) {
          val enteringTerminal = next.phase in TERMINAL_PHASES && before?.phase !in TERMINAL_PHASES
          mutable.update { current ->
            // A fresh completion/failure is unread again even if this session was viewed before.
            val acknowledged = if (enteringTerminal) current.acknowledged - sessionId else current.acknowledged
            withSummary(current.copy(tasks = current.tasks + (sessionId to next), acknowledged = acknowledged))
          }
          val session = mutable.value.sessions.firstOrNull { it.id == sessionId }
          val profile = mutable.value.server
          if (session != null && profile?.notifications == true && next.phase != before?.phase) notifications.show(profile, session, next,
            mutable.value.permissions.firstOrNull { it.sessionId == sessionId })
        }
      }
    }
    when (event.type) {
      "permission.asked" -> {
        val request = props.toPermission(directory)
        mutable.update { it.copy(permissions = (it.permissions.filterNot { old -> old.id == request.id } + request)) }
        notifyAttention(request.sessionId)
      }
      "question.asked" -> {
        val request = props.toQuestion(directory)
        mutable.update { it.copy(questions = (it.questions.filterNot { old -> old.id == request.id } + request)) }
        notifyAttention(request.sessionId)
      }
      "permission.replied", "permission.rejected" -> {
        val requestId = props.str("requestID").ifBlank { props.str("id") }
        mutable.update { it.copy(permissions = it.permissions.filterNot { old -> old.id == requestId }) }
      }
      "question.replied", "question.rejected" -> {
        val requestId = props.str("requestID").ifBlank { props.str("id") }
        mutable.update { it.copy(questions = it.questions.filterNot { old -> old.id == requestId }) }
      }
    }
    if (event.type in CATALOG_REFRESH_EVENTS) {
      eventRefresh?.cancel()
      eventRefresh = scope.launch { delay(350); if (generation > 0) reload() }
    } else if (event.type == "message.updated" && sessionId == mutable.value.sessionId) {
      // A whole message changed; a lightweight transcript refresh is the safest repair.
      val session = mutable.value.session ?: return
      messageRefresh?.cancel()
      messageRefresh = scope.launch { delay(350); loadSession(session, ancillary = false) }
    } else if (event.type == "message.part.updated" && sessionId == mutable.value.sessionId) {
      // Streaming emits one of these per delta. Upserting the single part avoids refetching and
      // re-parsing the entire transcript (and re-encrypting it for the cache) on every chunk.
      if (!upsertStreamedPart(props)) {
        val session = mutable.value.session ?: return
        messageRefresh?.cancel()
        messageRefresh = scope.launch { delay(350); loadSession(session, ancillary = false) }
      }
    }
  }

  /** Applies a single `message.part.updated` payload in place. Returns false when the payload is
   *  incomplete or targets a message we have not loaded, in which case the caller falls back to a
   *  full (debounced) transcript refresh. */
  private fun upsertStreamedPart(props: JSONObject): Boolean {
    val partJson = props.optJSONObject("part") ?: return false
    val partId = partJson.str("id")
    val messageId = props.str("messageID").ifBlank { partJson.str("messageID") }
    if (partId.isBlank()) return false
    val projected = if (mutable.value.protocol == ServerProtocol.V2) partJson.toV2MessagePart() else partJson.toMessagePart()
    val index = mutable.value.messages.indexOfFirst { it.id == messageId }
    if (index < 0) return false
    mutable.update { state ->
      val target = state.messages[index]
      val partIndex = target.parts.indexOfFirst { it.id == partId }
      val parts = if (partIndex < 0) target.parts + projected else target.parts.toMutableList().also { it[partIndex] = projected }
      state.copy(messages = state.messages.toMutableList().also { it[index] = target.copy(parts = parts) })
    }
    return true
  }
  private fun notifyAttention(sessionId: String) {
    val current = mutable.value
    val profile = current.server ?: return
    val session = current.sessions.firstOrNull { it.id == sessionId } ?: return
    if (!profile.notifications) return
    current.tasks[sessionId]?.let { notifications.show(profile, session, it, current.permissions.firstOrNull { p -> p.sessionId == sessionId }) }
  }
  fun selectProject(id: String) {
    val project = mutable.value.projects.firstOrNull { it.id == id } ?: return
    selectionRevision += 1
    mutable.update { it.copy(projectId = id, sessionId = null, messages = emptyList(), agent = null, model = null,
      files = emptyList(), searchResults = emptyList(), fileText = null, fileBinary = false) }
    store.rememberLocation(id, null)
    val token = generation
    scope.launch { loadChoices(project.directory, token) }
  }
  private suspend fun loadChoices(directory: String, token: Int = generation) {
    val client = api ?: return
    // Agents, models and commands are independent; one parallel round instead of three.
    val (agents, models, commands) = coroutineScope {
      val agentsTask = async { attempt { client.agents(directory) }.getOrDefault(emptyList()) }
      val modelsTask = async { attempt { client.models(directory) }.getOrDefault(emptyList()) }
      val commandsTask = async { attempt { client.commands(directory) }.getOrDefault(emptyList()) }
      Triple(agentsTask.await(), modelsTask.await(), commandsTask.await())
    }
    if (token == generation && mutable.value.project?.directory == directory) {
      mutable.update { it.copy(agents = agents, models = models, commands = commands) }
    }
  }
  fun selectSession(id: String) {
    val session = mutable.value.sessions.firstOrNull { it.id == id } ?: return
    selectionRevision += 1
    val project = mutable.value.projects.firstOrNull { it.directory == session.directory }
    val offline = mutable.value.cached && !mutable.value.connected
    val messages = emptyList<Message>()
    // Opening a session reads its result: stop counting it as unread on the island immediately, and
    // persist it so the durable background summary agrees after the App is later killed.
    val acknowledged = mutable.value.acknowledged + id
    mutable.value.serverId?.let { store.acknowledgeTask(it, id) }
    mutable.update { withSummary(it.copy(projectId = project?.id ?: it.projectId, sessionId = id, messages = messages,
      todos = emptyList(), children = emptyList(), changes = emptyList(), files = emptyList(),
      searchResults = emptyList(), fileText = null, fileBinary = false, acknowledged = acknowledged)) }
    store.rememberLocation(project?.id, id)
    if (offline) {
      val serverId = state.value.serverId ?: return
      val revision = selectionRevision
      scope.launch {
        val cachedMessages = kotlinx.coroutines.withContext(Dispatchers.IO) { cache.messages(serverId, id) }
        if (state.value.serverId == serverId && selectionRevision == revision) mutable.update { it.copy(messages = cachedMessages) }
      }
    } else {
      val token = generation
      // Choices and the session transcript are independent; run them concurrently.
      scope.launch { coroutineScope { launch { loadChoices(session.directory, token) }; loadSession(session, token = token) } }
    }
  }
  private suspend fun loadSession(session: Session, ancillary: Boolean = true, token: Int = generation, client: OpenCodeApi? = api) {
    val client = client ?: return
    val serverId = state.value.serverId ?: return
    val revision = selectionRevision
    val requestSequence = ++messageSequence
    fun current() = token == generation && revision == selectionRevision && state.value.sessionId == session.id && requestSequence == messageSequence
    val result = attempt { client.messages(session.id, session.directory) }
    if (token != generation) return
    val messages = result.getOrElse { error ->
      val fallback = kotlinx.coroutines.withContext(Dispatchers.IO) { cache.messages(serverId, session.id) }
      if (current()) mutable.update { it.copy(error = error.message, cached = true) }
      if (fallback.isEmpty()) return else fallback
    }
    if (result.isSuccess) cache.saveMessages(serverId, session.id, messages)
    if (!current()) return
    mutable.update { it.copy(messages = messages, cached = result.isFailure) }
    if (ancillary) {
      // Todos, children and diff are independent; fetch in parallel (one round trip when connected).
      val (todos, children, changes) = coroutineScope {
        val todosTask = async { attempt { client.todos(session) }.getOrDefault(emptyList()) }
        val childrenTask = async { attempt { client.children(session) }.getOrDefault(emptyList()) }
        val changesTask = async { attempt { client.diff(session) }.getOrDefault(emptyList()) }
        Triple(todosTask.await(), childrenTask.await(), changesTask.await())
      }
      if (current()) mutable.update { it.copy(todos = todos, children = children, changes = changes,
        sessions = (it.sessions + children).distinctBy { item -> item.id }.sortedByDescending { item -> item.updated }) }
    }
  }
  fun chooseAgent(name: String?) = mutable.update { it.copy(agent = name) }
  fun chooseModel(model: ModelChoice?) = mutable.update { it.copy(model = model) }

  /**
   * Immutable identity of the connection an asynchronous operation started on. A response may only
   * be committed while [isCurrent] still holds, so a request that was in flight when the user
   * switched server/project cannot write its result into the new context (A02).
   */
  private data class OperationContext(val token: Int, val serverId: String, val client: OpenCodeApi,
    val revision: Long, val snapshot: MobileState) {
    fun connectionCurrent(c: MobileController) = token == c.generation && c.state.value.serverId == serverId && c.api === client && c.state.value.server == snapshot.server
    fun isCurrent(c: MobileController) = connectionCurrent(c) && revision == c.selectionRevision &&
      c.state.value.projectId == snapshot.projectId && c.state.value.sessionId == snapshot.sessionId
  }
  private fun beginOperation(): OperationContext {
    val current = state.value
    check(current.connected && !current.cached) { "数据尚未同步，请重新连接后操作" }
    check(!current.degraded || current.project?.directory !in current.staleDirectories) { "当前项目状态尚未同步，请刷新后操作" }
    return OperationContext(generation, current.serverId ?: error("请先连接服务器"),
      requireNotNull(api) { "请先连接服务器" }, selectionRevision, current)
  }
  private fun OperationContext.commit(update: (MobileState) -> MobileState) {
    if (isCurrent(this@MobileController)) mutable.update(update)
  }
  private fun OperationContext.commitConnection(update: (MobileState) -> MobileState) {
    if (connectionCurrent(this@MobileController)) mutable.update(update)
  }
  fun createSession(title: String, onCreated: ((Session) -> Unit)? = null) = act { op ->
    val project = op.snapshot.project ?: error("先选择项目")
    val session = op.client.createSession(project.directory, title)
    op.commitConnection { it.copy(sessions = (listOf(session) + it.sessions).distinctBy { s -> s.id }) }
    if (op.isCurrent(this)) { selectSession(session.id); onCreated?.invoke(session) }
  }
  fun send(text: String, accepted: (() -> Unit)? = null) = act { op ->
    val session = op.snapshot.session ?: error("先打开会话")
    sendMutex.withLock {
      if (!op.isCurrent(this)) return@act
      check(state.value.tasks[session.id]?.active != true) { "当前会话仍在处理上一项任务" }
      val command = if (text.startsWith('/')) op.snapshot.commands.firstOrNull { text.substringAfter('/').substringBefore(' ') == it.name } else null
      if (command != null) op.client.command(session, command.name, text.substringAfter(' ', ""), op.snapshot.agent, op.snapshot.model)
      else op.client.send(session, text, op.snapshot.agent, op.snapshot.model)
      accepted?.invoke()
      op.commitConnection { withSummary(it.copy(tasks = it.tasks + (session.id to TaskState(session.id, TaskPhase.THINKING, "任务已发送")))) }
    }
    if (op.connectionCurrent(this) && op.snapshot.server?.notifications == true) TaskMonitorService.start(appContext, op.serverId, session.id)
    if (op.isCurrent(this)) loadSession(session, token = op.token, client = op.client, ancillary = false)
  }
  fun abort() = withSession { op, client, session ->
    client.abort(session)
    op.commitConnection { withSummary(it.copy(tasks = it.tasks + (session.id to TaskState(session.id, TaskPhase.ABORTED, "任务已停止")))) }
  }
  fun rename(title: String) = withSession { op, client, session -> client.renameSession(session, title); if (op.connectionCurrent(this)) reload() }
  fun deleteSession() = withSession { op, client, session ->
    client.deleteSession(session)
    cache.deleteMessages(op.serverId, session.id)
    op.commit { it.copy(sessionId = null, messages = emptyList()) }
    op.commitConnection { it.copy(sessions = it.sessions.filterNot { s -> s.id == session.id }, tasks = it.tasks - session.id) }
    if (op.connectionCurrent(this)) reload()
  }
  fun fork() = withSession { op, client, session ->
    val fork = client.forkSession(session)
    op.commitConnection { it.copy(sessions = (listOf(fork) + it.sessions).distinctBy { s -> s.id }) }
    if (op.isCurrent(this)) selectSession(fork.id)
  }
  fun share() = withSession { op, client, session -> val url = client.share(session); op.commit { it.copy(message = "分享链接：$url") } }
  fun unshare() = withSession { _, client, session -> client.unshare(session) }
  fun summarize() = withSession { op, client, session -> client.summarize(session, op.snapshot.model) }
  fun revert(messageId: String) = withSession { op, client, session ->
    client.revert(session, messageId); if (op.isCurrent(this)) loadSession(session, token = op.token, client = client)
  }
  fun unrevert() = withSession { op, client, session ->
    client.unrevert(session); if (op.isCurrent(this)) loadSession(session, token = op.token, client = client)
  }
  fun replyPermission(request: PermissionRequest, reply: String) = act { op ->
    check(request in op.snapshot.permissions) { "权限请求已变化，请刷新" }
    op.client.replyPermission(request, reply)
    op.commitConnection { it.copy(permissions = it.permissions.filterNot { p -> p.id == request.id }) }
  }
  /** Notification execution uses its own immutable client. Only reconcile matching UI state. */
  fun notificationCompleted(serverId: String) {
    scope.launch { if (state.value.serverId == serverId) reload() }
  }
  fun replyQuestion(request: QuestionRequest, answers: List<List<String>>) = act { op ->
    check(request in op.snapshot.questions) { "问题已变化，请刷新" }
    op.client.replyQuestion(request, answers)
    op.commitConnection { it.copy(questions = it.questions.filterNot { q -> q.id == request.id }) }
  }
  fun rejectQuestion(request: QuestionRequest) = act { op ->
    check(request in op.snapshot.questions) { "问题已变化，请刷新" }
    op.client.rejectQuestion(request)
    op.commitConnection { it.copy(questions = it.questions.filterNot { q -> q.id == request.id }) }
  }
  fun listFiles(path: String = "."): Job {
    val request = ++fileSequence
    return act { op ->
      val directory = op.snapshot.project?.directory ?: error("先选择项目")
      val files = op.client.files(directory, path)
      if (request == fileSequence) op.commit { it.copy(files = files, filePath = path, fileText = null, fileBinary = false) }
    }
  }
  fun readFile(path: String): Job {
    val request = ++fileSequence
    return act { op ->
      val directory = op.snapshot.project?.directory ?: error("先选择项目")
      val content = op.client.fileContent(directory, path)
      if (request == fileSequence) op.commit { it.copy(fileText = content.content.takeIf { content.type != "binary" }, fileBinary = content.type == "binary", filePath = path) }
    }
  }
  fun searchFiles(query: String): Job {
    val request = ++searchSequence
    return act { op ->
      val directory = op.snapshot.project?.directory ?: error("先选择项目")
      val results = op.client.searchFiles(directory, query)
      if (request == searchSequence) op.commit { it.copy(searchResults = results) }
    }
  }
  fun loadSavedPermissions() = act { op ->
    val project = op.snapshot.project ?: error("先选择项目")
    val saved = op.client.savedPermissions(project.id)
    op.commit { it.copy(savedPermissions = saved) }
  }
  fun closeSavedPermissions() = mutable.update { it.copy(savedPermissions = null) }
  fun revokeSavedPermission(rule: SavedPermission) = act { op ->
    check(rule in op.snapshot.savedPermissions.orEmpty())
    op.client.revokePermission(rule.id)
    op.commit { it.copy(savedPermissions = it.savedPermissions?.filterNot { old -> old.id == rule.id }) }
  }
  fun registerPush(token: String) = act { op ->
    val profile = op.snapshot.server ?: error("先连接服务器")
    com.igng.opencode.mobile.push.PushRegistration(appContext).register(profile, store.credentials(profile.id), token, store.deviceId())
  }
  /** Best-effort device (re-)registration whenever a push-configured profile connects, so token
   *  rotation or a fresh install does not silently stop background pushes. */
  private fun registerPushIfConfigured(profile: ServerProfile) {
    if (profile.companionUrl.isBlank() || !profile.notifications) return
    val registration = com.igng.opencode.mobile.push.PushRegistration(appContext)
    if (!registration.available()) return
    com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
      scope.launch {
        attempt {
          com.igng.opencode.mobile.push.PushRegistration(appContext).register(profile, store.credentials(profile.id), token, store.deviceId())
        }.onFailure { Diagnostics.warn("Push", "连接后自动注册设备失败", it) }
      }
    }
  }
  private fun withSession(block: suspend (OperationContext, OpenCodeApi, Session) -> Unit) = act { op ->
    val session = op.snapshot.session ?: error("先打开会话")
    block(op, op.client, session)
  }
  private fun act(block: suspend (OperationContext) -> Unit): Job {
    // Capture before launching or waiting for a mutex, not when the coroutine resumes later.
    val captured = attempt { beginOperation() }
    return operationScope.launch {
      val op = captured.getOrElse { error -> mutable.update { it.copy(error = error.message ?: "操作失败") }; return@launch }
      try { block(op) } catch (cancel: CancellationException) { throw cancel } catch (error: Exception) {
        if (op.isCurrent(this@MobileController)) mutable.update { it.copy(error = error.message ?: "操作失败") }
      }
    }
  }
  private inline fun <T> attempt(block: () -> T): Result<T> = try { Result.success(block()) }
    catch (cancel: CancellationException) { throw cancel }
    catch (error: Exception) { Result.failure(error) }
}
