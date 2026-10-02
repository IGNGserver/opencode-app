package com.igng.opencode.mobile.core

import android.content.Context
import com.igng.opencode.mobile.system.TaskNotifications
import com.igng.opencode.mobile.system.TaskMonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import java.util.UUID

data class MobileState(
  val profiles: List<ServerProfile> = emptyList(), val serverId: String? = null, val version: String = "", val protocol: ServerProtocol = ServerProtocol.UNKNOWN,
  val connected: Boolean = false, val cached: Boolean = false, val loading: Boolean = false, val error: String? = null,
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

class MobileController private constructor(private val context: Context) {
  companion object {
    @Volatile private var instance: MobileController? = null
    fun get(context: Context): MobileController = instance ?: synchronized(this) {
      instance ?: MobileController(context.applicationContext).also { instance = it }
    }
  }
  private val store = ServerStore(context)
  private val cache = OfflineCache(context)
  private val notifications = TaskNotifications(context)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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
  private val messageStore = MessageStore()

  init { if (mutable.value.profiles.any { it.id == mutable.value.serverId && it.autoConnect }) connect(mutable.value.serverId!!) }
  fun password(serverId: String): String = store.password(serverId)
  fun credentials(serverId: String): ServerCredentials = store.credentials(serverId)
  fun deviceId(): String = store.deviceId()
  suspend fun testServer(profile: ServerProfile, password: String): String = testServer(profile, ServerCredentials(profile.username, password))
  suspend fun testServer(profile: ServerProfile, credentials: ServerCredentials): String {
    val client = OpenCodeApi(profile, credentials)
    val version = client.health()
    client.projects()
    return version
  }
  fun clearError() = mutable.update { it.copy(error = null) }

  fun saveServer(profile: ServerProfile, password: String?, cookie: String? = null, credentialUsername: String? = null, connect: Boolean = true) {
    store.save(profile, password, cookie, credentialUsername)
    mutable.update { it.copy(profiles = store.profiles()) }
    if (connect) connect(profile.id)
  }
  fun deleteServer(id: String) {
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
        if (token == generation) startStream(token)
      } catch (error: Exception) { if (token == generation) showOffline(id, error.message ?: "连接失败") }
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
    // 归属由 projectID 关联；按项目拉取会话时带上 projectId，V2 用 ?project= 精确过滤。
    val sessionResults = projects.map { project -> runCatching { client.sessions(project.directory, project.id) } }
    if (sessionResults.isNotEmpty() && sessionResults.all { it.isFailure }) throw sessionResults.first().exceptionOrNull()!!
    val sessions = sessionResults.flatMap { it.getOrDefault(emptyList()) }.distinctBy { it.id }.sortedByDescending { it.updated }
    val statuses = projects.flatMap { project -> runCatching { client.status(project.directory).entries }.getOrDefault(emptySet()) }.associate { it.key to it.value }
    val permissions = projects.flatMap { runCatching { client.permissions(it.directory) }.getOrDefault(emptyList()) }.distinctBy { it.id }
    val questions = projects.flatMap { runCatching { client.questions(it.directory) }.getOrDefault(emptyList()) }.distinctBy { it.id }
    if (token != generation) return
    mutable.value.serverId?.let { cache.saveCatalog(it, projects, sessions) }
    mutable.update { previous ->
      val states = sessions.associate { session ->
        session.id to TaskReducer.status(session.id, statuses[session.id] ?: "idle", previous.tasks[session.id])
      }.toMutableMap()
      permissions.forEach { states[it.sessionId] = TaskState(it.sessionId, TaskPhase.WAITING_PERMISSION, "等待权限确认") }
      questions.forEach { states[it.sessionId] = TaskState(it.sessionId, TaskPhase.WAITING_QUESTION, "等待你的回答") }
      previous.copy(version = version, protocol = client.detectedProtocol(), connected = true, cached = false, loading = false, error = null,
        projects = projects, sessions = sessions, tasks = states, permissions = permissions, questions = questions,
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
    refresh = scope.launch { runCatching { loadAll(token) }.onFailure { if (token == generation) mutable.update { state -> state.copy(error = it.message) } } }
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
        // 事件属性已归一为 V1 形状（见 toServerEvent），用 V1 契约解析
        val request = V1Contract.permission(props, directory)
        mutable.update { it.copy(permissions = (it.permissions.filterNot { old -> old.id == request.id } + request)) }
        notifyAttention(request.sessionId)
      }
      "question.asked" -> {
        val request = V1Contract.question(props, directory)
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
    // 标题/归属正确性：对 session.created/updated 做**单条 reconcile**（官方 sync.tsx 语义），
    // 直接用事件里的 info 更新该会话，避免整表 reload 覆盖导致标题"找错"。
    if (event.type in setOf("session.created", "session.updated")) {
      val info = props.obj("info").takeIf { it.length() > 0 } ?: props
      reconcileSession(info)
    }
    if (event.type in setOf("session.created", "session.updated", "session.deleted", "session.idle", "session.error", "permission.asked", "question.asked", "permission.replied", "permission.rejected", "question.replied", "question.rejected")) {
      eventRefresh?.cancel()
      eventRefresh = scope.launch { delay(350); if (generation > 0) reload() }
    } else if (isMessageEvent(event.type)) {
      handleMessageEvent(event, sessionId)
    }
  }

  /** 消息级/流式事件（V1 message.* 与 V2 session.next.*）。 */
  private fun isMessageEvent(type: String): Boolean = type.startsWith("message.") || type.startsWith("session.next.")
  /** 用事件里的会话 info 单条更新列表（标题/更新时间/归属），不整表覆盖。 */
  private fun reconcileSession(info: JSONObject) {
    if (info.length() == 0) return
    val parsed = when (api?.detectedProtocol()) {
      ServerProtocol.V2 -> V2Contract.session(info)
      else -> V1Contract.session(info)
    }
    if (parsed.id.isBlank()) return
    mutable.update { state ->
      val exists = state.sessions.any { it.id == parsed.id }
      val merged = if (exists) state.sessions.map { if (it.id == parsed.id) parsed else it }
      else listOf(parsed) + state.sessions
      state.copy(sessions = merged.sortedByDescending { it.updated })
    }
  }

  private fun notifyAttention(sessionId: String) {
    val current = mutable.value
    val profile = current.server ?: return
    val session = current.sessions.firstOrNull { it.id == sessionId } ?: return
    if (!profile.notifications) return
    current.tasks[sessionId]?.let { notifications.show(profile, session, it, current.permissions.firstOrNull { p -> p.sessionId == sessionId }) }
  }

  /**
   * 消息级细粒度 reconcile（官方 sync.tsx 语义）。
   * V1：`message.updated`/`message.part.updated`/`message.part.delta`/`message.part.removed`/`message.removed` 就地改 MessageStore。
   * V2：`session.next.*` 用 assistantMessageID + textID/callID/reasoningID 精确定位 part，就地流式更新。
   * 未建模的事件对当前会话去抖刷新兜底，绝不丢内容。
   */
  private fun handleMessageEvent(event: ServerEvent, sessionId: String) {
    if (sessionId.isBlank()) return
    val props = event.properties
    var handled = true
    // V2 流式事件公共定位符
    val mid = props.str("assistantMessageID")
    val ts = props.optLong("timestamp").takeIf { it > 0 } ?: System.currentTimeMillis()
    fun ensureAssistant(id: String) {
      if (messageStore.snapshot(sessionId).none { it.id == id })
        messageStore.upsertMessage(sessionId, Message(id, "assistant", ts, emptyList()))
    }
    when (event.type) {
      /* ---- V1 聚合事件 ---- */
      "message.updated" -> messageStore.upsertMessage(sessionId, V1Contract.info(props.obj("info")))
      "message.part.updated" -> {
        val partJson = props.obj("part")
        messageStore.upsertPart(sessionId, partJson.str("messageID"), V1Contract.part(partJson))
      }
      "message.part.delta" -> {
        val pid = props.str("partID"); val delta = props.str("delta")
        messageStore.patchPart(sessionId, props.str("messageID"), pid, MessagePart(pid, "text")) { it.copy(text = it.text + delta) }
      }
      "message.part.removed" -> messageStore.removePart(sessionId, props.str("messageID"), props.str("partID"))
      "message.removed" -> messageStore.removeMessage(sessionId, props.str("messageID"))

      /* ---- V2 session.next.* 流式事件 ---- */
      "session.next.text.started" -> { val t = props.str("textID"); ensureAssistant(mid); messageStore.upsertPart(sessionId, mid, MessagePart(t, "text")) }
      "session.next.text.delta" -> { val t = props.str("textID"); messageStore.patchPart(sessionId, mid, t, MessagePart(t, "text")) { it.copy(text = it.text + props.str("delta")) } }
      "session.next.text.ended" -> { val t = props.str("textID"); ensureAssistant(mid); messageStore.upsertPart(sessionId, mid, MessagePart(t, "text", text = props.str("text"))) }

      "session.next.reasoning.started" -> { val r = props.str("reasoningID"); ensureAssistant(mid); messageStore.upsertPart(sessionId, mid, MessagePart(r, "reasoning")) }
      "session.next.reasoning.delta" -> { val r = props.str("reasoningID"); messageStore.patchPart(sessionId, mid, r, MessagePart(r, "reasoning")) { it.copy(text = it.text + props.str("delta")) } }
      "session.next.reasoning.ended" -> { val r = props.str("reasoningID"); ensureAssistant(mid); messageStore.upsertPart(sessionId, mid, MessagePart(r, "reasoning", text = props.str("text"))) }

      "session.next.tool.input.started" -> { val c = props.str("callID"); ensureAssistant(mid); messageStore.upsertPart(sessionId, mid, MessagePart(c, "tool", tool = props.str("name"), status = "running")) }
      "session.next.tool.input.delta" -> { val c = props.str("callID"); messageStore.patchPart(sessionId, mid, c, MessagePart(c, "tool")) { it.copy(input = it.input + props.str("delta")) } }
      "session.next.tool.input.ended" -> { val c = props.str("callID"); messageStore.patchPart(sessionId, mid, c, MessagePart(c, "tool")) { it.copy(input = props.str("text")) } }

      "session.next.tool.called" -> {
        val c = props.str("callID"); ensureAssistant(mid)
        messageStore.upsertPart(sessionId, mid, MessagePart(c, "tool", tool = props.str("tool"), status = "running", input = props.valueText("input")))
      }
      "session.next.tool.progress" -> {
        val c = props.str("callID")
        messageStore.patchPart(sessionId, mid, c, MessagePart(c, "tool", status = "running")) {
          it.copy(status = "running", output = v2ToolText(props.arr("content")).ifBlank { it.output })
        }
      }
      "session.next.tool.success" -> {
        val c = props.str("callID"); ensureAssistant(mid)
        messageStore.upsertPart(sessionId, mid, MessagePart(c, "tool",
          tool = messageStore.snapshot(sessionId).firstOrNull { it.id == mid }?.parts?.firstOrNull { it.id == c }?.tool.orEmpty(),
          status = "completed", output = v2ToolText(props.arr("content")),
          files = v2ToolFiles(props.arr("content")) + props.arr("outputPaths").strings()))
      }
      "session.next.tool.failed" -> {
        val c = props.str("callID"); ensureAssistant(mid)
        messageStore.patchPart(sessionId, mid, c, MessagePart(c, "tool")) {
          it.copy(status = "error", error = props.obj("error").str("message").ifBlank { props.obj("error").toString() })
        }
      }

      "session.next.shell.started" -> { val c = props.str("callID"); ensureAssistant(mid); messageStore.upsertPart(sessionId, mid, MessagePart(c, "tool", tool = "shell", title = props.str("command"), status = "running")) }
      "session.next.shell.ended" -> messageStore.patchPart(sessionId, mid, props.str("callID"), MessagePart(props.str("callID"), "tool", tool = "shell")) { it.copy(status = "exited", output = props.str("output")) }

      else -> handled = false
    }
    if (handled) {
      if (sessionId == mutable.value.sessionId) mutable.update { it.copy(messages = messageStore.snapshot(sessionId)) }
    } else if (sessionId == mutable.value.sessionId) {
      // 未建模事件：去抖刷新兜底（step 边界、agent/model 切换等结构性事件）
      val session = mutable.value.session ?: return
      messageRefresh?.cancel()
      messageRefresh = scope.launch { delay(350); loadSession(session, ancillary = false) }
    }
  }

  private fun v2ToolText(content: org.json.JSONArray): String =
    content.objects().filter { it.str("type") == "text" }.joinToString("\n") { it.str("text") }
  private fun v2ToolFiles(content: org.json.JSONArray): List<String> =
    content.objects().filter { it.str("type") == "file" }.map { it.str("name").ifBlank { it.str("uri") } }
  fun selectProject(id: String) {
    val project = mutable.value.projects.firstOrNull { it.id == id } ?: return
    mutable.update { it.copy(projectId = id, sessionId = null, messages = emptyList(), agent = null, model = null) }
    store.rememberLocation(id, null)
    val token = generation
    scope.launch { loadChoices(project.directory, token) }
  }
  private suspend fun loadChoices(directory: String, token: Int = generation) {
    val client = api ?: return
    val agents = runCatching { client.agents(directory) }.getOrDefault(emptyList())
    val models = runCatching { client.models(directory) }.getOrDefault(emptyList())
    val commands = runCatching { client.commands(directory) }.getOrDefault(emptyList())
    if (token == generation && mutable.value.project?.directory == directory) {
      mutable.update { it.copy(agents = agents, models = models, commands = commands) }
    }
  }
  fun selectSession(id: String) {
    val session = mutable.value.sessions.firstOrNull { it.id == id } ?: return
    // 归属判定用 projectID 关联（V1/V2 都有 projectID），严禁用 directory 字符串匹配反推。
    val project = mutable.value.projects.firstOrNull { it.id == session.projectId }
      ?: mutable.value.projects.firstOrNull { it.directory == session.directory }
    val offline = mutable.value.cached && !mutable.value.connected
    val messages = if (offline) mutable.value.serverId?.let { cache.messages(it, id) }.orEmpty() else emptyList()
    mutable.update { it.copy(projectId = project?.id ?: it.projectId, sessionId = id, messages = messages,
      todos = emptyList(), children = emptyList(), changes = emptyList(), files = emptyList(),
      searchResults = emptyList(), fileText = null, fileBinary = false) }
    store.rememberLocation(project?.id, id)
    if (!offline) {
      val token = generation
      scope.launch { loadChoices(session.directory, token); loadSession(session, token = token) }
    }
  }
  private suspend fun loadSession(session: Session, ancillary: Boolean = true, token: Int = generation) {
    val client = api ?: return
    val messages = runCatching { client.messages(session.id, session.directory) }.getOrElse { error ->
      val cached = mutable.value.serverId?.let { cache.messages(it, session.id) }.orEmpty()
      if (token == generation) mutable.update { current -> current.copy(error = error.message, cached = cached.isNotEmpty()) }
      if (cached.isEmpty()) return else cached
    }
    if (token != generation) return
    mutable.value.serverId?.let { cache.saveMessages(it, session.id, messages) }
    if (token != generation || mutable.value.sessionId != session.id) return
    messageStore.mergeFetched(session.id, messages)
    mutable.update { it.copy(messages = messageStore.snapshot(session.id)) }
    if (ancillary) {
      val todos = runCatching { client.todos(session) }.getOrDefault(emptyList())
      val children = runCatching { client.children(session) }.getOrDefault(emptyList())
      val changes = runCatching { client.diff(session) }.getOrDefault(emptyList())
      if (token == generation && mutable.value.sessionId == session.id) mutable.update { it.copy(todos = todos, children = children, changes = changes,
        sessions = (it.sessions + children).distinctBy { item -> item.id }.sortedByDescending { item -> item.updated }) }
    }
  }
  fun chooseAgent(name: String?) = mutable.update { it.copy(agent = name) }
  fun chooseModel(model: ModelChoice?) = mutable.update { it.copy(model = model) }
  fun createSession(title: String) = act {
    val project = state.value.project ?: error("先选择项目")
    val session = requireNotNull(api).createSession(project.directory, title)
    mutable.update { it.copy(sessions = listOf(session) + it.sessions) }
    selectSession(session.id)
  }
  fun send(text: String, accepted: (() -> Unit)? = null) = act {
    sendMutex.withLock {
      val session = state.value.session ?: error("先打开会话")
      if (state.value.tasks[session.id]?.active == true) error("当前会话仍在处理上一项任务")
      val client = requireNotNull(api)
      val command = if (text.startsWith('/')) state.value.commands.firstOrNull { text.substringAfter('/').substringBefore(' ') == it.name } else null
      if (command != null) client.command(session, command.name, text.substringAfter(' ', ""), state.value.agent, state.value.model)
      else client.send(session, text, state.value.agent, state.value.model)
      accepted?.invoke()
      mutable.update { it.copy(tasks = it.tasks + (session.id to TaskState(session.id, TaskPhase.THINKING, "任务已发送"))) }
      TaskMonitorService.start(context, requireNotNull(state.value.server).id, session.id)
      loadSession(session, ancillary = false, token = generation)
    }
  }
  fun abort() = withSession { client, session -> client.abort(session); mutable.update { it.copy(tasks = it.tasks + (session.id to TaskState(session.id, TaskPhase.ABORTED, "任务已停止"))) } }
  fun rename(title: String) = withSession { client, session -> client.renameSession(session, title); reload() }
  fun deleteSession() = withSession { client, session -> client.deleteSession(session); mutable.update { it.copy(sessionId = null, messages = emptyList()) }; reload() }
  fun fork() = withSession { client, session -> val fork = client.forkSession(session); reload(); delay(300); selectSession(fork.id) }
  fun share() = withSession { client, session -> val url = client.share(session); mutable.update { it.copy(error = "分享链接：$url") } }
  fun unshare() = withSession { client, session -> client.unshare(session) }
  fun summarize() = withSession { client, session -> client.summarize(session, state.value.model) }
  fun revert(messageId: String) = withSession { client, session -> client.revert(session, messageId); loadSession(session) }
  fun unrevert() = withSession { client, session -> client.unrevert(session); loadSession(session) }
  fun replyPermission(request: PermissionRequest, reply: String) = act {
    requireNotNull(api).replyPermission(request, reply)
    mutable.update { it.copy(permissions = it.permissions.filterNot { p -> p.id == request.id }) }
  }
  fun replyQuestion(request: QuestionRequest, answers: List<List<String>>) = act {
    requireNotNull(api).replyQuestion(request, answers)
    mutable.update { it.copy(questions = it.questions.filterNot { q -> q.id == request.id }) }
  }
  fun rejectQuestion(request: QuestionRequest) = act {
    requireNotNull(api).rejectQuestion(request)
    mutable.update { it.copy(questions = it.questions.filterNot { q -> q.id == request.id }) }
  }
  fun listFiles(path: String = ".") = act {
    val directory = state.value.project?.directory ?: error("先选择项目")
    val files = requireNotNull(api).files(directory, path)
    mutable.update { it.copy(files = files, filePath = path, fileText = null, fileBinary = false) }
  }
  fun readFile(path: String) = act {
    val directory = state.value.project?.directory ?: error("先选择项目")
    val content = requireNotNull(api).fileContent(directory, path)
    mutable.update { it.copy(fileText = content.content.takeIf { value -> content.type != "binary" }, fileBinary = content.type == "binary", filePath = path) }
  }
  fun searchFiles(query: String) = act {
    val directory = state.value.project?.directory ?: error("先选择项目")
    val results = requireNotNull(api).searchFiles(directory, query)
    mutable.update { it.copy(searchResults = results) }
  }
  fun registerPush(token: String) = act {
    val profile = state.value.server ?: error("先连接服务器")
    com.igng.opencode.mobile.push.PushRegistration(context).register(profile, store.credentials(profile.id), token, store.deviceId())
  }
  private fun withSession(block: suspend (OpenCodeApi, Session) -> Unit) = act { block(requireNotNull(api), state.value.session ?: error("先打开会话")) }
  private fun act(block: suspend () -> Unit): Job = scope.launch {
    try { block() } catch (error: Exception) { mutable.update { it.copy(error = error.message ?: "操作失败") } }
  }
}
