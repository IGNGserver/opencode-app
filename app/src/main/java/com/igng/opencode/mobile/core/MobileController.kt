package com.igng.opencode.mobile.core

import android.content.Context
import com.igng.opencode.mobile.system.TaskNotifications
import com.igng.opencode.mobile.system.TaskMonitorService
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
  val searchResults: List<String> = emptyList()
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
    @Volatile private var instance: MobileController? = null
    fun get(context: Context): MobileController = instance ?: synchronized(this) {
      instance ?: MobileController(context.applicationContext).also { instance = it }
    }
  }
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
  private val contentSearchMutex = Mutex()
  private var searchQuery = ""

  init { if (mutable.value.profiles.any { it.id == mutable.value.serverId && it.autoConnect }) connect(mutable.value.serverId!!) }
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
    store.save(profile, password, cookie, credentialUsername)
    mutable.update { it.copy(profiles = store.profiles()) }
    if (connect) connect(profile.id)
  }
  fun deleteServer(id: String) {
    // Capture the profile before deletion so the companion can be told to stop delivering (A08).
    val leaving = store.profiles().firstOrNull { it.id == id }
    if (mutable.value.serverId == id) {
      stream?.cancel()
      refresh?.cancel()
      eventRefresh?.cancel()
      messageRefresh?.cancel()
      generation += 1
      api = null
      lastEventId = ""
    }
    store.delete(id)
    cache.delete(id)
    leaving?.let { unregisterPush(it) }
    mutable.update { MobileState(profiles = store.profiles(), serverId = store.profiles().firstOrNull()?.id) }
    mutable.value.serverId?.let(::connect)
  }
  fun connect(id: String) {
    val profile = store.profiles().firstOrNull { it.id == id } ?: return
    stream?.cancel()
    refresh?.cancel()
    eventRefresh?.cancel()
    messageRefresh?.cancel()
    generation += 1
    val token = generation
    lastEventId = ""
    api = OpenCodeApi(profile, store.credentials(id))
    val rememberedProject = if (store.selectedId() == id) store.selectedProject() else null
    val rememberedSession = if (store.selectedId() == id) store.selectedSession() else null
    store.select(id, rememberedProject, rememberedSession)
    mutable.update { MobileState(profiles = store.profiles(), serverId = id, loading = true) }
    scope.launch {
      try {
        loadAll(token)
        if (token == generation) {
          startStream(token)
          registerPushIfConfigured(profile)
        }
      } catch (error: Exception) {
        if (token != generation) return@launch
        showOffline(id, error.message ?: "连接失败")
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
    } catch (_: Exception) {
      if (token == generation) showOffline(id, "连接失败，正在重试")
      scheduleReconnect(id, token, attempt + 1)
    }
  }
  private fun showOffline(id: String, reason: String) {
    val snapshot = cache.catalog(id)
    if (snapshot == null) mutable.update { it.copy(loading = false, connected = false, error = reason) }
    else {
      val (projects, sessions) = snapshot
      val sessionId = store.selectedSession()?.takeIf { selected -> sessions.any { it.id == selected } }
      mutable.update { it.copy(loading = false, connected = false, cached = true, error = "离线缓存：$reason",
        projects = projects, sessions = sessions, projectId = store.selectedProject() ?: projects.firstOrNull()?.id,
        sessionId = sessionId, messages = sessionId?.let { selected -> cache.messages(id, selected) } ?: emptyList()) }
    }
  }
  private suspend fun loadAll(token: Int) {
    val client = api ?: return
    val version = client.health()
    val projects = client.projects()
    // Sessions, statuses, permissions and questions are independent per project; fetch them in
    // parallel so a multi-project server resolves in roughly one round trip instead of 4*N.
    //
    // Failures are tracked per resource rather than converted to an empty result: a failed status,
    // permission or question read must not be mistaken for an authoritative "nothing to see", which
    // used to turn a running task into COMPLETED and silently clear pending approvals (A05).
    val sessionResults = coroutineScope {
      projects.map { project -> async { project to runCatching { client.sessions(project.directory) } } }.awaitAll()
    }
    if (sessionResults.isNotEmpty() && sessionResults.all { it.second.isFailure }) throw sessionResults.first().second.exceptionOrNull()!!
    val failedSessionDirs = sessionResults.filter { it.second.isFailure }.map { it.first.directory }.toSet()
    val sessions = sessionResults.flatMap { it.second.getOrDefault(emptyList()) }.distinctBy { it.id }.sortedByDescending { it.updated }
    val statusResults = coroutineScope {
      projects.map { project -> async { project.directory to runCatching { client.status(project.directory) } } }.awaitAll()
    }
    val failedStatusDirs = statusResults.filter { it.second.isFailure }.map { it.first }.toSet()
    val statuses = statusResults.mapNotNull { it.second.getOrNull() }.flatMap { it.entries }.associate { it.key to it.value }
    val permissionResults = coroutineScope {
      projects.map { project -> async { project.directory to runCatching { client.permissions(project.directory) } } }.awaitAll()
    }
    val failedPermissionDirs = permissionResults.filter { it.second.isFailure }.map { it.first }.toSet()
    val permissions = permissionResults.mapNotNull { it.second.getOrNull() }.flatten().distinctBy { it.id }
    val questionResults = coroutineScope {
      projects.map { project -> async { project.directory to runCatching { client.questions(project.directory) } } }.awaitAll()
    }
    val failedQuestionDirs = questionResults.filter { it.second.isFailure }.map { it.first }.toSet()
    val questions = questionResults.mapNotNull { it.second.getOrNull() }.flatten().distinctBy { it.id }
    if (token != generation) return
    val degraded = failedSessionDirs + failedStatusDirs + failedPermissionDirs + failedQuestionDirs
    if (degraded.isNotEmpty()) Diagnostics.warn("MobileController", "部分数据读取失败，保留上次可信状态：$degraded")
    mutable.value.serverId?.let { cache.saveCatalog(it, projects, sessions) }
    mutable.update { previous ->
      // A session whose project failed to report status keeps its previous phase; only an actual
      // authoritative status (or its absence from a successful response) may reduce it.
      val degradedDirs = failedStatusDirs
      val states = mutableMapOf<String, TaskState>()
      sessions.forEach { session ->
        val authoritative = session.directory !in degradedDirs || failedStatusDirs.isEmpty()
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
      val nextSessions = (sessions + previous.sessions.filter { it.directory in failedSessionDirs }).distinctBy { it.id }.sortedByDescending { it.updated }
      previous.copy(version = version, protocol = client.detectedProtocol(), connected = true, cached = false, loading = false,
        error = null, degraded = degraded.isNotEmpty(),
        projects = projects, sessions = nextSessions, tasks = states,
        permissions = nextPermissions, questions = nextQuestions,
        projectId = (previous.projectId ?: store.selectedProject())?.takeIf { id -> projects.any { it.id == id } } ?: projects.firstOrNull()?.id,
        sessionId = (previous.sessionId ?: store.selectedSession())?.takeIf { id -> sessions.any { it.id == id } })
    }
    val current = mutable.value
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
          api?.events(lastEventId)?.catch { cause ->
          if (token == generation) mutable.update { it.copy(connected = false, error = "实时连接断开，正在重连：${cause.message}", tasks = it.tasks.mapValues { (_, task) ->
            if (task.active) task.copy(phase = TaskPhase.DISCONNECTED, detail = "连接已断开") else task
          }) }
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
          delay(retry)
          retry = (retry * 2).coerceAtMost(30_000L)
          runCatching { loadAll(token) }
        }
      }
    }
  }
  private fun handleEvent(event: ServerEvent) {
    val props = event.properties
    val sessionId = props.str("sessionID").ifBlank { props.obj("part").str("sessionID") }.ifBlank { props.obj("info").str("sessionID") }
    val directory = event.directory.ifBlank { mutable.value.sessions.firstOrNull { it.id == sessionId }?.directory.orEmpty() }
    if (sessionId.isNotBlank()) {
      val before = mutable.value.tasks[sessionId]
      val after = TaskReducer.event(sessionId, event.type, props, before)
      if (after != null) {
        val pending = mutable.value.permissions.any { it.sessionId == sessionId } || mutable.value.questions.any { it.sessionId == sessionId }
        if (!(after.phase == TaskPhase.COMPLETED && pending)) {
          mutable.update { it.copy(tasks = it.tasks + (sessionId to after)) }
          val session = mutable.value.sessions.firstOrNull { it.id == sessionId }
          val profile = mutable.value.server
          if (session != null && profile?.notifications == true && after.phase != before?.phase) notifications.show(profile, session, after,
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
    mutable.update { it.copy(projectId = id, sessionId = null, messages = emptyList(), agent = null, model = null) }
    store.rememberLocation(id, null)
    val token = generation
    scope.launch { loadChoices(project.directory, token) }
  }
  private suspend fun loadChoices(directory: String, token: Int = generation) {
    val client = api ?: return
    // Agents, models and commands are independent; one parallel round instead of three.
    val (agents, models, commands) = coroutineScope {
      val agentsTask = async { runCatching { client.agents(directory) }.getOrDefault(emptyList()) }
      val modelsTask = async { runCatching { client.models(directory) }.getOrDefault(emptyList()) }
      val commandsTask = async { runCatching { client.commands(directory) }.getOrDefault(emptyList()) }
      Triple(agentsTask.await(), modelsTask.await(), commandsTask.await())
    }
    if (token == generation && mutable.value.project?.directory == directory) {
      mutable.update { it.copy(agents = agents, models = models, commands = commands) }
    }
  }
  fun selectSession(id: String) {
    val session = mutable.value.sessions.firstOrNull { it.id == id } ?: return
    val project = mutable.value.projects.firstOrNull { it.directory == session.directory }
    val offline = mutable.value.cached && !mutable.value.connected
    val messages = if (offline) mutable.value.serverId?.let { cache.messages(it, id) }.orEmpty() else emptyList()
    mutable.update { it.copy(projectId = project?.id ?: it.projectId, sessionId = id, messages = messages,
      todos = emptyList(), children = emptyList(), changes = emptyList(), files = emptyList(),
      searchResults = emptyList(), fileText = null, fileBinary = false) }
    store.rememberLocation(project?.id, id)
    if (!offline) {
      val token = generation
      // Choices and the session transcript are independent; run them concurrently.
      scope.launch { coroutineScope { launch { loadChoices(session.directory, token) }; loadSession(session, token = token) } }
    }
  }
  private suspend fun loadSession(session: Session, ancillary: Boolean = true, token: Int = generation, client: OpenCodeApi? = api) {
    val client = client ?: return
    val messages = runCatching { client.messages(session.id, session.directory) }.getOrElse { error ->
      val cached = mutable.value.serverId?.let { cache.messages(it, session.id) }.orEmpty()
      if (token == generation) mutable.update { current -> current.copy(error = error.message, cached = cached.isNotEmpty()) }
      if (cached.isEmpty()) return else cached
    }
    if (token != generation) return
    mutable.value.serverId?.let { cache.saveMessages(it, session.id, messages) }
    if (token != generation || mutable.value.sessionId != session.id) return
    // A successful authoritative read supersedes the offline snapshot, so clear the stale flag.
    mutable.update { it.copy(messages = messages, cached = false) }
    if (ancillary) {
      // Todos, children and diff are independent; fetch in parallel (one round trip when connected).
      val (todos, children, changes) = coroutineScope {
        val todosTask = async { runCatching { client.todos(session) }.getOrDefault(emptyList()) }
        val childrenTask = async { runCatching { client.children(session) }.getOrDefault(emptyList()) }
        val changesTask = async { runCatching { client.diff(session) }.getOrDefault(emptyList()) }
        Triple(todosTask.await(), childrenTask.await(), changesTask.await())
      }
      if (token == generation && mutable.value.sessionId == session.id) mutable.update { it.copy(todos = todos, children = children, changes = changes,
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
  private data class OperationContext(val token: Int, val serverId: String, val client: OpenCodeApi) {
    fun isCurrent(controller: MobileController): Boolean =
      token == controller.generation && controller.state.value.serverId == serverId
  }

  private fun beginOperation(): OperationContext {
    if (state.value.cached && !state.value.connected) error("离线缓存模式，重新连接后才能执行操作")
    val serverId = state.value.serverId ?: error("请先连接服务器")
    return OperationContext(generation, serverId, requireNotNull(api) { "请先连接服务器" })
  }

  /** Completes this operation only while its originating connection is still the active one. */
  private fun OperationContext.commit(update: (MobileState) -> MobileState) {
    if (isCurrent(this@MobileController)) mutable.update(update)
  }

  fun createSession(title: String, onCreated: ((Session) -> Unit)? = null) = act { op ->
    val project = state.value.project ?: error("先选择项目")
    val session = op.client.createSession(project.directory, title)
    op.commit { state -> state.copy(sessions = (listOf(session) + state.sessions).distinctBy { it.id }.sortedByDescending { it.updated }) }
    if (!op.isCurrent(this)) return@act
    selectSession(session.id)
    onCreated?.invoke(session)
  }
  fun send(text: String, accepted: (() -> Unit)? = null) = act { op ->
    // Only the guard-and-dispatch section is serialized; the follow-up transcript fetch runs outside
    // the lock so a slow reload cannot block the next send.
    val session = sendMutex.withLock {
      val current = state.value.session ?: error("先打开会话")
      if (state.value.tasks[current.id]?.active == true) error("当前会话仍在处理上一项任务")
      val command = if (text.startsWith('/')) state.value.commands.firstOrNull { text.substringAfter('/').substringBefore(' ') == it.name } else null
      if (command != null) op.client.command(current, command.name, text.substringAfter(' ', ""), state.value.agent, state.value.model)
      else op.client.send(current, text, state.value.agent, state.value.model)
      accepted?.invoke()
      op.commit { it.copy(tasks = it.tasks + (current.id to TaskState(current.id, TaskPhase.THINKING, "任务已发送"))) }
      current
    }
    // Monitoring is a user-visible notification contract; do not start it for a profile whose
    // notifications are disabled (A09).
    if (state.value.server?.notifications == true) TaskMonitorService.start(appContext, op.serverId, session.id)
    loadSession(session, token = op.token, client = op.client, ancillary = false)
  }
  fun abort() = withSession { op, client, session ->
    client.abort(session)
    op.commit { it.copy(tasks = it.tasks + (session.id to TaskState(session.id, TaskPhase.ABORTED, "任务已停止"))) }
  }
  fun rename(title: String) = withSession { op, client, session -> client.renameSession(session, title); reload() }
  fun deleteSession() = withSession { op, client, session ->
    client.deleteSession(session)
    // The session no longer exists; drop its offline copy so a later offline view cannot resurrect it.
    cache.deleteMessages(op.serverId, session.id)
    op.commit { it.copy(sessionId = null, messages = emptyList()) }
    reload()
  }
  fun fork() = withSession { op, client, session -> op.client.forkSession(session) }
  fun share() = withSession { op, client, session ->
    val url = client.share(session)
    op.commit { it.copy(message = "分享链接：$url") }
  }
  fun unshare() = withSession { op, client, session -> client.unshare(session) }
  fun summarize() = withSession { op, client, session -> client.summarize(session, state.value.model) }
  fun revert(messageId: String) = withSession { op, client, session ->
    client.revert(session, messageId); loadSession(session, token = op.token, client = op.client)
  }
  fun unrevert() = withSession { op, client, session ->
    client.unrevert(session); loadSession(session, token = op.token, client = op.client)
  }
  fun replyPermission(request: PermissionRequest, reply: String) = act { op ->
    op.client.replyPermission(request, reply)
    op.commit { it.copy(permissions = it.permissions.filterNot { p -> p.id == request.id }) }
  }
  /** Suspend form used by notification actions that must confirm with the server before dismissing;
   *  unlike the [Job]-returning variant it propagates failure instead of writing state.error. */
  suspend fun replyPermissionNow(requestId: String, sessionId: String, directory: String, reply: String) {
    val op = beginOperation()
    op.client.replyPermission(PermissionRequest(requestId, sessionId, directory, "", ""), reply)
    op.commit { it.copy(permissions = it.permissions.filterNot { p -> p.id == requestId }) }
  }
  /** Reply to a permission straight from a system notification, keeping in-app state in sync. */
  fun replyPermission(requestId: String, sessionId: String, directory: String, reply: String) =
    act { replyPermissionNow(requestId, sessionId, directory, reply) }
  /** Abort a session straight from a system notification, keeping in-app state in sync. The session
   *  is routed with its real directory so a multi-project V1 server aborts the right task (A03). */
  fun abortSession(sessionId: String, directory: String) = act { abortSessionNow(sessionId, directory) }
  /** Suspend form used by notification actions; see [replyPermissionNow]. */
  suspend fun abortSessionNow(sessionId: String, directory: String) {
    val op = beginOperation()
    op.client.abort(Session(sessionId, directory, "", 0))
    op.commit { it.copy(tasks = it.tasks + (sessionId to TaskState(sessionId, TaskPhase.ABORTED, "任务已停止"))) }
  }  fun replyQuestion(request: QuestionRequest, answers: List<List<String>>) = act { op ->
    op.client.replyQuestion(request, answers)
    op.commit { it.copy(questions = it.questions.filterNot { q -> q.id == request.id }) }
  }
  fun rejectQuestion(request: QuestionRequest) = act { op ->
    op.client.rejectQuestion(request)
    op.commit { it.copy(questions = it.questions.filterNot { q -> q.id == request.id }) }
  }
  fun listFiles(path: String = ".") = act { op ->
    val directory = state.value.project?.directory ?: error("先选择项目")
    val files = op.client.files(directory, path)
    op.commit { it.copy(files = files, filePath = path, fileText = null, fileBinary = false) }
  }
  fun readFile(path: String) = act { op ->
    val directory = state.value.project?.directory ?: error("先选择项目")
    val content = op.client.fileContent(directory, path)
    op.commit { it.copy(fileText = content.content.takeIf { value -> content.type != "binary" }, fileBinary = content.type == "binary", filePath = path) }
  }
  fun searchFiles(query: String) = act { op ->
    val directory = state.value.project?.directory ?: error("先选择项目")
    contentSearchMutex.withLock { searchQuery = query }
    val results = op.client.searchFiles(directory, query)
    // Latest-wins: a slow response for an older query must not overwrite a newer one.
    contentSearchMutex.withLock {
      if (op.isCurrent(this) && query == searchQuery) op.commit { it.copy(searchResults = results) }
    }
  }
  fun registerPush(token: String) = act { op ->
    val profile = state.value.server ?: error("先连接服务器")
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
        runCatching {
          com.igng.opencode.mobile.push.PushRegistration(appContext).register(profile, store.credentials(profile.id), token, store.deviceId())
        }.onFailure { Diagnostics.warn("Push", "连接后自动注册设备失败", it) }
      }
    }
  }
  private fun unregisterPush(profile: ServerProfile) {
    if (profile.companionUrl.isBlank()) return
    val deviceId = store.deviceId()
    scope.launch {
      runCatching { com.igng.opencode.mobile.push.PushRegistration(appContext).unregister(profile, store.credentials(profile.id), deviceId) }
        .onFailure { Diagnostics.warn("Push", "注销设备失败，服务器可能在 TTL 前仍保留记录", it) }
    }
  }
  private fun withSession(block: suspend (OperationContext, OpenCodeApi, Session) -> Unit) = act { op ->
    val session = state.value.session ?: error("先打开会话")
    block(op, op.client, session)
  }
  private fun act(block: suspend (OperationContext) -> Unit): Job = operationScope.launch {
    val op = try { beginOperation() } catch (error: Exception) {
      mutable.update { it.copy(error = error.message ?: "操作失败") }
      return@launch
    }
    try { block(op) } catch (error: Exception) {
      // Only surface an error for the server the user is still looking at.
      if (op.isCurrent(this@MobileController)) mutable.update { it.copy(error = error.message ?: "操作失败") }
    }
  }
}
