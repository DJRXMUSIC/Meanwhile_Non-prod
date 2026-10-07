package app.meanwhile.domain.forecast

import app.meanwhile.domain.dose.DoseEngine
import app.meanwhile.domain.dose.DoseInput
import app.meanwhile.domain.iob.Iob
import app.meanwhile.domain.iob.RapidDose
import app.meanwhile.domain.nba.ActionKind
import app.meanwhile.domain.nba.NextBestActions
import app.meanwhile.domain.profile.DoseSettings
import app.meanwhile.domain.profile.ForecastSettings
import app.meanwhile.domain.profile.Profile
import app.meanwhile.domain.profile.ProfileValidation
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The forecast (2.0): carbs still absorbing and the CGM change the records don't explain. */
class ForecastTest {
    private val profile = Profile() // ICR 10, ISF 25; carbs 10 min delay + 180 min; lookback 30, carry 120
    private val now = 1_800_000_000_000L
    private fun min(m: Double) = now - (m * 60_000).toLong()

    /** Readings every 5 min over the last 30 min following [bgAt] (minutes before now → mg/dL). */
    private fun readings(bgAt: (Double) -> Double) = (0..6).map { i -> val m = 30.0 - 5 * i; GlucosePoint(min(m), bgAt(m)) }

    private fun near(expected: Double, actual: Double, tol: Double = 1e-6, what: String = "") =
        assertTrue(abs(expected - actual) <= tol, "$what expected $expected ± $tol, was $actual")

    @Test
    fun `flat BG with nothing logged has nothing to forecast`() {
        val f = Forecaster.compute(now, readings { 120.0 }, emptyList(), emptyList(), profile)
        near(0.0, f.cobUnits, what = "cob")
        near(0.0, f.unexplainedRate!!, what = "rate")
        near(0.0, f.unexplainedUnits, what = "units")
        assertEquals(7, f.readingsUsed)
        assertNull(f.note)
    }

    @Test
    fun `a rise nothing explains is carried forward 120 min and fades out`() {
        val f = Forecaster.compute(now, readings { m -> 200.0 - 2.0 * m }, emptyList(), emptyList(), profile)
        near(2.0, f.observedRate!!, what = "observed")
        near(2.0, f.unexplainedRate!!, what = "unexplained")
        near(120.0, f.unexplainedMgDl, what = "2 mg/dL/min × 120 / 2")
        near(4.8, f.unexplainedUnits, what = "120 ÷ ISF 25")
    }

    @Test
    fun `a logged meal is carbs on board, and its rise is explained`() {
        // 60 g eaten 70 min ago: (70 − 10) / 180 = 1/3 absorbed → 40 g still to come = 4 u at ICR 10.
        val meal = CarbEntry(60.0, min(70.0))
        // Over the last 30 min it absorbs 10 g → +25 mg/dL → 0.833 mg/dL/min.
        val f = Forecaster.compute(now, readings { m -> 180.0 - (25.0 / 30.0) * m }, emptyList(), listOf(meal), profile)
        near(40.0, f.carbsOnBoardG, 1e-9, "cob g")
        near(4.0, f.cobUnits, 1e-9, "cob u")
        near(25.0 / 30.0, f.carbRate!!, 1e-9, "carb rate")
        near(0.0, f.unexplainedRate!!, 1e-9, "nothing left unexplained")
    }

    @Test
    fun `a meal rising faster than modelled uses up its own carbs, not new insulin`() {
        // 60 g eaten 40 min ago: (40 − 10)/180 → 50 g on board; the model expects +0.83 mg/dL/min.
        val meal = CarbEntry(60.0, min(40.0))
        // BG rises 2.83/min: 2 mg/dL/min more → 60 mg/dL over 30 min = 24 g of the meal, early.
        val f = Forecaster.compute(now, readings { m -> 200.0 - (2.0 + 25.0 / 30.0) * m }, emptyList(), listOf(meal), profile)
        near(24.0, f.absorbedEarlyG, 1e-6, "absorbed early")
        near(26.0, f.carbsOnBoardG, 1e-6, "50 − 24 left")
        near(0.0, f.unexplainedRate!!, 1e-9, "nothing new")
        near(0.0, f.unexplainedUnits, 1e-9, "no extra insulin")

        // A rise beyond everything still on board is unexplained for the rest.
        val big = Forecaster.compute(now, readings { m -> 300.0 - (10.0 + 25.0 / 30.0) * m }, emptyList(), listOf(meal), profile)
        near(50.0, big.absorbedEarlyG, 1e-6, "all of it")
        near(0.0, big.carbsOnBoardG, 1e-6, "none left")
        near(10.0 - 125.0 / 30.0, big.unexplainedRate!!, 1e-6, "10 − 50 g × 2.5 / 30")
    }

    @Test
    fun `a fall the insulin on board explains is not unexplained`() {
        val dose = RapidDose(5.0, min(90.0))
        fun left(m: Double) = Iob.fractionSinceDose(90.0 - m, profile.iob)
        // BG falls exactly as fast as the insulin absorbs × ISF.
        val f = Forecaster.compute(now, readings { m -> 100.0 + 5.0 * 25.0 * left(m) }, listOf(dose), emptyList(), profile)
        assertTrue(f.insulinRate!! < -0.5, "insulin should be acting: ${f.insulinRate}")
        // Linear fit of a gently curved line: small residual only.
        assertTrue(abs(f.unexplainedRate!!) < 0.05, "unexplained ${f.unexplainedRate}")
    }

    @Test
    fun `old or missing readings keep carbs on board but no unexplained term`() {
        val meal = listOf(CarbEntry(30.0, min(10.0)))
        val stale = Forecaster.compute(now, listOf(GlucosePoint(min(40.0), 150.0), GlucosePoint(min(20.0), 150.0)), emptyList(), meal, profile)
        assertEquals("newest reading is 20 min old", stale.note)
        near(3.0, stale.cobUnits, what = "cob")
        near(0.0, stale.unexplainedUnits, what = "units")

        val few = Forecaster.compute(now, listOf(GlucosePoint(min(4.0), 150.0), GlucosePoint(min(1.0), 151.0)), emptyList(), emptyList(), profile)
        assertEquals("only 2 readings in the last 30 min", few.note)
        assertEquals("no CGM readings", Forecaster.compute(now, emptyList(), emptyList(), emptyList(), profile).note)
    }

    @Test
    fun `switched off, the forecast is nothing`() {
        val off = profile.copy(forecast = ForecastSettings(enabled = false))
        val f = Forecaster.compute(now, readings { m -> 200.0 - 3 * m }, emptyList(), listOf(CarbEntry(60.0, min(30.0))), off)
        assertEquals(Forecast.NONE, f)
    }

    @Test
    fun `the forecast terms are part of the dose math and the breakdown`() {
        val r = DoseEngine.compute(DoseInput(bg = 100.0, iob = 4.0, cobUnits = 3.0, unexplainedUnits = 1.5), profile)
        near(0.5, r.baseline, 1e-9, "0 − 4 + 3 + 1.5")
        assertEquals(1, r.finalUnits)
        near(3.0, r.cobUnits, what = "cob in result")
        near(1.5, r.unexplainedUnits, what = "unexplained in result")
    }

    @Test
    fun `insulin on board meant for food being absorbed no longer reads as carbs to eat`() {
        // Danny's old settings: ISF 40, ICR 9, target 110. BG 160 with 5 u on board.
        val p = profile.copy(dose = DoseSettings(icr = 9.0, isf = 40.0, target = 110.0))
        fun action(input: DoseInput) = NextBestActions.decide(input, DoseEngine.compute(input, p), p, 3, false)
        val iobOnly = action(DoseInput(bg = 160.0, trendRate = 1.0, iob = 5.0))
        assertEquals(ActionKind.EAT_CARBS, iobOnly.kind, iobOnly.sentence)

        // Rising 3 mg/dL/min that nothing logged explains → +180 mg/dL carried → +4.5 u.
        val rising = Forecaster.compute(now, readings { m -> 160.0 - 3 * m }, emptyList(), emptyList(), p)
        val a = action(DoseInput(bg = 160.0, trendRate = 3.0, iob = 5.0, unexplainedUnits = rising.unexplainedUnits))
        assertEquals(ActionKind.TAKE_INSULIN, a.kind, a.sentence)
        assertEquals("Take 1 u", a.headline)
        assertTrue(a.detail.contains("rising more than logged food explains"), a.detail)
        assertNotNull(a.why.firstOrNull { it.startsWith("Rising faster than logged insulin and food explain") }, a.why.toString())
    }

    @Test
    fun `forecast settings are validated`() {
        val bad = profile.copy(forecast = ForecastSettings(carbAbsorptionMin = 0.0, lookbackMin = 2.0, minReadings = 1, carryMin = -1.0))
        val problems = ProfileValidation.problems(bad)
        assertEquals(4, problems.count { it.startsWith("forecast.") }, problems.toString())
    }
}
