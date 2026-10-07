package app.meanwhile.domain.nba

import app.meanwhile.domain.dose.DoseEngine
import app.meanwhile.domain.dose.DoseInput
import app.meanwhile.domain.profile.AlertSettings
import app.meanwhile.domain.profile.Profile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Notifications the app sends on its own (2.0): only the action the math gives, only when it matters. */
class AdvisorTest {
    private val profile = Profile()
    private val now = 1_800_000_000_000L

    private fun check(
        input: DoseInput,
        sinceDose: Double? = null,
        last: Map<AlertKind, Long> = emptyMap(),
        stale: Boolean = false,
        p: Profile = profile,
    ): Alert? {
        val action = NextBestActions.decide(input, DoseEngine.compute(input, p), p, 3, stale)
        return Advisor.check(action, input.bg, input.trendRate, stale, sinceDose, last, now, p)
    }

    @Test
    fun `a low is announced at once and repeats after the quiet period`() {
        val low = check(DoseInput(bg = 62.0, trendRate = -1.0))
        assertNotNull(low)
        assertEquals(AlertKind.LOW, low.kind)
        assertEquals("Eat 15 g fast carbs", low.title)
        assertEquals("BG 62 ↘ · No insulin · BG 62 is low · recheck in 15 min", low.text)

        assertNull(check(DoseInput(bg = 62.0), last = mapOf(AlertKind.LOW to now - 10 * 60_000)))
        assertNotNull(check(DoseInput(bg = 62.0), last = mapOf(AlertKind.LOW to now - 31 * 60_000)))
    }

    @Test
    fun `a low coming within 20 min is announced too`() {
        val a = check(DoseInput(bg = 95.0, trendRate = -2.0))
        assertEquals(AlertKind.LOW, a?.kind)
    }

    @Test
    fun `a correction waits for BG to be high and the last dose to have worked`() {
        assertNull(check(DoseInput(bg = 170.0), sinceDose = 200.0), "below 180")
        assertNull(check(DoseInput(bg = 250.0), sinceDose = 30.0), "dosed 30 min ago")
        val a = check(DoseInput(bg = 250.0, trendRate = 0.2), sinceDose = 90.0)
        assertNotNull(a)
        assertEquals(AlertKind.CORRECTION, a.kind)
        assertEquals("BG 250 → — take 6 u", a.title)
        assertEquals("correction — no food needed", a.text)
        assertEquals(6, a.action.unitsNow)
    }

    @Test
    fun `carbs only when insulin on board takes a falling BG low`() {
        // 4 u on board at BG 120: lands at 120 − 100 = 20 → eat carbs.
        assertNull(check(DoseInput(bg = 120.0, trendRate = 0.5, iob = 4.0)), "not falling")
        val a = check(DoseInput(bg = 120.0, trendRate = -0.5, iob = 4.0))
        assertEquals(AlertKind.CARBS, a?.kind)
        // Landing at 90 is above the 70 line: no alert.
        assertNull(check(DoseInput(bg = 140.0, trendRate = -0.5, iob = 2.0)))
    }

    @Test
    fun `nothing while the reading is stale, unknown or alerts are off`() {
        assertNull(check(DoseInput(bg = 55.0), stale = true))
        assertNull(check(DoseInput(bg = null)))
        assertNull(check(DoseInput(bg = 55.0), p = profile.copy(alerts = AlertSettings(enabled = false))))
        assertNull(check(DoseInput(bg = 55.0), p = profile.copy(alerts = AlertSettings(lows = false))))
    }
}
