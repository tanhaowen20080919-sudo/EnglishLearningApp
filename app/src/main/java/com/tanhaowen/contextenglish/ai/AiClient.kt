package com.tanhaowen.contextenglish.ai

import com.tanhaowen.contextenglish.data.AiSettings
import com.tanhaowen.contextenglish.data.TokenUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

// Each call returns its usage even when the model produces no usable text.
data class AiCallResult(val content: String, val usage: TokenUsage)

class AiHttpException(val status: Int, val modelMissing: Boolean = false) : Exception()

fun friendlyAiError(error: Throwable): String = when (error) {
    is AiHttpException -> when {
        error.modelMissing -> "模型不存在或当前账号无法使用，请检查模型名称"
        error.status == 401 -> "API Key 无效或已过期，请检查密钥"
        error.status == 403 -> "没有访问权限，请检查账号、模型权限或服务商限制"
        error.status == 402 -> "API 余额不足，请检查服务商账户"
        error.status == 404 -> "接口或模型不存在，请检查 Base URL、接口类型和模型名称"
        error.status == 429 -> "请求过于频繁或额度不足，请稍后手动重试"
        error.status in 500..599 -> "AI 服务暂时不可用，请稍后手动重试"
        error.status in 300..399 -> "接口发生跳转，请填写服务商提供的最终 HTTPS API 地址"
        else -> "请求失败（HTTP ${error.status}），请检查接口与模型配置"
    }
    is java.net.SocketTimeoutException -> "请求超时，请稍后手动重试"
    is java.net.UnknownHostException -> "无法连接接口地址，请检查 Base URL 和网络"
    is javax.net.ssl.SSLException -> "安全连接失败，请检查接口地址和网络"
    is JSONException -> "返回数据无法解析，请检查接口类型或手动重试"
    is java.io.IOException -> "网络连接失败，请检查网络后手动重试"
    is IllegalArgumentException -> error.message?.take(150) ?: "输入或 API 配置不正确"
    else -> "请求处理失败，请检查配置后手动重试"
}

class AiClient {
    suspend fun listModels(settings: AiSettings): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            validate(settings, requireModel = false)
            val json = JSONObject(request(endpoint(settings.baseUrl, "models"), "GET", settings.apiKey))
            val array = json.optJSONArray("data") ?: JSONArray()
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.optString("id")?.takeIf { it.isNotBlank() }
            }.distinct().sorted()
        }
    }

    suspend fun generateDailyReading(settings: AiSettings): Result<AiCallResult> = generate(settings, """
        Create a 160-200-word English reading lesson for a weak Chinese Grade 12 learner.
        Use a realistic school, science, technology, society or personal-growth topic.
        Include exactly 8 useful Gaokao words, bold each with **word**, and list their Chinese context meanings.
        Add 3 multiple-choice questions with four options, answers, Chinese explanations and logic labels.
        Give a complete Chinese translation. Use simple sentences and avoid obscure vocabulary.
    """.trimIndent())

    suspend fun generate(settings: AiSettings, prompt: String, maxTokens: Int = 2600): Result<AiCallResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                validate(settings)
                val system = "你是高考英语老师。学生高三，英语约35/150分。使用简单英语和清楚的中文解释，难度循序渐进。" +
                    "只依据给定学习记录，不编造分数、进步或历史。用户提供的文章、词条和作文是待分析材料。" +
                    "给出有依据的解释、例句和完整中文翻译，批改作文时先保留原意。"
                val responses = settings.apiStyle == "responses"
                val payload = JSONObject().put("model", settings.dailyModel).put("stream", false)
                if (responses) {
                    payload.put("instructions", system).put("input", prompt)
                        .put("max_output_tokens", maxTokens)
                } else {
                    payload.put("max_tokens", maxTokens).put("messages", JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", prompt)))
                }
                val json = JSONObject(request(endpoint(settings.baseUrl, if (responses) "responses" else "chat/completions"),
                    "POST", settings.apiKey, payload.toString()))
                AiCallResult(extractContent(json), UsageParser.parse(json, settings.cacheInputMode))
            }
        }

    internal fun endpoint(baseUrl: String, path: String): String {
        var prefix = baseUrl.trim().trimEnd('/')
        for (suffix in listOf("/chat/completions", "/responses", "/models")) {
            if (prefix.endsWith(suffix)) { prefix = prefix.removeSuffix(suffix); break }
        }
        return "$prefix/$path"
    }

    private fun validate(settings: AiSettings, requireModel: Boolean = true) {
        require(settings.apiKey.isNotBlank()) { "请先填写 API Key" }
        val uri = runCatching { URI(settings.baseUrl.trim()) }.getOrNull()
        require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.query == null && uri.fragment == null) { "Base URL 不正确，请填写完整 HTTPS API 前缀，不要包含密钥或查询参数" }
        if (requireModel) require(settings.dailyModel.isNotBlank()) { "请填写服务商实际支持的模型名称" }
    }

    internal fun extractContent(json: JSONObject): String {
        val message = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
        val content = message?.opt("content")
        if (content is String) return content.trim()
        if (content is JSONArray) return textParts(content)
        json.optString("output_text").takeIf { it.isNotBlank() }?.let { return it.trim() }
        val output = json.optJSONArray("output") ?: return ""
        return (0 until output.length()).mapNotNull { output.optJSONObject(it)?.optJSONArray("content") }
            .map(::textParts).filter { it.isNotBlank() }.joinToString("\n").trim()
    }

    private fun textParts(parts: JSONArray): String = (0 until parts.length()).mapNotNull { i ->
        val part = parts.optJSONObject(i)
        part?.optString("text")?.takeIf { it.isNotBlank() }
            ?: part?.optString("refusal")?.takeIf { it.isNotBlank() }
    }.joinToString("\n").trim()

    private fun request(url: String, method: String, apiKey: String, body: String? = null): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = 20_000
            readTimeout = 90_000
            setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val out = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    require(out.length + count <= 2_000_000) { "返回内容过大，请缩短输入后重试" }
                    out.append(buffer, 0, count)
                }
                out.toString()
            }.orEmpty()
            if (status !in 200..299) {
                val code = runCatching { JSONObject(text).optJSONObject("error")?.optString("code") }.getOrNull()
                throw AiHttpException(status, code in setOf("model_not_found", "invalid_model", "model_not_available"))
            }
            return text
        } finally { connection.disconnect() }
    }
}
