package app.meanwhile.domain.dose

import app.meanwhile.domain.profile.Profile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spec §5.5 golden tests — must pass in CI. Settings as §4; IOB given directly. */
class DoseEngineGoldenTest {
    private val profile = Profile()
    private val coffee = PendingUnits("F4", "Caffeine", units = 1.0, amount = 1.0)
    private fun w(id: String, name: String, weight: Double) = FactorWeight(id, name, weight)

    private fun check(
        input: DoseInput,
        baseline: Double,
        combined: Double,
        raw: Double,
        final: Int,
    ): DoseResult {
        val r = DoseEngine.compute(input, profile)
        assertEquals(baseline, r.baseline, 0.0005, "baseline")
        assertEquals(combined, r.combined, 0.0005, "combined")
        assertEquals(raw, r.raw, 0.0005, "raw")
        assertEquals(final, r.finalUnits, "final")
        return r
    }

    @Test fun g1_60gCarbs_bg100() {
        check(DoseInput(carbsG = 60.0, bg = 100.0, iob = 0.0), 6.0, 1.0, 6.0, 6)
    }

    @Test fun g2_60gCarbs_bg200() {
        check(DoseInput(carbsG = 60.0, bg = 200.0), 10.0, 1.0, 10.0, 10)
    }

    @Test fun g3_60gCarbs_bg150_iob15_halfRoundsUp() {
        check(DoseInput(carbsG = 60.0, bg = 150.0, iob = 1.5), 6.5, 1.0, 6.5, 7)
    }

    @Test fun g4_coffee_and_poorSleep() {
        check(
            DoseInput(carbsG = 50.0, bg = 100.0, factors = listOf(w("F8", "Sleep", 1.25)), pendingUnits = listOf(coffee)),
            5.0, 1.25, 7.25, 7,
        )
    }

    @Test fun g5_sunburn_and_freshAlcohol_addNotMultiply() {
        check(
            DoseInput(carbsG = 40.0, bg = 100.0, factors = listOf(w("F12", "Sunburn", 2.0), w("F5", "Alcohol", 0.70))),
            4.0, 1.70, 6.80, 7,
        )
    }

    @Test fun g6_fatProtein_split() {
        val r = check(DoseInput(carbsG = 80.0, fatG = 40.0, proteinG = 25.0, bg = 100.0), 8.0, 1.33, 10.64, 11)
        assertEquals(1.18, r.fatWeight, 1e-9)
        assertEquals(1.15, r.proteinWeight, 1e-9)
        assertEquals(SplitPlan(7, 4, 60, 0.60), r.split)
    }

    @Test fun g7_lowCarbMode_noSplit() {
        val r = check(DoseInput(carbsG = 5.0, fatG = 30.0, proteinG = 50.0, bg = 100.0), 5.227, 1.0, 5.227, 5)
        assertTrue(r.lowCarbMode)
        assertNull(r.split)
    }

    @Test fun g8_combinedCappedAt2() {
        val r = check(
            DoseInput(
                carbsG = 50.0, bg = 100.0,
                factors = listOf(w("F12", "Sunburn", 2.0), w("F8", "Sleep", 1.25), w("F9", "Stress", 1.20)),
            ),
            5.0, 2.0, 10.0, 10,
        )
        assertTrue(r.capped)
        assertEquals(2.45, r.combinedUncapped, 1e-9)
    }

    @Test fun g9_negativeRaw_suggestsCarbs() {
        val r = check(DoseInput(carbsG = 0.0, bg = 80.0, iob = 2.0), -2.8, 1.0, -2.8, 0)
        assertEquals(28, r.suggestedCarbsG)
    }

    @Test fun g10_coffeeOnly() {
        val r = check(DoseInput(bg = 100.0, pendingUnits = listOf(coffee)), 0.0, 1.0, 1.0, 1)
        assertNull(r.leadTimeMin)
        assertNull(r.suggestedCarbsG)
    }

    // --- lead time (spec §5.4) ---

    private fun lead(input: DoseInput) = DoseEngine.leadTime(input, profile).first

    @Test fun leadTimeRules() {
        assertEquals(12, lead(DoseInput(carbsG = 60.0, bg = 120.0, trendRate = 0.0)))
        assertEquals(0, lead(DoseInput(carbsG = 60.0, bg = 85.0)))
        assertEquals(0, lead(DoseInput(carbsG = 60.0, bg = 140.0, trendRate = -2.0)))
        assertEquals(12, lead(DoseInput(carbsG = 60.0, bg = 199.0)))
        assertEquals(17, lead(DoseInput(carbsG = 60.0, bg = 200.0)))
        assertEquals(27, lead(DoseInput(carbsG = 60.0, bg = 300.0)))
        assertEquals(17, lead(DoseInput(carbsG = 60.0, bg = 120.0, liquidOrSugary = true)))
        assertEquals(7, lead(DoseInput(carbsG = 60.0, fatG = 40.0, bg = 120.0)))
        assertEquals(2, lead(DoseInput(carbsG = 60.0, fatG = 45.0, bg = 120.0, factors = listOf(w("F7", "Exercise", 0.85)))))
        assertEquals(30, lead(DoseInput(carbsG = 60.0, bg = 450.0, liquidOrSugary = true)))
        assertEquals(12, lead(DoseInput(carbsG = 60.0, bg = null)))
    }

    @Test fun noBgOmitsCorrectionAndWarns() {
        val r = DoseEngine.compute(DoseInput(carbsG = 30.0), profile)
        assertEquals(3, r.finalUnits)
        assertFalse(r.warnings.isEmpty())
    }

    @Test fun negativeCombinedIsClampedNotFlipped() {
        val sensitive = listOf(w("F5", "Alcohol", 0.5), w("F7", "Exercise", 0.5), w("F10", "Hypo", 0.5))
        val r = DoseEngine.compute(DoseInput(carbsG = 0.0, bg = 60.0, iob = 1.0, factors = sensitive), profile)
        assertTrue(r.clampedAtZero)
        assertEquals(0, r.finalUnits)
    }
}
