package com.tanhaowen.contextenglish.adaptive

import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.tanhaowen.contextenglish.ContextEnglishApp
import com.tanhaowen.contextenglish.ai.*
import com.tanhaowen.contextenglish.data.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=ContextEnglishApp::class)
class AuditPersistenceTest {
    private lateinit var helper: EnglishDatabase
    private lateinit var repo: EnglishRepository
    private lateinit var adaptive: AdaptiveRepository
    @Before fun setup() {
        val c: Context=RuntimeEnvironment.getApplication();c.deleteDatabase("context_english.db")
        helper=EnglishDatabase(c);repo=EnglishRepository(helper);adaptive=AdaptiveRepository(repo)
        repo.loadWords();OfflineWordResources.load(c)
    }
    @After fun cleanup() { helper.close() }
    private fun attempt(day: LocalDate,reading: ReadingLesson,correct: Boolean) {
        val steps=reading.questions.map { LearningStep("$day:${it.id}","阅读",question=it) }
        var w=DailyWorkflow(day.toString(),Intensity.STANDARD,45,"",emptyList(),emptyList(),reading.id,"",steps,started=true)
        adaptive.save(w)
        while(!w.finished) { val after=w.copy(index=w.index+1);adaptive.answer(w,after,correct,1000);w=after }
    }
    @Test fun lastSevenArticlesAndLifetimeAccuracyAreDifferentRealAggregations() {
        val today=LocalDate.now()
        repeat(10) { i -> attempt(today.minusDays((9-i).toLong()),LocalContent.readings[i%LocalContent.readings.size],i>=3) }
        val recent=adaptive.skills(today).first { it.tag=="推断题" }
        val all=adaptive.skills(today,lifetime=true).first { it.tag=="推断题" }
        assertEquals(7,recent.total);assertEquals(7,recent.correct)
        assertEquals(10,all.total);assertEquals(7,all.correct)
        assertEquals(10,adaptive.summary().getInt("readingArticles"))
    }
    @Test fun twoArticlesOnOneDayAreCountedSeparatelyAndLookupsAreArticleSpecific() {
        val day=LocalDate.now();val a=LocalContent.readings[0];val b=LocalContent.readings[1]
        attempt(day,a,false);attempt(day,b,true)
        assertEquals(2,adaptive.summary().getInt("readingArticles"))
        adaptive.readingWord(day.toString(),a.id,"seed","种子",true)
        adaptive.readingWord(day.toString(),b.id,"wire","电线",false)
        assertEquals(setOf("seed"),adaptive.lookedUp(day.toString(),a.id).keys)
        assertEquals(setOf("wire"),adaptive.lookedUp(day.toString(),b.id).keys)
        assertEquals(2,adaptive.skills().first { it.tag=="推断题" }.total)
    }
    @Test fun adaptiveEvidenceSurvivesReopenAndEveryWordHasDifferentOfflineContexts() {
        val word=repo.loadWords().first { it.word=="represent" }
        adaptive.applyEvidence(word.id,Evidence(0,"spelling"))
        val before=adaptive.memory(repo.loadWord(word.id)!!)
        helper.close();helper=EnglishDatabase(RuntimeEnvironment.getApplication());repo=EnglishRepository(helper);adaptive=AdaptiveRepository(repo)
        assertEquals(before,adaptive.memory(repo.loadWord(word.id)!!))
        val pool=repo.loadWords()
        pool.forEach { w ->
            assertTrue("${w.word} must have at least two actual contexts",LocalContent.contexts(w).size>=2)
        }
        val variants=(1..25).map { LocalContent.vocabulary(word,pool,"context","${word.id}:$it") }
        assertTrue(variants.map { it.context }.distinct().size>=2)
        assertTrue(variants.all { it.form=="context" && it.answerText==word.word })
    }
    @Test fun clockCheckpointCannotOverwriteNewerAnswerAndFeedback() {
        val w=WorkflowFactory.create(LocalDate.now().toString(),Intensity.QUICK,15,repo.loadWords(),RecentPerformance(),emptyList(),System.currentTimeMillis())
        adaptive.save(w);adaptive.saveClock(w.day,w.step!!.key,2300)
        assertEquals(2300L,adaptive.load(w.day)!!.stepElapsedMs)
        val after=w.copy(index=1)
        adaptive.answer(w,after,true,3500,2)
        adaptive.saveClock(w.day,w.step!!.key,9000)
        assertEquals(after,adaptive.load(w.day))
        helper.readableDatabase.rawQuery("SELECT active_ms FROM learning_days WHERE day=?",arrayOf(w.day)).use { it.moveToFirst();assertEquals(3500L,it.getLong(0)) }
    }
    @Test fun zeroSamplesAreNotDisplayedAsFictionalSeventyFivePercent() {
        val summary=adaptive.summary()
        assertEquals(0,summary.getInt("spellingSamples"));assertTrue(summary.isNull("spellingAccuracy"))
        assertTrue(summary.getJSONArray("priorities").length()==0)
    }
    @Test fun secondSubmissionCannotReplaceAlreadyGradedFeedbackOrMastery() {
        val base=WorkflowFactory.create(LocalDate.now().toString(),Intensity.STANDARD,45,repo.loadWords(),RecentPerformance(),emptyList(),System.currentTimeMillis())
        val index=base.steps.indexOfFirst { (it.question?.wordId ?: 0)>0 }
        val before=base.copy(index=index,started=true);adaptive.save(before)
        val first=before.copy(feedback="第一次评分",correct=true)
        adaptive.answer(before,first,true,1000)
        val word=repo.loadWord(before.step!!.question!!.wordId)!!
        assertThrows(IllegalStateException::class.java) { adaptive.answer(before,before.copy(feedback="重复错误评分",correct=false),false,1000) }
        assertEquals(first,adaptive.load(before.day));assertEquals(word,repo.loadWord(word.id))
    }
    @Test fun repeatedLocalQuizPromptsTriggerOptionalSupplementOnlyAfterActualStudy() {
        val words=repo.loadWords();val date=LocalDate.now()
        val today=WorkflowFactory.create(date.toString(),Intensity.STANDARD,45,words,RecentPerformance(),emptyList(),System.currentTimeMillis())
        val yesterday=today.copy(day=date.minusDays(1).toString(),index=0)
        adaptive.save(yesterday);assertEquals(0.0,adaptive.quizRepetition(today),0.0)
        var prior=yesterday.copy(started=true)
        adaptive.save(prior)
        while(!prior.finished) {
            val after=prior.copy(index=prior.index+1)
            adaptive.answer(prior,after,true,500,if(prior.step?.question==null) 2 else null)
            prior=after
        }
        assertEquals(1.0,adaptive.quizRepetition(today),0.0)
        val pending=LearningEnhancements.plan(today,words,emptyList(),needsSupplement=true)
        assertTrue(pending.any { it.type=="quiz-supplement-v2" && it.practice })
    }
    @Test fun newWordAccuracyUsesTheActualCohortNotTheQuizSlotName() {
        val words=repo.loadWords();val id=words.first().id
        val q=LocalContent.vocabulary(words.first(),words,"meaning","fixture:quiz:review:0")
        val w=DailyWorkflow(LocalDate.now().toString(),Intensity.QUICK,15,"",listOf(id),emptyList(),"garden","",listOf(LearningStep(q.id,"综合小测",id,q)))
        adaptive.save(w);adaptive.answer(w,w.copy(index=1),false,1000)
        assertEquals(0.0,adaptive.performance().newAccuracy,0.0)
        val backup=JSONObject(repo.exportData(StudySettings(),AiSettings())).put("version",5)
        backup.getJSONArray("diagnostic_attempts").getJSONObject(0).apply { remove("new_cohort");remove("reading_id") }
        repo.importData(backup.toString(),AiSettings())
        assertEquals(0.0,adaptive.performance().newAccuracy,0.0)
    }
    @Test fun oldV5JsonIsRestoredAndInvalidMemoryRollsBackAtomically() {
        val word=repo.loadWords().first();adaptive.applyEvidence(word.id,Evidence(0,"spelling"))
        val backup=JSONObject(repo.exportData(StudySettings(),AiSettings())).put("version",5)
        val usage=backup.getJSONArray("ai_usage");repeat(usage.length()) { usage.getJSONObject(it).apply { remove("cache_creation_known");remove("cache_read_known") } }
        val diagnostics=backup.getJSONArray("diagnostic_attempts");repeat(diagnostics.length()) { diagnostics.getJSONObject(it).remove("reading_id") }
        repo.importData(backup.toString(),AiSettings())
        val before=adaptive.memory(repo.loadWord(word.id)!!)
        backup.getJSONArray("word_memory").getJSONObject(0).put("mastery",2.0)
        assertThrows(IllegalArgumentException::class.java) { repo.importData(backup.toString(),AiSettings()) }
        assertEquals(before,adaptive.memory(repo.loadWord(word.id)!!));assertEquals(1640,repo.loadWords().size)
    }
    @Test fun missingVsExplicitZeroCacheUsageRemainsDistinguishableAfterPersistence() {
        val missing=UsageParser.parse(JSONObject("""{"model":"returned","usage":{"prompt_tokens":10,"completion_tokens":2}}"""))
        val zero=UsageParser.parse(JSONObject("""{"usage":{"prompt_tokens":10,"completion_tokens":2,"prompt_tokens_details":{"cached_tokens":0,"cache_write_tokens":0}}}"""))
        val a=repo.recordAiUsage("returned",missing,AiSettings());val b=repo.recordAiUsage("returned",zero,AiSettings())
        assertFalse(repo.aiUsageRecord(a.id)!!.usage.cacheReadKnown);assertFalse(repo.aiUsageRecord(a.id)!!.usage.cacheCreationKnown)
        assertTrue(repo.aiUsageRecord(b.id)!!.usage.cacheReadKnown);assertTrue(repo.aiUsageRecord(b.id)!!.usage.cacheCreationKnown)
    }
}
