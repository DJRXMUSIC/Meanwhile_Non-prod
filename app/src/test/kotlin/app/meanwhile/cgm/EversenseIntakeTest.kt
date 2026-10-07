package app.meanwhile.cgm

import android.app.Notification
import android.os.Bundle
import android.widget.RemoteViews
import app.meanwhile.data.cgm.CgmFeedStatus
import app.meanwhile.data.cgm.CgmIntake
import app.meanwhile.data.cgm.CompanionNotification
import app.meanwhile.data.cgm.EversenseSource
import app.meanwhile.data.cgm.NotificationTexts
import app.meanwhile.data.cgm.XdripBroadcastSource
import app.meanwhile.data.cgm.XdripWebSource
import app.meanwhile.domain.cgm.CgmReading
import app.meanwhile.domain.cgm.EversenseNotification
import app.meanwhile.domain.cgm.ReadingGate
import app.meanwhile.testing.TestEnv
import app.meanwhile.ui.setup.SetupState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Duration
import java.time.Instant

/** The built-in Eversense interceptor from notification object to stored reading. */
@RunWith(RobolectricTestRunner::class)
class EversenseIntakeTest {
    private val env = TestEnv()
    private val status = MutableStateFlow(CgmFeedStatus())
    private val intake = CgmIntake(
        env.cgm, XdripWebSource(OkHttpClient(), env.settings), XdripBroadcastSource(env.context), env.settings, status,
        EversenseSource(), webExpected = { env.settings.current().customWebSource },
    )
    private val t0: Instant = Instant.ofEpochMilli((Instant.now().minus(Duration.ofHours(3)).toEpochMilli() / 1000) * 1000)
    private fun at(min: Double): Instant = t0.plusMillis((min * 60_000).toLong())
    private fun shown(min: Double, vararg texts: String) =
        CompanionNotification("com.senseonics.eversense365.us", texts.toList(), emptyList(), at(min))

    @After fun tearDown() = env.close()

    private suspend fun stored() = env.cgm.recent(t0.minus(Duration.ofHours(1)))

    @Test
    fun `standard notification fields are read`() {
        val n = Notification().apply {
            extras = Bundle().apply {
                putCharSequence(Notification.EXTRA_TITLE, "123 mg/dL")
                putCharSequence(Notification.EXTRA_TEXT, "Eversense")
            }
        }
        val seen = NotificationTexts.extract(env.context, n, "com.senseonics.gen12androidapp", at(0.0))
        assertEquals(listOf("123 mg/dL", "Eversense"), seen.texts)
        assertEquals(EversenseNotification.Result.Reading(123, null), EversenseNotification.parse(seen.texts, seen.descriptions))
    }

    @Test
    @Suppress("DEPRECATION")
    fun `a custom notification layout is read, including the trend arrow's description`() {
        val layout = RemoteViews(env.context.packageName, android.R.layout.activity_list_item).apply {
            setTextViewText(android.R.id.text1, "187")
            setContentDescription(android.R.id.icon, "Rising rapidly")
        }
        val n = Notification().apply {
            extras = Bundle()
            contentView = layout
        }
        val seen = NotificationTexts.extract(env.context, n, "com.senseonics.gen12androidapp", at(0.0))
        assertTrue(seen.texts.toString(), "187" in seen.texts)
        assertTrue(seen.descriptions.toString(), "Rising rapidly" in seen.descriptions)
        assertEquals(EversenseNotification.Result.Reading(187, "SingleUp"), EversenseNotification.parse(seen.texts, seen.descriptions))
    }

    @Test
    fun `a reading is stored once, re-posts are skipped, the next reading counts`() = runBlocking {
        assertTrue(intake.acceptEversense(shown(0.0, "123", "mg/dL")))
        assertFalse("re-post of the same reading", intake.acceptEversense(shown(1.0, "123", "mg/dL")))
        assertTrue(intake.acceptEversense(shown(5.0, "127 ↗", "mg/dL")))
        assertTrue("same value, next reading", intake.acceptEversense(shown(10.0, "127", "mg/dL")))
        val readings = stored()
        assertEquals(listOf(123, 127, 127), readings.map { it.mgDl })
        assertTrue(readings.all { it.source == EversenseNotification.SOURCE })
        assertEquals("FortyFiveUp", readings[1].direction)
        assertEquals(at(5.0), readings[1].timestamp)
        assertTrue(status.value.eversenseMessage!!, status.value.eversenseMessage!!.startsWith("127 mg/dL"))
    }

    @Test
    fun `a reading xDrip already delivered is not stored twice, in either order`() = runBlocking {
        env.cgm.save(listOf(CgmReading(at(0.0), 120, null, "Flat", "xdrip_web")))
        assertFalse(intake.acceptEversense(shown(0.5, "121")))
        assertTrue(intake.acceptEversense(shown(5.0, "125")))
        assertEquals("xDrip back-fill of the same reading", 0, env.cgm.save(listOf(CgmReading(at(5.0).minusSeconds(40), 124, null, null, "xdrip_web"))))
        assertEquals(1, env.cgm.save(listOf(CgmReading(at(10.0), 130, null, null, "xdrip_web"))))
        assertEquals(listOf(120, 125, 130), stored().map { it.mgDl })
    }

    @Test
    fun `a stuck value stops being stored until it changes`() = runBlocking {
        repeat(ReadingGate.MAX_IDENTICAL) { assertTrue(intake.acceptEversense(shown(it * 5.0, "110"))) }
        assertFalse(intake.acceptEversense(shown(35.0, "110")))
        assertTrue(status.value.eversenseMessage!!, "stuck" in status.value.eversenseMessage!!)
        assertTrue(intake.acceptEversense(shown(40.0, "112")))
    }

    @Test
    fun `an unknown notification is reported with what was seen`() = runBlocking {
        assertFalse(intake.acceptEversense(shown(0.0, "Sensor warm-up", "12 hours remaining")))
        assertTrue(status.value.eversenseMessage!!.startsWith("Not a reading"))
        assertEquals("Sensor warm-up | 12 hours remaining", status.value.eversenseTexts)
        assertTrue(stored().isEmpty())
    }

    @Test
    fun `a notification stamped in the future is stored at now`() = runBlocking {
        val now = at(0.0)
        intake.acceptEversense(CompanionNotification("com.senseonics.androidapp", listOf("140"), emptyList(), now.plusSeconds(600)), now = now)
        assertEquals(now, stored().single().timestamp)
    }

    @Test
    fun `Eversense access only counts as missing when the Eversense app is installed`() {
        val base = SetupState(notifications = true, batteryExempt = true, exactAlarms = true, microphone = true)
        assertEquals(0, base.copy(eversenseAccess = false, eversenseInstalled = false).missing)
        assertEquals(1, base.copy(eversenseAccess = false, eversenseInstalled = true).missing)
        assertEquals(0, base.copy(eversenseAccess = true, eversenseInstalled = true).missing)
    }

    @Test
    fun `with no local web service back-fill is skipped quietly`() = runBlocking {
        assertTrue("default address", !env.settings.current().customWebSource)
        assertEquals(0, intake.backCapture())
        assertTrue(status.value.lastBackCapture!!, status.value.lastBackCapture!!.startsWith("No back-fill"))
    }

    @Test
    fun `a custom local address counts as a CGM source without xDrip+`() = runBlocking {
        env.settings.update { it.copy(xdripBaseUrl = "http://127.0.0.1:9") } // nothing listens on port 9 (discard)
        assertTrue(env.settings.current().customWebSource)
        assertEquals(0, intake.backCapture())
        assertTrue(status.value.lastBackCapture!!, status.value.lastBackCapture!!.startsWith("Back-fill failed"))
    }
}
