package app.meanwhile.domain.nba

import app.meanwhile.domain.cgm.Trend
import app.meanwhile.domain.dose.DoseInput
import app.meanwhile.domain.dose.DoseResult
import app.meanwhile.domain.profile.Profile
import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** What the Next Best Action asks Danny to do (1.4: not always a bolus). */
@Serializable
enum class ActionKind {
    /** Insulin now (meal and/or correction), then eat after the lead time. */
    TAKE_INSULIN,

    /** A split dose: part now, the rest after the meal has started. */
    SPLIT_INSULIN,

    /** BG is low, or falling into a low: fast carbs, no insulin, recheck. */
    TREAT_LOW,

    /** Insulin on board will take BG below target: eat carbs, no insulin. */
    EAT_CARBS,

    /** A meal whose dose rounds to 0 u: eat it, no insulin. */
    EAT_NO_INSULIN,

    /** No meal and nothing to correct. */
    NOTHING,

    /** No CGM reading and no dose to give: a BG is needed first. */
    CHECK_BG,
}

/**
 * One clear action, decided by code from the dose engine's result: the headline Danny reads, the
 * numbers behind it, and [why] — the decision path, shown when he asks "Why?" next to the full
 * breakdown. Every line is deterministic; the AI never decides an action.
 */
@Serializable
data class NextBestAction(
    val kind: ActionKind,
    /** "Take 6 u", "Eat 15 g fast carbs", "Nothing to do now". */
    val headline: String,
    /** "then eat in 12 min", "No insulin · recheck in 15 min". */
    val detail: String,
    /** Units to inject now (0 = no insulin). */
    val unitsNow: Int = 0,
    /** A split's second injection. */
    val unitsLater: Int = 0,
    /** Minutes after starting the meal for the second injection. */
    val laterAfterMin: Int? = null,
    /** Carbs to eat on top of the described meal (all of them when there is no meal). */
    val carbsG: Int? = null,
    /** Minutes from injecting to eating, when there is a meal and insulin. */
    val eatInMin: Int? = null,
    /** Minutes until a BG recheck is worth doing (after treating a low). */
    val recheckInMin: Int? = null,
    /** Where BG lands with the meal eaten and no more insulin (mg/dL), when BG is known. */
    val projectedBg: Int? = null,
    val why: List<String> = emptyList(),
) {
    val insulin: Boolean get() = unitsNow > 0 || unitsLater > 0

    /** The whole action as one line ("Take 6 u — then eat in 12 min"). */
    val sentence: String get() = if (detail.isBlank()) headline else "$headline — $detail"
}

object NextBestActions {

    fun decide(
        input: DoseInput,
        result: DoseResult,
        profile: Profile,
        bgAgeMinutes: Long? = null,
        bgStale: Boolean = false,
    ): NextBestAction {
        val s = profile.nba
        val bg = input.bg
        val trend = input.trendRate
        val hasMeal = input.carbsG > 0 || input.fatG > 0 || input.proteinG > 0
        val units = result.finalUnits
        val lowLine = s.lowBelowMgDl.roundToInt()
        val soon = if (bg != null && trend != null) bg + trend * s.predictMinutes else null
        val low = bg != null && bg < s.lowBelowMgDl
        val goingLow = !low && soon != null && soon < s.lowBelowMgDl
        val projected = bg?.let { (profile.dose.target + result.baseline * profile.dose.isf).roundToInt() }
        val suggested = result.suggestedCarbsG ?: 0

        val why = mutableListOf<String>()
        why += when {
            bg == null -> "No BG reading — the correction isn't included."
            else -> buildString {
                append("BG ${bg.roundToInt()}${arrow(trend)}")
                trend?.let { append(" (${signed(it)}/min)") }
                bgAgeMinutes?.let { append(", $it min old") }
                if (bgStale) append(" — stale, check it before dosing")
            }
        }
        if (hasMeal) why += "Meal: ${g(input.carbsG)} g carbs, ${g(input.fatG)} g fat, ${g(input.proteinG)} g protein."
        if (input.iob >= 0.05) why += "Insulin on board: ${fmt(input.iob)} u."
        if (abs(input.cobUnits) >= 0.05) why += "Carbs still absorbing from logged meals: +${fmt(input.cobUnits)} u."
        if (abs(input.unexplainedUnits) >= 0.05) why += if (input.unexplainedUnits > 0) {
            "Rising faster than logged insulin and food explain: +${fmt(input.unexplainedUnits)} u."
        } else {
            "Falling faster than logged insulin and food explain: ${fmt(input.unexplainedUnits)} u."
        }
        why += "Dose math: ${fmt(result.raw)} u → ${if (units == 0 && result.raw < 0) "0 u (below zero)" else "rounds to $units u"}."

        fun recheck() = if (s.recheckMin > 0) " · recheck in ${s.recheckMin} min" else ""

        val action = when {
            units == 0 && (low || goingLow) -> {
                val needed = ceil(max(s.lowTreatCarbsG, suggested.toDouble())).toInt()
                val extra = if (hasMeal) max(0, needed - floor(input.carbsG).toInt()) else needed
                val reason = if (low) "BG ${bg!!.roundToInt()} is low" else
                    "falling ${signed(trend!!)}/min — about ${soon!!.roundToInt()} in ${s.predictMinutes} min"
                why += if (low) "BG is below $lowLine → treat the low: ${g(s.lowTreatCarbsG)} g fast carbs" +
                    (if (suggested > s.lowTreatCarbsG) ", or $suggested g to cover insulin on board" else "") + "." else
                    "The trend reaches $lowLine within ${s.predictMinutes} min → treat it now: ${g(s.lowTreatCarbsG)} g fast carbs" +
                    (if (suggested > s.lowTreatCarbsG) ", or $suggested g to cover insulin on board" else "") + "."
                when {
                    !hasMeal -> NextBestAction(ActionKind.TREAT_LOW, "Eat $extra g fast carbs", "No insulin · $reason${recheck()}", carbsG = extra)
                    extra == 0 -> NextBestAction(ActionKind.TREAT_LOW, "Eat now — no insulin", "$reason · the meal covers it${recheck()}", carbsG = 0)
                    else -> NextBestAction(ActionKind.TREAT_LOW, "Eat now, plus $extra g fast carbs", "No insulin · $reason${recheck()}", carbsG = extra)
                }.copy(recheckInMin = s.recheckMin.takeIf { it > 0 })
            }
            units == 0 && bg == null -> {
                why += "Without a BG the math can't tell whether insulin on board needs carbs or a correction."
                NextBestAction(ActionKind.CHECK_BG, "Check your BG", "No CGM reading — tell me your BG (e.g. “BG 140”) and I'll redo this")
            }
            units == 0 && suggested >= s.minCarbsG -> {
                why += "Insulin on board covers ${if (hasMeal) "this meal and more" else "more than your BG needs"}: " +
                    "${fmt(-result.raw)} u extra × ICR ${fmt(profile.dose.icr)} = $suggested g carbs to stay on target."
                NextBestAction(
                    ActionKind.EAT_CARBS,
                    if (hasMeal) "Eat it, plus ~$suggested g carbs" else "Eat ~$suggested g carbs",
                    "No insulin · insulin on board would take you to ~${projected ?: "?"}",
                    carbsG = suggested,
                )
            }
            units == 0 && hasMeal -> {
                why += if (input.iob >= 0.5 && result.raw < 0.5) "Insulin on board covers it." else "It needs less than half a unit."
                NextBestAction(
                    ActionKind.EAT_NO_INSULIN, "Eat — no insulin needed",
                    if (input.iob >= 0.5) "Insulin on board covers it" else "Needs only ${fmt(max(0.0, result.raw))} u — rounds to 0",
                )
            }
            units == 0 -> {
                if (suggested > 0) why += "Slightly below target (~$suggested g carbs) — under the ${g(s.minCarbsG)} g worth acting on."
                NextBestAction(ActionKind.NOTHING, "Nothing to do now", "BG ${bg!!.roundToInt()}${arrow(trend)} · on track")
            }
            result.split != null -> {
                val sp = result.split
                result.leadTimeSteps.takeIf { it.isNotEmpty() }?.let { why += "Lead time: " + it.joinToString("; ") + "." }
                why += "Split for a high fat + protein meal: ${sp.firstUnits} u now, ${sp.secondUnits} u ${sp.secondAfterMin} min into the meal."
                NextBestAction(
                    ActionKind.SPLIT_INSULIN, "Take ${sp.firstUnits} u now",
                    "${eat(result.leadTimeMin)} · ${sp.secondUnits} u more ${sp.secondAfterMin} min after you start",
                    unitsNow = sp.firstUnits, unitsLater = sp.secondUnits, laterAfterMin = sp.secondAfterMin, eatInMin = result.leadTimeMin,
                )
            }
            else -> {
                if (hasMeal) result.leadTimeSteps.takeIf { it.isNotEmpty() }?.let { why += "Lead time: " + it.joinToString("; ") + "." }
                if (low) why += "BG is below $lowLine — eat as soon as you inject."
                val detail = if (hasMeal) eat(result.leadTimeMin) else buildList {
                    if (result.correction > 0) add("correction")
                    if (result.cobUnits >= 0.5) add("carbs still absorbing")
                    if (result.unexplainedUnits >= 0.5) add("rising more than logged food explains")
                    result.pendingUnits.forEach { add(it.name.lowercase(Locale.US)) }
                }.joinToString(" + ").ifEmpty { "insulin" } + " — no food needed"
                NextBestAction(
                    ActionKind.TAKE_INSULIN, "Take $units u",
                    if (bg == null) "$detail · no BG, correction not included" else detail,
                    unitsNow = units, eatInMin = if (hasMeal) result.leadTimeMin else null,
                )
            }
        }
        if (projected != null && (hasMeal || action.insulin || result.raw < 0)) {
            why += "Without more insulin you'd land around $projected mg/dL."
        }
        return action.copy(projectedBg = projected, why = why)
    }

    private fun arrow(trend: Double?) = trend?.let { " " + Trend.arrow(it) } ?: ""

    private fun eat(leadMin: Int?) = when (leadMin) {
        null, 0 -> "and eat now"
        else -> "then eat in $leadMin min"
    }

    private fun g(x: Double) = if (x == floor(x)) x.toLong().toString() else String.format(Locale.US, "%.1f", x)
    private fun fmt(x: Double) = String.format(Locale.US, "%.1f", x).removeSuffix(".0")
    private fun signed(x: Double) = (if (x >= 0) "+" else "") + String.format(Locale.US, "%.1f", x)
}
