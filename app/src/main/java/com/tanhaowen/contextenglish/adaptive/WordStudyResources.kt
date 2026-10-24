package com.tanhaowen.contextenglish.adaptive

import android.content.Context
import com.tanhaowen.contextenglish.data.VocabWord
import org.json.JSONObject
import java.util.Locale

data class StudyWordNote(val meaning: String,val collocation: String,val example: String,val translation: String,
    val originalMeaning: String,val provenance: String,val pos: String = "")
data class MorphologyNote(val text: String,val kind: String,val basis: String,val reference: String)

/** Immutable assets, opened on the database's IO path. Never rewrites a word, its IPA, progress or audio. */
object WordStudyResources {
    private data class Snapshot(val cards: Map<String,StudyWordNote>,val forms: Map<String,MorphologyNote>)
    @Volatile private var snapshot: Snapshot?=null
    @Synchronized fun load(context: Context): Boolean {
        if(snapshot!=null) return true
        return runCatching {
            val root=JSONObject(context.assets.open("word-study-notes.json").bufferedReader().use { it.readText() })
            require(root.getInt("format") in 1..2)
            val cards=root.getJSONObject("studyCards");val forms=root.getJSONObject("morphology")
            val parsedCards=cards.keys().asSequence().associateWith { key -> val n=cards.getJSONObject(key)
                StudyWordNote(n.getString("meaning"),n.getString("collocation"),n.getString("example"),n.getString("translation"),n.getString("originalMeaning"),n.getString("provenance"),n.optString("pos")) }
            val parsedForms=forms.keys().asSequence().associateWith { key -> val n=forms.getJSONObject(key)
                MorphologyNote(n.getString("text"),n.getString("kind"),n.getString("basis"),n.getString("reference")) }
            require(parsedCards.values.all { it.meaning.isNotBlank() && it.collocation.isNotBlank() && it.example.isNotBlank() && it.translation.isNotBlank() })
            require(parsedForms.values.all { it.text.isNotBlank() })
            snapshot=Snapshot(parsedCards,parsedForms)
        }.isSuccess
    }
    fun note(word: VocabWord): StudyWordNote?=snapshot?.cards?.get(word.word.lowercase(Locale.ROOT))
        ?.takeIf { it.originalMeaning==word.meaning }
    fun morphology(word: String): MorphologyNote?=snapshot?.forms?.get(word.lowercase(Locale.ROOT))
    fun coverage(): Int=snapshot?.cards?.size ?: 0
}
