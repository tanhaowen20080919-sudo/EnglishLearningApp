package com.tanhaowen.contextenglish

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tanhaowen.contextenglish.data.AutoAiPolicy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean)->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
        Text(label,modifier=Modifier.weight(1f));Switch(checked=value,onCheckedChange=onChange)
    }
}

@Composable
fun StudyPreferences(state: AppUiState,vm: AppViewModel,close: ()->Unit) {
    val saved=state.studySettings
    var newWords by rememberSaveable { mutableStateOf(saved.newWords.toString()) }
    var goal by rememberSaveable { mutableStateOf(saved.dailyGoal.toString()) }
    var phonetic by rememberSaveable { mutableStateOf(saved.showPhonetic) }
    var autoSpeak by rememberSaveable { mutableStateOf(saved.autoSpeak) }
    var sound by rememberSaveable { mutableStateOf(saved.sound) }
    var haptics by rememberSaveable { mutableStateOf(saved.haptics) }
    var advance by rememberSaveable { mutableStateOf(saved.autoAdvance) }
    var autoAi by rememberSaveable { mutableStateOf(saved.autoAi) }
    var budget by rememberSaveable { mutableStateOf(saved.autoAiBudget.toString()) }
    var rate by rememberSaveable { mutableFloatStateOf(saved.speechRate) }
    val app=androidx.compose.ui.platform.LocalContext.current.applicationContext as ContextEnglishApp
    LazyColumn(contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { TextButton(onClick=close) { Text("返回我的") };Text("学习设置",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold) }
        item { OutlinedTextField(newWords,{ newWords=it.filter(Char::isDigit) },label={ Text("每日新词（1–100）") },singleLine=true,modifier=Modifier.fillMaxWidth()) }
        item { OutlinedTextField(goal,{ goal=it.filter(Char::isDigit) },label={ Text("每组最多词数（1–500）") },singleLine=true,modifier=Modifier.fillMaxWidth()) }
        item {
            ToggleRow("答题音效",sound) { sound=it };ToggleRow("轻微震动",haptics) { haptics=it }
            ToggleRow("答对后自动下一题",advance) { advance=it }
            Text("答错时停留看解释，点“记住了，继续”再前进。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { HorizontalDivider();Text("发音",style=MaterialTheme.typography.titleMedium) }
        item {
            ToggleRow("显示音标",phonetic) { phonetic=it };ToggleRow("自动朗读单词",autoSpeak) { autoSpeak=it }
            Text("语速 ${String.format(Locale.ROOT,"%.1f",rate)}×")
            Slider(value=rate,onValueChange={ rate=it },valueRange=0.6f..1.2f,steps=5)
            TextButton(onClick={ app.speech.speak("available") }) { Text("试听当前已保存的声音") }
            Text("当前使用本机美式英语TTS；高质量发音包以后再导入。",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { HorizontalDivider();Text("后台AI",style=MaterialTheme.typography.titleMedium) }
        item {
            ToggleRow("允许按需自动调用",autoAi) { autoAi=it }
            Text("仅学习期间，在重复答错时批量生成提示与语境练习。切到后台后不启动新请求，已发送的请求可完成并缓存。",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(budget,{ budget=it.filter { c -> c.isDigit() || c=='.' } },
                label={ Text("每日自动AI估算预算（${state.settings.currency}）") },singleLine=true,modifier=Modifier.fillMaxWidth().padding(top=12.dp))
            Text("每天最多${AutoAiPolicy.MAX_DAILY_CALLS}次自动请求，失败不自动重发。输入和输出单价必须先在AI设置中填写。",style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=10.dp))
            Text("预算基于Token与单价估算，不是服务商实际扣费硬上限。用量不明时暂停当天自动调用；手动AI不受此预算限制。",style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=8.dp))
        }
        item {
            val valid=(newWords.toIntOrNull() ?: 0) in 1..100 && (goal.toIntOrNull() ?: 0) in 1..500 &&
                budget.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..100.0 }==true
            Button(onClick={ vm.saveStudySettings(saved.copy(newWords=newWords.toInt(),dailyGoal=goal.toInt(),showPhonetic=phonetic,
                autoSpeak=autoSpeak,sound=sound,haptics=haptics,autoAdvance=advance,autoAi=autoAi,autoAiBudget=budget.toDouble(),speechRate=rate));close() },
                enabled=valid,modifier=Modifier.fillMaxWidth()) { Text("保存设置") }
        }
    }
}

@Composable
fun AutoAiHistory(state: AppUiState) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        TextButton(onClick={ expanded=!expanded }) { Text(if(expanded) "收起后台AI记录" else "后台AI记录 · ${state.autoAiJobs.size}") }
        if(state.autoAiStatus.isNotBlank()) Text(state.autoAiStatus,style=MaterialTheme.typography.bodySmall)
        if(expanded) {
            if(state.autoAiJobs.isEmpty()) Text("尚无自动调用；本地答题不会产生API费用。",style=MaterialTheme.typography.bodySmall)
            state.autoAiJobs.take(20).forEach { job ->
                val time=SimpleDateFormat("M-d HH:mm",Locale.CHINA).format(Date(job.createdAt))
                val status=when(job.status) { "DONE" -> "完成";"RUNNING" -> "生成中";else -> "失败，不自动重发" }
                Text("$time · $status",fontWeight=FontWeight.Medium)
                Text(job.reason,style=MaterialTheme.typography.bodySmall)
                Text("模型 ${job.model} · ${job.wordIds.size}词",style=MaterialTheme.typography.bodySmall)
                state.usageRecords.find { it.id==job.usageId }?.let { r ->
                    Text("输入 ${r.usage.promptTokens} · 输出 ${r.usage.completionTokens} · 缓存写入 ${r.usage.cacheCreationTokens} · 缓存读取 ${r.usage.cacheReadTokens}",style=MaterialTheme.typography.bodySmall)
                }
                Text(if(job.usageKnown) "估算费用 ${String.format(Locale.ROOT,"%.6f",job.actualCost ?: 0.0)} ${job.currency}"
                    else "费用/用量未确认，预留 ${String.format(Locale.ROOT,"%.6f",job.reservedCost)} ${job.currency}",style=MaterialTheme.typography.bodySmall)
                if(job.finishedAt>0) Text("耗时 ${(job.finishedAt-job.createdAt)/1000}秒",style=MaterialTheme.typography.bodySmall)
                if(job.error.isNotBlank()) Text(job.error,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                HorizontalDivider()
            }
        }
    }
}
