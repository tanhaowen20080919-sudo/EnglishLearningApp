package com.tanhaowen.contextenglish.adaptive

/** Reviewed meanings for the existing original WordNotes sentences; never inferred from a whole dictionary entry. */
object ContextSenseBank {
    val meanings=mapOf("maintain" to "保持","avoid" to "避免","suggest" to "表明","approach" to "方法",
        "context" to "语境","significant" to "显著的","infer" to "推断","review" to "评论",
        "practical" to "实用的","result" to "导致","recognize" to "认出","evidence" to "证据",
        "affect" to "影响","benefit" to "受益","focus" to "关注","require" to "需要",
        "provide" to "提供","available" to "可购得","improve" to "改善","support" to "支持")
    val contrasts=mapOf("review" to listOf("复习","评论","回顾","重新检查"),
        "suggest" to listOf("建议","表明","使人想起","推荐"),"approach" to listOf("接近","方法","请求","来临"),
        "result" to listOf("导致","结果","原因","经过"),"available" to listOf("可购得","有空的","有效的","可接受的"))
}
