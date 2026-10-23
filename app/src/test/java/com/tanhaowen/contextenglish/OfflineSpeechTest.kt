package com.tanhaowen.contextenglish

import android.app.Application
import android.content.Context
import android.media.MediaPlayer
import android.os.Looper
import com.tanhaowen.contextenglish.data.StudySettingsStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowMediaPlayer
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class OfflineSpeechTest {
    private lateinit var speech: SpeechController
    private val players = mutableListOf<MediaPlayer>()

    @Before fun prepare() {
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(100, 0) }
        ShadowMediaPlayer.setCreateListener { player, _ -> players.add(player) }
        speech = SpeechController(RuntimeEnvironment.getApplication())
    }

    @After fun stop() { speech.stop() }

    private fun pumpUntil(condition: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10))
            Thread.sleep(2)
        }
        assertTrue("Playback should complete without using device TTS", condition())
    }

    @Test fun automaticRequestPlaysExactlyTwiceAndThenFinishes() {
        speech.speak("available", repetitions = 2)
        assertTrue(speech.isSpeaking.value)
        pumpUntil { !speech.isSpeaking.value }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(2, players.size)
    }

    @Test fun tappingAnotherWordCancelsThePendingSecondReadAndReadsOnce() {
        speech.speak("available", repetitions = 2)
        pumpUntil { players.size == 1 }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertTrue("Two-read request stays busy during its gap", speech.isSpeaking.value)
        speech.speak("ability")
        pumpUntil { !speech.isSpeaking.value }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals("One old clip and one manual clip; no stale second read", 2, players.size)
    }

    @Test fun stopDuringTheGapPreventsAnyLaterPlayback() {
        speech.speak("available", repetitions = 2)
        pumpUntil { players.size == 1 }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        speech.stop()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertFalse(speech.isSpeaking.value)
        assertEquals(1, players.size)
    }

    @Test fun upgradePreservesExplicitSpeechPreferenceAndNewInstallEnablesIt() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("study_settings", Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("speak", false).putBoolean("phonetic", false).commit()
        val store = StudySettingsStore(context)
        assertFalse(store.load().autoSpeak)
        assertFalse(store.load().showPhonetic)
        prefs.edit().clear().commit()
        assertTrue(StudySettingsStore(context).load().autoSpeak)
        store.save(store.load().copy(autoSpeak = false))
        assertFalse(StudySettingsStore(context).load().autoSpeak)
    }
}
