package app.meanwhile.domain.profile

import app.meanwhile.domain.dose.DoseEngine
import app.meanwhile.domain.dose.DoseInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileValidationTest {

    @Test
    fun `default profile is valid`() {
        assertEquals(emptyList(), ProfileValidation.problems(Profile()))
    }

    @Test
    fun `zero or negative divisors are reported`() {
        val p = Profile(dose = DoseSettings(icr = 0.0, isf = -25.0, unitIncrement = 0.0))
        val problems = ProfileValidation.problems(p)
        assertTrue(problems.any { it.startsWith("dose.icr") }, problems.toString())
        assertTrue(problems.any { it.startsWith("dose.isf") }, problems.toString())
        assertTrue(problems.any { it.startsWith("dose.unitIncrement") }, problems.toString())
    }

    @Test
    fun `insulin peak must be under half the duration`() {
        val p = Profile(iob = IobCurve(peakMin = 160.0, durationMin = 300.0))
        assertTrue(ProfileValidation.problems(p).any { it.startsWith("iob.peakMin") })
        assertEquals(emptyList(), ProfileValidation.problems(Profile(iob = IobCurve(peakMin = 149.0, durationMin = 300.0))))
    }

    @Test
    fun `hours and fractions are range-checked, other values are not capped`() {
        assertTrue(ProfileValidation.problems(Profile(resetHour = 24)).any { it.startsWith("resetHour") })
        assertTrue(ProfileValidation.problems(Profile(split = SplitRules(firstFraction = 1.5))).isNotEmpty())
        // Not designed for caution: unusual but computable values are fine.
        assertEquals(emptyList(), ProfileValidation.problems(Profile(dose = DoseSettings(icr = 1.0, isf = 400.0, combinedCap = 10.0))))
    }

    @Test
    fun `invalid profile gives no dose instead of a silent 0`() {
        val r = DoseEngine.compute(DoseInput(carbsG = 60.0, bg = 150.0), Profile(dose = DoseSettings(icr = 0.0)))
        assertEquals(0, r.finalUnits)
        assertNull(r.split)
        assertNull(r.suggestedCarbsG)
        assertTrue(r.profileProblems.any { it.startsWith("dose.icr") })
        assertTrue(r.warnings.first().startsWith("No dose"))
    }

    @Test
    fun `valid profile has no problems on the result`() {
        val r = DoseEngine.compute(DoseInput(carbsG = 60.0, bg = 150.0), Profile())
        assertEquals(emptyList(), r.profileProblems)
        assertTrue(r.finalUnits > 0)
    }
}
