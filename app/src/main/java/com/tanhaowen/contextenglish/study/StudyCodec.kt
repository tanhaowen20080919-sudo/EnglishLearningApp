package com.tanhaowen.contextenglish.study

import org.json.JSONArray
import org.json.JSONObject

object StudyCodec {
    fun encode(s: StudySession): String = JSONObject().apply {
        put("id", s.id); put("day", s.day); put("mode", s.mode); put("index", s.index)
        put("tasks", JSONArray().apply { s.tasks.forEach { t -> put(JSONObject().put("key",t.key)
            .put("wordId",t.wordId).put("kind",t.kind.name).put("repeat",t.repeat).put("prompt",t.prompt)) } })
        put("hinted",s.hinted); put("answered",s.answered); put("correct",s.correct)
        put("combo",s.combo); put("bestCombo",s.bestCombo)
        put("wrongIds",JSONArray(s.wrongIds.toList())); put("newIds",JSONArray(s.newIds.toList()))
        put("originalIds",JSONArray(s.originalIds.toList())); put("coachIds",JSONArray(s.coachIds.toList()))
        s.feedback?.let { f -> put("feedback",JSONObject().put("selected",f.selected).put("typed",f.typed)
            .put("correct",f.correct).put("credited",f.credited).put("answer",f.answer)) }
    }.toString()

    fun decode(text: String): StudySession {
        val j = JSONObject(text)
        fun ids(key: String): Set<Long> { val a=j.optJSONArray(key) ?: JSONArray(); return (0 until a.length()).map { a.getLong(it) }.toSet() }
        val tasks=j.getJSONArray("tasks")
        require(tasks.length() <= 2000)
        val s=StudySession(j.getString("id"),j.getString("day"),j.getString("mode"),
            (0 until tasks.length()).map { i -> val t=tasks.getJSONObject(i); StudyTask(t.getString("key"),t.getLong("wordId"),
                TaskKind.valueOf(t.getString("kind")),t.optInt("repeat"),t.optString("prompt")) },
            j.getInt("index"),j.optJSONObject("feedback")?.let { f -> StudyFeedback(f.getInt("selected"),f.optString("typed"),
                f.getBoolean("correct"),f.getBoolean("credited"),f.getString("answer")) },j.optBoolean("hinted"),
            j.optInt("answered"),j.optInt("correct"),j.optInt("combo"),j.optInt("bestCombo"),ids("wrongIds"),
            ids("newIds"),ids("originalIds"),ids("coachIds"))
        require(s.index in 0..s.tasks.size && s.tasks.map { it.key }.distinct().size == s.tasks.size)
        return s
    }

    fun notes(text: String, allowed: Set<Long>): List<CoachNote> {
        val clean=text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val a=JSONObject(clean).getJSONArray("items")
        require(a.length() in 1..3)
        return (0 until a.length()).map { i ->
            val n=a.getJSONObject(i); val id=n.getLong("wordId")
            require(id in allowed)
            val note=CoachNote(id,n.getString("tip"),n.getString("example"),n.getString("translation"),n.getString("cloze"))
            require(note.tip.length in 1..300 && note.example.length in 3..240 && note.translation.length in 1..240 &&
                note.cloze.length in 3..240 && Regex("_{2,}").findAll(note.cloze).count()==1)
            note
        }.also { require(it.map { n -> n.wordId }.distinct().size == it.size) }
    }
}
