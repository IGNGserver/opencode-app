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
enum class ServerProtocol { UNKNOWN, V1, V2 }

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

  private fun url(path: String, directory: String? = null, query: Map<String, String> = emptyMap()): HttpUrl {
    val builder = base.newBuilder().addPathSegments(path.trimStart('/'))
    if (!directory.isNullOrBlank()) builder.addQueryParameter("directory", directory)
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
  private fun dataObject(value: JSONObject): JSONObject = value.optJSONObject("data") ?: value
  private fun dataArray(value: JSONObject): JSONArray = value.optJSONArray("data") ?: JSONArray()
  private suspend fun dataObjects(
    path: String,
    query: Map<String, String> = emptyMap(),
    dropAfterFirst: Set<String> = emptySet()
  ): List<JSONObject> {
    val result = mutableListOf<JSONObject>()
    val seen = mutableSetOf<String>()
    var cursor: String? = null
    do {
      val pageQuery = query.toMutableMap().apply {
        if (cursor != null) {
          put("cursor", cursor!!)
          dropAfterFirst.forEach(::remove)
        }
      }
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
  suspend fun projects(): List<Project> = when (ensureProtocol()) {
    ServerProtocol.V1 -> arr("project").objects().map { it.toProject() }.filter { it.directory.isNotBlank() }
    ServerProtocol.V2 -> {
      val location = dataObject(obj("api/location"))
      val directory = location.str("directory")
      if (directory.isBlank()) emptyList()
      else listOf(Project(location.obj("project").str("id").ifBlank { directory }, directory, directory.substringAfterLast('/')))
    }
    ServerProtocol.UNKNOWN -> emptyList()
  }
  suspend fun sessions(directory: String): List<Session> = when (ensureProtocol()) {
    ServerProtocol.V1 -> arr("session", directory).objects().map { it.toSession() }
    ServerProtocol.V2 -> dataObjects("api/session", query = mapOf("directory" to directory, "order" to "desc")).map { it.toSession() }
    ServerProtocol.UNKNOWN -> emptyList()
  }
  suspend fun status(directory: String): Map<String, String> {
    return when (ensureProtocol()) {
      ServerProtocol.V1 -> {
        val json = obj("session/status", directory)
        json.keys().asSequence().associateWith { json.optJSONObject(it)?.str("type") ?: "idle" }
      }
      ServerProtocol.V2 -> dataObject(obj("api/session/active")).keys().asSequence().associateWith { "running" }
      ServerProtocol.UNKNOWN -> emptyMap()
    }
  }
  suspend fun messages(sessionId: String, directory: String): List<Message> = when (ensureProtocol()) {
    ServerProtocol.V1 -> arr("session/${segment(sessionId)}/message", directory).objects().map { it.toMessage() }
    ServerProtocol.V2 -> dataObjects("api/session/${segment(sessionId)}/message", query = mapOf("order" to "asc"), dropAfterFirst = setOf("order")).map { it.toMessage() }
    ServerProtocol.UNKNOWN -> emptyList()
  }
  suspend fun createSession(directory: String, title: String): Session = when (ensureProtocol()) {
    ServerProtocol.V1 -> JSONObject(request("POST", "session", directory, body = JSONObject().put("title", title))).toSession()
    ServerProtocol.V2 -> dataObject(requestObject("POST", "api/session", body = JSONObject().put("location", JSONObject().put("directory", directory)))).toSession()
    ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
  }
  suspend fun renameSession(session: Session, title: String) {
    when (ensureProtocol()) {
      ServerProtocol.V1 -> request("PATCH", "session/${segment(session.id)}", session.directory, body = JSONObject().put("title", title))
      ServerProtocol.V2 -> unsupported("OpenCode V2 当前没有会话重命名接口")
      ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
    }
  }
  suspend fun deleteSession(session: Session) {
    when (ensureProtocol()) {
      ServerProtocol.V1 -> request("DELETE", "session/${segment(session.id)}", session.directory)
      ServerProtocol.V2 -> unsupported("OpenCode V2 当前没有会话删除接口")
      ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
    }
  }
  suspend fun forkSession(session: Session): Session = when (ensureProtocol()) {
    ServerProtocol.V1 -> JSONObject(request("POST", "session/${segment(session.id)}/fork", session.directory)).toSession()
    ServerProtocol.V2 -> unsupported("OpenCode V2 当前没有会话 Fork 接口")
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
    if (ensureProtocol() == ServerProtocol.V2) unsupported("OpenCode V2 当前没有分享接口")
    val response = JSONObject(request("POST", "session/${segment(session.id)}/share", session.directory))
    return response.obj("share").str("url").ifBlank { response.str("share") }.ifBlank { response.str("url") }
  }
  suspend fun unshare(session: Session) {
    if (ensureProtocol() == ServerProtocol.V2) unsupported("OpenCode V2 当前没有取消分享接口")
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
      ServerProtocol.V2 -> request("POST", "api/session/${segment(session.id)}/revert/clear")
      ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
    }
  }
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
        request("POST", "api/session/${segment(session.id)}/prompt", body = JSONObject().put("prompt", JSONObject().put("text", text)))
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
  suspend fun agents(directory: String): List<AgentChoice> = when (ensureProtocol()) {
    ServerProtocol.V1 -> arr("agent", directory).objects().map { AgentChoice(it.str("name"), it.str("description")) }
    ServerProtocol.V2 -> dataArray(obj("api/agent", query = locationQuery(directory))).objects().filterNot { it.optBoolean("hidden") }.map { AgentChoice(it.str("id"), it.str("description")) }
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
  suspend fun permissions(directory: String): List<PermissionRequest> = try {
    when (ensureProtocol()) {
      ServerProtocol.V1 -> arr("permission", directory).objects().map { it.toPermission(directory) }
      ServerProtocol.V2 -> dataArray(obj("api/permission/request", query = locationQuery(directory))).objects().map { it.toPermission(directory) }
      ServerProtocol.UNKNOWN -> emptyList()
    }
  } catch (e: ApiException) { if (e.status == 404) emptyList() else throw e }
  suspend fun questions(directory: String): List<QuestionRequest> = try {
    when (ensureProtocol()) {
      ServerProtocol.V1 -> arr("question", directory).objects().map { it.toQuestion(directory) }
      ServerProtocol.V2 -> dataArray(obj("api/question/request", query = locationQuery(directory))).objects().map { it.toQuestion(directory) }
      ServerProtocol.UNKNOWN -> emptyList()
    }
  } catch (e: ApiException) { if (e.status == 404) emptyList() else throw e }
  suspend fun replyPermission(request: PermissionRequest, reply: String) {
    when (ensureProtocol()) {
      ServerProtocol.V1 -> try {
        request("POST", "permission/${segment(request.id)}/reply", request.directory, body = JSONObject().put("reply", reply))
      } catch (e: ApiException) {
        if (e.status != 404) throw e
        request("POST", "session/${segment(request.sessionId)}/permissions/${segment(request.id)}", request.directory,
          body = JSONObject().put("response", reply).put("remember", reply == "always"))
      }
      ServerProtocol.V2 -> request("POST", "api/session/${segment(request.sessionId)}/permission/${segment(request.id)}/reply",
        body = JSONObject().put("reply", reply.lowercase().let { if (it == "allow") "once" else it }))
      ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
    }
  }
  suspend fun replyQuestion(request: QuestionRequest, answers: List<List<String>>) {
    val array = JSONArray()
    answers.forEach { row -> array.put(JSONArray(row)) }
    when (ensureProtocol()) {
      ServerProtocol.V1 -> request("POST", "question/${segment(request.id)}/reply", request.directory, body = JSONObject().put("answers", array))
      ServerProtocol.V2 -> request("POST", "api/session/${segment(request.sessionId)}/question/${segment(request.id)}/reply",
        body = JSONObject().put("answers", array))
      ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
    }
  }
  suspend fun rejectQuestion(request: QuestionRequest) {
    when (ensureProtocol()) {
      ServerProtocol.V1 -> request("POST", "question/${segment(request.id)}/reject", request.directory)
      ServerProtocol.V2 -> request("POST", "api/session/${segment(request.sessionId)}/question/${segment(request.id)}/reject")
      ServerProtocol.UNKNOWN -> error("OpenCode 协议未检测")
    }
  }
  suspend fun todos(session: Session): List<TodoItem> = when (ensureProtocol()) {
    ServerProtocol.V1 -> arr("session/${segment(session.id)}/todo", session.directory).objects().map { it.toTodo() }
    ServerProtocol.V2 -> emptyList()
    ServerProtocol.UNKNOWN -> emptyList()
  }
  suspend fun children(session: Session): List<Session> = when (ensureProtocol()) {
    ServerProtocol.V1 -> arr("session/${segment(session.id)}/children", session.directory).objects().map { it.toSession() }
    ServerProtocol.V2 -> sessions(session.directory).filter { it.parentId == session.id }
    ServerProtocol.UNKNOWN -> emptyList()
  }
  suspend fun diff(session: Session): List<FileChange> = when (ensureProtocol()) {
    ServerProtocol.V1 -> arr("session/${segment(session.id)}/diff", session.directory).objects().map { it.toChange() }
    ServerProtocol.V2 -> emptyList()
    ServerProtocol.UNKNOWN -> emptyList()
  }
  suspend fun files(directory: String, path: String): List<FileNode> = when (ensureProtocol()) {
    ServerProtocol.V1 -> arr("file", directory, mapOf("path" to path)).objects().map { it.toNode() }
    ServerProtocol.V2 -> dataArray(obj("api/fs/list", query = locationQuery(directory) + (if (path.isNotBlank()) mapOf("path" to path) else emptyMap())))
      .objects().map { item -> FileNode(item.str("path"), item.str("type"), item.str("path").substringAfterLast('/'), "", false) }
    ServerProtocol.UNKNOWN -> emptyList()
  }
  suspend fun fileContent(directory: String, path: String): FileContent = when (ensureProtocol()) {
    ServerProtocol.V1 -> obj("file/content", directory, mapOf("path" to path)).toFileContent()
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

  fun events(lastEventId: String? = null): Flow<ServerEvent> = callbackFlow {
    val path = if (protocol == ServerProtocol.V1) "global/event" else "api/event"
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

  private suspend fun requestObject(method: String, path: String, body: JSONObject): JSONObject = JSONObject(request(method, path, body = body))

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

internal fun String.toServerEvent(sseId: String): ServerEvent {
  val json = JSONObject(this)
  val payload = json.optJSONObject("payload")
  if (payload != null) return ServerEvent(sseId, json.str("directory"), payload.str("type"), payload.obj("properties"))
  val type = json.str("type")
  val data = json.optJSONObject("data") ?: json.obj("properties")
  val properties = when (type) {
    "permission.v2.asked" -> JSONObject().apply {
      put("id", data.str("id")); put("sessionID", data.str("sessionID")); put("permission", data.str("action"))
      put("patterns", data.arr("resources")); put("always", data.arr("save")); put("metadata", data.obj("metadata"))
      val source = data.obj("source")
      if (source.str("type") == "tool") put("tool", JSONObject().put("messageID", source.str("messageID")).put("callID", source.str("callID")))
    }
    else -> data
  }
  val normalizedType = when (type) {
    "permission.v2.asked" -> "permission.asked"
    "permission.v2.replied" -> "permission.replied"
    "question.v2.asked" -> "question.asked"
    "question.v2.replied" -> "question.replied"
    "question.v2.rejected" -> "question.rejected"
    else -> type
  }
  val directory = json.obj("location").str("directory").ifBlank { json.str("directory") }
  return ServerEvent(json.str("id").ifBlank { sseId }, directory, normalizedType, properties)
}
