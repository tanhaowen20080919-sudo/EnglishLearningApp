package com.tanhaowen.contextenglish.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.util.Calendar
import org.json.JSONObject
import org.json.JSONArray

class EnglishRepository(private val database: EnglishDatabase) {

    fun loadWords(): List<VocabWord> {
        val words = mutableListOf<VocabWord>()
        database.readableDatabase.query(
            "words",
            null,
            null,
            null,
            null,
            null,
            "weak DESC, CASE state WHEN 'LEARNING' THEN 0 WHEN 'NEW' THEN 1 ELSE 2 END, word ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) words += cursor.toWord()
        }
        return words
    }

    fun updateWordState(id: Long, state: WordState) {
        reviewWord(id, if (state == WordState.MASTERED) 2 else 1)
    }

    fun toggleWeak(id: Long, weak: Boolean) {
        val values = ContentValues().apply { put("weak", if (weak) 1 else 0) }
        database.writableDatabase.update("words", values, "id = ?", arrayOf(id.toString()))
    }

    fun recordAnswer(correct: Boolean) {
        val values = ContentValues().apply {
            put("event_type", "reading_question")
            put("correct", if (correct) 1 else 0)
            put("created_at", System.currentTimeMillis())
        }
        database.writableDatabase.insert("study_events", null, values)
    }

    fun stats(): LearningStats {
        val db = database.readableDatabase
        val total = db.singleInt("SELECT COUNT(*) FROM words")
        val seen = db.singleInt("SELECT COUNT(*) FROM words WHERE state != 'NEW'")
        val learning = db.singleInt("SELECT COUNT(*) FROM words WHERE state = 'LEARNING'")
        val mastered = db.singleInt("SELECT COUNT(*) FROM words WHERE state = 'MASTERED'")
        val weak = db.singleInt("SELECT COUNT(*) FROM words WHERE weak = 1")
        val start = startOfToday()
        val answered = db.singleInt(
            "SELECT COUNT(*) FROM study_events WHERE created_at >= ?",
            arrayOf(start.toString())
        )
        val correct = db.singleInt(
            "SELECT COUNT(*) FROM study_events WHERE created_at >= ? AND correct = 1",
            arrayOf(start.toString())
        )
        return LearningStats(total, seen, learning, mastered, weak, answered, correct)
    }

    fun cacheAiReading(title: String, content: String, model: String, usageId: Long? = null): Long {
        val values = ContentValues().apply {
            put("title", title)
            put("content", content)
            put("model", model)
            if (usageId != null) put("usage_id", usageId)
            put("created_at", System.currentTimeMillis())
        }
        return database.writableDatabase.insert("ai_cache", null, values)
    }

    fun cachedReadings(limit: Int = 50): List<CachedReading> {
        val result = mutableListOf<CachedReading>()
        database.readableDatabase.query(
            "ai_cache",
            null,
            null,
            null,
            null,
            null,
            "created_at DESC",
            limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += CachedReading(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                    content = cursor.getString(cursor.getColumnIndexOrThrow("content")),
                    model = cursor.getString(cursor.getColumnIndexOrThrow("model")),
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                    usageId = cursor.getColumnIndexOrThrow("usage_id").let { if (cursor.isNull(it)) null else cursor.getLong(it) }
                )
            }
        }
        return result
    }

    fun recordAiUsage(model: String, usage: TokenUsage, settings: AiSettings): AiUsageRecord {
        val cost = AiCostCalculator.calculate(usage, settings)
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("model", model); put("prompt_tokens", usage.promptTokens); put("completion_tokens", usage.completionTokens)
            put("cache_creation_tokens", usage.cacheCreationTokens); put("cache_read_tokens", usage.cacheReadTokens)
            put("total_tokens", usage.totalTokens); put("usage_known", if (usage.known) 1 else 0)
            put("input_cost", cost.input); put("output_cost", cost.output)
            put("cache_creation_cost", cost.cacheCreation); put("cache_read_cost", cost.cacheRead)
            put("estimated_cost", cost.total); put("currency", settings.currency); put("created_at", now)
            put("input_price", settings.inputPricePerMillion); put("output_price", settings.outputPricePerMillion)
            put("cache_creation_price", settings.cacheCreationPricePerMillion); put("cache_read_price", settings.cacheReadPricePerMillion)
            put("price_snapshot", 1)
        }
        val id = database.writableDatabase.insertOrThrow("ai_usage", null, values)
        return AiUsageRecord(id, model, usage, cost, cost.total, settings.currency, now,
            settings.inputPricePerMillion, settings.outputPricePerMillion,
            settings.cacheCreationPricePerMillion, settings.cacheReadPricePerMillion)
    }

    fun aiUsageSummary(today: Boolean = false): AiUsageSummary {
        val where = if (today) " WHERE created_at >= ?" else ""
        val args = if (today) arrayOf(startOfToday().toString()) else null
        val db = database.readableDatabase
        val costs = linkedMapOf<String, Double>()
        db.rawQuery("SELECT currency, SUM(estimated_cost) FROM ai_usage$where GROUP BY currency", args).use { c ->
            while (c.moveToNext()) costs[c.getString(0)] = c.getDouble(1)
        }
        db.rawQuery("""
            SELECT COUNT(*), COALESCE(SUM(MAX(prompt_tokens,0)),0),
                COALESCE(SUM(MAX(completion_tokens,0)),0), COALESCE(SUM(estimated_cost),0),
                COALESCE(SUM(cache_creation_tokens),0), COALESCE(SUM(cache_read_tokens),0),
                COALESCE(SUM(total_tokens),0), COALESCE(SUM(CASE WHEN usage_known=0 THEN 1 ELSE 0 END),0)
            FROM ai_usage$where
        """.trimIndent(), args).use { c ->
            c.moveToFirst()
            return AiUsageSummary(c.getLong(0), c.getLong(1), c.getLong(2), c.getDouble(3),
                c.getLong(4), c.getLong(5), c.getLong(6), costs, c.getLong(7))
        }
    }

    fun aiUsageRecord(id: Long): AiUsageRecord? = database.readableDatabase.query("ai_usage", null,
        "id = ?", arrayOf(id.toString()), null, null, null).use { if (it.moveToFirst()) it.toUsageRecord() else null }

    fun aiUsageRecords(limit: Int = 100): List<AiUsageRecord> = buildList {
        database.readableDatabase.query("ai_usage", null, null, null, null, null,
            "created_at DESC, id DESC", limit.toString()).use { c -> while (c.moveToNext()) add(c.toUsageRecord()) }
    }

    fun resetAiUsage() {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            db.delete("ai_usage", null, null)
            db.execSQL("UPDATE ai_cache SET usage_id = NULL")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun Cursor.toUsageRecord(): AiUsageRecord {
        fun n(key: String) = getLong(getColumnIndexOrThrow(key))
        fun d(key: String) = getDouble(getColumnIndexOrThrow(key))
        return AiUsageRecord(n("id"), getString(getColumnIndexOrThrow("model")),
            TokenUsage(n("prompt_tokens").coerceAtLeast(0), n("completion_tokens").coerceAtLeast(0),
                n("cache_creation_tokens"), n("cache_read_tokens"), n("total_tokens"), n("usage_known") == 1L),
            AiCost(d("input_cost"), d("output_cost"), d("cache_creation_cost"), d("cache_read_cost")),
            d("estimated_cost"), getString(getColumnIndexOrThrow("currency")), n("created_at"),
            d("input_price"), d("output_price"), d("cache_creation_price"), d("cache_read_price"), n("price_snapshot") == 1L)
    }

    fun reviewWord(id: Long, rating: Int) {
        require(rating in 0..2)
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val w = loadWords().first { it.id == id }
            val now = System.currentTimeMillis()
            val spaced = w.streak == 0 || now - w.lastReviewTime >= 12 * 60 * 60_000L
            val streak = if (rating == 2) w.streak + (if(spaced) 1 else 0) else 0
            val days = listOf(1, 3, 7, 14, 30)[(streak - 1).coerceIn(0, 4)]
            val values = ContentValues().apply {
                put("seen_count", w.seenCount + 1); put("review_count", w.reviewCount + 1)
                put("correct_count", w.correctCount + if (rating == 2) 1 else 0)
                put("mistake_count", w.mistakeCount + if (rating == 0) 1 else 0)
                put("streak", streak); put("last_review_at", now); put("updated_at", now)
                put("familiarity", (w.familiarity + when(rating) { 2 -> if(spaced) 20 else 0; 1 -> -5; else -> -25 }).coerceIn(0, 100))
                put("weak", if (rating == 0 || (w.weak && streak < 3)) 1 else 0)
                put("state", if (streak >= 4) "MASTERED" else "LEARNING")
                put("next_review_at", now + when(rating) { 0 -> 5 * 60_000L; 1 -> 4 * 60 * 60_000L; else -> days * 86_400_000L })
            }
            db.update("words", values, "id = ?", arrayOf(id.toString()))
            db.insertOrThrow("study_events", null, ContentValues().apply {
                put("event_type", "word_review"); put("word_id", id)
                put("correct", if (rating == 2) 1 else 0); put("created_at", now)
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun toggleFavorite(w: VocabWord) {
        database.writableDatabase.update("words", ContentValues().apply { put("favorite", if(w.favorite) 0 else 1) }, "id = ?", arrayOf(w.id.toString()))
    }

    fun dailyPlan(settings: StudySettings): DailyPlan {
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())
        val db = database.writableDatabase
        var ids = mutableListOf<Long>()
        db.rawQuery("SELECT word_id FROM daily_plan WHERE day = ? ORDER BY rowid", arrayOf(day)).use { c -> while(c.moveToNext()) ids.add(c.getLong(0)) }
        if(ids.isEmpty()) {
            val words = loadWords()
            val existing = words.filter { it.state != WordState.NEW }.sortedWith(compareByDescending<VocabWord> { it.due }.thenByDescending { it.weak }.thenByDescending { it.mistakeCount }.thenBy { it.lastReviewTime })
            val chosen = existing.filter { it.due || it.weak } + existing.filter { !it.due && !it.weak && it.state == WordState.LEARNING }.take(5) +
                words.filter { it.state == WordState.NEW }.shuffled().sortedBy { it.importance }.take(settings.newWords)
            db.beginTransaction()
            try {
                chosen.distinctBy { it.id }.forEach { w -> db.insertWithOnConflict("daily_plan", null, ContentValues().apply {
                    put("day", day); put("word_id", w.id); put("kind", if(w.state == WordState.NEW) "new" else if(w.weak) "weak" else "review")
                }, SQLiteDatabase.CONFLICT_IGNORE) }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            ids = chosen.map { it.id }.distinct().toMutableList()
        }
        val done = mutableSetOf<Long>()
        db.rawQuery("SELECT DISTINCT word_id FROM study_events WHERE word_id IS NOT NULL AND created_at >= ?", arrayOf(startOfToday().toString())).use { while(it.moveToNext()) done.add(it.getLong(0)) }
        val kinds = mutableMapOf<String, Int>()
        db.rawQuery("SELECT kind, COUNT(*) FROM daily_plan WHERE day = ? GROUP BY kind", arrayOf(day)).use { while(it.moveToNext()) kinds[it.getString(0)] = it.getInt(1) }
        return DailyPlan(ids, done.intersect(ids.toSet()), kinds["new"] ?: 0, kinds["review"] ?: 0, kinds["weak"] ?: 0)
    }

    fun studyStreak(): Int {
        val days = mutableSetOf<String>()
        val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
        database.readableDatabase.rawQuery("SELECT DISTINCT created_at FROM study_events", null).use { while(it.moveToNext()) days.add(format.format(java.util.Date(it.getLong(0)))) }
        val cal = Calendar.getInstance()
        if(format.format(cal.time) !in days) cal.add(Calendar.DATE, -1)
        var count = 0
        while(format.format(cal.time) in days) { count++; cal.add(Calendar.DATE, -1) }
        return count
    }

    fun recentMistakes(limit: Int = 20): List<VocabWord> {
        val ids = mutableListOf<Long>()
        database.readableDatabase.rawQuery("SELECT word_id FROM study_events WHERE correct=0 AND word_id IS NOT NULL GROUP BY word_id ORDER BY MAX(created_at) DESC LIMIT ?", arrayOf(limit.toString())).use { while(it.moveToNext()) ids.add(it.getLong(0)) }
        val words = loadWords().associateBy { it.id }
        return ids.mapNotNull { words[it] }
    }

    fun report(days: Int): String {
        val since = if(days == 1) startOfToday() else System.currentTimeMillis() - 7 * 86_400_000L
        val db = database.readableDatabase
        val count = db.singleInt("SELECT COUNT(*) FROM study_events WHERE created_at >= ?", arrayOf(since.toString()))
        val correct = db.singleInt("SELECT COUNT(*) FROM study_events WHERE created_at >= ? AND correct = 1", arrayOf(since.toString()))
        val unique = db.singleInt("SELECT COUNT(DISTINCT word_id) FROM study_events WHERE created_at >= ?", arrayOf(since.toString()))
        return "最近${days}天：学习单词 $unique，作答 $count，正确 $correct。全库：${stats()}"
    }

    fun clearStudyRecords() {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE words SET state='NEW', weak=0, seen_count=0, correct_count=0, mistake_count=0, review_count=0, familiarity=0, streak=0, last_review_at=0, next_review_at=0")
            db.delete("study_events", null, null); db.delete("daily_plan", null, null)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun exportData(settings: StudySettings, ai: AiSettings): String {
        val root = JSONObject().put("format", "context-english").put("version", 3)
        listOf("words", "study_events", "daily_plan", "ai_cache", "ai_usage").forEach { table ->
            val rows = JSONArray()
            database.readableDatabase.query(table, null, null, null, null, null, null).use { c ->
                while(c.moveToNext()) {
                    val row = JSONObject()
                    c.columnNames.forEachIndexed { i, name -> row.put(name, when(c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        else -> c.getString(i)
                    }) }
                    rows.put(row)
                }
            }
            root.put(table, rows)
        }
        root.put("settings", JSONObject().put("newWords", settings.newWords).put("dailyGoal", settings.dailyGoal).put("showPhonetic", settings.showPhonetic).put("autoSpeak", settings.autoSpeak))
        root.put("ai_settings", ai.toBackupJson())
        return root.toString(2)
    }

    fun importData(text: String, currentAi: AiSettings): RestoredBackup {
        val root = JSONObject(text)
        require(root.getString("format") == "context-english" && root.getInt("version") in 2..3) { "备份格式不兼容" }
        val version = root.getInt("version")
        val restoredAi = if (version >= 3) aiSettingsFromBackup(root.getJSONObject("ai_settings"), currentAi) else null
        val tables = if (version >= 3) listOf("words", "study_events", "daily_plan", "ai_cache", "ai_usage") else listOf("words", "study_events", "daily_plan", "ai_cache")
        val config = root.getJSONObject("settings")
        val settings = StudySettings(config.getInt("newWords"), config.getInt("dailyGoal"), config.getBoolean("showPhonetic"), config.getBoolean("autoSpeak"))
        require(settings.newWords in 1..100 && settings.dailyGoal in 1..500)
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            tables.asReversed().forEach { db.delete(it, null, null) }
            tables.forEach { table ->
                val allowed = db.rawQuery("SELECT * FROM $table LIMIT 0", null).use { it.columnNames.toSet() }
                val rows = root.getJSONArray(table)
                require(rows.length() <= 200_000)
                for(i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    require(row.keys().asSequence().all { it in allowed })
                    if(table == "words") { WordState.valueOf(row.getString("state")); require(row.getString("word").isNotBlank()) }
                    val values = ContentValues()
                    row.keys().forEach { key ->
                        when(val value = row.get(key)) {
                            JSONObject.NULL -> values.putNull(key)
                            is Double -> { require(value.isFinite()); values.put(key, value) }
                            is Float -> { require(value.isFinite()); values.put(key, value.toDouble()) }
                            is Number -> values.put(key, value.toLong())
                            else -> values.put(key, value.toString())
                        }
                    }
                    db.insertOrThrow(table, null, values)
                }
            }
            require(db.singleInt("SELECT COUNT(*) FROM words") > 0)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return RestoredBackup(settings, restoredAi)
    }

    private fun Cursor.toWord() = VocabWord(
        id = getLong(getColumnIndexOrThrow("id")),
        word = getString(getColumnIndexOrThrow("word")),
        phonetic = getString(getColumnIndexOrThrow("phonetic")),
        meaning = getString(getColumnIndexOrThrow("meaning")),
        contextMeaning = getString(getColumnIndexOrThrow("context_meaning")),
        example = getString(getColumnIndexOrThrow("example")),
        state = WordState.valueOf(getString(getColumnIndexOrThrow("state"))),
        weak = getInt(getColumnIndexOrThrow("weak")) == 1,
        seenCount = getInt(getColumnIndexOrThrow("seen_count")),
        correctCount = getInt(getColumnIndexOrThrow("correct_count")),
        partOfSpeech = getString(getColumnIndexOrThrow("part_of_speech")),
        importance = getInt(getColumnIndexOrThrow("importance")),
        familiarity = getInt(getColumnIndexOrThrow("familiarity")),
        mistakeCount = getInt(getColumnIndexOrThrow("mistake_count")),
        reviewCount = getInt(getColumnIndexOrThrow("review_count")),
        lastReviewTime = getLong(getColumnIndexOrThrow("last_review_at")),
        nextReviewTime = getLong(getColumnIndexOrThrow("next_review_at")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
        streak = getInt(getColumnIndexOrThrow("streak")),
        favorite = getInt(getColumnIndexOrThrow("favorite")) == 1,
        exampleTranslation = getString(getColumnIndexOrThrow("example_translation"))
    )

    private fun SQLiteDatabase.singleInt(sql: String, args: Array<String>? = null): Int =
        rawQuery(sql, args).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun nextReviewTime(state: WordState): Long {
        val days = when (state) {
            WordState.NEW -> 0
            WordState.LEARNING -> 1
            WordState.MASTERED -> 7
        }
        return System.currentTimeMillis() + days * 24L * 60L * 60L * 1000L
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
