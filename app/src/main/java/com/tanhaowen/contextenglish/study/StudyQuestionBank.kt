package com.tanhaowen.contextenglish.study

import com.tanhaowen.contextenglish.data.VocabWord
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** Immutable vocabulary index. Build/prepare on Default; UI reads only prepared questions. */
class StudyQuestionBank(words: List<VocabWord>) {
    private data class Entry(val word: VocabWord, val meaning: String, val atoms: Set<String>)
    private data class Key(val session: String, val task: StudyTask)
    private val strip = Regex("[A-Za-z.()（）]")
    private val split = Regex("[；;，,、\\s]+")
    private val entries = words.map { w ->
        val meaning = StudyEngine.meaning(w)
        Entry(w, meaning, meaning.replace(strip, "").split(split).filter { it.length >= 2 }.toSet())
    }
    private val byId = entries.associateBy { it.word.id }
    private val cache = ConcurrentHashMap<Key, StudyQuestion>()

    fun cached(session: StudySession): StudyQuestion? = session.task?.let { cache[Key(session.id,it)] }

    fun prepare(session: StudySession): StudyQuestion? {
        val task = session.task ?: return null
        val key = Key(session.id,task)
        cache[key]?.let { return it }
        val entry = byId[task.wordId] ?: return null
        val word = entry.word
        val question = if(task.kind == TaskKind.SPELLING || task.kind == TaskKind.LEARN)
            StudyQuestion(word,task,emptyList(),-1)
        else {
            val answer = if(task.kind == TaskKind.CONTEXT) word.word else entry.meaning
            val random = Random((session.id+task.key).hashCode())
            val candidates = entries.filter { other -> other.word.id != word.id && other.meaning.isNotBlank() &&
                other.atoms.none { it in entry.atoms } }.shuffled(random)
                .sortedBy { if(it.word.partOfSpeech == word.partOfSpeech) 0 else 1 }
                .map { if(task.kind == TaskKind.CONTEXT) it.word.word else it.meaning }
                .filter { it != answer }.distinct().take(3)
            if(candidates.size < 3) return null
            val options = (candidates+answer).shuffled(random)
            StudyQuestion(word,task,options,options.indexOf(answer))
        }
        if(cache.size >= 512) cache.clear()
        return cache.putIfAbsent(key,question) ?: question
    }
}
