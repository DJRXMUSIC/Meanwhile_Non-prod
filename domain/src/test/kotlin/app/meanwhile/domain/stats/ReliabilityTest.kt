package app.meanwhile.domain.stats

import kotlin.test.Test
import kotlin.test.assertEquals

class ReliabilityTest {
    @Test fun followClassification() {
        assertEquals(Follow.NOT_LOGGED, ProposalFollowRule.classify(6, emptyList()))
        assertEquals(Follow.FOLLOWED, ProposalFollowRule.classify(6, listOf(6.0 to 6.0)))
        assertEquals(Follow.FOLLOWED, ProposalFollowRule.classify(11, listOf(7.0 to 7.0, 4.0 to 4.0)))
        assertEquals(Follow.OVERRIDDEN, ProposalFollowRule.classify(11, listOf(7.0 to 7.0, 0.0 to 4.0)))
        assertEquals(Follow.OVERRIDDEN, ProposalFollowRule.classify(6, listOf(5.0 to 6.0)))
    }

    @Test fun followStatsWithOutcomes() {
        val proposals = listOf(ProposalRow("a", 1, 6), ProposalRow("b", 2, 5), ProposalRow("c", 3, 4))
        val doses = listOf(
            DoseRow("d1", 1, "rapid", 6.0, 6.0, "a"),
            DoseRow("d2", 2, "rapid", 3.0, 5.0, "b"),
        )
        val outcomes = listOf(OutcomeRow("d1", 140, 150, 130, 95, 190), OutcomeRow("d2", 220, 240, 210, 180, 260))
        val s = Reliability.follow(proposals, doses, outcomes)
        assertEquals(1, s.counts[Follow.FOLLOWED])
        assertEquals(1, s.counts[Follow.OVERRIDDEN])
        assertEquals(1, s.counts[Follow.NOT_LOGGED])
        assertEquals(150.0, s.outcomes[Follow.FOLLOWED]!!.meanBg3h)
        assertEquals(100.0, s.outcomes[Follow.FOLLOWED]!!.inRange3hPct)
        assertEquals(100.0, s.outcomes[Follow.OVERRIDDEN]!!.highWithin4hPct)
    }

    private fun day(tir: Double, coverage: Double = 1440.0) =
        GlucoseSummary(288, coverage, tir, 0.0, 0.0, 100 - tir, 0.0, 140.0, 30.0, 60, 250, 6.6)

    @Test fun streakSkipsTodaysPartialDay() {
        val daily = listOf("2026-10-01" to day(70.0), "2026-10-02" to day(82.0), "2026-10-03" to day(85.0), "2026-10-04" to day(90.0, coverage = 60.0))
        assertEquals(2, Reliability.goalStreak(daily))
    }

    @Test fun aiAccuracy() {
        val calls = listOf(
            AiCallRow("gemini", "g", "route", 800, false, "ok"),
            AiCallRow("gemini", "g", "estimate_meal", 1200, false, "invalid"),
            AiCallRow("claude", "c", "route", 2000, true, "ok"),
        )
        val stats = AiAccuracy.byModel(
            calls,
            listOf(AiDecisionRow("gemini", "g", "accepted"), AiDecisionRow("gemini", "g", "rejected")),
            listOf(EstimateRow("gemini", "g", 60.0, 70.0), EstimateRow("gemini", "g", 40.0, 38.0)),
        )
        val g = stats.first { it.provider == "gemini" }
        assertEquals(2, g.calls)
        assertEquals(50.0, g.successPct)
        assertEquals(1000.0, g.meanLatencyMs)
        assertEquals(6.0, g.meanAbsCarbError)
        assertEquals(1, g.accepted)
        assertEquals(1, stats.first { it.provider == "claude" }.fallbackServed)
    }
}
