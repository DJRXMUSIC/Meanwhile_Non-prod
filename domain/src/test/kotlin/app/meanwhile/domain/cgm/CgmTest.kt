package app.meanwhile.domain.cgm

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CgmTest {
    private val t0 = Instant.parse("2026-10-04T12:00:00Z")

    @Test
    fun parsesXdripSgvNewestFirstAndSortsAscending() {
        val body = """
            [{"_id":"a","device":"Eversense","dateString":"x","sysTime":"x","date":${t0.plusSeconds(300).toEpochMilli()},
              "sgv":130,"delta":5.0,"direction":"FortyFiveUp","noise":1,"units_hint":"mgdl","sensor_status":"ok"},
             {"date":${t0.toEpochMilli()},"sgv":125,"delta":-2.5,"direction":"Flat"},
             {"date":${t0.minusSeconds(300).toEpochMilli()},"sgv":"bad"},
             {"date":${t0.minusSeconds(600).toEpochMilli()},"sgv":5}]
        """.trimIndent()
        val r = XdripSgv.parse(body)
        assertEquals(listOf(125, 130), r.map { it.mgDl })
        assertEquals(1.0, r.last().trendRate)
        assertEquals("ok", r.last().sensorStatus)
        assertEquals("mgdl", XdripSgv.unitsHint(body))
    }

    @Test
    fun malformedBodyGivesEmptyList() {
        assertEquals(emptyList(), XdripSgv.parse("<html>"))
        assertEquals(emptyList(), XdripSgv.parse("{}"))
    }

    private fun r(minAgo: Long, mg: Int) = CgmReading(t0.minusSeconds(minAgo * 60), mg, null, null, "test")

    @Test
    fun trendIsRegressionSlope() {
        val rate = Trend.rate(listOf(r(10, 100), r(5, 110), r(0, 120)))!!
        assertEquals(2.0, rate, 1e-9)
        assertEquals("↑", Trend.arrow(rate))
    }

    @Test
    fun trendIgnoresOldReadingsAndFallsBack() {
        val onlyOne = listOf(r(0, 120).copy(trendRate = -1.2))
        assertEquals(-1.2, Trend.rate(onlyOne))
        val withGap = listOf(r(60, 300), r(0, 120).copy(trendRate = 0.4))
        assertEquals(0.4, Trend.rate(withGap))
        assertNull(Trend.rate(emptyList()))
    }

    @Test
    fun arrows() {
        assertEquals("⇊", Trend.arrow(-3.2))
        assertEquals("→", Trend.arrow(0.5))
        assertTrue(Trend.arrow(null) == "?")
    }
}
