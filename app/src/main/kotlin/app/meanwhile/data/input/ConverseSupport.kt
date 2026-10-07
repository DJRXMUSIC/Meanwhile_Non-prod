package app.meanwhile.data.input

import app.meanwhile.data.json.AppJson
import app.meanwhile.domain.nba.NextBestAction
import app.meanwhile.domain.router.RouteResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.Locale

/**
 * What the AI said back (1.4 conversation): [reply] in words, and [route] — what to act on — when it
 * read the message itself (null when the app had already acted and only wanted a reply).
 */
data class ConverseResult(val reply: String, val route: RouteResult?, val model: String?)

/** The live state the AI talks about: BG, trend, insulin on board, factors, and what the math says now. */
fun NowState.toJson(): JsonObject = buildJsonObject {
    put("bg", context.latest?.mgDl)
    put("trend_mg_dl_per_min", context.trendRate?.let { String.format(Locale.US, "%.1f", it).toDouble() })
    put("bg_age_min", context.bgAgeMinutes)
    put("bg_stale", bgStale)
    put("iob_u", String.format(Locale.US, "%.2f", context.iob).toDouble())
    put("last_rapid_dose_at", context.lastRapidDoseAt?.let { app.meanwhile.data.json.isoOf(it) })
    putJsonArray("active_factors") {
        context.applied.forEach { f ->
            addJsonObject {
                put("name", f.name)
                put("weight", f.weight)
                f.expiresAt?.let { put("until", app.meanwhile.data.json.isoOf(it)) }
            }
        }
    }
    putJsonArray("pending_units") {
        context.pendingUnits.forEach { p -> addJsonObject { put("name", p.name); put("units", p.units) } }
    }
    put("forecast", AppJson.encodeToJsonElement(app.meanwhile.domain.forecast.Forecast.serializer(), context.forecast))
    put("current_action", AppJson.encodeToJsonElement(NextBestAction.serializer(), action))
    val d = context.profile.profile.dose
    put("profile", buildJsonObject { put("icr", d.icr); put("isf", d.isf); put("target", d.target); put("version", context.profile.versionLabel) })
}
