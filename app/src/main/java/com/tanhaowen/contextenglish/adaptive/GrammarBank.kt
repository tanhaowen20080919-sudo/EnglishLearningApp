package com.tanhaowen.contextenglish.adaptive

/** Original diagnostic items. Every explanation identifies the rule applied to this sentence. */
object GrammarBank {
    private data class Item(val prompt: String,val choices: String,val answer: Int,val why: String)
    private fun item(prompt: String,choices: String,answer: Int,why: String)=Item(prompt,choices,answer,why)
    private fun lesson(tag: String,card: String,example: String,vararg rows: Item)=GrammarLesson(tag,card,example,
        rows.mapIndexed { i,r -> LocalQuestion("$tag:$i",r.prompt,r.choices.split('|'),r.answer,r.why,"grammar",tag) })
    val lessons=listOf(
        lesson("定语从句","先找先行词，再看从句缺少的成分；人作主语可用 who，物可用 that/which。",
            "The student who helped me is kind. → who 指学生，在从句中作主语。",
            item("The girl ____ won the race is my friend.","who|which|where|when",0,"先行词 girl 指人，空格在从句中作 won 的主语，所以选 who；where/when 不能充当主语。"),
            item("This is the book ____ changed my mind.","where|that|who|when",1,"先行词 book 指物，从句缺 changed 的主语，that 可指物；who 只指人。"),
            item("I know a teacher ____ speaks French.","which|when|who|where",2,"teacher 指人，从句缺主语，who 代替 teacher 作 speaks 的主语。"),
            item("The bike ____ is outside belongs to Tom.","who|where|when|which",3,"bike 是物，空格是 is 的主语，选 which；where 是地点状语，不能代替主语。")),
        lesson("时态","时间状语与上下文共同决定时态；since 常表示从过去持续到现在。",
            "She has lived here since 2020. → 从 2020 年一直住到现在。",
            item("He ____ to school yesterday.","goes|went|has gone|going",1,"yesterday 是明确结束的过去时间，需一般过去时 went，不能用现在完成时。"),
            item("She ____ here since 2020.","lives|lived|has lived|living",2,"since 2020 表示从过去持续到现在，用 has lived。"),
            item("Look! The children ____ in the park.","played|play|have played|are playing",3,"Look 提示正在发生的动作，主语 children 是复数，用 are playing。"),
            item("Water ____ at 100°C at standard pressure.","boils|boiled|is boiling|has boiled",0,"在标准气压下水的沸点是客观规律，用一般现在时；water 为不可数名词，用 boils。")),
        lesson("非谓语","先找句中谓语，再检查搭配；介词和 enjoy/avoid 后通常接 doing，want 后接 to do。",
            "She enjoys reading. / He wants to learn. → 动词后的形式受搭配限制。",
            item("She enjoys ____ English stories.","read|to read|reading|reads",2,"enjoy 后接动名词作宾语，选 reading，不能接 to read。"),
            item("He wants ____ a doctor.","become|becoming|became|to become",3,"want to do 是固定搭配，因此选不定式 to become。"),
            item("Avoid ____ the same mistake.","making|make|to make|made",0,"avoid 后接 doing 作宾语，故用 making。"),
            item("Thank you for ____ me.","help|helping|to help|helped",1,"for 是介词，后接动名词 helping，而不是动词原形或不定式。")),
        lesson("状语从句","根据因果、让步、时间和条件关系选择连词；将来时间/条件从句常用一般现在时。",
            "If it rains tomorrow, we will stay home. → 条件从句用现在时表将来。",
            item("We stayed indoors ____ it was raining heavily.","although|unless|because|before",2,"待在室内的原因是下大雨，because 引导原因状语从句；although 表让步，不符合句意。"),
            item("____ she was tired, she finished the work.","Although|Because|Until|Unless",0,"尽管累仍完成工作，前后是让步关系，选 Although。"),
            item("If it ____ tomorrow, we will cancel the picnic.","will rain|rained|rains|has rained",2,"if 引导真实条件从句，主句为将来时，从句用一般现在时 rains。"),
            item("I will call you as soon as I ____ home.","will get|get|got|am getting",1,"as soon as 引导将来时间从句，用一般现在时 get，而不用 will get。")),
        lesson("名词性从句","判断空格在从句中作什么；what 可充当主语/宾语，that 只连接而不作成分。",
            "What he said is useful. → what 在主语从句中作 said 的宾语。",
            item("____ he said surprised us.","That|What|Whether|If",1,"said 缺宾语，what 同时引导主语从句并充当宾语；that 不作成分。"),
            item("I don't know ____ she will come or not.","that|what|whether|which",2,"与 or not 连用表示是否，选 whether；that 不能表达疑问。"),
            item("The fact ____ he arrived early made us happy.","what|which|whether|that",3,"空格后的完整句解释 fact 的内容，是同位语从句；that 仅起连接作用。"),
            item("He asked me ____ I lived.","where|that|which|what",0,"从句主谓完整，缺地点状语，所以用 where 表示住在哪里。")),
        lesson("主谓一致","先辨认真正主语；插入语不改变主语单复数，either/neither 结构需结合就近原则。",
            "The teacher, with two students, is here. → 主语是 teacher。",
            item("The teacher, together with two students, ____ here.","are|were|is|be",2,"主语是单数 teacher，together with 后的 students 不改变主语，因此选 is。"),
            item("Each of the books ____ useful.","are|is|have been|be",1,"each 作主语中心词时按单数处理，所以用 is。"),
            item("Neither Tom nor his parents ____ at home now.","is|was|be|are",3,"neither...nor 遵循就近原则，靠近谓语的 parents 是复数，时间为 now，用 are。"),
            item("Ten kilometres ____ a long walk for a child.","is|are|have|were",0,"距离作为一个整体看待，作单数主语，因此用 is。")),
        lesson("被动语态","主语承受动作时使用 be + 过去分词，并让 be 体现时态和主谓一致。",
            "The bridge was built in 2020. → 桥是被建造的。",
            item("The bridge ____ in 2020.","built|was built|is building|has built",1,"bridge 是 build 的承受者，in 2020 为过去时间，需一般过去时被动 was built。"),
            item("English ____ in many countries.","speaks|is speaking|is spoken|has spoken",2,"English 是被人们使用的语言，描述一般事实，用一般现在时被动 is spoken。"),
            item("The work must ____ today.","finish|finished|be finishing|be finished",3,"情态动词后被动结构是 must be + 过去分词，所以选 be finished。"),
            item("These trees ____ every spring.","are planted|plant|planted|have planting",0,"trees 承受种植动作，every spring 表习惯，复数主语配 are planted。")),
        lesson("虚拟语气","与现在事实相反的 if 从句用过去式，主句用 would + 原形；与过去相反用过去完成时。",
            "If I were you, I would ask for help. → 与现在事实相反的假设。",
            item("If I ____ you, I would accept the advice.","am|will be|were|have been",2,"与现在事实相反，be 在虚拟条件从句中通常用 were。"),
            item("If she had left earlier, she ____ the train.","will catch|would have caught|catches|would catch",1,"if 从句为 had left，表示与过去相反，主句对应 would have caught。"),
            item("I wish I ____ more time now.","have|will have|have had|had",3,"wish 后对现在不真实的愿望用一般过去时，因此是 had。"),
            item("Without your help, I ____ failed yesterday.","would have|will have|would|will",0,"yesterday 指过去，without 引出与过去事实相反的条件，主句用 would have failed。"))
    )
}
