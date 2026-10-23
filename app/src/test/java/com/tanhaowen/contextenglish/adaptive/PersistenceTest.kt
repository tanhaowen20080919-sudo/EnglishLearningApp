package com.tanhaowen.contextenglish.adaptive

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.robolectric.RuntimeEnvironment
import com.tanhaowen.contextenglish.ContextEnglishApp
import com.tanhaowen.contextenglish.data.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=ContextEnglishApp::class)
class PersistenceTest {
    private lateinit var context: Context
    private var helper: EnglishDatabase?=null
    @Before fun setup() { context=RuntimeEnvironment.getApplication(); context.deleteDatabase("context_english.db"); File(context.filesDir,"recovery").deleteRecursively() }
    @After fun cleanup() { helper?.close(); context.deleteDatabase("context_english.db") }
    private fun repository(): EnglishRepository = EnglishRepository(EnglishDatabase(context).also { helper=it })
    private fun v4() {
        val path=context.getDatabasePath("context_english.db"); path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path,null).use { db ->
            javaClass.getResourceAsStream("/schema-v4.sql")!!.bufferedReader().readText().split(';').filter { it.isNotBlank() }.forEach { db.execSQL(it) }
            db.execSQL("INSERT INTO words(id,word,phonetic,meaning,context_meaning,example,state,weak,seen_count,correct_count,familiarity,mistake_count,review_count,last_review_at,next_review_at) VALUES(1,'maintain','/meɪnˈteɪn/','保持','保持','We maintain a balance.','LEARNING',1,12,7,55,5,12,1000,99999)")
            db.execSQL("INSERT INTO ai_usage(model,prompt_tokens,completion_tokens,estimated_cost,created_at,total_tokens,usage_known) VALUES('old-model',100,20,0.12,1000,120,1)")
            db.execSQL("INSERT INTO ai_cache(title,content,model,created_at) VALUES('旧解析','保留内容','old-model',1000)")
            db.version=4
        }
        context.getSharedPreferences("fixture",Context.MODE_PRIVATE).edit().putString("custom","keep-setting").commit()
    }
    @Test fun upgradePreservesCoreDataAndCreatesRestorableBackup() {
        v4(); val repo=repository(); val word=repo.loadWords().single()
        assertEquals(7,word.correctCount);assertEquals("/meɪnˈteɪn/",word.phonetic);assertEquals(55,word.familiarity)
        assertEquals(.55,AdaptiveRepository(repo).memory(word).mastery,1e-9)
        assertEquals(.12,repo.aiUsageRecords().single().totalCost,1e-9)
        assertEquals("保留内容",repo.cachedReadings().single().content)
        val backup=File(context.filesDir,"recovery").listFiles()!!.single()
        assertTrue(File(backup,"COMPLETE").exists())
        assertTrue(File(backup,"shared_prefs/fixture.xml").exists())
        SQLiteDatabase.openDatabase(File(backup,"context_english.db").path,null,SQLiteDatabase.OPEN_READONLY).use { old ->
            assertEquals(4,old.version)
            old.rawQuery("SELECT correct_count FROM words",null).use { assertTrue(it.moveToFirst());assertEquals(7,it.getInt(0)) }
        }
    }
    @Test fun failedBackupPreventsAnyUpgrade() {
        v4(); File(context.filesDir,"recovery").writeText("block directory")
        assertThrows(IllegalStateException::class.java) { repository().loadWords() }
        SQLiteDatabase.openDatabase(context.getDatabasePath("context_english.db").path,null,SQLiteDatabase.OPEN_READONLY).use { assertEquals(4,it.version) }
    }
    @Test fun failedMigrationRollsBackAndKeepsOldDatabase() {
        v4()
        SQLiteDatabase.openDatabase(context.getDatabasePath("context_english.db").path,null,SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("CREATE TABLE word_memory(collision TEXT)") }
        assertThrows(android.database.sqlite.SQLiteException::class.java) { repository().loadWords() }
        SQLiteDatabase.openDatabase(context.getDatabasePath("context_english.db").path,null,SQLiteDatabase.OPEN_READONLY).use { old ->
            assertEquals(4,old.version)
            old.rawQuery("SELECT correct_count FROM words",null).use { it.moveToFirst();assertEquals(7,it.getInt(0)) }
            old.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE name='learning_days'",null).use { it.moveToFirst();assertEquals(0,it.getInt(0)) }
        }
        assertTrue(File(context.filesDir,"recovery").listFiles()!!.any { File(it,"COMPLETE").exists() })
    }
    private fun plan(repo: EnglishRepository): DailyWorkflow = WorkflowFactory.create(LocalDate.now().toString(),Intensity.QUICK,15,
        repo.loadWords(),RecentPerformance(),emptyList(),System.currentTimeMillis())
    @Test fun gradingAndProgressAreIdempotent() {
        val repo=repository();val adaptive=AdaptiveRepository(repo)
        val w=plan(repo);adaptive.save(w)
        val after=w.copy(index=1)
        adaptive.answer(w,after,true,1000,2)
        val count=repo.loadWord(w.step!!.wordId)!!.seenCount
        assertThrows(IllegalStateException::class.java) { adaptive.answer(w,after,true,1000,2) }
        assertEquals(count,repo.loadWord(w.step!!.wordId)!!.seenCount)
        assertEquals(after,adaptive.load(w.day))
    }
    @Test fun failedSnapshotWriteRollsBackWordEvidence() {
        val repo=repository();val adaptive=AdaptiveRepository(repo);val w=plan(repo);adaptive.save(w)
        helper!!.writableDatabase.execSQL("CREATE TRIGGER fail_save BEFORE UPDATE ON learning_days BEGIN SELECT RAISE(ABORT,'fixture failure'); END")
        val old=repo.loadWord(w.step!!.wordId)!!
        assertThrows(android.database.sqlite.SQLiteException::class.java) { adaptive.answer(w,w.copy(index=1),true,1000,2) }
        assertEquals(old,repo.loadWord(old.id));assertEquals(w,adaptive.load(w.day))
    }
    @Test fun workflowCompletesOfflineAndQuizChangesLaterPlan() {
        val repo=repository();val adaptive=AdaptiveRepository(repo)
        val words=repo.loadWords()
        var w=WorkflowFactory.create(LocalDate.now().toString(),Intensity.STANDARD,45,words,
            RecentPerformance(),listOf(SkillScore("时态",6,1)),System.currentTimeMillis())
        adaptive.save(w)
        var safety=0
        while(!w.finished && safety++<500) {
            val step=w.step!!
            if(step.question==null) {
                val after=w.copy(index=w.index+1)
                adaptive.answer(w,after,true,500,2);w=after
            } else {
                val after=w.copy(index=w.index+1)
                adaptive.answer(w,after,false,1000);w=after
            }
        }
        assertTrue(w.finished);assertEquals(0,repo.aiUsageSummary().calls)
        assertEquals(0.0,adaptive.performance().quiz,0.0)
        val before=DailyPlanner.calculate(RecentPerformance(),0,Intensity.STANDARD)
        val after=DailyPlanner.calculate(adaptive.performance(),0,Intensity.STANDARD)
        assertTrue(after.newCount<before.newCount)
        assertTrue(adaptive.skills().any { it.tag=="推断题" || it.tag=="细节题" })
    }
    @Test fun v5ExportImportRestoresWorkflowAndInvalidImportIsAtomic() {
        val repo=repository();val adaptive=AdaptiveRepository(repo);val w=plan(repo);adaptive.save(w)
        val ai=AiSettings(apiKey="never-export",dailyModel="fixture")
        val backup=repo.exportData(StudySettings(),ai)
        assertFalse(backup.contains("never-export"))
        val restored=repo.importData(backup,ai.copy(apiKey="keep-local"))
        assertEquals("keep-local",restored.ai!!.apiKey);assertEquals(w,adaptive.load(w.day))
        val bad=org.json.JSONObject(backup);bad.getJSONArray("words").getJSONObject(0).put("state","BROKEN")
        assertThrows(IllegalArgumentException::class.java) { repo.importData(bad.toString(),ai) }
        assertEquals(1640,repo.loadWords().size);assertEquals(w,adaptive.load(w.day))
    }
    @Test fun readingTracksLookupsImpactAndErrorReasons() {
        val repo=repository();val adaptive=AdaptiveRepository(repo)
        val w=plan(repo);adaptive.save(w)
        adaptive.readingWord(w.day,w.readingId,"maintain","保持",true)
        assertTrue(adaptive.lookedUp(w.day).getValue("maintain"))
        assertTrue(repo.loadWords().first { it.word=="maintain" }.favorite)
        assertEquals(1,adaptive.summary().getInt("impactWords"))
    }
}
