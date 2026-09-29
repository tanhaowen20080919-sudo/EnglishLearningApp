package com.tanhaowen.contextenglish.ai

import com.tanhaowen.contextenglish.data.AiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class AiCallResult(
    val content: String,
    val promptTokens: Int,
    val completionTokens: Int
)

class AiClient {
    suspend fun listModels(settings: AiSettings): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            validate(settings)
            val response = request(
                url = "${settings.baseUrl.trimEnd('/')}/models",
                method = "GET",
                apiKey = settings.apiKey
            )
            val array = JSONObject(response).optJSONArray("data") ?: JSONArray()
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.optString("id")?.takeIf { it.isNotBlank() }?.let(::add)
                }
            }.sorted()
        }
    }

    suspend fun generateDailyReading(settings: AiSettings): Result<AiCallResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                validate(settings)
                val systemPrompt = """
                    You are a Gaokao English reading teacher. The learner is a Chinese Grade 12 student
                    whose current English score is about 35/150. Use simple, clear English and help the
                    learner progress gradually. Return only useful learning content, without greetings.
                """.trimIndent()
                val userPrompt = """
                    Create one contextual English reading lesson. Requirements:
                    1. 160-200 English words on a realistic school, science, technology, society, or personal-growth topic.
                    2. Include exactly 8 useful Gaokao words and bold each with **word**.
                    3. After the article, list the 8 words as: word — Chinese meaning in this context.
                    4. Add 3 multiple-choice questions with four options, answer, Chinese explanation, and logic label.
                    5. Keep sentences accessible to a weak learner; avoid obscure vocabulary.
                """.trimIndent()
                val payload = JSONObject().apply {
                    put("model", settings.dailyModel)
                    put("temperature", 0.7)
                    put("messages", JSONArray().apply {
                        put(JSONObject().put("role", "system").put("content", systemPrompt))
                        put(JSONObject().put("role", "user").put("content", userPrompt))
                    })
                }
                val response = request(
                    url = "${settings.baseUrl.trimEnd('/')}/chat/completions",
                    method = "POST",
                    apiKey = settings.apiKey,
                    body = payload.toString()
                )
                val json = JSONObject(response)
                val content = json.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()
                val usage = json.optJSONObject("usage")
                AiCallResult(
                    content = content,
                    promptTokens = usage?.optInt("prompt_tokens", 0) ?: 0,
                    completionTokens = usage?.optInt("completion_tokens", 0) ?: 0
                )
            }
        }

    private fun validate(settings: AiSettings) {
        require(settings.baseUrl.startsWith("https://")) { "Base URL 必须以 https:// 开头" }
        require(settings.apiKey.isNotBlank()) { "请先填写 API Key" }
        require(settings.dailyModel.isNotBlank()) { "请填写日常模型名称" }
    }

    private fun request(
        url: String,
        method: String,
        apiKey: String,
        body: String? = null
    ): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 90_000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            }
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { reader -> reader.readText() }
        }.orEmpty()
        connection.disconnect()
        if (status !in 200..299) {
            val message = runCatching {
                JSONObject(text).optJSONObject("error")?.optString("message")
            }.getOrNull().takeUnless { it.isNullOrBlank() } ?: "HTTP $status"
            error(message)
        }
        return text
    }
}
