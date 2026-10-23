package com.tanhaowen.contextenglish.adaptive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tanhaowen.contextenglish.ContextEnglishApp
import com.tanhaowen.contextenglish.ai.*
import com.tanhaowen.contextenglish.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.time.LocalDate

data class AdaptiveState(val workflow: DailyWorkflow? = null, val words: List<VocabWord> = emptyList(),
    val skills: List<SkillScore> = emptyList(), val results: List<SkillScore> = emptyList(), val report: String = "", val lookedUp: Map<String,Boolean> = emptyMap(),
    val active: Boolean = false, val busy: Boolean = false, val message: String? = null,
    val aiBusy: Boolean = false, val aiContent: String = "", val aiError: String = "", val cachedExplanation: String = "", val cachedGrammar: String = "")

class AdaptiveViewModel(private val app: ContextEnglishApp): ViewModel() {
    private val repository=AdaptiveRepository(app.repository)
    private val scheduler=app.aiScheduler
    private val mutable=MutableStateFlow(AdaptiveState())
    val state=mutable.asStateFlow()
    private var entered=System.nanoTime()
    private var aiJob: Job?=null
    private var foreground=true
    init { reload() }
    fun reload() {
        if(mutable.value.busy) return
        local {
            val day=LocalDate.now().toString()
            val words=app.repository.loadWords()
            val skills=repository.skills()
            val existing=repository.load(day)
            val plan=if(existing?.started==true) existing else WorkflowFactory.create(day,existing?.mode ?: Intensity.STANDARD,
                existing?.minutes ?: 45,words,repository.performance(),skills,System.currentTimeMillis(),app.studySettingsStore.load().newWords).also(repository::save)
            mutable.update { it.copy(workflow=plan,words=words,skills=skills,results=repository.results(day),report=repository.summary().toString(2),lookedUp=repository.lookedUp(day),busy=false) }
            loadExplanation(plan)
        }
    }
    private fun local(block: suspend ()->Unit) {
        mutable.update { it.copy(busy=true,message=null) }
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { mutable.update { it.copy(message="本地数据处理失败，进度已保留。${e.message?.take(80).orEmpty()}") } }
            finally { mutable.update { it.copy(busy=false) } }
        }
    }
    fun start() {
        val w=mutable.value.workflow ?: return
        if(mutable.value.busy) return
        entered=System.nanoTime()
        mutable.update { it.copy(active=true) }
        if(!w.started) persist(w.copy(started=true))
    }
    fun pause() { app.speech.stop(); mutable.update { it.copy(active=false) } }
    fun foreground(active: Boolean) { foreground=active; entered=System.nanoTime(); if(!active) app.speech.stop() }
    fun reveal() {
        val w=mutable.value.workflow ?: return
        if(!mutable.value.busy) persist(w.copy(revealed=true))
    }
    private fun persist(w: DailyWorkflow) = local {
        repository.save(w); mutable.update { it.copy(workflow=w) }
    }
    fun mode(mode: Intensity, minutes: Int = mode.minutes) {
        val old=mutable.value.workflow ?: return
        if(mutable.value.busy || old.finished) return
        local {
            val words=app.repository.loadWords()
            val fresh=WorkflowFactory.create(old.day,mode,minutes,words,repository.performance(),repository.skills(),System.currentTimeMillis(),app.studySettingsStore.load().newWords)
            val w=if(!old.started) fresh else {
                val keep=fresh.newIds.size
                var remaining=0
                val tail=old.steps.drop(old.index).filter { s ->
                    if(s.phase=="新词") { remaining++; remaining<=keep } else true
                }
                old.copy(mode=mode,minutes=fresh.minutes,reason=fresh.reason,
                    steps=old.steps.take(old.index)+tail,
                    newIds=(old.steps.take(old.index).filter { it.phase=="新词" }+tail.filter { it.phase=="新词" }).map { it.wordId })
            }
            repository.save(w); mutable.update { it.copy(workflow=w) }
        }
    }
    fun rate(rating: Int) {
        val before=mutable.value.workflow ?: return
        if(mutable.value.busy || before.step?.question!=null || !before.revealed || before.feedback.isNotBlank()) return
        val word=mutable.value.words.firstOrNull { it.id==before.step?.wordId } ?: return
        local {
            val audited=WorkflowFactory.audit(before,word,mutable.value.words,rating)
            val after=audited.copy(index=before.index+1,revealed=false,feedback="")
            repository.answer(before,after,rating==2,elapsed(),rating)
            mutable.update { it.copy(workflow=after,words=app.repository.loadWords()) }; entered=System.nanoTime()
        }
    }
    fun answer(value: String) {
        val before=mutable.value.workflow ?: return
        val q=before.step?.question ?: return
        if(mutable.value.busy || before.feedback.isNotBlank()) {
            return
        }
        if(q.spelling.isBlank() && value.toIntOrNull() !in q.options.indices) {
            return
        }
        local {
            val correct=q.correct(value)
            val after=before.copy(correct=correct,feedback=(if(correct) "正确" else "再记一次：${q.answerText}")+"\n"+q.explanation)
            repository.answer(before,after,correct,elapsed())
            mutable.update { it.copy(workflow=after,words=app.repository.loadWords(),skills=repository.skills(),results=repository.results(before.day),report=repository.summary().toString(2)) }
        }
    }
    fun next() {
        val w=mutable.value.workflow ?: return
        if(mutable.value.busy || w.feedback.isBlank()) return
        val next=w.copy(index=w.index+1,feedback="",revealed=false)
        local { repository.save(next); mutable.update { it.copy(workflow=next) }; entered=System.nanoTime() }
    }
    private fun elapsed()=if(foreground) ((System.nanoTime()-entered)/1_000_000).coerceIn(0,600_000) else 0
    fun lookup(input: String,impacts: Boolean? = null) {
        val word=input.trim().lowercase(java.util.Locale.ROOT)
        if(word.isBlank()) return
        val w=mutable.value.workflow ?: return
        val reading=LocalContent.readings.first { it.id==w.readingId }
        val meaning=reading.glossary.entries.firstOrNull { it.key.equals(word,true) }?.value
            ?: mutable.value.words.firstOrNull { it.word.equals(word,true) }?.meaning ?: "本地词库暂无此词，可记录后查阅"
        local {
            repository.readingWord(w.day,w.readingId,word,meaning,impacts ?: (mutable.value.lookedUp[word] ?: false))
            mutable.update { it.copy(lookedUp=repository.lookedUp(w.day),message="$word：$meaning") }
        }
    }
    fun errorReason(reason: String) {
        val key=mutable.value.workflow?.step?.key ?: return
        if(!mutable.value.busy) local { repository.errorReason(key,reason); mutable.update { it.copy(message="已记录错因：$reason",report=repository.summary().toString(2)) } }
    }
    fun clearMessage() { mutable.update { it.copy(message=null) } }
    fun cancelAi() { aiJob?.cancel(); mutable.update { it.copy(aiBusy=false,aiError="已取消；已发送请求的用量以服务商为准") } }
    private fun explanationPrompt(w: DailyWorkflow): String {
        val reading=LocalContent.readings.first { it.id==w.readingId }
        return "为以下高考阅读给简明中文解析，指出主旨、推断证据和一个长难句。仅返回JSON {\"explanation\":\"解析文字\",\"suggestions\":[\"建议\"]}。\n${reading.title}\n${reading.passage}\n${reading.questions.joinToString("\n") { it.prompt+" 答案："+it.answerText }}"
    }
    private suspend fun loadExplanation(w: DailyWorkflow) {
        val settings=app.settingsStore.load()
        val prompt=explanationPrompt(w)
        scheduler.queue(settings,"reading-explanation",prompt) // Queue inspection never sends a request.
        val cached=scheduler.cached(settings,"reading-explanation",prompt).orEmpty()
        val grammar=if(w.grammarTag.isNotBlank()) {
            scheduler.queue(settings,"grammar-explanation",grammarPrompt(w.grammarTag))
            scheduler.cached(settings,"grammar-explanation",grammarPrompt(w.grammarTag)).orEmpty()
        } else ""
        mutable.update { it.copy(cachedExplanation=cached,cachedGrammar=grammar) }
    }
    private fun grammarPrompt(tag: String) =
        "解释高考语法弱项：$tag。仅返回JSON {\"explanation\":\"中文讲解及典型例句\",\"suggestions\":[\"针对性练习建议\"]}。"

    /** Called only by the explicit prepare-tomorrow button. The plan itself is always local. */
    fun prepareTomorrow() = aiAction {
        val day=LocalDate.now().plusDays(1)
        val words=withContext(Dispatchers.IO) { app.repository.loadWords() }
        val tomorrow=withContext(Dispatchers.IO) {
            repository.load(day.toString()) ?: WorkflowFactory.create(day.toString(),mutable.value.workflow?.mode ?: Intensity.STANDARD,45,
                words,repository.performance(),repository.skills(),day.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),app.studySettingsStore.load().newWords)
                .also(repository::save)
        }
        val settings=withContext(Dispatchers.IO) { app.settingsStore.load() }
        val tasks=mutableListOf("reading-explanation" to explanationPrompt(tomorrow))
        if(tomorrow.grammarTag.isNotBlank()) tasks.add("grammar-explanation" to grammarPrompt(tomorrow.grammarTag))
        tasks.forEach { (type,prompt) -> scheduler.queue(settings,type,prompt) }
        val content=tasks.map { (type,prompt) -> scheduler.request(settings,type,prompt,validate=AiTaskScheduler::validateExplanation).getOrThrow().content }
        mutable.update { it.copy(aiContent="明日本地计划已保存，已准备 ${content.size} 项内容。\n"+content.joinToString("\n\n") { AiTaskScheduler.parseObject(it).getString("explanation") }) }
    }
    fun explainCurrent() = aiAction {
        val w=mutable.value.workflow ?: return@aiAction
        val step=w.step
        val prompt=if(step?.phase=="阅读") explanationPrompt(w) else
            "讲清下面的英语题，给依据、典型例句和下一步练习建议，仅返回JSON {\"explanation\":\"中文解析\",\"suggestions\":[\"建议\"]}。\n${step?.question?.let { it.prompt+"\n答案："+it.answerText+"\n"+it.explanation } ?: w.grammarTag}"
        val settings=withContext(Dispatchers.IO) { app.settingsStore.load() }
        val result=scheduler.request(settings,if(step?.phase=="阅读") "reading-explanation" else "question-explanation",prompt,validate=AiTaskScheduler::validateExplanation).getOrThrow()
        mutable.update { it.copy(aiContent=AiTaskScheduler.parseObject(result.content).getString("explanation")) }
    }
    fun analyzeWeek() = aiAction {
        val summary=withContext(Dispatchers.IO) { repository.weeklySummary() }
        val settings=withContext(Dispatchers.IO) { app.settingsStore.load() }
        val result=scheduler.request(settings,"weekly-analysis","解读以下本地学习统计。只根据真实数据，说明证据不足之处，给三个可操作建议。不要改变本地计划。\n$summary").getOrThrow()
        mutable.update { it.copy(aiContent=result.content) }
    }
    private fun aiAction(block: suspend ()->Unit) {
        if(mutable.value.aiBusy) return
        mutable.update { it.copy(aiBusy=true,aiError="",aiContent="") }
        aiJob=viewModelScope.launch {
            try { block() }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { mutable.update { it.copy(aiError=friendlyAiError(e)+"。本地学习可继续，点击原按钮重试。") } }
            finally { mutable.update { it.copy(aiBusy=false) } }
        }
    }
    class Factory(private val app: ContextEnglishApp): ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass: Class<T>): T = AdaptiveViewModel(app) as T
    }
}
