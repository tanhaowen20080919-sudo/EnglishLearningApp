package com.tanhaowen.contextenglish.adaptive

import android.content.Context
import org.json.JSONObject
import java.util.Locale

data class CorpusExample(val text: String,val translation: String,val attribution: String)
data class WordSupplement(val forms: String,val examples: List<CorpusExample>)

/** Loaded once on IO. Never overwrites the original vocabulary, IPA, meanings or audio. */
object OfflineWordResources {
    @Volatile private var supplements: Map<String,WordSupplement> = emptyMap()
    @Synchronized fun load(context: Context) {
        if(supplements.isNotEmpty()) return
        val rows=JSONObject(context.assets.open("vocab-enrichment.json").bufferedReader().use { it.readText() }).getJSONObject("words")
        supplements=rows.keys().asSequence().associateWith { word ->
            val row=rows.getJSONObject(word);val examples=row.getJSONArray("examples")
            WordSupplement(row.getString("forms"),(0 until examples.length()).map { i ->
                val e=examples.getJSONObject(i);CorpusExample(e.getString("text"),e.getString("translation"),e.getString("attribution"))
            })
        }
    }
    fun get(word: String)=supplements[word.lowercase(Locale.ROOT)]
}
