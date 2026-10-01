package com.igng.opencode.lagoon.core

import android.content.ContextWrapper
import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

/** Exercise actual Controller operations across delayed responses and context switches. */
class ControllerRegressionTest {
  private fun put(target: Any, name: String, value: Any?) {
    target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
  }
  private fun preferences(): SharedPreferences {
    val data = mutableMapOf<String, Any?>()
    val editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
      when {
        method.name.startsWith("put") -> { data[args!![0] as String] = args[1]; proxy }
        method.name == "remove" -> { data.remove(args!![0]); proxy }
        method.name == "clear" -> { data.clear(); proxy }
        method.name == "commit" -> true
        else -> null
      }
    }
    return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
      when(method.name) {
        "edit" -> editor
        "getAll" -> data.toMap()
        "contains" -> data.containsKey(args!![0])
        "getString", "getBoolean", "getInt", "getLong", "getFloat", "getStringSet" -> data[args!![0]] ?: args[1]
        else -> null
      }
    } as SharedPreferences
  }
  private val context = object: ContextWrapper(null) {
    private val stores = mutableMapOf<String, SharedPreferences>()
    override fun getSharedPreferences(name: String, mode: Int) = stores.getOrPut(name) { preferences() }
  }
  private fun controller(api: OpenCodeApi, initial: LagoonState): Pair<LagoonController, MutableStateFlow<LagoonState>> {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    val c = unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, LagoonController::class.java) as LagoonController
    val mutable = MutableStateFlow(initial)
    put(c,"mutable", mutable); put(c,"state",mutable); put(c,"generation",1); put(c,"api",api)
    put(c,"operationScope",CoroutineScope(SupervisorJob()+Dispatchers.Unconfined))
    put(c,"scope",CoroutineScope(Job().apply { cancel() }+Dispatchers.Default))
    put(c,"store",ServerStore(context));put(c,"cache",OfflineCache(context.getSharedPreferences("cache",0),{it},{it}));put(c,"sendMutex",kotlinx.coroutines.sync.Mutex())
    return c to mutable
  }
  private fun api(s:MockWebServer) = OpenCodeApi(ServerProfile("server","Server",s.url("/").toString(),allowCleartext=true), "fixture")
  private suspend fun refresh(c:LagoonController) = suspendCoroutine<Unit> { cont ->
    try {
      val m = LagoonController::class.java.getDeclaredMethod("loadAll", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Continuation::class.java).apply{isAccessible=true}
      val result=m.invoke(c,1,true,cont)
      if(result !== COROUTINE_SUSPENDED) cont.resume(Unit)
    }catch(e:Throwable){cont.resumeWithException(e.cause?:e)}
  }
  @Test fun offlineReadCannotOverwriteAChangedConnectionGeneration() = runBlocking {
    MockWebServer().use { server ->
      val (c,state)=controller(api(server),LagoonState(serverId="server",connected=true))
      val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
      val prefs=memoryPreferences()
      val cache=OfflineCache(prefs,{it},{entered.countDown();check(release.await(3,TimeUnit.SECONDS));it})
      cache.saveCatalog("server",emptyList(),emptyList());cache.awaitWrites();put(c,"cache",cache)
      val job=async {
        suspendCoroutine<Unit> { cont ->
          try {
            val m=LagoonController::class.java.getDeclaredMethod("showOffline",String::class.java,Int::class.javaPrimitiveType,String::class.java,Continuation::class.java).apply{isAccessible=true}
            val result=m.invoke(c,"server",1,"offline",cont)
            if(result !== COROUTINE_SUSPENDED)cont.resume(Unit)
          }catch(e:Throwable){cont.resumeWithException(e.cause?:e)}
        }
      }
      yield();assertTrue(entered.await(2,TimeUnit.SECONDS))
      put(c,"generation",2);state.value=state.value.copy(connected=true,error="new connection")
      release.countDown();job.await()
      assertTrue(state.value.connected);assertFalse(state.value.cached);assertEquals("new connection",state.value.error)
    }
  }
  @Test fun oldProjectFileResponseCannotOverwriteNewProject() = runBlocking {
    MockWebServer().use { s ->
      s.enqueue(MockResponse().setBody("""{"healthy":true}"""))
      s.enqueue(MockResponse().setBody("""{"type":"text","content":"private from project A"}""").setBodyDelay(250,TimeUnit.MILLISECONDS))
      val api=api(s);api.health();s.takeRequest()
      val(c,state)=controller(api,LagoonState(serverId="server",connected=true,projects=listOf(Project("a","/a","A"),Project("b","/b","B")),projectId="a"))
      val job=c.readFile("secret.txt");assertNotNull(s.takeRequest(2,TimeUnit.SECONDS))
      state.value=state.value.copy(projectId="b",sessionId="b-session")
      job.join();assertEquals("b",state.value.projectId);assertNull(state.value.fileText)
    }
  }
  @Test fun slowDeletePreservesNewlySelectedSession() = runBlocking {
    MockWebServer().use { s ->
      s.enqueue(MockResponse().setBody("""{"healthy":true}"""));s.enqueue(MockResponse().setResponseCode(204).setHeadersDelay(250,TimeUnit.MILLISECONDS))
      val api=api(s);api.health();s.takeRequest()
      val(c,state)=controller(api,LagoonState(serverId="server",connected=true,sessions=listOf(Session("a","/repo","A",0),Session("b","/repo","B",0)),sessionId="a"))
      val job=c.deleteSession();assertNotNull(s.takeRequest(2,TimeUnit.SECONDS));state.value=state.value.copy(sessionId="b")
      job.join();assertEquals("b",state.value.sessionId)
    }
  }
  @Test fun partialSessionFailurePreservesTaskAndSelection() = runBlocking {
    MockWebServer().use { s ->
      s.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest):MockResponse {
        val p=r.requestUrl!!; val body=when(p.encodedPath) {
          "/global/health" -> """{"healthy":true}"""
          "/project" -> """[{"id":"a","worktree":"/a"},{"id":"b","worktree":"/b"}]"""
          "/session" -> if(p.queryParameter("directory")=="/a") return MockResponse().setResponseCode(500) else """[{"id":"sb","directory":"/b","title":"B","time":{}}]"""
          "/session/status" -> if(p.queryParameter("directory")=="/a") """{"sa":{"type":"busy"}}""" else "{}"
          else -> "[]"
        }; return MockResponse().setBody(body)
      }}
      val(c,state)=controller(api(s),LagoonState(serverId="server",connected=true,projects=listOf(Project("a","/a","A"),Project("b","/b","B")),projectId="a",sessions=listOf(Session("sa","/a","A",0)),sessionId="sa",tasks=mapOf("sa" to TaskState("sa",TaskPhase.THINKING))))
      refresh(c)
      assertTrue(state.value.degraded);assertTrue(state.value.sessions.any{it.id=="sa"});assertEquals(TaskPhase.THINKING,state.value.tasks["sa"]?.phase);assertEquals("sa",state.value.sessionId)
    }
  }
  @Test fun forkResponseSelectsNewSession() = runBlocking {
    MockWebServer().use { s ->
      s.enqueue(MockResponse().setBody("""{"healthy":true}"""));s.enqueue(MockResponse().setBody("""{"id":"forked","directory":"/repo","title":"Fork","time":{}}"""))
      val api=api(s);api.health()
      val(c,state)=controller(api,LagoonState(serverId="server",connected=true,sessions=listOf(Session("original","/repo","Original",0)),sessionId="original"))
      c.fork().join();assertEquals("forked",state.value.sessionId);assertTrue(state.value.sessions.any{it.id=="forked"})
    }
  }
  @Test fun queuedSendCannotMixServerAndSession() = runBlocking {
    MockWebServer().use { a -> MockWebServer().use { b ->
      a.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest):MockResponse = when {
        r.path!!.startsWith("/global/health") -> MockResponse().setBody("""{"healthy":true}""")
        r.path!!.startsWith("/session/sa/prompt_async") -> MockResponse().setBody("{}").setBodyDelay(350,TimeUnit.MILLISECONDS)
        r.path!!.contains("prompt_async") -> MockResponse().setBody("{}")
        else -> MockResponse().setBody("[]")
      }}
      val aa=api(a);aa.health();a.takeRequest()
      val(c,state)=controller(aa,LagoonState(serverId="server",connected=true,sessions=listOf(Session("sa","/a","A",0)),sessionId="sa"))
      val first=c.send("first");assertTrue(a.takeRequest(2,TimeUnit.SECONDS)!!.path!!.contains("/sa/prompt_async"))
      val second=c.send("queued on A");delay(80)
      put(c,"generation",2);put(c,"api",api(b))
      state.value=LagoonState(serverId="other",connected=true,sessions=listOf(Session("sb","/b","B",0)),sessionId="sb")
      first.join();second.join()
      val requests=generateSequence { a.takeRequest(100,TimeUnit.MILLISECONDS) }.toList()
      assertFalse(requests.any{it.path!!.contains("prompt_async")})
      assertEquals(0,b.requestCount)
    }}
  }
  @Test fun transcriptFailureKeepsCachedFlagAcrossControlRefresh() = runBlocking {
    MockWebServer().use { server ->
      server.dispatcher = object: Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
        return when (request.requestUrl!!.encodedPath) {
          "/global/health" -> MockResponse().setBody("""{"healthy":true}""")
          "/project" -> MockResponse().setBody("""[{"id":"p","worktree":"/repo"}]""")
          "/session" -> MockResponse().setBody("""[{"id":"s","directory":"/repo","title":"Task","time":{}}]""")
          "/session/status" -> MockResponse().setBody("{}")
          "/session/s/message" -> MockResponse().setResponseCode(500)
          else -> MockResponse().setBody("[]")
        }
      } }
      val api = api(server); api.health()
      val session = Session("s", "/repo", "Task", 0)
      val (controller, state) = controller(api, LagoonState(serverId="server", connected=true,
        projects=listOf(Project("p", "/repo", "P")), projectId="p", sessions=listOf(session), sessionId="s"))
      val cache = LagoonController::class.java.getDeclaredField("cache").apply { isAccessible=true }.get(controller) as OfflineCache
      cache.saveMessages("server", "s", listOf(Message("old", "user", 0, emptyList()))); cache.awaitWrites()
      suspendCoroutine<Unit> { cont ->
        val method=LagoonController::class.java.getDeclaredMethod("loadSession", Session::class.java, Boolean::class.javaPrimitiveType,
          Int::class.javaPrimitiveType, OpenCodeApi::class.java, Continuation::class.java).apply { isAccessible=true }
        val result=method.invoke(controller, session, false, 1, api, cont)
        if (result !== COROUTINE_SUSPENDED) cont.resume(Unit)
      }
      assertTrue(state.value.cached); assertEquals("old", state.value.messages.single().id)
      refresh(controller)
      assertTrue(state.value.cached); assertTrue(state.value.connected)
    }
  }

}
