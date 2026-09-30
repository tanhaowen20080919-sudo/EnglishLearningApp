package com.tanhaowen.contextenglish.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AiSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun load(): AiSettings = AiSettings(
        provider = preferences.getString("provider", null) ?: "OpenAI Compatible",
        baseUrl = preferences.getString("base_url", null) ?: "https://api.openai.com/v1",
        apiKey = readKey(),
        dailyModel = preferences.getString("daily_model", null) ?: "gpt-5.6-sol",
        deepModel = preferences.getString("deep_model", null) ?: "gpt-6-astra",
        inputPricePerMillion = preferences.getString("input_price", null)?.toDoubleOrNull() ?: 0.0,
        outputPricePerMillion = preferences.getString("output_price", null)?.toDoubleOrNull() ?: 0.0
    )

    fun save(settings: AiSettings) {
        preferences.edit()
            .putString("provider", settings.provider)
            .putString("base_url", settings.baseUrl.trim().trimEnd('/'))
            .putString("encrypted_key", encrypt(settings.apiKey.trim()))
            .remove("api_key")
            .putString("daily_model", settings.dailyModel.trim())
            .putString("deep_model", settings.deepModel.trim())
            .putString("input_price", settings.inputPricePerMillion.toString())
            .putString("output_price", settings.outputPricePerMillion.toString())
            .apply()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("context_english_api", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("context_english_api", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    private fun readKey(): String {
        preferences.getString("api_key", null)?.let { legacy ->
            preferences.edit().putString("encrypted_key", encrypt(legacy)).remove("api_key").apply()
            return legacy
        }
        val encoded = preferences.getString("encrypted_key", null) ?: return ""
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0,12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }.getOrDefault("")
    }

    companion object {
        private const val FILE_NAME = "ai_settings"
    }
}
