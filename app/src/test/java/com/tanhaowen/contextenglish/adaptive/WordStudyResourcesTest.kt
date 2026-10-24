package com.tanhaowen.contextenglish.adaptive

import com.tanhaowen.contextenglish.data.*
import com.tanhaowen.contextenglish.study.StudyEngine
import com.tanhaowen.contextenglish.ai.LearningEnhancements
import org.junit.*
import org.junit.Assert.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class WordStudyResourcesTest {
    private lateinit var db: EnglishDatabase
    private lateinit var repo: EnglishRepository
    @Before fun setup() {
        val context=RuntimeEnvironment.getApplication()
        context.deleteDatabase("context_english.db")
        db=EnglishDatabase(context);repo=EnglishRepository(db);repo.loadWords()
    }
    @After fun cleanup() { db.close() }
    @Test fun reviewedCoreSensesDoNotTeachRareNounsAsTheMainAdjectiveMeaning() {
        val expected=mapOf("automatic" to "自动的","swift" to "迅速的","specific" to "具体的；特定的","perfect" to "完美的","basic" to "基本的","natural" to "自然的；天生的","still" to "仍然；静止的")
        val words=repo.loadWords()
        expected.forEach { (word,meaning) ->
            val w=words.first { it.word==word };val original=w.meaning
            assertTrue(original.startsWith("n."))
            assertEquals(meaning,StudyEngine.meaning(w))
            val question=LocalContent.vocabulary(w,words,"meaning","reviewed-$word")
            assertEquals(meaning,question.answerText);assertTrue(question.correct(question.answer.toString()))
            assertEquals(original,repo.loadWord(w.id)!!.meaning)
        }
    }
    @Test fun customDefinitionsAndUnreviewedWordsKeepTheirOriginalMeaning() {
        val word=repo.loadWords().first { it.word=="automatic" }
        val custom=word.copy(meaning="用户自定义的特定语境义")
        assertNull(WordStudyResources.note(custom));assertEquals(custom.meaning,StudyEngine.meaning(custom))
        val outside=word.copy(word="madeupword",meaning="自定义词义\n附加说明")
        assertNull(WordStudyResources.note(outside));assertEquals("自定义词义",StudyEngine.meaning(outside))
    }
    @Test fun everyOriginalWordHasMorphologyWhileMissingInflectionsAreNotInvented() {
        val words=repo.loadWords();assertEquals(1640,words.size)
        words.forEach { assertTrue("${it.word} needs an actual forms/usage note",WordStudyResources.morphology(it.word)?.text?.isNotBlank()==true) }
        assertTrue(WordStudyResources.morphology("basis")!!.text.contains("bases"))
        assertTrue(WordStudyResources.morphology("status")!!.text.contains("statuses"))
        assertEquals("uncountable",WordStudyResources.morphology("information")!!.kind)
        assertEquals("uncountable",WordStudyResources.morphology("furniture")!!.kind)
        assertEquals("modal",WordStudyResources.morphology("ought")!!.kind)
        assertEquals("agreement",WordStudyResources.morphology("statistics")!!.kind)
        assertEquals("relational",WordStudyResources.morphology("solar")!!.kind)
    }
    @Test fun actualGradingAndAiInputsUseTheSameReviewedSenseWithoutRewritingLegacyData() {
        val word=repo.loadWords().first { it.word=="automatic" };val original=word.meaning
        val pool=repo.loadWords();val q=LocalContent.vocabulary(word,pool,"meaning","reviewed-grading")
        val workflow=DailyWorkflow(LocalDate.now().toString(),Intensity.STANDARD,45,"",listOf(word.id),emptyList(),"garden","",listOf(LearningStep(q.id,"综合小测",word.id,q)))
        val adaptive=AdaptiveRepository(repo);adaptive.save(workflow)
        adaptive.answer(workflow,workflow.copy(index=1),q.correct(q.answer.toString()),1000,answerValue=q.answer.toString())
        assertTrue(adaptive.memory(repo.loadWord(word.id)!!).mastery>0)
        assertEquals(original,repo.loadWord(word.id)!!.meaning)
        val prompt=LearningEnhancements.examples(repo.loadWord(word.id)!!).prompt
        assertTrue(prompt.contains("高中常用义=自动的"))
        val note=WordStudyResources.note(word)!!
        assertTrue(LocalContent.contexts(word).any { it.substringBefore('\n')==note.example.replace("automatic","____") })
        db.close();db=EnglishDatabase(RuntimeEnvironment.getApplication());repo=EnglishRepository(db)
        assertEquals("自动的",StudyEngine.meaning(repo.loadWord(word.id)!!))
        assertEquals(original,repo.loadWord(word.id)!!.meaning)
    }
    @Test fun contentCorrectionPreservesAnsweredHistoryAndOnlyRefreshesUnansweredLocalQuestionsOnce() {
        val words=repo.loadWords();val word=words.first { it.word=="automatic" }
        val legacy=word.meaning.lineSequence().first()
        val q=LocalQuestion("old","automatic",listOf(legacy,"另一个词义一","另一个词义二","另一个词义三"),0,"旧题反馈","meaning",wordId=word.id)
        val spell=LocalQuestion("spell",legacy,emptyList(),-1,"拼写题","spelling",wordId=word.id,spelling=word.word)
        val w=DailyWorkflow(LocalDate.now().toString(),Intensity.STANDARD,45,"",listOf(word.id),emptyList(),"garden","",
            listOf(LearningStep("answered","综合小测",word.id,q.copy(id="answered")),LearningStep("current","综合小测",word.id,q),LearningStep("spell","综合小测",word.id,spell)),index=1,started=true,stepElapsedMs=2300)
        val fixed=WorkflowFactory.refreshReviewedVocabulary(w,words)
        assertEquals(w.steps.first(),fixed.steps.first())
        assertEquals("自动的",fixed.steps[1].question!!.answerText)
        assertEquals("自动的",fixed.steps[2].question!!.prompt)
        assertEquals(w.index,fixed.index);assertEquals(2300L,fixed.stepElapsedMs)
        assertEquals(fixed,WorkflowFactory.refreshReviewedVocabulary(fixed,words))
        val alreadyGraded=w.copy(feedback="已保存的旧反馈",correct=true,selectedAnswer="0")
        val preserved=WorkflowFactory.refreshReviewedVocabulary(alreadyGraded,words)
        assertEquals(q,preserved.step!!.question)
        assertEquals(alreadyGraded.feedback,preserved.feedback);assertEquals("0",preserved.selectedAnswer)
    }

    @Test fun reviewedSensesAlsoParticipateInDistractorAmbiguityFiltering() {
        val word=repo.loadWords().first { it.word=="privilege" }
        assertTrue(StudyEngine.meaning(word).contains("荣幸"))
        assertFalse(word.meaning.contains("荣幸"))
        val ambiguous=word.copy(id=100001,word="otherword",meaning="n. 荣幸")
        val safe=(1..3).map { n -> word.copy(id=100001+n.toLong(),word="safeword$n",meaning="不同测试释义$n") }
        repeat(20) { seed ->
            val q=LocalContent.vocabulary(word,listOf(word,ambiguous)+safe,"meaning","ambiguity-$seed")
            assertEquals(4,q.options.size)
            assertEquals(StudyEngine.meaning(word),q.answerText)
            assertFalse(q.options.filterIndexed { index,_ -> index!=q.answer }.any { it.contains("荣幸") })
        }
    }

    @Test fun contextFeedbackUsesTheActualSampledSentenceRatherThanAnUnrelatedDefaultExample() {
        val words=repo.loadWords();val word=words.first { it.word=="represent" }
        val questions=(1..30).map { LocalContent.vocabulary(word,words,"context","actual-context-$it") }
        assertTrue(questions.map { it.context }.distinct().size>=2)
        questions.forEach { q ->
            assertTrue(q.explanation.startsWith("当前句（填词后）："))
            assertTrue(q.explanation.contains(q.prompt.substringBefore('\n').replace("____",word.word)))
            assertTrue(q.correct(q.answer.toString()))
        }
    }

}
