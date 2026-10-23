package com.tanhaowen.contextenglish.adaptive

import com.tanhaowen.contextenglish.ai.*
import com.tanhaowen.contextenglish.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EnhancementContractTest {
    private val reading=LocalContent.readings.first()
    @Test fun robustJsonAcceptsFencesButRejectsWrongTypesAndTrailingObjects() {
        val valid="""{"explanation":"这是一段足够长而且字段完整的中文解析。","suggestions":["找证据"]}"""
        AiTaskScheduler.validateExplanation("```JSON\n$valid\n```")
        listOf("[]","{\"explanation\":123,\"suggestions\":[\"x\"]}","{\"explanation\":null}",valid+valid).forEach { value ->
            assertThrows(Exception::class.java) { AiTaskScheduler.validateExplanation(value) }
        }
    }
    private fun readingResponse()=JSONObject().put("explanation","这是一份涵盖全文逻辑并且包含具体证据的阅读解析。")
        .put("suggestions",JSONArray(listOf("先找原文证据"))).put("analysis",JSONArray().also { a ->
            reading.questions.forEach { q -> a.put(JSONObject().put("id",q.id).put("evidence",reading.passage.substringBefore('.')+".")
                .put("reason","定位真实原文后，排除扩大范围与无依据推断的错误选项。")) }
        })
    @Test fun readingSchemaRejectsHallucinatedQuotesAndMissingQuestionCoverage() {
        val task=LearningEnhancements.reading(reading)
        task.validate(readingResponse().toString())
        val wrong=readingResponse();wrong.getJSONArray("analysis").getJSONObject(0).put("evidence","A quotation that is absent from the passage.")
        assertThrows(IllegalArgumentException::class.java) { task.validate(wrong.toString()) }
        val missing=readingResponse();missing.getJSONArray("analysis").remove(0)
        assertThrows(IllegalArgumentException::class.java) { task.validate(missing.toString()) }
    }
    @Test fun cachedFullArticleDoesNotRevealUnattemptedAnswers() {
        val task=LearningEnhancements.reading(reading);val output=readingResponse()
        output.getJSONArray("analysis").getJSONObject(0).put("reason","第一题的证据与解释；只应在第一题作答后显示。")
        output.getJSONArray("analysis").getJSONObject(1).put("reason","第二题的答案秘密，第一题时不得提前显示。")
        val text=LearningEnhancements.display(task,output.toString(),reading.questions.first().id)
        assertTrue(text.contains("第一题"));assertFalse(text.contains("第二题"))
    }
    @Test fun contextMeaningMustGroundTheRequestedWordInItsActualSentence() {
        val task=LearningEnhancements.context("garden",reading.passage)
        val good=JSONObject().put("word","garden").put("meaning","花园").put("evidence","community garden")
            .put("reason","该段明确谈到把空地改为大家使用的花园。")
        task.validate(good.toString())
        good.put("evidence","At first, only three people came.")
        assertThrows(IllegalArgumentException::class.java) { task.validate(good.toString()) }
    }
    private val words=(1L..20).map { i -> VocabWord(i,"word${('a'+i.toInt())}","/x/","词义$i","","",WordState.LEARNING,false,0,0) }
    private fun workflow()=DailyWorkflow("2026-10-23",Intensity.STANDARD,45,"",words.map { it.id },emptyList(),"garden","时态",emptyList())
    private fun grammarResponse()=JSONObject().put("questions",JSONArray().also { a -> GrammarBank.lessons.first { it.tag=="时态" }.questions.take(3).forEach { q ->
        a.put(JSONObject().put("prompt",q.prompt).put("options",JSONArray(q.options)).put("answer",q.answer).put("explanation",q.explanation)
            .put("form","grammar").put("tag","时态").put("wordId",0).put("spelling",""))
    } })
    @Test fun generatedPracticeIsBoundToTheRealWeaknessAndCannotInventIdsOrFractionalAnswers() {
        val task=LearningEnhancements.practice(workflow(),words,listOf(SkillScore("时态",8,1)))
        task.validate(grammarResponse().toString())
        val wrongTag=grammarResponse();wrongTag.getJSONArray("questions").getJSONObject(0).put("tag","非谓语")
        assertThrows(IllegalArgumentException::class.java) { task.validate(wrongTag.toString()) }
        val inventedId=grammarResponse();inventedId.getJSONArray("questions").getJSONObject(0).put("wordId",999)
        assertThrows(IllegalArgumentException::class.java) { task.validate(inventedId.toString()) }
        val fraction=grammarResponse();fraction.getJSONArray("questions").getJSONObject(0).put("answer",1.7)
        assertThrows(IllegalArgumentException::class.java) { task.validate(fraction.toString()) }
        val duplicate=grammarResponse();duplicate.getJSONArray("questions").put(1,duplicate.getJSONArray("questions").getJSONObject(0))
        assertThrows(IllegalArgumentException::class.java) { task.validate(duplicate.toString()) }
    }
    @Test fun generatedExamplesRequireTheTargetWordTranslationCollocationAndContrast() {
        val w=words.first();val task=LearningEnhancements.examples(w)
        val j=JSONObject().put("word",w.word).put("example","This is a useful ${w.word} example.").put("translation","这是一个有用的新语境例句。")
            .put("collocations",JSONArray(listOf("useful "+w.word))).put("forms","测试词不自动变形").put("contrast","对照近义词在此句的使用差异与限制。")
        task.validate(j.toString());j.remove("contrast")
        assertThrows(Exception::class.java) { task.validate(j.toString()) }
    }
    @Test fun strongLocalEvidenceNeedsNoAutomaticAiPreparationTasks() {
        val strong=reading.questions.map { SkillScore(it.tag,10,10) }
        val plan=workflow().copy(grammarTag="",grammarFromWeakness=false)
        val tasks=LearningEnhancements.plan(plan,words,strong)
        assertTrue(tasks.isEmpty())
    }
    @Test fun contextualMeaningIsNotJustClozeAndDoesNotInflateContextVariety() {
        val note=WordNotes.entries.getValue("review")
        val word=words.first().copy(word="review",meaning="复习；评论",example=note.alternate,exampleTranslation=note.translation)
        val sense=LocalContent.vocabulary(word,words,"sense","sense-test")
        val cloze=(1..30).map { LocalContent.vocabulary(word,words,"context","cloze-test-$it") }.first { it.context==sense.context }
        assertEquals("评论",sense.answerText);assertTrue(sense.options.contains("复习"))
        assertEquals("context",sense.form);assertEquals(cloze.context,sense.context)
        assertTrue(sense.prompt.contains("review a new book"))
    }
}
