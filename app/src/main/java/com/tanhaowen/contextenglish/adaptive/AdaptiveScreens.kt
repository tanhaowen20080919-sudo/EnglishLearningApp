package com.tanhaowen.contextenglish.adaptive

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tanhaowen.contextenglish.*
import com.tanhaowen.contextenglish.ai.AiTaskScheduler
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveApp(legacy: AppViewModel) {
    val app=LocalContext.current.applicationContext as ContextEnglishApp
    val vm: AdaptiveViewModel=viewModel(factory=AdaptiveViewModel.Factory(app))
    val state by vm.state.collectAsStateWithLifecycle()
    val legacyState by legacy.uiState.collectAsStateWithLifecycle()
    val old=legacyState.copy(aiBusy=legacyState.aiBusy || state.aiBusy)
    var route by rememberSaveable { mutableStateOf("今日学习") }
    val pages=rememberSaveableStateHolder()
    val snack=remember { SnackbarHostState() }
    val lifecycle=LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver { _,event ->
            if(event==Lifecycle.Event.ON_RESUME) { legacy.refreshOnResume(); vm.foreground(true) }
            if(event==Lifecycle.Event.ON_STOP) { vm.foreground(false); app.speech.stop() }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(route) {
        if(route=="今日学习" || route=="学习诊断") vm.reload()
        if(route in listOf("词库与错词","AI 学习助手","设置与备份")) legacy.refresh()
    }
    LaunchedEffect(state.message,old.aiMessage) {
        (state.message ?: old.aiMessage)?.let { snack.showSnackbar(it); vm.clearMessage(); legacy.clearMessage() }
    }
    BackHandler(enabled=state.active || route!="今日学习" || old.selectedWord!=null) {
        if(old.selectedWord!=null) legacy.selectWord(null) else if(state.active) vm.pause() else route="今日学习"
    }
    old.selectedWord?.let { WordDetail(it,old,legacy,{ legacy.selectWord(null) },{}) }
    Scaffold(
        topBar={ TopAppBar(title={ Text(if(state.active) state.workflow?.step?.phase ?: "学习完成" else route,fontWeight=FontWeight.SemiBold) },
            navigationIcon={ if(state.active || route !in listOf("今日学习","更多")) TextButton(onClick={
                if(state.active) vm.pause() else route="更多"
            }) { Text(if(state.active) "保存退出" else "返回") } }) },
        bottomBar={ if(!state.active && route in listOf("今日学习","更多")) NavigationBar {
            listOf("今日学习","更多").forEach { tab -> NavigationBarItem(selected=route==tab,onClick={ route=tab },
                icon={ Text(if(tab=="今日学习") "◉" else "☰") },label={ Text(tab) },modifier=Modifier.testTag("nav-$tab")) }
        } },snackbarHost={ SnackbarHost(snack) }
    ) { padding -> Box(Modifier.fillMaxSize().padding(padding)) {
        Crossfade(targetState=if(state.active) "__study__" else route,animationSpec=tween(90),label="page-transition") { page ->
        if(page=="__study__") WorkflowScreen(state,vm,old.studySettings.autoSpeak)
        else pages.SaveableStateProvider(page) {
            when(page) {
                "今日学习" -> TodayScreen(state,vm)
                "更多" -> LazyColumn(contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    item { Text("工具与设置",style=MaterialTheme.typography.headlineSmall) }
                    listOf("词库与错词" to "WORDS","学习诊断" to "DIAGNOSTICS","AI 学习助手" to "AI","设置与备份" to "ME").forEach { (title,tag) ->
                        item { OutlinedButton(onClick={ route=title },modifier=Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("tab-$tag")) { Text(title) } }
                    }
                    item { Text("核心学习完全离线；AI 只在点击按钮后联网。",color=MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                "词库与错词" -> UpgradeWords(old,legacy)
                "学习诊断" -> DiagnosticScreen(state,vm)
                "AI 学习助手" -> Column {
                    if(old.aiBusy) TextButton(onClick=legacy::cancelAi) { Text("取消 AI 请求") }
                    Box(Modifier.weight(1f)) { UpgradeAi(old,legacy,{ route="AI 设置" },"") {} }
                }
                "设置与备份" -> UpgradeMe(old,legacy) { route="AI 设置" }
                "AI 设置" -> AiSettingsScreen(old,legacy) { route="设置与备份" }
            }
        }
        }
    } }
}

@Composable
private fun TodayScreen(state: AdaptiveState, vm: AdaptiveViewModel) {
    val w=state.workflow
    var minutes by rememberSaveable { mutableStateOf("30") }
    LazyColumn(contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item {
            Text(w?.day.orEmpty(),color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text("把今天学扎实",fontSize=30.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=8.dp))
            Text("复习优先，按你的表现调整",color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=8.dp))
        }
        if(w==null) item {
            if(state.busy) CircularProgressIndicator() else Button(onClick=vm::reload) { Text("重试加载本地数据") }
        } else {
            item { Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(22.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text("预计 ${w.minutes} 分钟 · 预算 ${w.budgetMinutes} 分钟",style=MaterialTheme.typography.titleLarge)
                    Text("新词 ${w.newIds.size}  ·  到期复习 ${w.reviewIds.size}")
                    Text("阅读 ${if(w.steps.count { it.phase=="阅读" }<=2) "微型训练" else "1 篇 · 五类题型"}")
                    Text(if(w.grammarTag.isBlank()) "语法：小测持续诊断" else "${if(w.grammarFromWeakness) "弱项专项" else "语法加练"}：${w.grammarTag}")
                    Text("综合小测 ${w.steps.count { it.phase=="综合小测" }} 题 · 约 ${if(LearningParameters.shortSession(w.mode,w.budgetMinutes)) 3 else 8} 分钟")
                    LinearProgressIndicator(progress={ w.progress },modifier=Modifier.fillMaxWidth())
                    Text("完成 ${w.index} / ${w.steps.size}",style=MaterialTheme.typography.labelMedium)
                }
            } }
            item { Button(onClick=vm::start,enabled=!state.busy,modifier=Modifier.fillMaxWidth().height(56.dp).testTag("start-study")) {
                Text(if(w.finished) "查看今日总结" else if(w.started) "继续今日学习" else "开始今日学习")
            } }
            if(!w.finished) item {
                Text("今天有多少时间？",style=MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Intensity.entries.forEach { mode -> FilterChip(selected=w.mode==mode,onClick={ vm.mode(mode,minutes.toIntOrNull() ?: 30) },
                        enabled=!state.busy,label={ Text(mode.label+if(mode==Intensity.CUSTOM) "" else " ${mode.minutes}分") }) }
                }
                if(w.mode==Intensity.CUSTOM) {
                    OutlinedTextField(minutes,{ minutes=it.filter(Char::isDigit).take(3) },label={ Text("可用时间（10～120分钟）") },singleLine=true,
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())
                    TextButton(onClick={ vm.mode(Intensity.CUSTOM,minutes.toIntOrNull() ?: 30) },enabled=!state.busy) { Text("应用时间") }
                }
            }
            item { Text(w.reason,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            item { HorizontalDivider(); Text("准备明日内容",style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(top=18.dp))
                Text("明日任务由本地计算；手动准备可提前缓存阅读解析和弱项讲解。",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(vertical=8.dp))
                OutlinedButton(onClick={ vm.prepareTomorrow() },enabled=!state.aiBusy,modifier=Modifier.fillMaxWidth()) { Text("准备明日内容 · AI") }
                state.tomorrow?.let { Text("明日本地预测：新词 ${it.newIds.size} · 复习 ${it.reviewIds.size}；开始学习时按实际到期情况核对。",style=MaterialTheme.typography.bodySmall) }
                TextButton(onClick={ vm.enhance("practice") },enabled=!state.aiBusy && WeaknessDetector.priorities(state.skills).isNotEmpty()) { Text("按诊断生成针对性练习 · AI") }
                TextButton(onClick={ vm.enhance("supplement") },enabled=!state.aiBusy && state.quizRepeatRatio>=LearningParameters.LOCAL_QUIZ_REPEAT_THRESHOLD) { Text(if(state.quizRepeatRatio>=LearningParameters.LOCAL_QUIZ_REPEAT_THRESHOLD) "同类题目重复较多，补充练习 · AI" else "本地题库当前够用") }
                if(state.materialTasks.any { it.practice && state.materials.containsKey(it.type) }) OutlinedButton(onClick=vm::useCachedPractice,enabled=!state.busy && !state.aiBusy) { Text("加入已缓存专项 · 离线可用") }
                AiPanel(state,vm)
            }
        }
    }
}

@Composable
private fun WorkflowScreen(state: AdaptiveState,vm: AdaptiveViewModel,autoSpeak: Boolean) {
    val w=state.workflow ?: return
    val step=w.step
    val app=LocalContext.current.applicationContext as ContextEnglishApp
    val lifecycle=LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver { _,e -> resumed=lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED); if(e==Lifecycle.Event.ON_STOP) app.speech.stop() }
        lifecycle.lifecycle.addObserver(observer); onDispose { lifecycle.lifecycle.removeObserver(observer);app.speech.stop() }
    }
    val word=state.words.firstOrNull { it.id==step?.wordId }
    LaunchedEffect(step?.key,resumed) {
        app.speech.stop()
        if(resumed && autoSpeak && step?.question==null && word!=null) app.speech.speak(word.word,repetitions=2)
    }
    Column(Modifier.fillMaxSize().imePadding().testTag("daily-workflow")) {
        LinearProgressIndicator(progress={ w.progress },modifier=Modifier.fillMaxWidth())
        if(w.finished) {
            LazyColumn(contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                item { Text("今日学习完成",style=MaterialTheme.typography.headlineMedium,modifier=Modifier.testTag("daily-complete")) }
                item { Text("词汇、阅读和小测结果已保存，后续任务会参考今天的表现。") }
                state.tomorrow?.let { plan -> item { Text("明日本地计划已生成：新词 ${plan.newIds.size} · 到期复习 ${plan.reviewIds.size}。${plan.reason}") } }
                state.results.forEach { result -> item { Text("${result.tag}：${result.correct}/${result.total} · ${(result.accuracy*100).toInt()}%") } }
                item { Text("阅读记录生词 ${state.lookedUp.size} 个，其中 ${state.lookedUp.values.count { it }} 个影响理解。") }
                item { PriorityList(state) }
                item { Button(onClick=vm::pause,modifier=Modifier.fillMaxWidth()) { Text("完成，返回今日") } }
                item { OutlinedButton(onClick={ vm.prepareTomorrow() },enabled=!state.aiBusy,modifier=Modifier.fillMaxWidth()) { Text("准备明日内容 · AI") }; AiPanel(state,vm) }
            }
        } else key(step!!.key) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                Text("${w.index+1} / ${w.steps.size}  ·  ${step.phase}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                if(step.question==null && word!=null) {
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text(word.word,fontSize=38.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f).testTag("study-word"))
                        StudyIconButton(StudyIcon.SOUND,"美式发音",{ app.speech.speak(word.word,repetitions=2) })
                    }
                    Text(word.phonetic,fontSize=19.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("先在脑中回忆词义，再翻开答案。",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(!w.revealed) Button(onClick=vm::reveal,enabled=!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=50.dp).testTag("reveal-meaning")) { Text("显示释义") }
                    else {
                        Text(com.tanhaowen.contextenglish.study.StudyEngine.meaning(word),style=MaterialTheme.typography.titleLarge,modifier=Modifier.testTag("learn-meaning"))
                        if(word.contextMeaning.isNotBlank() && word.contextMeaning!=word.meaning) Text(word.contextMeaning)
                        Text(word.example,style=MaterialTheme.typography.bodyLarge)
                        Text(word.exampleTranslation,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        WordNotes.entries[word.word.lowercase()]?.let { note -> Text("常见搭配：${note.collocation}"); Text("词形：${note.forms}") }
                        Text("按真实感受选择，稍后可能随机抽查。",style=MaterialTheme.typography.bodySmall)
                        listOf("不认识","模糊","认识").forEachIndexed { i,label ->
                            OutlinedButton(onClick={ vm.rate(i) },enabled=!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=50.dp).testTag("rating-$i")) { Text(label) }
                        }
                        var full by rememberSaveable { mutableStateOf(false) }
                        TextButton(onClick={ full=!full }) { Text("展开完整词义与更多补充") }
                        if (full) {
                            Text(word.meaning)
                            OfflineWordResources.get(word.word)?.let { supplement ->
                                if(supplement.forms.isNotBlank() && WordNotes.entries[word.word.lowercase()]==null) Text("词形：${supplement.forms}")
                                var more by rememberSaveable { mutableStateOf(false) }
                                TextButton(onClick={ more=!more }) { Text("更多语境 · 离线") }
                                if(more) supplement.examples.forEach { e -> Text(e.text);if(e.translation.isNotBlank()) Text(e.translation) }
                            }
                            TextButton(onClick={ vm.enhance("context") },enabled=!state.aiBusy) { Text("AI 解释当前语境义") }
                            TextButton(onClick={ vm.enhance("examples") },enabled=!state.aiBusy) { Text("AI 补充例句、搭配与易混辨析") }
                            AiPanel(state,vm)
                        }
                    }
                } else step.question?.let { q ->
                    if(q.form=="reading") ReadingBody(state,vm)
                    if(step.phase=="语法专项" || q.form=="grammar" && w.feedback.isNotBlank()) LocalContent.grammars.firstOrNull { it.tag==q.tag }?.let { g ->
                        Card { Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            Text(g.tag,fontWeight=FontWeight.SemiBold); Text(g.card); Text(g.example)
                            if(state.cachedGrammar.isNotBlank() && q.tag==w.grammarTag) {
                                var expanded by rememberSaveable { mutableStateOf(false) }
                                TextButton(onClick={ expanded=!expanded }) { Text("已缓存 AI 讲解 · 离线可用") }
                                if(expanded) Text(runCatching { AiTaskScheduler.parseObject(state.cachedGrammar).getString("explanation") }.getOrDefault("缓存无法解析，请使用本地知识卡。"))
                            }
                        } }
                    }
                    Text(if(step.phase=="随机抽查") "用一道题检验真实记忆" else q.tag,style=MaterialTheme.typography.labelMedium)
                    Text(q.prompt,style=MaterialTheme.typography.headlineSmall,modifier=Modifier.testTag("question-prompt"))
                    var typed by rememberSaveable(step.key) { mutableStateOf("") }
                    if(q.spelling.isNotBlank()) {
                        OutlinedTextField(typed,{ typed=it.take(100) },label={ Text("拼写英文单词") },enabled=w.feedback.isBlank() && !state.busy,
                            singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Ascii),modifier=Modifier.fillMaxWidth().testTag("spelling-input"))
                        if(w.feedback.isBlank()) Button(onClick={ vm.answer(typed) },enabled=typed.isNotBlank() && !state.busy,modifier=Modifier.fillMaxWidth()) { Text("检查拼写") }
                    } else q.options.forEachIndexed { i,text ->
                        OutlinedButton(onClick={ vm.answer(i.toString()) },enabled=w.feedback.isBlank() && !state.busy,
                            modifier=Modifier.fillMaxWidth().heightIn(min=54.dp).testTag("answer-$i")) { Text("${('A'+i)}. $text",modifier=Modifier.fillMaxWidth()) }
                    }
                    if(w.feedback.isNotBlank()) {
                        Text(w.feedback,color=if(w.correct) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,modifier=Modifier.testTag("answer-feedback"))
                        if(!w.correct && q.form=="reading") {
                            Text("主要卡在哪里？")
                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                listOf("生词影响","证据定位","推断过度","句子结构").forEach { reason ->
                                    TextButton(onClick={ vm.errorReason(reason) }) { Text(reason) }
                                }
                            }
                            var diagnose by rememberSaveable { mutableStateOf(false) }
                            TextButton(onClick={ diagnose=!diagnose }) { Text("句子结构不清楚？做一道语法验证题") }
                            if(diagnose) Row(Modifier.horizontalScroll(rememberScrollState())) {
                                LocalContent.grammars.forEach { g -> TextButton(onClick={ vm.diagnoseReadingGrammar(g.tag) },enabled=!state.busy) { Text(g.tag) } }
                            }
                        }
                        Button(onClick=vm::next,enabled=!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=50.dp).testTag("continue-study")) { Text("继续") }
                        TextButton(onClick={ vm.explainCurrent() },enabled=!state.aiBusy) { Text("让 AI 给我讲明白") }
                    }
                    if(step.phase=="阅读" && state.cachedExplanation.isNotBlank() && w.feedback.isNotBlank()) {
                        var show by rememberSaveable { mutableStateOf(false) }
                        TextButton(onClick={ show=!show }) { Text("查看已缓存解析 · 离线可用") }
                        if(show) Text(runCatching { state.materialTasks.first { it.type=="reading-analysis-v2" }.let { com.tanhaowen.contextenglish.ai.LearningEnhancements.display(it,state.cachedExplanation,q.id) } }.getOrDefault("缓存格式已变化，请使用本地解析。"))
                    }
                    AiPanel(state,vm)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun ReadingBody(state: AdaptiveState,vm: AdaptiveViewModel) {
    val w=state.workflow ?: return
    val reading=LocalContent.readings.first { it.id==w.readingId }
    var search by rememberSaveable { mutableStateOf("") }
    Text(reading.title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
    val wordCount=remember(reading.id) { Regex("[A-Za-z]+(?:['-][A-Za-z]+)*").findAll(reading.passage).count() }
    Text("原创${if(wordCount>=260) "高考题型篇章" else "基础过渡"}训练 · 约 $wordCount 词",style=MaterialTheme.typography.labelSmall)
    val color=MaterialTheme.colorScheme.primary
    val text=remember(reading.id,color) { buildAnnotatedString {
        append(reading.passage)
        Regex("[A-Za-z]+(?:'[A-Za-z]+)?").findAll(reading.passage).forEach { m ->
            addStringAnnotation("word",m.value,m.range.first,m.range.last+1)
            if(reading.glossary.containsKey(m.value.lowercase())) addStyle(SpanStyle(color=color),m.range.first,m.range.last+1)
        }
    } }
    ClickableText(text,style=MaterialTheme.typography.bodyLarge.copy(color=MaterialTheme.colorScheme.onSurface,lineHeight=29.sp),onClick={ offset ->
        text.getStringAnnotations("word",offset,offset).firstOrNull()?.let { vm.lookup(it.item) }
    })
    Text("点击文中单词可查本地词义。",style=MaterialTheme.typography.bodySmall)
    OutlinedTextField(search,{ search=it.take(80) },label={ Text("记录文中生词") },singleLine=true,modifier=Modifier.fillMaxWidth())
    TextButton(onClick={ if(search.isNotBlank()) vm.lookup(search.trim()) },enabled=!state.busy) { Text("查词并记录") }
    TextButton(onClick={ vm.enhance("sentence") },enabled=!state.aiBusy) { Text("AI 拆解本文长难句") }
    TextButton(onClick={ vm.enhance("context") },enabled=!state.aiBusy) { Text("AI 解释刚查词的语境义") }
    listOf("sentence-structure-v2","context-meaning-v2").forEach { type ->
        state.materials[type]?.let { content ->
            val task=state.materialTasks.first { it.type==type }
            var show by rememberSaveable(type) { mutableStateOf(false) }
            TextButton(onClick={ show=!show }) { Text("${task.title} · 已缓存，离线可用") }
            if(show) Text(runCatching { com.tanhaowen.contextenglish.ai.LearningEnhancements.display(task,content) }.getOrDefault("缓存不可用，继续本地阅读。"))
        }
    }
    if(state.lookedUp.isNotEmpty()) {
        Text("本篇生词 ${state.lookedUp.size} · 影响理解 ${state.lookedUp.values.count { it }}",style=MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            state.lookedUp.forEach { (word,impact) -> FilterChip(selected=impact,onClick={ vm.lookup(word,!impact) },
                enabled=!state.busy,label={ Text("$word${if(impact) " · 影响理解" else " · 标记影响"}") }) }
        }
    }
    HorizontalDivider()
}

@Composable
private fun AiPanel(state: AdaptiveState,vm: AdaptiveViewModel) {
    if(state.aiBusy) Row(verticalAlignment=Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp)); Text("  生成中，可继续学习",style=MaterialTheme.typography.bodySmall)
        TextButton(onClick=vm::cancelAi) { Text("取消") }
    }
    if(state.aiError.isNotBlank()) Text(state.aiError,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
    if(state.aiContent.isNotBlank()) SelectionContainer { Text(state.aiContent,style=MaterialTheme.typography.bodyMedium) }
    if(state.aiCacheHit) Text("本次使用本地缓存，未发送新请求、未增加费用。",style=MaterialTheme.typography.bodySmall)
    state.lastAiUsage?.let { AiCallUsage(it) }
    if(state.pendingQuestions.isNotEmpty()) OutlinedButton(onClick=vm::addPractice,enabled=!state.busy && !state.aiBusy,modifier=Modifier.fillMaxWidth()) { Text("加入 ${state.pendingQuestions.size} 道练习，离线作答") }
}

@Composable
private fun PriorityList(state: AdaptiveState) {
    val priorities=WeaknessDetector.priorities(state.skills)
    Text("接下来优先练什么",style=MaterialTheme.typography.titleLarge)
    if(priorities.isEmpty()) Text("当前诊断样本较少，继续完成阅读和小测，积累至少 3 次同类作答后识别弱项。")
    priorities.forEachIndexed { i,s -> Text("${i+1}. ${s.tag} · 正确 ${s.correct}/${s.total}。${WeaknessDetector.recommendation(s.tag)}") }
}

@Composable
private fun DiagnosticScreen(state: AdaptiveState,vm: AdaptiveViewModel) {
    val report=remember(state.report) { runCatching { JSONObject(state.report) }.getOrDefault(JSONObject()) }
    LazyColumn(contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { PriorityList(state) }
        item { HorizontalDivider(); Text("词汇",style=MaterialTheme.typography.titleLarge)
            report.optJSONObject("vocabulary")?.let { Text("稳定掌握 ${it.optInt("stable")} · 学习中 ${it.optInt("learning")} · 高频遗忘 ${it.optInt("frequentForgetting")}") }
            Text(if(report.optInt("spellingSamples")==0) "拼写：暂无作答数据" else "拼写正确率 ${(report.optDouble("spellingAccuracy")*100).toInt()}% · ${report.optInt("spellingSamples")} 次")
        }
        item { Text("阅读与语法",style=MaterialTheme.typography.titleLarge)
            Text("参与阅读 ${report.optInt("readingArticles")} 篇（含未完成） · 已答 ${report.optInt("readingQuestions")} 题")
            Text("累计查词 ${report.optInt("unknownWords")} · 影响理解 ${report.optInt("impactWords")}")
            Text("以下阅读诊断使用最近 7 篇文章，其他技能使用最近 30 天。",style=MaterialTheme.typography.bodySmall)
        }
        state.skills.forEach { s -> item {
            Text("${s.tag}：${s.correct}/${s.total} · ${(s.accuracy*100).toInt()}%")
            LinearProgressIndicator(progress={ s.accuracy.toFloat() },modifier=Modifier.fillMaxWidth().padding(top=8.dp))
            Text("平均每题 ${if(s.total==0) 0 else s.millis/s.total/1000} 秒",style=MaterialTheme.typography.bodySmall)
        } }
        report.optJSONArray("lifetimeSkills")?.let { lifetime -> item {
            Text("长期累计诊断",style=MaterialTheme.typography.titleMedium)
            repeat(lifetime.length()) { i -> val s=lifetime.getJSONObject(i)
                Text("${s.getString("tag")}：${s.getInt("correct")}/${s.getInt("total")} · ${(s.getInt("correct")*100.0/s.getInt("total")).toInt()}%")
            }
        } }
        item {
            val causes=report.optJSONObject("readingErrorReasons") ?: JSONObject()
            if(causes.length()>0) {
                Text("阅读自选错因",style=MaterialTheme.typography.titleMedium)
                Text(causes.keys().asSequence().joinToString(" · ") { "$it ${causes.optInt(it)}次" })
            }
        }
        item { Text("学习节奏",style=MaterialTheme.typography.titleLarge)
            Text("平均活跃时长 ${report.optDouble("averageMinutes").toInt()} 分钟 · 计划完成率 ${(report.optDouble("completion")*100).toInt()}%")
            val modes=report.optJSONObject("modes") ?: JSONObject()
            Text(Intensity.entries.joinToString(" · ") { "${it.label} ${modes.optInt(it.name)}天" })
        }
        val trend=report.optJSONArray("readingTrend")
        if(trend!=null && trend.length()>0) item {
            Text("最近阅读耗时",style=MaterialTheme.typography.titleMedium)
            repeat(trend.length()) { i -> val r=trend.getJSONObject(i); Text("${r.getString("day")} · ${r.getLong("elapsedMs")/1000}秒 · ${(r.getDouble("accuracy")*100).toInt()}%") }
        }
        item { OutlinedButton(onClick={ vm.analyzeWeek() },enabled=!state.aiBusy,modifier=Modifier.fillMaxWidth()) { Text("AI 分析本周表现") }; AiPanel(state,vm) }
    }
}
