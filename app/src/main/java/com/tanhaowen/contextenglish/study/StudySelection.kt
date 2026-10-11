package com.tanhaowen.contextenglish.study

import com.tanhaowen.contextenglish.data.VocabWord
import com.tanhaowen.contextenglish.data.WordState

object StudySelection {
    /** Small groups still mix new words with prioritized reviews; neither list monopolizes the group. */
    fun group(candidates: List<VocabWord>, limit: Int): List<VocabWord> {
        val unique = candidates.distinctBy { it.id }
        val reviews = unique.filter { it.state != WordState.NEW }.iterator()
        val fresh = unique.filter { it.state == WordState.NEW }.iterator()
        return buildList {
            while(size < limit && (reviews.hasNext() || fresh.hasNext())) {
                repeat(2) { if(size < limit && reviews.hasNext()) add(reviews.next()) }
                if(size < limit && fresh.hasNext()) add(fresh.next())
            }
        }
    }
}
