package com.tanhaowen.contextenglish

import android.os.Bundle
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tanhaowen.contextenglish.data.AiSettings
import com.tanhaowen.contextenglish.data.LearningStats
import com.tanhaowen.contextenglish.data.ReadingQuestion
import com.tanhaowen.contextenglish.data.VocabWord
import com.tanhaowen.contextenglish.data.WordState
import com.tanhaowen.contextenglish.data.builtInReading
import com.tanhaowen.contextenglish.ui.theme.ContextEnglishTheme
import com.tanhaowen.contextenglish.ui.theme.Forest
import com.tanhaowen.contextenglish.ui.theme.Mint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ContextEnglishApp
        setContent {
            ContextEnglishTheme {
                val appViewModel: AppViewModel = viewModel(
                    factory = AppViewModelFactory(app.repository, app.settingsStore, app.studySettingsStore)
                )
                ContextEnglishRoot(appViewModel)
            }
        }
    }
}

private enum class AppTab(val label: String) {
    HOME("今日"),
    STUDY("学习"),
    WORDS("词库"),
    AI("AI"),
    ME("我的")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContextEnglishRoot(viewModel: AppViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableStateOf(AppTab.HOME.name) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var aiWord by rememberSaveable { mutableStateOf("") }
    val openWordAi: (VocabWord) -> Unit = { aiWord = it.word; viewModel.selectWord(null); selectedTab = AppTab.AI.name }
    BackHandler(enabled = !state.studyActive && (showSettings || state.selectedWord != null || selectedTab != AppTab.HOME.name)) {
        if(showSettings) showSettings=false else if(state.selectedWord != null) viewModel.selectWord(null) else selectedTab=AppTab.HOME.name
    }
    state.selectedWord?.let { WordDetail(it, state, viewModel, { viewModel.selectWord(null) }, openWordAi) }
    val snackbarHostState = remember { SnackbarHostState() }
    val pageStates = rememberSaveableStateHolder()
    val lifecycleOwner = LocalLifecycleOwner.current
    val app=LocalContext.current.applicationContext as ContextEnglishApp
    DisposableEffect(lifecycleOwner) {
        viewModel.setForeground(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshOnResume()
            if (event == Lifecycle.Event.ON_START) viewModel.setForeground(true)
            if (event == Lifecycle.Event.ON_STOP) { viewModel.setForeground(false);app.speech.stop();app.feedback.stop() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.aiMessage) {
        state.aiMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    if(state.studyActive) {
        Box(Modifier.fillMaxSize()) {
            ImmersiveStudyScreen(state,viewModel)
            SnackbarHost(snackbarHostState,modifier=Modifier.align(Alignment.BottomCenter))
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (showSettings) "AI 设置" else AppTab.valueOf(selectedTab).label,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 19.sp
                        )
                    }
                },
                navigationIcon = {
                    if (showSettings) {
                        TextButton(onClick = { showSettings = false }) { Text("返回") }
                    }
                }
            )
        },
        bottomBar = {
            if (!showSettings) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    AppTab.entries.forEach { tab ->
                        NavigationBarItem(
                            modifier=Modifier.testTag("tab-${tab.name}"),
                            selected = selectedTab == tab.name,
                            onClick = { selectedTab = tab.name },
                            icon = {
                                Text(tab.label, style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (selectedTab == tab.name) FontWeight.SemiBold else FontWeight.Normal)
                            }
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Crossfade(targetState = if (showSettings) "SETTINGS" else selectedTab,
                animationSpec = tween(160), label = "pageTransition") { route ->
                pageStates.SaveableStateProvider(route) {
                    when (route) {
                        "SETTINGS" -> AiSettingsScreen(state, viewModel) { showSettings = false }
                        AppTab.HOME.name -> UpgradeHome(state,
                            start = { mode -> viewModel.startSession(mode); selectedTab = AppTab.STUDY.name },
                            words = { selectedTab = AppTab.WORDS.name })
                        AppTab.STUDY.name -> UpgradeStudy(state, viewModel, openWordAi) {
                            StudyScreen(state, viewModel::selectWord, viewModel::chooseAnswer, viewModel::submitReading,
                                { viewModel.updateWordState(it, WordState.LEARNING) },
                                { viewModel.updateWordState(it, WordState.MASTERED) }, viewModel::toggleWeak)
                        }
                        AppTab.WORDS.name -> UpgradeWords(state, viewModel)
                        AppTab.AI.name -> UpgradeAi(state, viewModel, { showSettings = true }, aiWord) { aiWord = "" }
                        AppTab.ME.name -> UpgradeMe(state, viewModel, { showSettings = true })
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    stats: LearningStats,
    onStartStudy: () -> Unit,
    onOpenWords: () -> Unit,
    onOpenAi: () -> Unit
) {
    val date = remember { SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(Date()) }
    val progress = if (stats.total == 0) 0f else stats.mastered.toFloat() / stats.total

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(date, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "今天也只做能坚持的小步",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("今日学习", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "1 篇情境阅读 · 10 个重点词 · 3 道题",
                        modifier = Modifier.padding(top = 6.dp, bottom = 18.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Button(onClick = onStartStudy, modifier = Modifier.fillMaxWidth()) {
                        Text("开始今日学习")
                    }
                }
            }
        }
        item {
            SectionTitle("你的进度")
            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatText("已掌握", stats.mastered.toString())
                        StatText("学习中", stats.learning.toString())
                        StatText("薄弱词", stats.weak.toString())
                        StatText("今日正确率", "${stats.todayAccuracy}%")
                    }
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "词库掌握 ${stats.mastered}/${stats.total}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
        item {
            SectionTitle("快速入口")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onOpenWords, modifier = Modifier.weight(1f)) {
                    Text("复习薄弱词")
                }
                OutlinedButton(onClick = onOpenAi, modifier = Modifier.weight(1f)) {
                    Text("按需 AI")
                }
            }
        }
    }
}

@Composable
private fun StatText(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StudyScreen(
    state: AppUiState,
    onWordClick: (VocabWord?) -> Unit,
    onChooseAnswer: (Int, Int) -> Unit,
    onSubmit: () -> Unit,
    onMarkLearning: (VocabWord) -> Unit,
    onMarkMastered: (VocabWord) -> Unit,
    onToggleWeak: (VocabWord) -> Unit
) {
    val targetWords = remember(state.words) {
        state.words.filter { it.word in builtInReading.targetWords }.associateBy { it.word.lowercase() }
    }
    val annotatedPassage = remember(targetWords) {
        buildPassage(builtInReading.passage, targetWords.keys)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("今日情境阅读", color = Forest, fontWeight = FontWeight.SemiBold)
            Text(
                builtInReading.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                "点击绿色重点词，查看它在本段中的意思。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        item {
            Card(shape = RoundedCornerShape(18.dp)) {
                ClickableText(
                    text = annotatedPassage,
                    style = TextStyle(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 17.sp,
                        lineHeight = 29.sp
                    ),
                    modifier = Modifier.padding(18.dp),
                    onClick = { offset ->
                        annotatedPassage.getStringAnnotations("WORD", offset, offset)
                            .firstOrNull()
                            ?.item
                            ?.let { key -> targetWords[key]?.let(onWordClick) }
                    }
                )
            }
        }
        state.selectedWord?.let { word ->
            item {
                WordMeaningCard(
                    word = word,
                    onClose = { onWordClick(null) },
                    onMarkLearning = { onMarkLearning(word) },
                    onMarkMastered = { onMarkMastered(word) },
                    onToggleWeak = { onToggleWeak(word) }
                )
            }
        }
        item { SectionTitle("读后练习") }
        items(builtInReading.questions, key = { it.id }) { question ->
            QuestionCard(
                question = question,
                selected = state.readingAnswers[question.id],
                showResult = state.readingScore != null,
                onChoose = { onChooseAnswer(question.id, it) }
            )
        }
        item {
            if (state.readingScore == null) {
                Button(onClick = onSubmit, modifier = Modifier.fillMaxWidth()) {
                    Text("提交答案")
                }
            } else {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text(
                            "本次得分 ${state.readingScore}/3",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text("先看证据，再看选项。错题已经计入今日记录。")
                    }
                }
            }
        }
    }
}

private fun buildPassage(passage: String, targets: Set<String>) = buildAnnotatedString {
    val wordRegex = Regex("[A-Za-z]+")
    var cursor = 0
    wordRegex.findAll(passage).forEach { match ->
        append(passage.substring(cursor, match.range.first))
        val key = match.value.lowercase()
        if (key in targets) {
            pushStringAnnotation("WORD", key)
            pushStyle(SpanStyle(color = Forest, fontWeight = FontWeight.Bold))
            append(match.value)
            pop()
            pop()
        } else {
            append(match.value)
        }
        cursor = match.range.last + 1
    }
    append(passage.substring(cursor))
}

@Composable
private fun WordMeaningCard(
    word: VocabWord,
    onClose: () -> Unit,
    onMarkLearning: () -> Unit,
    onMarkMastered: () -> Unit,
    onToggleWeak: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(word.word, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(word.phonetic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onClose) { Text("收起") }
            }
            Text(word.contextMeaning, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            Text(word.meaning, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(word.example, modifier = Modifier.padding(top = 10.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onMarkLearning, modifier = Modifier.weight(1f)) { Text("模糊") }
                Button(onClick = onMarkMastered, modifier = Modifier.weight(1f)) { Text("认识") }
            }
            TextButton(onClick = onToggleWeak, modifier = Modifier.align(Alignment.End)) {
                Text(if (word.weak) "移出薄弱词" else "加入薄弱词")
            }
        }
    }
}

@Composable
private fun QuestionCard(
    question: ReadingQuestion,
    selected: Int?,
    showResult: Boolean,
    onChoose: (Int) -> Unit
) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "${question.id}. ${question.prompt}",
                fontWeight = FontWeight.SemiBold,
                lineHeight = 23.sp
            )
            question.options.forEachIndexed { index, option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !showResult) { onChoose(index) }
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selected == index,
                        onClick = { if (!showResult) onChoose(index) },
                        enabled = !showResult
                    )
                    Text("${'A' + index}. $option", modifier = Modifier.padding(start = 4.dp))
                }
            }
            if (showResult) {
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Text(
                    if (selected == question.answerIndex) "回答正确 · ${question.logicLabel}"
                    else "正确答案 ${'A' + question.answerIndex} · ${question.logicLabel}",
                    color = if (selected == question.answerIndex) Forest else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold
                )
                Text(question.explanation, modifier = Modifier.padding(top = 5.dp))
            }
        }
    }
}

private enum class WordFilter(val label: String) {
    ALL("全部"), WEAK("薄弱"), LEARNING("学习中"), MASTERED("已掌握")
}

@Composable
private fun WordLibraryScreen(
    words: List<VocabWord>,
    onStateChange: (VocabWord, WordState) -> Unit,
    onToggleWeak: (VocabWord) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(WordFilter.ALL.name) }
    val visibleWords = remember(words, query, filter) {
        words.filter { word ->
            val matchesQuery = query.isBlank() || word.word.contains(query.trim(), ignoreCase = true) ||
                word.meaning.contains(query.trim())
            val matchesFilter = when (WordFilter.valueOf(filter)) {
                WordFilter.ALL -> true
                WordFilter.WEAK -> word.weak
                WordFilter.LEARNING -> word.state == WordState.LEARNING
                WordFilter.MASTERED -> word.state == WordState.MASTERED
            }
            matchesQuery && matchesFilter
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索单词或中文") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WordFilter.entries.forEach { item ->
                FilterChip(
                    selected = filter == item.name,
                    onClick = { filter = item.name },
                    label = { Text(item.label) }
                )
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(visibleWords, key = { it.id }) { word ->
                WordListItem(word, onStateChange, onToggleWeak)
            }
            if (visibleWords.isEmpty()) {
                item { EmptyState("没有符合条件的单词") }
            }
        }
    }
}

@Composable
private fun WordListItem(
    word: VocabWord,
    onStateChange: (VocabWord, WordState) -> Unit,
    onToggleWeak: (VocabWord) -> Unit
) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(word.word, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (word.weak) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "薄弱",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                    Text(word.phonetic, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(word.meaning, modifier = Modifier.padding(top = 6.dp))
                }
                Text(
                    when (word.state) {
                        WordState.NEW -> "未学"
                        WordState.LEARNING -> "学习中"
                        WordState.MASTERED -> "已掌握"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = Forest
                )
            }
            Text(
                word.example,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { onToggleWeak(word) }) {
                    Text(if (word.weak) "取消薄弱" else "标为薄弱")
                }
                TextButton(onClick = {
                    onStateChange(
                        word,
                        if (word.state == WordState.MASTERED) WordState.LEARNING else WordState.MASTERED
                    )
                }) {
                    Text(if (word.state == WordState.MASTERED) "继续学习" else "设为掌握")
                }
            }
        }
    }
}

@Composable
private fun AiScreen(
    state: AppUiState,
    onSettings: () -> Unit,
    onTest: () -> Unit,
    onLoadModels: () -> Unit,
    onGenerate: () -> Unit,
    onOpenCached: (com.tanhaowen.contextenglish.data.CachedReading) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("按需 AI", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "只有你点击按钮时才会联网调用",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                OutlinedButton(onClick = onSettings) { Text("设置") }
            }
        }
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("生成今日情境阅读", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "使用 ${state.settings.dailyModel}，生成适合当前基础的短文、重点词和题目。",
                        modifier = Modifier.padding(top = 6.dp, bottom = 14.dp)
                    )
                    Button(
                        onClick = onGenerate,
                        enabled = !state.aiBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (state.aiBusy) "正在处理…" else "手动生成并缓存")
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onTest, enabled = !state.aiBusy, modifier = Modifier.weight(1f)) {
                    Text("测试连接")
                }
                OutlinedButton(onClick = onLoadModels, enabled = !state.aiBusy, modifier = Modifier.weight(1f)) {
                    Text("读取模型")
                }
            }
        }
        if (state.availableModels.isNotEmpty()) {
            item {
                Card(shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("可用模型 ${state.availableModels.size}", fontWeight = FontWeight.SemiBold)
                        Text(
                            state.availableModels.take(10).joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
        state.aiOutput?.let { output ->
            item {
                SectionTitle("最近生成")
                Card(shape = RoundedCornerShape(18.dp)) {
                    Text(output, modifier = Modifier.padding(18.dp), lineHeight = 24.sp)
                }
            }
        }
        item {
            SectionTitle("使用统计")
            Card(shape = RoundedCornerShape(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatText("调用", state.usage.calls.toString())
                    StatText("Token", state.usage.totalTokens.toString())
                    StatText("估算费用", "¥${"%.4f".format(state.usage.estimatedCost)}")
                }
            }
        }
        if (state.cachedReadings.isNotEmpty()) {
            item { SectionTitle("本地缓存") }
            items(state.cachedReadings, key = { it.id }) { reading ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onOpenCached(reading) },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(reading.title, fontWeight = FontWeight.SemiBold)
                            Text(reading.model, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("打开", color = Forest)
                    }
                }
            }
        }
        item {
            Text(
                "API Key 只保存在本机设置中，不会写入源码；本 App 不会在后台自动调用 API。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
