package app.meanwhile.importer

import app.meanwhile.data.importer.HistoryImporter
import app.meanwhile.testing.TestEnv
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration

/** 2.0: the old app's export comes in append-only, once, without double-counting insulin. Synthetic data only. */
@RunWith(RobolectricTestRunner::class)
class HistoryImportTest {
    private val env = TestEnv()
    @After fun tearDown() = env.close()

    private val importer = HistoryImporter(env.db, env.records, env.cgm, env.conversation, env.onWrite)
    private val t0 = env.day(3).plus(Duration.ofHours(8)).toEpochMilli()
    private fun t(min: Int) = t0 + min * 60_000L

    private fun export() = """
        {"bg":[
          {"ts":${t(0)},"mgdl":143,"trend":"Flat","delta":0,"source":"xdrip","raw":{"_id":"x","sgv":143,"nested":{"a":[1,2]}},"id":1},
          {"ts":${t(5)},"mgdl":150,"trend":"FortyFiveUp","source":"xdrip","id":2},
          {"ts":${t(10)},"mgdl":0,"source":"xdrip","id":3},
          {"ts":${t(15)},"mgdl":158,"source":"xdrip","id":4}
        ],
        "insulin":[
          {"ts":${t(2)},"units":3,"kind":"bolus","source":"quick","note":"quick 3U","id":1},
          {"ts":${t(60)},"units":18,"kind":"basal","source":"manual","id":2},
          {"ts":${t(300)},"units":2,"kind":"bolus","source":"quick","id":3}
        ],
        "decisions":[],"context":[],
        "profile":[{"id":"current","ic_ratio":9,"isf":40,"dia_hours":6,"peak_min":75,"delay_min":15,"target_bg":110,"theme":"dark","daily_basal_enabled":true}],
        "exported_at":${t(400)}}
    """.trimIndent()

    private suspend fun run() = importer.import(export().byteInputStream())

    @Test
    fun `readings and doses come in, the raw copies are skipped, bad values dropped`() = runBlocking {
        val r = run()
        assertEquals("the reading with mgdl 0 is dropped", 3, r.readingsRead)
        assertEquals(3, r.readingsAdded)
        assertEquals(3, r.dosesRead)
        assertEquals(3, r.dosesAdded)
        assertEquals(t(0), r.firstAt)

        val readings = env.db.cgm().between(0, Long.MAX_VALUE)
        assertEquals(listOf(143, 150, 158), readings.map { it.mgDl })
        assertTrue(readings.all { it.source == "import:xdrip" })
        val doses = env.db.doses().effectiveSince(0)
        assertEquals(listOf("rapid" to 3.0, "long" to 18.0, "rapid" to 2.0), doses.map { it.insulin to it.units })
        assertTrue(doses.first().details, doses.first().details.contains("\"source\":\"import\""))
        assertTrue(env.db.conversation().since(0).any { it.kind == "action" && it.text.startsWith("Imported history") })
    }

    @Test
    fun `importing the same file twice adds nothing`() = runBlocking {
        run()
        val again = run()
        assertEquals(0, again.readingsAdded)
        assertEquals(0, again.dosesAdded)
        assertEquals(3, env.db.doses().effectiveSince(0).size)
    }

    @Test
    fun `doses from after this app's first dose are not imported twice`() = runBlocking {
        // Danny already logged here at t(200): the old app's dose at t(300) is skipped.
        env.nba.logStated(4.0, "rapid", t(200), null, now = java.time.Instant.ofEpochMilli(t(200)))
        val r = run()
        assertEquals(2, r.dosesAdded)
        assertEquals(1, r.dosesSkippedOverlap)
        assertEquals(listOf(3.0, 18.0, 4.0), env.db.doses().effectiveSince(0).map { it.units })
        // Imported doses don't move the cutoff for the next import.
        assertEquals(t(200), env.db.doses().earliestGivenAt())
    }

    @Test
    fun `the old app's settings are not imported`() = runBlocking {
        val before = env.profiles.current()
        run()
        val after = env.profiles.current()
        assertEquals(before.profile, after.profile)
        assertEquals(before.versionLabel, after.versionLabel)
    }
}
