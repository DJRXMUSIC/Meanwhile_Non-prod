package app.meanwhile.domain.dose

import app.meanwhile.domain.factors.AppliedFactor
import app.meanwhile.domain.profile.Profile
import kotlinx.serialization.Serializable
import kotlin.math.floor
import kotlin.math.roundToLong

/** Everything the engine needs for one recommendation (spec §5.1). */
@Serializable
data class DoseInput(
    val carbsG: Double = 0.0,
    val fatG: Double = 0.0,
    val proteinG: Double = 0.0,
    val liquidOrSugary: Boolean = false,
    /** Latest BG (mg/dL); null = unknown, correction omitted. */
    val bg: Double? = null,
    /** mg/dL per minute. */
    val trendRate: Double? = null,
    val iob: Double = 0.0,
    /** Active multipliers (not including meal-computed fat/protein). */
    val factors: List<FactorWeight> = emptyList(),
    /** Units from pending units-per-event factors (caffeine cups × units per cup). */
    val pendingUnits: List<PendingUnits> = emptyList(),
)

@Serializable
data class FactorWeight(val factorId: String, val name: String, val weight: Double, val note: String? = null)

@Serializable
data class PendingUnits(val factorId: String, val name: String, val units: Double, val amount: Double)

@Serializable
data class FactorTerm(val factorId: String, val name: String, val weight: Double, val contribution: Double, val note: String? = null)

@Serializable
data class SplitPlan(val firstUnits: Int, val secondUnits: Int, val secondAfterMin: Int, val firstFraction: Double)

/** Full breakdown (spec §9.5) — the math shown on the card is the math that ran. */
@Serializable
data class DoseResult(
    val carbDose: Double,
    val correction: Double,
    val iob: Double,
    val lowCarbMode: Boolean,
    val fatWeight: Double,
    val proteinWeight: Double,
    val fatUnits: Double,
    val proteinUnits: Double,
    val baseline: Double,
    val terms: List<FactorTerm>,
    val combinedUncapped: Double,
    val combined: Double,
    val capped: Boolean,
    val clampedAtZero: Boolean,
    val pendingUnits: List<PendingUnits>,
    val addedUnits: Double,
    val raw: Double,
    val finalUnits: Int,
    /** When raw < 0: carbs (g) that would bring the projection back to target. */
    val suggestedCarbsG: Int?,
    val split: SplitPlan?,
    /** Pre-bolus lead time; null when there's no meal. */
    val leadTimeMin: Int?,
    val leadTimeSteps: List<String>,
    val warnings: List<String>,
)

object DoseEngine {

    fun compute(input: DoseInput, profile: Profile): DoseResult {
        val d = profile.dose
        val m = profile.meal
        val warnings = mutableListOf<String>()

        val carbDose = input.carbsG / d.icr
        val correction = input.bg?.let { (it - d.target) / d.isf } ?: 0.0
        if (input.bg == null) warnings += "No BG: correction not included."

        val lowCarb = input.carbsG < m.lowCarbThresholdG
        val fatWeight: Double
        val proteinWeight: Double
        val fatUnits: Double
        val proteinUnits: Double
        if (!lowCarb) {
            fatWeight = 1 + m.kFatPerG * input.fatG
            proteinWeight = 1 + m.kProteinPerG * input.proteinG
            fatUnits = 0.0
            proteinUnits = 0.0
        } else {
            fatWeight = 1.0
            proteinWeight = 1.0
            fatUnits = input.fatG / m.fatGPerUnit
            proteinUnits = input.proteinG / m.proteinGPerUnit
        }

        val baseline = carbDose + fatUnits + proteinUnits + correction - input.iob

        // Weights are added, never multiplied: combined = 1 + Σ(w − 1).
        val terms = buildList {
            if (input.fatG > 0 && !lowCarb) add(FactorTerm("F2", "Fat", fatWeight, fatWeight - 1, "${fmt(input.fatG)} g"))
            if (input.proteinG > 0 && !lowCarb) add(FactorTerm("F3", "Protein", proteinWeight, proteinWeight - 1, "${fmt(input.proteinG)} g"))
            input.factors.forEach { add(FactorTerm(it.factorId, it.name, it.weight, it.weight - 1, it.note)) }
        }
        val combinedUncapped = 1 + terms.sumOf { it.contribution }
        val capped = combinedUncapped > d.combinedCap
        var combined = minOf(combinedUncapped, d.combinedCap)
        // No floor by design, but a negative multiplier would flip the sign of the dose.
        val clampedAtZero = combined < 0
        if (clampedAtZero) combined = 0.0

        val addedUnits = input.pendingUnits.sumOf { it.units }
        val raw = round6(baseline * combined + addedUnits)
        val inc = d.unitIncrement
        val finalUnits = maxOf(0L, floor(raw / inc + 0.5).toLong()).let { (it * inc).roundToLong().toInt() }
        val suggestedCarbs = if (raw < 0) (-raw * d.icr).roundToLong().toInt() else null

        val split = split(input, profile, finalUnits)
        val (lead, steps) = leadTime(input, profile)

        return DoseResult(
            carbDose = carbDose, correction = correction, iob = input.iob, lowCarbMode = lowCarb,
            fatWeight = fatWeight, proteinWeight = proteinWeight, fatUnits = fatUnits, proteinUnits = proteinUnits,
            baseline = baseline, terms = terms, combinedUncapped = combinedUncapped, combined = combined,
            capped = capped, clampedAtZero = clampedAtZero, pendingUnits = input.pendingUnits, addedUnits = addedUnits,
            raw = raw, finalUnits = finalUnits, suggestedCarbsG = suggestedCarbs, split = split,
            leadTimeMin = lead, leadTimeSteps = steps, warnings = warnings,
        )
    }

    /** Spec §5.3: high fat + protein meals are split for pens. */
    fun split(input: DoseInput, profile: Profile, finalUnits: Int): SplitPlan? {
        val s = profile.split
        if (!s.enabled) return null
        if (input.fatG < s.minFatG || input.proteinG < s.minProteinG || input.carbsG < s.minCarbsG) return null
        val first = floor(finalUnits * s.firstFraction + 0.5).toInt()
        val second = finalUnits - first
        if (first <= 0 || second <= 0) return null
        return SplitPlan(first, second, s.secondAfterMin, s.firstFraction)
    }

    /** Spec §5.4. Returns null minutes when there's no meal to pre-bolus for. */
    fun leadTime(input: DoseInput, profile: Profile): Pair<Int?, List<String>> {
        if (input.carbsG <= 0.0) return null to listOf("No carbs: no pre-bolus")
        val r = profile.leadTime
        val steps = mutableListOf<String>()
        val bg = input.bg
        val trend = input.trendRate
        if ((bg != null && bg < r.eatNowBelowBg) || (trend != null && trend <= r.eatNowTrendAtOrBelow)) {
            steps += if (bg != null && bg < r.eatNowBelowBg) "BG ${bg.toInt()} < ${r.eatNowBelowBg.toInt()}: eat now" else
                "Falling ${fmt(trend ?: 0.0)} mg/dL/min: eat now"
            return 0 to steps
        }
        var lead = r.baseMin
        steps += "Base ${r.baseMin} min"
        if (bg != null && bg > r.highBgStart) {
            val add = r.highBgStepMin * floor((bg - r.highBgStart) / r.highBgStepMgDl).toInt()
            if (add != 0) {
                lead += add
                steps += "BG ${bg.toInt()}: ${signed(add)} min"
            }
        }
        if (input.liquidOrSugary) {
            lead += r.liquidOrSugaryMin
            steps += "Liquid/sugary: ${signed(r.liquidOrSugaryMin)} min"
        }
        if (input.fatG >= r.highFatG) {
            lead += r.highFatMin
            steps += "Fat ≥ ${r.highFatG.toInt()} g: ${signed(r.highFatMin)} min"
        }
        r.factorMin.forEach { (id, add) ->
            val f = input.factors.firstOrNull { it.factorId == id }
            if (f != null) {
                lead += add
                steps += "${f.name} active: ${signed(add)} min"
            }
        }
        val clamped = lead.coerceIn(r.minMin, r.maxMin)
        if (clamped != lead) steps += "Clamped to ${r.minMin}–${r.maxMin} min"
        return clamped to steps
    }

    fun weights(applied: List<AppliedFactor>): List<FactorWeight> =
        applied.map { FactorWeight(it.factorId, it.name, it.weight, it.note) }

    private fun round6(x: Double) = Math.round(x * 1e6) / 1e6
    private fun signed(n: Int) = if (n >= 0) "+$n" else "$n"
    private fun fmt(x: Double) = if (x == floor(x)) x.toLong().toString() else String.format(java.util.Locale.US, "%.1f", x)
}
