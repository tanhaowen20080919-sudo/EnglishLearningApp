package com.tanhaowen.contextenglish.adaptive

import com.tanhaowen.contextenglish.data.*
import org.junit.Assert.*
import org.junit.Test

class AdaptiveEngineTest {
    private val day=LearningParameters.DAY
    private fun good()=RecentPerformance(.98,.98,.98,.98,.98,1.0)
    private fun poor()=RecentPerformance(.3,.2,.25,.3,.25,.4)
    @Test fun objectiveErrorOverridesOptimisticSelfReport() {
        val old=Memory(.85,10.0,.3,streak=5,last=day)
        val self=AdaptiveReview.update(old,Evidence(2,"self",objective=false),2*day)
        val wrong=AdaptiveReview.update(self.memory,Evidence(0,"spelling"),2*day)
        assertTrue(wrong.memory.mastery<.5)
        assertTrue(wrong.next<=2*day+5*60_000)
        assertEquals(0,wrong.memory.streak); assertEquals(1,wrong.memory.lapses)
        assertFalse(wrong.mastered)
    }
    @Test fun selfReportsCannotCreateStableMastery() {
        var m=Memory()
        repeat(100) { m=AdaptiveReview.update(m,Evidence(2,"self",objective=false),(it+1)*day).memory }
        assertTrue(m.forms.isEmpty());assertEquals(0,m.streak);assertEquals(0L,m.last)
        assertFalse(AdaptiveReview.update(m,Evidence(2,"self",objective=false),101*day).mastered)
    }
    @Test fun intervalDependsOnHistoryAndVariety() {
        val basic=Memory(.8,3.0,.4,streak=3,recent=.9,last=day)
        val rich=basic.copy(forms=setOf("meaning","context","spelling"),contexts=setOf("a","b"))
        val a=AdaptiveReview.update(basic,Evidence(2,"meaning"),3*day)
        val b=AdaptiveReview.update(rich,Evidence(2,"context","c"),3*day)
        assertTrue(b.next>a.next)
    }
    @Test fun rapidRepeatsDoNotInflateStabilityOrStreak() {
        val old=Memory(.6,4.0,.4,streak=2,last=day)
        val result=AdaptiveReview.update(old,Evidence(2,"meaning"),day+1000)
        assertEquals(old.stability,result.memory.stability,0.0)
        assertEquals(old.streak,result.memory.streak)
    }
    @Test fun spacedObjectiveSuccessAfterSelfReportStillCounts() {
        val old=Memory(.8,3.0,.4,streak=2,last=day)
        val self=AdaptiveReview.update(old,Evidence(2,"self",objective=false),3*day)
        val answer=AdaptiveReview.update(self.memory,Evidence(2,"context","second"),3*day+1000)
        assertEquals(3,answer.memory.streak);assertTrue(answer.memory.stability>old.stability)
    }
    @Test fun masteryRequiresFormsContextsAndSpacedSuccess() {
        var memory=Memory(.9,3.0,.4,streak=4,forms=setOf("meaning"),contexts=setOf("a","b"),last=day)
        assertFalse(AdaptiveReview.update(memory,Evidence(2,"meaning"),3*day).mastered)
        memory=memory.copy(forms=setOf("meaning","spelling","context"))
        assertTrue(AdaptiveReview.update(memory,Evidence(2,"context","c"),3*day).mastered)
    }
    @Test fun meaningQuestionsCannotPretendToBeDifferentContexts() {
        val r=AdaptiveReview.update(Memory(),Evidence(2,"meaning","fake-context"),day)
        assertTrue(r.memory.contexts.isEmpty())
    }
    @Test fun lapseShrinksIntervalsAndRepeatedErrorsStayBounded() {
        var memory=Memory(.8,30.0,.2,last=day)
        repeat(30) { memory=AdaptiveReview.update(memory,Evidence(0,"context"),(it+2)*day).memory }
        assertTrue(memory.stability>=.05);assertTrue(memory.mastery>=0);assertTrue(memory.difficulty<=.95)
    }
    @Test fun dailyNewLoadChangesWithRealPerformance() {
        val high=DailyPlanner.calculate(good(),0,Intensity.STANDARD)
        val low=DailyPlanner.calculate(poor(),0,Intensity.STANDARD)
        assertTrue(high.newCount>low.newCount)
        assertTrue(high.newCount in 15..25);assertTrue(low.newCount in 15..25)
    }
    @Test fun backlogReducesNewWordsWithoutDroppingReviews() {
        val plan=DailyPlanner.calculate(good(),200,Intensity.QUICK)
        assertEquals(200,plan.reviewCount);assertEquals(0,plan.newCount);assertTrue(plan.minutes>15)
    }
    @Test fun quickAndCustomRespectCapacity() {
        assertTrue(DailyPlanner.calculate(good(),0,Intensity.QUICK).newCount<=5)
        assertTrue(DailyPlanner.calculate(good(),20,Intensity.CUSTOM,10).newCount<15)
    }
    @Test fun weakPointRequiresEvidenceAndRanksByPriority() {
        val priorities=WeaknessDetector.priorities(listOf(SkillScore("样本少",1,0),SkillScore("推断题",8,1),SkillScore("细节题",8,4),SkillScore("时态",6,5)))
        assertEquals(listOf("推断题","细节题"),priorities.map { it.tag })
    }
    @Test fun costIncludesFourRealUsageBuckets() {
        val cost=AiCostCalculator.calculate(TokenUsage(1000,200,100,600,1200,true),AiSettings(
            inputPricePerMillion=2.0,outputPricePerMillion=8.0,cacheCreationPricePerMillion=3.0,cacheReadPricePerMillion=.2))
        assertEquals(.00262,cost.total,1e-12)
    }
    @Test fun invalidRatingsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { AdaptiveReview.update(Memory(),Evidence(3,"meaning"),day) }
    }
}
