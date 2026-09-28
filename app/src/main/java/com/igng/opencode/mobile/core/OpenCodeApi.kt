package com.igng.opencode.mobile.core

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

data class ServerEvent(val directory: String, val type: String, val properties: JSONObject)
class ApiException(val status: Int, message: String) : IOException(message)

class OpenCodeApi(private val profile: ServerProfile, private val password: String) {
  private val base: HttpUrl = requireNotNull(profile.url.trimEnd('/').toHttpUrlOrNull()) { "服务器地址无效" }
  private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build()
  private val streamClient = client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

  init {
    require(base.scheme == "https" || base.scheme == "http") { "仅支持 HTTP 和 HTTPS" }
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
    if (password.isNotEmpty()) builder.header("Authorization", Credentials.basic(profile.username.ifBlank { "opencode" }, password))
    return builder
  }

  private suspend fun request(method: String, path: String, directory: String? = null, query: Map<String, String> = emptyMap(), body: JSONObject? = null): String = withContext(Dispatchers.IO) {
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val payload = if (method == "GET" || method == "DELETE") null else (body?.toString() ?: "{}").toRequestBody(mediaType)
    val call = client.newCall(requestBuilder(path, directory, query).method(method, payload).build())
    try {
      call.execute().use { response ->
        if (!response.isSuccessful) throw ApiException(response.code, when (response.code) {
          401 -> "用户名或密码错误"
          403 -> "服务器拒绝访问"
          404 -> "当前 OpenCode 版本不支持此接口"
          else -> "服务器返回 HTTP ${response.code}"
        })
        response.body?.string().orEmpty()
      }
    } finally {
      if (!kotlin.coroutines.coroutineContext.isActive) call.cancel()
    }
  }

  private suspend fun obj(path: String, directory: String? = null, query: Map<String, String> = emptyMap()): JSONObject = JSONObject(request("GET", path, directory, query))
  private suspend fun arr(path: String, directory: String? = null, query: Map<String, String> = emptyMap()): JSONArray = JSONArray(request("GET", path, directory, query))
  private fun segment(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

  suspend fun health(): String {
    val response = obj("global/health")
    if (!response.optBoolean("healthy")) throw IOException("OpenCode 服务未就绪")
    return response.str("version")
  }
  suspend fun projects(): List<Project> = arr("project").objects().map { it.toProject() }.filter { it.directory.isNotBlank() }
  suspend fun sessions(directory: String): List<Session> = arr("session", directory).objects().map { it.toSession() }
  suspend fun status(directory: String): Map<String, String> {
    val json = obj("session/status", directory)
    return json.keys().asSequence().associateWith { json.optJSONObject(it)?.str("type") ?: "idle" }
  }
  suspend fun messages(sessionId: String, directory: String): List<Message> = arr("session/${segment(sessionId)}/message", directory).objects().map { it.toMessage() }
  suspend fun createSession(directory: String, title: String): Session = JSONObject(request("POST", "session", directory, body = JSONObject().put("title", title))).toSession()
  suspend fun renameSession(session: Session, title: String) { request("PATCH", "session/${segment(session.id)}", session.directory, body = JSONObject().put("title", title)) }
  suspend fun deleteSession(session: Session) { request("DELETE", "session/${segment(session.id)}", session.directory) }
  suspend fun forkSession(session: Session): Session = JSONObject(request("POST", "session/${segment(session.id)}/fork", session.directory)).toSession()
  suspend fun abort(session: Session) { request("POST", "session/${segment(session.id)}/abort", session.directory) }
  suspend fun share(session: Session): String = JSONObject(request("POST", "session/${segment(session.id)}/share", session.directory)).str("share")
  suspend fun unshare(session: Session) { request("DELETE", "session/${segment(session.id)}/share", session.directory) }
  suspend fun summarize(session: Session, model: ModelChoice) { request("POST", "session/${segment(session.id)}/summarize", session.directory, body = JSONObject().put("providerID", model.providerId).put("modelID", model.modelId)) }
  suspend fun revert(session: Session, messageId: String) { request("POST", "session/${segment(session.id)}/revert", session.directory, body = JSONObject().put("messageID", messageId)) }
  suspend fun unrevert(session: Session) { request("POST", "session/${segment(session.id)}/unrevert", session.directory) }
  suspend fun send(session: Session, text: String, agent: String?, model: ModelChoice?) {
    val body = JSONObject().put("parts", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
    if (!agent.isNullOrBlank()) body.put("agent", agent)
    if (model != null) body.put("model", JSONObject().put("providerID", model.providerId).put("modelID", model.modelId))
    request("POST", "session/${segment(session.id)}/prompt_async", session.directory, body = body)
  }
  suspend fun command(session: Session, name: String, arguments: String, agent: String?, model: ModelChoice?) {
    val body = JSONObject().put("command", name).put("arguments", arguments)
    if (!agent.isNullOrBlank()) body.put("agent", agent)
    if (model != null) body.put("model", "${model.providerId}/${model.modelId}")
    request("POST", "session/${segment(session.id)}/command", session.directory, body = body)
  }
  suspend fun agents(directory: String): List<AgentChoice> = arr("agent", directory).objects().map { AgentChoice(it.str("name"), it.str("description")) }
  suspend fun commands(directory: String): List<CommandChoice> = arr("command", directory).objects().map { CommandChoice(it.str("name"), it.str("description")) }
  suspend fun models(directory: String): List<ModelChoice> {
    val providers = obj("config/providers", directory).arr("providers").objects()
    return providers.flatMap { provider ->
      val providerId = provider.str("id")
      val models = provider.obj("models")
      models.keys().asSequence().map { key ->
        val model = models.optJSONObject(key) ?: JSONObject()
        ModelChoice(providerId, key, model.str("name").ifBlank { key })
      }.toList()
    }
  }
  suspend fun permissions(directory: String): List<PermissionRequest> = try {
    arr("permission", directory).objects().map { it.toPermission(directory) }
  } catch (e: ApiException) { if (e.status == 404) emptyList() else throw e }
  suspend fun questions(directory: String): List<QuestionRequest> = try {
    arr("question", directory).objects().map { it.toQuestion(directory) }
  } catch (e: ApiException) { if (e.status == 404) emptyList() else throw e }
  suspend fun replyPermission(request: PermissionRequest, reply: String) {
    try {
      request("POST", "permission/${segment(request.id)}/reply", request.directory, body = JSONObject().put("reply", reply))
    } catch (e: ApiException) {
      if (e.status != 404) throw e
      request("POST", "session/${segment(request.sessionId)}/permissions/${segment(request.id)}", request.directory,
        body = JSONObject().put("response", reply).put("remember", reply == "always"))
    }
  }
  suspend fun replyQuestion(request: QuestionRequest, answers: List<List<String>>) {
    val array = JSONArray()
    answers.forEach { row -> array.put(JSONArray(row)) }
    request("POST", "question/${segment(request.id)}/reply", request.directory, body = JSONObject().put("answers", array))
  }
  suspend fun rejectQuestion(request: QuestionRequest) { request("POST", "question/${segment(request.id)}/reject", request.directory) }
  suspend fun todos(session: Session): List<TodoItem> = arr("session/${segment(session.id)}/todo", session.directory).objects().map { it.toTodo() }
  suspend fun children(session: Session): List<Session> = arr("session/${segment(session.id)}/children", session.directory).objects().map { it.toSession() }
  suspend fun diff(session: Session): List<FileChange> = arr("session/${segment(session.id)}/diff", session.directory).objects().map { it.toChange() }
  suspend fun files(directory: String, path: String): List<FileNode> = arr("file", directory, mapOf("path" to path)).objects().map { it.toNode() }
  suspend fun fileContent(directory: String, path: String): String = obj("file/content", directory, mapOf("path" to path)).str("content")
  suspend fun searchFiles(directory: String, query: String): List<String> {
    val values = arr("find/file", directory, mapOf("query" to query))
    return (0 until values.length()).mapNotNull { values.optString(it).takeIf(String::isNotBlank) }
  }

  fun events(): Flow<ServerEvent> = callbackFlow {
    val request = requestBuilder("global/event", null, emptyMap()).header("Accept", "text/event-stream").build()
    val source: EventSource = EventSources.createFactory(streamClient).newEventSource(request, object : EventSourceListener() {
      override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
        try {
          val json = JSONObject(data)
          val payload = json.optJSONObject("payload") ?: json
          trySend(ServerEvent(json.str("directory"), payload.str("type"), payload.obj("properties")))
        } catch (_: Exception) { /* Ignore malformed event; next event or refresh repairs state. */ }
      }
      override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
        close(t ?: IOException("SSE 已断开：HTTP ${response?.code ?: 0}"))
      }
      override fun onClosed(eventSource: EventSource) { close() }
    })
    awaitClose { source.cancel() }
  }
}
