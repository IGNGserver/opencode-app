package com.igng.opencode.mobile.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ServerStore internal constructor(private val preferences: SharedPreferences, private val secrets: SharedPreferences,
  private val encryptValue: (String) -> String, private val decryptValue: (String) -> String) {
  constructor(context: Context) : this(context.getSharedPreferences("servers", Context.MODE_PRIVATE),
    context.getSharedPreferences("secrets", Context.MODE_PRIVATE),
    KeystoreCipher("opencode-mobile-server-passwords")::encrypt, KeystoreCipher("opencode-mobile-server-passwords")::decrypt)

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
  fun acknowledgedTasks(id: String): Set<String> =
    preferences.all.keys.filter { it.startsWith("taskRead:$id:") }.map { it.removePrefix("taskRead:$id:") }.toSet()

  fun acknowledgeTask(id: String, session: String) {
    preferences.edit().putString("taskRead:$id:$session", "1").apply()
  }

  fun selectedId(): String? = preferences.getString("selected", null)
  fun selectedProject(): String? = preferences.getString("selectedProject", null)
  fun selectedSession(): String? = preferences.getString("selectedSession", null)
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
    preferences.edit().putString("selected", id).putString("selectedProject", project).putString("selectedSession", session).apply()
  }
  fun rememberLocation(project: String?, session: String?) {
    preferences.edit().putString("selectedProject", project).putString("selectedSession", session).apply()
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
    preferences.edit().putString("profiles", json.toString()).apply()
    secrets.edit().remove(id).apply()
    preferences.edit().remove("pluginSecret:$id").apply()
    if (selectedId() == id) select("", null, null)
  }
}
