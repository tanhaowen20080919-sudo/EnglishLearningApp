package com.tanhaowen.contextenglish.data

enum class WordState {
    NEW,
    LEARNING,
    MASTERED
}

data class VocabWord(
    val id: Long,
    val word: String,
    val phonetic: String,
    val meaning: String,
    val contextMeaning: String,
    val example: String,
    val state: WordState,
    val weak: Boolean,
    val seenCount: Int,
    val correctCount: Int,
    val partOfSpeech: String = "",
    val importance: Int = 2,
    val familiarity: Int = 0,
    val mistakeCount: Int = 0,
    val reviewCount: Int = 0,
    val lastReviewTime: Long = 0,
    val nextReviewTime: Long = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val streak: Int = 0,
    val favorite: Boolean = false,
    val exampleTranslation: String = ""
) {
    val due: Boolean get() = state != WordState.NEW && nextReviewTime <= System.currentTimeMillis()
    val statusLabel: String get() = when {
        weak -> "薄弱"
        due -> "待复习"
        state == WordState.NEW -> "未学习"
        state == WordState.MASTERED -> "已掌握"
        else -> "学习中"
    }
}

data class LearningStats(
    val total: Int = 0,
    val seen: Int = 0,
    val learning: Int = 0,
    val mastered: Int = 0,
    val weak: Int = 0,
    val todayAnswered: Int = 0,
    val todayCorrect: Int = 0
) {
    val todayAccuracy: Int
        get() = if (todayAnswered == 0) 0 else todayCorrect * 100 / todayAnswered
}

data class ReadingQuestion(
    val id: Int,
    val prompt: String,
    val options: List<String>,
    val answerIndex: Int,
    val explanation: String,
    val logicLabel: String
)

data class StudyReading(
    val title: String,
    val passage: String,
    val targetWords: List<String>,
    val questions: List<ReadingQuestion>
)

data class CachedReading(
    val id: Long,
    val title: String,
    val content: String,
    val model: String,
    val createdAt: Long,
    val usageId: Long? = null
)

data class AiUsageSummary(
    val calls: Long = 0,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val estimatedCost: Double = 0.0,
    val cacheCreationTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val reportedTotalTokens: Long = 0,
    val costsByCurrency: Map<String, Double> = emptyMap(),
    val unknownUsageCalls: Long = 0
) {
    val totalTokens: Long get() = reportedTotalTokens
}

data class AiSettings(
    val provider: String = "OpenAI Compatible",
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val dailyModel: String = "",
    val deepModel: String = "",
    val inputPricePerMillion: Double = 0.0,
    val outputPricePerMillion: Double = 0.0,
    val cacheCreationPricePerMillion: Double = 0.0,
    val cacheReadPricePerMillion: Double = 0.0,
    val currency: String = "CNY",
    val apiStyle: String = "chat",
    val cacheInputMode: String = "auto"
)

val builtInReading = StudyReading(
    title = "Small Steps, Real Progress",
    passage = "Li Ming used to avoid English articles because every unknown word made him nervous. " +
        "His teacher suggested a different approach: understand the main idea first, then notice a few important words in context. " +
        "At first, the change seemed minor, but it had a significant effect. He began to recognize repeated patterns and infer meanings without checking every word. " +
        "After each short reading, he reviewed the words that caused difficulty and used them in simple sentences. " +
        "This routine was practical, so he could maintain it even during a busy school week. " +
        "Several months later, English reading was no longer a task he feared. His progress did not happen suddenly; it resulted from a steady process that he was willing to continue.",
    targetWords = listOf(
        "avoid", "suggest", "approach", "context", "significant", "infer",
        "review", "practical", "maintain", "result"
    ),
    questions = listOf(
        ReadingQuestion(
            id = 1,
            prompt = "What change helped Li Ming most?",
            options = listOf(
                "Translating every sentence",
                "Understanding the main idea before focusing on key words",
                "Reading only very easy stories",
                "Memorizing a dictionary"
            ),
            answerIndex = 1,
            explanation = "第二句说明老师建议先把握主旨，再关注语境中的重点词。",
            logicLabel = "细节定位"
        ),
        ReadingQuestion(
            id = 2,
            prompt = "What does “infer” most likely mean in the passage?",
            options = listOf("guess from evidence", "write down", "forget", "pronounce clearly"),
            answerIndex = 0,
            explanation = "根据“不查每个单词也能理解意思”，infer 表示依据上下文推断。",
            logicLabel = "语境猜词"
        ),
        ReadingQuestion(
            id = 3,
            prompt = "What is the main message of the passage?",
            options = listOf(
                "Natural talent is the key to English",
                "Long articles are always more useful",
                "Steady and practical habits can create real progress",
                "Students should avoid unknown words"
            ),
            answerIndex = 2,
            explanation = "末句与全文都强调：进步来自可以长期坚持的小步骤。",
            logicLabel = "主旨概括"
        )
    )
)


data class StudySettings(val newWords: Int = 20, val dailyGoal: Int = 30,
    val showPhonetic: Boolean = true, val autoSpeak: Boolean = false,
    val sound: Boolean = true, val haptics: Boolean = true, val autoAdvance: Boolean = true,
    val autoAi: Boolean = true, val autoAiBudget: Double = 0.10, val speechRate: Float = 0.9f)
data class DailyPlan(val ids: List<Long> = emptyList(), val completed: Set<Long> = emptySet(),
    val newCount: Int = 0, val reviewCount: Int = 0, val weakCount: Int = 0)
data class AiQuizQuestion(val wordId: Long, val prompt: String, val options: List<String>,
    val answer: Int, val explanation: String)
