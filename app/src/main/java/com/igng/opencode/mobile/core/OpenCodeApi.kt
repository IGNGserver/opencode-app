package com.igng.opencode.mobile.core

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ServerCredentials(
  val username: String = "opencode",
  val password: String = "",
  val cookie: String = ""
)

data class ServerEvent(val id: String = "", val directory: String, val type: String, val properties: JSONObject)
class ApiException(val status: Int, message: String, val responseBody: String = "") : IOException(message)

/**
 * 服务端 API 代际。两套都完整支持，不做"猜协议"：
 * [health] 探测确定后，端点与解析严格走对应代际的契约（见 [V1Contract]/[V2Contract]）。
 */
enum class ServerProtocol { UNKNOWN, V1, V2 }

/**
 * OpenCode Server 双协议客户端。
 *
 * 设计原则（对应"完全照抄官方客户端逻辑"）：
 * 1. 端点一一对应官方 SDK 的 `operationId`，不自造路径；
 * 2. 请求体字段严格按 schema（V2 均 `additionalProperties:false`，字段名错即 400）；
 * 3. 解析全部委托 [V1Contract]/[V2Contract]，本类不含 JSON 字段名；
 * 4. 分页遵守 `cursor` 不与 `order` 同用的约束。
 */
class OpenCodeApi(private val profile: ServerProfile, private val credentials: ServerCredentials) {
  constructor(profile: ServerProfile, password: String) : this(profile, ServerCredentials(profile.username, password))

  private val base: HttpUrl = requireNotNull(profile.url.trimEnd('/').toHttpUrlOrNull()) { "服务器地址无效" }
  private val client = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(25, TimeUnit.SECONDS)
    .callTimeout(60, TimeUnit.SECONDS)
    .build()
  private val streamClient = client.newBuilder()
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .callTimeout(0, TimeUnit.MILLISECONDS)
    .build()
  @Volatile private var protocol = ServerProtocol.UNKNOWN

  init {
    require(base.scheme == "https" || base.scheme == "http" && profile.allowCleartext) { "HTTP 明文连接未获授权，请在服务器资料中明确开启" }
    require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null) { "请使用不含凭据或参数的服务器地址" }
  }

  private fun isV2() = protocol == ServerProtocol.V2

  /**
   * 构造请求 URL。目录定位参数按代际不同：
   * V1 用 `?directory=`，V2 用 `?location[directory]=`（官方 SDK 对 `/api/` 前缀路由两者都写）。
   */
  private fun url(path: String, directory: String? = null, query: Map<String, String> = emptyMap()): HttpUrl {
    val builder = base.newBuilder().addPathSegments(path.trimStart('/'))
    if (!directory.isNullOrBlank()) {
      builder.addQueryParameter("directory", directory)
      if (isV2()) builder.addQueryParameter("location[directory]", directory)
    }
    query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
    return builder.build()
  }

  private fun requestBuilder(path: String, directory: String?, query: Map<String, String>): Request.Builder {
    val builder = Request.Builder().url(url(path, directory, query)).header("Accept", "application/json")
    if (credentials.password.isNotEmpty()) {
      builder.header("Authorization", Credentials.basic(credentials.username.ifBlank { profile.username.ifBlank { "opencode" } }, credentials.password))
    }
    if (credentials.cookie.isNotBlank()) builder.header("Cookie", credentials.cookie)
    return builder
  }

  private fun httpErrorMessage(status: Int, body: String): String {
    val serverMessage = runCatching {
      val json = JSONObject(body)
      json.errorMessage().ifBlank { json.obj("data").errorMessage() }
    }.getOrDefault("")
    return serverMessage.ifBlank {
      when (status) {
        401 -> "用户名或密码错误"
        403 -> "服务器拒绝访问"
        404 -> "OpenCode 未找到请求的资源或接口"
        else -> "服务器返回 HTTP $status"
      }
    }
  }

  private suspend fun request(method: String, path: String, directory: String? = null, query: Map<String, String> = emptyMap(), body: JSONObject? = null): String = withContext(Dispatchers.IO) {
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val payload = if (method == "GET" || method == "DELETE") null else (body?.toString() ?: "{}").toRequestBody(mediaType)
    val call = client.newCall(requestBuilder(path, directory, query).method(method, payload).build())
    try {
      call.execute().use { response ->
        val responseBody = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw ApiException(response.code, httpErrorMessage(response.code, responseBody), responseBody)
        responseBody
      }
    } finally {
      if (!kotlin.coroutines.coroutineContext.isActive) call.cancel()
    }
  }

  private suspend fun requestBytes(method: String, path: String, directory: String? = null, query: Map<String, String> = emptyMap()): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
    val call = client.newCall(requestBuilder(path, directory, query).method(method, null).build())
    try {
      call.execute().use { response ->
        val responseBody = response.body ?: error("服务器返回空响应")
        if (!response.isSuccessful) {
          val body = responseBody.string()
          throw ApiException(response.code, httpErrorMessage(response.code, body), body)
        }
        responseBody.bytes() to (response.header("Content-Type") ?: "application/octet-stream")
      }
    } finally {
      if (!kotlin.coroutines.coroutineContext.isActive) call.cancel()
    }
  }

  private suspend fun obj(path: String, directory: String? = null, query: Map<String, String> = emptyMap()): JSONObject = JSONObject(request("GET", path, directory, query))
  private suspend fun arr(path: String, directory: String? = null, query: Map<String, String> = emptyMap()): JSONArray = JSONArray(request("GET", path, directory, query))
  private fun dataArray(value: JSONObject): JSONArray = value.optJSONArray("data") ?: JSONArray()

  /**
   * 游标分页取全部对象。遵守契约约束：**跟随 cursor 时不再携带 `order` 等首页参数**，
   * 否则服务端行为未定义（重复/漏项）。返回 `{data, cursor:{next}}` 形状的所有页。
   */
  private suspend fun pagedObjects(path: String, query: Map<String, String>): List<JSONObject> {
    val result = mutableListOf<JSONObject>()
    val seen = mutableSetOf<String>()
    var cursor: String? = null
    do {
      val pageQuery: Map<String, String> = if (cursor == null) query
      else mapOf("cursor" to cursor!!)
      val page = obj(path, query = pageQuery)
      result += page.optJSONArray("data")?.objects().orEmpty()
      val next = page.obj("cursor").str("next").takeIf { it.isNotBlank() }
      cursor = next?.takeIf(seen::add)
    } while (cursor != null)
    return result
  }

  private fun segment(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
  private fun locationQuery(directory: String): Map<String, String> = mapOf("location[directory]" to directory)
  fun detectedProtocol(): ServerProtocol = protocol

  private suspend fun ensureProtocol(): ServerProtocol {
    if (protocol == ServerProtocol.UNKNOWN) health()
    return protocol
  }

  /* ============ 健康探测 ============ */
  suspend fun health(): String {
    if (protocol == ServerProtocol.V1) {
      val response = obj("global/health")
      if (!response.optBoolean("healthy")) throw IOException("OpenCode 服务未就绪")
      return response.str("version")
    }
    if (protocol == ServerProtocol.V2) {
      val response = obj("api/health")
      if (!response.optBoolean("healthy") && !response.has("pid")) throw IOException("OpenCode 服务未就绪")
      return "OpenCode V2"
    }
    try {
      val legacy = obj("global/health")
      if (!legacy.optBoolean("healthy")) throw IOException("OpenCode 服务未就绪")
      protocol = ServerProtocol.V1
      return legacy.str("version")
    } catch (error: ApiException) {
      if (error.status != 404) throw error
    }
    val current = obj("api/health")
    if (!current.optBoolean("healthy") && !current.has("pid")) throw IOException("OpenCode 服务未就绪")
    protocol = ServerProtocol.V2
    return "OpenCode V2"
  }

  /* ============ 项目 ============ */
  // V1: GET /project → Project[]；V2: GET /api/project → Project[]（**不是** /api/location）
  suspend fun projects(): List<Project> = when (ensureProtocol()) {
    ServerProtocol.V1 -> arr("project").objects().map(V1Contract::project).filter { it.directory.isNotBlank() }
    ServerProtocol.V2 -> arr("api/project").objects().map(V2Contract::project).filter { it.directory.isNotBlank() }
    ServerProtocol.UNKNOWN -> emptyList()
  }

  /* ============ 会话 ============ */
  // 归属由 projectID 关联；此处按目录拉取，调用方按 projectId 分组。
  suspend fun sessions(directory: String, projectId: String = ""): List<Session> = when (ensureProtocol()) {
    ServerProtocol.V1 -> arr("session", directory).objects().map(V1Contract::session)
    ServerProtocol.V2 -> {
      val query = if (projectId.isNotBlank()) mapOf("project" to projectId, "order" to "desc", "limit" to "200")
      else mapOf("directory" to directory, "order" to "desc", "limit" to "200")
      pagedObjects("api/session", query).map(V2Contract::session)
    }
    ServerProtocol.UNKNOWN -> emptyList()
  }

  suspend fun status(directory: String): Map<String, String> {
    return when (ensureProtocol()) {
      ServerProtocol.V1 -> {
        val json = obj("session/status", directory)
        json.keys().asSequence().associateWith { json.optJSONObject(it)?.str("type") ?: "idle" }
      }
      // V2: GET /api/session/active → { data: { sessionID: {type:"running"} } }
      ServerProtocol.V2 -> {
        val json = obj("api/session/active")
        val active = json.optJSONObject("data") ?: json
        active.keys().asSequence().associateWith { "running" }
      }
      ServerProtocol.UNKNOWN -> emptyMap()
    }
  }

  /* ============ 消息 ============ */
  // V1: GET /session/{id}/message → WithParts[]（{info,parts}）
  // V2: GET /api/session/{id}/message → {data: Session.Message.Info[], cursor}
  suspend fun messages(sessionId: String, directory: String): List<Message> = when (ensureProtocol()) {
    ServerProtocol.V1 -> arr("session/${segment(sessionId)}/message", directory).objects().map(V1Contract::message)
    ServerProtocol.V2 -> pagedObjects("api/session/${segment(sessionId)}/message", mapOf("order" to "asc", "limit" to "200"))
      .map(V2Contract::message).sortedBy { it.created }
    ServerProtocol.UNKNOWN -> emptyList()
  }

  /* ============ 会话写操作 ============ */
  suspend fun createSession(directory: String, title: String): Session = when (ensureProtocol()) {
    ServerProtocol.V1 -> JSONObject(request("POST", "session", directory, body = JSONObject().put("title", title))).let(V1Contract::session)
    ServerProtocol.V2 -> JSONObject(request("POST", "api/session", body = JSONObject()
      .put("title", title)
      .put("location", JSONObject().put("directory", directory)))).let(V2Contract::session)
    ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
  }

  suspend fun renameSession(session: Session, title: String) {
    when (ensureProtocol()) {
      ServerProtocol.V1 -> request("PATCH", "session/${segment(session.id)}", session.directory, body = JSONObject().put("title", title))
      ServerProtocol.V2 -> request("PATCH", "api/session/${segment(session.id)}", body = JSONObject().put("title", title))
      ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
    }
  }

  suspend fun deleteSession(session: Session) {
    when (ensureProtocol()) {
      ServerProtocol.V1 -> request("DELETE", "session/${segment(session.id)}", session.directory)
      ServerProtocol.V2 -> request("DELETE", "api/session/${segment(session.id)}")
      ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
    }
  }

  suspend fun forkSession(session: Session): Session = when (ensureProtocol()) {
    ServerProtocol.V1 -> JSONObject(request("POST", "session/${segment(session.id)}/fork", session.directory)).let(V1Contract::session)
    ServerProtocol.V2 -> JSONObject(request("POST", "api/session/${segment(session.id)}/fork")).let(V2Contract::session)
    ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
  }

  suspend fun abort(session: Session) {
    when (ensureProtocol()) {
      ServerProtocol.V1 -> request("POST", "session/${segment(session.id)}/abort", session.directory)
      ServerProtocol.V2 -> request("POST", "api/session/${segment(session.id)}/interrupt")
      ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
    }
  }

  suspend fun share(session: Session): String {
    if (ensureProtocol() == ServerProtocol.V2) unsupported("OpenCode V2 无分享接口")
    val response = JSONObject(request("POST", "session/${segment(session.id)}/share", session.directory))
    return response.obj("share").str("url").ifBlank { response.str("share") }.ifBlank { response.str("url") }
  }

  suspend fun unshare(session: Session) {
    if (ensureProtocol() == ServerProtocol.V2) unsupported("OpenCode V2 无取消分享接口")
    request("DELETE", "session/${segment(session.id)}/share", session.directory)
  }

  suspend fun summarize(session: Session, model: ModelChoice?) {
    when (ensureProtocol()) {
      ServerProtocol.V1 -> {
        val selected = model ?: error("请先选择模型")
        request("POST", "session/${segment(session.id)}/summarize", session.directory,
          body = JSONObject().put("providerID", selected.providerId).put("modelID", selected.modelId))
      }
      ServerProtocol.V2 -> request("POST", "api/session/${segment(session.id)}/compact")
      ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
    }
  }

  suspend fun revert(session: Session, messageId: String) {
    when (ensureProtocol()) {
      ServerProtocol.V1 -> request("POST", "session/${segment(session.id)}/revert", session.directory, body = JSONObject().put("messageID", messageId))
      ServerProtocol.V2 -> request("POST", "api/session/${segment(session.id)}/revert/stage", body = JSONObject().put("messageID", messageId))
      ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
    }
  }

  suspend fun unrevert(session: Session) {
    when (ensureProtocol()) {
      ServerProtocol.V1 -> request("POST", "session/${segment(session.id)}/unrevert", session.directory)
      ServerProtocol.V2 -> request("DELETE", "api/session/${segment(session.id)}/revert")
      ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
    }
  }

  /* ============ 发消息 ============ */
  // V2 契约：`text` 是**顶层必填**字段（不是 {"prompt":{"text"}}），schema additionalProperties:false。
  suspend fun send(session: Session, text: String, agent: String?, model: ModelChoice?) {
    when (ensureProtocol()) {
      ServerProtocol.V1 -> {
        val body = JSONObject().put("parts", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        if (!agent.isNullOrBlank()) body.put("agent", agent)
        if (model != null) body.put("model", JSONObject().put("providerID", model.providerId).put("modelID", model.modelId))
        request("POST", "session/${segment(session.id)}/prompt_async", session.directory, body = body)
      }
      ServerProtocol.V2 -> {
        if (!agent.isNullOrBlank()) request("POST", "api/session/${segment(session.id)}/agent", body = JSONObject().put("agent", agent))
        if (model != null) request("POST", "api/session/${segment(session.id)}/model", body = JSONObject().put("model", JSONObject().put("providerID", model.providerId).put("id", model.modelId)))
        request("POST", "api/session/${segment(session.id)}/prompt", body = JSONObject().put("text", text))
      }
      ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
    }
  }

  suspend fun command(session: Session, name: String, arguments: String, agent: String?, model: ModelChoice?) {
    when (ensureProtocol()) {
      ServerProtocol.V1 -> {
        val body = JSONObject().put("command", name).put("arguments", arguments)
        if (!agent.isNullOrBlank()) body.put("agent", agent)
        if (model != null) body.put("model", "${model.providerId}/${model.modelId}")
        request("POST", "session/${segment(session.id)}/command", session.directory, body = body)
      }
      ServerProtocol.V2 -> send(session, "/$name ${arguments.trim()}".trim(), agent, model)
      ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
    }
  }

  /* ============ Agent / Command / Model ============ */
  suspend fun agents(directory: String): List<AgentChoice> = when (ensureProtocol()) {
    ServerProtocol.V1 -> arr("agent", directory).objects().map { AgentChoice(it.str("name"), it.str("description")) }
    ServerProtocol.V2 -> dataArray(obj("api/agent", query = locationQuery(directory))).objects()
      .filterNot { it.optBoolean("hidden") }.map { AgentChoice(it.str("name").ifBlank { it.str("id") }, it.str("description")) }
    ServerProtocol.UNKNOWN -> emptyList()
  }

  suspend fun commands(directory: String): List<CommandChoice> = when (ensureProtocol()) {
    ServerProtocol.V1 -> arr("command", directory).objects().map { CommandChoice(it.str("name"), it.str("description")) }
    ServerProtocol.V2 -> dataArray(obj("api/command", query = locationQuery(directory))).objects().map { CommandChoice(it.str("name"), it.str("description")) }
    ServerProtocol.UNKNOWN -> emptyList()
  }

  suspend fun models(directory: String): List<ModelChoice> {
    return when (ensureProtocol()) {
      ServerProtocol.V1 -> {
        val providers = obj("config/providers", directory).arr("providers").objects()
        providers.flatMap { provider ->
          val providerId = provider.str("id")
          val models = provider.obj("models")
          models.keys().asSequence().map { key ->
            val model = models.optJSONObject(key) ?: JSONObject()
            ModelChoice(providerId, key, model.str("name").ifBlank { key })
          }.toList()
        }
      }
      ServerProtocol.V2 -> dataArray(obj("api/model", query = locationQuery(directory))).objects().map {
        ModelChoice(it.str("providerID"), it.str("id"), it.str("name").ifBlank { it.str("id") })
      }
      ServerProtocol.UNKNOWN -> emptyList()
    }
  }

  /* ============ 权限 / 问题 ============ */
  // V1 无权限/问题的 REST 列表端点（官方 SDK 端点表已核对）——仅事件推送，故列表返回空、实时靠 SSE。
  // V2: GET /api/permission/request；问题在 V2 已改为 `form`（/api/form）。
  suspend fun permissions(directory: String): List<PermissionRequest> = try {
    when (ensureProtocol()) {
      ServerProtocol.V1 -> emptyList()
      ServerProtocol.V2 -> dataArray(obj("api/permission/request", query = locationQuery(directory))).objects().map { V2Contract.permission(it, directory) }
      ServerProtocol.UNKNOWN -> emptyList()
    }
  } catch (e: ApiException) { if (e.status == 404) emptyList() else throw e }

  suspend fun questions(directory: String): List<QuestionRequest> = try {
    when (ensureProtocol()) {
      ServerProtocol.V1 -> emptyList()   // V1 无 REST 列表端点，实时靠 SSE `question.asked`
      ServerProtocol.V2 -> dataArray(obj("api/form", query = locationQuery(directory))).objects().map { formToQuestion(it, directory) }
      ServerProtocol.UNKNOWN -> emptyList()
    }
  } catch (e: ApiException) { if (e.status == 404) emptyList() else throw e }

  // V2 `Form.Info` → 统一 QuestionRequest（form 是 V2 的问答形态）
  private fun formToQuestion(json: JSONObject, directory: String): QuestionRequest {
    val fields = json.obj("fields").optJSONObject("fields") ?: json.obj("fields")
    val options = fields.arr("options").objects().map { QuestionOption(it.str("label"), it.str("description")) }
    return QuestionRequest(
      id = json.str("id"), sessionId = json.str("sessionID"), directory = directory,
      questions = listOf(QuestionPrompt(
        title = json.str("title").ifBlank { fields.str("label") },
        options = options,
        multiple = fields.optBoolean("multiple"),
        header = json.str("title"),
        custom = fields.str("type") == "string"
      ))
    )
  }

  // V1: POST /session/{id}/permissions/{permissionID}，body {reply, message?}（PermissionV1.ReplyBody）
  // V2: POST /api/session/{id}/permission/{requestID}/reply，body {decision}（Permission.Reply）
  suspend fun replyPermission(request: PermissionRequest, reply: String) {
    when (ensureProtocol()) {
      ServerProtocol.V1 -> request("POST", "session/${segment(request.sessionId)}/permissions/${segment(request.id)}", request.directory,
        body = JSONObject().put("reply", normalizeReply(reply)))
      ServerProtocol.V2 -> request("POST", "api/session/${segment(request.sessionId)}/permission/${segment(request.id)}/reply",
        body = JSONObject().put("decision", normalizeReply(reply)))
      ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
    }
  }

  // 契约枚举：once | always | reject
  private fun normalizeReply(reply: String): String = when (reply.lowercase()) {
    "allow", "once" -> "once"
    "always" -> "always"
    else -> "reject"
  }

  suspend fun replyQuestion(request: QuestionRequest, answers: List<List<String>>) {
    val array = JSONArray()
    answers.forEach { row -> array.put(JSONArray(row)) }
    when (ensureProtocol()) {
      ServerProtocol.V1 -> request("POST", "question/${segment(request.id)}/reply", request.directory, body = JSONObject().put("answers", array))
      ServerProtocol.V2 -> request("POST", "api/session/${segment(request.sessionId)}/form/${segment(request.id)}/reply",
        body = JSONObject().put("answers", array))
      ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
    }
  }

  suspend fun rejectQuestion(request: QuestionRequest) {
    when (ensureProtocol()) {
      ServerProtocol.V1 -> request("POST", "question/${segment(request.id)}/reject", request.directory)
      ServerProtocol.V2 -> request("DELETE", "api/session/${segment(request.sessionId)}/form/${segment(request.id)}")
      ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
    }
  }

  /* ============ 附属数据 ============ */
  suspend fun todos(session: Session): List<TodoItem> = when (ensureProtocol()) {
    ServerProtocol.V1 -> arr("session/${segment(session.id)}/todo", session.directory).objects().map(V1Contract::todo)
    ServerProtocol.V2 -> emptyList()   // V2 todo 走消息内 task 状态，无独立端点
    ServerProtocol.UNKNOWN -> emptyList()
  }

  suspend fun children(session: Session): List<Session> = when (ensureProtocol()) {
    ServerProtocol.V1 -> arr("session/${segment(session.id)}/children", session.directory).objects().map(V1Contract::session)
    ServerProtocol.V2 -> sessions(session.directory, session.projectId).filter { it.parentId == session.id }
    ServerProtocol.UNKNOWN -> emptyList()
  }

  suspend fun diff(session: Session): List<FileChange> = when (ensureProtocol()) {
    ServerProtocol.V1 -> arr("session/${segment(session.id)}/diff", session.directory).objects().map(V1Contract::change)
    ServerProtocol.V2 -> dataArray(obj("api/session/${segment(session.id)}/diff")).objects().map(V1Contract::change)
    ServerProtocol.UNKNOWN -> emptyList()
  }

  suspend fun files(directory: String, path: String): List<FileNode> = when (ensureProtocol()) {
    ServerProtocol.V1 -> arr("file", directory, mapOf("path" to path)).objects().map(V1Contract::node)
    ServerProtocol.V2 -> dataArray(obj("api/fs/list", query = locationQuery(directory) + (if (path.isNotBlank()) mapOf("path" to path) else emptyMap())))
      .objects().map { item -> FileNode(item.str("path"), item.str("type"), item.str("path").substringAfterLast('/'), "", false) }
    ServerProtocol.UNKNOWN -> emptyList()
  }

  suspend fun fileContent(directory: String, path: String): FileContent = when (ensureProtocol()) {
    ServerProtocol.V1 -> obj("file/content", directory, mapOf("path" to path)).let(V1Contract::fileContent)
    ServerProtocol.V2 -> {
      val (bytes, contentType) = requestBytes("GET", "api/fs/read/${path.trimStart('/')}", query = locationQuery(directory))
      val binary = !isTextFile(path, contentType)
      if (binary) FileContent("binary", Base64.encodeToString(bytes, Base64.NO_WRAP), "base64", contentType)
      else FileContent("text", bytes.toString(Charsets.UTF_8), mimeType = contentType)
    }
    ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
  }

  suspend fun searchFiles(directory: String, query: String): List<String> {
    return when (ensureProtocol()) {
      ServerProtocol.V1 -> {
        val values = arr("find/file", directory, mapOf("query" to query))
        (0 until values.length()).mapNotNull { values.optString(it).takeIf(String::isNotBlank) }
      }
      ServerProtocol.V2 -> dataArray(obj("api/fs/find", query = locationQuery(directory) + mapOf("query" to query))).objects()
        .mapNotNull { it.str("path").takeIf(String::isNotBlank) }
      ServerProtocol.UNKNOWN -> emptyList()
    }
  }

  /* ============ SSE 事件 ============ */
  // V1: GET /global/event；V2: GET /api/event。事件信封两代不同，统一解析为 ServerEvent。
  fun events(lastEventId: String? = null): Flow<ServerEvent> = callbackFlow {
    val path = if (isV2()) "api/event" else "global/event"
    val builder = requestBuilder(path, null, emptyMap()).header("Accept", "text/event-stream")
    if (!lastEventId.isNullOrBlank()) builder.header("Last-Event-ID", lastEventId)
    val request = builder.build()
    val source: EventSource = EventSources.createFactory(streamClient).newEventSource(request, object : EventSourceListener() {
      override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
        try {
          trySend(data.toServerEvent(id.orEmpty()))
        } catch (_: Exception) { /* Ignore malformed event; next event or refresh repairs state. */ }
      }
      override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
        close(t ?: IOException("SSE 已断开：HTTP ${response?.code ?: 0}"))
      }
      override fun onClosed(eventSource: EventSource) { close() }
    })
    awaitClose { source.cancel() }
  }

  private fun unsupported(message: String): Nothing = throw ApiException(501, message)

  private fun isTextFile(path: String, contentType: String): Boolean {
    val mime = contentType.substringBefore(';').trim().lowercase()
    if (mime.startsWith("text/") || mime in setOf("application/json", "application/xml", "application/javascript", "application/x-javascript")) return true
    val name = path.substringAfterLast('/').lowercase()
    if (name in setOf("dockerfile", "makefile", "license", "readme", "changelog", ".gitignore", ".gitattributes", ".env")) return true
    val extension = name.substringAfterLast('.', "")
    if (extension in setOf(
        "txt", "md", "markdown", "rst", "json", "jsonc", "yaml", "yml", "toml", "xml", "html", "htm", "css", "scss", "less",
        "js", "jsx", "ts", "tsx", "vue", "svelte", "kt", "kts", "java", "groovy", "gradle", "py", "rb", "go", "rs", "c", "cc",
        "cpp", "h", "hh", "hpp", "sh", "bash", "zsh", "sql", "swift", "php", "pl", "ini", "cfg", "conf", "properties",
        "env", "gitignore", "gitattributes", "bat", "cmd", "ps1", "tf", "hcl", "proto", "graphql", "lock", "svg", "tex", "diff", "patch"
      )) return true
    return false
  }
}

/**
 * 解析 SSE 事件信封为统一 [ServerEvent]。
 *
 * V1 `GET /global/event` 信封：`{directory, workspace, payload:{type, properties}}`。
 * V2 `GET /api/event` 信封：`{id, event, data}`，`data` 为 JSON 编码的事件对象。
 * 两代的 `permission.*`/`question.*` 变体（`permission.v2.*`、`form.*`）归一到统一事件名，
 * 以便 [TaskReducer] 与状态层统一处理。
 */
internal fun String.toServerEvent(sseId: String): ServerEvent {
  val json = JSONObject(this)
  // V1 信封
  val payload = json.optJSONObject("payload")
  if (payload != null) return ServerEvent(sseId, json.str("directory"), payload.str("type"), payload.obj("properties"))

  // V2 信封：data 字段是 JSON 编码的事件体
  val data = when {
    json.has("data") && json.opt("data") is JSONObject -> json.obj("data")
    json.has("data") && json.str("data").isNotBlank() -> runCatching { JSONObject(json.str("data")) }.getOrElse { json }
    json.has("type") -> json.optJSONObject("data") ?: json.obj("properties")
    else -> json.obj("properties")
  }
  val rawType = json.str("type").ifBlank { data.str("type") }
  val properties = when (rawType) {
    "permission.v2.asked" -> JSONObject().apply {
      put("id", data.str("id")); put("sessionID", data.str("sessionID")); put("permission", data.str("action"))
      put("patterns", data.arr("resources")); put("always", data.arr("save")); put("metadata", data.obj("metadata"))
      val source = data.obj("source")
      if (source.str("type") == "tool") put("tool", JSONObject().put("messageID", source.str("messageID")).put("callID", source.str("id")))
    }
    else -> data
  }
  val normalizedType = when (rawType) {
    "permission.v2.asked" -> "permission.asked"
    "permission.v2.replied" -> "permission.replied"
    "question.v2.asked" -> "question.asked"
    "question.v2.replied" -> "question.replied"
    "question.v2.rejected" -> "question.rejected"
    else -> rawType
  }
  val directory = json.obj("location").str("directory").ifBlank { json.str("directory") }
  return ServerEvent(json.str("id").ifBlank { sseId }, directory, normalizedType, properties)
}
