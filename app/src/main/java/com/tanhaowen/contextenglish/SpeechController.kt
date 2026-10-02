package com.tanhaowen.contextenglish

import android.content.Context
import android.speech.tts.TextToSpeech
import android.widget.Toast
import java.util.Locale

/** One lazily initialized engine for the application, reused across page changes. */
class SpeechController(private val context: Context) {
    private var engine: TextToSpeech? = null
    private var ready = false
    private var initialized = false
    private var pending: String? = null

    fun speak(word: String) {
        if (engine == null) {
            pending = word
            engine = TextToSpeech(context) { status ->
                initialized = true
                ready = status == TextToSpeech.SUCCESS
                val text = pending
                pending = null
                if (ready && text != null) speak(text) else unavailable()
            }
            return
        }
        if (!ready) { if (initialized) unavailable() else pending = word; return }
        val tts = engine ?: return
        if (tts.setLanguage(Locale.US) >= 0) tts.speak(word, TextToSpeech.QUEUE_FLUSH, null, word)
        else unavailable()
    }
    private fun unavailable() = Toast.makeText(context, "系统英语语音未就绪，请安装英语语音包后重试", Toast.LENGTH_SHORT).show()
}
