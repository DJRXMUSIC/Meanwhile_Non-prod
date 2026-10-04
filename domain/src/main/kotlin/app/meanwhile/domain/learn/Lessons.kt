package app.meanwhile.domain.learn

import app.meanwhile.domain.dose.DoseInput
import app.meanwhile.domain.dose.DoseResult
import app.meanwhile.domain.profile.LearningSettings
import app.meanwhile.domain.profile.Profile
import app.meanwhile.domain.stats.DoseRow
import app.meanwhile.domain.stats.OutcomeRow
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

@Serializable
enum class LessonKind { MEAL, CORRECTION, UNITS_PER_EVENT, OTHER }

/** What the model knew and computed when it proposed a dose (from the stored proposal). */
data class ProposalFacts(
    val id: String,
    val atMillis: Long,
    val input: DoseInput,
    val result: DoseResult,
    /** The profile the proposal was computed with (its version, or the defaults). */
    val profile: Profile,
)

/** A logged meal; [proposalId] links it to the proposal it was eaten with. */
data class MealRow(val atMillis: Long, val proposalId: String?)

/**
 * One completed dose turned into a measured error: what was given, where BG ended up, and the units
 * that would have landed on target. `implied*` are the setting values this one outcome points to —
 * absolute numbers, so lessons stay valid evidence after the setting changes.
 */
@Serializable
data class Lesson(
    val proposalId: String,
    val doseId: String,
    val atMillis: Long,
    val localHour: Int,
    val kind: LessonKind,
    val carbsG: Double,
    val fatG: Double,
    val proteinG: Double,
    val bgAtDose: Double?,
    val target: Double,
    val unitsGiven: Double,
    /** The model's unrounded output. */
    val unitsProposed: Double,
    /** Units that would have landed on target; null when the outcome can't tell. */
    val unitsNeeded: Double?,
    val endBg: Int?,
    val min4h: Int?,
    val max4h: Int?,
    val wentLow: Boolean,
    /** Factors in effect (multipliers and pending units-per-event). */
    val factors: List<String>,
    val clean: Boolean,
    val excludedBecause: String? = null,
    val impliedIcr: Double? = null,
    val impliedIsf: Double? = null,
    /** For a dose whose only pending units came from one units-per-event factor (caffeine). */
    val unitsFactorId: String? = null,
    val impliedUnitsPerEvent: Double? = null,
    /** Lower is better: how far the end BG missed target, plus a penalty for going low. */
    val score: Double? = null,
) {
    /** Positive = more insulin was needed. */
    val errorUnits: Double? get() = unitsNeeded?.let { it - unitsGiven }
}

object Lessons {
    private const val WINDOW_MS = 4 * 3_600_000L
    private const val MEAL_BEFORE_MS = 15 * 60_000L
    /** How much a low counts against an outcome, per mg/dL below the low line. */
    private const val LOW_PENALTY = 3.0

    fun build(
        proposals: List<ProposalFacts>,
        doses: List<DoseRow>,
        outcomes: List<OutcomeRow>,
        meals: List<MealRow>,
        zone: ZoneId,
        settings: LearningSettings,
    ): List<Lesson> {
        val outcomeByDose = outcomes.associateBy { it.doseId }
        val rapid = doses.filter { it.insulin == "rapid" && it.units > 0 }
        return proposals.mapNotNull { p ->
            val parts = rapid.filter { it.proposalId == p.id }.sortedBy { it.atMillis }
            val first = parts.firstOrNull() ?: return@mapNotNull null // never logged
            val outcome = outcomeByDose[first.id] ?: return@mapNotNull null // not tagged yet
            lesson(p, parts, first, outcome, rapid, meals, zone, settings)
        }.sortedBy { it.atMillis }
    }

    private fun lesson(
        p: ProposalFacts,
        parts: List<DoseRow>,
        first: DoseRow,
        o: OutcomeRow,
        rapid: List<DoseRow>,
        meals: List<MealRow>,
        zone: ZoneId,
        s: LearningSettings,
    ): Lesson {
        val input = p.input
        val r = p.result
        val target = p.profile.dose.target
        val isf = p.profile.dose.isf
        val windowEnd = first.atMillis + WINDOW_MS
        val given = parts.sumOf { it.units }
        val end = o.bg4h ?: o.bg3h
        val wentLow = o.min4h != null && o.min4h < s.lowMgDl

        val otherDose = rapid.any { it.proposalId != p.id && it.atMillis > first.atMillis && it.atMillis <= windowEnd }
        val otherMeal = meals.any { it.proposalId != p.id && it.atMillis >= first.atMillis - MEAL_BEFORE_MS && it.atMillis <= windowEnd }
        val excluded = when {
            end == null && !wentLow -> "no CGM at 3–4 h"
            otherDose -> "another rapid dose within 4 h"
            otherMeal -> "another meal within 4 h"
            input.bg == null -> "no BG at dose time"
            else -> null
        }

        // When BG went low, rescue carbs likely blur the end BG — the low itself is the lesson.
        val landed: Int? = if (wentLow) o.min4h else end
        val needed = landed?.let { given + (it - target) / isf }
        val kind = when {
            input.carbsG > 0 && !r.lowCarbMode -> LessonKind.MEAL
            input.carbsG == 0.0 && r.pendingUnits.isNotEmpty() -> LessonKind.UNITS_PER_EVENT
            input.carbsG == 0.0 && input.bg != null && input.bg - target > 20 -> LessonKind.CORRECTION
            else -> LessonKind.OTHER
        }

        var impliedIcr: Double? = null
        var impliedIsf: Double? = null
        var unitsFactor: String? = null
        var impliedUnits: Double? = null
        if (needed != null && r.combined > 0) {
            // The model: units = baseline × combined + added. Solve for the baseline that lands on
            // target, then give the whole gap to the one setting this kind of dose isolates.
            val neededBaseline = (needed - r.addedUnits) / r.combined
            when (kind) {
                LessonKind.MEAL -> {
                    val carbDose = neededBaseline - (r.baseline - r.carbDose)
                    if (carbDose > 0.1) impliedIcr = input.carbsG / carbDose
                }
                LessonKind.CORRECTION -> {
                    val correction = neededBaseline - (r.baseline - r.correction)
                    val rise = (input.bg ?: target) - target
                    if (correction > 0.1 && rise > 0) impliedIsf = rise / correction
                }
                LessonKind.UNITS_PER_EVENT -> {
                    val ids = r.pendingUnits.map { it.factorId }.distinct()
                    val amount = r.pendingUnits.sumOf { it.amount }
                    if (ids.size == 1 && amount > 0) {
                        unitsFactor = ids.single()
                        impliedUnits = (needed - r.baseline * r.combined) / amount
                    }
                }
                LessonKind.OTHER -> Unit
            }
        }

        val score = when {
            end == null && !wentLow -> null
            else -> {
                val miss = abs((end ?: o.min4h!!) - target)
                val low = if (wentLow) LOW_PENALTY * (s.lowMgDl - o.min4h!!) else 0.0
                miss + low
            }
        }

        return Lesson(
            proposalId = p.id, doseId = first.id, atMillis = first.atMillis,
            localHour = Instant.ofEpochMilli(first.atMillis).atZone(zone).hour,
            kind = kind, carbsG = input.carbsG, fatG = input.fatG, proteinG = input.proteinG,
            bgAtDose = input.bg, target = target, unitsGiven = given, unitsProposed = r.raw,
            unitsNeeded = needed, endBg = end, min4h = o.min4h, max4h = o.max4h, wentLow = wentLow,
            factors = (input.factors.map { it.factorId } + r.pendingUnits.map { it.factorId }).distinct(),
            clean = excluded == null, excludedBecause = excluded,
            impliedIcr = impliedIcr, impliedIsf = impliedIsf,
            unitsFactorId = unitsFactor, impliedUnitsPerEvent = impliedUnits,
            score = score,
        )
    }
}
