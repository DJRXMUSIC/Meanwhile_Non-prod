package app.meanwhile.diag

import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.core.content.FileProvider
import app.meanwhile.BuildConfig
import app.meanwhile.data.remote.AuthState
import app.meanwhile.data.learn.NightlyJobs
import app.meanwhile.di.AppContainer
import app.meanwhile.domain.profile.ProfileValidation
import app.meanwhile.log.AppLog
import app.meanwhile.ui.setup.SetupState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * One Markdown report with everything an AI coding assistant needs to debug the app: build and
 * device, the health of every moving part, problems grouped with stack traces, recent AI failures,
 * database counts and the log tail. No keys, tokens, passwords or email addresses (and a redaction
 * pass catches anything that slipped into a log line).
 */
class Diagnostics(private val c: AppContainer) {

    suspend fun report(now: Instant = Instant.now(), tailLines: Int = 300): String = withContext(Dispatchers.IO) {
        val text = buildString {
            header(now)
            appAndDevice(now)
            health(now)
            problems(now)
            crashes()
            aiFailures(now)
            learning(now)
            database()
            markers()
            logTail(tailLines)
        }
        redact(text).let { if (it.length > MAX_CHARS) it.take(MAX_CHARS) + "\n\n… (truncated)\n" else it }
    }

    /** Writes the full report to a shareable file. */
    suspend fun writeReport(): File = withContext(Dispatchers.IO) {
        val dir = File(c.app.cacheDir, "exports").apply { mkdirs() }
        val stamp = Instant.now().toString().replace(":", "").substringBefore('.')
        File(dir, "meanwhile-diagnostics-$stamp.md").apply { writeText(report()) }
    }

    fun shareIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(c.app, "${c.app.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "MeanwhileV4 diagnostics")
            putExtra(Intent.EXTRA_TEXT, "MeanwhileV4 diagnostics report attached — please diagnose the problems and propose fixes.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share diagnostics")
    }

    /** Warnings and errors in the last [hours] (Settings badge). */
    fun problemCount(hours: Long = 24): Int {
        val since = Instant.now().minus(Duration.ofHours(hours))
        return AppLog.entries().count { it.isProblem && it.at.isAfter(since) }
    }

    // --- sections ----------------------------------------------------------------------------------

    private fun StringBuilder.header(now: Instant) {
        appendLine("# MeanwhileV4 diagnostics")
        appendLine()
        appendLine("> **For the AI assistant:** this report comes from MeanwhileV4, a personal Android app (Kotlin, Jetpack")
        appendLine("> Compose, Room, WorkManager, Supabase; deterministic dose math in the pure-Kotlin `domain/` module).")
        appendLine("> Source: https://github.com/DJRXMUSIC/Meanwhile_Non-prod (branch `claude/hopeful-galileo-pgut04`,")
        appendLine("> commit `${BuildConfig.GIT_SHA.ifBlank { "unknown (local build)" }}`). Read `CLAUDE.md`, `docs/SPEC.md`,")
        appendLine("> `docs/DECISIONS.md` and `docs/TESTING.md` first. Below: build and device, the health of each part,")
        appendLine("> problems grouped with stack traces, recent AI-call failures, database counts and the log tail.")
        appendLine("> Find the root cause, propose a minimal fix, and add a test that would have caught it.")
        appendLine()
        appendLine("Generated ${now} (phone time zone ${ZoneId.systemDefault().id}).")
        appendLine()
    }

    private fun StringBuilder.appAndDevice(now: Instant) {
        val uptimeMin = (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 60_000
        appendLine("## App & device")
        appendLine("| | |")
        appendLine("|---|---|")
        appendLine("| Version | ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE}), commit ${BuildConfig.GIT_SHA.ifBlank { "local" }} |")
        appendLine("| Build | ${BuildConfig.BUILD_TYPE}; cloud ${if (c.supabase != null) "configured" else "not configured (local only)"} |")
        appendLine("| Device | ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) |")
        appendLine("| Locale / zone | ${Locale.getDefault()} / ${ZoneId.systemDefault().id} |")
        appendLine("| Process up | ${uptimeMin / 60} h ${uptimeMin % 60} min |")
        appendLine()
    }

    private suspend fun StringBuilder.health(now: Instant) {
        val setup = SetupState.read(c.app)
        val sync = c.settings.syncStatus.first()
        val pending = c.db.sync().pendingCount().first()
        val rejected = c.db.sync().rejectedCount().first()
        val latest = c.cgm.latestNow()
        val feed = c.cgmStatus.value
        val ai = c.ai.status.value
        val profile = c.profiles.current()
        val problems = ProfileValidation.problems(profile.profile)
        fun ago(ms: Long?) = ms?.let { "${Duration.between(Instant.ofEpochMilli(it), now).toMinutes()} min ago" } ?: "never"

        appendLine("## Health")
        appendLine("- **Permissions:** notifications ${tick(setup.notifications)}, unrestricted battery ${tick(setup.batteryExempt)}, exact alarms ${tick(setup.exactAlarms)}, microphone ${tick(setup.microphone)}")
        appendLine("- **Network:** ${if (c.network.online.value) "online" else "offline"}")
        appendLine("- **CGM:** latest reading ${latest?.let { ago(it.timestamp.toEpochMilli()) } ?: "none"}; xDrip+ web " +
            when (feed.webOk) { true -> "ok"; false -> "failing (${feed.webMessage})"; null -> "not checked yet" } +
            "; last broadcast ${ago(feed.lastBroadcastAt)}; ${feed.lastBackCapture ?: "no back-fill yet"}")
        val auth = when (val a = c.auth.state.value) {
            is AuthState.SignedIn -> "signed in (user ${a.userId.take(8)}…)" + if (a.offline) ", session offline" else ""
            AuthState.NotConfigured -> "not configured in this build"
            AuthState.SignedOut -> "signed out"
            AuthState.Loading -> "loading"
        }
        appendLine("- **Cloud sync:** $auth; last success ${ago(sync.lastSuccessAt)}; $pending pending, $rejected rejected" +
            (sync.failingSince?.let { "; failing since ${ago(it)}: ${sync.lastError}" } ?: ""))
        appendLine("- **AI:** reachable ${tick(c.ai.reachable())}; preference ${c.settings.current().aiProvider.wire}; last ok ${ago(ai.lastOkAt)}" +
            (ai.lastError?.let { "; last error ${ago(ai.lastErrorAt)}: $it" } ?: ""))
        appendLine("- **Profile:** ${profile.versionLabel}" + (profile.version?.let { " (${it.source}, ${ago(it.createdAt)})" } ?: "") +
            if (problems.isEmpty()) "; valid" else "; **INVALID:** ${problems.joinToString("; ")}")
        appendLine()
    }

    private fun StringBuilder.problems(now: Instant) {
        val since = now.minus(Duration.ofHours(72))
        val list = AppLog.entries().filter { it.isProblem && it.at.isAfter(since) }
        appendLine("## Problems in the last 72 h (${list.size} warnings/errors)")
        if (list.isEmpty()) {
            appendLine("None logged.")
            appendLine()
            return
        }
        // Group repeats: same level/tag/message once numbers are ignored.
        val groups = list.groupBy { "${it.level.code}/${it.tag} " + it.message.replace(Regex("\\d+"), "#") }
            .entries.sortedByDescending { g -> g.value.maxOf { it.at } }
        for ((_, entries) in groups.take(25)) {
            val last = entries.maxBy { it.at }
            appendLine("### ${entries.size}× ${last.level.code}/${last.tag}: ${last.message.take(200)}")
            appendLine("First ${entries.minOf { it.at }}, last ${last.at}.")
            entries.lastOrNull { it.detail.isNotEmpty() }?.let {
                appendLine("```")
                appendLine(it.detail.lines().take(30).joinToString("\n"))
                appendLine("```")
            }
            appendLine()
        }
    }

    private suspend fun StringBuilder.crashes() {
        val rows = c.db.feedback().byContext("crash", 3)
        appendLine("## Crashes (last ${rows.size})")
        if (rows.isEmpty()) appendLine("None recorded.")
        rows.forEach { f ->
            appendLine("```")
            appendLine(f.text.lines().take(40).joinToString("\n"))
            appendLine("```")
        }
        appendLine()
    }

    private suspend fun StringBuilder.aiFailures(now: Instant) {
        val failed = c.db.aiCalls().since(now.minus(Duration.ofDays(7)).toEpochMilli()).filter { it.validation != "ok" }.takeLast(20)
        appendLine("## AI-call failures, last 7 days (${failed.size})")
        if (failed.isEmpty()) {
            appendLine("None.")
        } else {
            appendLine("| time | job | provider/model | validation | error |")
            appendLine("|---|---|---|---|---|")
            failed.forEach { a ->
                appendLine("| ${Instant.ofEpochMilli(a.recordedAt)} | ${a.job} | ${a.provider ?: "—"}/${a.model ?: "—"} | ${a.validation} | ${(a.error ?: "").replace('|', '/').take(160)} |")
            }
        }
        appendLine()
    }

    private suspend fun StringBuilder.learning(now: Instant) {
        val st = runCatching { c.learning.status(now) }.getOrNull()
        appendLine("## Learning")
        if (st == null) {
            appendLine("Status unavailable.")
            appendLine()
            return
        }
        appendLine("Autonomy ${st.autonomy.wire}; ${st.lessons.size} lessons (${st.lessons.count { it.clean }} clean); " +
            "last local check ${st.lastLocalRunAt?.let { Instant.ofEpochMilli(it) } ?: "never"}; last AI review ${st.lastAiReviewAt?.let { Instant.ofEpochMilli(it) } ?: "never"}.")
        st.evidence.forEach { e -> appendLine("- ${e.path}: current ${e.current}, implied ${e.implied?.let { String.format(Locale.US, "%.2f", it) } ?: "—"} from ${e.lessons} lessons (${e.freshSinceChange} new)") }
        st.underEvaluation.forEach { o -> appendLine("- under evaluation: ${o.entry.summary.take(120)} — ${o.evaluation.reason}") }
        c.db.learningLog().since(now.minus(Duration.ofDays(3)).toEpochMilli()).takeLast(15).forEach { e ->
            appendLine("- ${Instant.ofEpochMilli(e.recordedAt)} ${e.kind}: ${e.summary.take(160)}")
        }
        appendLine()
    }

    private fun StringBuilder.database() {
        appendLine("## Database")
        appendLine("| table | rows | unsynced | rejected |")
        appendLine("|---|---|---|---|")
        val db = c.db.openHelper.readableDatabase
        for (t in SYNCED_TABLES) {
            runCatching {
                db.query("SELECT COUNT(*), SUM(CASE WHEN syncState = 0 THEN 1 ELSE 0 END), SUM(CASE WHEN syncState = 2 THEN 1 ELSE 0 END) FROM $t").use { cur ->
                    cur.moveToFirst()
                    appendLine("| $t | ${cur.getLong(0)} | ${cur.getLong(1)} | ${cur.getLong(2)} |")
                }
            }.onFailure { appendLine("| $t | error: ${it.message} | | |") }
        }
        runCatching {
            db.query("SELECT COUNT(*) FROM ai_queue").use { cur -> cur.moveToFirst(); appendLine("| ai_queue (local) | ${cur.getLong(0)} | | |") }
        }
        appendLine()
    }

    private suspend fun StringBuilder.markers() {
        appendLine("## Background markers")
        for (k in listOf(NightlyJobs.KEY_LEARN_DONE, NightlyJobs.KEY_F11_DONE, NightlyJobs.KEY_MORNING_SEEN)) {
            appendLine("- $k: ${c.settings.marker(k) ?: "—"}")
        }
        appendLine()
    }

    private fun StringBuilder.logTail(n: Int) {
        val tail = AppLog.entries().takeLast(n)
        appendLine("## Log tail (last ${tail.size} entries)")
        appendLine("```")
        tail.forEach { appendLine(it.toString()) }
        appendLine("```")
    }

    companion object {
        const val MAX_CHARS = 400_000

        val SYNCED_TABLES = listOf(
            "cgm_readings", "meals", "factor_events", "doses", "proposals", "outcomes", "profile_versions",
            "factor_definitions", "ai_calls", "feedback", "inputs", "learning_log",
        )

        private fun tick(ok: Boolean) = if (ok) "✓" else "✗"

        private val JWT = Regex("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}")
        private val GOOGLE_KEY = Regex("AIza[0-9A-Za-z_-]{30,}")
        private val ANTHROPIC_KEY = Regex("sk-ant-[A-Za-z0-9_-]{10,}")
        private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        private val BEARER = Regex("(?i)(bearer|api-secret|apikey|api_key|password)([\"'=: ]+)[^\\s\"',]+")

        /** Last line of defence: nothing secret leaves the phone in a report. */
        fun redact(text: String): String = text
            .replace(JWT, "[redacted-token]")
            .replace(GOOGLE_KEY, "[redacted-key]")
            .replace(ANTHROPIC_KEY, "[redacted-key]")
            .replace(EMAIL, "[email]")
            .replace(BEARER) { m -> "${m.groupValues[1]}${m.groupValues[2]}[redacted]" }
    }
}
