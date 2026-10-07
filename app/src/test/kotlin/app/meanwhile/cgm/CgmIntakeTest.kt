package app.meanwhile.cgm

import app.meanwhile.data.cgm.CgmFeedStatus
import app.meanwhile.data.cgm.CgmIntake
import app.meanwhile.data.cgm.XdripBroadcastSource
import app.meanwhile.data.cgm.XdripWebSource
import app.meanwhile.domain.cgm.CgmReading
import app.meanwhile.testing.TestEnv
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant

/**
 * CGM integrity against a real local HTTP server standing in for xDrip+'s web service: broadcasts
 * are confirmed by the web service, spoofed ones are ignored, and the broadcast only counts when
 * the web service is unreachable.
 */
@RunWith(RobolectricTestRunner::class)
class CgmIntakeTest {
    private val env = TestEnv()
    private lateinit var server: HttpServer
    @Volatile private var body = "[]"
    @Volatile private var status = 200
    private lateinit var intake: CgmIntake
    private val t = Instant.now().minus(Duration.ofMinutes(3)).let { Instant.ofEpochMilli(it.toEpochMilli() / 1000 * 1000) }

    @Before fun setUp() = runBlocking {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/sgv.json") { ex ->
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(status, bytes.size.toLong().coerceAtLeast(1))
                ex.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) else it.write(' '.code) }
            }
            start()
        }
        env.settings.update { it.copy(xdripBaseUrl = "http://127.0.0.1:${server.address.port}") }
        intake = CgmIntake(env.cgm, XdripWebSource(OkHttpClient(), env.settings), XdripBroadcastSource(env.context), env.settings, MutableStateFlow(CgmFeedStatus()))
    }

    @After fun tearDown() {
        server.stop(0)
        env.close()
    }

    private fun broadcast(at: Instant, mg: Int) = CgmReading(at, mg, 0.0, "Flat", "xdrip_broadcast")
    private fun sgv(at: Instant, mg: Int) = """{"date": ${at.toEpochMilli()}, "sgv": $mg, "direction": "Flat"}"""

    @Test
    fun `a broadcast confirmed by the web service is saved with the web's value`() = runBlocking {
        body = "[${sgv(t, 142)}]"
        intake.acceptBroadcast(broadcast(t, 142))
        assertEquals(142, env.cgm.latestNow()!!.mgDl)
        assertEquals("xdrip_web", env.cgm.latestNow()!!.source)
    }

    @Test
    fun `a spoofed broadcast the web service doesn't know is ignored`() = runBlocking {
        body = "[${sgv(t.minusSeconds(300), 120)}]"
        intake.acceptBroadcast(broadcast(t, 400))
        val latest = env.cgm.latestNow()!!
        assertEquals(120, latest.mgDl) // the web's real reading was back-filled, the fake 400 never stored
    }

    @Test
    fun `with the web service down the broadcast is used`() = runBlocking {
        status = 500
        intake.acceptBroadcast(broadcast(t, 133))
        assertEquals(133, env.cgm.latestNow()!!.mgDl)
    }

    @Test
    fun `future-dated readings never become the latest BG`() = runBlocking {
        env.cgm.save(listOf(broadcast(t, 110)))
        env.cgm.save(listOf(broadcast(Instant.now().plus(Duration.ofHours(6)), 300)))
        assertEquals(110, env.cgm.latestNow()!!.mgDl)
    }

    @Test
    fun `an invalid xDrip address is a normal error, not a crash`() = runBlocking {
        assertNull(XdripWebSource.urlFor("127.0.0.1:17580", "/sgv.json"))
        assertNotNull(XdripWebSource.urlFor("http://127.0.0.1:17580/", "sgv.json"))
        env.settings.update { it.copy(xdripBaseUrl = "not a url") }
        // Falls back to the broadcast exactly like an unreachable service.
        intake.acceptBroadcast(broadcast(t, 101))
        assertEquals(101, env.cgm.latestNow()!!.mgDl)
    }

    @Test
    fun `back-fill reads any local app that serves xDrip-style readings`() = runBlocking {
        // The test server is "Danny's own app" at a custom address: no xDrip+ involved.
        assertTrue(env.settings.current().customWebSource)
        body = "[${sgv(t.minusSeconds(600), 118)}, ${sgv(t.minusSeconds(300), 121)}]"
        assertEquals(2, intake.backCapture())
        assertEquals(121, env.cgm.latestNow()!!.mgDl)
    }
}
