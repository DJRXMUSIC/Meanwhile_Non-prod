package app.meanwhile.diag

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.meanwhile.di.AppContainer
import app.meanwhile.log.AppLog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DiagnosticsTest {

    @Test
    fun `report has every section, the grouped problems with stack traces, and no secrets`() = runBlocking {
        val app: Application = ApplicationProvider.getApplicationContext()
        AppLog.init(File(app.cacheDir, "logs-test").apply { deleteRecursively() })
        AppLog.i("App", "process start")
        repeat(3) { AppLog.w("Sync", "sync failed (retrying automatically): HTTP 503 attempt $it", RuntimeException("upstream 503")) }
        AppLog.e("Auth", "token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.c2lnbmF0dXJlLXNpZ25hdHVyZQ for danny@example.com, apikey=AIzaSyA1234567890abcdefghijklmnopqrstu")

        val report = AppContainer(app).diagnostics.report()

        for (section in listOf("# MeanwhileV4 diagnostics", "For the AI assistant", "## App & device", "## Health", "## Problems in the last 72 h",
            "## Crashes", "## AI-call failures", "## Learning", "## Database", "## Background markers", "## Log tail")) {
            assertTrue("missing $section", report.contains(section))
        }
        assertTrue(report.contains("3× W/Sync: sync failed (retrying automatically)"))
        assertTrue(report.contains("RuntimeException: upstream 503"))
        assertTrue(report.contains("| learning_log |"))
        // Redaction: no token, email or API key survives.
        assertFalse(report.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertFalse(report.contains("danny@example.com"))
        assertFalse(report.contains("AIzaSyA1234567890"))
        assertTrue(report.contains("[redacted-token]"))
        assertTrue(report.contains("[email]"))
    }

    @Test
    fun `redact catches common secret shapes`() {
        assertEquals("Bearer [redacted]", Diagnostics.redact("Bearer abc.def.ghi"))
        assertEquals("api-secret: [redacted]", Diagnostics.redact("api-secret: 5f4dcc3b5aa765d61d8327deb882cf99"))
        assertEquals("key [redacted-key]", Diagnostics.redact("key sk-ant-api03-abcdefghijklmnop"))
        assertEquals("plain text 123", Diagnostics.redact("plain text 123"))
    }
}
