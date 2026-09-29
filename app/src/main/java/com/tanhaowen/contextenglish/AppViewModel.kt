package com.tanhaowen.contextenglish

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tanhaowen.contextenglish.ai.AiClient
import com.tanhaowen.contextenglish.data.AiSettings
import com.tanhaowen.contextenglish.data.AiSettingsStore
import com.tanhaowen.contextenglish.data.AiUsageSummary
import com.tanhaowen.contextenglish.data.CachedReading
import com.tanhaowen.contextenglish.data.EnglishRepository
import com.tanhaowen.contextenglish.data.LearningStats
import com.tanhaowen.contextenglish.data.VocabWord
import com.tanhaowen.contextenglish.data.WordState
import com.tanhaowen.contextenglish.data.builtInReading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppUiState(
    val words: List<VocabWord> = emptyList(),
    val stats: LearningStats = LearningStats(),
    val selectedWord: VocabWord? = null,
    val readingAnswers: Map<Int, Int> = emptyMap(),
    val readingScore: Int? = null,
    val settings: AiSettings = AiSettings(),
    val availableModels: List<String> = emptyList(),
    val aiBusy: Boolean = false,
    val aiMessage: String? = null,
    val aiOutput: String? = null,
    val cachedReadings: List<CachedReading> = emptyList(),
    val usage: AiUsageSummary = AiUsageSummary()
)

class AppViewModel(
    private val repository: EnglishRepository,
    private val settingsStore: AiSettingsStore,
    private val aiClient: AiClient = AiClient()
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppUiState(settings = settingsStore.load()))
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val data = withContext(Dispatchers.IO) {
                RefreshData(
                    words = repository.loadWords(),
                    stats = repository.stats(),
                    cached = repository.cachedReadings(),
                    usage = repository.aiUsageSummary()
                )
            }
            _uiState.update {
                it.copy(
                    words = data.words,
                    stats = data.stats,
                    cachedReadings = data.cached,
                    usage = data.usage
                )
            }
        }
    }

    fun selectWord(word: VocabWord?) {
        _uiState.update { it.copy(selectedWord = word) }
    }

    fun chooseAnswer(questionId: Int, optionIndex: Int) {
        _uiState.update {
            it.copy(
                readingAnswers = it.readingAnswers + (questionId to optionIndex),
                readingScore = null
            )
        }
    }

    fun submitReading() {
        val answers = _uiState.value.readingAnswers
        if (answers.size != builtInReading.questions.size) {
            _uiState.update { it.copy(aiMessage = "请先完成 3 道题") }
            return
        }
        viewModelScope.launch {
            val score = builtInReading.questions.count { answers[it.id] == it.answerIndex }
            withContext(Dispatchers.IO) {
                builtInReading.questions.forEach { question ->
                    repository.recordAnswer(answers[question.id] == question.answerIndex)
                }
            }
            _uiState.update { it.copy(readingScore = score, aiMessage = null) }
            refresh()
        }
    }

    fun updateWordState(word: VocabWord, state: WordState) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.updateWordState(word.id, state) }
            refresh()
        }
    }

    fun toggleWeak(word: VocabWord) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.toggleWeak(word.id, !word.weak) }
            refresh()
        }
    }

    fun saveSettings(settings: AiSettings) {
        settingsStore.save(settings)
        _uiState.update { it.copy(settings = settings, aiMessage = "AI 设置已保存到本机") }
    }

    fun clearMessage() {
        _uiState.update { it.copy(aiMessage = null) }
    }

    fun testConnection() {
        runAiAction { settings ->
            aiClient.listModels(settings).fold(
                onSuccess = { models ->
                    _uiState.update {
                        it.copy(
                            aiBusy = false,
                            availableModels = models,
                            aiMessage = "连接成功，读取到 ${models.size} 个模型"
                        )
                    }
                },
                onFailure = ::showAiError
            )
        }
    }

    fun loadModels() {
        runAiAction { settings ->
            aiClient.listModels(settings).fold(
                onSuccess = { models ->
                    _uiState.update {
                        it.copy(
                            aiBusy = false,
                            availableModels = models,
                            aiMessage = if (models.isEmpty()) "接口可用，但没有返回模型列表" else "模型列表已更新"
                        )
                    }
                },
                onFailure = ::showAiError
            )
        }
    }

    fun generateDailyReading() {
        runAiAction { settings ->
            aiClient.generateDailyReading(settings).fold(
                onSuccess = { result ->
                    val title = "AI 情境阅读 · " +
                        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
                    withContext(Dispatchers.IO) {
                        repository.cacheAiReading(title, result.content, settings.dailyModel)
                        repository.recordAiUsage(
                            settings.dailyModel,
                            result.promptTokens,
                            result.completionTokens,
                            settings
                        )
                    }
                    _uiState.update {
                        it.copy(
                            aiBusy = false,
                            aiOutput = result.content,
                            aiMessage = "已生成并缓存到本机"
                        )
                    }
                    refresh()
                },
                onFailure = ::showAiError
            )
        }
    }

    fun openCachedReading(reading: CachedReading) {
        _uiState.update { it.copy(aiOutput = reading.content, aiMessage = reading.title) }
    }

    private fun runAiAction(block: suspend (AiSettings) -> Unit) {
        if (_uiState.value.aiBusy) return
        val settings = _uiState.value.settings
        _uiState.update { it.copy(aiBusy = true, aiMessage = null) }
        viewModelScope.launch { block(settings) }
    }

    private fun showAiError(error: Throwable) {
        _uiState.update {
            it.copy(
                aiBusy = false,
                aiMessage = "操作失败：${error.message ?: "未知错误"}"
            )
        }
    }

    private data class RefreshData(
        val words: List<VocabWord>,
        val stats: LearningStats,
        val cached: List<CachedReading>,
        val usage: AiUsageSummary
    )
}

class AppViewModelFactory(
    private val repository: EnglishRepository,
    private val settingsStore: AiSettingsStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        AppViewModel(repository, settingsStore) as T
}
