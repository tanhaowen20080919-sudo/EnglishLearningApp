package com.tanhaowen.contextenglish.data

import android.content.Context

class StudySettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("study_settings", Context.MODE_PRIVATE)
    fun load() = StudySettings(prefs.getInt("new", 20), prefs.getInt("goal", 30), prefs.getBoolean("phonetic", true), prefs.getBoolean("speak", false))
    fun save(s: StudySettings) { prefs.edit().putInt("new", s.newWords.coerceIn(1,100)).putInt("goal", s.dailyGoal.coerceIn(1,500)).putBoolean("phonetic", s.showPhonetic).putBoolean("speak", s.autoSpeak).apply() }
}
