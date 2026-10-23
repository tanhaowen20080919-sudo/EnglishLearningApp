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
}

data class Memory(val mastery: Double = 0.0, val stability: Double = .6,
    val difficulty: Double = .5, val lapses: Int = 0, val streak: Int = 0,
    val recent: Double = .5, val forms: Set<String> = emptySet(),
    val contexts: Set<String> = emptySet(), val last: Long = 0)
data class Evidence(val rating: Int, val form: String, val context: String = "", val objective: Boolean = true)
data class MemoryUpdate(val memory: Memory, val next: Long, val mastered: Boolean)

object AdaptiveReview {
    fun update(old: Memory, evidence: Evidence, now: Long): MemoryUpdate {
        require(evidence.rating in 0..2)
        val elapsed = if(old.last == 0L) 0.0 else ((now-old.last).coerceAtLeast(0)/LearningParameters.DAY.toDouble())
        val retention = exp(-elapsed / old.stability.coerceAtLeast(.05))
        val quality = evidence.rating / 2.0
        val right = evidence.rating == 2
        // Self report provides a small signal; objective errors override optimistic self reports.
        val strength = if(evidence.objective) .32 else .08
        val mastery = (old.mastery + strength*(quality-old.mastery) -
            if(evidence.objective && !right) .18 else 0.0).coerceIn(0.0,1.0)
        val forms = if(right && evidence.objective) old.forms+evidence.form else old.forms
        val contexts = if(right && evidence.objective && evidence.form=="context" && evidence.context.isNotBlank()) old.contexts+evidence.context else old.contexts
        val spaced = elapsed >= .5 || old.last == 0L
        val streak = if(right && evidence.objective) old.streak + if(spaced) 1 else 0 else if(!right) 0 else old.streak
        val difficulty = (old.difficulty + if(right) -.035 else .14).coerceIn(.1,.95)
        val recent = if(evidence.objective) .7*old.recent+.3*quality else old.recent
        val variety = min(forms.size,3)*.13 + min(contexts.size,3)*.09
        val growth = 1.12 + (1-difficulty)*.65 + variety + (1-retention)*.4 + recent*.25
        val stability = when {
            !right -> old.stability * (if(evidence.rating==0) .28 else .55)
            !evidence.objective || !spaced -> old.stability
            else -> old.stability*growth
        }.coerceIn(.05,LearningParameters.MAX_INTERVAL_DAYS)
        val interval = when {
            evidence.rating==0 -> 5*60_000L
            evidence.rating==1 -> min(4*3_600_000L,(stability*LearningParameters.DAY*.35).toLong())
            !evidence.objective -> min(LearningParameters.DAY,(stability*LearningParameters.DAY).toLong())
            else -> (stability*(.55+mastery*.45)*LearningParameters.DAY).toLong()
        }
        val updated = Memory(mastery,stability,difficulty,old.lapses+if(evidence.objective && !right) 1 else 0,
            streak,recent,forms,contexts,if(evidence.objective) now else old.last)
        return MemoryUpdate(updated,now+interval,mastery>=.8 && streak>=4 && forms.size>=3 && contexts.size>=2)
    }
}

enum class Intensity(val label: String, val minutes: Int) {
    QUICK("快速",15), STANDARD("标准",45), INTENSIVE("强化",60), CUSTOM("自定义",45)
}
data class RecentPerformance(val newAccuracy: Double = .75, val audit: Double = .75,
    val spelling: Double = .75, val context: Double = .75, val quiz: Double = .75,
    val completion: Double = 1.0)
data class Workload(val newCount: Int, val reviewCount: Int, val minutes: Int, val reason: String)

object DailyPlanner {
    fun calculate(performance: RecentPerformance, due: Int, mode: Intensity,
        availableMinutes: Int = mode.minutes, preferredNew: Int = 20): Workload {
        val score = performance.run { newAccuracy*.2+audit*.2+spelling*.15+context*.15+quiz*.2+completion*.1 }
        val base = (preferredNew.coerceIn(15,25) + ((score-.75)*20).roundToInt() -
            (due/25).coerceAtMost(5)).coerceIn(LearningParameters.MIN_NEW,LearningParameters.MAX_NEW)
        val minutes = if(mode==Intensity.CUSTOM) availableMinutes.coerceIn(10,120) else mode.minutes
        val capacity = ((minutes-5).coerceAtLeast(0)*60 - due*18).coerceAtLeast(0)/45
        val fresh = min(base,capacity).let { if(mode==Intensity.QUICK) min(5,it) else it }
        // Every due review remains in the plan, even when this exceeds the time budget.
        val estimate = max(minutes,ceil((due*18+fresh*45+300)/60.0).toInt())
        return Workload(fresh,due,estimate,"近期综合表现 ${(score*100).roundToInt()}%；到期 $due 词优先，按可用 $minutes 分钟安排新词。"+
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
        (1-accuracy)*min(1.0,total/8.0)
}
object WeaknessDetector {
    fun priorities(skills: List<SkillScore>): List<SkillScore> = skills.filter {
        it.total>=LearningParameters.MIN_DIAGNOSTIC_SAMPLES && it.accuracy<LearningParameters.WEAK_THRESHOLD
    }.sortedByDescending { it.weakness }.take(3)
}
