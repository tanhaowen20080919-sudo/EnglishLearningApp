package com.tanhaowen.contextenglish.data

import android.content.Context

class StudySettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("study_settings", Context.MODE_PRIVATE)
    fun load() = StudySettings(prefs.getInt("new", 20), prefs.getInt("goal", 30),
        prefs.getBoolean("phonetic", true), prefs.getBoolean("speak", false),
        prefs.getBoolean("sound", true), prefs.getBoolean("haptics", true), prefs.getBoolean("advance", true),
        prefs.getBoolean("auto_ai", true), prefs.getString("auto_budget", "0.10")?.toDoubleOrNull() ?: 0.10,
        prefs.getFloat("speech_rate", 0.9f))
    fun save(s: StudySettings) { prefs.edit().putInt("new", s.newWords.coerceIn(1,100))
        .putInt("goal", s.dailyGoal.coerceIn(1,500)).putBoolean("phonetic", s.showPhonetic)
        .putBoolean("speak", s.autoSpeak).putBoolean("sound", s.sound).putBoolean("haptics", s.haptics)
        .putBoolean("advance", s.autoAdvance).putBoolean("auto_ai", s.autoAi)
        .putString("auto_budget", s.autoAiBudget.coerceIn(0.0,100.0).toString())
        .putFloat("speech_rate", s.speechRate.coerceIn(0.6f,1.2f)).apply() }
}
