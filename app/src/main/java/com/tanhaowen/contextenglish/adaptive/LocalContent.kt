package com.tanhaowen.contextenglish.adaptive

import com.tanhaowen.contextenglish.data.VocabWord
import com.tanhaowen.contextenglish.study.LocalCloze
import com.tanhaowen.contextenglish.study.StudyEngine
import kotlin.random.Random

data class LocalQuestion(val id: String, val prompt: String, val options: List<String>, val answer: Int,
    val explanation: String, val form: String, val tag: String = "", val wordId: Long = 0,
    val spelling: String = "", val context: String = "") {
    fun correct(value: String) = if(spelling.isNotBlank()) value.trim().equals(spelling,true) else value.toIntOrNull()==answer
    val answerText get() = if(spelling.isNotBlank()) spelling else options[answer]
}
data class ReadingLesson(val id: String, val title: String, val passage: String, val glossary: Map<String,String>, val questions: List<LocalQuestion>)
data class GrammarLesson(val tag: String, val card: String, val example: String, val questions: List<LocalQuestion>)

object LocalContent {
    private val meaningAtoms=java.util.concurrent.ConcurrentHashMap<String,Set<String>>()
    private val stripMeaning=Regex("\\[[^]]*]|[A-Za-z.()（）]")
    private val splitMeaning=Regex("[；;，,、\\s]+")
    private fun atoms(meaning: String): Set<String> = meaningAtoms.getOrPut(meaning) {
        meaning.replace(stripMeaning,"").split(splitMeaning).filter { it.length>=2 }.toSet()
    }
    private fun rq(id: String, prompt: String, options: List<String>, answer: Int, why: String, tag: String) =
        LocalQuestion(id,prompt,options,answer,why,"reading",tag,context=id)
    private val introductoryReadings = listOf(
        ReadingLesson("garden","A Garden That Brings People Together",
            "When a small library lost its gardener, the land behind it became dry and empty. Maya, a student, suggested turning it into a community garden. The library had little money, so she invited neighbours to bring seeds and share tools. At first, only three people came. Instead of giving up, they put a box of fresh vegetables near the entrance with a sign: ‘Take what you need; help when you can.’ Within a month, more than twenty families were taking part.\n\nAn older neighbour, Mr Lee, taught children how to save water. In return, they helped him carry heavy bags. ‘I came for the tomatoes,’ he said, ‘but I stay for the conversations.’ The library now holds a weekly meeting in the garden. Maya believes its greatest benefit is not the food it produces. It gives people a reason to meet and learn from one another.",
            mapOf("community" to "社区", "seeds" to "种子", "entrance" to "入口", "benefit" to "益处", "suggested" to "建议"),
            listOf(
                rq("garden:1","What did Maya first suggest?",listOf("Selling the library","Creating a shared garden","Buying vegetables","Hiring more teachers"),1,"首段第三句明确建议把土地变成社区花园。","细节题"),
                rq("garden:2","Why does Mr Lee continue to come?",listOf("He enjoys talking with people","He is paid to work","He needs heavy bags","He wants to leave the city"),0,"由 stay for the conversations 可推断人际交流吸引他。","推断题"),
                rq("garden:3","What is the main idea?",listOf("Libraries should sell food","Gardening always saves money","A shared project can connect people","Students need more exams"),2,"首段写共同参与，末段总结相遇与互相学习。","主旨题"),
                rq("garden:4","What does ‘In return’ mean?",listOf("As a way of giving something back","Before arriving","Without permission","For no reason"),0,"老人教孩子节水，孩子作为回报帮他搬重物。","词义猜测题"),
                rq("garden:5","What does ‘It’ in the last sentence refer to?",listOf("The food","The garden","The sign","The money"),1,"前文讨论花园最大的益处，It 指这个花园。","指代题"))),
        ReadingLesson("repair","A Second Life for Old Things",
            "Every Saturday, volunteers in a town hall help people repair broken objects. The service is free, but visitors must stay and work alongside a volunteer. When Sam brought in a lamp, he expected someone to fix it for him. Instead, a volunteer asked him to describe what had happened. Together they found a loose wire. Ten minutes later, the lamp worked again.\n\nSam had never repaired anything before. He used to replace an object as soon as it stopped working. Now he understood that a small fault did not always mean the end of its useful life. The project has also changed how volunteers see their skills. ‘What seems simple to me may be valuable to someone else,’ says one of them. The organisers record how many objects are saved from being thrown away, but they consider the confidence visitors gain just as important.",
            mapOf("volunteers" to "志愿者", "replace" to "替换", "fault" to "故障", "confidence" to "信心", "loose" to "松动的"),
            listOf(
                rq("repair:1","What must visitors do?",listOf("Pay before entering","Bring new tools","Work with a volunteer","Leave the object overnight"),2,"第一段指出 visitors must stay and work alongside a volunteer。","细节题"),
                rq("repair:2","What can we infer about Sam?",listOf("He may try repairing things again","He dislikes the service","He is an expert electrician","He sold his lamp"),0,"他第一次成功修理并改变了有故障就换新的认识。","推断题"),
                rq("repair:3","Which title best fits the passage?",listOf("The Cost of New Lamps","Repairing Objects, Building Confidence","A Competition for Experts","Why Objects Always Break"),1,"文章兼顾延长物品寿命和建立人的信心。","主旨题"),
                rq("repair:4","What does ‘valuable’ mean here?",listOf("Difficult to carry","Helpful and useful","Very old","Easy to forget"),1,"志愿者认为自己的技能能帮助别人，因此有价值。","词义猜测题"),
                rq("repair:5","What does ‘they’ in the last sentence refer to?",listOf("Visitors","Objects","The organisers","Lamps"),2,"主句主语 The organisers 与 but 后的 they 一致。","指代题"))),
        ReadingLesson("birds","Listening Before School",
            "A science teacher asked her class to stand quietly outside for five minutes each morning. Their task was to listen to birds and record what they heard. Some students thought the activity would be boring. They soon discovered, however, that the sounds changed with the weather and the season.\n\nOne student, Ana, noticed that birds were harder to hear beside a busy road. She wondered whether traffic affected their behaviour. With her classmates, she compared recordings from two locations at the same time of day. They could not prove that traffic was the only cause, but their notes raised useful questions. The teacher praised them for recognising the limits of their evidence. ‘Science begins with careful observation,’ she said. The class later shared its recordings with a local nature group. What began as a short daily task had become a way to notice changes in the environment.",
            mapOf("record" to "记录", "behaviour" to "行为", "evidence" to "证据", "observation" to "观察", "environment" to "环境"),
            listOf(
                rq("birds:1","How long did students listen each morning?",listOf("Two minutes","Five minutes","Half an hour","A full lesson"),1,"第一句给出 five minutes。","细节题"),
                rq("birds:2","Why did the teacher praise the students?",listOf("They proved every cause","They made louder recordings","They understood what their evidence could not prove","They avoided questions"),2,"老师赞扬他们认识到证据的局限，不夸大结论。","推断题"),
                rq("birds:3","What is the passage mainly about?",listOf("A listening task encouraged scientific observation","Traffic never affects birds","All students prefer outdoor lessons","Recordings replace teachers"),0,"全文讲从每日聆听到提出问题、比较证据与关注环境。","主旨题"),
                rq("birds:4","What does ‘limits’ mean in the passage?",listOf("The loudness of sounds","The boundaries of what can be concluded","The number of students","The price of equipment"),1,"上下句表明记录无法证明交通是唯一原因，即证据支持结论的范围有限。","词义猜测题"),
                rq("birds:5","What does ‘their’ in ‘their notes’ refer to?",listOf("The birds'","The students'","The drivers'","The teachers'"),1,"Ana 与同学共同记录，所以 their 指学生们。","指代题")))
    )

    val readings = introductoryReadings + ReadingBank.extended
    val grammars = GrammarBank.lessons

    fun contexts(w: VocabWord): List<String> {
        val note=WordNotes.entries[w.word.lowercase()]
        val alternate=note?.let {
            it.alternate.replace(Regex("(?i)(?<![a-z])${Regex.escape(w.word)}(?![a-z])"),"____")+"\n"+it.translation
        }
        val token=Regex("(?i)(?<![a-z])${Regex.escape(w.word)}(?![a-z])")
        val corpus=OfflineWordResources.get(w.word)?.examples.orEmpty().filter { token.findAll(it.text).count()==1 }.map {
            token.replace(it.text,"____")+"\n"+it.translation.ifBlank { "词义提示："+StudyEngine.meaning(w) }
        }
        val reviewed=WordStudyResources.note(w)?.takeIf { token.findAll(it.example).count()==1 }?.let {
            token.replace(it.example,"____")+"\n"+it.translation
        }
        return (listOfNotNull(LocalCloze.prompt(w),alternate,reviewed)+corpus).distinctBy { normalizeContext(it,w.word) }
    }
    private fun normalizeContext(cloze: String,word: String)=cloze.substringBefore('\n').replace("____",word).lowercase().replace(Regex("\\s+")," ").trim()
    fun vocabulary(w: VocabWord, pool: List<VocabWord>, form: String, key: String): LocalQuestion {
        val random = Random(key.hashCode())
        val contexts=contexts(w)
        if(form=="sense" && w.word.lowercase() in ContextSenseBank.meanings) {
            val word=w.word.lowercase();val note=WordNotes.entries.getValue(word);val meaning=ContextSenseBank.meanings.getValue(word)
            val options=(ContextSenseBank.contrasts[word] ?: (ContextSenseBank.meanings.values.filter { it!=meaning }.shuffled(random).take(3)+meaning)).shuffled(random)
            val canonical=contexts.first { normalizeContext(it,w.word)==note.alternate.lowercase().replace(Regex("\\s+")," ").trim() }
            return LocalQuestion(key,"${note.alternate}\n句中 ${w.word} 的意思是？",options,options.indexOf(meaning),
                "${note.alternate}\n${note.translation}\n此处 ${w.word} 表示：$meaning。","context",wordId=w.id,context="example:${w.id}:${canonical.hashCode()}")
        }
        val cloze = contexts.takeIf { it.isNotEmpty() }?.let { it[random.nextInt(it.size)] }
        val requested=if(form=="sense") "context" else form
        val actual = if(requested=="context" && cloze==null) "meaning" else requested
        val reviewed=WordStudyResources.note(w)
        val wordExplanation=reviewed?.let { "${w.word}：${it.meaning}\n${it.example}\n${it.translation}" }
            ?: "${w.word}：${w.meaning}\n${w.example}\n${w.exampleTranslation}"
        if(actual=="spelling") return LocalQuestion(key,StudyEngine.meaning(w),emptyList(),-1,
            wordExplanation,actual,wordId=w.id,spelling=w.word,context=key)
        val answer = if(actual=="context") w.word else StudyEngine.meaning(w)
        val targetAtoms=atoms(w.meaning)+atoms(StudyEngine.meaning(w))
        val choices = pool.asSequence().filter { it.id!=w.id && it.meaning!=w.meaning && (atoms(it.meaning)+atoms(StudyEngine.meaning(it))).intersect(targetAtoms).isEmpty() }
            .map { if(actual=="context") it.word else StudyEngine.meaning(it) }
            .filter { it.isNotBlank() && it!=answer }.distinct().toList().shuffled(random).take(3)
        require(choices.size==3) { "词库不足，无法组成可靠题目" }
        val options=(choices+answer).shuffled(random)
        return LocalQuestion(key,if(actual=="context") cloze!! else w.word,options,options.indexOf(answer),
            if(actual=="context") "当前句（填词后）：${cloze!!.replace("____",w.word)}\n常用义参考：${StudyEngine.meaning(w)}" else wordExplanation,actual,wordId=w.id,context=if(actual=="context") "example:${w.id}:${cloze.hashCode()}" else key)
    }
}
