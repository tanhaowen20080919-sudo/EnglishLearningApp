package com.tanhaowen.contextenglish.study

import com.tanhaowen.contextenglish.data.VocabWord

/** Reuse the bundled, attributed example only when exactly one unchanged word can be blanked. */
object LocalCloze {
    fun prompt(word: VocabWord): String? {
        if(!Regex("[A-Za-z][A-Za-z'-]*").matches(word.word) || word.example.length !in 8..240 ||
            word.exampleTranslation.isBlank() || word.example.contains('_')) return null
        val token = Regex("(?i)(?<![a-z])${Regex.escape(word.word)}(?![a-z])")
        if(token.findAll(word.example).count() != 1 || token.containsMatchIn(word.exampleTranslation)) return null
        return token.replace(word.example,"____")+"\n"+word.exampleTranslation
    }
}
