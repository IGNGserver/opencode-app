package com.igng.opencode.lagoon.core

import android.content.SharedPreferences
import com.igng.opencode.lagoon.push.PushMessageVerifier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal fun memoryPreferences(): SharedPreferences {
  val values=java.util.Collections.synchronizedMap(mutableMapOf<String,Any?>())
  val loader=SharedPreferences::class.java.classLoader
  return Proxy.newProxyInstance(loader,arrayOf(SharedPreferences::class.java)) { _, method,args ->
    when(method.name) {
      "getAll" -> synchronized(values){values.toMap()}
      "contains" -> values.containsKey(args!![0])
      "edit" -> {
        val changes=mutableMapOf<String,Any?>()
        Proxy.newProxyInstance(loader,arrayOf(SharedPreferences.Editor::class.java)) { proxy,m,a ->
          when {
            m.name.startsWith("put") -> { changes[a!![0] as String]=a[1];proxy }
            m.name=="remove" -> {changes[a!![0] as String]=null;proxy}
            m.name=="commit" || m.name=="apply" -> {synchronized(values){changes.forEach{(k,v)->if(v==null)values.remove(k) else values[k]=v}};if(m.name=="commit")true else null}
            else -> null
          }
        }
      }
      "getString","getLong","getInt","getBoolean","getFloat","getStringSet" -> values[args!![0]]?:args[1]
      else -> null
    }
  } as SharedPreferences
}
class BoundaryRegressionTest {
  private fun store(p:SharedPreferences=memoryPreferences(),secret:SharedPreferences=memoryPreferences())=ServerStore(p,secret,{"encrypted:$it"},{require(it.startsWith("encrypted:"));it.removePrefix("encrypted:")})
  private fun profile(url:String)=ServerProfile("srv","Server",url,allowCleartext=true)
  @Test fun originChangeCannotRetainSavedCookieInTheRealConnection()=runBlocking {    MockWebServer().use { a -> MockWebServer().use { b ->
      val store=store();val old=profile(a.url("/").toString());store.save(old,"old-password","private-cookie","opencode")
      val edited=old.copy(url=b.url("/").toString())
      val tested=profileCredentials(old.url,edited.url,store.credentials(old.id),"opencode","new-password")
      store.save(edited,tested.password,tested.cookie,tested.username)
      b.enqueue(MockResponse().setBody("""{"healthy":true}"""))
      OpenCodeApi(store.profiles().single(),store.credentials("srv")).health()
      assertNull(b.takeRequest().getHeader("Cookie"));assertEquals("new-password",store.credentials("srv").password)
      // Defence in depth for a future caller that forgets to pass an explicit empty Cookie.
      store.save(old,"old-password","private-cookie","opencode");store.save(edited,"new-password",credentialUsername="opencode")
      assertEquals("",store.credentials("srv").cookie)
    }}
  }
  @Test fun interruptedProfileSaveCannotExposeNewCredentialsToOldOrigin() {
    val prefs=memoryPreferences();val secrets=memoryPreferences();val s=store(prefs,secrets)
    s.save(profile("https://old.example"),"old-password")
    val oldProfiles=prefs.getString("profiles",null)
    s.save(profile("https://new.example"),"new-password","new-cookie")
    // Simulate death after the credential commit but before the profile commit.
    prefs.edit().putString("profiles",oldProfiles).commit()
    assertEquals("",store(prefs,secrets).credentials("srv").password)
    assertEquals("",store(prefs,secrets).credentials("srv").cookie)
  }
  @Test fun unchangedOriginCanSaveCookieOnlyAccount() {
    val s=store();val p=profile("https://server.example");s.save(p,"","cookie","user")
    val c=profileCredentials(p.url,p.url,s.credentials(p.id),"user",null)
    s.save(p.copy(name="Renamed"),c.password,c.cookie,c.username)
    assertEquals("cookie",s.credentials(p.id).cookie);assertEquals("",s.credentials(p.id).password)
  }
  @Test fun failedKeyDecryptionNeverAdoptsCiphertextAsASecret() {
    val p=memoryPreferences();p.edit().putString("pluginSecret:srv","enc:v1:corrupted").commit()
    assertEquals("",store(p).pluginSecret("srv"));assertEquals("enc:v1:corrupted",p.getString("pluginSecret:srv",null))
  }
  @Test fun pushDeviceAndSequenceAreVerifiedAcrossStoreRecreation() {
    val p=memoryPreferences();val secret=memoryPreferences();p.edit().putString("deviceId","local-device").commit()
    val s=store(p,secret);s.save(profile("https://server.example").copy(pluginSecret="push-key"),"password")
    fun payload(device:String,seq:String):Map<String,String> {
      val data=mapOf("version" to "3","sessionId" to "session","serverId" to "srv","directory" to "/repo","phase" to "COMPLETED","detail" to "done","title" to "Task","deviceId" to device,"sequence" to seq,"ts" to System.currentTimeMillis().toString())
      return data+("sig" to PushMessageVerifier.sign("push-key",data))
    }
    assertFalse(PushMessageVerifier.verify(s,"srv",payload("other-device","1")))
    assertTrue(PushMessageVerifier.verify(s,"srv",payload("local-device","2")))
    val restored=store(p,secret)
    assertFalse(PushMessageVerifier.verify(restored,"srv",payload("local-device","2")))
    assertFalse(PushMessageVerifier.verify(restored,"srv",payload("local-device","1")))
    assertTrue(PushMessageVerifier.verify(restored,"srv",payload("local-device","3")))
    assertFalse(PushMessageVerifier.verify(restored,"srv",payload("local-device","4")+("version" to "2")))
    s.save(profile("https://server.example").copy(pluginSecret=""),null)
    assertFalse(PushMessageVerifier.verify(s,"srv",payload("local-device","5")))
  }
  @Test fun deletionDuringEncryptionCannotResurrectANewCacheKey()=runBlocking {
    val p=memoryPreferences();val entered=CountDownLatch(1);val release=CountDownLatch(1)
    val cache=OfflineCache(p,{entered.countDown();check(release.await(3,TimeUnit.SECONDS));it},{it})
    cache.saveMessages("server","session",listOf(Message("m","user",0,emptyList())))
    assertTrue(entered.await(2,TimeUnit.SECONDS));cache.delete("server");release.countDown();cache.awaitWrites()
    assertFalse(p.contains("messages:server:session"))
  }
  @Test fun newerWriteWinsWhenOldEncryptionFinishesLate()=runBlocking {
    val p=memoryPreferences();val entered=CountDownLatch(1);val release=CountDownLatch(1);var first=true
    val cache=OfflineCache(p,{if(first){first=false;entered.countDown();check(release.await(3,TimeUnit.SECONDS))};it},{it})
    cache.saveMessages("s","t",listOf(Message("old","user",0,emptyList())))
    assertTrue(entered.await(2,TimeUnit.SECONDS));cache.deleteMessages("s","t")
    cache.saveMessages("s","t",listOf(Message("new","user",0,emptyList())))
    release.countDown();cache.awaitWrites();assertEquals("new",cache.messages("s","t").single().id)
  }
  private fun api(s:MockWebServer)=OpenCodeApi(profile(s.url("/").toString()),"fixture")
  private suspend fun currentApi(s:MockWebServer):OpenCodeApi {
    s.enqueue(MockResponse().setResponseCode(404));s.enqueue(MockResponse().setResponseCode(404));s.enqueue(MockResponse().setBody("{}"))
    return api(s).also{it.health();repeat(3){s.takeRequest()}}
  }
  @Test fun currentV2UsesTextDecisionAndFormContractsWithoutWriteRetry()=runBlocking {
    MockWebServer().use { s ->
      val api=currentApi(s)
      s.enqueue(MockResponse().setBody("{}"));api.send(Session("ses_a","/repo","T",0),"hello",null,null)
      assertEquals("hello",JSONObject(s.takeRequest().body.readUtf8()).getString("text"))
      s.enqueue(MockResponse().setBody("""{"data":[{"id":"per_a","sessionID":"ses_a","action":"bash","resources":["ls"],"save":["ls *"],"source":{"type":"tool","messageID":"msg","id":"call"}}]}"""))
      val permission=api.permissions("/repo").single();s.takeRequest()
      assertEquals(listOf("ls *"),permission.always);assertEquals("call",permission.toolCallId)
      s.enqueue(MockResponse().setResponseCode(204));api.replyPermission(permission,"always")
      assertEquals("always",JSONObject(s.takeRequest().body.readUtf8()).getString("decision"))
      s.enqueue(MockResponse().setBody("""{"data":[{"id":"frm_a","sessionID":"ses_a","title":"Config","fields":[{"key":"enabled","type":"boolean","required":true}]}]}"""))
      val form=api.questions("/repo").single();assertTrue(form.form);assertEquals("/api/form",s.takeRequest().requestUrl!!.encodedPath)
      s.enqueue(MockResponse().setResponseCode(204));api.replyQuestion(form,listOf(listOf("true")))
      val reply=s.takeRequest();assertEquals("/api/session/ses_a/form/frm_a/reply",reply.requestUrl!!.encodedPath)
      assertTrue(JSONObject(reply.body.readUtf8()).getJSONObject("answer").getBoolean("enabled"))
      s.enqueue(MockResponse().setResponseCode(204));api.rejectQuestion(form);assertEquals("DELETE",s.takeRequest().method)
      s.enqueue(MockResponse().setResponseCode(400));assertTrue(runCatching{api.send(Session("ses_a","/repo","T",0),"bad",null,null)}.isFailure)
      s.takeRequest();assertNull(s.takeRequest(100,TimeUnit.MILLISECONDS))
    }
  }
  @Test fun legacyPermissionsCannotSaveAnUnverifiedScope()=runBlocking {
    MockWebServer().use { s ->
      s.enqueue(MockResponse().setBody("""{"healthy":true}"""));val api=api(s);api.health();s.takeRequest()
      assertTrue(runCatching{api.replyPermission(PermissionRequest("p","s","/repo","bash","ls",listOf("*")),"always")}.isFailure)
      assertNull(s.takeRequest(100,TimeUnit.MILLISECONDS))
    }
  }
  @Test fun missingPermissionEndpointIsAnErrorNotAuthoritativeEmpty()=runBlocking {
    MockWebServer().use { s -> val api=currentApi(s);s.enqueue(MockResponse().setResponseCode(404));assertTrue(runCatching{api.permissions("/repo")}.isFailure) }
  }
  @Test fun formValuesPreserveOptionValuesTypesAndVisibility() {
    val form=JSONObject("""{"id":"frm","sessionID":"s","fields":[{"key":"mode","type":"string","options":[{"value":"fast","label":"快速"}]},{"key":"count","type":"integer","minimum":1,"maximum":3,"required":true},{"key":"hidden","type":"string","required":true,"when":[{"key":"mode","op":"eq","value":"slow"}]}]}""").toForm("/repo")
    assertEquals("fast",form.questions[0].options.single().value)
    val answer=formAnswer(form,listOf(listOf("fast"),listOf("2"),emptyList()))
    assertEquals(2,answer.getInt("count"));assertFalse(answer.has("hidden"))
    assertTrue(runCatching{formAnswer(form,listOf(listOf("fast"),listOf("2.5"),emptyList()))}.isFailure)
  }
  @Test fun islandVendorSwitchesPersistWithoutTouchingCredentials() {
    val p=memoryPreferences();val sec=memoryPreferences();val store=store(p,sec)
    val original=ServerProfile("srv","Server","https://x",username="opencode")
    store.save(original,"secret-password",credentialUsername="opencode")
    store.updateIslandVendor(original.copy(islandHonor=true,islandOppoFluidCloud=true))
    val reloaded=store.profiles().single()
    assertTrue(reloaded.islandHonor);assertTrue(reloaded.islandOppoFluidCloud)
    assertEquals("secret-password",store.credentials("srv").password)
    assertEquals("https://x",reloaded.url)
    // Values survive a full round-trip through the persisted JSON read by a fresh store instance.
    val fresh=ServerStore(p,sec,{"encrypted:$it"},{require(it.startsWith("encrypted:"));it.removePrefix("encrypted:")})
    assertTrue(fresh.profiles().single().islandHonor)
  }
  @Test fun islandVendorSwitchesDefaultOffAndRoundTrip() {
    val store=store();store.save(profile("https://x"),"p",credentialUsername="opencode")
    val p=store.profiles().single()
    assertFalse(p.islandHonor);assertFalse(p.islandOppoFluidCloud)
  }
  @Test fun backgroundPhasesTrackCountsAndExpireTerminalStates() {
    val store=store();val now=System.currentTimeMillis()
    store.recordPushPhase("srv","run", TaskPhase.THINKING.name, now)
    store.recordPushPhase("srv","done", TaskPhase.COMPLETED.name, now)
    store.recordPushPhase("srv","ask", TaskPhase.WAITING_QUESTION.name, now)
    store.recordPushPhase("srv","boom", TaskPhase.FAILED.name, now)
    assertEquals(TaskSummary(running=1, completed=1, waiting=1, failed=1), TaskSummary.fromPhaseNames(store.pushPhases("srv")))
    // A read terminal result stops counting; a different server's phases are isolated.
    store.acknowledgeTask("srv","done");store.acknowledgeTask("srv","boom")
    assertEquals(TaskSummary(running=1, completed=0, waiting=1, failed=0),
      TaskSummary.fromPhaseNames(store.pushPhases("srv"), store.acknowledgedTasks("srv")))
    assertEquals(TaskSummary.EMPTY, TaskSummary.fromPhaseNames(store.pushPhases("other")))
  }
  @Test fun reCompletionMakesAReadResultUnreadAgain() {
    val store=store();val now=System.currentTimeMillis()
    store.recordPushPhase("srv","s", TaskPhase.THINKING.name, now)
    store.recordPushPhase("srv","s", TaskPhase.COMPLETED.name, now)
    store.acknowledgeTask("srv","s")
    assertTrue(store.acknowledgedTasks("srv").contains("s"))
    // A new run that reaches a terminal state again must be counted as unread.
    store.recordPushPhase("srv","s", TaskPhase.THINKING.name, now + 1)
    store.recordPushPhase("srv","s", TaskPhase.COMPLETED.name, now + 2)
    assertFalse(store.acknowledgedTasks("srv").contains("s"))
  }
  @Test fun terminalTransitionFromIdleKeepsReadFlag() {
    val store=store();val now=System.currentTimeMillis()
    store.recordPushPhase("srv","s", TaskPhase.COMPLETED.name, now)
    store.acknowledgeTask("srv","s")
    // Re-reporting the same terminal phase (a duplicate push) must not resurrect an unread count.
    store.recordPushPhase("srv","s", TaskPhase.COMPLETED.name, now + 1)
    assertTrue(store.acknowledgedTasks("srv").contains("s"))
  }
  @Test fun fromPhaseNamesIgnoresUnknownPhases() {
    val summary = TaskSummary.fromPhaseNames(mapOf("a" to "THINKING", "b" to "COMPLETED", "c" to "NOT_A_PHASE", "d" to ""))
    assertEquals(TaskSummary(running=1, completed=1), summary)
  }
  @Test fun repeatedCursorFailsInsteadOfReturningPartialAuthoritativeData()=runBlocking {
    MockWebServer().use { s ->
      val api=currentApi(s);repeat(2){s.enqueue(MockResponse().setBody("""{"data":[],"cursor":{"next":"same"}}"""))}
      assertTrue(runCatching{api.sessions("/repo")}.exceptionOrNull() is IOException)
    }
  }
  @Test fun sseOverflowTriggersObservableFailure()=runBlocking {
    MockWebServer().use { s ->
      s.enqueue(MockResponse().setBody("""{"healthy":true}"""))
      s.enqueue(MockResponse().addHeader("Content-Type","text/event-stream").setBody(buildString{repeat(201){append("id: $it\ndata: {\"type\":\"session.status\",\"properties\":{\"sessionID\":\"s\",\"status\":{\"type\":\"busy\"}}}\n\n")}}))
      val api=api(s);api.health();val failure=runCatching{withTimeout(5000){api.events().collect{delay(10)}}}.exceptionOrNull()
      assertTrue(failure is IOException)
    }
  }
}
