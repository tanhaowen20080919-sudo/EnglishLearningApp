package com.tanhaowen.contextenglish

import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tanhaowen.contextenglish.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun UpgradeHome(state: AppUiState, start: (String) -> Unit, words: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text(SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(Date()), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("今日任务", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        }
        item {
            Text("新词 ${state.plan.newCount} · 待复习 ${state.plan.reviewCount} · 薄弱词 ${state.plan.weakCount}")
            Text("今日完成 ${state.plan.completed.size} / ${state.plan.ids.size}", modifier = Modifier.padding(vertical = 12.dp))
            LinearProgressIndicator(progress = { if(state.plan.ids.isEmpty()) 0f else state.plan.completed.size.toFloat()/state.plan.ids.size }, modifier = Modifier.fillMaxWidth())
        }
        item {
            Button(onClick = { start("today") }, modifier = Modifier.fillMaxWidth()) { Text(if(state.plan.completed.isEmpty()) "开始今日学习" else "继续学习") }
            TextButton(onClick = { start("review") }, modifier = Modifier.fillMaxWidth()) { Text("今日复习 · ${state.words.count { it.due }}") }
            TextButton(onClick = { start("weak") }, modifier = Modifier.fillMaxWidth()) { Text("薄弱词强化 · ${state.stats.weak}") }
        }
        item {
            HorizontalDivider()
            Text("已学 ${state.stats.seen} · 已掌握 ${state.stats.mastered}", modifier = Modifier.padding(top=18.dp))
            Text("薄弱 ${state.stats.weak} · 连续学习 ${state.streak} 天", modifier = Modifier.padding(top=8.dp))
            Text("每日目标 ${state.studySettings.dailyGoal} 次 · 今日已完成 ${state.stats.todayAnswered} 次", modifier = Modifier.padding(top=8.dp))
        }
        item {
            TextButton(onClick = words) { Text("查看全部 ${state.stats.total} 个内置词汇") }
            Text("优先复习到期和薄弱词，再学习新词。新词默认每天20个，可在“我的”中调整。", color = MaterialTheme.colorScheme.onSurfaceVariant, style=MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun rememberSpeak(): (String) -> Unit {
    val context = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    var initialized by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<String?>(null) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(context) {
        val engine = TextToSpeech(context) { status -> ready = status == TextToSpeech.SUCCESS; initialized = true }
        tts = engine
        onDispose { engine.stop(); engine.shutdown() }
    }
    val say: (String) -> Unit = { word ->
        val engine = tts
        if(ready && engine != null && engine.setLanguage(Locale.US) >= 0) engine.speak(word, TextToSpeech.QUEUE_FLUSH, null, word)
        else Toast.makeText(context, "系统英语语音未就绪，请安装英语语音包后重试", Toast.LENGTH_SHORT).show()
    }
    LaunchedEffect(initialized, pending) {
        val word = pending
        if(initialized && word != null) { pending=null; say(word) }
    }
    return { word -> if(initialized) say(word) else pending=word }
}

@Composable
fun WordDetail(w: VocabWord, state: AppUiState, vm: AppViewModel, close: () -> Unit, ai: (VocabWord) -> Unit) {
    val speak = rememberSpeak()
    AlertDialog(onDismissRequest=close, title={ Text(w.word) }, text={
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(10.dp)) {
            if(state.studySettings.showPhonetic) Text(w.phonetic)
            Text("${w.partOfSpeech} · ${w.statusLabel} · 掌握度 ${w.familiarity}%")
            Text(w.meaning)
            if(w.example.isNotBlank()) { Text(w.example); Text(w.exampleTranslation) }
            else Text("此词暂未附本地例句，可手动生成 AI 例句。", style=MaterialTheme.typography.bodySmall)
            Text("正确 ${w.correctCount} · 错误 ${w.mistakeCount} · 复习 ${w.reviewCount}")
            if(w.nextReviewTime > 0) Text("下次复习：${SimpleDateFormat("M-d HH:mm", Locale.CHINA).format(Date(w.nextReviewTime))}")
            TextButton(onClick={ speak(w.word) }) { Text("发音") }
            TextButton(onClick={ vm.toggleFavorite(w) }) { Text(if(w.favorite) "取消收藏" else "收藏 / 重点") }
            TextButton(onClick={ vm.toggleWeak(w) }) { Text(if(w.weak) "移出薄弱词" else "加入薄弱词") }
            TextButton(onClick={ ai(w) }) { Text("AI 讲解这个词") }
        }
    }, confirmButton={ TextButton(onClick=close) { Text("关闭") } })
}

@Composable
fun UpgradeStudy(state: AppUiState, vm: AppViewModel, ai: (VocabWord) -> Unit, reading: @Composable () -> Unit) {
    var mode by rememberSaveable { mutableStateOf("words") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal=18.dp), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(selected=mode=="words", onClick={ mode="words" }, label={ Text("单词学习") })
            FilterChip(selected=mode=="reading", onClick={ mode="reading" }, label={ Text("情境阅读") })
        }
        if(mode=="reading") reading()
        else {
            val w = state.sessionIds.getOrNull(state.sessionIndex)?.let { id -> state.words.find { it.id==id } }
            val speak = rememberSpeak()
            var reveal by rememberSaveable(state.sessionIndex, w?.id) { mutableStateOf(false) }
            LaunchedEffect(w?.id, state.studySettings.autoSpeak) { if(w != null && state.studySettings.autoSpeak) speak(w.word) }
            LazyColumn(contentPadding=PaddingValues(22.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
                if(w==null) {
                    item { Text(if(state.sessionIds.isEmpty()) "当前没有待学习词汇" else "本轮学习已完成", style=MaterialTheme.typography.headlineSmall) }
                    item { Button(onClick={ vm.startSession() }) { Text("加载今日未完成单词") } }
                    item { TextButton(onClick={ vm.startSession("review") }) { Text("复习到期词") }; TextButton(onClick={ vm.startSession("weak") }) { Text("强化薄弱词") } }
                } else {
                    item { Text("${state.sessionIndex+1} / ${state.sessionIds.size} · ${w.statusLabel}") }
                    item { Text(w.word, style=MaterialTheme.typography.displaySmall, fontWeight=FontWeight.SemiBold); if(state.studySettings.showPhonetic) Text(w.phonetic) }
                    item { TextButton(onClick={ speak(w.word) }) { Text("听发音") } }
                    if(reveal) {
                        item { Text(w.partOfSpeech, color=MaterialTheme.colorScheme.onSurfaceVariant); Text(w.meaning, lineHeight=26.sp) }
                        if(w.example.isNotBlank()) item { Text(w.example); Text(w.exampleTranslation, modifier=Modifier.padding(top=8.dp)) }
                        item {
                            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                listOf("认识", "模糊", "不认识").forEachIndexed { i,label ->
                                    OutlinedButton(onClick={ vm.rateWord(w,2-i,true) }, enabled=!state.sessionBusy, modifier=Modifier.fillMaxWidth()) { Text(label) }
                                }
                            }
                        }
                    } else item { Button(onClick={ reveal=true }, modifier=Modifier.fillMaxWidth()) { Text("显示释义，再判断记忆程度") } }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
                            TextButton(onClick={ vm.moveSession(-1) }, enabled=state.sessionIndex>0 && !state.sessionBusy) { Text("上一个") }
                            TextButton(onClick={ vm.moveSession(1) }, enabled=!state.sessionBusy) { Text("下一个") }
                        }
                        TextButton(onClick={ vm.selectWord(w) }) { Text("详细信息 / 收藏") }
                        TextButton(onClick={ ai(w) }) { Text("AI 单词讲解") }
                    }
                }
            }
        }
    }
}

@Composable
fun UpgradeWords(state: AppUiState, vm: AppViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("全部") }
    var importance by rememberSaveable { mutableStateOf("全部级别") }
    val words = remember(state.words,query,filter,importance) { state.words.filter { w ->
        (query.isBlank() || w.word.contains(query.trim(),true) || w.meaning.contains(query.trim())) &&
        when(filter) {
            "未学习" -> w.state==WordState.NEW
            "学习中" -> w.state==WordState.LEARNING
            "薄弱" -> w.weak
            "待复习" -> w.due
            "已掌握" -> w.state==WordState.MASTERED
            "收藏" -> w.favorite
            else -> true
        } && when(importance) { "核心" -> w.importance==1; "高频" -> w.importance==2; "普通" -> w.importance==3; else -> true }
    } }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(value=query,onValueChange={ query=it },label={ Text("搜索英文或中文释义") },singleLine=true,modifier=Modifier.fillMaxWidth().padding(horizontal=18.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=18.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("全部","未学习","学习中","薄弱","待复习","已掌握","收藏").forEach { label -> FilterChip(selected=filter==label,onClick={ filter=label },label={ Text(label) }) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=18.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("全部级别","核心","高频","普通").forEach { label -> FilterChip(selected=importance==label,onClick={ importance=label },label={ Text(label) }) }
        }
        LazyColumn(contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            item { Text("${words.size} 个词",style=MaterialTheme.typography.labelMedium) }
            items(words,key={ it.id }) { w ->
                Column(Modifier.fillMaxWidth().clickable { vm.selectWord(w) }.padding(vertical=12.dp)) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(w.word,fontWeight=FontWeight.SemiBold); Text(w.statusLabel,style=MaterialTheme.typography.labelMedium) }
                    Text(w.meaning.lineSequence().take(2).joinToString("；"), color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=4.dp))
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun UpgradeAi(state: AppUiState, vm: AppViewModel, settings: () -> Unit, initialWord: String = "") {
    var kind by rememberSaveable { mutableStateOf("今日建议") }
    var source by rememberSaveable { mutableStateOf("today") }
    var input by rememberSaveable { mutableStateOf("") }
    var days by rememberSaveable { mutableStateOf(1) }
    LaunchedEffect(initialWord) { if(initialWord.isNotBlank()) { kind="单词讲解"; source="custom"; input=initialWord } }
    LazyColumn(contentPadding=PaddingValues(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {
            Text("AI 学习助手",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
            Text("根据你的词汇学习数据提供针对性帮助",style=MaterialTheme.typography.bodySmall)
            Text(if(state.settings.apiKey.isBlank()) "尚未配置 API" else "AI 已配置",modifier=Modifier.padding(top=8.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            listOf("今日建议","薄弱分析","单词讲解","例句","短文","小测","错词诊断","学习报告").chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    row.forEach { label -> OutlinedButton(onClick={ kind=label },modifier=Modifier.weight(1f)) { Text(if(kind==label) "• $label" else label) } }
                }
            }
        }
        if(kind in listOf("单词讲解","例句","短文","小测")) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf("today" to "今日单词","weak" to "薄弱词","custom" to "自选词").forEach { (value,label) -> FilterChip(selected=source==value,onClick={ source=value },label={ Text(label) }) }
                }
                if(source=="custom") OutlinedTextField(value=input,onValueChange={ input=it },label={ Text("输入词库中的单词，以逗号或空格分隔") },modifier=Modifier.fillMaxWidth())
                Text("短文最多12词，其他任务最多20词；单词讲解使用第一个词。",style=MaterialTheme.typography.bodySmall)
            }
        }
        if(kind=="学习报告") item {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { FilterChip(selected=days==1,onClick={ days=1 },label={ Text("今日") }); FilterChip(selected=days==7,onClick={ days=7 },label={ Text("最近7天") }) }
        }
        item {
            Button(onClick={ vm.generateAssistant(kind,source,input,days) },enabled=!state.aiBusy,modifier=Modifier.fillMaxWidth()) { Text(if(state.aiBusy) "生成中…" else "生成$kind") }
            if(state.aiBusy) LinearProgressIndicator(modifier=Modifier.fillMaxWidth().padding(top=8.dp))
        }
        state.aiError?.let { error -> item { Text(error,color=MaterialTheme.colorScheme.error); TextButton(onClick=vm::retryAi,enabled=!state.aiBusy) { Text("重试上次请求") } } }
        state.aiOutput?.let { output -> item {
            val primary = MaterialTheme.colorScheme.primary
            SelectionContainer {
                Text(buildAnnotatedString {
                    val pattern = Regex("\\*\\*(.+?)\\*\\*")
                    var position = 0
                    pattern.findAll(output).forEach { m -> append(output.substring(position,m.range.first)); pushStyle(SpanStyle(fontWeight=FontWeight.Bold,color=primary)); append(m.groupValues[1]); pop(); position=m.range.last+1 }
                    append(output.substring(position))
                },lineHeight=26.sp)
            }
            if(state.lastTokens.isNotBlank()) Text(state.lastTokens,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=12.dp))
        } }
        items(state.quiz.withIndex().toList(),key={ it.index }) { (i,q) ->
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("${i+1}. ${q.prompt}",fontWeight=FontWeight.SemiBold)
                q.options.forEachIndexed { j,option -> OutlinedButton(onClick={ vm.chooseQuiz(i,j) },enabled=!state.quizSubmitted,modifier=Modifier.fillMaxWidth()) { Text("${if(state.quizAnswers[i]==j) "● " else ""}${'A'+j}. $option") } }
                if(state.quizSubmitted) Text("${if(state.quizAnswers[i]==q.answer) "正确" else "回答错误"} · 正确答案 ${'A'+q.answer}\n${q.explanation}")
                HorizontalDivider()
            }
        }
        if(state.quiz.isNotEmpty()) item { Button(onClick=vm::submitQuiz,enabled=!state.quizSubmitted && !state.sessionBusy) { Text("提交小测并记录结果") } }
        item {
            HorizontalDivider()
            Text("本地历史记录",fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=16.dp))
        }
        items(state.cachedReadings,key={ it.id }) { record ->
            TextButton(onClick={ vm.openCachedReading(record) },modifier=Modifier.fillMaxWidth()) { Text(record.title) }
        }
        item {
            Text("累计请求 ${state.usage.calls} · 已知 Token ${state.usage.totalTokens}",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={ vm.generateDailyReading() },enabled=!state.aiBusy) { Text("原有功能：生成情境阅读") }
            TextButton(onClick=settings) { Text("AI 设置") }
            Row { TextButton(onClick=vm::testConnection,enabled=!state.aiBusy) { Text("测试连接") }; TextButton(onClick=vm::loadModels,enabled=!state.aiBusy) { Text("读取模型") } }
            if(state.availableModels.isNotEmpty()) Text(state.availableModels.take(30).joinToString("\n"),style=MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun UpgradeMe(state: AppUiState, vm: AppViewModel, aiSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var newWords by remember(state.studySettings) { mutableStateOf(state.studySettings.newWords.toString()) }
    var goal by remember(state.studySettings) { mutableStateOf(state.studySettings.dailyGoal.toString()) }
    var phonetic by remember(state.studySettings) { mutableStateOf(state.studySettings.showPhonetic) }
    var autoSpeak by remember(state.studySettings) { mutableStateOf(state.studySettings.autoSpeak) }
    var clear by remember { mutableStateOf(false) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var backupBusy by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if(uri != null) scope.launch {
            backupBusy=true
            try { val text=vm.exportBackup(); withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) } ?: error("无法写入文件") }; Toast.makeText(context,"备份已导出，不含 API Key",Toast.LENGTH_LONG).show() }
            catch(e: Exception) { Toast.makeText(context,"备份导出失败",Toast.LENGTH_LONG).show() }
            finally { backupBusy=false }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> importUri=uri }
    if(clear) AlertDialog(onDismissRequest={ clear=false },title={ Text("清空学习记录？") },text={ Text("词库和收藏会保留，学习状态及学习记录将清空。建议先导出备份。") },confirmButton={ TextButton(onClick={ vm.clearStudyRecords(); clear=false }) { Text("清空") } },dismissButton={ TextButton(onClick={ clear=false }) { Text("取消") } })
    importUri?.let { uri -> AlertDialog(onDismissRequest={ importUri=null },title={ Text("恢复备份？") },text={ Text("将用备份覆盖当前学习数据，API Key 保持现有配置。") },confirmButton={ TextButton(onClick={
        importUri=null; scope.launch {
            backupBusy=true
            try { val text=withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader -> val buffer=CharArray(8192); val out=StringBuilder(); while(true) { val n=reader.read(buffer); if(n<0) break; require(out.length+n<=20_000_000); out.append(buffer,0,n) }; out.toString() } ?: error("无法读取文件") }; vm.importBackup(text) }
            catch(e: Exception) { Toast.makeText(context,"无法读取备份，原数据未修改",Toast.LENGTH_LONG).show() }
            finally { backupBusy=false }
        }
    }) { Text("恢复") } },dismissButton={ TextButton(onClick={ importUri=null }) { Text("取消") } }) }
    LazyColumn(contentPadding=PaddingValues(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { Text("学习设置",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold) }
        item { OutlinedTextField(value=newWords,onValueChange={ newWords=it.filter(Char::isDigit) },label={ Text("每日新词数量（1–100）") },singleLine=true,modifier=Modifier.fillMaxWidth()) }
        item { OutlinedTextField(value=goal,onValueChange={ goal=it.filter(Char::isDigit) },label={ Text("每日作答目标（1–500）") },singleLine=true,modifier=Modifier.fillMaxWidth()) }
        item {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("显示音标"); Switch(checked=phonetic,onCheckedChange={ phonetic=it }) }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("自动发音"); Switch(checked=autoSpeak,onCheckedChange={ autoSpeak=it }) }
            Button(onClick={ vm.saveStudySettings(StudySettings(newWords.toInt(),goal.toInt(),phonetic,autoSpeak)) },enabled=(newWords.toIntOrNull() ?: 0) in 1..100 && (goal.toIntOrNull() ?: 0) in 1..500) { Text("保存学习设置") }
        }
        item { HorizontalDivider(); Text("学习统计",style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(top=14.dp)); Text("词库 ${state.stats.total} · 已学 ${state.stats.seen} · 已掌握 ${state.stats.mastered}\n薄弱 ${state.stats.weak} · 今日正确率 ${state.stats.todayAccuracy}%\n连续学习 ${state.streak} 天") }
        item {
            TextButton(onClick={ export.launch("EnglishLearning-backup.json") },enabled=!backupBusy) { Text("导出数据") }
            TextButton(onClick={ import.launch(arrayOf("application/json","text/plain","application/octet-stream")) },enabled=!backupBusy) { Text("导入数据") }
            TextButton(onClick={ clear=true },enabled=!backupBusy) { Text("清空学习记录") }
            if(backupBusy) LinearProgressIndicator(modifier=Modifier.fillMaxWidth())
            TextButton(onClick=aiSettings) { Text("AI 设置") }
        }
        item {
            HorizontalDivider(); Text("English Learning ${BuildConfig.VERSION_NAME}",modifier=Modifier.padding(top=14.dp))
            Text("内置词表来自你此前筛选的高考重点词表，释义与音标补充自 ECDICT。",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={ context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/tanhaowen20080919-sudo/EnglishLearningApp"))) }) { Text("GitHub 项目") }
        }
    }
}
