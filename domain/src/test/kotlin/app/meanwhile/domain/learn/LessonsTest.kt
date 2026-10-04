package app.meanwhile.domain.learn

import app.meanwhile.domain.dose.DoseInput
import app.meanwhile.domain.dose.FactorWeight
import app.meanwhile.domain.dose.PendingUnits
import app.meanwhile.domain.stats.DoseRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Profile defaults: ICR 10, ISF 25, target 100. */
class LessonsTest {

    @Test
    fun `under-dosed meal implies a lower ICR`() {
        val f = LearnFixtures()
        // 60 g at BG 100 → 6 u; ended at 150 → 50/25 = 2 u short → 8 u needed → 60/8 = 7.5 g/u.
        f.dose(f.at(0, 12), DoseInput(carbsG = 60.0, bg = 100.0), end = 150)
        val l = f.lessons().single()
        assertEquals(LessonKind.MEAL, l.kind)
        assertTrue(l.clean)
        assertEquals(8.0, l.unitsNeeded!!, 1e-9)
        assertEquals(2.0, l.errorUnits!!, 1e-9)
        assertEquals(7.5, l.impliedIcr!!, 1e-9)
        assertEquals(50.0, l.score!!, 1e-9)
        assertEquals(12, l.localHour)
    }

    @Test
    fun `a low drives the lesson, not the end BG`() {
        val f = LearnFixtures()
        // Went to 60 then rescued to 140: the low says 40/25 = 1.6 u too much → 4.4 u → 13.6 g/u.
        f.dose(f.at(0, 12), DoseInput(carbsG = 60.0, bg = 100.0), end = 140, min = 60)
        val l = f.lessons().single()
        assertTrue(l.wentLow)
        assertEquals(4.4, l.unitsNeeded!!, 1e-9)
        assertEquals(60.0 / 4.4, l.impliedIcr!!, 1e-9)
        // Miss 40 at the end + 3 × (70 − 60) for the low.
        assertEquals(70.0, l.score!!, 1e-9)
    }

    @Test
    fun `multipliers are accounted for before blaming ICR`() {
        val f = LearnFixtures()
        // Poor sleep ×1.2: 6 u × 1.2 = 7.2 → 7 u given; landed on target → baseline 7/1.2 → ICR ≈ 10.29.
        f.dose(f.at(0, 8), DoseInput(carbsG = 60.0, bg = 100.0, factors = listOf(FactorWeight("F8", "Sleep", 1.2))), end = 100)
        val l = f.lessons().single()
        assertEquals(60.0 / (7.0 / 1.2), l.impliedIcr!!, 1e-9)
        assertEquals(listOf("F8"), l.factors)
    }

    @Test
    fun `correction implies ISF`() {
        val f = LearnFixtures()
        // BG 200 → 4 u; ended at 140 → 40/25 = 1.6 u short → 5.6 u for a 100 mg/dL drop → ISF 17.9.
        f.dose(f.at(0, 15), DoseInput(bg = 200.0), end = 140)
        val l = f.lessons().single()
        assertEquals(LessonKind.CORRECTION, l.kind)
        assertEquals(100 / 5.6, l.impliedIsf!!, 1e-9)
        assertNull(l.impliedIcr)
    }

    @Test
    fun `caffeine-only dose implies units per cup`() {
        val f = LearnFixtures()
        // Two coffees at 1 u each → 2 u; ended at 150 → 2 u short → 4 u / 2 cups = 2 u per cup.
        f.dose(f.at(0, 9), DoseInput(bg = 100.0, pendingUnits = listOf(PendingUnits("F4", "Caffeine", 2.0, 2.0))), end = 150)
        val l = f.lessons().single()
        assertEquals(LessonKind.UNITS_PER_EVENT, l.kind)
        assertEquals("F4", l.unitsFactorId)
        assertEquals(2.0, l.impliedUnitsPerEvent!!, 1e-9)
    }

    @Test
    fun `overrides teach too - the lesson uses what was given`() {
        val f = LearnFixtures()
        // Proposed 6 u, Danny took 7 and landed on target → 7 u was right → ICR 60/7.
        f.dose(f.at(0, 12), DoseInput(carbsG = 60.0, bg = 100.0), end = 100, given = 7.0)
        val l = f.lessons().single()
        assertEquals(7.0, l.unitsGiven, 1e-9)
        assertEquals(6.0, l.unitsProposed, 1e-9)
        assertEquals(60.0 / 7.0, l.impliedIcr!!, 1e-9)
    }

    @Test
    fun `split parts are summed`() {
        val f = LearnFixtures()
        val id = f.dose(f.at(0, 19), DoseInput(carbsG = 60.0, bg = 100.0), end = 100, given = 4.0)
        f.doses += DoseRow("d-second", f.at(0, 20), "rapid", 2.0, 2.0, id)
        assertEquals(6.0, f.lessons().single().unitsGiven, 1e-9)
    }

    @Test
    fun `confounded doses are kept but marked not clean`() {
        val f = LearnFixtures()
        f.dose(f.at(0, 12), DoseInput(carbsG = 60.0, bg = 100.0), end = 180)
        f.dose(f.at(0, 14), DoseInput(carbsG = 30.0, bg = 160.0), end = 120) // a snack 2 h later
        val (lunch, snack) = f.lessons()
        assertFalse(lunch.clean)
        assertEquals("another rapid dose within 4 h", lunch.excludedBecause)
        assertTrue(snack.clean)
    }

    @Test
    fun `an unlogged meal in the window excludes the lesson`() {
        val f = LearnFixtures()
        f.dose(f.at(0, 12), DoseInput(carbsG = 60.0, bg = 100.0), end = 220)
        f.meals += MealRow(f.at(0, 13), proposalId = null)
        assertEquals("another meal within 4 h", f.lessons().single().excludedBecause)
    }

    @Test
    fun `no BG at dose time and no outcome are excluded`() {
        val f = LearnFixtures()
        f.dose(f.at(0, 12), DoseInput(carbsG = 60.0, bg = null), end = 120)
        f.dose(f.at(1, 12), DoseInput(carbsG = 60.0, bg = 100.0), end = null, min = 110)
        val (noBg, noCgm) = f.lessons()
        assertEquals("no BG at dose time", noBg.excludedBecause)
        assertEquals("no CGM at 3–4 h", noCgm.excludedBecause)
        assertNull(noCgm.score)
    }

    @Test
    fun `untagged and unlogged proposals produce no lesson`() {
        val f = LearnFixtures()
        f.dose(f.at(0, 12), DoseInput(carbsG = 60.0, bg = 100.0), end = 120)
        f.outcomes.clear()
        assertTrue(f.lessons().isEmpty())
        f.dose(f.at(1, 12), DoseInput(carbsG = 60.0, bg = 100.0), end = 120)
        f.doses.clear()
        assertTrue(f.lessons().isEmpty())
    }

    @Test
    fun `lessons use the profile the proposal was computed with`() {
        val f = LearnFixtures()
        val older = f.profile.copy(dose = f.profile.dose.copy(icr = 12.0))
        // With ICR 12: 60 g → 5 u; landed on target → 5 u was right → ICR 12 confirmed.
        f.dose(f.at(0, 12), DoseInput(carbsG = 60.0, bg = 100.0), end = 100, profileUsed = older)
        assertNotNull(f.lessons().single().impliedIcr)
        assertEquals(12.0, f.lessons().single().impliedIcr!!, 1e-9)
    }
}
