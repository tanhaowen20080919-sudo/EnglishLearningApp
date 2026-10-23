package com.tanhaowen.contextenglish.adaptive

import com.tanhaowen.contextenglish.data.*
import org.junit.Assert.*
import org.junit.Test

class WorkflowTest {
    private fun word(id: Long): VocabWord {
        val text="term"+('a'+(id/26).toInt())+('a'+(id%26).toInt())
        return VocabWord(id,text,"/test/","词义$id","","The $text is useful.",WordState.NEW,false,0,0,exampleTranslation="这个词很有用。")
    }
    private val words=(1L..60L).map(::word)
    private fun workflow(mode: Intensity=Intensity.STANDARD,skills: List<SkillScore> = emptyList())=
        WorkflowFactory.create("2026-10-23",mode,45,words,RecentPerformance(),skills,1000)
    @Test fun standardQuizHasRequestedComposition() {
        val quiz=workflow().steps.filter { it.phase=="综合小测" }
        assertEquals(15,quiz.size)
        assertEquals(5,quiz.count { it.key.contains(":new:") })
        assertEquals(4,quiz.count { it.key.contains(":review:") })
        assertEquals(2,quiz.count { it.question?.form=="spelling" })
        assertEquals(2,quiz.count { it.question?.form=="context" })
        assertEquals(2,quiz.count { it.question?.form=="grammar" })
    }
    @Test fun weakerGrammarAddsTargetedTraining() {
        val w=workflow(skills=listOf(SkillScore("时态",7,1)))
        assertEquals("时态",w.grammarTag)
        assertEquals(4,w.steps.count { it.phase=="语法专项" })
    }
    @Test fun readingWeaknessesDoNotHideGrammarIntervention() {
        val skills=listOf("推断题","细节题","主旨题","指代题").map { SkillScore(it,8,0) }+SkillScore("时态",6,3)
        assertEquals("时态",workflow(skills=skills).grammarTag)
    }
    @Test fun allReadingTypesAreAvailableOffline() {
        assertEquals(setOf("主旨题","细节题","推断题","词义猜测题","指代题"),LocalContent.readings.first().questions.map { it.tag }.toSet())
        LocalContent.readings.forEach { r -> assertEquals(5,r.questions.size);r.questions.forEach { assertTrue(it.answer in it.options.indices) } }
    }
    @Test fun workflowSnapshotPreservesExactQuestionAndFeedback() {
        val w=workflow().copy(index=22,feedback="错误说明",correct=false,revealed=true,started=true)
        assertEquals(w,WorkflowCodec.decode(WorkflowCodec.encode(w)))
    }
    @Test fun corruptIndexIsRejected() {
        val j=org.json.JSONObject(WorkflowCodec.encode(workflow())).put("index",10001)
        assertThrows(IllegalArgumentException::class.java) { WorkflowCodec.decode(j.toString()) }
    }
    @Test fun selfKnownTriggersAuditWithoutDuplicateQuestions() {
        val w=workflow()
        val audited=WorkflowFactory.audit(w,words.first(),words,2)
        assertEquals(w.steps.size+1,audited.steps.size)
        assertEquals("随机抽查",audited.steps[4].phase)
        assertEquals(audited,WorkflowFactory.audit(audited,words.first(),words,2))
    }
    @Test fun unknownDoesNotTriggerRedundantCalibration() {
        val w=workflow();assertEquals(w,WorkflowFactory.audit(w,words.first(),words,0))
    }
    @Test fun dueReviewsSurviveSmallTimeBudget() {
        val due=words.map { it.copy(state=WordState.LEARNING,nextReviewTime=1) }
        val w=WorkflowFactory.create("2026-10-23",Intensity.QUICK,15,due,RecentPerformance(),emptyList(),1000)
        assertEquals(60,w.reviewIds.size);assertTrue(w.newIds.isEmpty())
    }
    @Test fun distractorsAndQuestionOrderAreDeterministic() {
        val a=workflow();val b=workflow();assertEquals(a,b)
        a.steps.mapNotNull { it.question }.filter { it.spelling.isBlank() }.forEach { assertEquals(4,it.options.distinct().size) }
    }
}
