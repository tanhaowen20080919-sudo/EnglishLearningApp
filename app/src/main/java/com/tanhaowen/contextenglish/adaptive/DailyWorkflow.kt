package com.tanhaowen.contextenglish.adaptive

import com.tanhaowen.contextenglish.data.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

data class LearningStep(val key: String, val phase: String, val wordId: Long = 0, val question: LocalQuestion? = null)
data class DailyWorkflow(val day: String, val mode: Intensity, val minutes: Int, val reason: String,
    val newIds: List<Long>, val reviewIds: List<Long>, val readingId: String, val grammarTag: String,
    val steps: List<LearningStep>, val index: Int = 0, val feedback: String = "",
    val correct: Boolean = false, val revealed: Boolean = false, val started: Boolean = false,
    val budgetMinutes: Int = mode.minutes, val stepElapsedMs: Long = 0,val grammarFromWeakness: Boolean = false,val selectedAnswer: String = "") {
    val step get() = steps.getOrNull(index)
    val finished get() = index>=steps.size
    val progress get() = if(steps.isEmpty()) 1f else index.toFloat()/steps.size
}

object WorkflowFactory {
    fun create(day: String, mode: Intensity, minutes: Int, words: List<VocabWord>,
        performance: RecentPerformance, skills: List<SkillScore>, now: Long, preferred: Int = 20): DailyWorkflow {
        val due=words.count { it.state!=WordState.NEW && it.nextReviewTime<=now }
        val weak=WeaknessDetector.priorities(skills.filter { s -> LocalContent.grammars.any { it.tag==s.tag } }).firstOrNull()
        val grammar=weak?.let { w -> LocalContent.grammars.first { it.tag==w.tag } }
            ?: if(mode==Intensity.INTENSIVE) LocalContent.grammars[Math.floorMod(day.hashCode(),LocalContent.grammars.size)] else null
        val short=LearningParameters.shortSession(mode,minutes)
        val load=DailyPlanner.calculate(performance,due,mode,minutes,preferred,if(grammar!=null) LearningParameters.GRAMMAR_SPECIAL_SECONDS else 0)
        val selected=DailyPlanner.choose(words,load,now)
        val fresh=selected.filter { it.state==WordState.NEW }
        val reviews=selected.filter { it.state!=WordState.NEW }
        val history=(reviews+words.filter { it.state!=WordState.NEW }).distinctBy { it.id }
        val reading=LocalContent.readings[Math.floorMod(day.hashCode(),LocalContent.readings.size)]
        val steps=buildList {
            reviews.forEach { add(LearningStep("$day:review:${it.id}","复习",it.id)) }
            fresh.forEach { add(LearningStep("$day:learn:${it.id}","新词",it.id)) }
            val readingQuestions=if(short) reading.questions.take(2) else reading.questions
            readingQuestions.forEach { add(LearningStep("$day:${it.id}","阅读",question=it)) }
            grammar?.let { g -> g.questions.forEach { add(LearningStep("$day:special:${it.id}","语法专项",question=it)) } }
            val backup=words.sortedBy { it.id }
            fun pick(pool: List<VocabWord>, i: Int) = (pool.ifEmpty { selected }.ifEmpty { backup }).let { it[i%it.size] }
            val usedMeanings=mutableSetOf<Long>()
            fun meaningWord(pool: List<VocabWord>): VocabWord = (pool+selected+backup).firstOrNull { it.id !in usedMeanings }
                ?.also { usedMeanings.add(it.id) } ?: pick(pool,0)
            val quiz=buildList {
                repeat(if(short) 1 else 5) { i -> add(LocalContent.vocabulary(meaningWord(fresh),words,"meaning","$day:quiz:new:$i")) }
                repeat(if(short) 1 else 4) { i -> add(LocalContent.vocabulary(meaningWord(history),words,"meaning","$day:quiz:review:$i")) }
                repeat(if(short) 1 else 2) { i -> add(LocalContent.vocabulary(pick(selected,i),words,"spelling","$day:quiz:spell:$i")) }
                repeat(if(short) 1 else 2) { i ->
                    val contextual=(selected+backup).distinctBy { it.id }.filter { com.tanhaowen.contextenglish.study.LocalCloze.prompt(it)!=null }
                    add(LocalContent.vocabulary(pick(contextual,i),words,"context","$day:quiz:context:$i"))
                }
                repeat(if(short) 1 else 2) { i ->
                    val g=grammar ?: LocalContent.grammars[Math.floorMod(day.hashCode()+i,LocalContent.grammars.size)]
                    add(g.questions[Math.floorMod(day.hashCode()+i,g.questions.size)].copy(id="$day:quiz:grammar:$i"))
                }
            }
            quiz.forEach { add(LearningStep(it.id,"综合小测",it.wordId,it)) }
        }
        return DailyWorkflow(day,mode,load.minutes,load.reason+
            if(history.size<4) " 可用历史词不足 4 个，小测不足题位使用今日新词/词库巩固，不重复同一道词义题。" else "",
            fresh.map { it.id },reviews.map { it.id },reading.id,grammar?.tag.orEmpty(),steps,
            budgetMinutes=if(mode==Intensity.CUSTOM) minutes.coerceIn(10,120) else mode.minutes,grammarFromWeakness=weak!=null)
    }

    /** Preserve every answered step and every due review when shortening an active plan. */
    fun resize(old: DailyWorkflow, fresh: DailyWorkflow): DailyWorkflow {
        if(!old.started) return fresh
        val completed=old.steps.take(old.index)
        val locked=old.step?.takeIf { old.revealed || old.feedback.isNotBlank() || old.stepElapsedMs>0 }
        val fixed=completed+listOfNotNull(locked)
        val keys=fixed.map { it.key }.toSet()
        val short=LearningParameters.shortSession(fresh.mode,fresh.budgetMinutes)
        val reviews=(old.steps.drop(old.index).filter { it.phase=="复习" }+fresh.steps.filter { it.phase=="复习" })
            .distinctBy { it.key }.filter { it.key !in keys }
        val newLimit=(fresh.newIds.size-fixed.count { it.phase=="新词" }).coerceAtLeast(0)
        val newWords=fresh.steps.filter { it.phase=="新词" && it.key !in keys }.take(newLimit)
        val audits=old.steps.drop(old.index).filter { it.phase=="随机抽查" && it.key !in keys }
        val reading=LocalContent.readings.first { it.id==old.readingId }
        val readingSteps=reading.questions.take(if(short) 2 else reading.questions.size)
            .map { LearningStep("${old.day}:${it.id}","阅读",question=it) }.filter { it.key !in keys }
        val startedGrammar=fixed.any { it.phase=="语法专项" }
        val grammarTag=if(startedGrammar) old.grammarTag else fresh.grammarTag
        val grammar=LocalContent.grammars.firstOrNull { it.tag==grammarTag }
        val grammarSteps=grammar?.questions.orEmpty().map { LearningStep("${old.day}:special:${it.id}","语法专项",question=it) }.filter { it.key !in keys }
        val quiz=fresh.steps.filter { it.phase=="综合小测" && it.key !in keys }
        val steps=fixed+reviews+newWords+audits+readingSteps+grammarSteps+quiz
        return old.copy(mode=fresh.mode,budgetMinutes=fresh.budgetMinutes,reason=fresh.reason+" 已完成内容、正在作答的题目和到期复习均保留。",
            steps=steps,newIds=steps.filter { it.phase=="新词" }.map { it.wordId }.distinct(),
            reviewIds=steps.filter { it.phase=="复习" }.map { it.wordId }.distinct(),grammarTag=grammarTag,
            grammarFromWeakness=if(startedGrammar) old.grammarFromWeakness else fresh.grammarFromWeakness,
            minutes=kotlin.math.ceil((steps.count { it.phase=="复习" }*27+steps.count { it.phase=="新词" }*54+
                (if(short) LearningParameters.QUICK_FIXED_SECONDS else LearningParameters.STANDARD_FIXED_SECONDS)+
                if(grammar!=null) LearningParameters.GRAMMAR_SPECIAL_SECONDS else 0)/60.0).toInt())
    }

    fun readingGrammarDiagnosis(w: DailyWorkflow, tag: String): DailyWorkflow {
        val step=w.step ?: return w
        require(step.question?.form=="reading" && w.feedback.isNotBlank() && !w.correct)
        val lesson=LocalContent.grammars.first { it.tag==tag }
        val key="${step.key}:diagnose:$tag"
        if(w.steps.any { it.key==key }) return w
        val q=lesson.questions[Math.floorMod(key.hashCode(),lesson.questions.size)].copy(id=key)
        return w.copy(steps=w.steps.toMutableList().also { it.add(w.index+1,LearningStep(key,"阅读语法诊断",question=q)) })
    }

    fun audit(workflow: DailyWorkflow, word: VocabWord, pool: List<VocabWord>, rating: Int): DailyWorkflow {
        val step=workflow.step ?: return workflow
        val random=Random(step.key.hashCode())
        if(rating==0 || (random.nextDouble()>LearningParameters.AUDIT_PROBABILITY && workflow.index!=0)) return workflow
        val key="${step.key}:audit"
        if(workflow.steps.any { it.key==key }) return workflow
        val forms=listOf("meaning","spelling","context")+if(word.word.lowercase() in ContextSenseBank.meanings) listOf("sense") else emptyList()
        val form=forms[random.nextInt(forms.size)]
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
        .put("budgetMinutes",w.budgetMinutes).put("stepElapsedMs",w.stepElapsedMs)
        .put("grammarFromWeakness",w.grammarFromWeakness)
        .put("selectedAnswer",w.selectedAnswer)
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
                require(spelling.isNotBlank() || (options.size in 2..8 && answer in options.indices))
                LocalQuestion(p.getString("id"),p.getString("prompt"),options,answer,p.getString("explanation"),
                    p.getString("form"),p.getString("tag"),p.getLong("wordId"),spelling,p.getString("context"))
            }
            LearningStep(s.getString("key"),s.getString("phase"),s.getLong("wordId"),q)
        }
        val index=j.getInt("index"); require(index in 0..steps.size)
        require(steps.map { it.key }.distinct().size==steps.size)
        return DailyWorkflow(j.getString("day"),Intensity.valueOf(j.getString("mode")),j.getInt("minutes"),j.getString("reason"),
            ids("new"),ids("review"),j.getString("reading"),j.getString("grammar"),steps,index,j.getString("feedback"),
            j.getBoolean("correct"),j.getBoolean("revealed"),j.optBoolean("started"),
            j.optInt("budgetMinutes",Intensity.valueOf(j.getString("mode")).minutes),j.optLong("stepElapsedMs",0).coerceAtLeast(0),j.optBoolean("grammarFromWeakness",false),j.optString("selectedAnswer",""))
    }
}
