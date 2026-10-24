package com.tanhaowen.contextenglish.ai

import com.tanhaowen.contextenglish.ContextEnglishApp
import com.tanhaowen.contextenglish.adaptive.*
import com.tanhaowen.contextenglish.data.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory
import java.time.LocalDate

/** Real TLS sockets + unmodified production AiClient + SQLite scheduler. No paid provider or Release key. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=ContextEnglishApp::class)
class AiHttpsIntegrationTest {
    private lateinit var server: MockWebServer
    private lateinit var originalTls: SSLSocketFactory
    private lateinit var helper: EnglishDatabase
    private lateinit var repo: EnglishRepository
    private lateinit var scheduler: AiTaskScheduler
    private lateinit var settings: AiSettings
    @Before fun setup() {
        // Ephemeral in-memory TLS identity only. Never reads/writes an Android Release keystore.
        val certificate=HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls=HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls=HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        originalTls=HttpsURLConnection.getDefaultSSLSocketFactory()
        HttpsURLConnection.setDefaultSSLSocketFactory(clientTls.sslSocketFactory())
        server=MockWebServer();server.useHttps(serverTls.sslSocketFactory(),false);server.start()
        val context=RuntimeEnvironment.getApplication()
        context.deleteDatabase("context_english.db")
        helper=EnglishDatabase(context);repo=EnglishRepository(helper);repo.loadWords()
        scheduler=AiTaskScheduler(repo,AiClient())
        settings=AiSettings(apiKey="local-test-fixture",baseUrl=server.url("/v1").toString(),dailyModel="requested",
            inputPricePerMillion=2.0,outputPricePerMillion=8.0,cacheReadPricePerMillion=.2)
    }
    @After fun cleanup() {
        HttpsURLConnection.setDefaultSSLSocketFactory(originalTls)
        server.shutdown();helper.close()
    }
    private fun response(): String {
        val content=JSONObject().put("explanation","这是来自本地HTTPS边界的结构化解析，必须经过真实生产解析与校验。")
            .put("suggestions",org.json.JSONArray(listOf("先定位证据"))).toString()
        return JSONObject().put("model","wire-returned-model").put("choices",org.json.JSONArray().put(
            JSONObject().put("message",JSONObject().put("content",content))))
            .put("usage",JSONObject().put("prompt_tokens",100).put("completion_tokens",20)
                .put("prompt_tokens_details",JSONObject().put("cached_tokens",40))).toString()
    }
    @Test fun realHttpsRequestValidatesPayloadPersistsUsageAndCacheAvoidsASecondSocketRequest()=runBlocking {
        server.enqueue(MockResponse().setBody(response()).setHeader("Content-Type","application/json"))
        val result=scheduler.request(settings,"wire-analysis","指定原文解析",validate=AiTaskScheduler::validateExplanation).getOrThrow()
        val request=server.takeRequest(3,TimeUnit.SECONDS)!!
        assertEquals("/v1/chat/completions",request.path)
        assertEquals("Bearer local-test-fixture",request.getHeader("Authorization"))
        val payload=JSONObject(request.body.readUtf8())
        assertEquals("requested",payload.getString("model"));assertFalse(payload.getBoolean("stream"))
        assertEquals("指定原文解析",payload.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals("wire-returned-model",result.model)
        val usage=repo.aiUsageRecord(result.usageId!!)!!
        assertTrue(usage.usage.known);assertEquals(100L,usage.usage.promptTokens);assertEquals(40L,usage.usage.cacheReadTokens)
        assertEquals(.000288,usage.cost.total,1e-12)
        val cached=scheduler.request(settings,"wire-analysis","指定原文解析",validate=AiTaskScheduler::validateExplanation).getOrThrow()
        assertTrue(cached.fromCache);assertEquals(result.content,cached.content);assertEquals(1,server.requestCount)
        assertEquals(1,repo.aiUsageSummary().calls)
    }
    @Test fun httpErrorAndMalformedBodyCannotEnterCacheOrPreventAnOfflineAnswer()=runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setBody("{\"error\":{\"code\":\"rate_limit\"}}"))
        val error=scheduler.request(settings,"wire-error","retry manually").exceptionOrNull()
        assertTrue(error is AiHttpException && error.status==429)
        assertNull(scheduler.cached(settings,"wire-error","retry manually"))
        server.enqueue(MockResponse().setBody("not a JSON response"))
        assertTrue(scheduler.request(settings,"wire-invalid","malformed").isFailure)
        assertNull(scheduler.cached(settings,"wire-invalid","malformed"));assertEquals(2,server.requestCount)
        val adaptive=AdaptiveRepository(repo);val words=repo.loadWords()
        val w=WorkflowFactory.create(LocalDate.now().toString(),Intensity.QUICK,15,words,RecentPerformance(),emptyList(),System.currentTimeMillis())
        adaptive.save(w);adaptive.answer(w,w.copy(index=1),true,500,2)
        assertEquals(1,adaptive.load(w.day)!!.index)
        assertEquals(1,repo.loadWord(w.step!!.wordId)!!.seenCount)
    }
    @Test fun cancellingARealPendingHttpsRequestRecordsUnknownUsageAndAllowsManualRetry()=runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending=async(Dispatchers.Default) { scheduler.request(settings,"wire-cancel","cancel this") }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5,TimeUnit.SECONDS) })
        withTimeout(3000) { pending.cancelAndJoin() }
        assertNull(scheduler.cached(settings,"wire-cancel","cancel this"))
        helper.readableDatabase.rawQuery("SELECT request_status,usage_known FROM ai_usage",null).use {
            assertTrue(it.moveToFirst());assertEquals("CANCELLED_USAGE_UNKNOWN",it.getString(0));assertEquals(0,it.getInt(1))
        }
        server.enqueue(MockResponse().setBody(response()))
        assertTrue(scheduler.request(settings,"wire-cancel","cancel this",validate=AiTaskScheduler::validateExplanation).isSuccess)
        assertEquals(2,server.requestCount)
    }
}
