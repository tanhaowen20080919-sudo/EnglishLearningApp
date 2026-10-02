package com.tanhaowen.contextenglish

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tanhaowen.contextenglish.data.AiSettings
import java.net.URI
import java.util.Locale

@Composable
fun AiSettingsScreen(state: AppUiState, vm: AppViewModel, close: () -> Unit) {
    val current = state.settings
    var provider by rememberSaveable(current.provider) { mutableStateOf(current.provider) }
    var baseUrl by rememberSaveable(current.baseUrl) { mutableStateOf(current.baseUrl) }
    // Sensitive data remains in memory and never enters saved instance state.
    var apiKey by remember(current.apiKey) { mutableStateOf(current.apiKey) }
    var keyVisible by remember { mutableStateOf(false) }
    var model by rememberSaveable(current.dailyModel) { mutableStateOf(current.dailyModel) }
    var deepModel by rememberSaveable(current.deepModel) { mutableStateOf(current.deepModel) }
    var currency by rememberSaveable(current.currency) { mutableStateOf(current.currency) }
    var style by rememberSaveable(current.apiStyle) { mutableStateOf(current.apiStyle) }
    var cacheMode by rememberSaveable(current.cacheInputMode) { mutableStateOf(current.cacheInputMode) }
    var input by rememberSaveable(current.inputPricePerMillion) { mutableStateOf(current.inputPricePerMillion.toString()) }
    var output by rememberSaveable(current.outputPricePerMillion) { mutableStateOf(current.outputPricePerMillion.toString()) }
    var write by rememberSaveable(current.cacheCreationPricePerMillion) { mutableStateOf(current.cacheCreationPricePerMillion.toString()) }
    var read by rememberSaveable(current.cacheReadPricePerMillion) { mutableStateOf(current.cacheReadPricePerMillion.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    fun parsed(): AiSettings? {
        val prices = listOf(input, output, write, read).map { if (it.isBlank()) 0.0 else it.toDoubleOrNull() }
        if (prices.any { it == null || !it.isFinite() || it < 0.0 }) { error = "价格必须为不小于 0 的有效数字，单位为每 1M Tokens"; return null }
        val uri = runCatching { URI(baseUrl.trim()) }.getOrNull()
        if (uri == null || uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null) {
            error = "请填写完整 HTTPS API 前缀，不要在地址中放入密钥"; return null
        }
        val code = currency.trim().uppercase(Locale.ROOT)
        if (!code.matches(Regex("[A-Z]{3}"))) { error = "货币请使用三个字母，例如 CNY 或 USD"; return null }
        error = null
        return current.copy(provider = provider.trim(), baseUrl = baseUrl.trim().trimEnd('/'), apiKey = apiKey.trim(),
            dailyModel = model.trim(), deepModel = deepModel.trim(), inputPricePerMillion = prices[0]!!,
            outputPricePerMillion = prices[1]!!, cacheCreationPricePerMillion = prices[2]!!,
            cacheReadPricePerMillion = prices[3]!!, currency = code, apiStyle = style, cacheInputMode = cacheMode)
    }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("API 接口", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("使用服务商提供的完整 API 前缀；兼容服务常见格式为 https://服务商地址/v1。", style = MaterialTheme.typography.bodySmall)
        }
        item { OutlinedTextField(value = provider, onValueChange = { provider = it }, label = { Text("服务商名称") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("API Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item { TaskPicker("接口类型", if (style == "responses") "Responses" else "Chat Completions", listOf("Chat Completions", "Responses")) { style = if (it == "Responses") "responses" else "chat" } }
        item {
            OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, label = { Text("API Key") },
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { keyVisible = !keyVisible }) { Text(if (keyVisible) "隐藏" else "显示") } },
                singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item { OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("使用的模型名称") },
            supportingText = { Text("填写当前服务商实际支持的模型，不预设模型名称。") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(value = deepModel, onValueChange = { deepModel = it }, label = { Text("备用模型（可留空）") },
            supportingText = { Text("保留旧版配置；当前生成使用上面的模型和价格。") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { parsed()?.let(vm::saveAndTest) }, enabled = !state.aiBusy && apiKey.isNotBlank() && model.isNotBlank(), modifier = Modifier.weight(1f)) { Text("保存并测试") }
                OutlinedButton(onClick = vm::loadModels, enabled = !state.aiBusy, modifier = Modifier.weight(1f)) { Text("读取已保存接口的模型") }
            }
            Text("测试会发送一条小请求，并记录用量；读取模型列表不生成内容。", style = MaterialTheme.typography.bodySmall)
            if (state.aiBusy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            state.aiError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.lastUsage?.let { AiCallUsage(it) }
        }
        if (state.availableModels.isNotEmpty()) item {
            Text("已读取 ${state.availableModels.size} 个模型，点击填入：", style = MaterialTheme.typography.bodySmall)
            state.availableModels.take(50).forEach { name -> TextButton(onClick = { model = name }, contentPadding = PaddingValues(0.dp)) { Text(name) } }
        }
        item { HorizontalDivider(); Text("模型价格", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        item { OutlinedTextField(value = currency, onValueChange = { currency = it }, label = { Text("价格货币（CNY / USD 等）") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
        item { Text("以下价格全部为每 1M Tokens。填 0 表示该项不收费；不自动换汇。", style = MaterialTheme.typography.bodySmall) }
        item { PriceField("普通输入", input) { input = it } }
        item { PriceField("输出", output) { output = it } }
        item { PriceField("缓存写入 / Cache Creation", write) { write = it } }
        item { PriceField("缓存读取 / Cache Read", read) { read = it } }
        item {
            val label = when (cacheMode) { "included" -> "输入已包含缓存"; "separate" -> "输入与缓存分开"; else -> "自动识别（推荐）" }
            TaskPicker("服务商用量规则", label, listOf("自动识别（推荐）", "输入已包含缓存", "输入与缓存分开")) {
                cacheMode = when (it) { "输入已包含缓存" -> "included"; "输入与缓存分开" -> "separate"; else -> "auto" }
            }
            Text("常见接口自动识别。若中转服务改变字段含义，可按其账单规则调整，避免重复计费。", style = MaterialTheme.typography.bodySmall)
        }
        item {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { parsed()?.let { vm.saveSettings(it); close() } }, enabled = !state.aiBusy, modifier = Modifier.fillMaxWidth()) { Text("保存设置") }
        }
        item { HorizontalDivider(); AiUsageOverview(state, vm) }
        item { Text("密钥使用 Android Keystore 加密保存在本机，普通备份不包含密钥。费用按服务商返回的用量和你填写的价格估算。", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun PriceField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text("$label（每 1M Tokens）") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
}
