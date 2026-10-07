package app.meanwhile.data.input

import app.meanwhile.data.settings.SettingsStore
import app.meanwhile.domain.nba.Advisor
import app.meanwhile.domain.nba.Alert
import app.meanwhile.domain.nba.AlertKind
import app.meanwhile.log.AppLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/**
 * Speaks up on its own (2.0): after each new CGM reading, works out the Next Best Action for "no food,
 * right now" and, when [Advisor] says it matters, posts it as a notification and writes it to the
 * conversation log. Nothing is logged as a dose — Danny still logs what he does.
 */
class AdviceService(
    private val nba: NbaService,
    private val settings: SettingsStore,
    private val conversation: ConversationLog,
    private val post: (Alert) -> Unit,
) {
    private val lock = Mutex()

    /** Returns the alert that was sent, if any. */
    suspend fun check(now: Instant = Instant.now()): Alert? = lock.withLock {
        try {
            val state = nba.preview(now)
            val ctx = state.context
            val last = AlertKind.entries.mapNotNull { k -> settings.marker(marker(k))?.toLongOrNull()?.let { k to it } }.toMap()
            val sinceRapid = ctx.lastRapidDoseAt?.let { (now.toEpochMilli() - it) / 60_000.0 }
            val alert = Advisor.check(
                state.action, state.input.bg, state.input.trendRate, state.bgStale, sinceRapid, last, now.toEpochMilli(), ctx.profile.profile,
            ) ?: return@withLock null
            settings.setMarker(marker(alert.kind), now.toEpochMilli().toString())
            post(alert)
            conversation.notification(
                "${alert.title} — ${alert.text}",
                buildJsonObject {
                    put("source", "advice")
                    put("alert", alert.kind.name)
                    put("action", alert.action.kind.name)
                    alert.action.projectedBg?.let { put("projected_bg", it) }
                    put("iob_u", ctx.iob)
                    put("cob_u", ctx.forecast.cobUnits)
                    put("unexplained_u", ctx.forecast.unexplainedUnits)
                    put("profile", ctx.profile.versionLabel)
                },
                at = now,
            )
            AppLog.i("Advice", "${alert.kind}: ${alert.title}")
            alert
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLog.e("Advice", "check failed: ${e.message}", e)
            null
        }
    }

    private fun marker(kind: AlertKind) = "advice_last_${kind.name.lowercase()}"
}
