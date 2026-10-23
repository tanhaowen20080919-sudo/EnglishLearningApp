package com.tanhaowen.contextenglish.adaptive

import com.tanhaowen.contextenglish.data.VocabWord
import com.tanhaowen.contextenglish.data.WordState
import kotlin.math.*

/** All scheduling parameters live here. Time and evidence are explicit for reproducible tests. */
object LearningParameters {
    const val DAY = 86_400_000L
    const val BASE_NEW = 20
    const val MIN_NEW = 15
    const val MAX_NEW = 25
    const val WEAK_THRESHOLD = .65
    const val MIN_DIAGNOSTIC_SAMPLES = 3
    const val AUDIT_PROBABILITY = .45
    const val MAX_INTERVAL_DAYS = 180.0
    const val SELF_STRENGTH = .08
    const val OBJECTIVE_STRENGTH = .32
    const val ERROR_PENALTY = .18
    const val LAPSE_GROWTH_PENALTY = .06
    const val STREAK_GROWTH_BONUS = .035
    const val MIN_SPACING_DAYS = .5
    const val REVIEW_SECONDS = 18
    const val NEW_SECONDS = 45
    const val AUDIT_SECONDS = 20
    const val STANDARD_QUIZ_SECONDS = 8 * 60
    const val STANDARD_READING_SECONDS = 10 * 60
    const val QUICK_QUIZ_SECONDS = 3 * 60
    const val QUICK_READING_SECONDS = 3 * 60
    const val STANDARD_FIXED_SECONDS = STANDARD_READING_SECONDS + STANDARD_QUIZ_SECONDS
    const val QUICK_FIXED_SECONDS = QUICK_READING_SECONDS + QUICK_QUIZ_SECONDS
    const val GRAMMAR_SPECIAL_SECONDS = 150
    const val LOCAL_QUIZ_REPEAT_THRESHOLD = .6
    const val INITIAL_STABILITY = .6
    const val INITIAL_DIFFICULTY = .5
    const val MIN_STABILITY = .05
    const val MIN_DIFFICULTY = .1
    const val MAX_DIFFICULTY = .95
    const val DIFFICULTY_SUCCESS = .035
    const val DIFFICULTY_FAILURE = .14
    const val RECENT_WEIGHT = .3
    const val VARIETY_CAP = 3
    const val FORM_BONUS = .13
    const val CONTEXT_BONUS = .09
    const val GROWTH_BASE = 1.12
    const val DIFFICULTY_GROWTH = .65
    const val FORGETTING_GROWTH = .4
    const val RECENT_GROWTH = .25
    const val MIN_SUCCESS_GROWTH = 1.05
    const val MAX_HISTORY_STREAK = 8
    const val MAX_HISTORY_LAPSES = 12
    const val ERROR_STABILITY = .28
    const val FUZZY_STABILITY = .55
    const val FUZZY_INTERVAL = .35
    const val RETRY_MS = 5*60_000L
    const val FUZZY_MAX_MS = 4*3_600_000L
    const val CORRECT_INTERVAL_BASE = .55
    const val MASTERY_INTERVAL_WEIGHT = .45
    const val STABLE_MASTERY = .8
    const val STABLE_STREAK = 4
    const val STABLE_FORMS = 3
    const val STABLE_CONTEXTS = 2
    const val PERFORMANCE_TARGET = .75
    const val VOLUME_RESPONSE = 20
    const val BACKLOG_PER_DISCOUNT = 25
    const val MAX_BACKLOG_DISCOUNT = 5
    const val QUICK_NEW_CAP = 5
    const val WEAK_EVIDENCE_TARGET = 8.0
    const val AI_READING_HELP_THRESHOLD = .8
    val PERFORMANCE_WEIGHTS = listOf(.2, .2, .15, .15, .2, .1)
    fun shortSession(mode: Intensity,minutes: Int)=mode==Intensity.QUICK || mode==Intensity.CUSTOM && minutes<=20
}

data class Memory(val mastery: Double = 0.0, val stability: Double = LearningParameters.INITIAL_STABILITY,
    val difficulty: Double = LearningParameters.INITIAL_DIFFICULTY, val lapses: Int = 0, val streak: Int = 0,
    val recent: Double = LearningParameters.INITIAL_DIFFICULTY, val forms: Set<String> = emptySet(),
    val contexts: Set<String> = emptySet(), val last: Long = 0)
data class Evidence(val rating: Int, val form: String, val context: String = "", val objective: Boolean = true)
data class MemoryUpdate(val memory: Memory, val next: Long, val mastered: Boolean)

object AdaptiveReview {
    fun update(old: Memory, evidence: Evidence, now: Long): MemoryUpdate {
        require(evidence.rating in 0..2)
        val elapsed = if(old.last == 0L) 0.0 else ((now-old.last).coerceAtLeast(0)/LearningParameters.DAY.toDouble())
        val retention = exp(-elapsed / old.stability.coerceAtLeast(LearningParameters.MIN_STABILITY))
        val quality = evidence.rating / 2.0
        val right = evidence.rating == 2
        // Self report provides a small signal; objective errors override optimistic self reports.
        val strength = if(evidence.objective) LearningParameters.OBJECTIVE_STRENGTH else LearningParameters.SELF_STRENGTH
        val mastery = (old.mastery + strength*(quality-old.mastery) -
            if(evidence.objective && !right) LearningParameters.ERROR_PENALTY else 0.0).coerceIn(0.0,1.0)
        val forms = if(right && evidence.objective) old.forms+evidence.form else old.forms
        val contexts = if(right && evidence.objective && evidence.form=="context" && evidence.context.isNotBlank()) old.contexts+evidence.context else old.contexts
        val spaced = elapsed >= LearningParameters.MIN_SPACING_DAYS || old.last == 0L
        val streak = if(right && evidence.objective) old.streak + if(spaced) 1 else 0 else if(!right) 0 else old.streak
        val difficulty = (old.difficulty + if(right) -LearningParameters.DIFFICULTY_SUCCESS else LearningParameters.DIFFICULTY_FAILURE)
            .coerceIn(LearningParameters.MIN_DIFFICULTY,LearningParameters.MAX_DIFFICULTY)
        val recent = if(evidence.objective) (1-LearningParameters.RECENT_WEIGHT)*old.recent+LearningParameters.RECENT_WEIGHT*quality else old.recent
        val variety = min(forms.size,LearningParameters.VARIETY_CAP)*LearningParameters.FORM_BONUS + min(contexts.size,LearningParameters.VARIETY_CAP)*LearningParameters.CONTEXT_BONUS
        val history = (1 + min(streak, LearningParameters.MAX_HISTORY_STREAK)*LearningParameters.STREAK_GROWTH_BONUS) /
            (1 + min(old.lapses, LearningParameters.MAX_HISTORY_LAPSES)*LearningParameters.LAPSE_GROWTH_PENALTY)
        val growth = (LearningParameters.GROWTH_BASE + (1-difficulty)*LearningParameters.DIFFICULTY_GROWTH + variety +
            (1-retention)*LearningParameters.FORGETTING_GROWTH + recent*LearningParameters.RECENT_GROWTH) * history
        val stability = when {
            !right -> old.stability * (if(evidence.rating==0) LearningParameters.ERROR_STABILITY else LearningParameters.FUZZY_STABILITY)
            !evidence.objective || !spaced -> old.stability
            else -> old.stability*growth.coerceAtLeast(LearningParameters.MIN_SUCCESS_GROWTH)
        }.coerceIn(LearningParameters.MIN_STABILITY,LearningParameters.MAX_INTERVAL_DAYS)
        val interval = when {
            evidence.rating==0 -> LearningParameters.RETRY_MS
            evidence.rating==1 -> min(LearningParameters.FUZZY_MAX_MS,(stability*LearningParameters.DAY*LearningParameters.FUZZY_INTERVAL).toLong())
            !evidence.objective -> min(LearningParameters.DAY,(stability*LearningParameters.DAY).toLong())
            else -> (stability*(LearningParameters.CORRECT_INTERVAL_BASE+mastery*LearningParameters.MASTERY_INTERVAL_WEIGHT)*LearningParameters.DAY).toLong()
        }
        val updated = Memory(mastery,stability,difficulty,old.lapses+if(evidence.objective && !right) 1 else 0,
            streak,recent,forms,contexts,if(evidence.objective) now else old.last)
        return MemoryUpdate(updated,now+interval,mastery>=LearningParameters.STABLE_MASTERY && streak>=LearningParameters.STABLE_STREAK &&
            forms.size>=LearningParameters.STABLE_FORMS && contexts.size>=LearningParameters.STABLE_CONTEXTS)
    }
}

enum class Intensity(val label: String, val minutes: Int) {
    QUICK("快速",15), STANDARD("标准",45), INTENSIVE("强化",60), CUSTOM("自定义",45)
}
data class RecentPerformance(val newAccuracy: Double = LearningParameters.PERFORMANCE_TARGET, val audit: Double = LearningParameters.PERFORMANCE_TARGET,
    val spelling: Double = LearningParameters.PERFORMANCE_TARGET, val context: Double = LearningParameters.PERFORMANCE_TARGET, val quiz: Double = LearningParameters.PERFORMANCE_TARGET,
    val completion: Double = LearningParameters.PERFORMANCE_TARGET,val sampleCount: Int = 0)
data class Workload(val newCount: Int, val reviewCount: Int, val minutes: Int, val reason: String)

object DailyPlanner {
    fun calculate(performance: RecentPerformance, due: Int, mode: Intensity,
        availableMinutes: Int = mode.minutes, preferredNew: Int = 20,extraSeconds: Int = 0): Workload {
        val score = performance.run { listOf(newAccuracy,audit,spelling,context,quiz,completion) }
            .zip(LearningParameters.PERFORMANCE_WEIGHTS).sumOf { (value,weight) -> value.coerceIn(0.0,1.0)*weight }
        val base = (preferredNew.coerceIn(LearningParameters.MIN_NEW,LearningParameters.MAX_NEW) +
            ((score-LearningParameters.PERFORMANCE_TARGET)*LearningParameters.VOLUME_RESPONSE).roundToInt() -
            (due/LearningParameters.BACKLOG_PER_DISCOUNT).coerceAtMost(LearningParameters.MAX_BACKLOG_DISCOUNT)).coerceIn(LearningParameters.MIN_NEW,LearningParameters.MAX_NEW)
        val minutes = if(mode==Intensity.CUSTOM) availableMinutes.coerceIn(10,120) else mode.minutes
        val short=LearningParameters.shortSession(mode,minutes)
        val fixed = (if(short) LearningParameters.QUICK_FIXED_SECONDS else LearningParameters.STANDARD_FIXED_SECONDS)+extraSeconds
        val reviewSeconds=LearningParameters.REVIEW_SECONDS+LearningParameters.AUDIT_PROBABILITY*LearningParameters.AUDIT_SECONDS
        val newSeconds=LearningParameters.NEW_SECONDS+LearningParameters.AUDIT_PROBABILITY*LearningParameters.AUDIT_SECONDS
        val capacity = floor((minutes*60-fixed-due*reviewSeconds).coerceAtLeast(0.0)/newSeconds).toInt()
        val fresh = min(base,capacity).let { if(short) min(LearningParameters.QUICK_NEW_CAP,it) else it }
        // Every due review remains in the plan, even when this exceeds the time budget.
        val estimate = ceil((due*reviewSeconds+fresh*newSeconds+fixed)/60.0).toInt()
        val evidence=if(performance.sampleCount==0) "暂无客观作答样本，采用保守初始参数；" else "近7天 ${performance.sampleCount} 次客观作答，排程参考分 ${(score*100).roundToInt()}%（缺项用初始值）；"
        return Workload(fresh,due,estimate,evidence+"到期 $due 词优先，按可用 $minutes 分钟安排新词。"+
            if(fresh<15) "复习积压或时间较少，暂减新内容。" else "新词在 15～25 范围内调整。")
    }
    fun choose(words: List<VocabWord>, load: Workload, at: Long): List<VocabWord> {
        val due = words.filter { it.state!=WordState.NEW && it.nextReviewTime<=at }
            .sortedWith(compareByDescending<VocabWord> { it.weak }.thenBy { it.nextReviewTime }.thenByDescending { it.mistakeCount })
        return due+words.filter { it.state==WordState.NEW }.sortedWith(compareBy<VocabWord> { it.importance }.thenBy { it.id }).take(load.newCount)
    }
}

data class SkillScore(val tag: String, val total: Int, val correct: Int, val millis: Long = 0) {
    val accuracy: Double get() = if(total==0) 1.0 else correct.toDouble()/total
    val weakness: Double get() = if(total<LearningParameters.MIN_DIAGNOSTIC_SAMPLES) 0.0 else
        (1-accuracy)*min(1.0,total/LearningParameters.WEAK_EVIDENCE_TARGET)
}
object WeaknessDetector {
    fun recommendation(tag: String): String = when(tag) {
        "拼写" -> "先遮住英文按词义拼写，再检查漏字母与词形；错词进入近期复习。"
        "语境选词","词义猜测题" -> "先定位前后句线索，再解释该词在当前句中的含义。"
        "词义辨认" -> "先主动回忆，再用客观选择题核对，避免只凭熟悉感。"
        "推断题" -> "先找支持结论的原文证据，排除原文无法推出的选项。"
        "主旨题" -> "概括每段共同主题，再排除只覆盖一个细节的标题。"
        "细节题" -> "回到题干关键词所在句，逐项核对范围、时间和对象。"
        "指代题" -> "向前寻找名词，再把它代回原句检查单复数和语意。"
        else -> "先复盘 $tag 的知识卡，再做 3～5 道专项并解释错项。"
    }
    fun priorities(skills: List<SkillScore>): List<SkillScore> = skills.filter {
        it.total>=LearningParameters.MIN_DIAGNOSTIC_SAMPLES && it.accuracy<LearningParameters.WEAK_THRESHOLD
    }.sortedWith(compareByDescending<SkillScore> { it.weakness }.thenBy { it.tag }).take(3)
}
