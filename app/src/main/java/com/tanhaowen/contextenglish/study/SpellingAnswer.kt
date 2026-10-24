package com.tanhaowen.contextenglish.study

import java.util.Locale

/** Standard UK/US spellings of the same vocabulary item, never fuzzy matching or lemmatization.
 * POS/sense-dependent pairs (practice/practise, license/licence, program/programme) are
 * deliberately not treated as unconditional equivalents.
 */
object SpellingAnswer {
    private val groups=listOf(
        setOf("organisation","organization"),setOf("organise","organize"),
        setOf("realise","realize"),setOf("recognize","recognise"),setOf("analyse","analyze"),
        setOf("humour","humor"),setOf("behaviour","behavior"),setOf("neighbourhood","neighborhood"),
        setOf("labour","labor"),setOf("favour","favor"),setOf("honour","honor"),
        setOf("centre","center"),setOf("skilful","skillful"),setOf("judgement","judgment")
    )
    private val variants=groups.flatMap { group -> group.map { it to group } }.toMap()
    fun correct(expected: String,answer: String): Boolean {
        val target=expected.trim().lowercase(Locale.ROOT)
        val input=answer.trim().lowercase(Locale.ROOT)
        if(target.isBlank()) return false
        return input==target || input in variants[target].orEmpty()
    }
}
