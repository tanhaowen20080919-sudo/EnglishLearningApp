package com.tanhaowen.contextenglish.adaptive

/** Small reviewed supplement; original IPA, definitions and attributed examples remain untouched. */
data class WordNote(val collocation: String, val forms: String, val alternate: String, val translation: String)
object WordNotes {
    val entries=mapOf(
        "maintain" to WordNote("maintain a balance / maintain good health","maintained · maintaining","We must maintain a balance between study and rest.","我们必须保持学习与休息的平衡。"),
        "avoid" to WordNote("avoid doing sth / avoid mistakes","avoided · avoiding","Try to avoid making the same mistake again.","尽量避免再次犯同样的错误。"),
        "suggest" to WordNote("suggest doing sth / suggest that…","suggested · suggestion (n.)","The results suggest that the plan is working.","结果表明这个计划正在起作用。"),
        "approach" to WordNote("an approach to doing sth / approach a problem","approached · approaches","We need a different approach to this problem.","我们需要一种不同的方法来解决这个问题。"),
        "context" to WordNote("in context / in the context of","contexts","Read the whole sentence to understand the word in context.","读完整句来理解单词的语境义。"),
        "significant" to WordNote("a significant difference / significant progress","significance (n.) · significantly (adv.)","The new bridge made a significant difference to village life.","新桥显著改变了村里的生活。"),
        "infer" to WordNote("infer…from…","inferred · inferring","What can you infer from the final paragraph?","你能从最后一段推断出什么？"),
        "review" to WordNote("review a lesson / a book review","reviewed · reviewing","The editor asked her to review a new book.","编辑请她评论一本新书。"),
        "practical" to WordNote("practical advice / practical skills","practically (adv.)","The course teaches practical skills for everyday life.","这门课程教授日常生活的实用技能。"),
        "result" to WordNote("result in 导致 / result from 源于","results · resulted","Heavy rain may result in delays.","大雨可能导致延误。"),
        "recognize" to WordNote("recognize…as… / recognize a voice","recognized · recognition (n.)","I could recognize her voice even in the dark.","即使在黑暗中我也能认出她的声音。"),
        "evidence" to WordNote("evidence of / evidence that…","不可数名词：a piece of evidence","There is no evidence that he was there.","没有证据表明他当时在那里。"),
        "affect" to WordNote("affect the environment / affect health","affected · affecting；effect 常作名词","Lack of sleep can affect your memory.","缺乏睡眠会影响记忆。"),
        "benefit" to WordNote("benefit from / be of benefit to","benefited · beneficial (adj.)","Both schools will benefit from the shared library.","两所学校都会从共享图书馆中受益。"),
        "focus" to WordNote("focus on / the focus of","focused · focusing","Please focus on the evidence in the passage.","请关注文章中的证据。"),
        "require" to WordNote("require sb to do / require doing","required · requirement (n.)","These plants require very little water.","这些植物需要的水很少。"),
        "provide" to WordNote("provide sb with sth / provide sth for sb","provided · providing","The centre will provide free meals for children.","中心将为孩子们提供免费餐食。"),
        "available" to WordNote("be available to / be available for","availability (n.)","Tickets are available online from Monday.","门票从周一起可在线购买。"),
        "improve" to WordNote("improve skills / improve on","improved · improvement (n.)","Regular exercise can improve your mood.","经常锻炼可以改善心情。"),
        "support" to WordNote("support a conclusion / in support of","supported · supporting","The figures support the writer's conclusion.","这些数字支持作者的结论。")
    )
}
