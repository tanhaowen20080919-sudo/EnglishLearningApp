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
import androidx.compose.ui.platform.testTag
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
    var more by rememberSaveable { mutableStateOf(false) }
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
            Button(onClick = { start("today") }, enabled=state.loaded,modifier = Modifier.fillMaxWidth().height(54.dp).testTag("start-study")) {
                Text(if(state.studySession?.finished==false) "继续上次学习" else "开始今日学习") }
            TextButton(onClick={ more=!more }) { Text(if(more) "收起专项练习" else "专项练习") }
            if(more) {
                TextButton(onClick = { start("review") }, modifier = Modifier.fillMaxWidth()) { Text("到期复习 · ${state.words.count { it.due }}") }
                TextButton(onClick = { start("random") }, modifier = Modifier.fillMaxWidth()) { Text("随机学习 · 未掌握优先") }
                TextButton(onClick = { start("weak") }, modifier = Modifier.fillMaxWidth()) { Text("薄弱词强化 · ${state.stats.weak}") }
            }
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
    val app = LocalContext.current.applicationContext as ContextEnglishApp
    return remember(app) { { word: String -> app.speech.speak(word) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordDetail(w: VocabWord, state: AppUiState, vm: AppViewModel, close: () -> Unit, ai: (VocabWord) -> Unit) {
    val speak = rememberSpeak()
    var requested by rememberSaveable(w.id) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest=close, sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=false)) {
        Column(Modifier.fillMaxWidth().heightIn(max=520.dp).verticalScroll(rememberScrollState())
            .padding(start=24.dp,end=24.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Text(w.word,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                StudyIconButton(StudyIcon.SOUND,"朗读单词",{ speak(w.word) })
                StudyIconButton(StudyIcon.STAR,if(w.favorite) "取消收藏" else "收藏单词",{ vm.toggleFavorite(w) })
            }
            if(state.studySettings.showPhonetic) Text(w.phonetic,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(w.meaning,style=MaterialTheme.typography.bodyLarge)
            if(w.example.isNotBlank()) { Text(w.example);Text(w.exampleTranslation,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            state.coachNotes[w.id]?.let { Text(it.tip,color=MaterialTheme.colorScheme.primary) }
            HorizontalDivider()
            Text("正确 ${w.correctCount} · 错误 ${w.mistakeCount} · 复习 ${w.reviewCount}",style=MaterialTheme.typography.bodySmall)
            Text("掌握度 ${w.familiarity}% · ${w.statusLabel}",style=MaterialTheme.typography.bodySmall)
            if(w.nextReviewTime>0) Text("下次复习：${SimpleDateFormat("M-d HH:mm",Locale.CHINA).format(Date(w.nextReviewTime))}",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={ vm.toggleWeak(w) }) { Text(if(w.weak) "移出薄弱词" else "加入薄弱词") }
            TextButton(onClick={ requested=true;vm.generateLearningTool("单词讲解",w.word) },enabled=!state.aiBusy) { Text("需要更多？AI讲解") }
            if(requested) {
                if(state.aiBusy) Text("后台生成中，可以先关闭面板继续学习。",style=MaterialTheme.typography.bodySmall)
                state.aiError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                state.aiOutput?.let { SelectionContainer { Text(it,style=MaterialTheme.typography.bodyMedium) } }
            }
        }
    }
}

@Composable
fun UpgradeStudy(state: AppUiState, vm: AppViewModel, ai: (VocabWord) -> Unit, reading: @Composable () -> Unit) {
    var inReading by rememberSaveable { mutableStateOf(false) }
    if(inReading) {
        androidx.activity.compose.BackHandler { inReading=false }
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick={ inReading=false }) { Text("返回学习") }
            Box(Modifier.weight(1f)) { reading() }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            Text("单词学习",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
            Text("一次只学一个词，新词与复习自动安排。",color=MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick={ vm.startSession() },enabled=state.loaded,modifier=Modifier.fillMaxWidth()) {
                Text(if(state.studySession?.finished==false) "继续上次学习" else "开始今日学习")
            }
            HorizontalDivider()
            Text("专项练习",style=MaterialTheme.typography.titleMedium)
            TextButton(onClick={ vm.startSession("review") }) { Text("到期复习 · ${state.words.count { it.due }}") }
            TextButton(onClick={ vm.startSession("weak") }) { Text("薄弱词强化 · ${state.stats.weak}") }
            TextButton(onClick={ vm.startSession("random") }) { Text("随机学习 · 未掌握优先") }
            HorizontalDivider()
            TextButton(onClick={ inReading=true }) { Text("情境阅读") }
            Text("阅读独立于单词答题，不打断当前学习进度。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
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
        OutlinedTextField(value=query,onValueChange={ query=it },label={ Text("搜索英文或中文释义") },singleLine=true,modifier=Modifier.fillMaxWidth().padding(horizontal=18.dp).testTag("word-search"))
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
fun UpgradeMe(state: AppUiState, vm: AppViewModel, aiSettings: () -> Unit) {
    var preferences by rememberSaveable { mutableStateOf(false) }
    if(preferences) {
        androidx.activity.compose.BackHandler { preferences=false }
        StudyPreferences(state,vm) { preferences=false }
        return
    }
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
    importUri?.let { uri -> AlertDialog(onDismissRequest={ importUri=null },title={ Text("恢复备份？") },text={ Text("将用备份恢复学习数据、AI 使用统计和设置，API Key 保持现有配置。") },confirmButton={ TextButton(onClick={
        importUri=null; scope.launch {
            backupBusy=true
            try { val text=withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader -> val buffer=CharArray(8192); val out=StringBuilder(); while(true) { val n=reader.read(buffer); if(n<0) break; require(out.length+n<=20_000_000); out.append(buffer,0,n) }; out.toString() } ?: error("无法读取文件") }; vm.importBackup(text) }
            catch(e: Exception) { Toast.makeText(context,"无法读取备份，原数据未修改",Toast.LENGTH_LONG).show() }
            finally { backupBusy=false }
        }
    }) { Text("恢复") } },dismissButton={ TextButton(onClick={ importUri=null }) { Text("取消") } }) }
    LazyColumn(contentPadding=PaddingValues(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { TextButton(onClick={ preferences=true }) { Text("学习设置") } }
        item { AiUsageOverview(state, vm) }
        item { AutoAiHistory(state) }
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
