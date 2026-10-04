package app.meanwhile.data.input

import app.meanwhile.data.RecordFactory
import app.meanwhile.data.db.AppDatabase
import app.meanwhile.data.db.FactorEventEntity
import app.meanwhile.data.db.ProfileVersionEntity
import app.meanwhile.data.profile.ProfileRepository
import app.meanwhile.data.profile.ProfileSource
import app.meanwhile.data.profile.ProfileStatus
import app.meanwhile.data.profile.decodedProfile
import app.meanwhile.domain.factors.Activations
import app.meanwhile.domain.profile.DecayRule
import app.meanwhile.domain.profile.FactorDefinition
import app.meanwhile.domain.profile.FactorKind
import app.meanwhile.domain.profile.Profile
import app.meanwhile.domain.profile.WindowType
import app.meanwhile.domain.router.FactorIntent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant

/**
 * Applies factor updates (spec §9.4). Offline/manual: log the factor event, apply the weight by code
 * as a new profile version immediately. Every change is append-only; "undo" writes compensating rows.
 */
class FactorUpdater(
    private val db: AppDatabase,
    private val records: RecordFactory,
    private val profiles: ProfileRepository,
    private val onWrite: () -> Unit,
) {
    data class Outcome(
        val changes: List<FactorChangeView>,
        val version: ProfileVersionEntity?,
        val events: List<FactorEventEntity>,
    )

    /** One factor change to apply. */
    data class Request(
        val factorId: String,
        val action: String = "activate",
        val weight: Double? = null,
        val preset: String? = null,
        val amount: Double? = null,
        val unitsAdd: Double? = null,
        val windowMinutes: Int? = null,
        val decay: DecayRule? = null,
        val at: Instant,
        val details: Map<String, String> = emptyMap(),
        val note: String? = null,
    )

    fun requestFrom(intent: FactorIntent, now: Instant) = Request(
        factorId = intent.factorId,
        action = intent.action,
        preset = intent.preset,
        amount = intent.amount,
        at = now.minus(Duration.ofMinutes((intent.minutesAgo ?: 0).toLong())),
        details = intent.details,
        note = describe(intent),
    )

    suspend fun apply(
        requests: List<Request>,
        inputId: String?,
        eventSource: String,
        versionSource: String,
        summary: String,
        aiCallId: String? = null,
        newDefinitions: List<FactorDefinition> = emptyList(),
        status: String = ProfileStatus.ACCEPTED,
        now: Instant = Instant.now(),
    ): Outcome {
        val before = profiles.current().profile
        var profile = if (newDefinitions.isEmpty()) before else before.copy(
            factors = before.factors.filterNot { f -> newDefinitions.any { it.id == f.id } } + newDefinitions,
        )
        val views = mutableListOf<FactorChangeView>()
        val events = mutableListOf<FactorEventEntity>()
        for (r in requests) {
            val def = profile.factor(r.factorId) ?: continue
            val m = records.meta(recordedAt = r.at.toEpochMilli(), now = now.toEpochMilli())
            val details = buildJsonObject {
                r.amount?.let { put("amount", it); if (def.kind == FactorKind.UNITS_PER_EVENT) put("cups", it) }
                r.preset?.let { put("preset", it) }
                r.note?.let { put("note", it) }
                r.details.forEach { (k, v) -> put(k, v) }
            }.toString()
            when {
                def.kind == FactorKind.UNITS_PER_EVENT -> {
                    val units = r.unitsAdd ?: ((r.amount ?: 1.0) * (def.unitsPerEvent ?: 1.0))
                    events += event(m.id, m.userId, m.createdAt, m.recordedAt, def, "value", null, null, units, eventSource, inputId, details)
                    views += FactorChangeView(def.id, def.name, "add", null, units, "until the next dose", r.note)
                }
                r.action == "deactivate" -> {
                    profile = Activations.deactivate(profile, def.id)
                    events += event(m.id, m.userId, m.createdAt, m.recordedAt, def, "deactivate", null, null, null, eventSource, inputId, details)
                    views += FactorChangeView(def.id, def.name, "deactivate", 1.0, null, "back to 1.00", r.note)
                }
                else -> {
                    val weight = r.weight ?: Activations.defaultWeight(profile, def.id, r.preset)
                    val window = r.windowMinutes ?: def.window.minutes
                    profile = Activations.activate(
                        profile, def.id, r.at.toEpochMilli(), eventSource, weight = r.weight, preset = r.preset,
                        windowMinutes = r.windowMinutes, decay = r.decay, eventId = m.id, note = r.note,
                    )
                    events += event(m.id, m.userId, m.createdAt, m.recordedAt, def, "activate", weight, window, null, eventSource, inputId, details)
                    views += FactorChangeView(def.id, def.name, "activate", weight, null, windowText(def, window), r.note)
                }
            }
        }
        db.factorEvents().insertAll(events)
        val version = if (profile != before) {
            profiles.saveVersion(profile, versionSource, status, summary, aiCallId = aiCallId, now = now)
        } else {
            null
        }
        onWrite()
        return Outcome(views, version, events)
    }

    /**
     * Reverses an [Outcome]: restores the affected factors' activations from before it and adds
     * compensating events (negative units for caffeine). Nothing is deleted.
     */
    suspend fun undo(outcome: Outcome, now: Instant = Instant.now()): Outcome {
        val current = profiles.current().profile
        val base: Profile = outcome.version?.baseVersionId?.let { profiles.byId(it)?.decodedProfile() } ?: Profile()
        val affected = outcome.events.map { it.factorId }.toSet()
        val restored = current.copy(
            active = current.active.filterNot { it.factorId in affected } + base.active.filter { it.factorId in affected },
        )
        val compensating = outcome.events.map { e ->
            val m = records.meta(recordedAt = e.recordedAt, now = now.toEpochMilli())
            e.copy(
                id = m.id, userId = m.userId, createdAt = m.createdAt, supersedesId = e.id,
                action = if (e.unitsAdd != null) "value" else "deactivate",
                unitsAdd = e.unitsAdd?.let { -it },
                source = "manual",
                details = JsonObject(mapOf("undo_of" to JsonPrimitive(e.id))).toString(),
                syncState = 0,
            )
        }
        db.factorEvents().insertAll(compensating)
        val version = if (restored != current) {
            profiles.saveVersion(restored, ProfileSource.MANUAL, ProfileStatus.ACCEPTED, "Undo: ${outcome.changes.joinToString { it.name }}", now = now)
        } else {
            null
        }
        onWrite()
        return Outcome(emptyList(), version, compensating)
    }

    private fun event(
        id: String, userId: String?, createdAt: Long, recordedAt: Long, def: FactorDefinition, action: String,
        weight: Double?, window: Int?, unitsAdd: Double?, source: String, inputId: String?, details: String,
    ) = FactorEventEntity(
        id = id, userId = userId, createdAt = createdAt, recordedAt = recordedAt, factorId = def.id,
        action = action, weight = weight, windowMinutes = window, unitsAdd = unitsAdd, source = source,
        inputId = inputId, details = details,
    )

    companion object {
        fun windowText(def: FactorDefinition, minutes: Int?): String = when (def.window.type) {
            WindowType.UNTIL_RESET -> "until 1 am"
            WindowType.FIXED, WindowType.AFTER_LAST_TRIGGER -> minutes?.let { if (it % 60 == 0) "${it / 60} h" else "$it min" } ?: "—"
            WindowType.CONSUMED_BY_NEXT_DOSE -> "until the next dose"
            WindowType.PER_MEAL -> "this meal"
        } + if (def.decay != null) ", decaying" else ""

        fun describe(i: FactorIntent): String? = buildList {
            i.preset?.let { add(it) }
            i.amount?.takeIf { it != 1.0 }?.let { add("× ${if (it % 1.0 == 0.0) it.toLong().toString() else it.toString()}") }
            i.details.forEach { (k, v) -> add("$k: $v") }
            i.minutesAgo?.let { add("$it min ago") }
        }.joinToString(", ").ifEmpty { null }
    }
}
