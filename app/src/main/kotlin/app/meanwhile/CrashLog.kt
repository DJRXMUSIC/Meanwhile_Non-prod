package app.meanwhile

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant

/**
 * Private crash reporting with zero setup: an uncaught exception is written to a local file before
 * the process dies, and turned into a `feedback` row on the next start — so it syncs, exports and
 * shows up like any note. No third-party service involved.
 */
object CrashLog {
    private const val FILE = "last-crash.txt"
    private const val MAX_CHARS = 6000

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val text = "Crash ${Instant.now()} on ${thread.name}\n${Log.getStackTraceString(e)}"
                File(context.filesDir, FILE).writeText(text.take(MAX_CHARS))
            } catch (_: Exception) {
                // Never make a crash worse.
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** The previous run's crash, if there was one; the file is consumed. */
    fun takePending(context: Context): String? {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrNull()
        f.delete()
        return text?.takeIf { it.isNotBlank() }
    }
}
