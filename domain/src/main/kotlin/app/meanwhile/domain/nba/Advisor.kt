package app.meanwhile.domain.nba

import app.meanwhile.domain.cgm.Trend
import app.meanwhile.domain.profile.Profile
import kotlin.math.roundToInt

enum class AlertKind { LOW, CARBS, CORRECTION }

/** A notification the app sends on its own: what the dose math says to do right now (2.0). */
data class Alert(val kind: AlertKind, val title: String, val text: String, val action: NextBestAction)

/**
 * When to speak up without being asked. Deterministic and offline: it only ever repeats the Next Best
 * Action the dose math gives for "no food, right now", and only when that action matters — a low now or
 * coming, carbs because insulin on board will take BG low while it's falling, or a correction once BG is
 * high and the last dose has had time to work. Each kind repeats at most every `alerts.repeatMin`.
 */
object Advisor {

    fun check(
        action: NextBestAction,
        bg: Double?,
        trend: Double?,
        bgStale: Boolean,
        minutesSinceRapid: Double?,
        lastSentEpochMillis: Map<AlertKind, Long>,
        nowEpochMillis: Long,
        profile: Profile,
    ): Alert? {
        val a = profile.alerts
        if (!a.enabled || bgStale || bg == null) return null
        val reading = "BG ${bg.roundToInt()}${trend?.let { " " + Trend.arrow(it) } ?: ""}"
        val alert = when (action.kind) {
            ActionKind.TREAT_LOW -> if (a.lows) Alert(AlertKind.LOW, action.headline, "$reading · ${action.detail}", action) else null
            ActionKind.EAT_CARBS -> {
                val projected = action.projectedBg
                if (a.carbs && projected != null && projected < a.carbsProjectedBelowMgDl && (trend ?: 0.0) < 0) {
                    Alert(AlertKind.CARBS, action.headline, "$reading · ${action.detail}", action)
                } else {
                    null
                }
            }
            ActionKind.TAKE_INSULIN -> {
                val waited = minutesSinceRapid == null || minutesSinceRapid >= a.correctionMinMinutesSinceDose
                if (a.corrections && bg >= a.correctionAboveMgDl && action.unitsNow >= a.correctionMinUnits && waited) {
                    Alert(AlertKind.CORRECTION, "$reading — ${action.headline.replaceFirstChar { it.lowercase() }}", action.detail, action)
                } else {
                    null
                }
            }
            else -> null
        } ?: return null
        val last = lastSentEpochMillis[alert.kind]
        if (last != null && nowEpochMillis - last < a.repeatMin * 60_000L) return null
        return alert
    }
}
