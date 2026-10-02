package com.tanhaowen.contextenglish.data

data class TokenUsage(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cacheCreationTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val totalTokens: Long = 0,
    val known: Boolean = false
) {
    val normalInputTokens: Long
        get() = (promptTokens - cacheCreationTokens - cacheReadTokens).coerceAtLeast(0)
}

data class AiCost(
    val input: Double = 0.0,
    val output: Double = 0.0,
    val cacheCreation: Double = 0.0,
    val cacheRead: Double = 0.0
) {
    val total: Double get() = input + output + cacheCreation + cacheRead
}

object AiCostCalculator {
    fun calculate(usage: TokenUsage, settings: AiSettings): AiCost {
        fun cost(tokens: Long, price: Double): Double =
            tokens.coerceAtLeast(0) / 1_000_000.0 * price.takeIf { it.isFinite() && it >= 0.0 }.orZero()
        return AiCost(
            input = cost(usage.normalInputTokens, settings.inputPricePerMillion),
            output = cost(usage.completionTokens, settings.outputPricePerMillion),
            cacheCreation = cost(usage.cacheCreationTokens, settings.cacheCreationPricePerMillion),
            cacheRead = cost(usage.cacheReadTokens, settings.cacheReadPricePerMillion)
        )
    }
    private fun Double?.orZero() = this ?: 0.0
}

data class AiUsageRecord(
    val id: Long = 0,
    val model: String,
    val usage: TokenUsage,
    val cost: AiCost,
    val totalCost: Double = cost.total,
    val currency: String,
    val createdAt: Long = System.currentTimeMillis(),
    val inputPrice: Double = 0.0,
    val outputPrice: Double = 0.0,
    val cacheCreationPrice: Double = 0.0,
    val cacheReadPrice: Double = 0.0,
    val hasPriceSnapshot: Boolean = true
)

data class RestoredBackup(val study: StudySettings, val ai: AiSettings? = null)
