package com.tanhaowen.contextenglish.data

data class AutoAiJob(val id: Long, val scope: String, val wordIds: Set<Long>, val reason: String,
    val status: String, val model: String, val currency: String, val reservedCost: Double,
    val actualCost: Double?, val usageKnown: Boolean, val result: String, val error: String,
    val createdAt: Long, val finishedAt: Long, val usageId: Long? = null)

/** Conservative local estimate, not a provider-enforced billing cap. */
object AutoAiPolicy {
    const val MAX_DAILY_CALLS = 5
    const val MAX_OUTPUT_TOKENS = 900
    fun scope(settings: AiSettings): String = "coach-v1|${settings.baseUrl.trim()}|${settings.dailyModel}|${settings.apiStyle}"
    fun reserve(settings: AiSettings, prompt: String): Double {
        val inputPrice=maxOf(settings.inputPricePerMillion,settings.cacheCreationPricePerMillion,settings.cacheReadPricePerMillion)
        // UTF-8 bytes plus generous system/wrapper overhead upper-bounds normal input token counts.
        return ((prompt.toByteArray(Charsets.UTF_8).size + 2048) * inputPrice +
            MAX_OUTPUT_TOKENS * settings.outputPricePerMillion) / 1_000_000.0
    }
    fun block(settings: AiSettings, study: StudySettings, today: List<AutoAiJob>, reserve: Double): String? = when {
        !study.autoAi -> "自动AI已关闭"
        settings.apiKey.isBlank() || settings.dailyModel.isBlank() -> "配置API后启用自动AI；本地学习不受影响"
        !settings.baseUrl.startsWith("https://") -> "请填写HTTPS接口地址"
        settings.inputPricePerMillion <= 0 || settings.outputPricePerMillion <= 0 -> "填写输入/输出单价后启用自动AI"
        !study.autoAiBudget.isFinite() || study.autoAiBudget <= 0 -> "自动AI预算为0，仅使用本地内容"
        today.size >= MAX_DAILY_CALLS -> "今日自动调用次数已达上限"
        today.any { !it.usageKnown && it.status != "QUEUED" } -> "有调用的扣费/用量不明，今日自动AI已暂停"
        !reserve.isFinite() || today.filter { it.currency==settings.currency }.sumOf { it.actualCost ?: it.reservedCost } + reserve > study.autoAiBudget -> "今日自动AI估算预算不足"
        else -> null
    }
}
