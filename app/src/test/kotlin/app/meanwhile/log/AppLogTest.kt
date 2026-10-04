package app.meanwhile.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Plain JVM: the log must survive restarts, keep stack traces, rotate, and parse back losslessly. */
class AppLogTest {
    private lateinit var dir: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("applog").toFile()
        AppLog.init(dir)
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `entries round-trip with level, tag and stack trace`() {
        AppLog.i("Sync", "sync recovered")
        AppLog.e("AI", "learn_cycle failed: timeout", IllegalStateException("boom"))
        val entries = AppLog.entries()
        assertEquals(2, entries.size)
        assertEquals(AppLog.Level.INFO, entries[0].level)
        assertEquals("Sync", entries[0].tag)
        val err = entries[1]
        assertTrue(err.isProblem)
        assertEquals("learn_cycle failed: timeout", err.message)
        assertTrue(err.detail, err.detail.contains("IllegalStateException: boom"))
        assertTrue(err.detail.lines().all { it.startsWith("    ") })
    }

    @Test
    fun `log survives a restart (re-init reads the same files)`() {
        AppLog.w("CGM", "xDrip+ unreachable")
        AppLog.init(dir)
        assertEquals("xDrip+ unreachable", AppLog.entries().single().message)
    }

    @Test
    fun `multi-line messages stay one entry`() {
        AppLog.w("X", "line one\nline two")
        assertEquals(1, AppLog.entries().size)
    }

    @Test
    fun `rotation keeps the previous file and bounds disk use`() {
        val chunk = "x".repeat(2000)
        repeat(900) { AppLog.i("Fill", "$it $chunk") } // ~1.8 MB written
        val files = dir.listFiles()!!.map { it.name }.toSet()
        assertEquals(setOf("app.log", "app.1.log"), files)
        assertTrue(dir.listFiles()!!.sumOf { it.length() } < 2 * AppLog.MAX_FILE_BYTES + 10_000)
        val entries = AppLog.entries()
        assertTrue(entries.last().message.startsWith("899 "))
        assertTrue(entries.size in 300..900)
    }

    @Test
    fun `throttle lets a repeating problem through once per interval`() {
        assertTrue(AppLog.throttle("k", 1000, nowMs = 0))
        assertFalse(AppLog.throttle("k", 1000, nowMs = 500))
        assertTrue(AppLog.throttle("k", 1000, nowMs = 1500))
        AppLog.clearThrottle("k")
        assertTrue(AppLog.throttle("k", 1000, nowMs = 1600))
    }

    @Test
    fun `parse ignores junk and keeps timestamps`() {
        val text = "garbage line\n2026-10-05T10:00:00Z E/T [main] bad\n    at x.y(Z.kt:1)\n2026-10-05T10:00:01Z I/T [main] ok\n"
        val e = AppLog.parse(text)
        assertEquals(2, e.size)
        assertEquals("    at x.y(Z.kt:1)", e[0].detail)
        assertEquals("2026-10-05T10:00:01Z", e[1].at.toString())
    }
}
