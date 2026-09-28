package com.igng.opencode.mobile.core

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.TimeUnit

/**
 * Process-wide OkHttp resources.
 *
 * OkHttp creates a ConnectionPool (up to 5 sockets plus a cleanup thread) and a Dispatcher (executor
 * threads) per client instance. The app used to build four unrelated clients, so API calls, the SSE
 * stream, pair-link resolution and push registration could not reuse TCP/TLS connections and each kept
 * its own idle sockets. Sharing the pool lets every request after the SSE stream is open reuse the
 * already-authenticated connection instead of paying a fresh handshake, and sharing the dispatcher
 * avoids redundant idle thread pools.
 */
internal object SharedHttp {
  val connectionPool = ConnectionPool(maxIdleConnections = 5, keepAliveDuration = 5, timeUnit = TimeUnit.MINUTES)
  val dispatcher = Dispatcher()
  /** Blocking HTTP work; shared so per-API instances do not each leak a scope. */
  val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
