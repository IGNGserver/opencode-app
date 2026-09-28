package com.igng.opencode.mobile.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.concurrent.TimeUnit

data class PairResolution(val serverUrl: String, val credentials: ServerCredentials)

object PairLinkResolver {
  private val client = OkHttpClient.Builder()
    .followRedirects(true)
    .followSslRedirects(true)
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    .connectionPool(SharedHttp.connectionPool)
    .dispatcher(SharedHttp.dispatcher)
    .build()

  fun isPairLink(value: String): Boolean {
    val url = value.trim().toHttpUrlOrNull() ?: return false
    return url.queryParameter("auth_token") != null || url.encodedPath.contains("/auth/connect/")
  }

  suspend fun resolve(value: String): PairResolution = withContext(Dispatchers.IO) {
    val input = requireNotNull(value.trim().toHttpUrlOrNull()) { "配对链接无效" }
    // The one-time pairing token is a bearer credential; never send it over cleartext unless the
    // target is the local loopback (tests/dev). This mirrors the gate in PushRegistration.register.
    require(input.scheme == "https" || input.host in setOf("localhost", "127.0.0.1", "::1")) {
      "配对链接必须使用 HTTPS"
    }
    val direct = input.queryParameter("auth_token")
    if (!direct.isNullOrBlank()) return@withContext PairResolution(rootUrl(input), decodeToken(direct))

    val jar = CapturingCookieJar()
    val response = client.newBuilder().cookieJar(jar).build()
      .newCall(Request.Builder().url(input).get().header("Accept", "text/html, */*").build())
      .execute()
    response.use {
      if (!it.isSuccessful && it.code !in 300..399) throw IOException("配对链接返回 HTTP ${it.code}")
      val finalUrl = it.request.url
      require(finalUrl.scheme == "https" || finalUrl.host in setOf("localhost", "127.0.0.1", "::1")) {
        "配对链接重定向到了非 HTTPS 地址"
      }
      val token = finalUrl.queryParameter("auth_token")
      if (!token.isNullOrBlank()) return@withContext PairResolution(rootUrl(finalUrl), decodeToken(token))
      val cookie = jar.header(finalUrl)
      if (cookie.isNotBlank()) return@withContext PairResolution(rootUrl(finalUrl), ServerCredentials(cookie = cookie))
      throw IOException("配对链接已失效或未返回登录凭据")
    }
  }

  private fun rootUrl(url: HttpUrl): String = url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')

  private fun decodeToken(value: String): ServerCredentials {
    val normalized = value.replace('-', '+').replace('_', '/')
      .let { it + "=".repeat((4 - it.length % 4) % 4) }
    val decoded = runCatching { java.util.Base64.getDecoder().decode(normalized) }
      .getOrElse { throw IOException("配对凭据格式无效") }
    val credentials = String(decoded, Charsets.UTF_8)
    val separator = credentials.indexOf(':')
    if (separator < 0) throw IOException("配对凭据格式无效")
    return ServerCredentials(credentials.substring(0, separator).ifBlank { "opencode" }, credentials.substring(separator + 1))
  }

  private class CapturingCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
      this.cookies.removeAll { old -> cookies.any { next -> old.name == next.name && old.domain == next.domain && old.path == next.path } }
      this.cookies += cookies
    }
    override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter { it.matches(url) }
    fun header(url: HttpUrl): String = loadForRequest(url).joinToString("; ") { "${it.name}=${it.value}" }
  }
}
