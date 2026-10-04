package app.meanwhile.domain.stats

import kotlin.math.abs
import kotlin.math.roundToLong

/** Minimal views of stored records, so reliability stats stay pure and testable. */
data class ProposalRow(val id: String, val atMillis: Long, val finalUnits: Int)

data class DoseRow(
    val id: String,
    val atMillis: Long,
    val insulin: String,
    val units: Double,
    val proposedUnits: Double?,
    val proposalId: String?,
)

data class OutcomeRow(val doseId: String, val bg2h: Int?, val bg3h: Int?, val bg4h: Int?, val min4h: Int?, val max4h: Int?)

/** How Danny acted on a proposal (spec §14 M8: proposals followed vs. overridden). */
enum class Follow { FOLLOWED, OVERRIDDEN, NOT_LOGGED }

object ProposalFollowRule {
    /**
     * Followed = every injection logged for the proposal matched what it proposed for that injection,
     * and the total equals the proposal. Edited amounts and skipped second parts are overrides.
     */
    fun classify(finalUnits: Int, doses: List<Pair<Double, Double?>>): Follow {
        if (doses.isEmpty()) return Follow.NOT_LOGGED
        val total = doses.sumOf { it.first }.roundToLong()
        val each = doses.all { (units, proposed) -> proposed == null || units.roundToLong() == proposed.roundToLong() }
        return if (total == finalUnits.toLong() && each) Follow.FOLLOWED else Follow.OVERRIDDEN
    }
}

data class OutcomeAgg(
    val proposals: Int,
    val withOutcome: Int,
    val meanBg3h: Double?,
    val inRange3hPct: Double?,
    /** Any reading < 70 within 4 h of the dose. */
    val lowWithin4hPct: Double?,
    /** Any reading > 250 within 4 h of the dose. */
    val highWithin4hPct: Double?,
)

data class FollowStats(val counts: Map<Follow, Int>, val outcomes: Map<Follow, OutcomeAgg>) {
    val total: Int get() = counts.values.sum()
    fun pct(f: Follow): Double = if (total == 0) 0.0 else 100.0 * (counts[f] ?: 0) / total
}

object Reliability {

    fun follow(proposals: List<ProposalRow>, doses: List<DoseRow>, outcomes: List<OutcomeRow>): FollowStats {
        val byProposal = doses.filter { it.proposalId != null }.groupBy { it.proposalId!! }
        val outcomeByDose = outcomes.associateBy { it.doseId }
        val classified = proposals.map { p ->
            val ds = byProposal[p.id].orEmpty()
            Triple(p, ProposalFollowRule.classify(p.finalUnits, ds.map { it.units to it.proposedUnits }), ds)
        }
        val counts = Follow.entries.associateWith { f -> classified.count { it.second == f } }
        val aggs = Follow.entries.associateWith { f ->
            val group = classified.filter { it.second == f }
            // Outcome of a proposal = outcome of its first injection (covers the meal).
            val os = group.mapNotNull { (_, _, ds) -> ds.minByOrNull { it.atMillis }?.let { outcomeByDose[it.id] } }
            val bg3 = os.mapNotNull { it.bg3h }
            val withMinMax = os.filter { it.min4h != null }
            OutcomeAgg(
                proposals = group.size,
                withOutcome = os.size,
                meanBg3h = bg3.takeIf { it.isNotEmpty() }?.average(),
                inRange3hPct = bg3.takeIf { it.isNotEmpty() }?.let { l -> 100.0 * l.count { it in 70..180 } / l.size },
                lowWithin4hPct = withMinMax.takeIf { it.isNotEmpty() }?.let { l -> 100.0 * l.count { it.min4h!! < 70 } / l.size },
                highWithin4hPct = withMinMax.takeIf { it.isNotEmpty() }?.let { l -> 100.0 * l.count { (it.max4h ?: 0) > 250 } / l.size },
            )
        }
        return FollowStats(counts, aggs)
    }

    /** Consecutive days at or above [goalPct] TIR, counting back from the most recent day with data. */
    fun goalStreak(daily: List<Pair<String, GlucoseSummary>>, goalPct: Double = 80.0, minCoverageMin: Double = 12 * 60.0): Int {
        var streak = 0
        for ((_, s) in daily.sortedByDescending { it.first }) {
            if (s.coveredMinutes < minCoverageMin) {
                if (streak == 0) continue else break
            }
            if (s.timeInRangePct >= goalPct) streak++ else break
        }
        return streak
    }
}

/** AI accuracy by provider/model (spec §14 M8). */
data class AiCallRow(val provider: String?, val model: String?, val job: String, val latencyMs: Long?, val fallbackUsed: Boolean, val validation: String)

data class AiDecisionRow(val provider: String?, val model: String?, val status: String)

data class EstimateRow(val provider: String?, val model: String?, val estimatedCarbs: Double, val loggedCarbs: Double)

data class AiModelStats(
    val provider: String,
    val model: String,
    val calls: Int,
    val ok: Int,
    val invalid: Int,
    val errors: Int,
    val fallbackServed: Int,
    val meanLatencyMs: Double?,
    val byJob: Map<String, Int>,
    val accepted: Int,
    val edited: Int,
    val rejected: Int,
    val estimates: Int,
    val meanAbsCarbError: Double?,
) {
    val successPct: Double get() = if (calls == 0) 0.0 else 100.0 * ok / calls
}

object AiAccuracy {
    fun byModel(calls: List<AiCallRow>, decisions: List<AiDecisionRow>, estimates: List<EstimateRow>): List<AiModelStats> {
        fun key(p: String?, m: String?) = (p ?: "unknown") to (m ?: "unknown")
        val keys = (calls.map { key(it.provider, it.model) } + decisions.map { key(it.provider, it.model) } +
            estimates.map { key(it.provider, it.model) }).distinct()
        return keys.map { k ->
            val cs = calls.filter { key(it.provider, it.model) == k }
            val ds = decisions.filter { key(it.provider, it.model) == k }
            val es = estimates.filter { key(it.provider, it.model) == k }
            AiModelStats(
                provider = k.first, model = k.second, calls = cs.size,
                ok = cs.count { it.validation == "ok" }, invalid = cs.count { it.validation == "invalid" },
                errors = cs.count { it.validation == "error" }, fallbackServed = cs.count { it.fallbackUsed && it.validation == "ok" },
                meanLatencyMs = cs.mapNotNull { it.latencyMs }.takeIf { it.isNotEmpty() }?.average(),
                byJob = cs.groupingBy { it.job }.eachCount(),
                accepted = ds.count { it.status == "accepted" }, edited = ds.count { it.status == "edited" },
                rejected = ds.count { it.status == "rejected" },
                estimates = es.size,
                meanAbsCarbError = es.takeIf { it.isNotEmpty() }?.map { abs(it.estimatedCarbs - it.loggedCarbs) }?.average(),
            )
        }.sortedByDescending { it.calls }
    }
}
