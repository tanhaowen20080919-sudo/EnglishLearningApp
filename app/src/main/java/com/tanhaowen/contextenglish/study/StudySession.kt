package com.tanhaowen.contextenglish.study

import com.tanhaowen.contextenglish.data.VocabWord
import com.tanhaowen.contextenglish.data.WordState
import java.util.UUID
import kotlin.random.Random

enum class TaskKind { MEANING, SPELLING, CONTEXT }
data class StudyTask(val key: String, val wordId: Long, val kind: TaskKind = TaskKind.MEANING,
    val repeat: Int = 0, val prompt: String = "")
data class StudyFeedback(val selected: Int = -1, val typed: String = "", val correct: Boolean,
    val credited: Boolean, val answer: String)
data class StudySession(val id: String, val day: String, val mode: String,
    val tasks: List<StudyTask>, val index: Int = 0, val feedback: StudyFeedback? = null,
    val hinted: Boolean = false, val answered: Int = 0, val correct: Int = 0,
    val combo: Int = 0, val bestCombo: Int = 0, val wrongIds: Set<Long> = emptySet(),
    val newIds: Set<Long> = emptySet(), val originalIds: Set<Long> = emptySet(),
    val coachIds: Set<Long> = emptySet()) {
    val task: StudyTask? get() = tasks.getOrNull(index)
    val finished: Boolean get() = index >= tasks.size
    val accuracy: Int get() = if (answered == 0) 0 else correct * 100 / answered
}
data class StudyQuestion(val word: VocabWord, val task: StudyTask, val options: List<String>, val answer: Int)
data class CoachNote(val wordId: Long, val tip: String, val example: String, val translation: String,
    val cloze: String)

/** Local-only, deterministic questions; no API request is needed to grade or move forward. */
object StudyEngine {
    fun create(words: List<VocabWord>, mode: String, day: String): StudySession {
        val id = UUID.randomUUID().toString()
        val original = words.distinctBy { it.id }
        val fresh=original.filter { it.state==WordState.NEW }.toMutableList()
        val review=original.filter { it.state!=WordState.NEW }.toMutableList()
        val distinct=buildList {
            while(fresh.isNotEmpty() || review.isNotEmpty()) {
                repeat(2) { if(review.isNotEmpty()) add(review.removeAt(0)) }
                if(fresh.isNotEmpty()) add(fresh.removeAt(0))
            }
        }
        return StudySession(id, day, mode, distinct.mapIndexed { i,w -> StudyTask("$id:$i", w.id) },
            newIds = distinct.filter { it.state == WordState.NEW }.map { it.id }.toSet(),
            originalIds = distinct.map { it.id }.toSet())
    }

    fun meaning(word: VocabWord): String = word.meaning.lineSequence().firstOrNull { it.isNotBlank() }
        ?.trim().orEmpty().ifBlank { word.contextMeaning.trim() }

    private fun atoms(text: String) = text.replace(Regex("[A-Za-z.()（）]"), "")
        .split(Regex("[；;，,、\\s]+" )).filter { it.length >= 2 }.toSet()

    fun question(session: StudySession, words: List<VocabWord>): StudyQuestion? {
        val task = session.task ?: return null
        val word = words.find { it.id == task.wordId } ?: return null
        if (task.kind == TaskKind.SPELLING) return StudyQuestion(word, task, emptyList(), -1)
        val answerText = if (task.kind == TaskKind.CONTEXT) word.word else meaning(word)
        val meanings = atoms(meaning(word))
        val random = Random((session.id + task.key).hashCode())
        val candidates = words.filter { other ->
            other.id != word.id && meaning(other).isNotBlank() &&
                atoms(meaning(other)).intersect(meanings).isEmpty()
        }.shuffled(random).sortedBy { if (it.partOfSpeech == word.partOfSpeech) 0 else 1 }
            .map { if (task.kind == TaskKind.CONTEXT) it.word else meaning(it) }
            .filter { it != answerText }.distinct().take(3)
        if (candidates.size < 3) return null // Never manufacture an ambiguous or incomplete question.
        val options = (candidates + answerText).shuffled(random)
        return StudyQuestion(word, task, options, options.indexOf(answerText))
    }

    fun answer(session: StudySession, question: StudyQuestion, selected: Int = -1,
        typed: String = "", unknown: Boolean = false): StudySession {
        if (session.feedback != null || session.task?.key != question.task.key) return session
        val right = !unknown && if (question.task.kind == TaskKind.SPELLING)
            typed.trim().equals(question.word.word.trim(), ignoreCase = true)
        else selected == question.answer
        val credited = right && !session.hinted
        val answerText = if (question.task.kind == TaskKind.SPELLING) question.word.word else question.options[question.answer]
        val combo = if (credited) session.combo + 1 else 0
        var tasks = session.tasks
        if (!credited && question.task.repeat < 2) {
            val retry = StudyTask("${question.task.key}:r", question.word.id, TaskKind.MEANING, question.task.repeat + 1)
            val at = (session.index + 4).coerceAtMost(tasks.size)
            tasks = tasks.toMutableList().also { it.add(at, retry) }
        }
        return session.copy(tasks = tasks, feedback = StudyFeedback(selected, typed, right, credited, answerText),
            answered = session.answered + 1, correct = session.correct + if (credited) 1 else 0,
            combo = combo, bestCombo = maxOf(session.bestCombo, combo),
            wrongIds = if (credited) session.wrongIds else session.wrongIds + question.word.id)
    }

    fun next(session: StudySession): StudySession = if (session.feedback == null) session
        else session.copy(index = session.index + 1, feedback = null, hinted = false)

    fun addCoach(session: StudySession, notes: List<CoachNote>): StudySession {
        if (session.finished) return session
        val fresh = notes.filter { it.wordId in session.wrongIds && it.wordId !in session.coachIds }
        if (fresh.isEmpty()) return session
        val tasks = session.tasks.toMutableList()
        val at = (session.index + 4).coerceAtMost(tasks.size)
        tasks.addAll(at, fresh.map { StudyTask("${session.id}:coach:${it.wordId}", it.wordId, TaskKind.CONTEXT, prompt = it.cloze+"\n"+it.translation) })
        return session.copy(tasks = tasks, coachIds = session.coachIds + fresh.map { it.wordId })
    }
}
