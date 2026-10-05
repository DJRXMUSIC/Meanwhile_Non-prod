package app.meanwhile.domain.cgm

import app.meanwhile.domain.cgm.EversenseNotification.Result.Reading
import app.meanwhile.domain.cgm.EversenseNotification.Result.Rejected
import app.meanwhile.domain.cgm.ReadingGate.Decision.Accept
import app.meanwhile.domain.cgm.ReadingGate.Decision.Skip
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EversenseInterceptorTest {
    private fun parse(vararg texts: String, descriptions: List<String> = emptyList()) =
        EversenseNotification.parse(texts.toList(), descriptions)

    private fun reading(vararg texts: String, descriptions: List<String> = emptyList()) =
        assertIs<Reading>(parse(*texts, descriptions = descriptions))

    private fun rejected(vararg texts: String) = assertIs<Rejected>(parse(*texts))

    // --- parsing ---------------------------------------------------------------------------------

    @Test
    fun aBareNumberIsTheReading() {
        assertEquals(Reading(123, null), reading("123"))
        assertEquals(123, reading("Eversense", "123", "mg/dL", "5:32 PM").mgDl)
    }

    @Test
    fun unitsSpacingAndInvisibleCharactersAreIgnored() {
        assertEquals(123, reading("123 mg/dL").mgDl)
        assertEquals(98, reading("⁠ 98 ​").mgDl)
        assertEquals(250, reading("250 MG/DL").mgDl)
    }

    @Test
    fun theSameValueInTitleAndLayoutCountsOnce() {
        assertEquals(140, reading("140 mg/dL", "140", "Glucose").mgDl)
    }

    @Test
    fun arrowsGiveTheDirection() {
        assertEquals(Reading(180, "FortyFiveUp"), reading("180 ↗"))
        assertEquals(Reading(180, "SingleDown"), reading("180", "↓"))
        assertEquals(Reading(95, "Flat"), reading("95→"))
        assertEquals(Reading(60, "DoubleDown"), reading("⇊ 60"))
    }

    @Test
    fun trendDescriptionsGiveTheDirectionWhenTheArrowIsAnImage() {
        assertEquals("SingleUp", reading("200", descriptions = listOf("Trend: rising rapidly")).direction)
        assertEquals("FortyFiveDown", reading("130", descriptions = listOf("Falling")).direction)
        assertEquals("Flat", reading("110", descriptions = listOf("Stable")).direction)
    }

    @Test
    fun mmolIsConverted() {
        assertEquals(123, reading("6.8", "mmol/L").mgDl)
        assertEquals(126, reading("7,0 mmol/l").mgDl)
    }

    @Test
    fun alertsPredictionsAndTimesAreNotReadings() {
        rejected("Low Glucose Alert", "Glucose is 65 mg/dL")
        rejected("Predicted low in 20 minutes")
        rejected("12:45", "Transmitter battery 85%")
        rejected()
    }

    @Test
    fun twoDifferentNumbersAreAmbiguous() {
        val r = rejected("123", "85")
        assertTrue("more than one value" in r.reason, r.reason)
    }

    @Test
    fun loHiAndOutOfRangeAreNotReadings() {
        assertTrue("LO" in rejected("LO").reason)
        assertTrue("HI" in rejected("hi").reason)
        assertTrue("outside" in rejected("39").reason)
        assertTrue("outside" in rejected("450").reason)
    }

    @Test
    fun eversensePackagesAreKnown() {
        assertTrue("com.senseonics.eversense365.us" in EversenseNotification.PACKAGES)
        assertTrue("com.senseonics.gen12androidapp" in EversenseNotification.PACKAGES)
    }

    // --- gate --------------------------------------------------------------------------------------

    private val t0 = Instant.parse("2026-10-04T12:00:00Z")
    private fun at(min: Double) = t0.plusMillis((min * 60_000).toLong())
    private fun r(min: Double, mg: Int, source: String = EversenseNotification.SOURCE) = CgmReading(at(min), mg, null, null, source)
    private fun decide(mg: Int, min: Double, recent: List<CgmReading>) =
        ReadingGate.decide(mg, at(min), EversenseNotification.SOURCE, recent)

    @Test
    fun aNewReadingIsAccepted() {
        assertEquals(Accept, decide(120, 0.0, emptyList()))
        assertEquals(Accept, decide(125, 5.0, listOf(r(0.0, 120))))
    }

    @Test
    fun aReadingXdripAlreadyDeliveredIsSkipped() {
        assertIs<Skip>(decide(121, 5.0, listOf(r(4.5, 120, "xdrip_web"))))
        assertIs<Skip>(decide(121, 5.0, listOf(r(6.5, 120, "xdrip_web"))))
        assertEquals(Accept, decide(121, 5.0, listOf(r(2.5, 120, "xdrip_web"))))
    }

    @Test
    fun aRepostOfTheSameValueIsSkippedButTheSameValueFiveMinutesLaterCounts() {
        assertIs<Skip>(decide(120, 3.0, listOf(r(0.0, 120))))
        assertEquals(Accept, decide(120, 5.0, listOf(r(0.0, 120))))
        assertEquals(Accept, decide(124, 3.0, listOf(r(0.0, 120))), "a different value is a new reading")
    }

    @Test
    fun aStuckValueStopsAfterSevenAndResumesWhenItChanges() {
        val seven = (0 until ReadingGate.MAX_IDENTICAL).map { r(it * 5.0, 110) }
        val stuck = assertIs<Skip>(decide(110, 35.0, seven))
        assertTrue(stuck.stuck)
        assertEquals(Accept, decide(111, 35.0, seven))
        val six = seven.drop(1)
        assertEquals(Accept, decide(110, 35.0, six))
        // Another source's identical values don't make Eversense's value stuck.
        assertEquals(Accept, decide(110, 35.0, six.map { it.copy(source = "xdrip_web") } + r(-5.0, 110)))
    }

    @Test
    fun windowsAreWhatTheDocsSay() {
        assertEquals(Duration.ofMinutes(2), ReadingGate.SAME_READING)
        assertEquals(Duration.ofSeconds(270), ReadingGate.REPOST)
    }
}
