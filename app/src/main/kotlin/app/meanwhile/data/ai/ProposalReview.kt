package app.meanwhile.data.ai

import app.meanwhile.data.RecordFactory
import app.meanwhile.data.db.AppDatabase
import app.meanwhile.data.db.FactorEventEntity
import app.meanwhile.data.db.ProfileVersionEntity
import app.meanwhile.data.json.AppJson
import app.meanwhile.data.profile.ProfileRepository
import app.meanwhile.data.profile.ProfileSource
import app.meanwhile.data.profile.ProfileStatus
import app.meanwhile.data.profile.changes
import app.meanwhile.domain.profile.ActiveFactor
import app.meanwhile.domain.profile.ProfileChange
import app.meanwhile.domain.profile.ProfileJson
import app.meanwhile.domain.profile.ProfilePatch
import app.meanwhile.domain.profile.ProfileValidation
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.time.Instant

/** Danny's decision on one proposed change. */
data class ChangeDecision(val change: ProfileChange, val decision: String, val editedValue: JsonElement? = null)

/**
 * Accept / edit / reject proposed profile changes (spec §11.3, §9.4 refinements). The pending
 * version stays as proposed; the decision is a new version that supersedes it (append-only).
 * Changes are re-applied onto the *current* profile, so anything that changed meanwhile is kept.
 */
class ProposalReview(
    private val db: AppDatabase,
    private val records: RecordFactory,
    private val profiles: ProfileRepository,
    private val onWrite: () -> Unit,
    /** Learned changes Danny accepted (learning sources only): start watching their outcomes. */
    private val onLearnedApplied: suspend (ProfileVersionEntity, List<ProfileChange>) -> Unit = { _, _ -> },
) {
    suspend fun decide(pending: ProfileVersionEntity, decisions: List<ChangeDecision>, now: Instant = Instant.now()): Result<ProfileVersionEntity> = runCatching {
        val annotated = decisions.map { d ->
            d.change.copy(
                new = if (d.decision == "edited") d.editedValue ?: d.change.new else d.change.new,
                decision = d.decision,
            )
        }
        val applied = annotated.filter { it.decision == "accepted" || it.decision == "edited" }
        val current = profiles.current().profile
        val status = when {
            applied.isEmpty() -> ProfileStatus.REJECTED
            annotated.any { it.decision == "edited" } -> ProfileStatus.EDITED
            else -> ProfileStatus.ACCEPTED
        }
        val updated = if (applied.isEmpty()) current else ProfilePatch.apply(current, applied).getOrThrow()
        val problems = ProfileValidation.problems(updated)
        require(problems.isEmpty()) { "these values would break the dose math — " + problems.joinToString("; ") + ". Edit or reject those changes." }
        val version = profiles.saveVersion(
            updated, pending.source, status,
            summary = "${if (applied.isEmpty()) "Rejected" else "Applied ${applied.size} of ${annotated.size}"}: ${pending.summary}".take(300),
            changes = annotated, supersedesId = pending.id, aiCallId = pending.aiCallId, now = now,
        )
        logActivationEvents(applied, version.id, now)
        if (applied.isNotEmpty() && pending.source in LEARNING_SOURCES) onLearnedApplied(version, applied)
        onWrite()
        version
    }

    fun proposedChanges(pending: ProfileVersionEntity): List<ProfileChange> = pending.changes()

    private companion object {
        val LEARNING_SOURCES = setOf(ProfileSource.LEARN_CYCLE, ProfileSource.AUTO_TUNE, ProfileSource.AUTO_REVERT)
    }

    /** Accepted `active.F*` changes are factor activations: record them as factor events (source ai). */
    private suspend fun logActivationEvents(applied: List<ProfileChange>, versionId: String, now: Instant) {
        val events = applied.filter { it.path.startsWith("active.") }.mapNotNull { ch ->
            val factorId = ch.path.removePrefix("active.")
            val activation = ch.new?.takeIf { it !is JsonNull }?.let {
                runCatching { ProfileJson.json.decodeFromJsonElement(ActiveFactor.serializer(), it) }.getOrNull()
            }
            val m = records.meta(recordedAt = activation?.startedAt ?: now.toEpochMilli(), now = now.toEpochMilli())
            FactorEventEntity(
                id = m.id, userId = m.userId, createdAt = m.createdAt, recordedAt = m.recordedAt,
                factorId = factorId, action = if (activation == null) "deactivate" else "activate",
                weight = activation?.weight, windowMinutes = activation?.windowMinutes, source = "ai",
                profileVersionId = versionId, details = AppJson.encodeToString(ProfileChange.serializer(), ch),
            )
        }
        if (events.isNotEmpty()) db.factorEvents().insertAll(events)
    }
}
