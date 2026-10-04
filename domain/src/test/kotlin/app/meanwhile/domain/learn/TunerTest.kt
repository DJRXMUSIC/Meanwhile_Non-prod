package app.meanwhile.domain.learn

import app.meanwhile.domain.dose.DoseInput
import app.meanwhile.domain.dose.PendingUnits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TunerTest {
    /** [n] lunches that each ended at [end] (60 g at BG 100 with ICR 10 → 6 u). */
    private fun lunches(f: LearnFixtures, n: Int, end: Int, startDay: Int = 0) {
        repeat(n) { i -> f.dose(f.at(startDay + i, 12), DoseInput(carbsG = 60.0, bg = 100.0), end = end) }
    }

    @Test
    fun `consistent under-dosing lowers ICR halfway toward the evidence`() {
        val f = LearnFixtures()
        lunches(f, 5, end = 150) // each implies 7.5 g/u
        val p = Tuner.propose(f.lessons(), f.profile, emptyMap(), f.at(6, 0)).single()
        assertEquals("dose.icr", p.path)
        assertEquals(10.0, p.old)
        assertEquals(7.5, p.implied, 1e-9)
        assertEquals(8.8, p.new, 1e-9) // 10 + 0.5 × (7.5 − 10) = 8.75 → 8.8
        assertEquals(5, p.lessons)
        assertTrue(p.evidence.contains("1 u per 7.5 g"), p.evidence)
    }

    @Test
    fun `needs enough lessons`() {
        val f = LearnFixtures()
        lunches(f, 2, end = 150)
        assertTrue(Tuner.propose(f.lessons(), f.profile, emptyMap(), f.at(3, 0)).isEmpty())
    }

    @Test
    fun `the same evidence never moves a value twice`() {
        val f = LearnFixtures()
        lunches(f, 5, end = 150)
        // ICR was changed after these lessons: nothing new to learn from yet.
        assertTrue(Tuner.propose(f.lessons(), f.profile, mapOf("dose.icr" to f.at(5, 0)), f.at(6, 0)).isEmpty())
        // Three fresh lessons after the change → a new step.
        lunches(f, 3, end = 150, startDay = 6)
        assertEquals(1, Tuner.propose(f.lessons(), f.profile, mapOf("dose.icr" to f.at(5, 0)), f.at(10, 0)).size)
    }

    @Test
    fun `on-target outcomes or tiny moves change nothing`() {
        val f = LearnFixtures()
        lunches(f, 5, end = 100)
        assertTrue(Tuner.propose(f.lessons(), f.profile, emptyMap(), f.at(6, 0)).isEmpty())
        val g = LearnFixtures()
        lunches(g, 5, end = 104) // implies ~9.7 → step to 9.9: under 3 %
        assertTrue(Tuner.propose(g.lessons(), g.profile, emptyMap(), g.at(6, 0)).isEmpty())
    }

    @Test
    fun `old lessons outside the lookback are ignored`() {
        val f = LearnFixtures()
        lunches(f, 5, end = 150)
        assertTrue(Tuner.propose(f.lessons(), f.profile, emptyMap(), f.at(30, 0)).isEmpty())
    }

    @Test
    fun `median resists one bad lesson`() {
        val f = LearnFixtures()
        lunches(f, 4, end = 150)
        f.dose(f.at(4, 12), DoseInput(carbsG = 60.0, bg = 100.0), end = 400) // unlogged dessert, say
        assertEquals(7.5, Tuner.propose(f.lessons(), f.profile, emptyMap(), f.at(6, 0)).single().implied, 1e-9)
    }

    @Test
    fun `repeated steps converge on the evidence without overshooting`() {
        val f = LearnFixtures()
        var profile = f.profile
        var lastChange = Long.MIN_VALUE
        var day = 0
        repeat(8) {
            // Three more lunches per round, each computed with the current ICR and ending where an
            // ICR of 8 would have put them: needed = 60/8 = 7.5 u.
            repeat(3) {
                val units = 60.0 / profile.dose.icr
                val endBg = (100 + (7.5 - Math.round(units)) * 25).toInt()
                f.dose(f.at(day++, 12), DoseInput(carbsG = 60.0, bg = 100.0), end = endBg, profileUsed = profile)
            }
            val now = f.at(day, 0)
            val step = Tuner.propose(f.lessons(), profile, mapOf("dose.icr" to lastChange), now).firstOrNull() ?: return@repeat
            assertTrue(step.new >= 7.9, "never past the evidence: ${step.new}")
            profile = profile.copy(dose = profile.dose.copy(icr = step.new))
            lastChange = now
        }
        // It stops once half the remaining gap is under minChangePct (3 %): within ~6 % of the evidence.
        assertTrue(profile.dose.icr in 7.9..8.0 * 1.065, "converged to ${profile.dose.icr}")
    }

    @Test
    fun `units per event are tuned from caffeine-only doses`() {
        val f = LearnFixtures()
        repeat(4) { i ->
            f.dose(f.at(i, 9), DoseInput(bg = 100.0, pendingUnits = listOf(PendingUnits("F4", "Caffeine", 1.0, 1.0))), end = 125)
        }
        val p = Tuner.propose(f.lessons(), f.profile, emptyMap(), f.at(5, 0)).single()
        assertEquals("factors.F4.unitsPerEvent", p.path)
        assertEquals(2.0, p.implied, 1e-9) // 1 u + 25/25
        assertEquals(1.5, p.new, 1e-9)
    }

    @Test
    fun `rate is tunable - 1 jumps straight to the evidence`() {
        val f = LearnFixtures()
        lunches(f, 5, end = 150)
        val fast = f.profile.copy(learning = f.profile.learning.copy(rate = 1.0))
        assertEquals(7.5, Tuner.propose(f.lessons(), fast, emptyMap(), f.at(6, 0)).single().new, 1e-9)
    }

    @Test
    fun `quantiles interpolate`() {
        assertEquals(2.5, Tuner.quantile(listOf(1.0, 2.0, 3.0, 4.0), 0.5), 1e-9)
        assertEquals(5.0, Tuner.quantile(listOf(5.0), 0.25), 1e-9)
    }
}
