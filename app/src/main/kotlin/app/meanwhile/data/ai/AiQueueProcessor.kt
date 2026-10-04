package app.meanwhile.data.ai

import android.content.Context
import android.util.Log
import app.meanwhile.data.db.AppDatabase
import app.meanwhile.data.json.AppJson
import app.meanwhile.data.profile.ProfileRepository
import app.meanwhile.data.profile.ProfileSource
import app.meanwhile.data.profile.ProfileStatus
import app.meanwhile.domain.factors.Activations
import app.meanwhile.domain.profile.FactorKind
import app.meanwhile.domain.profile.ProfileChange
import app.meanwhile.domain.profile.ProfileDiff
import app.meanwhile.domain.router.FactorIntent
import app.meanwhile.notify.Notifications
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant

/**
 * Runs AI calls queued while offline (spec §9.4): the offline fallback already applied default
 * weights; the AI refinement becomes a *pending* version Danny reviews (notification + review screen).
 */
class AiQueueProcessor(
    private val context: Context,
    private val db: AppDatabase,
    private val ai: AiClient,
    private val hooks: AiHooksImpl,
    private val profiles: ProfileRepository,
) {
    private val lock = Mutex()

    suspend fun runPending() = lock.withLock {
        if (!ai.reachable()) return@withLock
        for (item in db.aiQueue().all()) {
            if (item.job != "update_profile") {
                db.aiQueue().remove(item.id)
                continue
            }
            val payload = runCatching { AppJson.parseToJsonElement(item.payload).jsonObject }.getOrNull()
            val text = payload?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
            val intents = runCatching {
                AppJson.decodeFromJsonElement(ListSerializer(FactorIntent.serializer()), payload!!["intents"]!!)
            }.getOrDefault(emptyList())
            val at = Instant.ofEpochMilli(item.createdAt)
            val current = profiles.current()
            val out = hooks.requestUpdate(text, intents, current.profile, item.inputId, at)
            if (out == null) {
                if (!ai.reachable()) return@withLock // went offline again; keep the queue
                val attempts = item.attempts + 1
                if (attempts >= MAX_ATTEMPTS) db.aiQueue().remove(item.id)
                else db.aiQueue().upsert(item.copy(attempts = attempts, lastError = ai.status.value.lastError))
                continue
            }
            val (dto, ok) = out
            var proposed = current.profile.copy(
                factors = current.profile.factors.filterNot { f -> dto.newFactors.any { it.definition.id == f.id } } +
                    dto.newFactors.map { it.definition.toDomain() },
            )
            for (ch in hooks.toProposed(dto, proposed)) {
                if (ch.kind != FactorKind.MULTIPLIER && ch.kind != FactorKind.AUTO_MULTIPLIER) continue
                proposed = if (ch.action == "deactivate") {
                    Activations.deactivate(proposed, ch.factorId)
                } else {
                    val start = at.minus(Duration.ofMinutes((ch.startedMinutesAgo ?: 0).toLong())).toEpochMilli()
                    Activations.activate(
                        proposed, ch.factorId, start, "ai", weight = ch.weight, preset = ch.preset,
                        windowMinutes = ch.windowMinutes, decay = ch.decay, note = ch.reason,
                    )
                }
            }
            val reasons = hooks.toProposed(dto, proposed).associate { it.factorId to it.reason }
            val diff: List<ProfileChange> = ProfileDiff.diff(current.profile, proposed).map { c ->
                val id = c.path.substringAfter('.').substringBefore('.')
                c.copy(reason = reasons[id] ?: dto.summary)
            }
            if (diff.isNotEmpty()) {
                val v = profiles.saveVersion(
                    proposed, ProfileSource.AI_UPDATE, ProfileStatus.PENDING,
                    summary = "AI refinement of “$text”: ${dto.summary}".take(300),
                    changes = diff, aiCallId = ok.callId,
                )
                Notifications.post(
                    context, Notifications.ID_AI_REFINEMENT, Notifications.CHANNEL_REPORTS,
                    "AI refinement ready", "Review the AI's update for “$text”.", destination = "review/${v.id}",
                )
            }
            db.aiQueue().remove(item.id)
            Log.i("AiQueue", "processed ${item.id}: ${diff.size} changes")
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 6
    }
}
