package com.tanhaowen.contextenglish.ai

import com.tanhaowen.contextenglish.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UsageAndCompatibilityTest {
    private val rates = AiSettings(inputPricePerMillion = 2.0, outputPricePerMillion = 8.0,
        cacheCreationPricePerMillion = 2.5, cacheReadPricePerMillion = 0.2)
    private fun usage(json: String, mode: String = "auto") = UsageParser.parse(JSONObject(json), mode)

    @Test fun openAiCachedInputIsNotBilledTwice() {
        val u = usage("""{"usage":{"prompt_tokens":1000,"completion_tokens":200,"total_tokens":1200,"prompt_tokens_details":{"cached_tokens":800}}}""")
        assertEquals(200L, u.normalInputTokens)
        assertEquals(800L, u.cacheReadTokens)
        assertEquals(1200L, u.totalTokens)
        assertEquals(0.00216, AiCostCalculator.calculate(u, rates).total, 1e-12)
    }
    @Test fun responsesReadsAndWritesAreInputPartitions() {
        val u = usage("""{"object":"response","usage":{"input_tokens":1500,"output_tokens":50,"input_tokens_details":{"cached_tokens":700,"cache_write_tokens":500}}}""")
        assertEquals(300L, u.normalInputTokens)
        assertEquals(500L, u.cacheCreationTokens)
        assertEquals(1550L, u.totalTokens)
        assertEquals(0.00239, AiCostCalculator.calculate(u, rates).total, 1e-12)
    }
    @Test fun deepSeekHitAndMissAreNotAddedToPromptTwice() {
        val u = usage("""{"usage":{"prompt_tokens":2000,"completion_tokens":300,"prompt_cache_hit_tokens":1200,"prompt_cache_miss_tokens":800}}""")
        assertEquals(800L, u.normalInputTokens)
        assertEquals(2300L, u.totalTokens)
        assertEquals(0.00424, AiCostCalculator.calculate(u, rates).total, 1e-12)
    }
    @Test fun separateGatewayCountsIncludeBothCachesOnce() {
        val u = usage("""{"usage":{"input_tokens":100,"output_tokens":20,"cache_creation_input_tokens":300,"cache_read_input_tokens":600}}""")
        assertEquals(1000L, u.promptTokens)
        assertEquals(100L, u.normalInputTokens)
        assertEquals(1020L, u.totalTokens)
        assertEquals(0.00123, AiCostCalculator.calculate(u, rates).total, 1e-12)
    }
    @Test fun proxyCanExplicitlyDeclareInclusiveInput() {
        val u = usage("""{"usage":{"input_tokens":1000,"output_tokens":20,"cache_read_input_tokens":600}}""", "included")
        assertEquals(400L, u.normalInputTokens)
    }
    @Test fun proxyCanExplicitlyDeclareSeparateInput() {
        val u = usage("""{"usage":{"prompt_tokens":100,"completion_tokens":20,"cache_read_input_tokens":600}}""", "separate")
        assertEquals(700L, u.promptTokens)
        assertEquals(100L, u.normalInputTokens)
    }
    @Test fun noUsageDoesNotCrashOrPretendUsageWasKnown() {
        val u = usage("{}")
        assertFalse(u.known)
        assertEquals(0L, u.totalTokens)
        assertEquals(0.0, AiCostCalculator.calculate(u, rates).total, 0.0)
    }
    @Test fun missingCacheFieldsDefaultToZero() {
        val u = usage("""{"usage":{"prompt_tokens":10,"completion_tokens":5}}""")
        assertTrue(u.known)
        assertEquals(0L, u.cacheCreationTokens)
        assertEquals(0L, u.cacheReadTokens)
        assertEquals(15L, u.totalTokens)
    }
    @Test fun negativeAndMalformedTokensAreSafe() {
        val u = usage("""{"usage":{"prompt_tokens":-10,"completion_tokens":"bad","prompt_tokens_details":{"cached_tokens":null}}}""")
        assertEquals(0L, u.promptTokens)
        assertEquals(0L, u.totalTokens)
        assertFalse(u.known)
    }
    @Test fun largeCountsUseLongs() {
        val u = usage("""{"usage":{"prompt_tokens":3000000000,"completion_tokens":2000000000}}""")
        assertEquals(5000000000L, u.totalTokens)
    }
    @Test fun deepSeekCanDeriveInputWhenPromptIsOmitted() {
        val u = usage("""{"usage":{"completion_tokens":20,"prompt_cache_hit_tokens":600,"prompt_cache_miss_tokens":100}}""")
        assertEquals(700L, u.promptTokens)
        assertTrue(u.known)
    }
    @Test fun zeroPricesAreAllowed() {
        assertEquals(0.0, AiCostCalculator.calculate(TokenUsage(1000, 200, 100, 500, 1200, true), AiSettings()).total, 0.0)
    }
    @Test fun backupExcludesKeyAndRestorePreservesDeviceKey() {
        val saved = rates.copy(apiKey = "dummy-never-transmitted", dailyModel = "my-model", apiStyle = "responses", currency = "USD")
        val json = saved.toBackupJson()
        assertFalse(json.toString().contains(saved.apiKey))
        json.put("apiKey", "ignored-backup-field")
        val restored = aiSettingsFromBackup(json, AiSettings(apiKey = "device-only-key"))
        assertEquals("device-only-key", restored.apiKey)
        assertEquals("responses", restored.apiStyle)
        assertEquals(2.5, restored.cacheCreationPricePerMillion, 0.0)
    }
    @Test fun backupRejectsInvalidPrices() {
        val json = rates.toBackupJson().put("inputPrice", -1)
        assertThrows(IllegalArgumentException::class.java) { aiSettingsFromBackup(json, rates) }
    }
    @Test fun responseTextSupportsChatAndResponses() {
        val client = AiClient()
        assertEquals("你好", client.extractContent(JSONObject("""{"choices":[{"message":{"content":"你好"}}]}""")))
        assertEquals("Sentence\n解释", client.extractContent(JSONObject("""{"output":[{"type":"message","content":[{"type":"output_text","text":"Sentence"},{"type":"output_text","text":"解释"}]}]}""")))
    }
    @Test fun endpointDoesNotDuplicateAlreadyCompletePaths() {
        val c = AiClient()
        assertEquals("https://example.com/v1/chat/completions", c.endpoint("https://example.com/v1/chat/completions", "chat/completions"))
        assertEquals("https://example.com/responses", c.endpoint("https://example.com/", "responses"))
    }
    @Test fun httpErrorsAreDistinguishableAndDoNotExposeResponses() {
        assertTrue(friendlyAiError(AiHttpException(401)).contains("Key"))
        assertTrue(friendlyAiError(AiHttpException(403)).contains("权限"))
        assertTrue(friendlyAiError(AiHttpException(404)).contains("接口"))
        assertTrue(friendlyAiError(AiHttpException(429)).contains("额度"))
        assertTrue(friendlyAiError(AiHttpException(500)).contains("服务"))
        assertTrue(friendlyAiError(AiHttpException(400, true)).contains("模型"))
    }
}
