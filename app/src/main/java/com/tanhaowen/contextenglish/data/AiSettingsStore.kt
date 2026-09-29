package com.tanhaowen.contextenglish.data

import android.content.Context

class AiSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun load(): AiSettings = AiSettings(
        baseUrl = preferences.getString("base_url", null) ?: "https://api.openai.com/v1",
        apiKey = preferences.getString("api_key", null).orEmpty(),
        dailyModel = preferences.getString("daily_model", null) ?: "gpt-5.6-sol",
        deepModel = preferences.getString("deep_model", null) ?: "gpt-6-astra",
        inputPricePerMillion = preferences.getString("input_price", null)?.toDoubleOrNull() ?: 0.0,
        outputPricePerMillion = preferences.getString("output_price", null)?.toDoubleOrNull() ?: 0.0
    )

    fun save(settings: AiSettings) {
        preferences.edit()
            .putString("base_url", settings.baseUrl.trim().trimEnd('/'))
            .putString("api_key", settings.apiKey.trim())
            .putString("daily_model", settings.dailyModel.trim())
            .putString("deep_model", settings.deepModel.trim())
            .putString("input_price", settings.inputPricePerMillion.toString())
            .putString("output_price", settings.outputPricePerMillion.toString())
            .apply()
    }

    companion object {
        private const val FILE_NAME = "ai_settings"
    }
}
