package app.meanwhile.domain.nba

import app.meanwhile.domain.dose.DoseEngine
import app.meanwhile.domain.dose.DoseInput
import app.meanwhile.domain.dose.PendingUnits
import app.meanwhile.domain.profile.NbaSettings
import app.meanwhile.domain.profile.Profile
import app.meanwhile.domain.profile.ProfileValidation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Next Best Action is insulin, carbs or nothing — decided by code from the dose math (1.4). */
class NextBestActionTest {
    private val profile = Profile() // ICR 10, ISF 25, target 100, base lead time 12 min

    private fun nba(input: DoseInput, p: Profile = profile, stale: Boolean = false): NextBestAction =
        NextBestActions.decide(input, DoseEngine.compute(input, p), p, bgAgeMinutes = 3, bgStale = stale)

    @Test
    fun `a meal gets insulin and the lead time`() {
        val a = nba(DoseInput(carbsG = 60.0, bg = 150.0))
        assertEquals(ActionKind.TAKE_INSULIN, a.kind)
        assertEquals("Take 8 u", a.headline)
        assertEquals("then eat in 12 min", a.detail)
        assertEquals(8, a.unitsNow)
        assertEquals(12, a.eatInMin)
        assertTrue(a.insulin)
    }

    @Test
    fun `a high BG alone is a correction with no food`() {
        val a = nba(DoseInput(bg = 250.0))
        assertEquals("Take 6 u", a.headline)
        assertEquals("correction — no food needed", a.detail)
        assertNull(a.eatInMin)
    }

    @Test
    fun `caffeine alone is named in the action`() {
        val a = nba(DoseInput(bg = 100.0, pendingUnits = listOf(PendingUnits("F4", "Caffeine", 1.0, 1.0))))
        assertEquals("Take 1 u", a.headline)
        assertEquals("caffeine — no food needed", a.detail)
    }

    @Test
    fun `a low is treated with fast carbs, never insulin`() {
        val a = nba(DoseInput(bg = 60.0))
        assertEquals(ActionKind.TREAT_LOW, a.kind)
        assertEquals("Eat 16 g fast carbs", a.headline) // max(15 g rule, 16 g the math wants)
        assertEquals("No insulin · BG 60 is low · recheck in 15 min", a.detail)
        assertEquals(16, a.carbsG)
        assertEquals(15, a.recheckInMin)
        assertFalse(a.insulin)
    }

    @Test
    fun `a low with insulin on board asks for enough carbs to cover it`() {
        val a = nba(DoseInput(bg = 65.0, iob = 2.0))
        assertEquals("Eat 34 g fast carbs", a.headline) // (65−100)/25 − 2 = −3.4 u × ICR 10
    }

    @Test
    fun `a gentle low still gets the rule of 15`() {
        val a = nba(DoseInput(bg = 68.0))
        assertEquals("Eat 15 g fast carbs", a.headline)
    }

    @Test
    fun `falling fast toward a low counts as a low`() {
        val a = nba(DoseInput(bg = 85.0, trendRate = -2.0))
        assertEquals(ActionKind.TREAT_LOW, a.kind)
        assertEquals("Eat 15 g fast carbs", a.headline)
        assertTrue(a.detail.contains("falling -2.0/min — about 45 in 20 min"), a.detail)
    }

    @Test
    fun `insulin on board that overshoots means carbs, not insulin`() {
        val a = nba(DoseInput(bg = 120.0, iob = 2.0))
        assertEquals(ActionKind.EAT_CARBS, a.kind)
        assertEquals("Eat ~12 g carbs", a.headline)
        assertEquals("No insulin · insulin on board would take you to ~70", a.detail)
        assertEquals(12, a.carbsG)
        assertEquals(70, a.projectedBg)
    }

    @Test
    fun `a meal that insulin on board more than covers gets extra carbs`() {
        val a = nba(DoseInput(carbsG = 10.0, bg = 110.0, iob = 3.0))
        assertEquals(ActionKind.EAT_CARBS, a.kind)
        assertEquals("Eat it, plus ~16 g carbs", a.headline) // 1 + 0.4 − 3 = −1.6 u
    }

    @Test
    fun `a snack covered by insulin on board needs nothing`() {
        val a = nba(DoseInput(carbsG = 8.0, bg = 100.0, iob = 1.0))
        assertEquals(ActionKind.EAT_NO_INSULIN, a.kind)
        assertEquals("Eat — no insulin needed", a.headline)
        assertEquals("Insulin on board covers it", a.detail)
    }

    @Test
    fun `a tiny snack rounds to no insulin`() {
        val a = nba(DoseInput(carbsG = 4.0, bg = 100.0))
        assertEquals(ActionKind.EAT_NO_INSULIN, a.kind)
        assertEquals("Needs only 0.4 u — rounds to 0", a.detail)
    }

    @Test
    fun `on target with nothing on board is nothing to do`() {
        val a = nba(DoseInput(bg = 105.0, trendRate = 0.2))
        assertEquals(ActionKind.NOTHING, a.kind)
        assertEquals("Nothing to do now", a.headline)
        assertEquals("BG 105 → · on track", a.detail)
    }

    @Test
    fun `slightly below target is not worth a snack`() {
        val a = nba(DoseInput(bg = 95.0, iob = 0.1)) // −0.3 u → 3 g, under the 5 g threshold
        assertEquals(ActionKind.NOTHING, a.kind)
        assertTrue(a.why.any { it.startsWith("Slightly below target") }, a.why.toString())
    }

    @Test
    fun `a high fat and protein meal is split`() {
        val a = nba(DoseInput(carbsG = 60.0, fatG = 45.0, proteinG = 30.0, bg = 100.0))
        assertEquals(ActionKind.SPLIT_INSULIN, a.kind)
        assertEquals("Take ${a.unitsNow} u now", a.headline)
        assertTrue(a.unitsLater > 0)
        assertEquals(60, a.laterAfterMin)
        assertTrue(a.detail.contains("${a.unitsLater} u more 60 min after you start"), a.detail)
    }

    @Test
    fun `no BG and nothing to dose asks for a BG`() {
        val a = nba(DoseInput())
        assertEquals(ActionKind.CHECK_BG, a.kind)
        assertEquals("Check your BG", a.headline)
    }

    @Test
    fun `no BG with a meal still doses the meal and says so`() {
        val a = nba(DoseInput(carbsG = 50.0))
        assertEquals(ActionKind.TAKE_INSULIN, a.kind)
        assertEquals("Take 5 u", a.headline)
        assertTrue(a.detail.endsWith("no BG, correction not included"), a.detail)
        assertNull(a.projectedBg)
    }

    @Test
    fun `low with a meal that needs insulin - inject and eat now`() {
        val a = nba(DoseInput(carbsG = 45.0, bg = 62.0))
        assertEquals(ActionKind.TAKE_INSULIN, a.kind)
        assertEquals("Take 3 u", a.headline)
        assertEquals("and eat now", a.detail)
        assertTrue(a.why.any { it.contains("below 70") }, a.why.toString())
    }

    @Test
    fun `low with a small meal - eat it plus fast carbs`() {
        val a = nba(DoseInput(carbsG = 10.0, bg = 60.0))
        assertEquals(ActionKind.TREAT_LOW, a.kind)
        assertEquals("Eat now, plus 5 g fast carbs", a.headline)
        assertEquals(5, a.carbsG)
    }

    @Test
    fun `low where the meal already has enough carbs`() {
        val a = nba(DoseInput(carbsG = 20.0, bg = 60.0, iob = 1.0)) // 2 − 1.6 − 1 = −0.6 u
        assertEquals("Eat now — no insulin", a.headline)
        assertEquals(0, a.carbsG)
    }

    @Test
    fun `thresholds are tunable profile values`() {
        val p = profile.copy(nba = NbaSettings(lowBelowMgDl = 80.0, lowTreatCarbsG = 20.0, recheckMin = 20))
        val a = nba(DoseInput(bg = 75.0), p)
        assertEquals("Eat 20 g fast carbs", a.headline)
        assertTrue(a.detail.endsWith("recheck in 20 min"), a.detail)
    }

    @Test
    fun `why explains the decision with the numbers`() {
        val a = nba(DoseInput(carbsG = 60.0, bg = 150.0, trendRate = 1.2, iob = 1.0), stale = true)
        val why = a.why.joinToString("\n")
        assertTrue(why.contains("BG 150 ↗ (+1.2/min), 3 min old — stale"), why)
        assertTrue(why.contains("Insulin on board: 1 u."), why)
        assertTrue(why.contains("Dose math: 7 u → rounds to 7 u."), why)
        assertTrue(why.contains("Without more insulin you'd land around 275 mg/dL."), why)
        assertEquals("Take 7 u — then eat in 12 min", a.sentence)
    }

    @Test
    fun `invalid NBA settings are profile problems`() {
        val problems = ProfileValidation.problems(profile.copy(nba = NbaSettings(lowTreatCarbsG = -1.0, recheckMin = -5)))
        assertTrue(problems.any { it.startsWith("nba.lowTreatCarbsG") }, problems.toString())
        assertTrue(problems.any { it.startsWith("nba.recheckMin") }, problems.toString())
    }
}
