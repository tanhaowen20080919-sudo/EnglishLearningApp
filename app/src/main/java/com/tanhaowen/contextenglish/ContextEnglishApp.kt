package com.tanhaowen.contextenglish

import android.app.Application
import com.tanhaowen.contextenglish.data.StudySettingsStore
import com.tanhaowen.contextenglish.data.AiSettingsStore
import com.tanhaowen.contextenglish.data.EnglishDatabase
import com.tanhaowen.contextenglish.data.EnglishRepository

class ContextEnglishApp : Application() {
    lateinit var repository: EnglishRepository
        private set

    lateinit var settingsStore: AiSettingsStore
        private set

    lateinit var studySettingsStore: StudySettingsStore
        private set

    val speech by lazy { SpeechController(this) }

    override fun onCreate() {
        super.onCreate()
        repository = EnglishRepository(EnglishDatabase(this))
        settingsStore = AiSettingsStore(this)
        studySettingsStore = StudySettingsStore(this)
    }
}
