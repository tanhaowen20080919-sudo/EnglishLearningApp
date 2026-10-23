package com.tanhaowen.contextenglish.adaptive

import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.tanhaowen.contextenglish.ContextEnglishApp
import com.tanhaowen.contextenglish.ai.*
import com.tanhaowen.contextenglish.data.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=ContextEnglishApp::class)
class AiSchedulerTest {
    private lateinit var helper: EnglishDatabase
    private lateinit var repo: EnglishRepository
    private val settings=AiSettings(apiKey="test-only",dailyModel="requested",inputPricePerMillion=2.0,outputPricePerMillion=8.0)
    private var calls=0
    private var content="{\"explanation\":\"这是一段有效的中文解析，给出具体证据。\",\"suggestions\":[\"继续本地训练\"]}"
    private val client=object: AiClient() {
        override suspend fun generate(settings: AiSettings,prompt: String,maxTokens: Int,canStart: ()->Boolean): Result<AiCallResult> {
            calls++
            return Result.success(AiCallResult(content,TokenUsage(100,20,totalTokens=120,known=true),"actual-upstream"))
        }
    }
    @Before fun setup() {
        val c=RuntimeEnvironment.getApplication();c.deleteDatabase("context_english.db")
        helper=EnglishDatabase(c);repo=EnglishRepository(helper)
    }
    @After fun cleanup() { helper.close() }
    @Test fun queueAndLocalStudyNeverInitiateRequests() = runBlocking {
        val scheduler=AiTaskScheduler(repo,client)
        scheduler.queue(settings,"reading","prompt")
        repo.loadWords();AdaptiveRepository(repo).summary()
        assertEquals(0,calls);assertEquals(0,repo.aiUsageSummary().calls)
    }
    @Test fun cacheHitAvoidsNetworkAndDoesNotChargeAgain() = runBlocking {
        val scheduler=AiTaskScheduler(repo,client)
        val a=scheduler.request(settings,"reading","prompt",validate=AiTaskScheduler::validateExplanation).getOrThrow()
        val b=scheduler.request(settings,"reading","prompt",validate=AiTaskScheduler::validateExplanation).getOrThrow()
        assertFalse(a.fromCache);assertTrue(b.fromCache);assertEquals(1,calls)
        assertEquals(1,repo.aiUsageSummary().calls);assertEquals("actual-upstream",repo.aiUsageRecords().single().model)
    }
    @Test fun changedInputModelAndExpiryInvalidateCache() = runBlocking {
        val scheduler=AiTaskScheduler(repo,client)
        scheduler.generate(settings,"first").getOrThrow()
        scheduler.generate(settings,"second").getOrThrow()
        scheduler.generate(settings.copy(dailyModel="other"),"first").getOrThrow()
        helper.writableDatabase.execSQL("UPDATE ai_tasks SET expires_at=0")
        scheduler.generate(settings,"first").getOrThrow()
        assertEquals(4,calls)
    }
    @Test fun invalidJsonNeverBecomesCacheAndUsageStillRecorded() = runBlocking {
        val scheduler=AiTaskScheduler(repo,client);content="```json\n{\"bad\":1}\n```"
        assertTrue(scheduler.request(settings,"json","prompt",validate=AiTaskScheduler::validateExplanation).isFailure)
        assertNull(scheduler.cached(settings,"json","prompt"))
        assertEquals(1,repo.aiUsageSummary().calls)
        content="{\"explanation\":\"重试后返回的完整有效中文解析。\",\"suggestions\":[\"练习\"]}"
        assertTrue(scheduler.request(settings,"json","prompt",validate=AiTaskScheduler::validateExplanation).isSuccess)
        assertEquals(2,calls)
    }
    @Test fun cancellationIsRecordedWithUnknownUsageAndCanRetryManually() = runBlocking {
        val blocked=object: AiClient() {
            override suspend fun generate(settings: AiSettings,prompt: String,maxTokens: Int,canStart: ()->Boolean): Result<AiCallResult> {
                delay(10000);return Result.failure(Exception("not reached"))
            }
        }
        val scheduler=AiTaskScheduler(repo,blocked)
        val job=launch { scheduler.generate(settings,"cancel me") }
        // Wait until the queue records the in-flight request; no actual network is used.
        withTimeout(5000) { while(helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM ai_tasks WHERE status='RUNNING'",null).use { it.moveToFirst();it.getInt(0) }==0) delay(10) }
        job.cancelAndJoin()
        val record=repo.aiUsageRecords().single()
        assertFalse(record.usage.known);assertEquals("CANCELLED_USAGE_UNKNOWN",record.status)
    }
    @Test fun missingConfigurationDoesNotCreateFictionalRequests() = runBlocking {
        val result=AiTaskScheduler(repo,client).generate(settings.copy(apiKey=""),"not sent")
        assertTrue(result.isFailure);assertEquals(0,calls);assertEquals(0,repo.aiUsageSummary().calls)
    }
    @Test fun disabledStartGuardPreventsRequestAndCharge() = runBlocking {
        val result=AiTaskScheduler(repo,client).generate(settings,"blocked",canStart={ false })
        assertTrue(result.exceptionOrNull() is AiStartSkippedException)
        assertEquals(0,calls);assertEquals(0,repo.aiUsageSummary().calls)
    }
}
