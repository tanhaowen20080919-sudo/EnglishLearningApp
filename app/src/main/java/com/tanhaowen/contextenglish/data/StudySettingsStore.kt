package com.tanhaowen.contextenglish.data

import android.content.Context

class StudySettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("study_settings", Context.MODE_PRIVATE)
    init {
        // Enable the requested behavior once; later user choices persist.
        if (!prefs.getBoolean("heart_audio_v1", false)) prefs.edit()
            .putBoolean("speak", true).putBoolean("heart_audio_v1", true).apply()
    }
    fun load() = StudySettings(prefs.getInt("new", 20), prefs.getInt("goal", 30),
        prefs.getBoolean("phonetic", true), prefs.getBoolean("speak", true),
        prefs.getBoolean("sound", true), prefs.getBoolean("haptics", true), prefs.getBoolean("advance", true),
        false, prefs.getString("auto_budget", "0.10")?.toDoubleOrNull() ?: 0.10,
        prefs.getFloat("speech_rate", 0.9f),
        runCatching { AppPalette.valueOf(prefs.getString("palette", "BLUE").orEmpty()) }.getOrDefault(AppPalette.BLUE)).normalized()
    fun save(s: StudySettings) { prefs.edit().putInt("new", s.newWords.coerceIn(1,100))
        .putInt("goal", s.dailyGoal.coerceIn(1,500)).putBoolean("phonetic", s.showPhonetic)
        .putBoolean("speak", s.autoSpeak).putBoolean("sound", s.sound).putBoolean("haptics", s.haptics)
        .putBoolean("advance", s.autoAdvance).putBoolean("auto_ai", s.autoAi)
        .putString("auto_budget", s.autoAiBudget.coerceIn(0.0,100.0).toString())
        .putFloat("speech_rate", s.speechRate.coerceIn(0.6f,1.2f)).putString("palette",s.palette.name).apply() }
}
