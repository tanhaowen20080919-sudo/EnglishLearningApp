package com.tanhaowen.contextenglish

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tanhaowen.contextenglish.data.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatMoney(value: Double, currency: String): String {
    val symbol = when (currency) { "CNY" -> "¥"; "USD" -> "$"; else -> "$currency " }
    return symbol + String.format(Locale.ROOT, "%.8f", value).trimEnd('0').trimEnd('.').ifBlank { "0" }
}

@Composable
fun AiCallUsage(record: AiUsageRecord, title: String = "本次使用") {
    var expanded by rememberSaveable(record.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
            Text("$title · ${record.usage.totalTokens} Tokens · ${formatMoney(record.totalCost, record.currency)}  ${if (expanded) "收起" else "详情"}")
        }
        if (!record.usage.known) Text("服务商未返回完整用量，缺失字段显示 0；费用仅按已返回用量估算。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (expanded) {
            val u = record.usage
            Text("输入（含缓存） ${u.promptTokens} · 输出 ${u.completionTokens}")
            Text("普通输入 ${u.normalInputTokens} · 缓存写入 ${u.cacheCreationTokens} · 缓存读取 ${u.cacheReadTokens}")
            Text("总 Tokens ${u.totalTokens}")
            if (record.hasPriceSnapshot) {
                listOf(Triple("普通输入", record.cost.input, record.inputPrice),
                    Triple("输出", record.cost.output, record.outputPrice),
                    Triple("缓存写入", record.cost.cacheCreation, record.cacheCreationPrice),
                    Triple("缓存读取", record.cost.cacheRead, record.cacheReadPrice)).forEach { (name, cost, price) ->
                    Text("$name：${formatMoney(cost, record.currency)}（${formatMoney(price, record.currency)} / 1M Tokens）",
                        style = MaterialTheme.typography.bodySmall)
                }
            } else Text("旧版记录保留原有总费用，未保存当时的分项价格。", style = MaterialTheme.typography.bodySmall)
            Text("模型 ${record.model}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun UsageSummary(label: String, usage: AiUsageSummary, detailed: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold)
        Text("${usage.calls} 次请求 · ${usage.totalTokens} Tokens")
        Text(if (usage.costsByCurrency.isEmpty()) "估算费用 0" else
            "估算费用 " + usage.costsByCurrency.entries.joinToString(" · ") { formatMoney(it.value, it.key) })
        if (detailed) {
            Text("输入 ${usage.promptTokens} · 输出 ${usage.completionTokens}", style = MaterialTheme.typography.bodySmall)
            Text("缓存写入 ${usage.cacheCreationTokens} · 缓存读取 ${usage.cacheReadTokens}", style = MaterialTheme.typography.bodySmall)
        }
        if (usage.unknownUsageCalls > 0) Text("${usage.unknownUsageCalls} 次请求用量不完整", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun AiUsageOverview(state: AppUiState, vm: AppViewModel) {
    var details by rememberSaveable { mutableStateOf(false) }
    var reset by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UsageSummary("今日使用", state.todayUsage)
        UsageSummary("累计使用", state.usage)
        TextButton(onClick = { details = true }, contentPadding = PaddingValues(0.dp)) { Text("查看用量与费用详情") }
    }
    if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("AI 使用统计") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                UsageSummary("今日", state.todayUsage, detailed = true)
                UsageSummary("全部", state.usage, detailed = true)
                Text("按每次请求时的模型价格计算；不同货币分别统计。", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text("最近 ${state.usageRecords.size} 次请求", fontWeight = FontWeight.SemiBold)
                state.usageRecords.forEach { record ->
                    Text(SimpleDateFormat("M-d HH:mm", Locale.CHINA).format(Date(record.createdAt)), style = MaterialTheme.typography.bodySmall)
                    AiCallUsage(record, "请求 #${record.id}")
                    HorizontalDivider()
                }
            }
        }, confirmButton = { TextButton(onClick = { details = false }) { Text("关闭") } },
        dismissButton = { TextButton(onClick = { reset = true }, enabled = !state.aiBusy) { Text("重置统计") } })
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text("重置 AI 使用统计？") },
        text = { Text("将清空本机 Token 和费用统计。学习记录、收藏、AI 回复历史和 API 设置会保留。") },
        confirmButton = { TextButton(onClick = { vm.resetAiUsage(); reset = false; details = false }) { Text("确认重置") } },
        dismissButton = { TextButton(onClick = { reset = false }) { Text("取消") } })
}
