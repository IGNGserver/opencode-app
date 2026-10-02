package com.igng.opencode.lagoon.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ServerStore internal constructor(private val preferences: SharedPreferences, private val secrets: SharedPreferences,
  private val encryptValue: (String) -> String, private val decryptValue: (String) -> String) {
  constructor(context: Context) : this(context.getSharedPreferences("servers", Context.MODE_PRIVATE),
    context.getSharedPreferences("secrets", Context.MODE_PRIVATE),
    KeystoreCipher("opencode-lagoon-server-passwords")::encrypt, KeystoreCipher("opencode-lagoon-server-passwords")::decrypt)

  fun profiles(): List<ServerProfile> = try {
    JSONArray(preferences.getString("profiles", "[]")).objects().map {
      val id = it.str("id")
      ServerProfile(id, it.str("name"), it.str("url"), it.str("username"),
        it.optBoolean("autoConnect", true), it.optBoolean("notifications", true), it.str("companionUrl"), it.optBoolean("allowCleartext", false),
        pluginSecret(id), it.optBoolean("islandHonor", false), it.optBoolean("islandOppoFluidCloud", false))
    }
  } catch (error: Exception) {
    Diagnostics.warn("ServerStore", "profiles 解析失败，已忽略全部服务器资料", error)
    emptyList()
  }

  /** Versioned encrypted value; failed decryption never becomes a new plaintext key. */
  fun pluginSecret(id: String): String {
    val stored = preferences.getString("pluginSecret:$id", "").orEmpty()
    if (stored.isBlank()) return ""
    val encrypted = stored.removePrefix("enc:v1:")
    val value = runCatching { decryptValue(encrypted) }.getOrNull() ?: return ""
    if (!stored.startsWith("enc:v1:")) preferences.edit().putString("pluginSecret:$id", "enc:v1:$encrypted").apply()
    return value
  }

  /** Persist acceptance before displaying; sequence is scoped to the server profile and session. */
  fun acceptPush(id: String, session: String, sequence: Long, timestamp: Long): Boolean = synchronized(pushLock) {
    val name = "pushSeen:$id:$session"
    if (sequence <= preferences.getString(name, "0:0").orEmpty().substringBefore(':').toLongOrNull().let { it ?: 0L }) return false
    val editor = preferences.edit().putString(name, "$sequence:$timestamp")
    val cutoff = System.currentTimeMillis() - 86_400_000L
    preferences.all.filter { (k, v) -> k.startsWith("pushSeen:") && k != name && (v as? String)?.substringAfter(':')?.toLongOrNull()?.let { it < cutoff } == true }
      .keys.forEach(editor::remove)
    editor.commit()
  }
  private companion object {
    val pushLock = Any()
    private val TERMINAL_PUSH_PHASES = setOf("COMPLETED", "FAILED")
  }

  /**
   * Records the latest phase seen for a session from a background push, so the island summary can be
   * rebuilt after the App process is killed. A fresh terminal state clears its read flag so the new
   * result counts as unread again. Terminal phases expire after the same window as pushes.
   */
  fun recordPushPhase(id: String, session: String, phase: String, timestamp: Long): Boolean = synchronized(pushLock) {
    val name = "pushPhase:$id:$session"
    val previous = preferences.getString(name, "").orEmpty().substringBeforeLast(':')
    val editor = preferences.edit().putString(name, "$phase:$timestamp")
    if (phase in TERMINAL_PUSH_PHASES && previous !in TERMINAL_PUSH_PHASES) editor.remove("taskRead:$id:$session")
    val cutoff = timestamp - 86_400_000L
    preferences.all.filter { (k, v) -> k.startsWith("pushPhase:$id:") && k != name &&
      (v as? String)?.substringAfterLast(':')?.toLongOrNull()?.let { it < cutoff } == true }
      .keys.forEach(editor::remove)
    editor.commit()
  }

  fun taskStates(id: String): Map<String, TaskState> = preferences.all.filterKeys { it.startsWith("taskState:$id:") }
    .mapNotNull { (key, raw) -> runCatching {
      val json = JSONObject(raw as String); val session = key.removePrefix("taskState:$id:")
      session to TaskState(session, TaskPhase.valueOf(json.str("phase")), json.str("detail"), json.optLong("since"), json.optLong("finished").takeIf { it > 0 })
    }.getOrNull() }.toMap()
  fun taskParents(id: String): Map<String, String> = preferences.all.filterKeys { it.startsWith("taskParent:$id:") }
    .mapKeys { it.key.removePrefix("taskParent:$id:") }.mapValues { it.value as? String ?: "" }.filterValues(String::isNotBlank)
  fun rememberParents(id: String, sessions: List<Session>) {
    val editor = preferences.edit()
    sessions.forEach { session ->
      val key = "taskParent:$id:${session.id}"
      if (session.parentId == null) editor.remove(key) else editor.putString(key, session.parentId)
    }
    editor.apply()
  }
  fun rememberTask(id: String, state: TaskState, parent: String? = null, observedAt: Long = System.currentTimeMillis(), durable: Boolean = false): TaskState = synchronized(pushLock) {
    val key = "taskState:$id:${state.sessionId}"
    val previous = runCatching { JSONObject(preferences.getString(key, "")!!).let { json -> TaskState(state.sessionId, TaskPhase.valueOf(json.str("phase")), json.str("detail"), json.optLong("since"), json.optLong("finished").takeIf { it > 0 }) } }.getOrNull()
    val lastObserved = preferences.getLong("taskTime:$id:${state.sessionId}", 0)
    if (observedAt < lastObserved) return@synchronized previous ?: state
    val next = if (previous != null && state.active && !previous.active && state.since <= previous.since)
      state.copy(since = observedAt, finishedAt = null) else state
    if (previous != next || parent != preferences.getString("taskParent:$id:${state.sessionId}", null)) {
      val editor = preferences.edit().putString(key, JSONObject().put("phase", next.phase.name).put("detail", next.detail)
        .put("since", next.since).put("finished", next.finishedAt).toString()).putLong("taskTime:$id:${state.sessionId}", observedAt)
      if (parent != null) editor.putString("taskParent:$id:${state.sessionId}", parent)
      if (previous?.since != next.since || previous?.phase !in setOf(TaskPhase.COMPLETED, TaskPhase.FAILED) && next.phase in setOf(TaskPhase.COMPLETED, TaskPhase.FAILED))
        editor.remove("taskRead:$id:${state.sessionId}")
      if (durable) editor.commit() else editor.apply()
    }
    next
  }
  fun claimNotification(id: String, state: TaskState, detail: String): Boolean = synchronized(pushLock) {
    val key = "notification:$id:${state.sessionId}"
    val signature = "${state.since}:${state.phase}:$detail"
    if (preferences.getString(key, "") == signature) return@synchronized false
    preferences.edit().putString(key, signature).commit()
  }

  /** Latest background-observed phases for one server, as session id to phase name. */
  fun pushPhases(id: String): Map<String, String> =
    preferences.all.filter { (k, _) -> k.startsWith("pushPhase:$id:") }
      .mapKeys { (k, _) -> k.removePrefix("pushPhase:$id:") }
      .mapValues { (_, v) -> (v as? String)?.substringBeforeLast(':').orEmpty() }
      .filterValues { it.isNotBlank() }

  /**
   * Sessions whose terminal result the user has already opened. Persisted per server so the island
   * summary stays unread-aware even after the App is killed and rebuilt from background pushes.
   */
  fun acknowledgedTasks(id: String): Set<String> {
    val states = taskStates(id)
    return preferences.all.filterKeys { it.startsWith("taskRead:$id:") }.filter { (key, read) ->
      read == "1" || read == states[key.removePrefix("taskRead:$id:")]?.since?.toString()
    }.keys.map { it.removePrefix("taskRead:$id:") }.toSet()
  }

  fun unacknowledgeTask(id: String, session: String) {
    preferences.edit().remove("taskRead:$id:$session").apply()
  }
  fun acknowledgeTask(id: String, session: String) {
    preferences.edit().putString("taskRead:$id:$session", taskStates(id)[session]?.since?.toString() ?: "1").apply()
  }

  fun selectedId(): String? = preferences.getString("selected", null)
  fun selectedProject(id: String? = selectedId()): String? = id?.let { preferences.getString("location:$it:project", null)
    ?: preferences.getString("selectedProject", null).takeIf { selectedId() == id } }
  fun selectedSession(id: String? = selectedId()): String? = id?.let { preferences.getString("location:$it:session", null)
    ?: preferences.getString("selectedSession", null).takeIf { selectedId() == id } }

  fun knownDirectories(id: String): Set<String> = preferences.getStringSet("directories:$id", emptySet()).orEmpty().toSet()
  fun rememberDirectory(id: String, directory: String) {
    preferences.edit().putStringSet("directories:$id", knownDirectories(id) + directory).apply()
  }
  fun sessionPreview(server: String, session: String): SessionPreview = runCatching {
    val raw = secrets.getString("preview:$server:$session", null)?.let(decryptValue)
      ?: preferences.getString("preview:$server:$session", "{}").orEmpty()
    val value = JSONObject(raw)
    SessionPreview(SessionContent.valueOf(value.str("content").ifBlank { "UNKNOWN" }), value.str("text"))
  }.getOrDefault(SessionPreview())
  fun rememberPreview(server: String, session: String, preview: SessionPreview) {
    runCatching {
      val value = JSONObject().put("content", preview.content.name).put("text", preview.text).toString()
      secrets.edit().putString("preview:$server:$session", encryptValue(value)).apply()
      preferences.edit().remove("preview:$server:$session").apply()
    }.onFailure { Diagnostics.warn("ServerStore", "会话摘要缓存暂不可用") }
  }
  fun configuration(server: String, session: String): SessionConfiguration = runCatching {
    val value = JSONObject(preferences.getString("configuration:$server:$session", "{}").orEmpty())
    SessionConfiguration(value.str("agent").ifBlank { null }, value.obj("model").toModelChoice(), value.optBoolean("agentChanged"), value.optBoolean("modelChanged"))
  }.getOrDefault(SessionConfiguration())
  fun rememberConfiguration(server: String, session: String, configuration: SessionConfiguration) {
    val model = configuration.model?.let { JSONObject().put("providerID", it.providerId).put("modelID", it.modelId).put("name", it.label) }
    preferences.edit().putString("configuration:$server:$session", JSONObject().put("agent", configuration.agent).put("model", model).put("agentChanged", configuration.agentChanged).put("modelChanged", configuration.modelChanged).toString()).apply()
  }
  fun references(server: String, session: String): List<FileReference> = runCatching {
    val raw = secrets.getString("references:$server:$session", null)?.let(decryptValue) ?: "[]"
    JSONArray(raw).objects().map { FileReference(it.str("path"), it.str("mime")) }
  }.getOrDefault(emptyList())
  fun rememberReferences(server: String, session: String, references: List<FileReference>) {
    if (references.isEmpty()) secrets.edit().remove("references:$server:$session").apply()
    else secrets.edit().putString("references:$server:$session", encryptValue(JSONArray().apply { references.forEach { put(JSONObject().put("path", it.path).put("mime", it.mime)) } }.toString())).apply()
  }
  fun draftSessionIds(server: String): Set<String> = secrets.all.keys.filter { it.startsWith("draft:$server:") }.map { it.removePrefix("draft:$server:") }.toSet()
  fun draft(server: String, session: String): String = runCatching {
    secrets.getString("draft:$server:$session", null)?.let(decryptValue).orEmpty()
  }.getOrDefault("")
  fun rememberDraft(server: String, session: String, text: String) {
    val key = "draft:$server:$session"
    if (text.isBlank()) secrets.edit().remove(key).apply()
    else secrets.edit().putString(key, encryptValue(text.take(100_000))).apply()
  }
  fun forgetSession(server: String, session: String) {
    val editor = preferences.edit()
    listOf("preview", "configuration", "taskRead", "taskState", "pushPhase", "taskParent", "taskTime", "pushSeen", "notification").forEach { editor.remove("$it:$server:$session") }
    editor.apply()
    secrets.edit().remove("draft:$server:$session").remove("references:$server:$session").remove("preview:$server:$session").apply()
  }
  fun deviceId(): String = preferences.getString("deviceId", null) ?: UUID.randomUUID().toString().also { preferences.edit().putString("deviceId", it).apply() }

  fun credentials(id: String): ServerCredentials {
    val profile = profiles().firstOrNull { it.id == id }
    val fallback = profile?.username ?: "opencode"
    return try {
      val raw = decrypt(id)
      when {
        raw == null -> ServerCredentials(fallback)
        !raw.trimStart().startsWith("{") -> ServerCredentials(fallback, raw)
        else -> {
          val json = JSONObject(raw)
          if (json.has("origin") && (profile == null || json.str("origin") != credentialOrigin(profile.url))) return ServerCredentials(fallback)
          ServerCredentials(
            username = json.str("username").ifBlank { fallback },
            password = json.str("password"),
            cookie = json.str("cookie")
          )
        }
      }
    } catch (error: Exception) {
      Diagnostics.warn("ServerStore", "凭据解密失败，回退到用户名", error)
      ServerCredentials(fallback)
    }
  }

  /** Update only the island vendor switches for a profile, leaving credentials and other fields intact. */
  fun updateIslandVendor(profile: ServerProfile) {
    val existing = profiles().firstOrNull { it.id == profile.id } ?: return
    save(existing.copy(islandHonor = profile.islandHonor, islandOppoFluidCloud = profile.islandOppoFluidCloud), password = null, credentialUsername = existing.username)
  }

  fun select(id: String, project: String? = null, session: String? = null) {
    preferences.edit().putString("selected", id).putString("selectedProject", project).putString("selectedSession", session)
      .putString("location:$id:project", project).putString("location:$id:session", session).apply()
  }
  fun rememberLocation(project: String?, session: String?) {
    val id = selectedId() ?: return
    preferences.edit().putString("selectedProject", project).putString("selectedSession", session)
      .putString("location:$id:project", project).putString("location:$id:session", session).apply()
  }

  fun save(profile: ServerProfile, password: String?, cookie: String? = null, credentialUsername: String? = null) {
    val previousProfile = profiles().firstOrNull { it.id == profile.id }
    val previousCredentials = credentials(profile.id)
    val next = profileCredentials(previousProfile?.url, profile.url, previousCredentials,
      credentialUsername ?: profile.username, password, cookie)
    // Encrypt everything before mutating either store. Credentials carry their own origin, so a
    // crash between these two preference commits can only cause a missing login, never a leak.
    val encoded = if (next.password.isEmpty() && next.cookie.isEmpty()) null else encryptValue(JSONObject()
      .put("origin", credentialOrigin(profile.url)).put("username", next.username).put("password", next.password).put("cookie", next.cookie).toString())
    val pushKey = profile.pluginSecret.takeIf { it.isNotBlank() }?.let { "enc:v1:" + encryptValue(it) }
    val updated = profiles().filterNot { it.id == profile.id } + profile
    val json = JSONArray().apply { updated.forEach { item -> put(JSONObject()
      .put("id", item.id).put("name", item.name).put("url", item.url).put("username", item.username)
      .put("autoConnect", item.autoConnect).put("notifications", item.notifications).put("companionUrl", item.companionUrl)
      .put("allowCleartext", item.allowCleartext).put("islandHonor", item.islandHonor)
      .put("islandOppoFluidCloud", item.islandOppoFluidCloud)) } }
    check(secrets.edit().putString(profile.id, encoded).commit()) { "无法保存凭据，请重试" }
    check(preferences.edit().putString("profiles", json.toString()).putString("pluginSecret:${profile.id}", pushKey).commit()) { "无法保存服务器资料，请重试" }
  }
  private fun decrypt(id: String): String? = try {
    val value = secrets.getString(id, null) ?: return null
    decryptValue(value)
  } catch (_: Exception) { null }
  fun delete(id: String) {
    val json = JSONArray().apply { profiles().filterNot { it.id == id }.forEach { item ->
      put(JSONObject().put("id", item.id).put("name", item.name).put("url", item.url)
        .put("username", item.username).put("autoConnect", item.autoConnect)
        .put("notifications", item.notifications).put("companionUrl", item.companionUrl)
        .put("allowCleartext", item.allowCleartext).put("islandHonor", item.islandHonor)
        .put("islandOppoFluidCloud", item.islandOppoFluidCloud))
    } }
    val editor = preferences.edit().putString("profiles", json.toString()).remove("pluginSecret:$id").remove("directories:$id")
    val prefixes = listOf("location", "preview", "configuration", "taskRead", "taskState", "taskParent", "taskTime", "pushPhase", "pushSeen", "notification").map { "$it:$id:" }
    preferences.all.keys.filter { key -> prefixes.any(key::startsWith) }.forEach(editor::remove)
    editor.apply()
    val encrypted = secrets.edit().remove(id)
    val secretPrefixes = listOf("draft", "references", "preview").map { "$it:$id:" }
    secrets.all.keys.filter { key -> secretPrefixes.any(key::startsWith) }.forEach(encrypted::remove)
    encrypted.apply()
    if (selectedId() == id) select("", null, null)
  }
}
