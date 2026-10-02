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

@RunWith(AndroidJUnit4::class)
class UpgradeSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun tabsHaveSingleLabelsAndPreserveDraftsAndSearch() {
        listOf("今", "学", "词", "我").forEach { compose.onNodeWithText(it, substring = false).assertDoesNotExist() }
        compose.onNodeWithText("AI", substring = false).performClick()
        compose.onNodeWithTag("learning-input").performTextInput("Explain a simple sentence")
        compose.onNodeWithText("词库", substring = false).performClick()
        compose.onNodeWithTag("word-search").performTextInput("avoid")
        compose.onNodeWithText("AI", substring = false).performClick()
        compose.onNodeWithTag("learning-input").assertTextContains("Explain a simple sentence")
        compose.onNodeWithText("词库", substring = false).performClick()
        compose.onNodeWithTag("word-search").assertTextContains("avoid")
        compose.onNodeWithText("学习", substring = false).performClick()
        compose.onNodeWithText("单词学习").assertIsDisplayed()
        compose.onNodeWithText("我的", substring = false).performClick()
        compose.onNodeWithText("学习设置").assertIsDisplayed()
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
