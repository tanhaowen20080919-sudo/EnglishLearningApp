package com.tanhaowen.contextenglish

import com.tanhaowen.contextenglish.data.*
import org.json.JSONObject
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
    val usage: AiUsageSummary = AiUsageSummary(),
    val studySettings: StudySettings = StudySettings(),
    val plan: DailyPlan = DailyPlan(),
    val streak: Int = 0,
    val sessionIds: List<Long> = emptyList(),
    val sessionIndex: Int = 0,
    val sessionBusy: Boolean = false,
    val quiz: List<AiQuizQuestion> = emptyList(),
    val quizAnswers: Map<Int, Int> = emptyMap(),
    val quizSubmitted: Boolean = false,
    val aiError: String? = null,
    val lastTokens: String = "",
    val outputTargets: List<String> = emptyList()
)

class AppViewModel(
    private val repository: EnglishRepository,
    private val settingsStore: AiSettingsStore,
    private val studyStore: StudySettingsStore,
    private val aiClient: AiClient = AiClient()
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppUiState(settings = settingsStore.load(), studySettings = studyStore.load()))
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
                    usage = repository.aiUsageSummary(),
                    plan = repository.dailyPlan(studyStore.load()),
                    streak = repository.studyStreak()
                )
            }
            _uiState.update {
                it.copy(
                    words = data.words,
                    stats = data.stats,
                    cachedReadings = data.cached,
                    usage = data.usage, plan = data.plan, streak = data.streak,
                    selectedWord = it.selectedWord?.let { w -> data.words.find { word -> word.id == w.id } }
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
        if (_uiState.value.readingScore != null) return
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
        if(_uiState.value.aiBusy) return
        lastAi = { generateDailyReading() }
        _uiState.update { it.copy(aiError=null, quiz=emptyList(), aiOutput=null, lastTokens="") }
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
        if(reading.title.startsWith("小测 ·")) {
            runCatching { parseQuiz(reading.content, _uiState.value.words) }.fold(
                onSuccess = { quiz -> _uiState.update { it.copy(quiz=quiz, quizAnswers=emptyMap(), quizSubmitted=false, aiOutput=null, aiError=null, lastTokens="", aiMessage="从本地历史打开小测") } },
                onFailure = { _uiState.update { it.copy(aiMessage="这份历史小测无法解析") } })
            return
        }
        _uiState.update { it.copy(aiOutput = reading.content, aiMessage = reading.title, quiz=emptyList(), quizAnswers=emptyMap(), quizSubmitted=false, aiError=null, lastTokens="") }
    }

    private var lastAi: (() -> Unit)? = null
    fun retryAi() { lastAi?.invoke() }
    private fun friendlyError(e: Throwable): String = when(e) {
        is java.net.SocketTimeoutException -> "请求超时，请稍后重试"
        is org.json.JSONException -> "AI 返回格式不符合要求，请重试"
        is java.io.IOException -> "网络连接失败，请检查网络后重试"
        else -> e.message?.take(150) ?: "请求失败，请检查 AI 配置后重试"
    }

    fun saveStudySettings(s: StudySettings) {
        studyStore.save(s); _uiState.update { it.copy(studySettings = studyStore.load(), aiMessage = "学习设置已保存，新的每日词量从明天生效") }; refresh()
    }
    fun startSession(mode: String = "today") {
        val state = _uiState.value
        val ids = when(mode) {
            "review" -> state.words.filter { it.due }.sortedBy { it.nextReviewTime }.map { it.id }
            "weak" -> state.words.filter { it.weak }.sortedByDescending { it.mistakeCount }.map { it.id }
            else -> state.plan.ids.filter { it !in state.plan.completed }
        }
        _uiState.update { it.copy(sessionIds = ids, sessionIndex = 0) }
    }
    fun moveSession(delta: Int) { _uiState.update { it.copy(sessionIndex = (it.sessionIndex + delta).coerceIn(0,it.sessionIds.size)) } }
    fun rateWord(w: VocabWord, rating: Int, advance: Boolean = false) {
        if(_uiState.value.sessionBusy) return
        _uiState.update { it.copy(sessionBusy = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repository.reviewWord(w.id, rating) }
                if(advance) _uiState.update { current ->
                    val ids = if(rating == 0 && current.sessionIds.count { it == w.id } < 3) current.sessionIds + w.id else current.sessionIds
                    current.copy(sessionIds = ids, sessionIndex = current.sessionIndex + 1)
                }
                refresh()
            } finally { _uiState.update { it.copy(sessionBusy = false) } }
        }
    }
    fun toggleFavorite(w: VocabWord) { viewModelScope.launch { withContext(Dispatchers.IO) { repository.toggleFavorite(w) }; refresh() } }
    fun clearStudyRecords() { viewModelScope.launch { withContext(Dispatchers.IO) { repository.clearStudyRecords() }; _uiState.update { it.copy(sessionIds = emptyList(), sessionIndex = 0, aiMessage = "学习记录已清空") }; refresh() } }
    suspend fun exportBackup(): String = withContext(Dispatchers.IO) { repository.exportData(studyStore.load()) }
    fun importBackup(text: String) { viewModelScope.launch {
        runCatching { withContext(Dispatchers.IO) { repository.importData(text) } }.fold(
            onSuccess = { studyStore.save(it); _uiState.update { current -> current.copy(studySettings = it, sessionIds = emptyList(), selectedWord = null, aiMessage = "备份恢复成功") }; refresh() },
            onFailure = { _uiState.update { it.copy(aiMessage = "导入失败：备份文件无效，原有数据已保留") } })
    } }

    fun generateAssistant(kind: String, source: String = "today", input: String = "", days: Int = 1) {
        if(_uiState.value.aiBusy) return
        val current = _uiState.value
        val selected = when(source) {
            "weak" -> current.words.filter { it.weak }.sortedByDescending { it.mistakeCount }
            "custom" -> { val terms = input.lowercase().split(Regex("[,，\\s]+" )).filter { it.isNotBlank() }.toSet(); current.words.filter { it.word.lowercase() in terms } }
            else -> current.plan.ids.mapNotNull { id -> current.words.find { it.id == id } }
        }.take(if(kind == "短文") 12 else 20)
        val dataWords = when(kind) {
            "薄弱分析" -> current.words.filter { it.weak }.sortedByDescending { it.mistakeCount }.take(20)
            "错词诊断" -> current.words.filter { it.mistakeCount > 0 }.sortedByDescending { it.lastReviewTime }.take(20)
            "学习报告" -> (current.words.filter { it.weak }.sortedByDescending { it.mistakeCount } + selected).distinctBy { it.id }.take(20)
            "单词讲解" -> selected.take(1)
            else -> selected
        }
        if(kind !in listOf("今日建议", "学习报告") && dataWords.isEmpty()) {
            _uiState.update { it.copy(aiMessage = "没有可用词汇，请选择词库中存在的单词") }; return
        }
        lastAi = { generateAssistant(kind, source, input, days) }
        _uiState.update { it.copy(aiError = null, quiz = emptyList(), quizAnswers = emptyMap(), quizSubmitted = false, aiOutput = null) }
        runAiAction { settings ->
            val summary = withContext(Dispatchers.IO) { repository.report(days) }
            val taskWords = if(kind == "错词诊断") withContext(Dispatchers.IO) { repository.recentMistakes() } else dataWords
            val data = taskWords.joinToString("\n") { "id=${it.id}; ${it.word}: ${it.meaning.take(120)}; 错误=${it.mistakeCount}; 正确=${it.correctCount}/${it.reviewCount}; 掌握度=${it.familiarity}" }
            val instruction = when(kind) {
                "今日建议" -> "生成简短今日建议：复习优先顺序、新词建议量、重点词、学习还是复习。"
                "薄弱分析" -> "分析薄弱词、可能的易混词、主题和词根关系，明确推测，给出优先顺序。"
                "单词讲解" -> "讲解核心义、高考常见义、搭配、一词多义、熟词生义、易混词、简单记忆方法，给英文例句及中文翻译。"
                "例句" -> "对每个目标词生成一个简单自然的英文例句及中文翻译。"
                "短文" -> "生成约150词自然短文，所有目标词必须出现（可变形），用 **目标词** 标记；给完整中文翻译和目标词原形清单。"
                "小测" -> "只返回有效 JSON 对象，格式 {\"questions\":[{\"wordId\":整数id,\"prompt\":题干,\"options\":[四个选项字符串],\"answer\":0到3,\"explanation\":中文解析}]}。生成5道不重复的英译中、中译英或语境选词题，答案准确且唯一，wordId必须来自目标词。不在题干泄露答案。"
                "错词诊断" -> "依据错词记录分析遗忘、词义混淆、熟词生义、拼写或近义词问题。不能从只有正误的记录断言具体原因，请说明可能原因和验证建议。"
                else -> "生成最近${days}天学习报告：学词、掌握、错误、薄弱词、学习变化、下一步建议。没有历史对照时不要编造提升趋势。"
            }
            val prompt = "$instruction\n数据：$summary\n今日计划：新词${current.plan.newCount}，复习${current.plan.reviewCount}，薄弱${current.plan.weakCount}，完成${current.plan.completed.size}/${current.plan.ids.size}。\n目标词：\n$data"
            aiClient.generate(settings, prompt).fold(onSuccess = { result ->
                withContext(Dispatchers.IO) {
                    repository.cacheAiReading(kind + " · " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date()), result.content, settings.dailyModel)
                    repository.recordAiUsage(settings.dailyModel, result.promptTokens, result.completionTokens, settings)
                }
                val quiz = if(kind == "小测") parseQuiz(result.content, taskWords) else emptyList()
                _uiState.update { it.copy(aiBusy = false, aiOutput = if(kind == "小测") null else result.content, quiz = quiz,
                    outputTargets = taskWords.map { w -> w.word }, lastTokens = if(result.promptTokens < 0 || result.completionTokens < 0) "服务商未返回 Token 用量" else "输入 ${result.promptTokens} · 输出 ${result.completionTokens} · 总计 ${result.promptTokens + result.completionTokens}", aiMessage = "已保存到本地历史") }
                refresh()
            }, onFailure = ::showAiError)
        }
    }
    private fun parseQuiz(text: String, words: List<VocabWord>): List<AiQuizQuestion> {
        val json = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val array = JSONObject(json).getJSONArray("questions")
        require(array.length() in 1..10) { "小测格式不符合要求，请重试" }
        return (0 until array.length()).map { i ->
            val q = array.getJSONObject(i); val id = q.getLong("wordId")
            require(words.any { it.id == id }) { "小测包含未知单词，请重试" }
            val options = q.getJSONArray("options")
            require(options.length() == 4 && q.getInt("answer") in 0..3) { "小测选项格式不符合要求，请重试" }
            AiQuizQuestion(id, q.getString("prompt"), (0..3).map { options.getString(it) }, q.getInt("answer"), q.getString("explanation"))
        }
    }
    fun chooseQuiz(i: Int, answer: Int) { if(!_uiState.value.quizSubmitted) _uiState.update { it.copy(quizAnswers = it.quizAnswers + (i to answer)) } }
    fun submitQuiz() {
        val current = _uiState.value
        if(current.quizSubmitted || current.sessionBusy || current.quiz.isEmpty()) return
        if(current.quizAnswers.size != current.quiz.size) { _uiState.update { it.copy(aiMessage = "请完成所有题目") }; return }
        _uiState.update { it.copy(sessionBusy = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    current.quiz.forEachIndexed { i, q -> repository.reviewWord(q.wordId, if(current.quizAnswers[i] == q.answer) 2 else 0) }
                    val result = current.quiz.mapIndexed { i,q -> "${i+1}. ${q.prompt}\n你的答案：${q.options[current.quizAnswers.getValue(i)]}\n正确答案：${q.options[q.answer]}\n${q.explanation}" }.joinToString("\n\n")
                    repository.cacheAiReading("小测结果", result, "本地评分")
                }
                _uiState.update { it.copy(quizSubmitted = true, aiMessage = "结果已保存；错词已进入薄弱词") }; refresh()
            } finally { _uiState.update { it.copy(sessionBusy = false) } }
        }
    }

    private fun runAiAction(block: suspend (AiSettings) -> Unit) {
        if (_uiState.value.aiBusy) return
        val settings = _uiState.value.settings
        _uiState.update { it.copy(aiBusy = true, aiMessage = null) }
        viewModelScope.launch { try { block(settings) } catch (e: Exception) { showAiError(e) } }
    }

    private fun showAiError(error: Throwable) {
        _uiState.update {
            it.copy(
                aiBusy = false,
                aiMessage = friendlyError(error), aiError = friendlyError(error)
            )
        }
    }

    private data class RefreshData(
        val words: List<VocabWord>,
        val stats: LearningStats,
        val cached: List<CachedReading>,
        val usage: AiUsageSummary, val plan: DailyPlan, val streak: Int
    )
}

class AppViewModelFactory(
    private val repository: EnglishRepository,
    private val settingsStore: AiSettingsStore,
    private val studyStore: StudySettingsStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        AppViewModel(repository, settingsStore, studyStore) as T
}
