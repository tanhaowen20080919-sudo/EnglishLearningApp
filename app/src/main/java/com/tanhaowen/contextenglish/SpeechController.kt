package com.tanhaowen.contextenglish

import android.content.Context
import android.speech.tts.TextToSpeech
import android.widget.Toast
import java.util.Locale
import android.media.MediaPlayer
import android.media.AudioAttributes
import com.tanhaowen.contextenglish.data.StudySettingsStore
import org.json.JSONObject
import java.io.File

/** One lazily initialized engine for the application, reused across page changes. */
class SpeechController(private val context: Context) {
    private var engine: TextToSpeech? = null
    private var ready = false
    private var initialized = false
    private var pending: String? = null
    private var player: MediaPlayer? = null
    private val settings=StudySettingsStore(context)
    private val manifest by lazy {
        runCatching {
            val j=JSONObject(context.assets.open("pronunciation/manifest.json").bufferedReader().use { it.readText() })
            val entries=j.getJSONArray("entries")
            (0 until entries.length()).associate { i -> val item=entries.getJSONObject(i)
                item.getString("word").lowercase(Locale.US) to item.getString("file") }
        }.getOrDefault(emptyMap())
    }

    fun speak(word: String) {
        if(word.isBlank()) return
        player?.release();player=null
        if(playLocal(word)) { engine?.stop();return }
        if (engine == null) {
            pending = word
            engine = TextToSpeech(context) { status ->
                initialized = true
                ready = status == TextToSpeech.SUCCESS
                if(ready) {
                    val tts=engine
                    ready=(tts?.setLanguage(Locale.US) ?: -1)>=0
                    tts?.voices?.filter { it.locale.language=="en" && it.locale.country=="US" && !it.isNetworkConnectionRequired }
                        ?.maxByOrNull { it.quality }?.let { tts.voice=it }
                    tts?.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                }
                val text = pending
                pending = null
                if (ready && text != null) speak(text) else unavailable()
            }
            return
        }
        if (!ready) { if (initialized) unavailable() else pending = word; return }
        val tts = engine ?: return
        tts.setSpeechRate(settings.load().speechRate)
        tts.setPitch(1.0f)
        tts.speak(word, TextToSpeech.QUEUE_FLUSH, null, word)
    }
    private fun playLocal(word: String): Boolean = runCatching {
        // Future packages map words to stable files; reject path traversal and unsupported file types.
        val external=File(context.filesDir,"pronunciation")
        val disk=File(external,"manifest.json")
        val file=if(disk.isFile) {
            val a=JSONObject(disk.readText()).getJSONArray("entries")
            (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.getString("word").equals(word,true) }?.getString("file")
        } else null
        val name=file ?: manifest[word.lowercase(Locale.US)] ?: return false
        require(Regex("[A-Za-z0-9_.-]+\\.(mp3|wav|ogg)").matches(name))
        val audio=MediaPlayer()
        try {
            audio.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            if(file!=null) audio.setDataSource(File(external,name).canonicalPath)
            else context.assets.openFd("pronunciation/$name").use { audio.setDataSource(it.fileDescriptor,it.startOffset,it.length) }
            audio.setOnPreparedListener { it.start() }
            audio.setOnCompletionListener { if(player===it) player=null;it.release() }
            audio.setOnErrorListener { p,_,_ -> if(player===p) player=null;p.release();speakTtsFallback(word);true }
            player=audio;audio.prepareAsync();true
        } catch(e: Exception) { audio.release();false }
    }.getOrDefault(false)
    private fun speakTtsFallback(word: String) {
        if(ready) engine?.speak(word,TextToSpeech.QUEUE_FLUSH,null,word) else unavailable()
    }
    fun stop() { pending=null;engine?.stop();player?.release();player=null }
    private fun unavailable() = Toast.makeText(context, "系统英语语音未就绪，请安装英语语音包后重试", Toast.LENGTH_SHORT).show()
}
