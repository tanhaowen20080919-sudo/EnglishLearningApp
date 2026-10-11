package com.tanhaowen.contextenglish.study

import com.tanhaowen.contextenglish.ai.*
import com.tanhaowen.contextenglish.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ForegroundGuardTest {
    @Test fun backgroundGuardStopsBeforeAnyNetworkRequest() = runBlocking {
        val settings=AiSettings(apiKey="fixture",dailyModel="fixture",baseUrl="https://must-not-send.invalid/v1")
        val result=AiClient().generate(settings,"fixture",32) { false }
        assertTrue(result.exceptionOrNull() is AiStartSkippedException)
    }
}
