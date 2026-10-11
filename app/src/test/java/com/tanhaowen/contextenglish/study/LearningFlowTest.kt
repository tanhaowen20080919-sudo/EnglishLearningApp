package com.tanhaowen.contextenglish.study

import com.tanhaowen.contextenglish.data.*
import org.junit.Assert.*
import org.junit.Test

class LearningFlowTest {
    private fun word(id: Long, state: WordState = WordState.NEW) = VocabWord(id,"term$id","",
        "词义$id","","",state,false,0,0)
    private val pool = (1L..10L).map(::word)

    @Test fun everyNewWordIsLearnedOnceBeforeItsRecall() {
        val session=StudyEngine.create(pool,"quick","day")
        pool.forEach { w ->
            val learn=session.tasks.indexOfFirst { it.wordId==w.id && it.kind==TaskKind.LEARN }
            val recall=session.tasks.indexOfFirst { it.wordId==w.id && it.kind==TaskKind.MEANING }
            assertTrue(learn>=0 && recall>learn)
            assertEquals(1,session.tasks.count { it.wordId==w.id && it.kind==TaskKind.LEARN })
        }
        assertEquals(4,session.tasks.indexOfFirst { it.wordId==1L && it.kind==TaskKind.MEANING })
    }

    @Test fun viewingNewWordDoesNotCountAsAnswerOrMastery() {
        val s=StudyEngine.create(pool,"quick","day")
        val q=StudyEngine.question(s,pool)!!
        assertEquals(s,StudyEngine.answer(s,q,unknown=true))
        val next=StudyEngine.finishLearning(s)
        assertEquals(s.index+1,next.index); assertEquals(0,next.answered)
        assertEquals(0,next.correct);assertTrue(next.wrongIds.isEmpty());assertNull(next.feedback)
        assertEquals(WordState.NEW,q.word.state)
    }

    @Test fun repeatedContinueCannotSkipAnUngradedRecall() {
        var s=StudyEngine.create(pool.take(1),"quick","day")
        s=StudyEngine.finishLearning(s)
        assertEquals(TaskKind.MEANING,s.task!!.kind)
        assertEquals(s,StudyEngine.finishLearning(s));assertEquals(s,StudyEngine.next(s))
    }

    @Test fun exposureCheckpointSurvivesResumeAndOldSnapshotStillLoads() {
        val s=StudyEngine.create(pool,"quick","day")
        assertEquals(s,StudyCodec.decode(StudyCodec.encode(s)))
        val next=StudyEngine.finishLearning(s)
        assertEquals(next,StudyCodec.decode(StudyCodec.encode(next)))
        val old=StudyEngine.create(pool,"today","day",learnFirst=false)
        assertEquals(old,StudyCodec.decode(StudyCodec.encode(old)))
        assertFalse(old.tasks.any { it.kind==TaskKind.LEARN })
    }

    @Test fun delayedRetryDoesNotRecreateLearningStageAndRemainsBounded() {
        var s=StudyEngine.create(pool.take(1),"quick","day")
        s=StudyEngine.finishLearning(s)
        repeat(3) {
            val q=StudyEngine.question(s,pool)!!
            s=StudyEngine.next(StudyEngine.answer(s,q,unknown=true))
        }
        assertTrue(s.finished);assertEquals(3,s.answered)
        assertEquals(1,s.tasks.count { it.kind==TaskKind.LEARN })
    }

    @Test fun fiveWordGroupMixesNewAndReviewWithoutDuplicates() {
        val candidates=(1L..10L).map { word(it,WordState.LEARNING) }+(11L..20L).map(::word)
        val group=StudySelection.group(candidates+candidates,5)
        assertEquals(5,group.size);assertEquals(5,group.map { it.id }.distinct().size)
        assertEquals(listOf(1L,2L,11L,3L,4L),group.map { it.id })
        assertEquals(5,StudySelection.group(candidates.filter { it.state==WordState.NEW },5).size)
        assertEquals(5,StudySelection.group(candidates.filter { it.state!=WordState.NEW },5).size)
    }

    private val example=VocabWord(100,"available","","adj. 可用的","可用的","The book is available now.",
        WordState.LEARNING,false,1,1,reviewCount=1,exampleTranslation="这本书现在可以买到了。")

    @Test fun clozeKeepsAttributionExampleAndDoesNotLeakAnswer() {
        val prompt=LocalCloze.prompt(example)!!
        assertEquals("The book is ____ now.\n这本书现在可以买到了。",prompt)
        assertFalse(prompt.contains(example.word,true))
        val session=StudyEngine.create(listOf(example),"review","day")
        assertEquals(TaskKind.CONTEXT,session.task!!.kind)
        assertEquals(prompt,session.task!!.prompt)
    }

    @Test fun repeatedWordMorphologyAndLeakedTranslationsFallBackToMeaning() {
        listOf(example.copy(example="Available books are available."),
            example.copy(example="Its availability is low."),
            example.copy(exampleTranslation="available 的意思是可用的。"),
            example.copy(example="It is ____ and available."),example.copy(word="two words")).forEach { w ->
            assertNull(LocalCloze.prompt(w))
            assertEquals(TaskKind.MEANING,StudyEngine.create(listOf(w),"review","day").task!!.kind)
        }
    }

    @Test fun newWordsWithExamplesStillLearnBeforeTesting() {
        val session=StudyEngine.create(listOf(example.copy(state=WordState.NEW)),"today","day")
        assertEquals(TaskKind.LEARN,session.task!!.kind)
        assertEquals(TaskKind.MEANING,StudyEngine.finishLearning(session).task!!.kind)
    }

    @Test fun changedKindCannotGradeWithAStaleQuestion() {
        val s=StudyEngine.create(pool,"today","day",learnFirst=false)
        val q=StudyEngine.question(s,pool)!!
        val spelling=s.copy(tasks=s.tasks.mapIndexed { i,t -> if(i==0) t.copy(kind=TaskKind.SPELLING) else t })
        assertEquals(spelling,StudyEngine.answer(spelling,q,q.answer))
    }

    @Test fun preparedQuestionsKeepTheirOptionsAfterProgressChanges() {
        val bank=StudyQuestionBank(pool)
        val session=StudyEngine.create(pool,"today","day",learnFirst=false)
        assertNull(bank.cached(session))
        val q=bank.prepare(session)!!
        assertSame(q,bank.cached(session))
        assertSame(q,bank.prepare(session.copy(feedback=StudyFeedback(correct=true,credited=true,answer="correct"))))
        assertEquals(4,q.options.distinct().size)
        assertNull(bank.cached(session.copy(index=1)))
    }
}
