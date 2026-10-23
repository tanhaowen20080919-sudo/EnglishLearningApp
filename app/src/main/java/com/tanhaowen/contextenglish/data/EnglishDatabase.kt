package com.tanhaowen.contextenglish.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class EnglishDatabase(private val context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        if (db.version in 1 until DATABASE_VERSION) createRecoveryBackup(context, db)
    }

    internal fun backupNow(db: SQLiteDatabase) = createRecoveryBackup(context,db)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE words (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                word TEXT NOT NULL UNIQUE,
                phonetic TEXT NOT NULL,
                meaning TEXT NOT NULL,
                context_meaning TEXT NOT NULL,
                example TEXT NOT NULL,
                state TEXT NOT NULL DEFAULT 'NEW',
                weak INTEGER NOT NULL DEFAULT 0,
                seen_count INTEGER NOT NULL DEFAULT 0,
                correct_count INTEGER NOT NULL DEFAULT 0,
                next_review_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE study_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_type TEXT NOT NULL,
                correct INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE ai_cache (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                model TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE ai_usage (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                model TEXT NOT NULL,
                prompt_tokens INTEGER NOT NULL,
                completion_tokens INTEGER NOT NULL,
                estimated_cost REAL NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        seedWords(db)
        upgradeV2(db)
        importAssets(db)
        upgradeV3(db)
        upgradeV4(db)
        upgradeV5(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) { upgradeV2(db); importAssets(db) }
        if (oldVersion < 3) upgradeV3(db)
        if (oldVersion < 4) upgradeV4(db)
        if (oldVersion < 5) upgradeV5(db)
    }

    private fun upgradeV2(db: SQLiteDatabase) {
        listOf("part_of_speech TEXT NOT NULL DEFAULT ''", "importance INTEGER NOT NULL DEFAULT 2",
            "familiarity INTEGER NOT NULL DEFAULT 0", "mistake_count INTEGER NOT NULL DEFAULT 0",
            "review_count INTEGER NOT NULL DEFAULT 0", "last_review_at INTEGER NOT NULL DEFAULT 0",
            "created_at INTEGER NOT NULL DEFAULT 0", "updated_at INTEGER NOT NULL DEFAULT 0",
            "streak INTEGER NOT NULL DEFAULT 0", "favorite INTEGER NOT NULL DEFAULT 0",
            "example_translation TEXT NOT NULL DEFAULT ''").forEach { db.execSQL("ALTER TABLE words ADD COLUMN $it") }
        db.execSQL("ALTER TABLE study_events ADD COLUMN word_id INTEGER")
        db.execSQL("CREATE TABLE daily_plan (day TEXT NOT NULL, word_id INTEGER NOT NULL, kind TEXT NOT NULL, PRIMARY KEY(day, word_id))")
        db.execSQL("CREATE INDEX review_due ON words(next_review_at)")
        db.execSQL("CREATE INDEX event_word ON study_events(word_id, created_at)")
        db.execSQL("UPDATE words SET familiarity = CASE WHEN state = 'MASTERED' THEN 80 WHEN state = 'LEARNING' THEN 30 ELSE 0 END")
    }

    private fun importAssets(db: SQLiteDatabase) {
        val array = org.json.JSONObject(context.assets.open("vocabulary.json").bufferedReader().use { it.readText() }).getJSONArray("words")
        for (i in 0 until array.length()) {
            val w = array.getJSONObject(i)
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put("word", w.getString("word")); put("phonetic", w.optString("phonetic"))
                put("meaning", w.getString("meaning")); put("part_of_speech", w.optString("partOfSpeech"))
                put("context_meaning", w.optString("contextMeaning", w.getString("meaning")))
                put("example", w.optString("example")); put("example_translation", w.optString("exampleTranslation"))
                put("importance", w.optInt("importance", 2)); put("created_at", now); put("updated_at", now)
            }
            val existing = db.rawQuery("SELECT id FROM words WHERE word = ?", arrayOf(w.getString("word"))).use { if (it.moveToFirst()) it.getLong(0) else null }
            if (existing == null) db.insertOrThrow("words", null, values)
            else { values.remove("created_at"); db.update("words", values, "id = ?", arrayOf(existing.toString())) }
        }
    }

    private fun upgradeV3(db: SQLiteDatabase) {
        listOf(
            "cache_creation_tokens INTEGER NOT NULL DEFAULT 0", "cache_read_tokens INTEGER NOT NULL DEFAULT 0",
            "total_tokens INTEGER NOT NULL DEFAULT 0", "input_cost REAL NOT NULL DEFAULT 0",
            "output_cost REAL NOT NULL DEFAULT 0", "cache_creation_cost REAL NOT NULL DEFAULT 0",
            "cache_read_cost REAL NOT NULL DEFAULT 0", "currency TEXT NOT NULL DEFAULT 'CNY'",
            "usage_known INTEGER NOT NULL DEFAULT 0", "input_price REAL NOT NULL DEFAULT 0",
            "output_price REAL NOT NULL DEFAULT 0", "cache_creation_price REAL NOT NULL DEFAULT 0",
            "cache_read_price REAL NOT NULL DEFAULT 0", "price_snapshot INTEGER NOT NULL DEFAULT 0"
        ).forEach { db.execSQL("ALTER TABLE ai_usage ADD COLUMN $it") }
        db.execSQL("UPDATE ai_usage SET total_tokens = MAX(prompt_tokens,0) + MAX(completion_tokens,0), usage_known = CASE WHEN prompt_tokens >= 0 AND completion_tokens >= 0 THEN 1 ELSE 0 END")
        db.execSQL("ALTER TABLE ai_cache ADD COLUMN usage_id INTEGER")
        db.execSQL("CREATE INDEX ai_usage_time ON ai_usage(created_at)")
    }

    private fun upgradeV4(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE study_session (id INTEGER PRIMARY KEY CHECK(id=1), snapshot TEXT NOT NULL)")
        db.execSQL("CREATE TABLE session_attempts (task_key TEXT PRIMARY KEY, session_id TEXT NOT NULL, word_id INTEGER NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("""CREATE TABLE auto_ai_jobs (
            id INTEGER PRIMARY KEY AUTOINCREMENT, scope TEXT NOT NULL, word_ids TEXT NOT NULL,
            reason TEXT NOT NULL, status TEXT NOT NULL, model TEXT NOT NULL, currency TEXT NOT NULL,
            reserved_cost REAL NOT NULL, actual_cost REAL, usage_known INTEGER NOT NULL DEFAULT 0,
            usage_id INTEGER, result TEXT NOT NULL DEFAULT '', error TEXT NOT NULL DEFAULT '',
            created_at INTEGER NOT NULL, finished_at INTEGER NOT NULL DEFAULT 0)""")
        db.execSQL("CREATE INDEX auto_ai_time ON auto_ai_jobs(created_at)")
    }

    private fun upgradeV5(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE word_memory (word_id INTEGER PRIMARY KEY, mastery REAL NOT NULL, stability REAL NOT NULL, difficulty REAL NOT NULL, lapses INTEGER NOT NULL, streak INTEGER NOT NULL, recent REAL NOT NULL, forms TEXT NOT NULL, contexts TEXT NOT NULL, last_at INTEGER NOT NULL)")
        db.execSQL("INSERT INTO word_memory SELECT id, familiarity / 100.0, MAX(0.6,(next_review_at-last_review_at)/86400000.0),0.5,mistake_count,streak,CASE WHEN review_count>0 THEN MIN(1.0,correct_count*1.0/review_count) ELSE 0.5 END,'[]','[]',last_review_at FROM words WHERE state!='NEW'")
        db.execSQL("CREATE TABLE learning_days (day TEXT PRIMARY KEY, snapshot TEXT NOT NULL, mode TEXT NOT NULL, planned INTEGER NOT NULL, completed INTEGER NOT NULL DEFAULT 0, active_ms INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE diagnostic_attempts (task_key TEXT PRIMARY KEY, day TEXT NOT NULL, phase TEXT NOT NULL, form TEXT NOT NULL, tag TEXT NOT NULL, word_id INTEGER NOT NULL, correct INTEGER NOT NULL, elapsed_ms INTEGER NOT NULL, context TEXT NOT NULL, created_at INTEGER NOT NULL, error_reason TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE INDEX diagnostic_day ON diagnostic_attempts(day,form,tag)")
        db.execSQL("CREATE TABLE reading_words (day TEXT NOT NULL, reading_id TEXT NOT NULL, word TEXT NOT NULL, meaning TEXT NOT NULL, impacts INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(day,reading_id,word))")
        db.execSQL("CREATE TABLE ai_tasks (task_key TEXT PRIMARY KEY, content_type TEXT NOT NULL, input_hash TEXT NOT NULL, model TEXT NOT NULL, version INTEGER NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, status TEXT NOT NULL, content TEXT NOT NULL DEFAULT '', error TEXT NOT NULL DEFAULT '', usage_id INTEGER)")
        db.execSQL("ALTER TABLE ai_usage ADD COLUMN elapsed_ms INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE ai_usage ADD COLUMN request_status TEXT NOT NULL DEFAULT 'OK'")
    }

    private fun seedWords(db: SQLiteDatabase) {
        seedData.forEach { item ->
            val values = ContentValues().apply {
                put("word", item[0])
                put("phonetic", item[1])
                put("meaning", item[2])
                put("context_meaning", item[3])
                put("example", item[4])
            }
            db.insert("words", null, values)
        }
    }

    companion object {
        private const val DATABASE_NAME = "context_english.db"
        const val DATABASE_VERSION = 5

        /** Before the upgrade transaction: failure aborts migration without changing old data. */
        fun createRecoveryBackup(context: Context, db: SQLiteDatabase): java.io.File {
            check(!db.inTransaction()) { "备份必须在迁移事务前完成" }
            db.rawQuery("PRAGMA wal_checkpoint(FULL)",null).use { c ->
                if(c.moveToFirst()) check(c.getInt(0)==0) { "数据库忙，请重新启动后升级" }
            }
            val folder=java.io.File(context.filesDir,"recovery/v${db.version}-${System.currentTimeMillis()}")
            check(folder.mkdirs()) { "无法建立升级备份，已停止迁移" }
            try {
                java.io.File(db.path).copyTo(java.io.File(folder,DATABASE_NAME))
                SQLiteDatabase.openDatabase(java.io.File(folder,DATABASE_NAME).path,null,SQLiteDatabase.OPEN_READONLY).use { copy ->
                    copy.rawQuery("PRAGMA integrity_check",null).use { check(it.moveToFirst() && it.getString(0)=="ok") }
                }
                val prefs=java.io.File(context.applicationInfo.dataDir,"shared_prefs")
                if(prefs.exists()) check(prefs.copyRecursively(java.io.File(folder,"shared_prefs")))
                java.io.File(folder,"README.txt").writeText("Pre-upgrade database v${db.version}. Stop the app before restoring context_english.db and shared_prefs. Keep the same application and Android keystore. Failed migrations roll back automatically.")
                java.io.File(folder,"COMPLETE").writeText("ok")
                return folder
            } catch(e: Exception) { folder.deleteRecursively(); throw java.io.IOException("升级备份失败，旧数据未迁移",e) }
        }

        private val seedData = listOf(
            arrayOf("avoid", "/əˈvɔɪd/", "v. 避免；避开", "文中：逃避、不愿接触", "Try to avoid checking every unknown word."),
            arrayOf("suggest", "/səˈdʒest/", "v. 建议；表明", "文中：建议一种方法", "The teacher suggested a simple reading plan."),
            arrayOf("approach", "/əˈprəʊtʃ/", "n. 方法；途径 v. 接近", "文中：学习方法", "A new approach can make reading easier."),
            arrayOf("context", "/ˈkɒntekst/", "n. 语境；背景", "文中：单词所在的上下文", "Use the context to understand the word."),
            arrayOf("significant", "/sɪɡˈnɪfɪkənt/", "adj. 重要的；显著的", "文中：明显而重要的", "Small habits can make a significant difference."),
            arrayOf("infer", "/ɪnˈfɜː(r)/", "v. 推断；推论", "文中：根据语境猜出", "Readers can infer meaning from nearby clues."),
            arrayOf("review", "/rɪˈvjuː/", "v./n. 复习；回顾", "文中：再次学习易错词", "Review weak words after reading."),
            arrayOf("practical", "/ˈpræktɪkl/", "adj. 实用的；可行的", "文中：容易真正执行的", "Choose a practical daily target."),
            arrayOf("maintain", "/meɪnˈteɪn/", "v. 保持；维护", "文中：长期坚持", "It is easier to maintain a short routine."),
            arrayOf("result", "/rɪˈzʌlt/", "n. 结果 v. 导致", "文中：源于、由……造成", "Progress results from steady practice."),
            arrayOf("recognize", "/ˈrekəɡnaɪz/", "v. 认出；意识到", "学习中：快速识别词义", "You will recognize common patterns."),
            arrayOf("increase", "/ɪnˈkriːs/", "v. 增加；提高", "学习中：逐渐提高", "Daily reading can increase your speed."),
            arrayOf("reduce", "/rɪˈdjuːs/", "v. 减少；降低", "学习中：降低困难", "Context can reduce the need for translation."),
            arrayOf("evidence", "/ˈevɪdəns/", "n. 证据；依据", "阅读中：支持判断的信息", "Find evidence before choosing an answer."),
            arrayOf("likely", "/ˈlaɪkli/", "adj./adv. 可能的（地）", "阅读中：很可能", "The word is likely to have a positive meaning."),
            arrayOf("require", "/rɪˈkwaɪə(r)/", "v. 需要；要求", "学习中：需要某条件", "Improvement requires regular practice."),
            arrayOf("provide", "/prəˈvaɪd/", "v. 提供", "阅读中：给予信息或帮助", "Examples provide useful clues."),
            arrayOf("affect", "/əˈfekt/", "v. 影响", "阅读中：对……产生影响", "Stress may affect reading speed."),
            arrayOf("benefit", "/ˈbenɪfɪt/", "n. 益处 v. 使受益", "学习中：获得帮助", "Students benefit from timely review."),
            arrayOf("challenge", "/ˈtʃælɪndʒ/", "n./v. 挑战", "学习中：有难度的任务", "Treat each hard sentence as a challenge."),
            arrayOf("focus", "/ˈfəʊkəs/", "v./n. 集中；重点", "学习中：把注意力放在", "Focus on the writer's main point."),
            arrayOf("improve", "/ɪmˈpruːv/", "v. 改善；提高", "学习中：变得更好", "Your accuracy will improve with practice."),
            arrayOf("method", "/ˈmeθəd/", "n. 方法", "学习中：做事方式", "Test the method for one week."),
            arrayOf("process", "/ˈprəʊses/", "n. 过程；步骤", "文中：逐步发生的过程", "Learning is a gradual process."),
            arrayOf("support", "/səˈpɔːt/", "v./n. 支持；支撑", "阅读中：用信息证明", "Which detail supports the conclusion?"),
            arrayOf("available", "/əˈveɪləbl/", "adj. 可获得的；有空的", "阅读中：可以使用的", "Use the time available after class."),
            arrayOf("immediate", "/ɪˈmiːdiət/", "adj. 立即的；直接的", "学习中：立刻发生的", "Do not expect immediate results."),
            arrayOf("gradually", "/ˈɡrædʒuəli/", "adv. 逐渐地", "学习中：一点点地", "The task becomes easier gradually."),
            arrayOf("essential", "/ɪˈsenʃl/", "adj. 必不可少的；本质的", "学习中：非常必要的", "Review is essential for long-term memory."),
            arrayOf("determine", "/dɪˈtɜːmɪn/", "v. 决定；查明", "阅读中：通过信息判断", "Details help determine the writer's purpose.")
        )
    }
}
