package app.meanwhile.domain.factors

import app.meanwhile.domain.cgm.CgmReading
import app.meanwhile.domain.profile.ActiveFactor
import app.meanwhile.domain.profile.Profile
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FactorEngineTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(day: Int, hour: Int, minute: Int = 0): Instant = ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone).toInstant()
    private fun Instant.ms() = toEpochMilli()
    private fun weightOf(p: Profile, id: String, now: Instant, readings: List<CgmReading> = emptyList()) =
        FactorEngine.applied(p, now, zone, readings).firstOrNull { it.factorId == id }?.weight

    @Test fun alcoholDecaysHourlyThenStops() {
        val start = at(4, 20)
        val p = Profile(active = listOf(ActiveFactor("F5", 0.70, start.ms(), source = "offline")))
        assertEquals(0.70, weightOf(p, "F5", start.plusSeconds(30 * 60))!!, 1e-9)
        assertEquals(0.75, weightOf(p, "F5", start.plusSeconds(90 * 60))!!, 1e-9)
        assertEquals(0.80, weightOf(p, "F5", start.plusSeconds(150 * 60))!!, 1e-9)
        assertEquals(0.85, weightOf(p, "F5", start.plusSeconds(210 * 60))!!, 1e-9)
        assertNull(weightOf(p, "F5", start.plusSeconds(241 * 60)))
    }

    @Test fun newDrinkResetsNoStacking() {
        val p = Profile(
            active = listOf(
                ActiveFactor("F5", 0.70, at(4, 20).ms(), source = "offline"),
                ActiveFactor("F5", 0.70, at(4, 21, 30).ms(), source = "offline"),
            ),
        )
        val applied = FactorEngine.applied(p, at(4, 21, 45), zone, emptyList()).filter { it.factorId == "F5" }
        assertEquals(1, applied.size)
        assertEquals(0.70, applied[0].weight, 1e-9)
    }

    @Test fun aiInitialWeightScalesDecay() {
        val start = at(4, 20)
        val p = Profile(active = listOf(ActiveFactor("F5", 0.60, start.ms(), source = "ai")))
        assertEquals(1 - 0.4 * 0.25 / 0.30, weightOf(p, "F5", start.plusSeconds(90 * 60))!!, 1e-9)
    }

    @Test fun sleepResetsAt1am() {
        val p = Profile(active = listOf(ActiveFactor("F8", 1.25, at(4, 7).ms(), source = "morning_report")))
        assertEquals(1.25, weightOf(p, "F8", at(4, 23, 59))!!, 1e-9)
        assertEquals(1.25, weightOf(p, "F8", at(5, 0, 59))!!, 1e-9)
        assertNull(weightOf(p, "F8", at(5, 1, 0)))
    }

    @Test fun exerciseSurvivesResetFor24h() {
        val p = Profile(active = listOf(ActiveFactor("F7", 0.85, at(4, 18).ms(), source = "offline")))
        assertEquals(0.85, weightOf(p, "F7", at(5, 2))!!, 1e-9)
        assertEquals(0.85, weightOf(p, "F7", at(5, 17, 59))!!, 1e-9)
        assertNull(weightOf(p, "F7", at(5, 18, 1)))
    }

    @Test fun recentHypoFor12hAfterLastLow() {
        val readings = listOf(
            CgmReading(at(4, 8), 64, null, null, "t"),
            CgmReading(at(4, 9), 120, null, null, "t"),
        )
        val p = Profile()
        assertEquals(0.80, weightOf(p, "F10", at(4, 19, 59), readings)!!, 1e-9)
        assertNull(weightOf(p, "F10", at(4, 20, 1), readings))
    }

    @Test fun pendingCaffeineConsumedByDoseAndReset() {
        val p = Profile()
        val events = listOf(
            PendingUnitsEvent("F4", 1.0, 1.0, at(4, 7).ms()),
            PendingUnitsEvent("F4", 2.0, 2.0, at(4, 9).ms()),
        )
        assertEquals(3.0, FactorEngine.pendingUnits(p, at(4, 10), zone, events, null).sumOf { it.units })
        assertEquals(2.0, FactorEngine.pendingUnits(p, at(4, 10), zone, events, at(4, 8).ms()).sumOf { it.units })
        val yesterday = listOf(PendingUnitsEvent("F4", 1.0, 1.0, at(4, 0, 30).ms()))
        assertTrue(FactorEngine.pendingUnits(p, at(4, 2), zone, yesterday, null).isEmpty())
    }

    private fun night(highHours: Double): List<CgmReading> {
        val start = at(3, 22)
        val highUntil = start.plusSeconds((highHours * 3600).toLong())
        return (0 until 8 * 12).map { i ->
            val t = start.plus(Duration.ofMinutes(5L * i))
            CgmReading(t, if (t.isBefore(highUntil)) 220 else 140, null, null, "t")
        }
    }

    @Test fun overnightHighs() {
        val p = Profile()
        val six = at(4, 6)
        assertEquals(1.0, FactorEngine.overnightHighs(p, six, zone, night(2.0)).weight, 1e-9)
        assertEquals(1.15, FactorEngine.overnightHighs(p, six, zone, night(3.0)).weight, 1e-9)
        assertEquals(1.20, FactorEngine.overnightHighs(p, six, zone, night(4.0)).weight, 1e-9)
        assertEquals(1.25, FactorEngine.overnightHighs(p, six, zone, night(6.0)).weight, 1e-9)
        assertEquals(4.0, FactorEngine.overnightHighs(p, six, zone, night(4.0)).hours, 1e-9)
    }

    @Test fun overnightGapsAreNotCounted() {
        val p = Profile()
        val sparse = listOf(CgmReading(at(3, 23), 250, null, null, "t"), CgmReading(at(4, 5), 250, null, null, "t"))
        assertEquals(0.5, FactorEngine.overnightHighs(p, at(4, 6), zone, sparse).hours, 1e-9)
    }

    @Test fun pruneDropsExpired() {
        val p = Profile(
            active = listOf(
                ActiveFactor("F8", 1.25, at(3, 7).ms(), source = "morning_report"),
                ActiveFactor("F7", 0.85, at(4, 6).ms(), source = "offline"),
            ),
        )
        assertEquals(listOf("F7"), FactorEngine.prune(p, at(4, 9), zone).active.map { it.factorId })
    }
}
