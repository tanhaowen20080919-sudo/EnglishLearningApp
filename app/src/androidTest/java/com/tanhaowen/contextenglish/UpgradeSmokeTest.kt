package com.tanhaowen.contextenglish

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tanhaowen.contextenglish.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import java.io.File

@RunWith(AndroidJUnit4::class)
class UpgradeSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun tabsHaveSingleLabelsAndPreserveDraftsAndSearch() {
        listOf("今", "学", "词", "我").forEach { compose.onNodeWithText(it, substring = false).assertDoesNotExist() }
        compose.onNodeWithTag("tab-AI").performClick()
        compose.onNodeWithTag("learning-input").performTextInput("Explain a simple sentence")
        compose.onNodeWithTag("tab-WORDS").performClick()
        compose.onNodeWithTag("word-search").performTextInput("avoid")
        compose.onNodeWithTag("tab-AI").performClick()
        compose.onNodeWithTag("learning-input").assertTextContains("Explain a simple sentence")
        compose.onNodeWithTag("tab-WORDS").performClick()
        compose.onNodeWithTag("word-search").assertTextContains("avoid")
        compose.onNodeWithTag("tab-STUDY").performClick()
        compose.onNodeWithText("单词学习").assertIsDisplayed()
        compose.onNodeWithTag("tab-ME").performClick()
        compose.onNodeWithText("学习设置").assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val output=File(context.getExternalFilesDir(null),"screenshots").apply { mkdirs() }
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(output,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
    }

    @Test fun immersiveLearningKeepsFeedbackAndResumesWithoutNavigation() {
        compose.waitUntil(15000) { compose.onAllNodesWithTag("start-study").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("start-study").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithTag("answer-0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tab-AI").assertDoesNotExist()
        screenshot("01-immersive-question")
        compose.onNodeWithTag("dont-know").performScrollTo().performClick()
        compose.onNodeWithTag("continue-study").performScrollTo().assertIsDisplayed()
        screenshot("02-wrong-feedback")
        compose.onNodeWithTag("study-detail").performScrollTo().performClick()
        compose.onNodeWithText("需要更多？AI讲解").assertExists()
        // Dismiss the native sheet; the question is still graded and remains on screen.
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("continue-study").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("退出学习并保存进度").performClick()
        compose.onNodeWithTag("start-study").performClick()
        compose.onNodeWithTag("continue-study").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("continue-study").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("answer-feedback").assertDoesNotExist()
    }

    @Test fun sessionSnapshotAndAnswerAreAtomicAndBackupSafe() {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ContextEnglishApp
        val repo=app.repository
        val original=repo.exportData(StudySettings(),AiSettings())
        try {
            val words=repo.loadWords()
            val s=com.tanhaowen.contextenglish.study.StudyEngine.create(words.take(4),"fixture","day")
            val q=com.tanhaowen.contextenglish.study.StudyEngine.question(s,words)!!
            val graded=com.tanhaowen.contextenglish.study.StudyEngine.answer(s,q,q.answer)
            val before=repo.loadWords().first { it.id==q.word.id }.reviewCount
            repo.saveSessionAnswer(graded,q.task,2);repo.saveSessionAnswer(graded,q.task,2)
            assertEquals(before+1,repo.loadWords().first { it.id==q.word.id }.reviewCount)
            assertEquals(graded,repo.loadSession())
            val backup=repo.exportData(StudySettings(sound=false,autoAi=false),AiSettings())
            val restored=repo.importData(backup,AiSettings())
            assertFalse(restored.study.sound);assertFalse(restored.study.autoAi)
            assertEquals(graded,repo.loadSession())
        } finally { repo.importData(original,AiSettings()) }
    }

    @Test fun billingBackupRoundTripAndInvalidImportKeepLocalData() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ContextEnglishApp
        val repo = app.repository
        val original = repo.exportData(StudySettings(), AiSettings())
        try {
            val settings = AiSettings(apiKey = "dummy-device-key", dailyModel = "fixture-model", currency = "USD",
                inputPricePerMillion = 2.0, outputPricePerMillion = 8.0,
                cacheCreationPricePerMillion = 2.5, cacheReadPricePerMillion = 0.2)
            val record = repo.recordAiUsage(settings.dailyModel, TokenUsage(1000, 200, 0, 800, 1200, true), settings)
            repo.cacheAiReading("fixture", "saved text", settings.dailyModel, record.id)
            val backup = repo.exportData(StudySettings(), settings)
            assertFalse(backup.contains(settings.apiKey))
            val result = repo.importData(backup, settings.copy(apiKey = "keep-device-key"))
            assertEquals("keep-device-key", result.ai!!.apiKey)
            assertEquals(0.00216, repo.aiUsageRecord(record.id)!!.totalCost, 1e-12)
            assertEquals(record.id, repo.cachedReadings().first().usageId)
            val count = repo.aiUsageSummary().calls
            val v2 = JSONObject(backup).put("version", 2).apply { remove("ai_usage"); remove("ai_settings") }
            repo.importData(v2.toString(), settings)
            assertEquals(count, repo.aiUsageSummary().calls)
            val bad = JSONObject(backup)
            bad.getJSONArray("words").getJSONObject(0).put("state", "INVALID")
            assertThrows(IllegalArgumentException::class.java) { repo.importData(bad.toString(), settings) }
            assertEquals(count, repo.aiUsageSummary().calls)
            assertEquals(1640, repo.loadWords().size)
        } finally { repo.importData(original, AiSettings()) }
    }
}
