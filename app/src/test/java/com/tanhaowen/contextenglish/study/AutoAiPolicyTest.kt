package com.tanhaowen.contextenglish.study

import com.tanhaowen.contextenglish.data.*
import org.junit.Assert.*
import org.junit.Test

class AutoAiPolicyTest {
    private val ai=AiSettings(apiKey="test-only",dailyModel="fixture",inputPricePerMillion=2.0,outputPricePerMillion=8.0)
    private val study=StudySettings(autoAiBudget=0.1)
    private fun job(known: Boolean=true,cost: Double=0.01)=AutoAiJob(1,"scope",setOf(1),"repeat error","DONE","fixture","CNY",cost,cost,known,"","",1,2)
    @Test fun missingKeyAndPricesNeverMakePaidCalls() {
        assertNotNull(AutoAiPolicy.block(ai.copy(apiKey=""),study,emptyList(),0.01))
        assertNotNull(AutoAiPolicy.block(ai.copy(inputPricePerMillion=0.0),study,emptyList(),0.01))
        assertNotNull(AutoAiPolicy.block(ai.copy(outputPricePerMillion=0.0),study,emptyList(),0.01))
    }
    @Test fun allowOnlyWithKnownBudget() { assertNull(AutoAiPolicy.block(ai,study,emptyList(),0.01)) }
    @Test fun budgetAndCallLimitStopQueue() {
        assertNotNull(AutoAiPolicy.block(ai,study,listOf(job(cost=0.095)),0.01))
        assertNotNull(AutoAiPolicy.block(ai,study,List(5) { job(cost=0.001) },0.001))
    }
    @Test fun uncertainUsagePausesTheWholeDay() {
        assertNotNull(AutoAiPolicy.block(ai,study,listOf(job(known=false,cost=0.001)),0.001))
    }
    @Test fun disablingOrZeroBudgetDisallowsAutoCalls() {
        assertNotNull(AutoAiPolicy.block(ai,study.copy(autoAi=false),emptyList(),0.01))
        assertNotNull(AutoAiPolicy.block(ai,study.copy(autoAiBudget=0.0),emptyList(),0.01))
    }
    @Test fun reserveAccountsForCacheWritingAndUnicode() {
        val s=ai.copy(cacheCreationPricePerMillion=10.0)
        assertTrue(AutoAiPolicy.reserve(s,"中文提示")>AutoAiPolicy.reserve(ai,"hint"))
    }
    @Test fun noApiKeyInCacheScope() { assertFalse(AutoAiPolicy.scope(ai).contains("test-only")) }
}
