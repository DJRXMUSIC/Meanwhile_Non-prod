package app.meanwhile.domain.iob

import app.meanwhile.domain.profile.IobCurve
import kotlin.test.Test
import kotlin.test.assertEquals

/** Spec §6 golden tests (tolerance ±0.01). */
class IobTest {
    private val curve = IobCurve()

    @Test fun fractionTable() {
        val expected = mapOf(0.0 to 1.00, 60.0 to 0.76, 75.0 to 0.67, 120.0 to 0.41, 180.0 to 0.16, 240.0 to 0.03, 300.0 to 0.00)
        expected.forEach { (t, f) -> assertEquals(f, Iob.fraction(t, curve.peakMin, curve.durationMin), 0.01, "t=$t") }
    }

    @Test fun fourUnits70MinAgo() {
        val now = 10_000_000_000L
        assertEquals(3.06, Iob.total(listOf(RapidDose(4.0, now - 70 * 60_000L)), now, curve), 0.01)
    }

    @Test fun twoDoses() {
        val now = 10_000_000_000L
        val doses = listOf(RapidDose(3.0, now - 120 * 60_000L), RapidDose(2.0, now - 30 * 60_000L))
        assertEquals(3.32, Iob.total(doses, now, curve), 0.01)
    }

    @Test fun delayKeepsFullDose() {
        val now = 10_000_000_000L
        assertEquals(5.0, Iob.total(listOf(RapidDose(5.0, now - 5 * 60_000L)), now, curve), 1e-9)
        assertEquals(0.0, Iob.total(listOf(RapidDose(5.0, now - 400 * 60_000L)), now, curve), 1e-9)
    }

    @Test fun activityIntegratesToOne() {
        var sum = 0.0
        var t = 0.0
        while (t < curve.durationMin) {
            sum += Iob.activity(t + 0.05, curve.peakMin, curve.durationMin) * 0.1
            t += 0.1
        }
        assertEquals(1.0, sum, 0.01)
    }
}
