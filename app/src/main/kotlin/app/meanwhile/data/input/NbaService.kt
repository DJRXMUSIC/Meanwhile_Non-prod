package app.meanwhile.data.input

import app.meanwhile.log.AppLog
import app.meanwhile.data.RecordFactory
import app.meanwhile.data.db.AppDatabase
import app.meanwhile.data.db.DoseEntity
import app.meanwhile.data.db.MealEntity
import app.meanwhile.data.db.ProposalEntity
import app.meanwhile.data.dose.DoseContextBuilder
import app.meanwhile.data.json.AppJson
import app.meanwhile.domain.dose.DoseEngine
import app.meanwhile.domain.dose.DoseInput
import app.meanwhile.domain.dose.DoseResult
import app.meanwhile.domain.dose.SplitPlan
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant

/** What gets stored as a proposal's input snapshot. */
@Serializable
data class ProposalSnapshot(
    val doseInput: DoseInput,
    val meal: MealDraft,
    val bgAgeMinutes: Long?,
    val bgStale: Boolean,
    val profileLabel: String,
    val computeMs: Long,
)

/** A split second injection that's due or upcoming. */
data class PendingSecond(
    val proposalId: String,
    val units: Int,
    val dueAt: Long,
    val firstDoseAt: Long,
)

/** The profile has values the dose math can't use; the message lists them (nothing is stored). */
class DoseUnavailableException(problems: List<String>) :
    IllegalStateException("No dose: the profile has values the math can't use — " + problems.joinToString("; ") + ". Fix them in Profile → Edit settings.")

/** Next Best Action + dose logging (spec §9.5, §9.6). Deterministic and offline. */
class NbaService(
    private val db: AppDatabase,
    private val records: RecordFactory,
    private val contexts: DoseContextBuilder,
    private val onWrite: () -> Unit,
    private val staleMinutes: suspend () -> Int,
    private val scheduleSecond: (proposalId: String, units: Int, dueAt: Long) -> Unit,
    private val cancelSecond: (proposalId: String) -> Unit,
) {
    suspend fun propose(meal: MealDraft, inputId: String?, now: Instant = Instant.now(), bgOverride: Double? = null): NbaCard {
        val started = System.nanoTime()
        val ctx = contexts.build(now)
        val input = ctx.input(meal.carbsG, meal.fatG, meal.proteinG, meal.liquidOrSugary, bgOverride)
        val result = DoseEngine.compute(input, ctx.profile.profile)
        if (result.profileProblems.isNotEmpty()) {
            AppLog.w("NBA", "no dose: invalid profile — ${result.profileProblems.joinToString("; ")}")
            throw DoseUnavailableException(result.profileProblems)
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        if (ms > 200) AppLog.w("NBA", "slow calculation: $ms ms (target < 200)")
        val stale = ctx.bgAgeMinutes == null || ctx.bgAgeMinutes > staleMinutes()
        val m = records.meta(now = now.toEpochMilli())
        val snapshot = ProposalSnapshot(input, meal, ctx.bgAgeMinutes, stale, ctx.profile.versionLabel, ms)
        db.proposals().insert(
            ProposalEntity(
                id = m.id, userId = m.userId, createdAt = m.createdAt, recordedAt = m.recordedAt,
                inputSnapshot = AppJson.encodeToString(ProposalSnapshot.serializer(), snapshot),
                breakdown = AppJson.encodeToString(DoseResult.serializer(), result),
                finalUnits = result.finalUnits,
                leadTimeMin = result.leadTimeMin,
                splitPlan = result.split?.let { AppJson.encodeToString(SplitPlan.serializer(), it) } ?: "null",
                profileVersionId = ctx.profile.version?.id,
                inputId = inputId,
            ),
        )
        onWrite()
        return NbaCard(
            key = "nba-${m.id}", proposalId = m.id, inputId = inputId, meal = meal, input = input, result = result,
            profileLabel = ctx.profile.versionLabel, profileVersion = ctx.profile.version,
            bgAgeMinutes = ctx.bgAgeMinutes, bgStale = stale, computedAt = now.toEpochMilli(), computeMs = ms,
        )
    }

    /**
     * Logs the first (or only) injection of a proposal, records the meal, and arms the split reminder.
     * [laterUnits] > 0 schedules the second injection at meal start + secondAfterMin.
     */
    suspend fun logFromProposal(
        card: NbaCard,
        nowUnits: Int,
        laterUnits: Int,
        reason: String?,
        now: Instant = Instant.now(),
    ): String {
        val split = card.result.split
        val proposedNow = split?.firstUnits ?: card.result.finalUnits
        val mealStart = now.plus(Duration.ofMinutes((card.result.leadTimeMin ?: 0).toLong()))
        val dueAt = mealStart.plus(Duration.ofMinutes((split?.secondAfterMin ?: 60).toLong())).toEpochMilli()
        val hasMeal = card.meal.carbsG > 0 || card.meal.fatG > 0 || card.meal.proteinG > 0
        if (hasMeal) {
            val mm = records.meta(recordedAt = mealStart.toEpochMilli(), now = now.toEpochMilli())
            db.meals().insert(
                MealEntity(
                    id = mm.id, userId = mm.userId, createdAt = mm.createdAt, recordedAt = mm.recordedAt,
                    carbsG = card.meal.carbsG, fatG = card.meal.fatG, proteinG = card.meal.proteinG,
                    liquidOrSugary = card.meal.liquidOrSugary, isEstimate = card.meal.isEstimate,
                    description = card.meal.description, inputId = card.inputId,
                    details = buildJsonObject {
                        put("proposal_id", card.proposalId)
                        put("estimate", AppJson.parseToJsonElement(card.meal.estimateDetails))
                    }.toString(),
                ),
            )
        }
        val dm = records.meta(now = now.toEpochMilli())
        val second = if (laterUnits > 0) laterUnits else 0
        db.doses().insert(
            DoseEntity(
                id = dm.id, userId = dm.userId, createdAt = dm.createdAt, recordedAt = now.toEpochMilli(),
                insulin = "rapid", units = nowUnits.toDouble(), givenAt = now.toEpochMilli(),
                proposalId = card.proposalId, splitPart = if (second > 0 || split != null) 1 else null,
                proposedUnits = proposedNow.toDouble(),
                overrideReason = reason?.takeIf { it.isNotBlank() },
                inputId = card.inputId,
                details = buildJsonObject {
                    put("proposal_final_units", card.result.finalUnits)
                    if (second > 0) {
                        put("second_units", second)
                        put("second_due_at", dueAt)
                    }
                }.toString(),
            ),
        )
        if (second > 0) scheduleSecond(card.proposalId, second, dueAt)
        AppLog.i("Dose", "logged $nowUnits u for proposal ${card.proposalId.take(8)} (proposed ${card.result.finalUnits})" + if (second > 0) " + $second u later" else "")
        onWrite()
        return buildString {
            append("Logged $nowUnits u")
            if (second > 0) append(" · reminder for $second u at ${app.meanwhile.format.formatTime(dueAt)}")
        }
    }

    private val secondLock = Mutex()

    /**
     * Logs (or with [skipped], records skipping) a split's second injection. Returns false when it was
     * already logged — from the app and the notification, or a double tap — so insulin is never counted twice.
     */
    suspend fun logSecond(proposalId: String, units: Int, reason: String? = null, skipped: Boolean = false, now: Instant = Instant.now()): Boolean = secondLock.withLock {
        val forProposal = db.doses().forProposal(proposalId)
        if (forProposal.any { it.splitPart == 2 }) {
            AppLog.i("Dose", "second injection for ${proposalId.take(8)} already logged — ignored duplicate")
            cancelSecond(proposalId)
            return@withLock false
        }
        val first = forProposal.firstOrNull { it.splitPart == 1 }
        val proposed = first?.details?.let { secondUnitsOf(it) }
        val m = records.meta(now = now.toEpochMilli())
        db.doses().insert(
            DoseEntity(
                id = m.id, userId = m.userId, createdAt = m.createdAt, recordedAt = now.toEpochMilli(),
                insulin = "rapid", units = if (skipped) 0.0 else units.toDouble(), givenAt = now.toEpochMilli(),
                proposalId = proposalId, splitPart = 2, proposedUnits = proposed?.toDouble(),
                overrideReason = if (skipped) "skipped" else reason?.takeIf { it.isNotBlank() },
            ),
        )
        cancelSecond(proposalId)
        onWrite()
        true
    }

    /** Second injections that have a first part logged in the last 12 h and no second part yet. */
    suspend fun pendingSeconds(now: Instant = Instant.now()): List<PendingSecond> {
        val doses = db.doses().since(now.minus(Duration.ofHours(12)).toEpochMilli())
        val done = doses.filter { it.splitPart == 2 }.mapNotNull { it.proposalId }.toSet()
        return doses.filter { it.splitPart == 1 && it.proposalId != null && it.proposalId !in done }
            .mapNotNull { d ->
                val units = secondUnitsOf(d.details) ?: return@mapNotNull null
                val due = runCatching { AppJson.parseToJsonElement(d.details).jsonObject["second_due_at"]?.jsonPrimitive?.longOrNull }
                    .getOrNull() ?: return@mapNotNull null
                PendingSecond(d.proposalId!!, units, due, d.givenAt)
            }
    }

    /** "took 6 units" / "took my long-acting 22" (spec §9.6). */
    suspend fun logDose(units: Double, insulin: String, givenAt: Long, inputId: String?, reason: String? = null): String {
        val m = records.meta(recordedAt = givenAt)
        db.doses().insert(
            DoseEntity(
                id = m.id, userId = m.userId, createdAt = m.createdAt, recordedAt = givenAt,
                insulin = insulin, units = units, givenAt = givenAt, inputId = inputId,
                overrideReason = reason?.takeIf { it.isNotBlank() },
            ),
        )
        onWrite()
        return "Logged ${app.meanwhile.format.formatUnits(units)} ${if (insulin == "long") "long-acting" else "rapid"} at ${app.meanwhile.format.formatTime(givenAt)}"
    }

    private fun secondUnitsOf(details: String): Int? = runCatching {
        AppJson.parseToJsonElement(details).jsonObject["second_units"]?.jsonPrimitive?.contentOrNull?.toDouble()?.toInt()
    }.getOrNull()
}
