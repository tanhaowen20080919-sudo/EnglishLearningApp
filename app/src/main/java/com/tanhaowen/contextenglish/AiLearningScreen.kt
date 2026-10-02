package com.tanhaowen.contextenglish

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun UpgradeAi(state: AppUiState, vm: AppViewModel, settings: () -> Unit, initialWord: String = "", consumeWord: () -> Unit = {}) {
    var kind by rememberSaveable { mutableStateOf("学习助手") }
    var question by rememberSaveable { mutableStateOf("") }
    var word by rememberSaveable { mutableStateOf("") }
    var sentence by rememberSaveable { mutableStateOf("") }
    var article by rememberSaveable { mutableStateOf("") }
    var topic by rememberSaveable { mutableStateOf("") }
    var essay by rememberSaveable { mutableStateOf("") }
    var readAction by rememberSaveable { mutableStateOf("翻译") }
    var writeAction by rememberSaveable { mutableStateOf("综合批改") }
    var recordsOpen by rememberSaveable { mutableStateOf(false) }
    var recordKind by rememberSaveable { mutableStateOf("今日建议") }
    var source by rememberSaveable { mutableStateOf("today") }
    var selectedWords by rememberSaveable { mutableStateOf("") }
    var days by rememberSaveable { mutableStateOf(1) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(initialWord) {
        if (initialWord.isNotBlank()) { kind = "单词讲解"; word = initialWord; consumeWord() }
    }
    val input = when (kind) { "单词讲解" -> word; "句子分析" -> sentence; "阅读辅助" -> article; "写作助手" -> essay; else -> question }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("AI 学习中心", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = settings) { Text("设置") }
            }
            Text("仅点击发送或生成时调用 AI，结果保存在本机。", style = MaterialTheme.typography.bodySmall)
            if (state.settings.apiKey.isBlank() || state.settings.dailyModel.isBlank()) Text("先在设置中填写 API Key 和模型名称。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("学习助手", "单词讲解", "句子分析", "阅读辅助", "写作助手").forEach { tool ->
                    FilterChip(selected = kind == tool, onClick = { kind = tool }, label = { Text(tool) })
                }
            }
        }
        if (kind == "阅读辅助") item {
            TaskPicker("阅读任务", readAction, listOf("翻译", "逐句解释", "提取重点词", "长难句分析", "文章总结", "解题思路")) { readAction = it }
        }
        if (kind == "写作助手") {
            item { TaskPicker("写作任务", writeAction, listOf("综合批改", "语法检查", "用词优化", "句式升级", "评分建议", "改写版本", "参考范文")) { writeAction = it } }
            item { OutlinedTextField(value = topic, onValueChange = { topic = it }, label = { Text("作文题目 / 要求") },
                minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth()) }
        }
        item {
            OutlinedTextField(value = input, onValueChange = { text ->
                when (kind) { "单词讲解" -> word = text; "句子分析" -> sentence = text; "阅读辅助" -> article = text; "写作助手" -> essay = text; else -> question = text }
            }, label = { Text(when (kind) {
                "单词讲解" -> "输入一个英语单词（可不在词库中）"
                "句子分析" -> "输入要分析的英文句子"
                "阅读辅助" -> "输入英语文章，可附题目"
                "写作助手" -> "输入自己的作文（生成范文可留空）"
                else -> "输入英语、语法、阅读或学习计划问题"
            }) }, minLines = if (kind == "单词讲解") 1 else 4, maxLines = 10, modifier = Modifier.fillMaxWidth().testTag("learning-input"))
        }
        item {
            Button(onClick = { vm.generateLearningTool(kind, input, if (kind == "写作助手") topic else "", when (kind) { "阅读辅助" -> readAction; "写作助手" -> writeAction; else -> "" }) },
                enabled = !state.aiBusy && (input.isNotBlank() || (kind == "写作助手" && writeAction == "参考范文" && topic.isNotBlank())),
                modifier = Modifier.fillMaxWidth()) { Text(if (state.aiBusy) "正在处理…" else if (kind == "学习助手") "发送问题" else "生成$kind") }
            if (state.aiBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        state.aiError?.let { error -> item {
            Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = vm::retryAi, enabled = !state.aiBusy) { Text("手动重试上次请求") }
        } }
        if (state.aiOutput != null || state.quiz.isNotEmpty()) item {
            HorizontalDivider()
            Text(state.aiResultTitle.ifBlank { "AI 回复" }, fontWeight = FontWeight.SemiBold)
            Row {
                TextButton(onClick = {
                    val text = state.aiOutput ?: state.quiz.joinToString("\n\n") { q -> "${q.prompt}\n${q.options.joinToString("\n")}\n答案：${q.options[q.answer]}\n${q.explanation}" }
                    clipboard.setText(AnnotatedString(text))
                }) { Text("复制结果") }
                TextButton(onClick = vm::clearAiOutput, enabled = !state.aiBusy) { Text("清空结果") }
            }
        }
        state.aiOutput?.let { output -> item { SelectionContainer { Text(output, lineHeight = 26.sp) } } }
        items(state.quiz.withIndex().toList(), key = { it.index }) { (i, q) ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${i + 1}. ${q.prompt}", fontWeight = FontWeight.SemiBold)
                q.options.forEachIndexed { j, option ->
                    OutlinedButton(onClick = { vm.chooseQuiz(i, j) }, enabled = !state.quizSubmitted, modifier = Modifier.fillMaxWidth()) {
                        Text("${if (state.quizAnswers[i] == j) "● " else ""}${'A' + j}. $option")
                    }
                }
                if (state.quizSubmitted) Text("${if (state.quizAnswers[i] == q.answer) "正确" else "回答错误"} · 正确答案 ${'A' + q.answer}\n${q.explanation}")
            }
        }
        if (state.quiz.isNotEmpty()) item { Button(onClick = vm::submitQuiz, enabled = !state.quizSubmitted && !state.sessionBusy) { Text("提交小测并记录结果") } }
        state.lastUsage?.let { record -> item { AiCallUsage(record) } }
        item {
            HorizontalDivider()
            TextButton(onClick = { recordsOpen = !recordsOpen }, contentPadding = PaddingValues(0.dp)) {
                Text("学习记录分析与练习 · ${if (recordsOpen) "收起" else "展开"}")
            }
        }
        if (recordsOpen) {
            item { TaskPicker("任务", recordKind, listOf("今日建议", "薄弱分析", "例句", "短文", "小测", "错词诊断", "学习报告")) { recordKind = it } }
            if (recordKind in listOf("例句", "短文", "小测")) item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("today" to "今日单词", "weak" to "薄弱词", "custom" to "自选词").forEach { (value, label) ->
                        FilterChip(selected = source == value, onClick = { source = value }, label = { Text(label) })
                    }
                }
                if (source == "custom") OutlinedTextField(value = selectedWords, onValueChange = { selectedWords = it },
                    label = { Text("输入词库中的词，用空格或逗号分隔") }, modifier = Modifier.fillMaxWidth())
            }
            if (recordKind == "学习报告") item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = days == 1, onClick = { days = 1 }, label = { Text("今日") })
                    FilterChip(selected = days == 7, onClick = { days = 7 }, label = { Text("最近7天") })
                }
            }
            item {
                OutlinedButton(onClick = { vm.generateAssistant(recordKind, source, selectedWords, days) }, enabled = !state.aiBusy, modifier = Modifier.fillMaxWidth()) { Text("生成$recordKind") }
                TextButton(onClick = vm::generateDailyReading, enabled = !state.aiBusy) { Text("生成情境阅读") }
            }
        }
        item { HorizontalDivider(); AiUsageOverview(state, vm) }
        item { Text("本地回复历史", fontWeight = FontWeight.SemiBold) }
        items(state.cachedReadings, key = { it.id }) { record ->
            TextButton(onClick = { vm.openCachedReading(record) }, enabled = !state.aiBusy, modifier = Modifier.fillMaxWidth()) { Text(record.title) }
        }
    }
}

@Composable
fun TaskPicker(label: String, current: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$label：$current") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false }) }
        }
    }
}
