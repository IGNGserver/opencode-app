package com.igng.opencode.mobile.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ServerStore(context: Context) {
  private val preferences = context.getSharedPreferences("servers", Context.MODE_PRIVATE)
  private val secrets = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
  private val key = KeystoreCipher("opencode-mobile-server-passwords")

  fun profiles(): List<ServerProfile> = try {
    JSONArray(preferences.getString("profiles", "[]")).objects().map {
      val id = it.str("id")
      ServerProfile(id, it.str("name"), it.str("url"), it.str("username"),
        it.optBoolean("autoConnect", true), it.optBoolean("notifications", true), it.str("companionUrl"), it.optBoolean("allowCleartext", false),
        pluginSecret(id))
    }
  } catch (error: Exception) {
    Diagnostics.warn("ServerStore", "profiles 解析失败，已忽略全部服务器资料", error)
    emptyList()
  }

  /** Shared HMAC key for companion push messages; a symmetric verification key, not a login secret.
   *  Stored Keystore-encrypted at rest, with a transparent read of the legacy plaintext value (A07). */
  fun pluginSecret(id: String): String {
    val stored = preferences.getString("pluginSecret:$id", "") ?: ""
    if (stored.isBlank()) return ""
    val decrypted = runCatching { key.decrypt(stored) }.getOrNull()
    if (decrypted != null) return decrypted
    // Legacy installs stored the key in cleartext; adopt it and rewrite encrypted.
    if (stored.startsWith("{")) return ""
    runCatching { preferences.edit().putString("pluginSecret:$id", key.encrypt(stored)).apply() }
    return stored
  }

  fun selectedId(): String? = preferences.getString("selected", null)
  fun selectedProject(): String? = preferences.getString("selectedProject", null)
  fun selectedSession(): String? = preferences.getString("selectedSession", null)
  fun deviceId(): String = preferences.getString("deviceId", null) ?: UUID.randomUUID().toString().also { preferences.edit().putString("deviceId", it).apply() }

  fun credentials(id: String): ServerCredentials {
    val fallback = profiles().firstOrNull { it.id == id }?.username ?: "opencode"
    return try {
      val raw = decrypt(id)
      when {
        raw == null -> ServerCredentials(fallback)
        !raw.trimStart().startsWith("{") -> ServerCredentials(fallback, raw)
        else -> {
          val json = JSONObject(raw)
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

  fun select(id: String, project: String? = null, session: String? = null) {
    preferences.edit().putString("selected", id).putString("selectedProject", project).putString("selectedSession", session).apply()
  }
  fun rememberLocation(project: String?, session: String?) {
    preferences.edit().putString("selectedProject", project).putString("selectedSession", session).apply()
  }

  fun save(profile: ServerProfile, password: String?, cookie: String? = null, credentialUsername: String? = null) {
    // The secret is encrypted at rest; a blank value explicitly clears it so the user can revoke it
    // instead of being stuck with a previously entered key.
    if (profile.pluginSecret.isBlank()) preferences.edit().remove("pluginSecret:${profile.id}").apply()
    else runCatching { preferences.edit().putString("pluginSecret:${profile.id}", key.encrypt(profile.pluginSecret)).apply() }
      .onFailure { Diagnostics.warn("ServerStore", "推送密钥加密失败", it) }
    val updated = profiles().filterNot { it.id == profile.id } + profile
    val json = JSONArray().apply { updated.forEach { item -> put(JSONObject()
      .put("id", item.id).put("name", item.name).put("url", item.url).put("username", item.username)
      .put("autoConnect", item.autoConnect).put("notifications", item.notifications).put("companionUrl", item.companionUrl)
      .put("allowCleartext", item.allowCleartext)) } }
    preferences.edit().putString("profiles", json.toString()).apply()
    if (password != null || cookie != null || credentialUsername != null) {
      val previous = credentials(profile.id)
      val next = ServerCredentials(
        username = credentialUsername ?: previous.username.ifBlank { profile.username },
        password = password ?: previous.password,
        cookie = cookie ?: previous.cookie
      )
      if (next.password.isEmpty() && next.cookie.isEmpty()) secrets.edit().remove(profile.id).apply()
      else encrypt(profile.id, JSONObject().put("username", next.username).put("password", next.password).put("cookie", next.cookie).toString())
    }
  }
  private fun decrypt(id: String): String? = try {
    val value = secrets.getString(id, null) ?: return null
    key.decrypt(value)
  } catch (_: Exception) { null }
  private fun encrypt(id: String, value: String) {
    secrets.edit().putString(id, key.encrypt(value)).apply()
  }
  fun delete(id: String) {
    val json = JSONArray().apply { profiles().filterNot { it.id == id }.forEach { item ->
      put(JSONObject().put("id", item.id).put("name", item.name).put("url", item.url)
        .put("username", item.username).put("autoConnect", item.autoConnect)
        .put("notifications", item.notifications).put("companionUrl", item.companionUrl)
        .put("allowCleartext", item.allowCleartext))
    } }
    preferences.edit().putString("profiles", json.toString()).apply()
    secrets.edit().remove(id).apply()
    preferences.edit().remove("pluginSecret:$id").apply()
    if (selectedId() == id) select("", null, null)
  }
}
