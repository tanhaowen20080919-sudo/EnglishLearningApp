package com.tanhaowen.contextenglish.adaptive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tanhaowen.contextenglish.ContextEnglishApp
import com.tanhaowen.contextenglish.ai.*
import com.tanhaowen.contextenglish.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId

data class AdaptiveState(val workflow: DailyWorkflow? = null,val words: List<VocabWord> = emptyList(),
    val skills: List<SkillScore> = emptyList(),val results: List<SkillScore> = emptyList(),val report: String = "",val lookedUp: Map<String,Boolean> = emptyMap(),
    val active: Boolean = false,val busy: Boolean = false,val message: String? = null,
    val aiBusy: Boolean = false,val aiContent: String = "",val aiError: String = "",val cachedExplanation: String = "",val cachedGrammar: String = "",
    val materials: Map<String,String> = emptyMap(),val materialTasks: List<EnhancementTask> = emptyList(),
    val pendingQuestions: List<LocalQuestion> = emptyList(),val practiceKey: String = "",val lastAiUsage: AiUsageRecord? = null,
    val aiCacheHit: Boolean = false,val tomorrow: DailyWorkflow? = null,val quizRepeatRatio: Double=0.0)

class AdaptiveViewModel(private val app: ContextEnglishApp,
    private val scheduler: AiTaskScheduler = app.aiScheduler,
    private val today: () -> LocalDate = { LocalDate.now() },
    private val now: () -> Long = System::currentTimeMillis,
    private val aiSettings: () -> AiSettings = { app.settingsStore.load() }): ViewModel() {
    private val repository=AdaptiveRepository(app.repository,now)
    private val mutable=MutableStateFlow(AdaptiveState())
    val state=mutable.asStateFlow()
    private val localMutex=Mutex()
    private val clock=StudyClock()
    private var aiJob: Job?=null
    private var aiGeneration=0L
    private var foreground=true
    init { reload() }
    fun reload() {
        if(mutable.value.busy) return
        local {
            OfflineWordResources.load(app)
            val day=today().toString();val words=app.repository.loadWords();val skills=repository.skills(today())
            val existing=repository.load(day)
            val mode=existing?.mode ?: mutable.value.workflow?.mode ?: Intensity.STANDARD
            val plan=if(existing?.started==true) existing else WorkflowFactory.create(day,mode,
                existing?.budgetMinutes ?: mode.minutes,words,repository.performance(today()),skills,now(),app.studySettingsStore.load().newWords).also(repository::save)
            mutable.update { it.copy(workflow=plan,words=words,skills=skills,results=repository.results(day),report=repository.summary().toString(2),
                lookedUp=repository.lookedUp(day,plan.readingId),tomorrow=repository.load(today().plusDays(1).toString())) }
            if(!mutable.value.active) clock.restore(plan.stepElapsedMs)
            loadMaterials(plan)
        }
    }
    private fun local(block: suspend ()->Unit) {
        mutable.update { it.copy(busy=true,message=null) }
        viewModelScope.launch {
            try { localMutex.withLock { withContext(Dispatchers.IO) { block() } } }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { mutable.update { it.copy(message="本地数据处理失败，进度已保留。${e.message?.take(80).orEmpty()}") } }
            finally { mutable.update { it.copy(busy=false) } }
        }
    }
    fun start() {
        val w=mutable.value.workflow ?: return
        if(mutable.value.busy) return
        clock.restore(w.stepElapsedMs)
        if(foreground && w.feedback.isBlank() && !w.finished) clock.resume()
        mutable.update { it.copy(active=true) }
        if(!w.started) persist(w.copy(started=true))
    }
    private fun checkpointClock() {
        val w=mutable.value.workflow ?: return
        val step=w.step ?: return
        val elapsed=clock.pause()
        if(w.feedback.isNotBlank()) return
        mutable.update { it.copy(workflow=it.workflow?.copy(stepElapsedMs=elapsed)) }
        viewModelScope.launch(Dispatchers.IO) { runCatching { repository.saveClock(w.day,step.key,elapsed) } }
    }
    fun pause() { checkpointClock();app.speech.stop();mutable.update { it.copy(active=false) } }
    fun foreground(active: Boolean) {
        foreground=active
        if(!active) { checkpointClock();app.speech.stop() }
        else if(mutable.value.workflow?.day!=today().toString()) {
            mutable.update { it.copy(active=false) };reload()
        } else if(mutable.value.active && mutable.value.workflow?.feedback?.isBlank()==true) clock.resume()
    }
    fun reveal() {
        val w=mutable.value.workflow ?: return
        if(!mutable.value.busy) persist(w.copy(revealed=true))
    }
    private fun persist(w: DailyWorkflow)=local { repository.save(w);mutable.update { it.copy(workflow=w) } }
    fun mode(mode: Intensity,minutes: Int=mode.minutes) {
        val old=mutable.value.workflow ?: return
        if(mutable.value.busy || old.finished) return
        local {
            // Preserve today's new-word cohort while re-planning; this projection never changes stored mastery.
            val projection=app.repository.loadWords().map { if(it.id in old.newIds) it.copy(state=WordState.NEW) else it }
            val fresh=WorkflowFactory.create(old.day,mode,minutes,projection,repository.performance(today()),repository.skills(today()),now(),app.studySettingsStore.load().newWords)
            val w=WorkflowFactory.resize(old,fresh)
            repository.save(w);mutable.update { it.copy(workflow=w) };loadMaterials(w)
        }
    }
    private fun updatedWords(id: Long): List<VocabWord> {
        val word=if(id>0) app.repository.loadWord(id) else null
        return if(word==null) mutable.value.words else mutable.value.words.map { if(it.id==id) word else it }
    }
    fun rate(rating: Int) {
        val before=mutable.value.workflow ?: return
        if(mutable.value.busy || before.step?.question!=null || !before.revealed || before.feedback.isNotBlank()) return
        val word=mutable.value.words.firstOrNull { it.id==before.step?.wordId } ?: return
        val elapsed=clock.pause()
        local {
            val audited=WorkflowFactory.audit(before,word,mutable.value.words,rating)
            val after=audited.copy(index=before.index+1,revealed=false,feedback="",stepElapsedMs=0,selectedAnswer="")
            repository.answer(before,after,rating==2,elapsed,rating)
            mutable.update { it.copy(workflow=after,words=updatedWords(word.id),aiContent="") }
            clock.reset(foreground && mutable.value.active)
        }
    }
    fun answer(value: String) {
        val before=mutable.value.workflow ?: return
        val q=before.step?.question ?: return
        if(mutable.value.busy || before.feedback.isNotBlank()) return
        if(q.spelling.isBlank() && value.toIntOrNull() !in q.options.indices) return
        val elapsed=clock.pause()
        local {
            val correct=q.correct(value)
            val chosen=if(q.spelling.isNotBlank()) value else q.options[value.toInt()]
            val after=before.copy(correct=correct,feedback="你的答案：$chosen\n"+(if(correct) "正确" else "再记一次：${q.answerText}")+"\n"+q.explanation,stepElapsedMs=0,selectedAnswer=value)
            repository.answer(before,after,correct,elapsed,answerValue=value)
            mutable.update { it.copy(workflow=after,words=updatedWords(q.wordId),skills=repository.skills(today()),results=repository.results(before.day),report=repository.summary().toString(2)) }
        }
    }
    fun next() {
        val w=mutable.value.workflow ?: return
        if(mutable.value.busy || w.feedback.isBlank()) return
        val next=w.copy(index=w.index+1,feedback="",revealed=false,stepElapsedMs=0,selectedAnswer="")
        local {
            repository.save(next);mutable.update { it.copy(workflow=next,aiContent="") }
            if(next.finished) {
                val tomorrow=calculateTomorrow()
                mutable.update { it.copy(tomorrow=tomorrow,results=repository.results(w.day),skills=repository.skills(today()),report=repository.summary().toString(2)) }
            }
            clock.reset(foreground && mutable.value.active && !next.finished)
        }
    }
    fun lookup(input: String,impacts: Boolean?=null) {
        val word=input.trim().lowercase(java.util.Locale.ROOT);if(word.isBlank()) return
        val w=mutable.value.workflow ?: return
        val reading=LocalContent.readings.first { it.id==w.readingId }
        val meaning=reading.glossary.entries.firstOrNull { it.key.equals(word,true) }?.value
            ?: mutable.value.words.firstOrNull { it.word.equals(word,true) }?.meaning ?: "本地词库暂无此词，可记录后查阅"
        local {
            repository.readingWord(w.day,w.readingId,word,meaning,impacts ?: (mutable.value.lookedUp[word] ?: false))
            mutable.update { it.copy(lookedUp=repository.lookedUp(w.day,w.readingId),message="$word：$meaning") }
        }
    }
    fun errorReason(reason: String) {
        val key=mutable.value.workflow?.step?.key ?: return
        if(!mutable.value.busy) local { repository.errorReason(key,reason);mutable.update { it.copy(message="已记录错因：$reason",report=repository.summary().toString(2)) } }
    }
    fun diagnoseReadingGrammar(tag: String) {
        val w=mutable.value.workflow ?: return
        if(!mutable.value.busy) local {
            val after=WorkflowFactory.readingGrammarDiagnosis(w,tag)
            repository.errorReason(w.step!!.key,"句子结构");repository.save(after)
            mutable.update { it.copy(workflow=after,message="已加入 $tag 验证题；依据真实作答再判断弱项。") }
        }
    }
    fun clearMessage() { mutable.update { it.copy(message=null) } }
    fun cancelAi() { aiGeneration++;aiJob?.cancel();mutable.update { it.copy(aiBusy=false,aiError="已取消；已发送请求的用量以服务商为准") } }
    private suspend fun loadMaterials(w: DailyWorkflow) {
        val settings=aiSettings()
        val repetition=repository.quizRepetition(w)
        val tasks=LearningEnhancements.plan(w,mutable.value.words,repository.skills(today()),repetition>=LearningParameters.LOCAL_QUIZ_REPEAT_THRESHOLD)
        val cache=buildMap { tasks.forEach { task ->
            scheduler.queue(settings,task.type,task.prompt)
            scheduler.cached(settings,task.type,task.prompt)?.let { content -> if(runCatching { task.validate(content) }.isSuccess) put(task.type,content) }
        } }
        mutable.update { it.copy(materialTasks=tasks,materials=cache,quizRepeatRatio=repetition,
            cachedExplanation=cache["reading-analysis-v2"].orEmpty(),cachedGrammar=cache["grammar-explanation-v2"].orEmpty()) }
    }
    private fun calculateTomorrow(): DailyWorkflow {
        val date=today().plusDays(1);val old=repository.load(date.toString())
        if(old?.started==true) return old
        val current=mutable.value.workflow
        return WorkflowFactory.create(date.toString(),current?.mode ?: Intensity.STANDARD,current?.budgetMinutes ?: 45,
            app.repository.loadWords(),repository.performance(today()),repository.skills(today()),date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),app.studySettingsStore.load().newWords)
            .also(repository::save)
    }
    fun prepareTomorrow()=aiAction {
        val prepared=withContext(Dispatchers.IO) { calculateTomorrow().also { plan -> mutable.update { it.copy(tomorrow=plan) } } }
        val tasks=withContext(Dispatchers.IO) { LearningEnhancements.plan(prepared,app.repository.loadWords(),repository.skills(today()),repository.quizRepetition(prepared)>=LearningParameters.LOCAL_QUIZ_REPEAT_THRESHOLD) }
        if(tasks.isEmpty()) {
            mutable.update { it.copy(aiContent="明日本地计划已保存；当前证据和题库足够，无需发起 AI 请求。") }
            return@aiAction
        }
        var done=0
        val failures=mutableListOf<String>()
        tasks.forEach { task ->
            try { request(task);done++ }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { failures.add(task.title+"："+friendlyAiError(e)) }
        }
        mutable.update { it.copy(aiContent="明日本地计划：新词 ${prepared.newIds.size}，复习 ${prepared.reviewIds.size}。已缓存 $done/${tasks.size} 项增强内容。"+
            if(failures.isEmpty()) "" else "\n"+failures.joinToString("\n"),aiError=if(failures.isEmpty()) "" else "部分内容准备失败；成功内容已保留，可手动重试。本地明日任务不受影响。") }
    }
    private suspend fun request(task: EnhancementTask): AiCallResult {
        val settings=withContext(Dispatchers.IO) { aiSettings() }
        val result=scheduler.request(settings,task.type,task.prompt,validate=task.validate).getOrThrow()
        val usage=withContext(Dispatchers.IO) { result.usageId?.let(app.repository::aiUsageRecord) }
        mutable.update { it.copy(lastAiUsage=usage,aiCacheHit=result.fromCache) }
        return result
    }
    fun explainCurrent()=aiAction {
        val w=mutable.value.workflow ?: return@aiAction
        val step=w.step ?: return@aiAction
        val attempt=withContext(Dispatchers.IO) { repository.attemptContext(step.key) }
        val isReading=step.question?.form=="reading"
        val task=if(isReading) {
            val reading=LocalContent.readings.first { it.id==w.readingId }
            LearningEnhancements.readingError(reading,step.question!!,attempt)
        }
        else EnhancementTask("question-analysis-v2","题目解析",
            "只返回JSON {\"explanation\":\"中文解析、规则和正确项依据及错项原因\",\"suggestions\":[\"建议\"]}。依据真实选择，不编造未记录的错误原因。\n${step.question?.let { it.prompt+"\n"+it.options+"\n答案："+it.answerText+"\n"+it.explanation } ?: w.grammarTag}\n真实作答：$attempt",AiTaskScheduler::validateExplanation)
        val result=request(task)
        val text=if(isReading) LearningEnhancements.display(task,result.content,step.question?.id) else AiTaskScheduler.parseObject(result.content).getString("explanation")
        mutable.update { it.copy(aiContent=text) }
    }
    fun enhance(kind: String)=aiAction {
        val snapshot=mutable.value;val w=snapshot.workflow ?: return@aiAction
        require(kind!="supplement" || snapshot.quizRepeatRatio>=LearningParameters.LOCAL_QUIZ_REPEAT_THRESHOLD) { "当前本地小测题量足够，继续使用离线题库即可" }
        val task=withContext(Dispatchers.IO) {
            val reading=LocalContent.readings.first { it.id==w.readingId }
            val word=snapshot.words.firstOrNull { it.id==(w.step?.question?.wordId ?: w.step?.wordId) }
            when(kind) {
                "sentence" -> LearningEnhancements.sentence(reading)
                "context" -> LearningEnhancements.context(word?.word ?: snapshot.lookedUp.keys.lastOrNull() ?: reading.glossary.keys.first(),if(word!=null) word.example else reading.passage)
                "examples" -> LearningEnhancements.examples(word ?: snapshot.words.first { it.id in w.newIds })
                "practice","supplement" -> LearningEnhancements.practice(w,snapshot.words,snapshot.skills,kind=="supplement")
                else -> error("未知增强类型")
            }
        }
        val result=request(task)
        if(task.practice) {
            val target=WeaknessDetector.priorities(snapshot.skills).firstOrNull()?.tag ?: w.grammarTag.ifBlank { "词义辨认" }
            val questions=LearningEnhancements.questions(task,result.content,snapshot.words,target)
            mutable.update { it.copy(pendingQuestions=questions,practiceKey=task.type+":"+AiTaskScheduler.hash(task.prompt)) }
        }
        mutable.update { it.copy(aiContent=LearningEnhancements.display(task,result.content,w.step?.question?.id)) }
    }
    fun useCachedPractice() {
        val s=mutable.value
        val task=s.materialTasks.firstOrNull { it.practice && s.materials.containsKey(it.type) } ?: return
        val target=WeaknessDetector.priorities(s.skills).firstOrNull()?.tag ?: s.workflow?.grammarTag.orEmpty().ifBlank { "词义辨认" }
        val questions=runCatching { LearningEnhancements.questions(task,s.materials.getValue(task.type),s.words,target) }.getOrElse {
            mutable.update { it.copy(aiError="缓存练习不再适用，继续使用本地题库。") };return
        }
        mutable.update { it.copy(pendingQuestions=questions,practiceKey=task.type+":"+AiTaskScheduler.hash(task.prompt),aiCacheHit=true) }
        addPractice()
    }
    fun addPractice() {
        val s=mutable.value;val w=s.workflow ?: return
        if(s.busy || s.pendingQuestions.isEmpty()) return
        local {
            val steps=s.pendingQuestions.mapIndexed { i,q -> LearningStep("${w.day}:${s.practiceKey}:$i","针对性练习",q.wordId,q) }
                .filter { candidate -> w.steps.none { it.key==candidate.key } }
            val next=w.copy(steps=w.steps+steps,minutes=w.minutes+(steps.size+1)/2)
            repository.save(next);mutable.update { it.copy(workflow=next,pendingQuestions=emptyList(),message=if(steps.isEmpty()) "这些练习已经加入，未重复计分。" else "已加入 ${steps.size} 道练习，可离线作答，结果参与诊断和复习。") }
        }
    }
    fun analyzeWeek()=aiAction {
        val summary=withContext(Dispatchers.IO) { repository.weeklySummary() }
        val task=EnhancementTask("weekly-analysis-v2","本周分析","只返回JSON {\"explanation\":\"统计解读\",\"suggestions\":[\"三个可操作建议\"]}。只依据以下真实统计，说明证据不足之处，不决定新词量，不编造对照周。\n$summary",AiTaskScheduler::validateExplanation)
        val explanation=AiTaskScheduler.parseObject(request(task).content).getString("explanation")
        mutable.update { it.copy(aiContent=explanation) }
    }
    private fun aiAction(block: suspend ()->Unit) {
        if(mutable.value.aiBusy) return
        val generation=++aiGeneration
        mutable.update { it.copy(aiBusy=true,aiError="",aiContent="",lastAiUsage=null,aiCacheHit=false) }
        aiJob=viewModelScope.launch {
            try { block() }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { mutable.update { it.copy(aiError=friendlyAiError(e)+"。本地学习可继续，点击原按钮重试。") } }
            finally { if(generation==aiGeneration) mutable.update { it.copy(aiBusy=false) } }
        }
    }
    class Factory(private val app: ContextEnglishApp): ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass: Class<T>): T=AdaptiveViewModel(app) as T
    }
}
