package app.meanwhile.domain.learn

import app.meanwhile.domain.dose.DoseInput
import app.meanwhile.domain.dose.FactorWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChangeEvaluatorTest {
    private fun lunch(f: LearnFixtures, day: Int, end: Int, min: Int = end) =
        f.dose(f.at(day, 12), DoseInput(carbsG = 60.0, bg = 100.0), end = end, min = min)

    private val icrChange = { f: LearnFixtures -> TrackedChange("c1", "dose.icr", f.at(5, 0), moreInsulin = true) }

    @Test
    fun `waits for enough outcomes`() {
        val f = LearnFixtures()
        (0..4).forEach { lunch(f, it, 160) }
        lunch(f, 5, 120)
        val e = ChangeEvaluator.evaluate(icrChange(f), f.lessons(), f.profile.learning)
        assertEquals(Verdict.PENDING, e.verdict)
        assertEquals(1, e.lessonsAfter)
    }

    @Test
    fun `keeps a change that helped`() {
        val f = LearnFixtures()
        (0..4).forEach { lunch(f, it, 160) } // missed by 60
        (5..7).forEach { lunch(f, it, 115) } // missed by 15
        val e = ChangeEvaluator.evaluate(icrChange(f), f.lessons(), f.profile.learning)
        assertEquals(Verdict.KEEP, e.verdict)
        assertEquals(60.0, e.scoreBefore!!, 1e-9)
        assertEquals(15.0, e.scoreAfter!!, 1e-9)
    }

    @Test
    fun `reverts a change that made things worse`() {
        val f = LearnFixtures()
        (0..4).forEach { lunch(f, it, 130) } // missed by 30
        (5..7).forEach { lunch(f, it, 45 + 100) } // missed by 45: 50 % worse
        val e = ChangeEvaluator.evaluate(icrChange(f), f.lessons(), f.profile.learning)
        assertEquals(Verdict.REVERT, e.verdict)
        assertTrue(e.reason.contains("30 → 45"), e.reason)
    }

    @Test
    fun `small wobble inside the tolerance is kept`() {
        val f = LearnFixtures()
        (0..4).forEach { lunch(f, it, 140) } // 40
        (5..7).forEach { lunch(f, it, 144) } // 44: 10 % worse, under 15 %
        assertEquals(Verdict.KEEP, ChangeEvaluator.evaluate(icrChange(f), f.lessons(), f.profile.learning).verdict)
    }

    @Test
    fun `a severe low after a more-insulin change reverts immediately`() {
        val f = LearnFixtures()
        (0..4).forEach { lunch(f, it, 160) }
        lunch(f, 5, end = 120, min = 48)
        val e = ChangeEvaluator.evaluate(icrChange(f), f.lessons(), f.profile.learning)
        assertEquals(Verdict.REVERT, e.verdict)
        assertTrue(e.reason.contains("48"))
        // The same low after a change that gives LESS insulin is just a lesson, not a revert trigger.
        val less = TrackedChange("c2", "dose.icr", f.at(5, 0), moreInsulin = false)
        assertEquals(Verdict.PENDING, ChangeEvaluator.evaluate(less, f.lessons(), f.profile.learning).verdict)
    }

    @Test
    fun `no baseline means keep once enough outcomes`() {
        val f = LearnFixtures()
        (5..7).forEach { lunch(f, it, 120) }
        val e = ChangeEvaluator.evaluate(icrChange(f), f.lessons(), f.profile.learning)
        assertEquals(Verdict.KEEP, e.verdict)
        assertNull(e.scoreBefore)
    }

    @Test
    fun `only relevant lessons count`() {
        val f = LearnFixtures()
        (0..4).forEach { lunch(f, it, 160) }
        // Corrections after an ICR change don't judge it.
        (5..7).forEach { f.dose(f.at(it, 15), DoseInput(bg = 250.0), end = 300) }
        assertEquals(Verdict.PENDING, ChangeEvaluator.evaluate(icrChange(f), f.lessons(), f.profile.learning).verdict)

        val g = LearnFixtures()
        g.dose(g.at(0, 8), DoseInput(carbsG = 40.0, bg = 100.0, factors = listOf(FactorWeight("F8", "Sleep", 1.2))), end = 120)
        val sleepy = g.lessons().single()
        assertTrue(ChangeEvaluator.relevant("factors.F8.defaultWeight", sleepy))
        assertTrue(ChangeEvaluator.relevant("active.F8", sleepy))
        assertFalse(ChangeEvaluator.relevant("factors.F7.defaultWeight", sleepy))
        assertFalse(ChangeEvaluator.relevant("dose.isf", sleepy))
        assertTrue(ChangeEvaluator.relevant("leadTime.baseMin", sleepy))
    }

    @Test
    fun `direction of a change`() {
        assertEquals(true, ChangeEvaluator.moreInsulin("dose.icr", 10.0, 9.0))
        assertEquals(false, ChangeEvaluator.moreInsulin("dose.isf", 25.0, 30.0))
        assertEquals(true, ChangeEvaluator.moreInsulin("factors.F4.unitsPerEvent", 1.0, 1.5))
        assertEquals(true, ChangeEvaluator.moreInsulin("dose.target", 110.0, 100.0))
        assertNull(ChangeEvaluator.moreInsulin("leadTime.baseMin", 12.0, 15.0))
        assertNull(ChangeEvaluator.moreInsulin("dose.icr", 10.0, 10.0))
    }
}
