package com.igng.opencode.mobile.core

import okhttp3.HttpUrl

/**
 * Identity of a credential target: scheme + host + port. Two URLs with equal [HttpOrigin] may share
 * one set of credentials; any difference (HTTP→HTTPS, port change, host change) is a different
 * target and must not receive them implicitly.
 */
internal data class HttpOrigin(val scheme: String, val host: String, val port: Int) {
  companion object {
    fun of(url: HttpUrl): HttpOrigin = HttpOrigin(url.scheme.lowercase(), url.host.lowercase(), url.port)
    /** The cleartext target restriction used by pairing and push registration. */
    fun allowsCleartext(host: String): Boolean = host in setOf("localhost", "127.0.0.1", "::1")
  }
}
