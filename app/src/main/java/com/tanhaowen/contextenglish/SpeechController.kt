package com.tanhaowen.contextenglish

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import com.tanhaowen.contextenglish.data.StudySettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** One application player. A new request or stop invalidates every old callback. */
class SpeechController(private val context: Context) {
    private data class Clip(val name: String, val external: Boolean = false)
    private data class Request(val id: Long, val word: String, var remaining: Int,
        val rate: Float, var ttsOnly: Boolean = false)

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings = StudySettingsStore(context)
    private val audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val filePattern = Regex("[A-Za-z0-9_.-]+\\.(mp3|wav|ogg)")
    private val catalog = scope.async(Dispatchers.IO) {
        val entries = mutableMapOf<String, Clip>()
        fun read(json: String, external: Boolean) {
            val array = JSONObject(json).getJSONArray("entries")
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val name = item.getString("file")
                if (filePattern.matches(name)) entries[item.getString("word").trim().lowercase(Locale.US)] = Clip(name, external)
            }
        }
        runCatching { read(context.assets.open("pronunciation/manifest.json").bufferedReader().use { it.readText() }, false) }
        val disk = File(context.filesDir, "pronunciation/manifest.json")
        if (disk.isFile) runCatching { read(disk.readText(), true) }
        entries.toMap()
    }
    private var serial = 0L
    private var active: Request? = null
    private var player: MediaPlayer? = null
    private var replay: Runnable? = null
    private var engine: TextToSpeech? = null
    private var initialized = false
    private var ready = false
    private val mutableSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = mutableSpeaking.asStateFlow()

    /** Manual taps read once; automatic card playback explicitly requests twice. */
    fun speak(word: String, repetitions: Int = 1, rate: Float? = null) {
        if (word.isBlank()) return
        stop()
        val request = Request(serial, word.trim(), repetitions.coerceIn(1, 2),
            (rate ?: settings.load().speechRate).let { if (it.isFinite()) it.coerceIn(0.6f, 1.2f) else 0.9f })
        active = request
        mutableSpeaking.value = true
        scope.launch { play(request, catalog.await()[request.word.lowercase(Locale.US)]) }
    }

    private fun current(request: Request) = active === request && request.id == serial

    private fun play(request: Request, clip: Clip?) {
        if (!current(request)) return
        if (request.ttsOnly || clip == null || !playLocal(request, clip)) {
            request.ttsOnly = true
            speakTts(request)
        }
    }

    private fun playLocal(request: Request, clip: Clip): Boolean {
        val audio = MediaPlayer()
        return try {
            audio.setAudioAttributes(audioAttributes)
            if (clip.external) {
                val folder = File(context.filesDir, "pronunciation").canonicalFile
                val file = File(folder, clip.name).canonicalFile
                require(file.parentFile == folder && file.isFile)
                audio.setDataSource(file.path)
            } else context.assets.openFd("pronunciation/${clip.name}").use {
                audio.setDataSource(it.fileDescriptor, it.startOffset, it.length)
            }
            audio.setOnPreparedListener {
                if (!current(request) || player !== it) return@setOnPreparedListener
                try {
                    // The pack's synthesis speed is 0.95; preserve the saved speaking rate.
                    it.playbackParams = PlaybackParams().setPitch(1f).setSpeed(request.rate / 0.95f)
                    it.start()
                } catch (_: Exception) { mediaFailed(request, it) }
            }
            audio.setOnCompletionListener {
                if (!current(request) || player !== it) return@setOnCompletionListener
                player = null
                it.release()
                completedOnce(request)
            }
            audio.setOnErrorListener { p, _, _ -> mediaFailed(request, p); true }
            player = audio
            audio.prepareAsync()
            true
        } catch (_: Exception) {
            if (player === audio) player = null
            audio.release()
            false
        }
    }

    private fun mediaFailed(request: Request, failed: MediaPlayer) {
        if (!current(request) || player !== failed) return
        player = null
        failed.release()
        request.ttsOnly = true
        speakTts(request)
    }

    private fun completedOnce(request: Request) {
        if (!current(request)) return
        request.remaining--
        if (request.remaining <= 0) finish(request) else {
            replay = Runnable {
                replay = null
                if (current(request)) scope.launch {
                    play(request, catalog.await()[request.word.lowercase(Locale.US)])
                }
            }.also { main.postDelayed(it, 400L) }
        }
    }

    private fun speakTts(request: Request) {
        if (!current(request)) return
        if (engine == null) {
            engine = TextToSpeech(context) { status -> main.post { initializeTts(status) } }
            return
        }
        if (!initialized) return
        if (!ready) { unavailable(request); return }
        val tts = engine ?: return
        tts.setSpeechRate(request.rate)
        tts.setPitch(1f)
        if (tts.speak(request.word, TextToSpeech.QUEUE_FLUSH, null,
                "${request.id}:${request.remaining}") == TextToSpeech.ERROR) unavailable(request)
    }

    private fun initializeTts(status: Int) {
        initialized = true
        val tts = engine ?: return
        ready = status == TextToSpeech.SUCCESS && tts.setLanguage(Locale.US) >= 0
        if (ready) {
            tts.voices?.filter { it.locale.language == "en" && it.locale.country == "US" && !it.isNetworkConnectionRequired }
                ?.maxByOrNull { it.quality }?.let { tts.voice = it }
            tts.setAudioAttributes(audioAttributes)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { main.post {
                    active?.takeIf { "${it.id}:${it.remaining}" == utteranceId }?.let(::completedOnce)
                } }
                @Deprecated("Android callback compatibility")
                override fun onError(utteranceId: String?) { main.post {
                    active?.takeIf { "${it.id}:${it.remaining}" == utteranceId }?.let(::unavailable)
                } }
            })
        }
        // Initialization may finish after the original word was cancelled.
        active?.takeIf { it.ttsOnly }?.let { if (ready) speakTts(it) else unavailable(it) }
    }

    private fun finish(request: Request) {
        if (!current(request)) return
        active = null
        mutableSpeaking.value = false
    }

    fun stop() {
        serial++
        active = null
        replay?.let { main.removeCallbacks(it) }
        replay = null
        engine?.stop()
        player?.release()
        player = null
        mutableSpeaking.value = false
    }

    private fun unavailable(request: Request) {
        if (!current(request)) return
        finish(request)
        Toast.makeText(context, "暂时无法播放这个单词，请稍后重试", Toast.LENGTH_SHORT).show()
    }
}
