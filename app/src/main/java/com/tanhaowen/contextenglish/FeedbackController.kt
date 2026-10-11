package com.tanhaowen.contextenglish

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.PI
import kotlin.math.sin

enum class FeedbackTone { CORRECT, WRONG, COMPLETE }

/** Short original tones, synthesized once locally; no downloaded assets or API calls. */
class FeedbackController(context: Context) {
    private val audio=context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutex=Mutex()
    private val tracks=mutableMapOf<FeedbackTone,AudioTrack>()
    fun prepare() { scope.launch { mutex.withLock { FeedbackTone.entries.forEach { ensure(it) } } } }
    private fun ensure(tone: FeedbackTone): AudioTrack? = tracks[tone] ?: runCatching {
        val frequencies=when(tone) { FeedbackTone.CORRECT -> listOf(660.0,880.0)
            FeedbackTone.WRONG -> listOf(330.0,280.0);FeedbackTone.COMPLETE -> listOf(523.0,659.0,784.0) }
        val rate=22050
        val noteSamples=if(tone==FeedbackTone.COMPLETE) 2205 else 1543
        val samples=ShortArray(frequencies.size*noteSamples) { i ->
            val phase=i%noteSamples
            val envelope=sin(PI*phase/noteSamples).coerceAtLeast(0.0)
            (sin(2*PI*frequencies[i/noteSamples]*phase/rate)*envelope*6500).toInt().toShort()
        }
        AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size*2).build().also {
                it.write(samples,0,samples.size);tracks[tone]=it
            }
    }.getOrNull()
    fun play(tone: FeedbackTone) {
        if(audio.ringerMode!=AudioManager.RINGER_MODE_NORMAL || audio.getStreamVolume(AudioManager.STREAM_MUSIC)==0) return
        scope.launch { mutex.withLock { runCatching {
            tracks.values.forEach { if(it.playState==AudioTrack.PLAYSTATE_PLAYING) it.stop() }
            ensure(tone)?.let { it.reloadStaticData();it.play() }
        } } }
    }
    fun stop() { scope.launch { mutex.withLock { tracks.values.forEach { runCatching { it.stop() } } } } }
}
