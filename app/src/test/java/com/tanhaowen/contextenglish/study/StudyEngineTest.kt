package com.tanhaowen.contextenglish.study

import com.tanhaowen.contextenglish.data.*
import org.junit.Assert.*
import org.junit.Test

class StudyEngineTest {
    private fun word(id: Long,text: String,meaning: String,state: WordState=WordState.NEW)=VocabWord(id,text,"",meaning,meaning,"",state,false,0,0)
    private val words=listOf(word(1,"available","adj. 可获得的；有空的"),word(2,"colorful","adj. 多彩的"),
        word(3,"gas","n. 气体"),word(4,"decide","v. 决定"),word(5,"room","n. 房间"),word(6,"learn","v. 学习"))
    private fun session()=StudyEngine.create(words,"today","2026-10-09")

    @Test fun deterministicDistinctFourOptions() {
        val s=session(); val q=StudyEngine.question(s,words)!!
        assertEquals(4,q.options.distinct().size)
        assertEquals(StudyEngine.meaning(q.word),q.options[q.answer])
        assertEquals(q,StudyEngine.question(s,words))
    }
    @Test fun identicalAndOverlappingMeaningsAreNotDistractors() {
        val pool=words+word(7,"obtainable","adj. 可获得的")
        val q=StudyEngine.question(StudyEngine.create(pool,"today","day"),pool)!!
        assertFalse(q.options.contains("adj. 可获得的"))
    }
    @Test fun correctDoesNotAutomaticallyBecomeMastered() {
        val s=session();val q=StudyEngine.question(s,words)!!
        val rated=StudyEngine.answer(s,q,q.answer)
        assertEquals(1,rated.correct);assertTrue(rated.feedback!!.credited)
        assertEquals(s.tasks,rated.tasks);assertEquals(WordState.NEW,q.word.state)
    }
    @Test fun repeatedTapCannotDoubleCount() {
        val s=session();val q=StudyEngine.question(s,words)!!;val rated=StudyEngine.answer(s,q,q.answer)
        assertEquals(rated,StudyEngine.answer(rated,q,q.answer))
    }
    @Test fun wrongAddsDelayedRetryAndKeepsFeedbackUntilNext() {
        val s=session();val q=StudyEngine.question(s,words)!!;val rated=StudyEngine.answer(s,q,(q.answer+1)%4)
        assertFalse(rated.feedback!!.correct);assertEquals(0,rated.index)
        assertEquals(q.word.id,rated.tasks[4].wordId);assertEquals(1,rated.tasks[4].repeat)
        assertEquals(1,StudyEngine.next(rated).index)
    }
    @Test fun retriesAreBounded() {
        var s=StudyEngine.create(words.take(1),"today","day")
        repeat(3) {
            val q=StudyEngine.question(s,words)!!
            s=StudyEngine.next(StudyEngine.answer(s,q,unknown=true))
        }
        assertTrue(s.finished);assertEquals(3,s.answered);assertEquals(3,s.tasks.size)
    }
    @Test fun hintsDoNotCreditGuessingOrHideWrongWord() {
        val s=session().copy(hinted=true);val q=StudyEngine.question(s,words)!!
        val rated=StudyEngine.answer(s,q,q.answer)
        assertTrue(rated.feedback!!.correct);assertFalse(rated.feedback.credited)
        assertEquals(0,rated.correct);assertTrue(q.word.id in rated.wrongIds)
    }
    @Test fun spellingIsTrimmedAndCaseInsensitive() {
        val s=session();val spelling=s.copy(tasks=s.tasks.mapIndexed { i,t -> if(i==0) t.copy(kind=TaskKind.SPELLING) else t })
        val q=StudyEngine.question(spelling,words)!!
        assertTrue(StudyEngine.answer(spelling,q,typed=" AVAILABLE ").feedback!!.correct)
        assertFalse(StudyEngine.answer(spelling,q,typed="availble").feedback!!.correct)
    }
    @Test fun ungradedNextDoesNothing() { val s=session();assertEquals(s,StudyEngine.next(s)) }
    @Test fun codecRoundTripIncludesWrongFeedbackAndQueue() {
        val s=session().copy(hinted=true);val q=StudyEngine.question(s,words)!!
        val rated=StudyEngine.answer(s,q,unknown=true)
        assertEquals(rated,StudyCodec.decode(StudyCodec.encode(rated)))
    }
    @Test fun codecRejectsOutOfRangeIndex() {
        val text=org.json.JSONObject(StudyCodec.encode(session())).put("index",999).toString()
        assertTrue(runCatching { StudyCodec.decode(text) }.isFailure)
    }
    @Test fun coachDoesNotReplaceCurrentQuestionOrDuplicate() {
        val s=session().copy(wrongIds=setOf(1));val n=CoachNote(1,"提示","It is available.","它可用。","It is ____.")
        val extra=StudyEngine.addCoach(s,listOf(n))
        assertEquals(s.task,extra.task);assertEquals(TaskKind.CONTEXT,extra.tasks[4].kind)
        assertEquals(extra,StudyEngine.addCoach(extra,listOf(n)))
    }
    @Test fun newAndReviewWordsAreInterleaved() {
        val pool=words.mapIndexed { i,w -> if(i<3) w.copy(state=WordState.LEARNING) else w }
        val s=StudyEngine.create(pool,"today","day")
        assertEquals(4L,s.tasks[2].wordId)
    }
    @Test fun invalidCoachJsonAndUnknownIdsAreRejected() {
        assertTrue(runCatching { StudyCodec.notes("{\"items\":[{\"wordId\":999}]}",setOf(1)) }.isFailure)
    }
}
