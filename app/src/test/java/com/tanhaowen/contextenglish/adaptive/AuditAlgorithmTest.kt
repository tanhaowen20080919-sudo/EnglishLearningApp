package com.tanhaowen.contextenglish.adaptive

import com.tanhaowen.contextenglish.data.*
import org.junit.Assert.*
import org.junit.Test

class AuditAlgorithmTest {
    private val day=LearningParameters.DAY
    private fun words()=(1L..70).map { n -> VocabWord(n,"word${('a'+n.toInt()/26)}${('a'+n.toInt()%26)}","/test/","中文$n","",
        "The word${('a'+n.toInt()/26)}${('a'+n.toInt()%26)} is useful.",WordState.NEW,false,0,0,exampleTranslation="词义$n") }
    @Test fun historicalLapsesAndSpacedStreakIndependentlyAffectIntervals() {
        val base=Memory(.85,8.0,.3,streak=3,recent=.8,last=day)
        val normal=AdaptiveReview.update(base,Evidence(2,"meaning"),3*day)
        val forgotten=AdaptiveReview.update(base.copy(lapses=8),Evidence(2,"meaning"),3*day)
        val established=AdaptiveReview.update(base.copy(streak=7),Evidence(2,"meaning"),3*day)
        assertTrue("Prior forgetting must shorten the new interval",forgotten.next<normal.next)
        assertTrue("A spaced success streak must lengthen the new interval",established.next>normal.next)
    }
    @Test fun eachRequiredPerformanceSignalIndependentlyChangesNewWordVolume() {
        val baseline=RecentPerformance()
        val variations=listOf(baseline.copy(newAccuracy=0.0),baseline.copy(audit=0.0),baseline.copy(spelling=0.0),
            baseline.copy(context=0.0),baseline.copy(quiz=0.0),baseline.copy(completion=0.0))
        val count=DailyPlanner.calculate(baseline,0,Intensity.STANDARD).newCount
        variations.forEachIndexed { i,value -> assertTrue("Signal $i must affect volume",DailyPlanner.calculate(value,0,Intensity.STANDARD).newCount<count) }
        assertTrue(DailyPlanner.calculate(baseline,40,Intensity.STANDARD).newCount<count)
        assertTrue(DailyPlanner.calculate(baseline,0,Intensity.CUSTOM,10).newCount<count)
    }
    @Test fun auditIsDeterministicallyRandomAndOnlyTargetsPositiveSelfReports() {
        val pool=words();var triggered=0;val forms=mutableSetOf<String>()
        repeat(500) { i ->
            val key="test-$i";val steps=List(6) { LearningStep("$key:$it","新词",pool[it].id) }
            val w=DailyWorkflow("2026-10-23",Intensity.STANDARD,45,"",pool.take(6).map { it.id },emptyList(),"garden","",steps,index=1)
            val audited=WorkflowFactory.audit(w,pool[1],pool,if(i%2==0) 1 else 2)
            if(audited.steps.size>w.steps.size) { triggered++;forms.add(audited.steps.first { it.phase=="随机抽查" }.question!!.form) }
            assertEquals(audited,WorkflowFactory.audit(w,pool[1],pool,if(i%2==0) 1 else 2))
            assertEquals(w,WorkflowFactory.audit(w,pool[1],pool,0))
        }
        assertTrue(triggered in 170..280);assertEquals(setOf("meaning","spelling","context"),forms)
    }
    @Test fun spellingAndContextErrorsHaveRealMasteryAndDueDateConsequences() {
        val known=Memory(.9,15.0,.2,streak=5,recent=.95,forms=setOf("meaning","spelling","context"),contexts=setOf("x","y"),last=day)
        listOf("spelling","context").forEach { form ->
            val wrong=AdaptiveReview.update(known,Evidence(0,form,"new passage"),3*day)
            val right=AdaptiveReview.update(known,Evidence(2,form,"new passage"),3*day)
            assertTrue(wrong.memory.mastery<.5);assertTrue(wrong.next<right.next)
            assertTrue(wrong.memory.lapses>known.lapses);assertFalse(wrong.mastered)
        }
    }
    @Test fun shorteningStartedPlanPreservesReviewsAndProducesMixedMicroQuiz() {
        val pool=words().mapIndexed { i,w -> if(i<8) w.copy(state=WordState.LEARNING,nextReviewTime=0) else w }
        val old=WorkflowFactory.create("2026-10-23",Intensity.STANDARD,45,pool,RecentPerformance(),emptyList(),day).copy(started=true,index=2)
        val quick=WorkflowFactory.create(old.day,Intensity.QUICK,15,pool,RecentPerformance(),emptyList(),day)
        val resized=WorkflowFactory.resize(old,quick)
        assertEquals(old.steps.take(2),resized.steps.take(2));assertEquals(old.reviewIds,resized.reviewIds)
        assertEquals(8,resized.steps.count { it.phase=="复习" })
        val quiz=resized.steps.filter { it.phase=="综合小测" }
        assertEquals(5,quiz.size);assertEquals(setOf("meaning","spelling","context","grammar"),quiz.map { it.question!!.form }.toSet())
        assertTrue(resized.newIds.size<=5);assertEquals(2,resized.steps.count { it.phase=="阅读" })
    }
    @Test fun grammarQuestionsDoNotRepeatAndNonDueHistoryIsActuallyUsed() {
        val pool=words().mapIndexed { i,w -> if(i<5) w.copy(state=WordState.LEARNING,nextReviewTime=20*day) else w }
        val w=WorkflowFactory.create("2026-10-23",Intensity.STANDARD,45,pool,RecentPerformance(),listOf(SkillScore("时态",8,1)),day)
        val history=w.steps.filter { it.key.contains(":quiz:review:") }
        assertEquals(4,history.size);assertTrue(history.all { it.wordId in pool.take(5).map { it.id } })
        assertEquals(2,w.steps.filter { it.phase=="综合小测" && it.question?.form=="grammar" }.map { it.question!!.prompt }.distinct().size)
        assertEquals(9,w.steps.filter { it.phase=="综合小测" && it.question?.form=="meaning" }.map { it.wordId }.distinct().size)
    }
    @Test fun foregroundClockExcludesBackgroundAndFeedbackAndRestoresElapsedTime() {
        var time=0L;val clock=StudyClock { time }
        clock.restore(1000);clock.resume();time+=2_000_000_000;assertEquals(3000L,clock.pause())
        time+=60_000_000_000;assertEquals(3000L,clock.elapsed())
        clock.resume();time+=500_000_000;assertEquals(3500L,clock.pause())
        val restarted=StudyClock { time };restarted.restore(clock.elapsed());restarted.resume();time+=1_000_000_000
        assertEquals(4500L,restarted.pause());restarted.reset(false);time+=5_000_000_000;assertEquals(0L,restarted.elapsed())
    }
    @Test fun readingErrorCanInsertAnObjectiveGrammarDiagnosisOnlyOnce() {
        val w=WorkflowFactory.create("2026-10-23",Intensity.STANDARD,45,words(),RecentPerformance(),emptyList(),day)
        val reading=w.copy(index=w.steps.indexOfFirst { it.phase=="阅读" },feedback="错误",correct=false)
        val added=WorkflowFactory.readingGrammarDiagnosis(reading,"非谓语")
        assertEquals("阅读语法诊断",added.steps[added.index+1].phase)
        assertEquals("grammar",added.steps[added.index+1].question!!.form)
        assertEquals(added,WorkflowFactory.readingGrammarDiagnosis(added,"非谓语"))
    }
    @Test fun allExtendedReadingsHaveFiveTypesAndRealPassageLength() {
        val tags=setOf("主旨题","细节题","推断题","词义猜测题","指代题")
        assertTrue(ReadingBank.extended.size>=4)
        ReadingBank.extended.forEach { r ->
            assertTrue("${r.id} needs a full passage",Regex("[A-Za-z]+(?:['-][A-Za-z]+)*").findAll(r.passage).count() in 260..420)
            assertEquals(tags,r.questions.map { it.tag }.toSet())
            r.questions.forEach { q -> assertEquals(4,q.options.distinct().size);assertTrue(q.explanation.length>=20) }
        }
        assertTrue(GrammarBank.lessons.size>=8)
        GrammarBank.lessons.forEach { g -> assertTrue(g.questions.size in 3..5);assertEquals(g.questions.size,g.questions.map { it.prompt }.distinct().size) }
    }
    @Test fun highestPriorityMayBeVocabularyRatherThanOnlyReadingOrGrammar() {
        val priorities=WeaknessDetector.priorities(listOf(SkillScore("拼写",20,2),SkillScore("推断题",8,5),SkillScore("时态",5,3)))
        assertEquals("拼写",priorities.first().tag)
        assertTrue(WeaknessDetector.recommendation(priorities.first().tag).contains("拼写"))
    }
    @Test fun shortCustomTimeUsesMicroFlowAndLongerModeCanRestoreRemainingContent() {
        val pool=words()
        val short=WorkflowFactory.create("2026-10-23",Intensity.CUSTOM,10,pool,RecentPerformance(),emptyList(),day)
        assertEquals(5,short.steps.count { it.phase=="综合小测" });assertTrue(short.newIds.size<=5)
        assertTrue(short.minutes<=10)
        val active=short.copy(index=1,started=true)
        val full=WorkflowFactory.create(active.day,Intensity.INTENSIVE,60,pool,RecentPerformance(),emptyList(),day)
        val grown=WorkflowFactory.resize(active,full)
        assertEquals(active.steps.first(),grown.steps.first())
        assertEquals(15,grown.steps.count { it.phase=="综合小测" })
        assertEquals(5,grown.steps.count { it.phase=="阅读" })
        assertEquals(4,grown.steps.count { it.phase=="语法专项" })
        assertTrue(grown.newIds.size>active.newIds.size)
        assertFalse(grown.grammarFromWeakness)
    }
    @Test fun futureReadingFormatsCanRoundTripMoreThanFourOptions() {
        val q=LocalQuestion("extension","选择最适合空缺的一句",listOf("A","B","C","D","E","F","G"),4,"测试扩展点，不声称已有七选五题库。","reading","七选五")
        val w=DailyWorkflow("2026-10-23",Intensity.STANDARD,45,"",emptyList(),emptyList(),"garden","",listOf(LearningStep("extension","阅读",question=q)))
        assertEquals(w,WorkflowCodec.decode(WorkflowCodec.encode(w)))
        assertTrue(q.correct("4"));assertFalse(q.correct("3"))
    }
}
