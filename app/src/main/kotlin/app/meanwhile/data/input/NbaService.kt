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
import app.meanwhile.domain.nba.ActionKind
import app.meanwhile.domain.nba.NextBestAction
import app.meanwhile.domain.nba.NextBestActions
import app.meanwhile.domain.router.OfflineRouter
import app.meanwhile.data.profile.decodedProfile
import app.meanwhile.format.formatTime
import app.meanwhile.format.formatUnits
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
    /** 1.4: what the app told Danny to do (insulin, carbs, nothing). Null on older proposals. */
    val action: NextBestAction? = null,
    /** BG Danny said for this message ("BG 140") instead of the CGM. */
    val bgOverride: Double? = null,
)

/** A dose row just written (logged, or a correction superseding an earlier one) and its message. */
data class LoggedDose(val dose: DoseEntity, val message: String, val previous: DoseEntity? = null)

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
        val stale = bgOverride == null && (ctx.bgAgeMinutes == null || ctx.bgAgeMinutes > staleMinutes())
        val action = NextBestActions.decide(input, result, ctx.profile.profile, if (bgOverride != null) 0L else ctx.bgAgeMinutes, stale)
        val m = records.meta(now = now.toEpochMilli())
        val snapshot = ProposalSnapshot(input, meal, ctx.bgAgeMinutes, stale, ctx.profile.versionLabel, ms, action, bgOverride)
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
        AppLog.i("NBA", "${action.kind}: ${action.sentence} (raw ${result.raw} u, ${ms} ms)")
        return NbaCard(
            key = "nba-${m.id}", proposalId = m.id, inputId = inputId, meal = meal, input = input, result = result,
            profileLabel = ctx.profile.versionLabel, profileVersion = ctx.profile.version,
            bgAgeMinutes = ctx.bgAgeMinutes, bgStale = stale, computedAt = now.toEpochMilli(), computeMs = ms,
            action = action, bgOverride = bgOverride,
        )
    }

    /**
     * The newest Next Best Action from the last [within] that nothing was logged against yet — what
     * "took it" / "done" refers to. Rebuilt from the stored proposal, so it works after a restart.
     */
    suspend fun openProposal(now: Instant = Instant.now(), within: Duration = Duration.ofMinutes(90)): NbaCard? {
        val from = now.minus(within).toEpochMilli()
        val candidates = db.proposals().since(from).sortedByDescending { it.recordedAt }
        for (p in candidates) {
            if (db.doses().effectiveForProposal(p.id).isNotEmpty()) return null // the newest one was acted on
            if (db.meals().between(from - 3_600_000, now.toEpochMilli() + 3 * 3_600_000).any { proposalIdOf(it.details) == p.id }) return null
            return cardFrom(p)
        }
        return null
    }

    /** An [NbaCard] for a stored proposal (null when its JSON can't be read). */
    suspend fun cardFrom(p: app.meanwhile.data.db.ProposalEntity): NbaCard? {
        val snapshot = runCatching { AppJson.decodeFromString(ProposalSnapshot.serializer(), p.inputSnapshot) }.getOrNull() ?: return null
        val result = runCatching { AppJson.decodeFromString(DoseResult.serializer(), p.breakdown) }.getOrNull() ?: return null
        val version = p.profileVersionId?.let { db.profileVersions().byId(it) }
        val action = snapshot.action ?: NextBestActions.decide(
            snapshot.doseInput, result, version?.let { runCatching { it.decodedProfile() }.getOrNull() } ?: app.meanwhile.domain.profile.Profile(),
            snapshot.bgAgeMinutes, snapshot.bgStale,
        )
        return NbaCard(
            key = "nba-${p.id}", proposalId = p.id, inputId = p.inputId, meal = snapshot.meal, input = snapshot.doseInput, result = result,
            profileLabel = snapshot.profileLabel, profileVersion = version, bgAgeMinutes = snapshot.bgAgeMinutes, bgStale = snapshot.bgStale,
            computedAt = p.recordedAt, computeMs = snapshot.computeMs, action = action, bgOverride = snapshot.bgOverride,
        )
    }

    /**
     * Danny ate what a no-insulin action said (carbs for a low, extra carbs, a meal that needs no
     * insulin): the meal is recorded — carbs included — and no dose row is written.
     */
    suspend fun logMealOnly(card: NbaCard, extraCarbsG: Double = (card.action.carbsG ?: 0).toDouble(), now: Instant = Instant.now()): String {
        val carbs = card.meal.carbsG + extraCarbsG
        val description = when {
            card.meal.description.isNotBlank() && card.meal.description != OfflineRouter.CHECK_DESCRIPTION && extraCarbsG > 0 ->
                "${card.meal.description} + ${extraCarbsG.toInt()} g carbs"
            card.meal.description.isNotBlank() && card.meal.description != OfflineRouter.CHECK_DESCRIPTION -> card.meal.description
            card.action.kind == ActionKind.TREAT_LOW -> "fast carbs for a low"
            else -> "carbs (no insulin)"
        }
        val mm = records.meta(now = now.toEpochMilli())
        db.meals().insert(
            MealEntity(
                id = mm.id, userId = mm.userId, createdAt = mm.createdAt, recordedAt = mm.recordedAt,
                carbsG = carbs, fatG = card.meal.fatG, proteinG = card.meal.proteinG,
                liquidOrSugary = card.meal.liquidOrSugary || card.action.kind == ActionKind.TREAT_LOW,
                isEstimate = card.meal.isEstimate, description = description, inputId = card.inputId,
                details = buildJsonObject {
                    put("proposal_id", card.proposalId)
                    put("action", card.action.kind.name)
                    put("extra_carbs_g", extraCarbsG)
                    put("estimate", AppJson.parseToJsonElement(card.meal.estimateDetails))
                }.toString(),
            ),
        )
        AppLog.i("Meal", "logged ${carbs.toInt()} g carbs, no insulin, for proposal ${card.proposalId.take(8)} (${card.action.kind})")
        onWrite()
        return "Logged ${carbs.toInt()} g carbs · no insulin" + (card.action.recheckInMin?.let { " · recheck at ${formatTime(now.plus(Duration.ofMinutes(it.toLong())).toEpochMilli())}" } ?: "")
    }

    private fun proposalIdOf(details: String): String? = runCatching {
        AppJson.parseToJsonElement(details).jsonObject["proposal_id"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

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
    ): String = logProposal(card, nowUnits, laterUnits, reason, now).message

    /** [logFromProposal] returning the dose row too; [givenAt] differs from now for "took it 10 min ago". */
    suspend fun logProposal(
        card: NbaCard,
        nowUnits: Int,
        laterUnits: Int,
        reason: String?,
        now: Instant = Instant.now(),
        givenAt: Instant = now,
        inputId: String? = card.inputId,
    ): LoggedDose {
        val split = card.result.split
        val proposedNow = split?.firstUnits ?: card.result.finalUnits
        val mealStart = givenAt.plus(Duration.ofMinutes((card.result.leadTimeMin ?: 0).toLong()))
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
        val dose = DoseEntity(
            id = dm.id, userId = dm.userId, createdAt = dm.createdAt, recordedAt = givenAt.toEpochMilli(),
            insulin = "rapid", units = nowUnits.toDouble(), givenAt = givenAt.toEpochMilli(),
            proposalId = card.proposalId, splitPart = if (second > 0 || split != null) 1 else null,
            proposedUnits = proposedNow.toDouble(),
            overrideReason = reason?.takeIf { it.isNotBlank() },
            inputId = inputId,
            details = buildJsonObject {
                put("proposal_final_units", card.result.finalUnits)
                put("action", card.action.kind.name)
                if (second > 0) {
                    put("second_units", second)
                    put("second_due_at", dueAt)
                }
            }.toString(),
        )
        db.doses().insert(dose)
        if (second > 0) scheduleSecond(card.proposalId, second, dueAt)
        AppLog.i("Dose", "logged $nowUnits u for proposal ${card.proposalId.take(8)} (proposed ${card.result.finalUnits})" + if (second > 0) " + $second u later" else "")
        onWrite()
        val message = buildString {
            append("Logged $nowUnits u")
            if (givenAt != now) append(" at ${formatTime(givenAt.toEpochMilli())}")
            if (second > 0) append(" · reminder for $second u at ${formatTime(dueAt)}")
        }
        return LoggedDose(dose, message)
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
        val doses = db.doses().effectiveSince(now.minus(Duration.ofHours(12)).toEpochMilli())
        val done = doses.filter { it.splitPart == 2 }.mapNotNull { it.proposalId }.toSet()
        return doses.filter { it.splitPart == 1 && it.units > 0 && it.proposalId != null && it.proposalId !in done }
            .mapNotNull { d ->
                val units = secondUnitsOf(d.details) ?: return@mapNotNull null
                val due = runCatching { AppJson.parseToJsonElement(d.details).jsonObject["second_due_at"]?.jsonPrimitive?.longOrNull }
                    .getOrNull() ?: return@mapNotNull null
                PendingSecond(d.proposalId!!, units, due, d.givenAt)
            }
    }

    /** "took 6 units" / "took my long-acting 22" (spec §9.6). */
    suspend fun logDose(units: Double, insulin: String, givenAt: Long, inputId: String?, reason: String? = null): String =
        logStated(units, insulin, givenAt, inputId, reason).message

    /** A dose Danny said he took, logged as he said it (1.4: saying it is logging it). */
    suspend fun logStated(units: Double, insulin: String, givenAt: Long, inputId: String?, reason: String? = null, now: Instant = Instant.now()): LoggedDose {
        val m = records.meta(recordedAt = givenAt, now = now.toEpochMilli())
        val dose = DoseEntity(
            id = m.id, userId = m.userId, createdAt = m.createdAt, recordedAt = givenAt,
            insulin = insulin, units = units, givenAt = givenAt, inputId = inputId,
            overrideReason = reason?.takeIf { it.isNotBlank() },
        )
        db.doses().insert(dose)
        AppLog.i("Dose", "logged ${formatUnits(units)} $insulin as said")
        onWrite()
        return LoggedDose(dose, "Logged ${formatUnits(units)} ${insulinLabel(insulin)} at ${formatTime(givenAt)}")
    }

    /**
     * The dose a spoken correction refers to: the newest one logged in the last [within] that is
     * still in effect (of [insulin] when given). Corrections never reach further back on their own.
     */
    suspend fun correctable(now: Instant = Instant.now(), insulin: String? = null, within: Duration = CORRECTION_WINDOW): DoseEntity? =
        db.doses().effectiveLoggedSince(now.minus(within).toEpochMilli())
            .firstOrNull { insulin == null || it.insulin == insulin }

    /**
     * Corrects [target] append-only: a new row superseding it with the new [units] (0 = "I didn't take
     * it") and/or time. Everything else — proposal link, split part, details — carries over, so IOB,
     * outcomes and learning all read the corrected dose. A cancelled first injection also cancels its
     * split reminder.
     */
    suspend fun correct(
        target: DoseEntity,
        units: Double?,
        givenAt: Long?,
        inputId: String?,
        reason: String,
        now: Instant = Instant.now(),
    ): LoggedDose {
        val newUnits = units ?: target.units
        val newAt = givenAt ?: target.givenAt
        val m = records.meta(recordedAt = newAt, now = now.toEpochMilli())
        val details = runCatching { AppJson.parseToJsonElement(target.details).jsonObject }.getOrNull() ?: kotlinx.serialization.json.JsonObject(emptyMap())
        val dose = target.copy(
            id = m.id, userId = m.userId ?: target.userId, createdAt = m.createdAt, recordedAt = newAt, supersedesId = target.id,
            units = newUnits, givenAt = newAt, inputId = inputId ?: target.inputId,
            overrideReason = reason.take(300),
            details = kotlinx.serialization.json.JsonObject(
                details + mapOf(
                    "correction_of" to kotlinx.serialization.json.JsonPrimitive(target.id),
                    "previous_units" to kotlinx.serialization.json.JsonPrimitive(target.units),
                    "previous_given_at" to kotlinx.serialization.json.JsonPrimitive(target.givenAt),
                ),
            ).toString(),
            syncState = 0,
        )
        db.doses().insert(dose)
        if (newUnits <= 0.0 && target.splitPart == 1 && target.proposalId != null) cancelSecond(target.proposalId)
        AppLog.i("Dose", "corrected ${target.id.take(8)}: ${formatUnits(target.units)} → ${formatUnits(newUnits)}" + if (newAt != target.givenAt) " at ${formatTime(newAt)}" else "")
        onWrite()
        val what = insulinLabel(target.insulin)
        val message = when {
            newUnits <= 0.0 -> "Removed the ${formatUnits(target.units)} $what dose from ${formatTime(target.givenAt)} — not counted as taken"
            newUnits != target.units && newAt != target.givenAt -> "Changed ${formatUnits(target.units)} → ${formatUnits(newUnits)} $what, taken at ${formatTime(newAt)}"
            newUnits != target.units -> "Changed ${formatUnits(target.units)} → ${formatUnits(newUnits)} $what (${formatTime(newAt)})"
            else -> "Moved the ${formatUnits(newUnits)} $what dose to ${formatTime(newAt)}"
        }
        return LoggedDose(dose, message, previous = target)
    }

    private fun insulinLabel(insulin: String) = if (insulin == "long") "long-acting" else "rapid"

    companion object {
        /** How far back a spoken correction ("never mind, only 5") may reach. */
        val CORRECTION_WINDOW: Duration = Duration.ofHours(2)
    }

    private fun secondUnitsOf(details: String): Int? = runCatching {
        AppJson.parseToJsonElement(details).jsonObject["second_units"]?.jsonPrimitive?.contentOrNull?.toDouble()?.toInt()
    }.getOrNull()
}
