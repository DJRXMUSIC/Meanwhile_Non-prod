package app.meanwhile.data.input

import app.meanwhile.data.RecordFactory
import app.meanwhile.data.db.AppDatabase
import app.meanwhile.data.db.ConversationLogEntity
import app.meanwhile.data.json.AppJson
import app.meanwhile.domain.dose.DoseInput
import app.meanwhile.domain.dose.DoseResult
import app.meanwhile.domain.nba.NextBestAction
import app.meanwhile.domain.router.RoutedIntent
import app.meanwhile.format.fmt
import app.meanwhile.log.AppLog
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant

/** What the speech recognizer heard, beyond the text sent (1.4: logged word for word). */
data class VoiceDetails(
    val alternatives: List<String> = emptyList(),
    val confidences: List<Float> = emptyList(),
    val listenedMs: Long? = null,
    val language: String? = null,
)

/**
 * Writes the conversation to `conversation_log` (1.4): Danny's words exactly as sent, each processing
 * step, the app's answer as shown (plus the full card data) and every button he taps. It is for AI
 * analysis, so nothing is summarised. Logging never breaks processing.
 */
class ConversationLog(
    private val db: AppDatabase,
    private val records: RecordFactory,
    private val onWrite: () -> Unit,
) {
    private val lock = Any()
    private var lastAt = 0L

    /** Strictly increasing timestamps keep the transcript in order even within one millisecond. */
    private fun stamp(at: Instant): Long = synchronized(lock) {
        val t = maxOf(at.toEpochMilli(), lastAt + 1)
        lastAt = t
        t
    }

    suspend fun write(role: String, kind: String, text: String, details: JsonObject = EMPTY, inputId: String? = null, at: Instant = Instant.now()) {
        try {
            val t = stamp(at)
            val m = records.meta(recordedAt = t, now = t)
            db.conversation().insert(
                ConversationLogEntity(
                    id = m.id, userId = m.userId, createdAt = m.createdAt, recordedAt = m.recordedAt,
                    role = role, kind = kind, text = text, details = details.toString(), inputId = inputId?.takeIf { UUID.matches(it) },
                ),
            )
            onWrite()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (AppLog.throttle("conversation-log", 10 * 60_000L)) AppLog.e("Conversation", "couldn't write the conversation log: ${e.message}", e)
        }
    }

    suspend fun message(inputId: String, text: String, via: String, voice: VoiceDetails?, at: Instant, extra: JsonObject = EMPTY) =
        write(
            ROLE_USER, KIND_MESSAGE, text,
            buildJsonObject {
                put("via", via)
                voice?.let { v ->
                    putJsonArray("alternatives") { v.alternatives.forEach { add(JsonPrimitive(it)) } }
                    putJsonArray("confidences") { v.confidences.forEach { add(JsonPrimitive(it)) } }
                    v.listenedMs?.let { put("listened_ms", it) }
                    v.language?.let { put("language", it) }
                }
                extra.forEach { (k, v) -> put(k, v) }
            },
            inputId, at,
        )

    suspend fun step(inputId: String, step: Step) =
        write(
            ROLE_SYSTEM, KIND_STEP, step.label + (step.detail?.let { ": $it" } ?: ""),
            buildJsonObject {
                put("step", step.id)
                put("state", step.state.name.lowercase())
                put("started_at", step.startedAt)
                step.endedAt?.let { put("ms", it - step.startedAt) }
            },
            inputId, Instant.ofEpochMilli(step.endedAt ?: step.startedAt),
        )

    suspend fun reply(session: InputSession, ms: Long, at: Instant = Instant.now()) =
        write(
            ROLE_APP, KIND_REPLY, CardText.reply(session.cards),
            buildJsonObject {
                put("router", session.route.router)
                put("path", session.route.path)
                put("intents", AppJson.encodeToJsonElement(ListSerializer(RoutedIntent.serializer()), session.route.intents))
                put("ms", ms)
                putJsonArray("cards") { session.cards.forEach { add(CardText.json(it)) } }
            },
            session.inputId, at,
        )

    /** A button Danny tapped (or an outcome he caused) and what it did. */
    suspend fun action(inputId: String?, text: String, details: JsonObject = EMPTY) = write(ROLE_USER, KIND_ACTION, text, details, inputId)

    suspend fun error(inputId: String?, text: String) = write(ROLE_APP, KIND_ERROR, text, EMPTY, inputId)

    /** A notification the app posted (title and text as shown). */
    suspend fun notification(text: String, details: JsonObject = EMPTY, inputId: String? = null, at: Instant = Instant.now()) =
        write(ROLE_APP, KIND_NOTIFICATION, text, details, inputId, at)

    /** Messages, replies, taps and errors since [from] (the history shown above this session's chat). */
    suspend fun transcriptSince(from: Long): List<ConversationLogEntity> = db.conversation().transcriptSince(from)

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_APP = "app"
        const val ROLE_SYSTEM = "system"
        const val KIND_MESSAGE = "message"
        const val KIND_STEP = "step"
        const val KIND_REPLY = "reply"
        const val KIND_ACTION = "action"
        const val KIND_ERROR = "error"
        const val KIND_NOTIFICATION = "notification"
        private val EMPTY = JsonObject(emptyMap())
        private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}

/** The app's answer as words (what the screen says) and as data (everything on the card). */
object CardText {

    fun reply(cards: List<ResultCard>): String = cards.joinToString("\n") { text(it) }.ifBlank { "(no answer)" }

    fun text(card: ResultCard): String = when (card) {
        is NbaCard -> card.action.sentence + (card.loggedMessage?.let { " · $it" } ?: "")
        is DoseLoggedCard -> card.message
        is MealLoggedCard -> card.message
        is DoseConfirmCard -> card.loggedMessage ?: "How many units did you take?"
        is ReplyCard -> card.text
        is FeedbackSavedCard -> "Saved to feedback: ${card.text}"
        is FactorUpdateCard -> "Profile updated: " + card.changes.joinToString("; ") { change(it) } +
            if (card.aiQueued) " (AI refinement queued)" else ""
        is AiProposalCard -> "AI proposes: " + card.changes.joinToString("; ") { ch ->
            "${ch.name} " + when {
                ch.action == "deactivate" -> "off"
                ch.unitsAdd != null -> "+${fmt(ch.unitsAdd, 1)} u"
                else -> "weight ${ch.weight?.let { fmt(it) } ?: "default"}"
            }
        } + (card.summary.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "") + (card.decidedMessage?.let { " · $it" } ?: " — accept?")
        is MealMacrosCard -> if (card.hasNumbers) {
            "${card.draft.description}: estimated ${fmt(card.draft.carbsG, 0)} g carbs, ${fmt(card.draft.fatG, 0)} g fat, ${fmt(card.draft.proteinG, 0)} g protein — confirm or edit"
        } else {
            "${card.draft.description}: how many carbs, fat and protein?" + (card.note?.let { " ($it)" } ?: "")
        }
        is FactorPickerCard -> "Which factor did you mean by “${card.text}”?"
        is InfoCard -> card.message
    }

    private fun change(c: FactorChangeView) = "${c.name} " + when (c.action) {
        "add" -> "+${fmt(c.units ?: 0.0, 1)} u"
        "deactivate" -> "off"
        else -> "weight ${c.weight?.let { fmt(it) } ?: "—"}"
    } + " (${c.window})"

    fun json(card: ResultCard): JsonElement = buildJsonObject {
        put("type", card::class.java.simpleName)
        put("key", card.key)
        put("text", text(card))
        when (card) {
            is NbaCard -> {
                put("proposal_id", card.proposalId)
                put("action", AppJson.encodeToJsonElement(NextBestAction.serializer(), card.action))
                put("meal", AppJson.encodeToJsonElement(MealDraft.serializer(), card.meal))
                put("dose_input", AppJson.encodeToJsonElement(DoseInput.serializer(), card.input))
                put("dose_result", AppJson.encodeToJsonElement(DoseResult.serializer(), card.result))
                put("profile", card.profileLabel)
                put("bg_age_min", card.bgAgeMinutes?.let { JsonPrimitive(it) } ?: JsonNull)
                put("bg_stale", card.bgStale)
                card.bgOverride?.let { put("bg_override", it) }
                put("compute_ms", card.computeMs)
            }
            is DoseLoggedCard -> {
                put("dose_id", card.dose.id)
                put("units", card.dose.units)
                put("insulin", card.dose.insulin)
                put("given_at", card.dose.givenAt)
                card.dose.proposalId?.let { put("proposal_id", it) }
                card.previous?.let { put("supersedes", it.id); put("previous_units", it.units) }
            }
            is MealLoggedCard -> put("proposal_id", card.proposalId)
            is AiProposalCard -> {
                put("ai_call_id", card.callId)
                put("provider", card.provider)
                put("model", card.model)
                put("summary", card.summary)
            }
            is FactorUpdateCard -> {
                put("source", card.source)
                card.outcome.version?.let { put("profile_version", it.version) }
            }
            is MealMacrosCard -> put("draft", AppJson.encodeToJsonElement(MealDraft.serializer(), card.draft))
            else -> Unit
        }
    }
}

/** One visible processing step of a message (1.4: Danny sees what is happening while he waits). */
data class Step(
    val id: String,
    val label: String,
    val state: StepState,
    val detail: String? = null,
    val startedAt: Long,
    val endedAt: Long? = null,
)

enum class StepState { RUNNING, DONE, FAILED }
