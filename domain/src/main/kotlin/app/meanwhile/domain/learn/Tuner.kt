package app.meanwhile.domain.learn

import app.meanwhile.domain.profile.FactorKind
import app.meanwhile.domain.profile.Profile
import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

/** One step the local tuner wants to take, with the evidence in plain words. */
@Serializable
data class TuneProposal(
    val path: String,
    val old: Double,
    val new: Double,
    /** Median of what the lessons imply. */
    val implied: Double,
    val lessons: Int,
    val newLessons: Int,
    val evidence: String,
)

/**
 * Deterministic, offline learning (no AI needed): ICR from clean meal doses, ISF from clean
 * corrections, units-per-event from doses where that was the only addition. Each step moves the
 * value [LearningSettings.rate] of the way to the median the lessons imply.
 *
 * A value only moves again once [LearningSettings.minLessons] new lessons arrived after its last
 * change, so the same evidence never counts twice and every change is judged on fresh outcomes.
 */
object Tuner {
    private const val DAY_MS = 86_400_000L

    fun propose(lessons: List<Lesson>, profile: Profile, lastChanged: Map<String, Long>, nowMillis: Long): List<TuneProposal> {
        val s = profile.learning
        val window = lessons.filter { it.clean && it.atMillis >= nowMillis - s.lookbackDays * DAY_MS }
        val out = mutableListOf<TuneProposal>()

        fun consider(path: String, current: Double, values: List<Pair<Long, Double>>, describe: (median: Double, lo: Double, hi: Double) -> String) {
            val usable = values.filter { it.second.isFinite() && it.second > 0 }
            if (usable.size < s.minLessons) return
            val since = lastChanged[path] ?: Long.MIN_VALUE
            val fresh = usable.count { it.first > since }
            if (fresh < s.minLessons) return
            val sorted = usable.map { it.second }.sorted()
            val median = quantile(sorted, 0.5)
            val next = round1(current + s.rate * (median - current))
            if (next <= 0 || abs(next - current) / current * 100 < s.minChangePct) return
            val evidence = describe(median, quantile(sorted, 0.25), quantile(sorted, 0.75)) +
                " → ${fmt(current)} → ${fmt(next)} (${(s.rate * 100).toInt()}% step, ${usable.size} lessons, $fresh new)"
            out += TuneProposal(path, current, next, median, usable.size, fresh, evidence)
        }

        consider(
            "dose.icr", profile.dose.icr,
            window.mapNotNull { l -> l.impliedIcr?.let { l.atMillis to it } },
        ) { m, lo, hi -> "Clean meal doses needed 1 u per ${fmt(m)} g carbs (middle half ${fmt(lo)}–${fmt(hi)})" }

        consider(
            "dose.isf", profile.dose.isf,
            window.mapNotNull { l -> l.impliedIsf?.let { l.atMillis to it } },
        ) { m, lo, hi -> "Clean corrections: 1 u lowered BG ${fmt(m)} mg/dL (middle half ${fmt(lo)}–${fmt(hi)})" }

        profile.factors.filter { it.kind == FactorKind.UNITS_PER_EVENT && it.unitsPerEvent != null }.forEach { f ->
            consider(
                "factors.${f.id}.unitsPerEvent", f.unitsPerEvent!!,
                window.filter { it.unitsFactorId == f.id }.mapNotNull { l -> l.impliedUnitsPerEvent?.let { l.atMillis to it } },
            ) { m, lo, hi -> "${f.name}-only doses needed ${fmt(m)} u each (middle half ${fmt(lo)}–${fmt(hi)})" }
        }
        return out
    }

    /** Linear-interpolated quantile of a sorted list. */
    fun quantile(sorted: List<Double>, q: Double): Double {
        if (sorted.size == 1) return sorted[0]
        val pos = q * (sorted.size - 1)
        val lo = pos.toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }

    private fun round1(x: Double) = round(x * 10) / 10
    private fun fmt(x: Double) = String.format(Locale.US, "%.1f", x)
}
