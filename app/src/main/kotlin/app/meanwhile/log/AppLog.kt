package app.meanwhile.log

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The app's own log: every line goes to logcat AND to a rotating file under `filesDir/logs`, so it
 * survives process death and can be shared (Settings → Diagnostics). Two files of up to
 * [MAX_FILE_BYTES] each ≈ several days of normal use.
 *
 * Line format (one entry; stack traces follow on lines indented with 4 spaces):
 * `2026-10-05T14:03:00.123Z W/Sync [worker-3] message`
 */
object AppLog {
    enum class Level(val code: Char, val priority: Int) {
        DEBUG('D', Log.DEBUG), INFO('I', Log.INFO), WARN('W', Log.WARN), ERROR('E', Log.ERROR),
    }

    data class Entry(val at: Instant, val level: Level, val tag: String, val thread: String, val message: String, val detail: String) {
        val isProblem: Boolean get() = level == Level.WARN || level == Level.ERROR
        override fun toString(): String = buildString {
            append(at).append(' ').append(level.code).append('/').append(tag).append(" [").append(thread).append("] ").append(message)
            if (detail.isNotEmpty()) append('\n').append(detail)
        }
    }

    const val MAX_FILE_BYTES = 768 * 1024L
    private const val CURRENT = "app.log"
    private const val PREVIOUS = "app.1.log"
    private const val MAX_STACK_LINES = 40

    private val lock = Any()
    @Volatile private var dir: File? = null
    private val lastThrottled = ConcurrentHashMap<String, Long>()
    private val ENTRY_START = Regex("^(\\d{4}-\\d{2}-\\d{2}T\\S+) ([DIWE])/(\\S+) \\[([^\\]]*)] ?(.*)$")

    fun init(context: Context) = init(File(context.filesDir, "logs"))

    /** Test hook: log into any directory. */
    fun init(directory: File) {
        directory.mkdirs()
        dir = directory
    }

    fun d(tag: String, message: String) = write(Level.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = write(Level.INFO, tag, message, null)
    fun w(tag: String, message: String, t: Throwable? = null) = write(Level.WARN, tag, message, t)
    fun e(tag: String, message: String, t: Throwable? = null) = write(Level.ERROR, tag, message, t)

    /**
     * True at most once per [intervalMs] for [key] — for things that can fail every minute
     * (xDrip+ polling) so the log shows the problem without drowning in it.
     */
    fun throttle(key: String, intervalMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        val last = lastThrottled[key]
        if (last != null && nowMs - last < intervalMs) return false
        lastThrottled[key] = nowMs
        return true
    }

    fun clearThrottle(key: String) {
        lastThrottled.remove(key)
    }

    private fun write(level: Level, tag: String, message: String, t: Throwable?) {
        val detail = t?.let { stack(it) }.orEmpty()
        val entry = Entry(Instant.now(), level, tag, Thread.currentThread().name, message.replace('\n', ' '), detail)
        try {
            Log.println(level.priority, "Meanwhile/$tag", if (detail.isEmpty()) message else "$message\n$detail")
        } catch (_: RuntimeException) {
            // Plain JVM unit tests have no logcat.
        }
        val d = dir ?: return
        synchronized(lock) {
            try {
                val file = File(d, CURRENT)
                if (file.length() > MAX_FILE_BYTES) {
                    val prev = File(d, PREVIOUS)
                    prev.delete()
                    file.renameTo(prev)
                }
                File(d, CURRENT).appendText(entry.toString() + "\n")
            } catch (_: Exception) {
                // Logging must never break the app.
            }
        }
    }

    private fun stack(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        val lines = sw.toString().trimEnd().lines()
        val kept = lines.take(MAX_STACK_LINES) + if (lines.size > MAX_STACK_LINES) listOf("… ${lines.size - MAX_STACK_LINES} more lines") else emptyList()
        return kept.joinToString("\n") { "    $it" }
    }

    /** Every entry on disk, oldest first (both files). */
    fun entries(): List<Entry> {
        val d = dir ?: return emptyList()
        val text = synchronized(lock) {
            listOf(PREVIOUS, CURRENT).joinToString("") { name -> File(d, name).takeIf { it.exists() }?.readText().orEmpty() }
        }
        return parse(text)
    }

    fun parse(text: String): List<Entry> {
        val out = mutableListOf<Entry>()
        var head: MatchResult? = null
        val detail = StringBuilder()
        fun flush() {
            val h = head ?: return
            val at = runCatching { Instant.parse(h.groupValues[1]) }.getOrNull() ?: return
            val level = Level.entries.first { it.code == h.groupValues[2][0] }
            out += Entry(at, level, h.groupValues[3], h.groupValues[4], h.groupValues[5], detail.toString().trimEnd())
        }
        for (line in text.lineSequence()) {
            val m = ENTRY_START.find(line)
            if (m != null) {
                flush()
                head = m
                detail.setLength(0)
            } else if (head != null && line.isNotEmpty()) {
                if (detail.isNotEmpty()) detail.append('\n')
                detail.append(line)
            }
        }
        flush()
        return out
    }

    /** Raw log text (both files), for attaching to an export. */
    fun rawText(): String = entries().joinToString("\n")
}
