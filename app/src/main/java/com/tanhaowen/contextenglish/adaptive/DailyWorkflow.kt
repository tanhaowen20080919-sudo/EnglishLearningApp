package com.tanhaowen.contextenglish.adaptive

import com.tanhaowen.contextenglish.data.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

data class LearningStep(val key: String, val phase: String, val wordId: Long = 0, val question: LocalQuestion? = null)
data class DailyWorkflow(val day: String, val mode: Intensity, val minutes: Int, val reason: String,
    val newIds: List<Long>, val reviewIds: List<Long>, val readingId: String, val grammarTag: String,
    val steps: List<LearningStep>, val index: Int = 0, val feedback: String = "",
    val correct: Boolean = false, val revealed: Boolean = false, val started: Boolean = false) {
    val step get() = steps.getOrNull(index)
    val finished get() = index>=steps.size
    val progress get() = if(steps.isEmpty()) 1f else index.toFloat()/steps.size
}

object WorkflowFactory {
    fun create(day: String, mode: Intensity, minutes: Int, words: List<VocabWord>,
        performance: RecentPerformance, skills: List<SkillScore>, now: Long, preferred: Int = 20): DailyWorkflow {
        val due=words.count { it.state!=WordState.NEW && it.nextReviewTime<=now }
        val load=DailyPlanner.calculate(performance,due,mode,minutes,preferred)
        val selected=DailyPlanner.choose(words,load,now)
        val fresh=selected.filter { it.state==WordState.NEW }
        val reviews=selected.filter { it.state!=WordState.NEW }
        val reading=LocalContent.readings[Math.floorMod(day.hashCode(),LocalContent.readings.size)]
        val weak=WeaknessDetector.priorities(skills.filter { s -> LocalContent.grammars.any { it.tag==s.tag } }).firstOrNull()
        val grammar=weak?.let { w -> LocalContent.grammars.first { it.tag==w.tag } }
            ?: if(mode==Intensity.INTENSIVE) LocalContent.grammars.first() else null
        val steps=buildList {
            reviews.forEach { add(LearningStep("$day:review:${it.id}","复习",it.id)) }
            fresh.forEach { add(LearningStep("$day:learn:${it.id}","新词",it.id)) }
            val readingQuestions=if(mode==Intensity.QUICK) reading.questions.take(2) else reading.questions
            readingQuestions.forEach { add(LearningStep("$day:${it.id}","阅读",question=it)) }
            grammar?.let { g -> g.questions.forEach { add(LearningStep("$day:special:${it.id}","语法专项",question=it)) } }
            val backup=words.sortedBy { it.id }
            fun pick(pool: List<VocabWord>, i: Int) = (pool.ifEmpty { selected }.ifEmpty { backup }).let { it[i%it.size] }
            val quiz=buildList {
                repeat(if(mode==Intensity.QUICK) 1 else 5) { i -> add(LocalContent.vocabulary(pick(fresh,i),words,"meaning","$day:quiz:new:$i")) }
                repeat(if(mode==Intensity.QUICK) 1 else 4) { i -> add(LocalContent.vocabulary(pick(reviews,i),words,"meaning","$day:quiz:review:$i")) }
                repeat(if(mode==Intensity.QUICK) 1 else 2) { i -> add(LocalContent.vocabulary(pick(selected,i),words,"spelling","$day:quiz:spell:$i")) }
                repeat(if(mode==Intensity.QUICK) 1 else 2) { i ->
                    val contextual=(selected+backup).distinctBy { it.id }.filter { com.tanhaowen.contextenglish.study.LocalCloze.prompt(it)!=null }
                    add(LocalContent.vocabulary(pick(contextual,i),words,"context","$day:quiz:context:$i"))
                }
                repeat(if(mode==Intensity.QUICK) 1 else 2) { i ->
                    val g=grammar ?: LocalContent.grammars[Math.floorMod(day.hashCode()+i,LocalContent.grammars.size)]
                    add(g.questions[Math.floorMod(day.hashCode(),g.questions.size)].copy(id="$day:quiz:grammar:$i"))
                }
            }
            quiz.forEach { add(LearningStep(it.id,"综合小测",it.wordId,it)) }
        }
        return DailyWorkflow(day,mode,load.minutes,load.reason+
            if(reviews.isEmpty()) " 暂无历史复习词，小测相应题位使用词库巩固。" else "",
            fresh.map { it.id },reviews.map { it.id },reading.id,grammar?.tag.orEmpty(),steps)
    }

    fun audit(workflow: DailyWorkflow, word: VocabWord, pool: List<VocabWord>, rating: Int): DailyWorkflow {
        val step=workflow.step ?: return workflow
        val random=Random(step.key.hashCode())
        if(rating==0 || (random.nextDouble()>LearningParameters.AUDIT_PROBABILITY && workflow.index!=0)) return workflow
        val key="${step.key}:audit"
        if(workflow.steps.any { it.key==key }) return workflow
        val form=listOf("meaning","spelling","context")[random.nextInt(3)]
        val audit=LearningStep(key,"随机抽查",word.id,LocalContent.vocabulary(word,pool,form,key))
        return workflow.copy(steps=workflow.steps.toMutableList().also { it.add((workflow.index+4).coerceAtMost(it.size),audit) })
    }
}

/** Snapshots include generated options and feedback: process death never changes the current question. */
object WorkflowCodec {
    private fun question(q: LocalQuestion) = JSONObject().put("id",q.id).put("prompt",q.prompt)
        .put("options",JSONArray(q.options)).put("answer",q.answer).put("explanation",q.explanation)
        .put("form",q.form).put("tag",q.tag).put("wordId",q.wordId).put("spelling",q.spelling).put("context",q.context)
    fun encode(w: DailyWorkflow): String = JSONObject().put("day",w.day).put("mode",w.mode.name)
        .put("minutes",w.minutes).put("reason",w.reason).put("new",JSONArray(w.newIds)).put("review",JSONArray(w.reviewIds))
        .put("reading",w.readingId).put("grammar",w.grammarTag).put("index",w.index).put("feedback",w.feedback)
        .put("correct",w.correct).put("revealed",w.revealed).put("started",w.started)
        .put("steps",JSONArray().also { a -> w.steps.forEach { s -> a.put(JSONObject().put("key",s.key)
            .put("phase",s.phase).put("wordId",s.wordId).also { j -> s.question?.let { j.put("question",question(it)) } }) } }).toString()
    fun decode(text: String): DailyWorkflow {
        val j=JSONObject(text)
        fun ids(key: String)=j.getJSONArray(key).let { a -> (0 until a.length()).map { a.getLong(it) } }
        val a=j.getJSONArray("steps")
        require(a.length() in 1..10000)
        val steps=(0 until a.length()).map { i -> val s=a.getJSONObject(i)
            val q=s.optJSONObject("question")?.let { p ->
                val options=p.getJSONArray("options").let { o -> (0 until o.length()).map { o.getString(it) } }
                val spelling=p.getString("spelling"); val answer=p.getInt("answer")
                require(spelling.isNotBlank() || (options.size==4 && answer in options.indices))
                LocalQuestion(p.getString("id"),p.getString("prompt"),options,answer,p.getString("explanation"),
                    p.getString("form"),p.getString("tag"),p.getLong("wordId"),spelling,p.getString("context"))
            }
            LearningStep(s.getString("key"),s.getString("phase"),s.getLong("wordId"),q)
        }
        val index=j.getInt("index"); require(index in 0..steps.size)
        require(steps.map { it.key }.distinct().size==steps.size)
        return DailyWorkflow(j.getString("day"),Intensity.valueOf(j.getString("mode")),j.getInt("minutes"),j.getString("reason"),
            ids("new"),ids("review"),j.getString("reading"),j.getString("grammar"),steps,index,j.getString("feedback"),
            j.getBoolean("correct"),j.getBoolean("revealed"),j.optBoolean("started"))
    }
}
