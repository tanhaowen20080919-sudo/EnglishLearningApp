package com.tanhaowen.contextenglish.adaptive

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.tanhaowen.contextenglish.data.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

class AdaptiveRepository(private val legacy: EnglishRepository,private val now: () -> Long = System::currentTimeMillis) {
    private val db get() = legacy.database.writableDatabase
    fun load(day: String): DailyWorkflow? = db.rawQuery("SELECT snapshot FROM learning_days WHERE day=?",arrayOf(day)).use {
        if(it.moveToFirst()) WorkflowCodec.decode(it.getString(0)) else null
    }
    fun save(w: DailyWorkflow) {
        db.insertWithOnConflict("learning_days",null,ContentValues().apply {
            put("day",w.day); put("snapshot",WorkflowCodec.encode(w)); put("mode",w.mode.name)
            put("planned",w.steps.size); put("completed",w.index)
        },SQLiteDatabase.CONFLICT_IGNORE)
        db.update("learning_days",ContentValues().apply {
            put("snapshot",WorkflowCodec.encode(w)); put("mode",w.mode.name); put("planned",w.steps.size); put("completed",w.index)
        },"day=?",arrayOf(w.day))
    }
    fun saveClock(day: String, key: String, millis: Long) {
        db.beginTransaction()
        try {
            val stored=load(day)
            if(stored?.step?.key==key && stored.feedback.isBlank()) save(stored.copy(stepElapsedMs=millis.coerceAtLeast(stored.stepElapsedMs)))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun memory(word: VocabWord): Memory = db.rawQuery("SELECT * FROM word_memory WHERE word_id=?",arrayOf(word.id.toString())).use { c ->
        if(!c.moveToFirst()) return Memory(mastery=word.familiarity/100.0,lapses=word.mistakeCount,last=word.lastReviewTime)
        fun d(k: String)=c.getDouble(c.getColumnIndexOrThrow(k))
        fun n(k: String)=c.getInt(c.getColumnIndexOrThrow(k))
        fun set(k: String)=JSONArray(c.getString(c.getColumnIndexOrThrow(k))).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        Memory(d("mastery"),d("stability"),d("difficulty"),n("lapses"),n("streak"),d("recent"),set("forms"),set("contexts"),c.getLong(c.getColumnIndexOrThrow("last_at")))
    }
    fun applyEvidence(id: Long, evidence: Evidence, now: Long = System.currentTimeMillis()) {
        val w=requireNotNull(legacy.loadWord(id))
        val result=AdaptiveReview.update(memory(w),evidence,now)
        val m=result.memory
        db.insertWithOnConflict("word_memory",null,ContentValues().apply {
            put("word_id",id); put("mastery",m.mastery); put("stability",m.stability); put("difficulty",m.difficulty)
            put("lapses",m.lapses); put("streak",m.streak); put("recent",m.recent)
            put("forms",JSONArray(m.forms.toList()).toString()); put("contexts",JSONArray(m.contexts.toList()).toString()); put("last_at",m.last)
        },SQLiteDatabase.CONFLICT_REPLACE)
        db.update("words",ContentValues().apply {
            put("state",if(result.mastered) "MASTERED" else "LEARNING"); put("familiarity",(m.mastery*100).toInt())
            put("weak",if(evidence.rating==0 || m.lapses>0 && m.mastery<.65) 1 else 0)
            put("mistake_count",m.lapses); put("streak",m.streak); put("seen_count",w.seenCount+1)
            put("review_count",w.reviewCount+if(evidence.objective) 1 else 0)
            put("correct_count",w.correctCount+if(evidence.objective && evidence.rating==2) 1 else 0)
            put("last_review_at",now); put("next_review_at",result.next); put("updated_at",now)
        },"id=?",arrayOf(id.toString()))
        db.insertOrThrow("study_events",null,ContentValues().apply {
            put("event_type",if(evidence.objective) evidence.form else "self_report"); put("word_id",id)
            put("correct",if(evidence.rating==2) 1 else 0); put("created_at",now)
        })
    }
    /** Answer + mastery + workflow snapshot commit together. The unique key prevents duplicate grading. */
    fun answer(before: DailyWorkflow, after: DailyWorkflow, correct: Boolean, elapsed: Long, rating: Int? = null,answerValue: String? = null) {
        val step=requireNotNull(before.step); val q=step.question; val now=now()
        db.beginTransaction()
        try {
            val stored=load(before.day)
            check(stored==null || stored.index==before.index && stored.feedback.isBlank()) { "进度已更新或该题已评分，请重新加载" }
            val id=db.insertWithOnConflict("diagnostic_attempts",null,ContentValues().apply {
                put("task_key",step.key); put("day",before.day); put("phase",step.phase); put("form",q?.form ?: "self")
                put("tag",q?.tag.orEmpty()); put("word_id",q?.wordId ?: step.wordId); put("correct",if(correct) 1 else 0)
                put("elapsed_ms",elapsed.coerceIn(0,86_400_000)); put("context",q?.context.orEmpty()); put("created_at",now)
                put("reading_id",if(q?.form=="reading") before.readingId else "")
                put("new_cohort",if((q?.wordId ?: step.wordId) in before.newIds) 1 else 0)
                put("selected_answer",answerValue ?: rating?.toString().orEmpty())
            },SQLiteDatabase.CONFLICT_IGNORE)
            if(id!=-1L) {
                val wordId=q?.wordId ?: step.wordId
                if(wordId>0) applyEvidence(wordId,Evidence(rating ?: if(correct) 2 else 0,q?.form ?: "self",q?.context.orEmpty(),q!=null),now)
                db.execSQL("UPDATE learning_days SET active_ms=active_ms+? WHERE day=?",arrayOf(elapsed.coerceIn(0,86_400_000),before.day))
            }
            save(after)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun results(day: String): List<SkillScore> = buildList {
        db.rawQuery("SELECT phase,COUNT(*),SUM(correct),SUM(elapsed_ms) FROM diagnostic_attempts WHERE day=? AND form!='self' GROUP BY phase",arrayOf(day)).use {
            while(it.moveToNext()) add(SkillScore(it.getString(0),it.getInt(1),it.getInt(2),it.getLong(3)))
        }
    }
    fun quizRepetition(plan: DailyWorkflow): Double {
        val since=LocalDate.parse(plan.day).minusDays(7).toString()
        val seen=mutableSetOf<String>()
        db.rawQuery("SELECT snapshot FROM learning_days WHERE day>=? AND day<? AND completed>0",arrayOf(since,plan.day)).use { c ->
            while(c.moveToNext()) {
                val old=WorkflowCodec.decode(c.getString(0))
                old.steps.take(old.index).filter { it.phase=="综合小测" }.mapNotNullTo(seen) { it.question?.prompt }
            }
        }
        val quiz=plan.steps.filter { it.phase=="综合小测" }.mapNotNull { it.question }
        return if(quiz.isEmpty()) 0.0 else quiz.count { it.prompt in seen }.toDouble()/quiz.size
    }
    fun skills(today: LocalDate = LocalDate.now(), lifetime: Boolean = false): List<SkillScore> = buildList {
        val window=if(lifetime) "1=1" else """(form='reading' AND day||'|'||reading_id IN
            (SELECT day||'|'||reading_id FROM diagnostic_attempts WHERE form='reading' AND day<=?
            GROUP BY day,reading_id ORDER BY MAX(created_at) DESC,day DESC LIMIT 7)) OR (form!='reading' AND day>=?)"""
        val args=if(lifetime) arrayOf(today.toString()) else arrayOf(today.toString(),today.toString(),today.minusDays(30).toString())
        db.rawQuery("""SELECT CASE form WHEN 'spelling' THEN '拼写' WHEN 'context' THEN '语境选词'
            WHEN 'meaning' THEN '词义辨认' ELSE tag END skill,COUNT(*),SUM(correct),SUM(elapsed_ms)
            FROM diagnostic_attempts WHERE form!='self' AND day<=? AND ($window) GROUP BY skill""",args).use {
            while(it.moveToNext()) add(SkillScore(it.getString(0),it.getInt(1),it.getInt(2),it.getLong(3)))
        }
    }
    fun performance(today: LocalDate = LocalDate.now()): RecentPerformance {
        val since=today.minusDays(7).toString()
        fun accuracy(where: String): Double = db.rawQuery("SELECT AVG(correct) FROM diagnostic_attempts WHERE day>=? AND day<=? AND ($where)",arrayOf(since,today.toString())).use {
            if(it.moveToFirst() && !it.isNull(0)) it.getDouble(0) else .75
        }
        val completion=db.rawQuery("SELECT AVG(completed*1.0/MAX(planned,1)) FROM learning_days WHERE day>=? AND day<?",arrayOf(since,today.toString())).use {
            if(it.moveToFirst() && !it.isNull(0)) it.getDouble(0) else LearningParameters.PERFORMANCE_TARGET
        }
        return RecentPerformance(accuracy("new_cohort=1 AND form!='self'"),accuracy("phase='随机抽查'"),
            accuracy("form='spelling'"),accuracy("form='context'"),accuracy("phase='综合小测'"),completion,
            db.rawQuery("SELECT COUNT(*) FROM diagnostic_attempts WHERE form!='self' AND day>=? AND day<=?",arrayOf(since,today.toString())).use { it.moveToFirst();it.getInt(0) })
    }
    fun readingWord(day: String, reading: String, word: String, meaning: String, impacts: Boolean) {
        db.insertWithOnConflict("reading_words",null,ContentValues().apply {
            put("day",day); put("reading_id",reading); put("word",word); put("meaning",meaning); put("impacts",if(impacts) 1 else 0)
        },SQLiteDatabase.CONFLICT_REPLACE)
        if(impacts) db.execSQL("UPDATE words SET weak=1,favorite=1 WHERE word=? COLLATE NOCASE",arrayOf(word))
    }
    fun lookedUp(day: String, reading: String? = null): Map<String,Boolean> = buildMap {
        val filter=if(reading==null) "day=?" else "day=? AND reading_id=?"
        val args=if(reading==null) arrayOf(day) else arrayOf(day,reading)
        db.rawQuery("SELECT word,impacts FROM reading_words WHERE $filter",args).use { while(it.moveToNext()) put(it.getString(0),it.getInt(1)==1) }
    }
    fun errorReason(key: String, reason: String) {
        require(reason in listOf("生词影响","证据定位","推断过度","句子结构"))
        db.update("diagnostic_attempts",ContentValues().apply { put("error_reason",reason) },"task_key=?",arrayOf(key))
    }
    fun attemptContext(key: String): JSONObject = db.rawQuery("SELECT selected_answer,error_reason,correct,elapsed_ms FROM diagnostic_attempts WHERE task_key=?",arrayOf(key)).use {
        if(!it.moveToFirst()) JSONObject() else JSONObject().put("selectedAnswer",it.getString(0)).put("reportedReason",it.getString(1)).put("correct",it.getInt(2)==1).put("elapsedMs",it.getLong(3))
    }
    fun weeklySummary(): JSONObject {
        val since=LocalDate.now().minusDays(6).toString()
        val result=JSONObject().put("periodStart",since).put("periodEnd",LocalDate.now().toString())
        result.put("skills",JSONArray().also { a -> db.rawQuery("SELECT form,tag,COUNT(*),SUM(correct),SUM(elapsed_ms) FROM diagnostic_attempts WHERE day>=? GROUP BY form,tag",arrayOf(since)).use {
            while(it.moveToNext()) a.put(JSONObject().put("form",it.getString(0)).put("tag",it.getString(1)).put("total",it.getInt(2)).put("correct",it.getInt(3)).put("elapsedMs",it.getLong(4)))
        } })
        result.put("days",JSONArray().also { a -> db.rawQuery("SELECT day,mode,planned,completed,active_ms FROM learning_days WHERE day>=? AND day<=? ORDER BY day",arrayOf(since,LocalDate.now().toString())).use {
            while(it.moveToNext()) a.put(JSONObject().put("day",it.getString(0)).put("mode",it.getString(1)).put("planned",it.getInt(2)).put("completed",it.getInt(3)).put("activeMs",it.getLong(4)))
        } })
        result.put("note","仅此时间窗内的数据；没有对照周，不能断言进步幅度。")
        return result
    }
    fun summary(): JSONObject {
        val root=JSONObject()
        db.rawQuery("SELECT SUM(state='MASTERED'),SUM(state='LEARNING'),SUM(mistake_count>=3) FROM words",null).use {
            it.moveToFirst();root.put("vocabulary",JSONObject().put("stable",it.getInt(0)).put("learning",it.getInt(1)).put("frequentForgetting",it.getInt(2)))
        }
        val skills=skills()
        root.put("skills",JSONArray().also { a -> skills.forEach { a.put(JSONObject().put("tag",it.tag).put("total",it.total).put("correct",it.correct).put("elapsedMs",it.millis)) } })
        root.put("priorities",JSONArray(WeaknessDetector.priorities(skills).map { it.tag }))
        root.put("lifetimeSkills",JSONArray().also { a -> skills(lifetime=true).forEach { a.put(JSONObject().put("tag",it.tag).put("total",it.total).put("correct",it.correct).put("elapsedMs",it.millis)) } })
        db.rawQuery("SELECT COUNT(DISTINCT day||'|'||reading_id),COUNT(*),SUM(elapsed_ms) FROM diagnostic_attempts WHERE form='reading'",null).use {
            it.moveToFirst(); root.put("readingArticles",it.getInt(0)).put("readingQuestions",it.getInt(1)).put("readingMs",it.getLong(2))
        }
        db.rawQuery("SELECT COUNT(*),COALESCE(SUM(impacts),0) FROM reading_words",null).use { it.moveToFirst(); root.put("unknownWords",it.getInt(0)).put("impactWords",it.getInt(1)) }
        db.rawQuery("SELECT AVG(CASE WHEN active_ms>0 THEN active_ms END),AVG(completed*1.0/MAX(planned,1)) FROM learning_days WHERE day<=?",arrayOf(LocalDate.now().toString())).use {
            it.moveToFirst(); root.put("averageMinutes",it.getDouble(0)/60000).put("completion",it.getDouble(1))
        }
        db.rawQuery("SELECT COUNT(*),AVG(correct) FROM diagnostic_attempts WHERE form='spelling'",null).use {
            it.moveToFirst();root.put("spellingSamples",it.getInt(0));root.put("spellingAccuracy",if(it.getInt(0)>0) it.getDouble(1) else JSONObject.NULL)
        }
        root.put("modes",JSONObject().also { m -> db.rawQuery("SELECT mode,COUNT(*) FROM learning_days WHERE day<=? GROUP BY mode",arrayOf(LocalDate.now().toString())).use {
            while(it.moveToNext()) m.put(it.getString(0),it.getInt(1))
        } })
        root.put("readingTrend",JSONArray().also { a -> db.rawQuery("SELECT day,SUM(elapsed_ms),AVG(correct) FROM diagnostic_attempts WHERE form='reading' GROUP BY day,reading_id ORDER BY MAX(created_at) DESC,day DESC LIMIT 7",null).use {
            while(it.moveToNext()) a.put(JSONObject().put("day",it.getString(0)).put("elapsedMs",it.getLong(1)).put("accuracy",it.getDouble(2)))
        } })
        root.put("readingErrorReasons",JSONObject().also { j -> db.rawQuery("SELECT error_reason,COUNT(*) FROM diagnostic_attempts WHERE form='reading' AND correct=0 AND error_reason!='' GROUP BY error_reason",null).use {
            while(it.moveToNext()) j.put(it.getString(0),it.getInt(1))
        } })
        return root
    }
}
