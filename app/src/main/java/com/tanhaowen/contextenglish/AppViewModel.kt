package com.tanhaowen.contextenglish

import com.tanhaowen.contextenglish.data.*
import org.json.JSONObject
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tanhaowen.contextenglish.ai.AiCallResult
import com.tanhaowen.contextenglish.ai.friendlyAiError
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.tanhaowen.contextenglish.study.*
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
    val todayUsage: AiUsageSummary = AiUsageSummary(),
    val usageRecords: List<AiUsageRecord> = emptyList(),
    val lastUsage: AiUsageRecord? = null,
    val aiResultTitle: String = "",
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
    val outputTargets: List<String> = emptyList(),
    val loaded: Boolean = false,
    val studyActive: Boolean = false,
    val studySession: StudySession? = null,
    val autoAiJobs: List<AutoAiJob> = emptyList(),
    val coachNotes: Map<Long,CoachNote> = emptyMap(),
    val autoAiStatus: String = "",
    val autoAiRunning: Boolean = false
)

class AppViewModel(
    private val repository: EnglishRepository,
    private val settingsStore: AiSettingsStore,
    private val studyStore: StudySettingsStore,
    private val aiClient: AiClient = AiClient()
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppUiState(settings = settingsStore.load(), studySettings = studyStore.load()))
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null
    private var lastLocalRefresh = 0L

    private val sessionMutex = Mutex()
    private var appForeground = false
    private val pendingCoach = linkedSetOf<Long>()
    private var coachJob: Job? = null
    private var loadedSession = false

    init { viewModelScope.launch { withContext(Dispatchers.IO) { repository.recoverAutoAiJobs() }; refresh() } }

    fun refreshOnResume() {
        if (refreshJob?.isActive != true && System.currentTimeMillis() - lastLocalRefresh > 30_000) refresh()
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val data = withContext(Dispatchers.IO) {
                RefreshData(
                    words = repository.loadWords(),
                    stats = repository.stats(),
                    cached = repository.cachedReadings(),
                    usage = repository.aiUsageSummary(),
                    todayUsage = repository.aiUsageSummary(today = true),
                    usageRecords = repository.aiUsageRecords(),
                    plan = repository.dailyPlan(studyStore.load()),
                    streak = repository.studyStreak(),
                    session = repository.loadSession(), jobs = repository.autoAiJobs()
                )
            }
            lastLocalRefresh = System.currentTimeMillis()
            _uiState.update {
                it.copy(
                    words = data.words,
                    stats = data.stats,
                    cachedReadings = data.cached,
                    usage = data.usage, todayUsage = data.todayUsage, usageRecords = data.usageRecords, plan = data.plan, streak = data.streak,
                    loaded = true, studySession = if(!loadedSession) data.session else it.studySession,
                    autoAiJobs = data.jobs,
                    selectedWord = it.selectedWord?.let { w -> data.words.find { word -> word.id == w.id } }
                )
            }
            loadedSession = true
            restoreCoachNotes(data.jobs)
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
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { settingsStore.save(settings) } }.fold(
                onSuccess = { _uiState.update { it.copy(settings = settings, aiMessage = "AI 设置已保存到本机") } },
                onFailure = { _uiState.update { it.copy(aiMessage = "无法安全保存 API 设置，请重试") } })
        }
    }

    fun saveAndTest(settings: AiSettings) {
        if (_uiState.value.aiBusy) return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { settingsStore.save(settings) } }.fold(
                onSuccess = { _uiState.update { it.copy(settings = settings) }; testConnection() },
                onFailure = { _uiState.update { it.copy(aiMessage = "无法安全保存 API 设置，请重试") } })
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(aiMessage = null) }
    }

    fun testConnection() {
        if (_uiState.value.aiBusy) return
        lastAi = { testConnection() }
        prepareAiOutput()
        runAiAction { settings ->
            aiClient.generate(settings, "仅回复 OK，确认接口和当前模型可用。", maxTokens = 32).fold(
                onSuccess = { result ->
                    persistAiResult("接口测试", result, settings)
                    require(result.content.isNotBlank()) { "接口已响应但没有返回文字，请检查模型或接口类型" }
                    _uiState.update { it.copy(aiBusy = false, aiMessage = "当前模型连接成功，用量和费用已记录") }
                }, onFailure = ::showAiError)
        }
    }

    fun loadModels() {
        lastAi = { loadModels() }
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
        if (_uiState.value.aiBusy) return
        lastAi = { generateDailyReading() }
        prepareAiOutput()
        runAiAction { settings ->
            aiClient.generateDailyReading(settings).fold(
                onSuccess = { result ->
                    persistAiResult("AI 情境阅读", result, settings)
                    require(result.content.isNotBlank()) { "模型没有返回文字，请检查接口类型或手动重试" }
                    _uiState.update { it.copy(aiBusy = false, aiOutput = result.content, aiMessage = "已生成并缓存到本机") }
                }, onFailure = ::showAiError)
        }
    }

    fun openCachedReading(reading: CachedReading) {
        if (_uiState.value.aiBusy) return
        viewModelScope.launch {
            val usage = withContext(Dispatchers.IO) { reading.usageId?.let(repository::aiUsageRecord) }
            if (reading.title.startsWith("小测 ·")) {
                runCatching { parseQuiz(reading.content, _uiState.value.words) }.fold(
                    onSuccess = { quiz -> _uiState.update { it.copy(quiz = quiz, quizAnswers = emptyMap(),
                        quizSubmitted = false, aiOutput = null, aiError = null, lastTokens = "", lastUsage = usage,
                        aiResultTitle = reading.title, aiMessage = "从本地历史打开小测") } },
                    onFailure = { _uiState.update { it.copy(aiMessage = "这份历史小测无法解析") } })
            } else _uiState.update { it.copy(aiOutput = reading.content, aiResultTitle = reading.title,
                lastUsage = usage, aiMessage = reading.title, quiz = emptyList(), quizAnswers = emptyMap(),
                quizSubmitted = false, aiError = null, lastTokens = "") }
        }
    }

    fun clearAiOutput() {
        if (_uiState.value.aiBusy) return
        lastAi = null
        prepareAiOutput()
    }

    private fun prepareAiOutput() {
        _uiState.update { it.copy(aiError = null, quiz = emptyList(), quizAnswers = emptyMap(),
            quizSubmitted = false, aiOutput = null, lastTokens = "", lastUsage = null, aiResultTitle = "") }
    }

    private suspend fun persistAiResult(title: String, result: AiCallResult, settings: AiSettings) {
        val record = withContext(Dispatchers.IO) {
            repository.recordAiUsage(settings.dailyModel, result.usage, settings).also {
                if (result.content.isNotBlank()) repository.cacheAiReading(
                    title + " · " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date()),
                    result.content, settings.dailyModel, it.id)
            }
        }
        _uiState.update { it.copy(lastUsage = record, aiResultTitle = title) }
        refreshAiData()
    }

    private suspend fun refreshAiData() {
        val usage = withContext(Dispatchers.IO) { repository.aiUsageSummary() }
        val today = withContext(Dispatchers.IO) { repository.aiUsageSummary(today = true) }
        val records = withContext(Dispatchers.IO) { repository.aiUsageRecords() }
        val cached = withContext(Dispatchers.IO) { repository.cachedReadings() }
        _uiState.update { it.copy(usage = usage, todayUsage = today, usageRecords = records, cachedReadings = cached) }
    }

    fun resetAiUsage() {
        if (_uiState.value.aiBusy) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repository.resetAiUsage() }
            _uiState.update { it.copy(lastUsage = null, aiMessage = "AI 使用统计已重置") }
            refreshAiData()
        }
    }

    fun generateLearningTool(kind: String, input: String, extra: String = "", action: String = "") {
        if (_uiState.value.aiBusy) return
        val text = input.trim()
        val topic = extra.trim()
        if (text.isBlank() && !(kind == "写作助手" && action == "参考范文" && topic.isNotBlank())) {
            _uiState.update { it.copy(aiMessage = "请先填写需要分析的内容") }; return
        }
        if (text.length + topic.length > 24_000) {
            _uiState.update { it.copy(aiMessage = "内容太长，请分段输入（每次最多 24000 字符）") }; return
        }
        val instruction = when (kind) {
            "学习助手" -> "回答下面的英语学习问题。先给出清楚的答案，再用适合基础较弱高中生的步骤说明，并给必要例句。"
            "单词讲解" -> "讲解这个英语单词：词性、中文核心义、高考常见义、常见搭配、一词多义、熟词生义、易混词、记忆提示，以及简单英文例句和中文翻译。"
            "句子分析" -> "先给中文翻译，抽出句子主干，再标明主语、谓语、宾语或表语；解释从句、非谓语和修饰成分，给高考考点与读句顺序。不要把简单句硬说成复杂句。"
            "阅读辅助" -> when (action) {
                "逐句解释" -> "逐句列出原句、中文译文和关键结构解释。"
                "提取重点词" -> "提取高考英语中值得优先学习的重点词，标明原形、词性、文中词义与搭配。"
                "长难句分析" -> "挑选文中较难的句子，说明主干、从句、非谓语、修饰关系与翻译。"
                "文章总结" -> "概括文章主题、段落作用和逻辑关系，给简短英文摘要及中文解释。"
                "解题思路" -> "说明如何读懂文章主旨、定位细节、推断词义和作者态度。有题目时逐题给证据；没有题目时仅给阅读思路，不编造题目。"
                else -> "按原文段落给出完整、自然的中文翻译。"
            }
            "写作助手" -> when (action) {
                "语法检查" -> "逐项检查语法错误：原句、改正、中文原因。避免把正确表达说成错误。"
                "用词优化" -> "保留作文原意和学生水平，给具体词语替换及理由。"
                "句式升级" -> "改进句式并解释变化，避免为了复杂而复杂。"
                "评分建议" -> "结合题目给高考作文评分建议，说明内容、语言与结构。未提供满分或题型时不要声称官方确定分数；给清楚的参考依据与提升点。"
                "改写版本" -> "保留原意给出自然、正确、适合高中生的改写作文，附中文翻译和主要修改理由。"
                "参考范文" -> "依据作文题目写一篇适合基础较弱高三学生学习的参考范文，给中文翻译与可积累表达，不冒充学生原文。"
                else -> "批改作文：指出具体错误、给正确写法与理由，分析内容和结构，给有依据的评分建议，最后给保留原意的改写版本与中文翻译。"
            }
            else -> "回答英语学习问题。"
        }
        lastAi = { generateLearningTool(kind, input, extra, action) }
        prepareAiOutput()
        runAiAction { settings ->
            val prompt = "$instruction\n${if (topic.isNotBlank()) "作文题目：$topic\n" else ""}待分析材料：\n$text"
            aiClient.generate(settings, prompt).fold(onSuccess = { result ->
                persistAiResult(if (action.isBlank()) kind else "$kind · $action", result, settings)
                require(result.content.isNotBlank()) { "模型没有返回文字，请检查接口类型或手动重试" }
                _uiState.update { it.copy(aiBusy = false, aiOutput = result.content, aiMessage = "结果已保存到本地历史") }
            }, onFailure = ::showAiError)
        }
    }

    private var lastAi: (() -> Unit)? = null
    fun retryAi() { lastAi?.invoke() }
    fun saveStudySettings(s: StudySettings) {
        studyStore.save(s); _uiState.update { it.copy(studySettings = studyStore.load(), aiMessage = "设置已保存；每日新词数量从明天生效") }; refresh()
        if(!s.autoAi) pendingCoach.clear() else pumpCoach()
    }
    fun startSession(mode: String = "today") {
        val state = _uiState.value
        if(!state.loaded || state.sessionBusy) return
        val previous=state.studySession
        if(mode=="today" && previous!=null && !previous.finished) {
            _uiState.update { it.copy(studyActive=true) }; pumpCoach(); return
        }
        val ids = when(mode) {
            "review" -> state.words.filter { it.due }.sortedBy { it.nextReviewTime }.map { it.id }
            "weak" -> state.words.filter { it.weak }.sortedByDescending { it.mistakeCount }.map { it.id }
            "random" -> state.words.filter { it.state != WordState.MASTERED }.shuffled()
                .sortedWith(compareByDescending<VocabWord> { it.weak }.thenByDescending { it.mistakeCount }.thenBy { it.importance })
                .take(state.studySettings.dailyGoal).map { it.id }
            else -> state.plan.ids.filter { it !in state.plan.completed }.take(state.studySettings.dailyGoal)
        }
        val words=ids.mapNotNull { id -> state.words.find { it.id==id } }
        val session=StudyEngine.create(words,mode,today())
        _uiState.update { it.copy(sessionIds=ids,sessionIndex=0,studySession=session,studyActive=true) }
        persistSession(session)
    }
    private fun today() = SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(Date())

    fun pauseStudy() { _uiState.update { it.copy(studyActive=false) } }

    fun setForeground(active: Boolean) { appForeground=active; if(active) pumpCoach() }

    private fun persistSession(s: StudySession) { viewModelScope.launch {
        sessionMutex.withLock { withContext(Dispatchers.IO) { repository.saveSession(s) } }
    } }

    fun hintStudy() {
        val s=_uiState.value.studySession ?: return
        if(s.feedback!=null || _uiState.value.sessionBusy) return
        val updated=s.copy(hinted=true)
        _uiState.update { it.copy(studySession=updated) }; persistSession(updated)
    }

    fun toggleSpelling() {
        val s=_uiState.value.studySession ?: return
        if(s.feedback!=null || _uiState.value.sessionBusy || s.task?.kind==TaskKind.CONTEXT) return
        val task=s.task ?: return
        val updated=s.copy(tasks=s.tasks.toMutableList().also { it[s.index]=task.copy(
            kind=if(task.kind==TaskKind.SPELLING) TaskKind.MEANING else TaskKind.SPELLING) },hinted=false)
        _uiState.update { it.copy(studySession=updated) }; persistSession(updated)
    }

    fun answerStudy(selected: Int = -1, typed: String = "", unknown: Boolean = false) {
        val state=_uiState.value
        val session=state.studySession ?: return
        if(state.sessionBusy || session.feedback!=null) return
        val question=StudyEngine.question(session,state.words) ?: return
        if(!unknown && question.task.kind!=TaskKind.SPELLING && selected !in question.options.indices) return
        val updated=StudyEngine.answer(session,question,selected,typed,unknown)
        _uiState.update { it.copy(studySession=updated,sessionBusy=true) }
        viewModelScope.launch {
            try {
                val rating=if(updated.feedback!!.credited) 2 else if(updated.feedback.correct) 1 else 0
                sessionMutex.withLock { withContext(Dispatchers.IO) { repository.saveSessionAnswer(updated,question.task,rating) } }
                if(!updated.feedback.correct && (question.word.mistakeCount>=1 || question.word.id in session.wrongIds)) {
                    pendingCoach.add(question.word.id); pumpCoach()
                }
                val cached=_uiState.value.coachNotes[question.word.id]
                if(cached!=null) insertCoach(listOf(cached))
                refresh()
            } catch(e: Exception) {
                _uiState.update { it.copy(studySession=session,aiMessage="本地记录未保存，请重试") }
            } finally { _uiState.update { it.copy(sessionBusy=false) } }
        }
    }

    fun nextStudy() {
        val state=_uiState.value; val s=state.studySession ?: return
        if(state.sessionBusy || s.feedback==null) return
        val updated=StudyEngine.next(s)
        _uiState.update { it.copy(studySession=updated,sessionIndex=updated.index) }; persistSession(updated)
    }

    private fun insertCoach(notes: List<CoachNote>) {
        val session=_uiState.value.studySession ?: return
        val updated=StudyEngine.addCoach(session,notes)
        if(updated!=session) { _uiState.update { it.copy(studySession=updated) }; persistSession(updated) }
    }

    private fun restoreCoachNotes(jobs: List<AutoAiJob>) {
        val scope=AutoAiPolicy.scope(_uiState.value.settings)
        val notes=jobs.filter { it.status=="DONE" && it.scope==scope }.asReversed().flatMap {
            runCatching { StudyCodec.notes(it.result,it.wordIds) }.getOrDefault(emptyList())
        }.associateBy { it.wordId }
        _uiState.update { it.copy(coachNotes=notes) }
    }

    /** Foreground-owned queue: at most one request, batched, cached, never blocks local study. */
    private fun pumpCoach() {
        if(!appForeground || !_uiState.value.studyActive || coachJob?.isActive==true || pendingCoach.isEmpty()) return
        coachJob=viewModelScope.launch {
            delay(800)
            try {
                while(appForeground && _uiState.value.studyActive && pendingCoach.isNotEmpty()) {
                    val state=_uiState.value
                    if(!state.studySettings.autoAi) { pendingCoach.clear(); break }
                    if(state.aiBusy) { delay(1000); continue }
                    val scope=AutoAiPolicy.scope(state.settings)
                    val attempted=withContext(Dispatchers.IO) { repository.autoAiJobs(today=true) }
                    val blockedIds=attempted.filter { it.scope==scope }.flatMap { it.wordIds }.toSet()
                    val cached=pendingCoach.mapNotNull { state.coachNotes[it] }
                    insertCoach(cached); pendingCoach.removeAll(cached.map { it.wordId }.toSet())
                    pendingCoach.removeAll(blockedIds)
                    val ids=pendingCoach.take(3).toSet()
                    if(ids.isEmpty()) break
                    val words=ids.mapNotNull { id -> state.words.find { it.id==id } }
                    val prompt="""这些单词在学习中重复答错。仅说明可能的记忆难点，不断言具体错因。
                        返回JSON {"items":[{"wordId":整数,"tip":"不超过80字中文记忆/辨析提示","example":"含目标单词原形的简单英文例句","translation":"例句中文翻译","cloze":"将例句中目标词替换为____，仅一个空格"}]}。
                        每词一项，不输出Markdown，不生成选项，不在cloze泄露答案。
                        词条：${words.joinToString("\n") { "${it.id}: ${it.word} / ${it.meaning.take(160)}" }}
                    """.trimIndent()
                    val reserve=AutoAiPolicy.reserve(state.settings,prompt)
                    val reason=AutoAiPolicy.block(state.settings,state.studySettings,attempted,reserve)
                    if(reason!=null) { _uiState.update { it.copy(autoAiStatus=reason) }; break }
                    val jobId=withContext(Dispatchers.IO) { repository.beginAutoAi(state.settings,ids,"重复答错，生成记忆提示与语境强化",reserve) }
                    pendingCoach.removeAll(ids)
                    _uiState.update { it.copy(autoAiRunning=true,autoAiStatus="正在准备针对性练习，不影响当前学习") }
                    var record: AiUsageRecord?=null
                    try {
                        val result=aiClient.generate(state.settings,prompt,AutoAiPolicy.MAX_OUTPUT_TOKENS).getOrThrow()
                        val usageRecord=withContext(Dispatchers.IO) { repository.recordAiUsage(state.settings.dailyModel,result.usage,state.settings) }
                        record=usageRecord
                        val notes=StudyCodec.notes(result.content,ids)
                        notes.forEach { n ->
                            val w=words.first { it.id==n.wordId }
                            val pattern=Regex("(?i)(?<![a-z])${Regex.escape(w.word)}(?![a-z])")
                            require(pattern.containsMatchIn(n.example) && !pattern.containsMatchIn(n.cloze)) { "强化题格式未通过检查" }
                        }
                        withContext(Dispatchers.IO) {
                            repository.finishAutoAi(jobId,"DONE",result.content,usage=usageRecord)
                            repository.cacheAiReading("后台强化 · ${words.joinToString { it.word }}",result.content,state.settings.dailyModel,usageRecord.id)
                        }
                        _uiState.update { it.copy(coachNotes=it.coachNotes+notes.associateBy { n -> n.wordId },autoAiStatus="针对性练习已准备好") }
                        insertCoach(notes)
                    } catch(e: kotlinx.coroutines.CancellationException) { throw e }
                    catch(e: Exception) {
                        withContext(Dispatchers.IO) { repository.finishAutoAi(jobId,"FAILED",error=friendlyAiError(e),usage=record) }
                        _uiState.update { it.copy(autoAiStatus="强化生成未完成；继续使用本地题目") }
                    } finally { _uiState.update { it.copy(autoAiRunning=false) } }
                    refreshAiData()
                    val jobs=withContext(Dispatchers.IO) { repository.autoAiJobs() }
                    _uiState.update { it.copy(autoAiJobs=jobs) }
                }
            } finally { _uiState.update { it.copy(autoAiRunning=false) } }
        }
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
    fun clearStudyRecords() { viewModelScope.launch { sessionMutex.withLock { withContext(Dispatchers.IO) { repository.clearStudyRecords() } }; _uiState.update { it.copy(sessionIds = emptyList(), sessionIndex = 0, studySession=null, studyActive=false, aiMessage = "学习记录已清空") }; refresh() } }
    suspend fun exportBackup(): String = withContext(Dispatchers.IO) { repository.exportData(studyStore.load(), settingsStore.load()) }
    fun importBackup(text: String) { viewModelScope.launch {
        runCatching { withContext(Dispatchers.IO) { repository.importData(text, _uiState.value.settings) } }.fold(
            onSuccess = { restored ->
                withContext(Dispatchers.IO) { studyStore.save(restored.study); restored.ai?.let(settingsStore::save) }
                _uiState.update { current -> current.copy(studySettings = restored.study, settings = restored.ai ?: current.settings,
                    sessionIds = emptyList(), selectedWord = null, lastUsage = null, studyActive=false,studySession=null, aiMessage = "备份恢复成功，API Key 已保留") }; loadedSession=false; refresh()
            },
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
        prepareAiOutput()
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
                persistAiResult(kind, result, settings)
                require(result.content.isNotBlank()) { "模型没有返回文字，请手动重试" }
                val quiz = if(kind == "小测") parseQuiz(result.content, taskWords) else emptyList()
                _uiState.update { it.copy(aiBusy = false, aiOutput = if(kind == "小测") null else result.content, quiz = quiz,
                    outputTargets = taskWords.map { w -> w.word }, lastTokens = "", aiMessage = "已保存到本地历史") }
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
        _uiState.update { it.copy(aiBusy = true, aiMessage = null, aiError = null) }
        viewModelScope.launch {
            try { block(settings) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { showAiError(e) }
        }
    }

    private fun showAiError(error: Throwable) {
        _uiState.update {
            it.copy(
                aiBusy = false,
                aiMessage = friendlyAiError(error), aiError = friendlyAiError(error)
            )
        }
    }

    private data class RefreshData(
        val words: List<VocabWord>,
        val stats: LearningStats,
        val cached: List<CachedReading>,
        val usage: AiUsageSummary, val todayUsage: AiUsageSummary, val usageRecords: List<AiUsageRecord>, val plan: DailyPlan, val streak: Int,
        val session: StudySession?, val jobs: List<AutoAiJob>
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
