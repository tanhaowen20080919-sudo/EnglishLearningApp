package com.tanhaowen.contextenglish.data

import org.json.JSONObject
import java.net.URI

// Export a whitelist. Never serialize the AiSettings object or its API Key.
fun AiSettings.toBackupJson(): JSONObject = JSONObject()
    .put("provider", provider).put("baseUrl", baseUrl).put("dailyModel", dailyModel).put("deepModel", deepModel)
    .put("inputPrice", inputPricePerMillion).put("outputPrice", outputPricePerMillion)
    .put("cacheCreationPrice", cacheCreationPricePerMillion).put("cacheReadPrice", cacheReadPricePerMillion)
    .put("currency", currency).put("apiStyle", apiStyle).put("cacheInputMode", cacheInputMode)

fun aiSettingsFromBackup(json: JSONObject, current: AiSettings): AiSettings {
    fun price(key: String): Double = json.optDouble(key, 0.0).also { require(it.isFinite() && it >= 0.0) }
    val base = json.getString("baseUrl")
    val uri = URI(base)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null)
    val style = json.optString("apiStyle", "chat").also { require(it in listOf("chat", "responses")) }
    val mode = json.optString("cacheInputMode", "auto").also { require(it in listOf("auto", "included", "separate")) }
    val currency = json.optString("currency", "CNY").also { require(it.matches(Regex("[A-Z]{3}"))) }
    return current.copy(provider = json.optString("provider", "OpenAI Compatible"), baseUrl = base,
        dailyModel = json.optString("dailyModel"), deepModel = json.optString("deepModel"),
        inputPricePerMillion = price("inputPrice"), outputPricePerMillion = price("outputPrice"),
        cacheCreationPricePerMillion = price("cacheCreationPrice"), cacheReadPricePerMillion = price("cacheReadPrice"),
        currency = currency, apiStyle = style, cacheInputMode = mode)
}
