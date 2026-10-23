package com.tanhaowen.contextenglish.adaptive

import android.os.Looper
import androidx.lifecycle.ViewModelStore
import com.tanhaowen.contextenglish.ContextEnglishApp
import com.tanhaowen.contextenglish.ai.*
import com.tanhaowen.contextenglish.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

/** Real ViewModel + SQLite + snapshots + grading. Only the external AI provider is a test fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=ContextEnglishApp::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AuditViewModelIntegrationTest {
    private lateinit var app: ContextEnglishApp
    private lateinit var vm: AdaptiveViewModel
    private var store=ViewModelStore()
    private var day=LocalDate.now()
    private var now=0L
    private var calls=0
    private var offline=true
    private var malformed=false
    private var holdRequest=false
    @Volatile private var waiting=false
    @Volatile private var lastPrompt=""
    private val settings=AiSettings(apiKey="test-fixture-never-transmitted",dailyModel="requested",inputPricePerMillion=2.0,outputPricePerMillion=8.0)
    private val client=object: AiClient() {
        override suspend fun generate(settings: AiSettings,prompt: String,maxTokens: Int,canStart: ()->Boolean): Result<AiCallResult> = withContext(Dispatchers.IO) {
            calls++
            lastPrompt=prompt
            if(offline) return@withContext Result.failure(java.net.UnknownHostException("offline fixture"))
            if(holdRequest) { waiting=true;awaitCancellation() }
            val content=if(malformed) "{\"wrong\":true}" else response(prompt)
            Result.success(AiCallResult(content,TokenUsage(100,20,totalTokens=120,known=true),"actual-upstream"))
        }
    }
    @Before fun setup() {
        app=RuntimeEnvironment.getApplication() as ContextEnglishApp;app.repository.database.close();app.deleteDatabase("context_english.db")
        day=LocalDate.now();now=day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()+12*3_600_000L
    }
    @After fun cleanup() {
        store.clear();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));app.repository.database.close()
    }
    private fun launch(config: AiSettings=settings) {
        vm=AdaptiveViewModel(app,AiTaskScheduler(app.repository,client),{ day },{ now },{ config })
        store.put("vm",vm);ready()
    }
    private fun until(condition: ()->Boolean) {
        val deadline=System.nanoTime()+15_000_000_000
        while(!condition() && System.nanoTime()<deadline) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5));Thread.sleep(2) }
        assertTrue("Timed out; message=${if(::vm.isInitialized) vm.state.value.message else "startup"}",condition())
    }
    private fun ready() { until { !vm.state.value.busy && vm.state.value.workflow!=null } }
    private fun aiDone() { until { !vm.state.value.aiBusy } }
    private fun response(prompt: String): String {
        if(prompt.contains("真实作答索引/拼写及自报错因")) {
            val reading=LocalContent.readings.first { prompt.contains(it.passage) }
            return JSONObject().put("explanation","根据已记录的真实选择与自报错因解释证据定位步骤，不推断未记录的个人原因。")
                .put("suggestions",JSONArray(listOf("回原文核对对应证据"))).put("evidence",reading.passage.substringBefore('.')+".").toString()
        }
        if(prompt.contains("\"analysis\"")) {
            val reading=LocalContent.readings.first { prompt.contains(it.passage) }
            val quote=reading.passage.substringBefore('.')+"."
            return JSONObject().put("explanation","这是一份依据给定文章与题目生成的完整结构化解析。").put("suggestions",JSONArray(listOf("先回原文定位证据")))
                .put("analysis",JSONArray().also { a -> reading.questions.forEach { q -> a.put(JSONObject().put("id",q.id).put("evidence",quote).put("reason","结合该题定位到原文证据，排除扩大范围和无依据推断的选项。")) } }).toString()
        }
        if(prompt.contains("\"mainClause\"")) return JSONObject().put("sentence",prompt.substringAfter("原句：").trim()).put("mainClause","先定位主语和谓语，再确认宾语及修饰部分。")
            .put("clauses",JSONArray(listOf("从句修饰关系根据指定原句逐一说明"))).put("translation","这是测试契约中的完整中文译文。").toString()
        if(prompt.contains("\"meaning\"" ) && prompt.contains("\"evidence\"")) {
            val word=Regex("^词：(.*)$",RegexOption.MULTILINE).find(prompt)!!.groupValues[1]
            val passage=prompt.substringAfter("原文：")
            val hit=Regex("(?i)(?<![a-z])${Regex.escape(word)}(?![a-z])").find(passage)!!
            val quote=passage.substring((hit.range.first-25).coerceAtLeast(0),(hit.range.last+35).coerceAtMost(passage.length))
            return JSONObject().put("word",word).put("meaning","当前语境中的含义").put("evidence",quote).put("reason","根据目标词前后的真实原文线索判断此处词义。").toString()
        }
        if(prompt.contains("\"questions\"")) {
            val target=prompt.substringAfter("本地确定的训练重点=").substringBefore('，')
            val pool=app.repository.loadWords()
            val ids=Regex("id=(\\d+);").findAll(prompt).map { it.groupValues[1].toLong() }.toList()
            val questions=if(GrammarBank.lessons.any { it.tag==target }) GrammarBank.lessons.first { it.tag==target }.questions.take(3)
                else ids.take(3).mapIndexed { i,id -> LocalContent.vocabulary(pool.first { it.id==id },pool,if(target=="拼写") "spelling" else if(target=="语境选词") "context" else "meaning","fixture-$i") }
            return JSONObject().put("questions",JSONArray().also { a -> questions.forEach { q -> a.put(JSONObject().put("prompt",q.prompt).put("options",JSONArray(q.options))
                .put("answer",q.answer).put("explanation",q.explanation+"；核对题干和答案依据。").put("form",q.form).put("tag",q.tag.ifBlank { target }).put("wordId",q.wordId).put("spelling",q.spelling).put("context",if(q.form=="context") q.prompt else "")) } }).toString()
        }
        return JSONObject().put("explanation","依据真实本地记录解释知识点并提出可操作的复习步骤。").put("suggestions",JSONArray(listOf("继续本地训练"))).toString()
    }
    private fun finishAll(wrong: Boolean=true) {
        vm.start();ready();var safety=0
        while(!vm.state.value.workflow!!.finished && safety++<400) {
            val w=vm.state.value.workflow!!;val q=w.step!!.question
            when {
                q==null -> { if(!w.revealed) { vm.reveal();ready() };vm.rate(2);ready() }
                w.feedback.isBlank() -> { vm.answer(if(q.spelling.isNotBlank()) if(wrong) "__wrong__" else q.spelling else (if(wrong) (q.answer+1)%4 else q.answer).toString());ready() }
                else -> { vm.next();ready() }
            }
        }
        assertTrue(vm.state.value.workflow!!.finished)
    }
    @Test fun actualViewModelCompletesOfflineAndAutomaticallyCreatesAdaptiveTomorrow() {
        launch(AiSettings());val initial=vm.state.value.workflow!!.newIds.size
        vm.mode(Intensity.INTENSIVE);ready();finishAll()
        val tomorrow=vm.state.value.tomorrow!!
        assertEquals(0,calls);assertEquals(0L,app.repository.aiUsageSummary().calls)
        assertTrue(tomorrow.newIds.size<initial);assertTrue(tomorrow.reviewIds.isNotEmpty())
        assertTrue(tomorrow.grammarTag.isNotBlank());assertTrue(tomorrow.steps.count { it.phase=="语法专项" } in 3..5)
        assertEquals(tomorrow,AdaptiveRepository(app.repository).load(day.plusDays(1).toString()))
    }
    @Test fun processRecreationRestoresRevealedWordAndPreventsDoubleGrading() {
        launch();vm.start();ready();vm.reveal();ready()
        val before=vm.state.value.workflow!!
        vm.pause();store.clear();store=ViewModelStore();launch()
        assertEquals(before.step!!.key,vm.state.value.workflow!!.step!!.key)
        assertTrue(vm.state.value.workflow!!.revealed)
        vm.start();ready();vm.rate(2);ready()
        val after=vm.state.value.workflow!!;val word=app.repository.loadWord(before.step!!.wordId)!!
        assertEquals(before.index+1,after.index);assertEquals(1,word.seenCount)
        assertEquals(after,AdaptiveRepository(app.repository).load(day.toString()))
    }
    @Test fun preparationIsManualDeduplicatedAndNextDayReadsCacheWithoutNetwork() {
        launch();assertEquals(0,calls);offline=false
        vm.prepareTomorrow();aiDone()
        assertTrue("${vm.state.value.aiContent}\n${vm.state.value.aiError}",vm.state.value.aiError.isBlank())
        val sent=calls;assertTrue(sent>=3);assertEquals(sent.toLong(),app.repository.aiUsageSummary().calls)
        vm.prepareTomorrow();aiDone();assertEquals(sent,calls)
        offline=true;day=day.plusDays(1);now=day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        vm.foreground(false);vm.foreground(true);ready()
        assertEquals(day.toString(),vm.state.value.workflow!!.day)
        assertTrue(vm.state.value.materials.containsKey("reading-analysis-v2"));assertTrue(vm.state.value.materials.containsKey("sentence-structure-v2"))
        assertEquals(sent,calls)
    }
    @Test fun invalidAiResponseLeavesLocalPlanUsableAndCannotEnterCache() {
        launch();val before=vm.state.value.workflow!!;offline=false;malformed=true
        vm.enhance("sentence");aiDone()
        assertTrue(vm.state.value.aiError.isNotBlank());assertEquals(before,vm.state.value.workflow)
        assertEquals(1,calls);assertEquals("INVALID_CONTENT",app.repository.aiUsageRecords().single().status)
        vm.start();ready();vm.reveal();ready();vm.rate(0);ready()
        assertEquals(before.index+1,vm.state.value.workflow!!.index)
    }
    @Test fun coreLearningKeepsWorkingDuringAnInFlightAiRequestAndCancellation() {
        launch();offline=false;holdRequest=true
        vm.enhance("sentence");until { waiting }
        assertTrue(vm.state.value.aiBusy)
        vm.start();ready();vm.reveal();ready();vm.rate(2);ready()
        assertEquals(1,vm.state.value.workflow!!.index)
        assertTrue("Core grading must not wait for AI",vm.state.value.aiBusy)
        vm.cancelAi();aiDone()
        until { app.repository.aiUsageRecords().firstOrNull()?.status=="CANCELLED_USAGE_UNKNOWN" }
        assertFalse(app.repository.aiUsageRecords().single().usage.known)
        assertEquals(1,calls)
    }
    @Test fun wrongReadingChoiceAndReasonSurviveRestartAndReachTheAiPrompt() {
        launch()
        val original=vm.state.value.workflow!!
        val readingIndex=original.steps.indexOfFirst { it.phase=="阅读" }
        AdaptiveRepository(app.repository).save(original.copy(index=readingIndex,started=true))
        vm.reload();ready();vm.start();ready()
        val q=vm.state.value.workflow!!.step!!.question!!
        val wrong=((q.answer+1)%q.options.size).toString()
        vm.answer(wrong);ready();vm.errorReason("证据定位");ready();vm.pause()
        store.clear();store=ViewModelStore();launch()
        val restored=vm.state.value.workflow!!
        assertEquals(wrong,restored.selectedAnswer);assertFalse(restored.correct)
        val recorded=AdaptiveRepository(app.repository).attemptContext(restored.step!!.key)
        assertEquals(wrong,recorded.getString("selectedAnswer"));assertEquals("证据定位",recorded.getString("reportedReason"))
        offline=false;vm.explainCurrent();aiDone()
        assertTrue(vm.state.value.aiError.isBlank());assertTrue(lastPrompt.contains("证据定位"))
        assertTrue(lastPrompt.contains("\"selectedAnswer\":\"$wrong\""))
    }
    @Test fun generatedQuestionsAreActuallyAnsweredPersistedAndChangeDiagnosis() {
        val db=app.repository.database.writableDatabase
        repeat(3) { i -> db.execSQL("INSERT INTO diagnostic_attempts(task_key,day,phase,form,tag,word_id,correct,elapsed_ms,context,created_at) VALUES(?,?,'fixture','grammar','时态',0,0,1000,'',?)",arrayOf("prior-$i",day.minusDays(1).toString(),now-86_400_000L)) }
        launch();offline=false
        vm.enhance("practice");aiDone();assertEquals(3,vm.state.value.pendingQuestions.size)
        val original=vm.state.value.workflow!!
        val finished=original.copy(index=original.steps.size,started=true)
        AdaptiveRepository(app.repository).save(finished);vm.reload();ready()
        vm.addPractice();ready();assertEquals(3,vm.state.value.workflow!!.steps.size-finished.steps.size)
        finishAll()
        val tense=AdaptiveRepository(app.repository).skills().first { it.tag=="时态" }
        assertEquals(6,tense.total);assertEquals(0,tense.correct)
        assertEquals("时态",vm.state.value.tomorrow!!.grammarTag)
        assertTrue(app.repository.aiUsageRecords().all { it.model=="actual-upstream" })
    }
}
