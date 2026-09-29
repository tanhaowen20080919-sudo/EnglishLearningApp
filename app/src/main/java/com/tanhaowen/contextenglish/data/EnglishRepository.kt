package com.tanhaowen.contextenglish.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.util.Calendar

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
        val values = ContentValues().apply {
            put("state", state.name)
            put("next_review_at", nextReviewTime(state))
        }
        database.writableDatabase.update("words", values, "id = ?", arrayOf(id.toString()))
        database.writableDatabase.execSQL(
            "UPDATE words SET seen_count = seen_count + 1 WHERE id = ?",
            arrayOf(id)
        )
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

    fun cacheAiReading(title: String, content: String, model: String): Long {
        val values = ContentValues().apply {
            put("title", title)
            put("content", content)
            put("model", model)
            put("created_at", System.currentTimeMillis())
        }
        return database.writableDatabase.insert("ai_cache", null, values)
    }

    fun cachedReadings(limit: Int = 8): List<CachedReading> {
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
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at"))
                )
            }
        }
        return result
    }

    fun recordAiUsage(
        model: String,
        promptTokens: Int,
        completionTokens: Int,
        settings: AiSettings
    ) {
        val cost = promptTokens / 1_000_000.0 * settings.inputPricePerMillion +
            completionTokens / 1_000_000.0 * settings.outputPricePerMillion
        val values = ContentValues().apply {
            put("model", model)
            put("prompt_tokens", promptTokens)
            put("completion_tokens", completionTokens)
            put("estimated_cost", cost)
            put("created_at", System.currentTimeMillis())
        }
        database.writableDatabase.insert("ai_usage", null, values)
    }

    fun aiUsageSummary(): AiUsageSummary {
        database.readableDatabase.rawQuery(
            """
            SELECT COUNT(*) AS calls,
                   COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens,
                   COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
                   COALESCE(SUM(estimated_cost), 0) AS estimated_cost
            FROM ai_usage
            """.trimIndent(),
            null
        ).use { cursor ->
            cursor.moveToFirst()
            return AiUsageSummary(
                calls = cursor.getInt(cursor.getColumnIndexOrThrow("calls")),
                promptTokens = cursor.getInt(cursor.getColumnIndexOrThrow("prompt_tokens")),
                completionTokens = cursor.getInt(cursor.getColumnIndexOrThrow("completion_tokens")),
                estimatedCost = cursor.getDouble(cursor.getColumnIndexOrThrow("estimated_cost"))
            )
        }
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
        correctCount = getInt(getColumnIndexOrThrow("correct_count"))
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
