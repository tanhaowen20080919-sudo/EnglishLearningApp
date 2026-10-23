package com.tanhaowen.contextenglish

import android.os.SystemClock
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tanhaowen.contextenglish.adaptive.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/** Complete UI path, actual production repository, no AI transport or mocked learning state. */
@RunWith(AndroidJUnit4::class)
class OfflineDayUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    private lateinit var app: ContextEnglishApp
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val latencies=mutableListOf<Long>()
    @Before fun setup() {
        app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ContextEnglishApp
        app.repository.clearStudyRecords()
        app.studySettingsStore.save(app.studySettingsStore.load().copy(autoSpeak=false))
        scenario=ActivityScenario.launch(MainActivity::class.java)
    }
    @After fun cleanup() {
        runCatching {
            val output=File(app.getExternalFilesDir(null),"screenshots").apply { mkdirs() }
            File(output,"offline-day-semantics.txt").writeText(compose.onRoot(useUnmergedTree=true).printToString())
            File(output,"offline-day-final.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
            }
        }
        publishUiEvidence()
        scenario.close();app.repository.clearStudyRecords()
    }
    private fun exists(tag: String)=compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun ready() {
        compose.waitUntil(15000) { compose.onAllNodesWithTag("start-study").fetchSemanticsNodes().any { !it.config.contains(SemanticsProperties.Disabled) } }
    }
    private fun snapshot()=AdaptiveRepository(app.repository).load(LocalDate.now().toString())!!
    @Test fun quickDayCanFinishOfflineAfterRecreationWithRealGradingAndTimingEvidence() {
        ready()
        compose.onNodeWithTag("start-study").assertIsDisplayed()
        compose.onNodeWithTag("today-list").performScrollToNode(hasText("快速 15分"))
        compose.onNodeWithText("快速 15分").performClick()
        compose.onNodeWithTag("today-list").performScrollToIndex(0)
        ready()
        compose.onNodeWithTag("start-study").performScrollTo().performClick()
        compose.waitUntil(15000) { exists("reveal-meaning") }
        var recreated=false;var steps=0
        while(!exists("daily-complete") && steps++<100) {
            val current=snapshot();val step=current.step ?: break
            if(step.question==null) {
                if(!current.revealed) {
                    val start=SystemClock.elapsedRealtime()
                    compose.onNodeWithTag("reveal-meaning").performScrollTo().performClick()
                    compose.waitUntil(5000) { exists("learn-meaning") }
                    latencies.add(SystemClock.elapsedRealtime()-start)
                }
                if(!recreated) {
                    scenario.recreate();compose.waitUntil(15000) { exists("learn-meaning") }
                    recreated=true
                }
                compose.waitUntil(15000) { compose.onAllNodesWithTag("rating-2").fetchSemanticsNodes().any { !it.config.contains(SemanticsProperties.Disabled) } }
                compose.onNodeWithTag("rating-2").performScrollTo().performClick()
                compose.waitUntil(15000) { snapshot().step?.key!=step.key }
            } else if(current.feedback.isBlank()) {
                val q=step.question!!
                if(q.spelling.isNotBlank()) {
                    compose.onNodeWithTag("spelling-input").performScrollTo().performTextInput(q.spelling)
                    hideTestKeyboard(scenario)
                    compose.onNodeWithText("检查拼写").performScrollTo().performClick()
                } else compose.onNodeWithTag("answer-${q.answer}").performScrollTo().performClick()
                compose.waitUntil(15000) { exists("answer-feedback") }
            } else {
                compose.waitUntil(15000) { compose.onAllNodesWithTag("continue-study").fetchSemanticsNodes().any { !it.config.contains(SemanticsProperties.Disabled) } }
                compose.onNodeWithTag("continue-study").performScrollTo().performClick()
                compose.waitUntil(15000) { snapshot().step?.key!=step.key || snapshot().finished }
            }
        }
        compose.onNodeWithTag("daily-complete").assertExists()
        assertTrue(recreated);assertTrue(snapshot().finished)
        assertEquals(5,snapshot().steps.count { it.phase=="综合小测" })
        assertEquals(0L,app.repository.aiUsageSummary().calls)
        compose.waitUntil(15000) { exists("tomorrow-plan-ready") }
        compose.onNodeWithTag("tomorrow-plan-ready").assertExists()
        assertNotNull(AdaptiveRepository(app.repository).load(LocalDate.now().plusDays(1).toString()))
        val db=app.repository.database.readableDatabase
        db.rawQuery("SELECT COUNT(*) FROM diagnostic_attempts WHERE form='spelling'",null).use { it.moveToFirst();assertTrue(it.getInt(0)>0) }
        db.rawQuery("SELECT COUNT(*) FROM diagnostic_attempts WHERE form='context'",null).use { it.moveToFirst();assertTrue(it.getInt(0)>0) }
        val output=File(app.getExternalFilesDir(null),"screenshots").apply { mkdirs() }
        val ordered=latencies.sorted();val p95=ordered[((ordered.size-1)*.95).toInt()]
        File(output,"ui-responsiveness.json").writeText(JSONObject().put("action","reveal-meaning until visible")
            .put("latencyMs",JSONArray(latencies)).put("p95Ms",p95).put("scope","Android emulator, not physical-device touch latency").toString(2))
        assertTrue("Revealing a locally stored meaning must remain responsive; p95=$p95 ms",p95<1500)
    }
}
