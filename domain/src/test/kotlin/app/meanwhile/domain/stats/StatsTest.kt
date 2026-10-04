package app.meanwhile.domain.stats

import app.meanwhile.domain.cgm.CgmReading
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatsTest {
    private val t0 = Instant.parse("2026-10-04T00:00:00Z")
    private fun r(min: Long, mg: Int) = CgmReading(t0.plus(Duration.ofMinutes(min)), mg, null, null, "t")

    @Test fun timeWeightedRanges() {
        // 6 h at 120 (in range), 2 h at 200, 1 h at 60, 1 h at 300 → 10 h total
        val readings = (0 until 120).map { i ->
            val min = i * 5L
            r(min, when { min < 360 -> 120; min < 480 -> 200; min < 540 -> 60; else -> 300 })
        }
        val s = GlucoseStats.summarize(readings, t0, t0.plus(Duration.ofHours(10)))
        assertEquals(60.0, s.timeInRangePct, 1e-6)
        assertEquals(30.0, s.timeAbove180Pct, 1e-6)
        assertEquals(10.0, s.timeAbove250Pct, 1e-6)
        assertEquals(10.0, s.timeBelow70Pct, 1e-6)
        assertEquals(600.0, s.coveredMinutes, 1e-6)
    }

    @Test fun gapsAreNotInvented() {
        val s = GlucoseStats.summarize(listOf(r(0, 100), r(120, 100)), t0, t0.plus(Duration.ofHours(3)))
        assertEquals(30.0, s.coveredMinutes, 1e-6)
        assertEquals(100.0, s.timeInRangePct, 1e-6)
    }

    @Test fun outcomes() {
        val readings = (0..60).map { i -> r(i * 5L, 100 + i) } // rising 1 mg/dL per 5 min
        val o = Outcomes.compute(t0, readings)
        assertEquals(124, o.bg2h)
        assertEquals(136, o.bg3h)
        assertEquals(148, o.bg4h)
        assertEquals(101, o.min4h)
        assertEquals(148, o.max4h)
        assertNull(Outcomes.compute(t0, listOf(r(0, 100))).bg2h)
        assertTrue(Outcomes.ready(t0, t0.plus(Duration.ofMinutes(251))))
        assertFalse(Outcomes.ready(t0, t0.plus(Duration.ofMinutes(200))))
    }
}
