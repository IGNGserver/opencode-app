package com.igng.opencode.mobile.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class OfflineCache(context: Context) {
  private val preferences = context.getSharedPreferences("offline_cache", Context.MODE_PRIVATE)
  private val alias = "opencode-mobile-offline-cache"
  private val key: SecretKey by lazy {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
      init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
      generateKey()
    }
  }
  private val writes = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val pendingLock = Any()
  private val pending = HashMap<String, String>()
  private var writer: Job? = null
  // Serialization and AES-GCM encryption of large transcripts are CPU-bound. Queue them on a
  // dedicated IO scope so refreshing a session never blocks the UI thread. Draining a snapshot of
  // the pending map means a fast stream cannot grow an unbounded encryption backlog and the latest
  // value for a key always wins, while writes stay ordered.
  private fun write(name: String, value: String) {
    synchronized(pendingLock) {
      pending[name] = value
      if (writer == null) writer = writes.launch { drainWrites() }
    }
  }
  private suspend fun drainWrites() {
    try {
      while (true) {
        val batch = synchronized(pendingLock) {
          if (pending.isEmpty()) {
            writer = null
            return
          }
          pending.toMap().also { pending.clear() }
        }
        for ((name, value) in batch) {
          val cipher = Cipher.getInstance("AES/GCM/NoPadding")
          cipher.init(Cipher.ENCRYPT_MODE, key)
          val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
          preferences.edit().putString(name, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
        }
      }
    } catch (_: Exception) {
      // A failed write must not wedge the drain loop; the next enqueue restarts it.
      synchronized(pendingLock) { writer = null }
    }
  }
  private fun read(name: String): String? = try {
    val payload = Base64.decode(preferences.getString(name, null) ?: return null, Base64.NO_WRAP)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, payload.copyOfRange(0, 12)))
    String(cipher.doFinal(payload.copyOfRange(12, payload.size)), Charsets.UTF_8)
  } catch (error: Exception) {
    Diagnostics.warn("OfflineCache", "读取 $name 失败", error)
    null
  }
  fun saveCatalog(serverId: String, projects: List<Project>, sessions: List<Session>) {
    val data = JSONObject().put("projects", JSONArray().apply { projects.forEach { put(JSONObject().put("id", it.id).put("directory", it.directory).put("name", it.name)) } })
      .put("sessions", JSONArray().apply { sessions.take(300).forEach { put(JSONObject().put("id", it.id).put("directory", it.directory)
        .put("title", it.title).put("updated", it.updated).put("parentId", it.parentId)) } })
    write("catalog:$serverId", data.toString())
  }
  fun catalog(serverId: String): Pair<List<Project>, List<Session>>? {
    val raw = read("catalog:$serverId") ?: return null
    return try {
    val data = JSONObject(raw)
    data.arr("projects").objects().map { Project(it.str("id"), it.str("directory"), it.str("name")) } to
      data.arr("sessions").objects().map { Session(it.str("id"), it.str("directory"), it.str("title"), it.optLong("updated"), it.str("parentId").ifBlank { null }) }
  } catch (error: Exception) {
    Diagnostics.warn("OfflineCache", "catalog 解析失败", error)
    null
  }
  }
  fun saveMessages(serverId: String, sessionId: String, messages: List<Message>) {
    val data = JSONArray().apply { messages.takeLast(100).forEach { message ->
      put(JSONObject().put("id", message.id).put("role", message.role).put("created", message.created).put("error", message.error)
        .put("parts", JSONArray().apply { message.parts.forEach { part ->
          put(JSONObject().put("id", part.id).put("type", part.type).put("text", part.text.take(20_000))
            .put("tool", part.tool).put("title", part.title).put("status", part.status).put("input", part.input.take(4_000))
            .put("output", part.output.take(4_000)).put("path", part.path).put("error", part.error.take(4_000))
            .put("patch", part.patch.take(20_000)).put("files", JSONArray(part.files)).put("attachments", JSONArray(part.attachments)))
        } }))
    } }
    write("messages:$serverId:$sessionId", data.toString())
  }
  fun messages(serverId: String, sessionId: String): List<Message> {
    val raw = read("messages:$serverId:$sessionId") ?: return emptyList()
    return try {
    JSONArray(raw).objects().map { item ->
      Message(item.str("id"), item.str("role"), item.optLong("created"), item.arr("parts").objects().map { part ->
        MessagePart(part.str("id"), part.str("type"), part.str("text"), part.str("tool"), part.str("title"), part.str("status"), part.str("input"), part.str("output"), part.str("path"), part.str("error"), part.str("patch"),
          (0 until part.arr("files").length()).mapNotNull { index -> part.arr("files").optString(index).takeIf(String::isNotBlank) },
          (0 until part.arr("attachments").length()).mapNotNull { index -> part.arr("attachments").optString(index).takeIf(String::isNotBlank) })
      }, item.str("error").ifBlank { null })
    }
  } catch (error: Exception) {
    Diagnostics.warn("OfflineCache", "messages 解析失败", error)
    emptyList()
  }
  }
  fun delete(serverId: String) {
    synchronized(pendingLock) {
      pending.keys.removeAll { it == "catalog:$serverId" || it.startsWith("messages:$serverId:") }
      preferences.edit().apply {
        preferences.all.keys.filter { it == "catalog:$serverId" || it.startsWith("messages:$serverId:") }.forEach(::remove)
      }.apply()
    }
  }
}
