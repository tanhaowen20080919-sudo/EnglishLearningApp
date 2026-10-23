package com.tanhaowen.contextenglish.ai

import com.tanhaowen.contextenglish.data.AiSettings
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Exercises production payload/response code with a fixture only at the HTTPS transport boundary. */
class AiTransportContractTest {
    private var requests=0
    private var url=""
    private var payload=JSONObject()
    private var response="""{"model":"actual-remote","choices":[{"message":{"content":"真实响应结构"}}],"usage":{"prompt_tokens":100,"completion_tokens":20,"prompt_tokens_details":{"cached_tokens":40}}}"""
    private val client=object: AiClient() {
        override suspend fun request(url: String,method: String,apiKey: String,body: String?): String {
            requests++;this@AiTransportContractTest.url=url;payload=JSONObject(body!!)
            assertEquals("POST",method);return response
        }
    }
    private val settings=AiSettings(apiKey="fixture-not-a-real-key",baseUrl="https://example.test/v1",dailyModel="requested")
    @Test fun chatPayloadAndActualReturnedModelAndUsageAreParsedTogether()=runBlocking {
        val result=client.generate(settings,"分析指定句子",96).getOrThrow()
        assertEquals("https://example.test/v1/chat/completions",url)
        assertEquals("requested",payload.getString("model"));assertEquals(96,payload.getInt("max_tokens"))
        assertEquals("分析指定句子",payload.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals("actual-remote",result.model);assertEquals(40L,result.usage.cacheReadTokens)
    }
    @Test fun responsesApiUsesItsOwnPayloadAndOutputFormat()=runBlocking {
        response="""{"model":"responses-actual","output":[{"type":"message","content":[{"type":"output_text","text":"解析"}]}],"usage":{"input_tokens":70,"output_tokens":12}}"""
        val result=client.generate(settings.copy(apiStyle="responses"),"prompt",120).getOrThrow()
        assertEquals("https://example.test/v1/responses",url);assertFalse(payload.has("messages"))
        assertEquals(120,payload.getInt("max_output_tokens"));assertEquals("解析",result.content);assertEquals("responses-actual",result.model)
    }
    @Test fun omittedActualModelIsReportedAsUnknownRatherThanInvented()=runBlocking {
        response="""{"choices":[{"message":{"content":"ok"}}]}"""
        val result=client.generate(settings,"prompt").getOrThrow()
        assertTrue(result.model.contains("未返回"));assertFalse(result.usage.known)
    }
    @Test fun startGuardPreventsTransportAndMissingConfigurationIsNotANetworkCall()=runBlocking {
        assertTrue(client.generate(settings,"prompt",canStart={ false }).exceptionOrNull() is AiStartSkippedException)
        assertTrue(client.generate(settings.copy(apiKey=""),"prompt").isFailure)
        assertEquals(0,requests)
    }
}
