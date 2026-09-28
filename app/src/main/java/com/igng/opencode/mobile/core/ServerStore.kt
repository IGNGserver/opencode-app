package com.igng.opencode.mobile.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class ServerStore(context: Context) {
  private val preferences = context.getSharedPreferences("servers", Context.MODE_PRIVATE)
  private val secrets = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
  private val alias = "opencode-mobile-server-passwords"
  private val key: SecretKey by lazy {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
      init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
      generateKey()
    }
  }

  fun profiles(): List<ServerProfile> = try {
    JSONArray(preferences.getString("profiles", "[]")).objects().map {
      ServerProfile(it.str("id"), it.str("name"), it.str("url"), it.str("username"),
        it.optBoolean("autoConnect", true), it.optBoolean("notifications", true), it.str("companionUrl"), it.optBoolean("allowCleartext", false))
    }
  } catch (_: Exception) { emptyList() }

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
    } catch (_: Exception) {
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
  fun password(id: String): String = credentials(id).password
  private fun decrypt(id: String): String? = try {
    val value = secrets.getString(id, null) ?: return null
    val payload = Base64.decode(value, Base64.NO_WRAP)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, payload.copyOfRange(0, 12)))
    String(cipher.doFinal(payload.copyOfRange(12, payload.size)), Charsets.UTF_8)
  } catch (_: Exception) { null }
  private fun encrypt(id: String, value: String) {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, key)
    val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
    val payload = cipher.iv + encrypted
    secrets.edit().putString(id, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
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
    if (selectedId() == id) select("", null, null)
  }
}
