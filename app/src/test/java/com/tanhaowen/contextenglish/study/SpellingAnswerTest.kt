package com.tanhaowen.contextenglish.study

import com.tanhaowen.contextenglish.data.*
import org.junit.Test
import org.junit.Assert.*

class SpellingAnswerTest {
    @Test fun onlyExplicitStandardVariantsAreAcceptedInBothDirections() {
        listOf("organisation" to "organization","organise" to "organize","realise" to "realize",
            "recognize" to "recognise","analyse" to "analyze","humour" to "humor","behaviour" to "behavior",
            "neighbourhood" to "neighborhood","labour" to "labor","favour" to "favor","honour" to "honor",
            "centre" to "center","skilful" to "skillful","judgement" to "judgment").forEach { (a,b) ->
            assertTrue(SpellingAnswer.correct(a,b));assertTrue(SpellingAnswer.correct(b,a))
        }
        listOf("advice" to "advise","affect" to "effect","quiet" to "quite","form" to "from",
            "humour" to "humou","realise" to "realised","part-time" to "parttime",
            "practice" to "practise","license" to "licence","program" to "programme").forEach { (a,b) -> assertFalse(SpellingAnswer.correct(a,b)) }
        assertFalse(SpellingAnswer.correct("",""))
    }
    @Test fun legacyAndDailySpellingShareTheSameCreditRules() {
        val word=VocabWord(1,"centre","/x/","中心","","",WordState.LEARNING,false,0,0)
        val task=StudyTask("legacy-variant",word.id,TaskKind.SPELLING)
        val session=StudySession("s","2026-10-24","review",listOf(task))
        val answered=StudyEngine.answer(session,StudyQuestion(word,task,emptyList(),-1),typed="center")
        assertTrue(answered.feedback!!.correct);assertTrue(answered.feedback!!.credited)
        assertEquals(1,answered.correct);assertTrue(answered.wrongIds.isEmpty())
        val wrong=StudyEngine.answer(session,StudyQuestion(word,task,emptyList(),-1),typed="centres")
        assertFalse(wrong.feedback!!.correct);assertTrue(word.id in wrong.wrongIds)
    }
}
