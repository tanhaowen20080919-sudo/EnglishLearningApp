package com.tanhaowen.contextenglish.ai

import com.tanhaowen.contextenglish.adaptive.*
import com.tanhaowen.contextenglish.data.VocabWord
import org.json.JSONObject

/** Prompts and schemas are bound to local evidence; generated questions use the real grading path. */
data class EnhancementTask(val type: String,val title: String,val prompt: String,
    val validate: (String)->Unit,val practice: Boolean = false)

object LearningEnhancements {
    /** Bind a manual explanation to the sentence on screen, including restored cloze answers. */
    fun displayedContext(w: DailyWorkflow,words: List<VocabWord>,lookedUp: String? = null): EnhancementTask {
        val reading=LocalContent.readings.first { it.id==w.readingId }
        val q=w.step?.question
        val word=words.firstOrNull { it.id==(q?.wordId ?: w.step?.wordId) }
        if(word!=null) {
            val sentence=if(q?.form=="context") q.prompt.substringBefore('\n').replace("____",word.word)
                else WordStudyResources.note(word)?.example ?: word.example
            return context(word.word,sentence)
        }
        val target=lookedUp?.takeIf { Regex("(?i)(?<![a-z])${Regex.escape(it)}(?![a-z])").containsMatchIn(reading.passage) }
            ?: reading.glossary.keys.first { Regex("(?i)(?<![a-z])${Regex.escape(it)}(?![a-z])").containsMatchIn(reading.passage) }
        return context(target,reading.passage)
    }
    private fun text(j: JSONObject,key: String,min: Int = 1,max: Int = 12000): String {
        val value=j.opt(key)
        require(value is String && value.trim().length in min..max) { "AI 字段 $key 缺失或格式不正确" }
        return value.trim()
    }
    private fun strings(j: JSONObject,key: String,min: Int=1,max: Int=8) {
        val a=j.getJSONArray(key);require(a.length() in min..max)
        repeat(a.length()) { require(a.get(it) is String && a.getString(it).isNotBlank()) }
    }
    private const val JSON_ONLY="只返回一个完整 JSON 对象，不输出 Markdown。所有解析用中文，英文引用不得改写；不编造学习记录。"
    fun reading(reading: ReadingLesson)=EnhancementTask("reading-analysis-v2","阅读逐题解析",
        "$JSON_ONLY\n为以下阅读五类题逐题解释正确选项及常见错误。格式 {\"explanation\":\"文章逻辑概述\",\"suggestions\":[\"建议\"],\"analysis\":[{\"id\":\"题目ID\",\"evidence\":\"原文中逐字引用的证据\",\"reason\":\"正确项依据及错项错误原因\"}]}。analysis 必须且只能覆盖给定题目 ID。\n${reading.passage}\n"+
            reading.questions.joinToString("\n") { "${it.id}: ${it.prompt} ${it.options} 正确答案=${it.answerText}" },{ output ->
                val j=AiTaskScheduler.parseObject(output);text(j,"explanation",10);strings(j,"suggestions")
                val a=j.getJSONArray("analysis");require(a.length()==reading.questions.size)
                val ids=(0 until a.length()).map { i -> val row=a.getJSONObject(i)
                    val quote=text(row,"evidence",5,1000)
                    require(reading.passage.contains(quote,true)) { "阅读解析引用的证据不在原文中" }
                    text(row,"reason",10);text(row,"id") }
                require(ids.toSet()==reading.questions.map { it.id }.toSet() && ids.distinct().size==ids.size)
            })
    fun sentence(reading: ReadingLesson): EnhancementTask {
        val sentence=reading.passage.split(Regex("(?<=[.!?])\\s+")).maxBy { it.length }
        return EnhancementTask("sentence-structure-v2","长难句结构拆解",
            "$JSON_ONLY\n分析指定原句，不自行替换句子。返回 {\"sentence\":\"原句\",\"mainClause\":\"主干并标出主谓宾\",\"clauses\":[\"从句/非谓语/修饰关系或说明这是简单句\"],\"translation\":\"完整中文译文\"}。\n原句：$sentence",{ output ->
                val j=AiTaskScheduler.parseObject(output);require(text(j,"sentence")==sentence)
                text(j,"mainClause",5);strings(j,"clauses");text(j,"translation",3)
            })
    }
    fun readingError(reading: ReadingLesson,q: LocalQuestion,attempt: JSONObject)=EnhancementTask("reading-error-v2","根据真实作答解释阅读题",
        "$JSON_ONLY\n仅分析当前题，不透露其他题答案。根据所给真实作答解释正确项依据、用户错选项的问题和已报告的错因；未记录的信息必须明确说不知道，不诊断臆测的个人错误原因。返回 {\"explanation\":\"逐步解释及下一步方法\",\"suggestions\":[\"建议\"],\"evidence\":\"原文逐字引用\"}。\n原文：${reading.passage}\n题目：${q.prompt}\n选项：${q.options}\n正确选项索引：${q.answer}\n真实作答索引/拼写及自报错因：$attempt",{ output ->
            AiTaskScheduler.validateExplanation(output)
            val j=AiTaskScheduler.parseObject(output)
            require(reading.passage.contains(text(j,"evidence",5,1000),true)) { "错因解释没有可核对的原文证据" }
        })
    fun context(word: String,passage: String)=EnhancementTask("context-meaning-v2","当前语境义",
        "$JSON_ONLY\n只解释指定单词在给定原文中的意思，不只罗列词典义。返回 {\"word\":\"指定词\",\"meaning\":\"当前语境义\",\"evidence\":\"原文逐字引用\",\"reason\":\"为何是此义而非其他义\"}。\n词：$word\n原文：$passage",{ output ->
            val j=AiTaskScheduler.parseObject(output);require(text(j,"word").equals(word,true))
            text(j,"meaning");text(j,"reason",8)
            val quote=text(j,"evidence",3,1000)
            require(passage.contains(quote,true) && Regex("(?i)(?<![a-z])${Regex.escape(word)}(?![a-z])").containsMatchIn(quote)) { "语境义证据不在原文中或未包含目标词" }
        })
    fun examples(word: VocabWord)=EnhancementTask("word-examples-v2","例句与易混辨析",
        "$JSON_ONLY\n为该高考词补充一个自然的新语境例句及中文翻译、常见搭配、必要词形和一个易混词辨析。保持原词义资料不变。返回 {\"word\":\"原词\",\"example\":\"英文例句，原词须原形出现\",\"translation\":\"中文\",\"collocations\":[\"搭配\"],\"forms\":\"词形或明确无变化\",\"contrast\":\"易混词与差别\"}。\n${word.word}: ${if(WordStudyResources.note(word)!=null) "已审校高中常用义" else "原词库义（排序未审校）"}=${com.tanhaowen.contextenglish.study.StudyEngine.meaning(word)}；原词库义=${word.meaning}\n原例句：${WordStudyResources.note(word)?.example ?: word.example}",{ output ->
            val j=AiTaskScheduler.parseObject(output);require(text(j,"word").equals(word.word,true))
            require(Regex("(?i)(?<![a-z])${Regex.escape(word.word)}(?![a-z])").containsMatchIn(text(j,"example",10,500)))
            text(j,"translation",3);strings(j,"collocations");text(j,"forms");text(j,"contrast",8)
        })
    fun grammar(tag: String)=EnhancementTask("grammar-explanation-v2","语法知识点讲解",
        "$JSON_ONLY\n解释本地计划安排的知识点：$tag。返回 {\"explanation\":\"超短规则、典型例句及逐步解析\",\"suggestions\":[\"练习建议\"]}。",AiTaskScheduler::validateExplanation)

    fun practice(w: DailyWorkflow,words: List<VocabWord>,skills: List<SkillScore>,supplement: Boolean=false): EnhancementTask {
        val weakest=WeaknessDetector.priorities(skills).firstOrNull()?.tag ?: w.grammarTag.ifBlank { "词义辨认" }
        val targets=(words.filter { it.weak }.sortedByDescending { it.mistakeCount }+
            w.newIds.mapNotNull { id -> words.find { it.id==id } }+words).distinctBy { it.id }.take(8)
        val reading=LocalContent.readings.first { it.id==w.readingId }
        val kind=if(supplement) "quiz-supplement-v2" else "target-practice-v2"
        val allowed=targets.associateBy { it.id }
        val prompt="$JSON_ONLY\n本地确定的训练重点=$weakest，不得改变本地新词量。生成3到5道可实际作答的针对题，四选一或拼写。返回 {\"questions\":[{\"prompt\":\"题干不泄漏答案\",\"options\":[\"四个互异选项；拼写题用空数组\"],\"answer\":0,\"explanation\":\"中文依据及错项解释\",\"form\":\"meaning/spelling/context/grammar/reading\",\"tag\":\"知识点或阅读题型\",\"wordId\":0,\"spelling\":\"仅拼写题填写目标词，其余为空\"}]}。词汇题 wordId 必须来自下表；grammar/reading 的 wordId=0。grammar 的 tag 必须是 ${LocalContent.grammars.map { it.tag }}；reading 的 tag 必须是 ${reading.questions.map { it.tag }}，并且只依据下列原文。\n词义题正确选项必须逐字使用给定核心义。语境题的正确选项必须是对应原词，context 字段给出题干中的英文挖空原句（含____）；非语境题 context 为空。拼写题不能在题干中泄漏原词。\n目标词：${targets.joinToString("\n") { "id=${it.id}; ${it.word}: ${com.tanhaowen.contextenglish.study.StudyEngine.meaning(it)}" }}\n原文：${reading.passage}\n真实诊断：${skills.joinToString { "${it.tag}=${it.correct}/${it.total}" }}"
        return EnhancementTask(kind,if(supplement) "补充小测题" else "针对性练习",prompt,{ output -> parsePractice(output,allowed,weakest,kind) },true)
    }
    private fun parsePractice(output: String,allowed: Map<Long,VocabWord>,target: String,type: String): List<LocalQuestion> {
        val a=AiTaskScheduler.parseObject(output).getJSONArray("questions");require(a.length() in 3..5)
        val result=(0 until a.length()).map { i ->
            val row=a.getJSONObject(i);val prompt=text(row,"prompt",5,1500);val why=text(row,"explanation",8,3000)
            val form=text(row,"form");require(form in setOf("meaning","context","spelling","grammar","reading"))
            val tag=text(row,"tag")
            val numericId=row.opt("wordId")
            require(numericId is Number && numericId.toDouble().isFinite() && numericId.toDouble()==numericId.toLong().toDouble())
            val id=row.getLong("wordId")
            if(form in setOf("meaning","context","spelling")) require(id in allowed) else require(id==0L)
            if(form=="grammar") require(LocalContent.grammars.any { it.tag==tag })
            if(form=="reading") require(tag in setOf("主旨题","细节题","推断题","词义猜测题","指代题"))
            if(target in LocalContent.grammars.map { it.tag }) require(form=="grammar" && tag==target) { "练习偏离本地语法弱项" }
            if(target in setOf("主旨题","细节题","推断题","词义猜测题","指代题")) require(form=="reading" && tag==target)
            if(target=="拼写") require(form=="spelling")
            if(target=="语境选词") require(form=="context")
            if(target=="词义辨认") require(form=="meaning")
            val options=row.getJSONArray("options").let { o -> (0 until o.length()).map { n -> require(o.get(n) is String);o.getString(n).trim() } }
            val spelling=row.optString("spelling","")
            val context=row.optString("context","")
            if(form=="spelling") require(spelling.equals(allowed.getValue(id).word,true) && options.isEmpty() && !Regex("(?i)(?<![a-z])${Regex.escape(spelling)}(?![a-z])").containsMatchIn(prompt))
            else {
                val answer=row.opt("answer")
                require(answer is Number && answer.toDouble().isFinite() && answer.toDouble()==answer.toInt().toDouble())
                require(spelling.isBlank() && options.size==4 && options.distinct().size==4 && options.all { it.isNotBlank() } && row.getInt("answer") in options.indices)
            }
            if(form=="meaning") require(options[row.getInt("answer")]==com.tanhaowen.contextenglish.study.StudyEngine.meaning(allowed.getValue(id))) { "词义题答案与本地词义不符" }
            if(form=="context") require(context.length>=10 && "____" in context && prompt.contains(context) && options[row.getInt("answer")].equals(allowed.getValue(id).word,true)) { "语境题缺少实际语境或答案与目标词不符" }
            LocalQuestion("$type:${AiTaskScheduler.hash(output).take(16)}:$i",prompt,options,if(form=="spelling") -1 else row.getInt("answer"),why,form,tag,id,spelling,
                "ai:${AiTaskScheduler.hash((if(form=="context") context else prompt).lowercase().replace(Regex("\\s+")," ")).take(24)}")
        }
        require(result.map { it.prompt.lowercase() }.distinct().size==result.size) { "AI 练习题重复" }
        return result
    }
    fun questions(task: EnhancementTask,output: String,words: List<VocabWord>,target: String): List<LocalQuestion> {
        task.validate(output)
        return parsePractice(output,words.associateBy { it.id },target,task.type)
    }
    fun plan(w: DailyWorkflow,words: List<VocabWord>,skills: List<SkillScore>,needsSupplement: Boolean=false): List<EnhancementTask> {
        val reading=LocalContent.readings.first { it.id==w.readingId }
        val readingTags=reading.questions.map { it.tag }.toSet()
        val readingScores=skills.filter { it.tag in readingTags }
        val needsReadingHelp=readingScores.map { it.tag }.toSet()!=readingTags || readingScores.any {
            it.total<LearningParameters.MIN_DIAGNOSTIC_SAMPLES || it.accuracy<LearningParameters.AI_READING_HELP_THRESHOLD
        }
        return buildList {
            if(needsReadingHelp) {
                add(reading(reading));add(sentence(reading))
                val word=words.firstOrNull { v -> v.state!=com.tanhaowen.contextenglish.data.WordState.MASTERED && Regex("(?i)(?<![a-z])${Regex.escape(v.word)}(?![a-z])").containsMatchIn(reading.passage) }
                if(word!=null) add(context(word.word,reading.passage))
            }
            if(w.grammarTag.isNotBlank() && w.grammarFromWeakness) add(grammar(w.grammarTag))
            if(WeaknessDetector.priorities(skills).isNotEmpty()) add(practice(w,words,skills))
            if(needsSupplement) add(practice(w,words,skills,supplement=true))
        }
    }
    fun display(task: EnhancementTask,output: String,questionId: String? = null): String {
        val j=AiTaskScheduler.parseObject(output)
        return when(task.type) {
            "reading-analysis-v2" -> {
                // Never reveal answers to unattempted questions when viewing a cached full-article analysis.
                val a=j.getJSONArray("analysis")
                (0 until a.length()).map { a.getJSONObject(it) }.filter { it.getString("id")==questionId }
                    .joinToString("\n") { "证据：${it.getString("evidence")}\n${it.getString("reason")}" }
            }
            "sentence-structure-v2" -> "${j.getString("sentence")}\n主干：${j.getString("mainClause")}\n${j.getJSONArray("clauses")}\n${j.getString("translation")}"
            "context-meaning-v2" -> "${j.getString("word")}：${j.getString("meaning")}\n${j.getString("evidence")}\n${j.getString("reason")}"
            "word-examples-v2" -> "${j.getString("example")}\n${j.getString("translation")}\n搭配：${j.getJSONArray("collocations")}\n词形：${j.getString("forms")}\n辨析：${j.getString("contrast")}"
            "grammar-explanation-v2" -> j.getString("explanation")
            "reading-error-v2" -> j.getString("explanation")+"\n证据："+j.getString("evidence")
            else -> "${j.getJSONArray("questions").length()} 道练习已校验，可加入本地学习并离线作答。"
        }
    }
}
