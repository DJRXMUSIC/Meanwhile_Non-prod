package app.meanwhile.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.meanwhile.data.json.EpochMillisIso
import app.meanwhile.data.json.JsonText
import app.meanwhile.data.json.NullableEpochMillisIso
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/*
 * Append-only records (spec §12). Every row: UUIDv7 `id`, `userId`, `createdAt` (device time, UTC),
 * `recordedAt` (when it happened), optional `supersedesId` for corrections, and a local-only
 * `syncState`. Rows are never updated or deleted — except `syncState`, which is local bookkeeping.
 *
 * Each class doubles as the Supabase row: kotlinx-serialization with [app.meanwhile.data.json.RecordJson]
 * produces snake_case columns, ISO timestamps and real JSON for json-text columns.
 */

object SyncState {
    const val PENDING = 0
    const val SYNCED = 1
    /** Server rejected the row (e.g. constraint violation); kept locally, excluded from retries. */
    const val REJECTED = 2
}

interface Record {
    val id: String
    val userId: String?
    val createdAt: Long
    val recordedAt: Long
    val supersedesId: String?
    val syncState: Int
}

@Serializable
@Entity(
    tableName = "cgm_readings",
    indices = [Index(value = ["recordedAt"], unique = true), Index("syncState")],
)
data class CgmReadingEntity(
    @PrimaryKey override val id: String,
    override val userId: String? = null,
    @Serializable(with = EpochMillisIso::class) override val createdAt: Long,
    @Serializable(with = EpochMillisIso::class) override val recordedAt: Long,
    override val supersedesId: String? = null,
    val mgDl: Int,
    /** mg/dL per minute; positive = rising. */
    val trendRate: Double? = null,
    /** Nightscout direction name as reported by the source (e.g. "Flat", "SingleUp"). */
    val direction: String? = null,
    val source: String,
    val sensorStatus: String? = null,
    @Transient override val syncState: Int = SyncState.PENDING,
) : Record

@Serializable
@Entity(tableName = "meals", indices = [Index("recordedAt"), Index("syncState")])
data class MealEntity(
    @PrimaryKey override val id: String,
    override val userId: String? = null,
    @Serializable(with = EpochMillisIso::class) override val createdAt: Long,
    @Serializable(with = EpochMillisIso::class) override val recordedAt: Long,
    override val supersedesId: String? = null,
    val carbsG: Double,
    val fatG: Double,
    val proteinG: Double,
    val liquidOrSugary: Boolean = false,
    val isEstimate: Boolean = false,
    val description: String = "",
    val inputId: String? = null,
    /** e.g. the AI's original estimate, provider/model, notes. */
    @Serializable(with = JsonText::class) val details: String = "{}",
    @Transient override val syncState: Int = SyncState.PENDING,
) : Record

@Serializable
@Entity(tableName = "factor_events", indices = [Index("recordedAt"), Index("factorId"), Index("syncState")])
data class FactorEventEntity(
    @PrimaryKey override val id: String,
    override val userId: String? = null,
    @Serializable(with = EpochMillisIso::class) override val createdAt: Long,
    @Serializable(with = EpochMillisIso::class) override val recordedAt: Long,
    override val supersedesId: String? = null,
    val factorId: String,
    /** activate | deactivate | value */
    val action: String,
    val weight: Double? = null,
    val windowMinutes: Int? = null,
    val unitsAdd: Double? = null,
    /** ai | offline | manual | auto | morning_report */
    val source: String,
    val inputId: String? = null,
    val profileVersionId: String? = null,
    /** cups, workout type/duration/intensity, drink type, preset, … */
    @Serializable(with = JsonText::class) val details: String = "{}",
    @Transient override val syncState: Int = SyncState.PENDING,
) : Record

@Serializable
@Entity(tableName = "doses", indices = [Index("recordedAt"), Index("proposalId"), Index("syncState")])
data class DoseEntity(
    @PrimaryKey override val id: String,
    override val userId: String? = null,
    @Serializable(with = EpochMillisIso::class) override val createdAt: Long,
    /** Same instant as [givenAt]. */
    @Serializable(with = EpochMillisIso::class) override val recordedAt: Long,
    override val supersedesId: String? = null,
    /** rapid | long */
    val insulin: String,
    val units: Double,
    @Serializable(with = EpochMillisIso::class) val givenAt: Long,
    val proposalId: String? = null,
    /** 1 or 2 for split doses, null otherwise. */
    val splitPart: Int? = null,
    /** Units the proposal recommended for this injection, when there was one. */
    val proposedUnits: Double? = null,
    val overrideReason: String? = null,
    val inputId: String? = null,
    @Serializable(with = JsonText::class) val details: String = "{}",
    @Transient override val syncState: Int = SyncState.PENDING,
) : Record

@Serializable
@Entity(tableName = "proposals", indices = [Index("recordedAt"), Index("syncState")])
data class ProposalEntity(
    @PrimaryKey override val id: String,
    override val userId: String? = null,
    @Serializable(with = EpochMillisIso::class) override val createdAt: Long,
    @Serializable(with = EpochMillisIso::class) override val recordedAt: Long,
    override val supersedesId: String? = null,
    @Serializable(with = JsonText::class) val inputSnapshot: String,
    @Serializable(with = JsonText::class) val breakdown: String,
    val finalUnits: Int,
    val leadTimeMin: Int? = null,
    @Serializable(with = JsonText::class) val splitPlan: String = "null",
    val profileVersionId: String? = null,
    val inputId: String? = null,
    val mealId: String? = null,
    @Transient override val syncState: Int = SyncState.PENDING,
) : Record

@Serializable
@Entity(tableName = "outcomes", indices = [Index("recordedAt"), Index(value = ["doseId"]), Index("syncState")])
data class OutcomeEntity(
    @PrimaryKey override val id: String,
    override val userId: String? = null,
    @Serializable(with = EpochMillisIso::class) override val createdAt: Long,
    @Serializable(with = EpochMillisIso::class) override val recordedAt: Long,
    override val supersedesId: String? = null,
    val doseId: String,
    @SerialName("bg_2h") val bg2h: Int? = null,
    @SerialName("bg_3h") val bg3h: Int? = null,
    @SerialName("bg_4h") val bg4h: Int? = null,
    @SerialName("min_4h") val min4h: Int? = null,
    @SerialName("max_4h") val max4h: Int? = null,
    @Transient override val syncState: Int = SyncState.PENDING,
) : Record

@Serializable
@Entity(tableName = "profile_versions", indices = [Index("recordedAt"), Index("version"), Index("syncState")])
data class ProfileVersionEntity(
    @PrimaryKey override val id: String,
    override val userId: String? = null,
    @Serializable(with = EpochMillisIso::class) override val createdAt: Long,
    @Serializable(with = EpochMillisIso::class) override val recordedAt: Long,
    override val supersedesId: String? = null,
    val version: Int,
    /** learn_cycle | ai_update | offline_fallback | manual | auto_f11 | sleep_checkin | revert */
    val source: String,
    /** pending | accepted | edited | rejected */
    val status: String,
    @Serializable(with = JsonText::class) val profile: String,
    @Serializable(with = JsonText::class) val diff: String = "[]",
    @Serializable(with = NullableEpochMillisIso::class) val decidedAt: Long? = null,
    val baseVersionId: String? = null,
    val aiCallId: String? = null,
    val summary: String = "",
    @Transient override val syncState: Int = SyncState.PENDING,
) : Record

@Serializable
@Entity(tableName = "factor_definitions", indices = [Index("recordedAt"), Index("factorId"), Index("syncState")])
data class FactorDefinitionEntity(
    @PrimaryKey override val id: String,
    override val userId: String? = null,
    @Serializable(with = EpochMillisIso::class) override val createdAt: Long,
    @Serializable(with = EpochMillisIso::class) override val recordedAt: Long,
    override val supersedesId: String? = null,
    val factorId: String,
    @Serializable(with = JsonText::class) val definition: String,
    val profileVersionId: String? = null,
    @Transient override val syncState: Int = SyncState.PENDING,
) : Record

@Serializable
@Entity(tableName = "ai_calls", indices = [Index("recordedAt"), Index("syncState")])
data class AiCallEntity(
    @PrimaryKey override val id: String,
    override val userId: String? = null,
    @Serializable(with = EpochMillisIso::class) override val createdAt: Long,
    @Serializable(with = EpochMillisIso::class) override val recordedAt: Long,
    override val supersedesId: String? = null,
    /** route | estimate_meal | update_profile | learn_cycle */
    val job: String,
    val provider: String? = null,
    val model: String? = null,
    val latencyMs: Long? = null,
    val fallbackUsed: Boolean = false,
    val requestSummary: String = "",
    @Serializable(with = JsonText::class) val response: String = "null",
    /** ok | invalid | error | offline | submitted */
    val validation: String,
    val error: String? = null,
    val inputId: String? = null,
    /** 1.4: the full request sent to the AI layer (job, provider preference, payload) — word for word, for later analysis. */
    @Serializable(with = JsonText::class) val request: String = "{}",
    @Transient override val syncState: Int = SyncState.PENDING,
) : Record

@Serializable
@Entity(tableName = "feedback", indices = [Index("recordedAt"), Index("syncState")])
data class FeedbackEntity(
    @PrimaryKey override val id: String,
    override val userId: String? = null,
    @Serializable(with = EpochMillisIso::class) override val createdAt: Long,
    @Serializable(with = EpochMillisIso::class) override val recordedAt: Long,
    override val supersedesId: String? = null,
    val text: String,
    /** Screen / context the feedback was given from. */
    val context: String = "",
    @Transient override val syncState: Int = SyncState.PENDING,
) : Record

@Serializable
@Entity(tableName = "inputs", indices = [Index("recordedAt"), Index("syncState")])
data class InputEntity(
    @PrimaryKey override val id: String,
    override val userId: String? = null,
    @Serializable(with = EpochMillisIso::class) override val createdAt: Long,
    @Serializable(with = EpochMillisIso::class) override val recordedAt: Long,
    override val supersedesId: String? = null,
    val rawText: String,
    /** voice | text */
    val via: String,
    /** ai | offline */
    val router: String,
    @Serializable(with = JsonText::class) val routerResult: String = "null",
    /** Comma-separated intents acted on, e.g. "factor_update,meal". */
    val pathTaken: String,
    val manualSwitch: Boolean = false,
    @Transient override val syncState: Int = SyncState.PENDING,
) : Record

/**
 * The learning journal (1.3): what the app learned, changed, kept and reverted, in order. Append-only
 * like everything else — an `applied` change is closed by a later `kept` / `reverted` / `undone` row
 * whose `supersedesId` points at it.
 */
@Serializable
@Entity(tableName = "learning_log", indices = [Index("recordedAt"), Index("syncState"), Index("kind")])
data class LearningLogEntity(
    @PrimaryKey override val id: String,
    override val userId: String? = null,
    @Serializable(with = EpochMillisIso::class) override val createdAt: Long,
    @Serializable(with = EpochMillisIso::class) override val recordedAt: Long,
    override val supersedesId: String? = null,
    /** lessons | ai_review | applied | proposed | kept | reverted | undone | error */
    val kind: String,
    val summary: String,
    @Serializable(with = JsonText::class) val details: String = "{}",
    val profileVersionId: String? = null,
    val aiCallId: String? = null,
    @Transient override val syncState: Int = SyncState.PENDING,
) : Record

/**
 * The conversation, word for word (1.4): what Danny said or typed (with the recognizer's alternatives),
 * every processing step, what the app answered (text plus the full card data), and what he tapped.
 * Written for AI analysis, not for reading: nothing is summarised or left out.
 */
@Serializable
@Entity(tableName = "conversation_log", indices = [Index("recordedAt"), Index("syncState"), Index("inputId")])
data class ConversationLogEntity(
    @PrimaryKey override val id: String,
    override val userId: String? = null,
    @Serializable(with = EpochMillisIso::class) override val createdAt: Long,
    @Serializable(with = EpochMillisIso::class) override val recordedAt: Long,
    override val supersedesId: String? = null,
    /** user | app | system */
    val role: String,
    /** message | step | reply | action | error */
    val kind: String,
    /** Exactly what was said or shown. */
    val text: String,
    @Serializable(with = JsonText::class) val details: String = "{}",
    /** The message (inputs row) this belongs to. */
    val inputId: String? = null,
    @Transient override val syncState: Int = SyncState.PENDING,
) : Record

/** Local-only queue of AI calls deferred while offline (spec §9.4). Not synced; rows are removed when done. */
@Entity(tableName = "ai_queue")
data class AiQueueEntity(
    @PrimaryKey val id: String,
    val job: String,
    val payload: String,
    val inputId: String?,
    /** Profile version created by the offline fallback that this call refines. */
    val fallbackVersionId: String?,
    val createdAt: Long,
    val attempts: Int = 0,
    val lastError: String? = null,
)
