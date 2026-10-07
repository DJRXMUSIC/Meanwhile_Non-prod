package app.meanwhile.data.ai

import app.meanwhile.log.AppLog
import app.meanwhile.data.RecordFactory
import app.meanwhile.data.db.AiCallEntity
import app.meanwhile.data.db.AppDatabase
import app.meanwhile.data.json.AppJson
import app.meanwhile.data.net.NetworkMonitor
import app.meanwhile.data.remote.AuthRepository
import app.meanwhile.data.settings.AiProviderPreference
import app.meanwhile.data.settings.SettingsStore
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import io.ktor.client.plugins.timeout
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class AiStatus(
    val configured: Boolean = false,
    val lastOkAt: Long? = null,
    val lastErrorAt: Long? = null,
    val lastError: String? = null,
)

sealed interface AiOutcome {
    data class Ok(
        val result: JsonElement,
        val provider: String,
        val model: String,
        val latencyMs: Long,
        val fallbackUsed: Boolean,
        val callId: String,
    ) : AiOutcome

    data class Failed(val reason: String, val callId: String?, val retryWith: String? = null) : AiOutcome

    /** A learn-cycle batch accepted or still running at Anthropic (1.4); poll again later. */
    data class Pending(val batchId: String, val status: String, val model: String?, val callId: String?) : AiOutcome
}

/**
 * Calls the `ai` Edge Function (spec §10). Every call — success or failure — is stored in `ai_calls`
 * (job, provider, model, latency, fallback, request summary, response, validation; never keys).
 */
class AiClient(
    private val client: SupabaseClient?,
    private val db: AppDatabase,
    private val records: RecordFactory,
    private val auth: AuthRepository,
    private val settings: SettingsStore,
    private val network: NetworkMonitor,
    private val onWrite: () -> Unit,
) {
    private val _status = MutableStateFlow(AiStatus(configured = client != null))
    val status: StateFlow<AiStatus> = _status

    /** The most recent call's provider, model and timing — shown on the message's progress line. */
    @Volatile
    var lastCall: app.meanwhile.data.input.AiCallInfo? = null
        private set

    /** Usable right now: configured, signed in with a live session, network up. */
    fun reachable(): Boolean = client != null && network.online.value && auth.currentUserId() != null

    /**
     * What the AI is doing right now, in words ("Asking Gemini…", "Gemini didn't answer — asking
     * Claude…"), shown live on the message's step line (1.4: never wait without knowing why).
     */
    private val _activity = MutableStateFlow<String?>(null)
    val activity: StateFlow<String?> = _activity

    suspend fun call(
        job: String,
        payload: JsonObject,
        inputId: String?,
        summary: String,
        timeoutMs: Long,
        preference: AiProviderPreference? = null,
        preferenceWire: String? = null,
        /** Learn-cycle batches (1.4): {"mode": "submit"} or {"mode": "poll", "id": …}. */
        batch: JsonObject? = null,
    ): AiOutcome {
        val pref = preferenceWire ?: (preference ?: settings.current().aiProvider).wire
        // Jobs Danny waits on try one provider per request, so the screen can say which one is
        // working and when it moves on to the other (the function would otherwise do it silently).
        val order = when {
            batch != null || job == "learn_cycle" -> null
            pref == AiProviderPreference.GEMINI_FIRST.wire -> listOf(AiProviderPreference.GEMINI_ONLY, AiProviderPreference.CLAUDE_ONLY)
            pref == AiProviderPreference.CLAUDE_FIRST.wire -> listOf(AiProviderPreference.CLAUDE_ONLY, AiProviderPreference.GEMINI_ONLY)
            else -> null
        }
        if (order == null) {
            _activity.value = "Asking ${providerName(pref)}"
            return try {
                callOnce(job, payload, inputId, summary, timeoutMs, pref, batch)
            } finally {
                _activity.value = null
            }
        }
        var last: AiOutcome? = null
        try {
            for ((i, p) in order.withIndex()) {
                _activity.value = if (i == 0) "Asking ${providerName(p.wire)}" else
                    "${providerName(order[0].wire)} didn't answer (${shortReason(last)}) — asking ${providerName(p.wire)}"
                val out = callOnce(job, payload, inputId, summary, timeoutMs, p.wire, null)
                if (out !is AiOutcome.Failed) return out
                last = out
                // Nothing to gain from the other provider when the problem is on this side.
                if (out.callId == null) return out
            }
        } finally {
            _activity.value = null
        }
        return last ?: AiOutcome.Failed("no provider", null)
    }

    private fun providerName(wire: String) = when {
        wire.startsWith("gemini") -> "Gemini"
        wire.startsWith("claude") -> "Claude"
        else -> "the AI"
    }

    private fun shortReason(o: AiOutcome?): String {
        val r = (o as? AiOutcome.Failed)?.reason ?: return "no answer"
        return when {
            r.contains("timed out", ignoreCase = true) -> "timed out"
            r.contains("429") || r.contains("rate", ignoreCase = true) -> "busy"
            else -> r.substringBefore(" · ").take(60)
        }
    }

    private suspend fun callOnce(
        job: String,
        payload: JsonObject,
        inputId: String?,
        summary: String,
        timeoutMs: Long,
        pref: String,
        batch: JsonObject?,
    ): AiOutcome {
        val c = client ?: return AiOutcome.Failed("AI not configured", null)
        if (!network.online.value) return AiOutcome.Failed("offline", null)
        auth.awaitSessionUserId(3_000) ?: return AiOutcome.Failed("not signed in", null)
        val started = System.currentTimeMillis()
        val body = buildJsonObject {
            put("job", job)
            put("payload", payload)
            put("provider_preference", pref)
            batch?.let { put("batch", it) }
        }
        // timeoutMs is this request's wait (one provider for the quick jobs, up to 60 s at the function).
        val (envelope, httpError) = try {
            val text = withTimeout(timeoutMs + 5_000) {
                c.functions.invoke("ai") {
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                    timeout {
                        requestTimeoutMillis = timeoutMs + 3_000
                        socketTimeoutMillis = timeoutMs + 3_000
                    }
                }.bodyAsText()
            }
            parse(text) to null
        } catch (e: RestException) {
            // Non-2xx: the body (with per-provider attempts) is in `error`.
            (runCatching { parse(e.error) }.getOrNull()) to "HTTP ${e.response.status.value}"
        } catch (e: TimeoutCancellationException) {
            null to "timed out after ${timeoutMs + 5_000} ms"
        } catch (e: CancellationException) {
            throw e // the caller went away (screen closed, worker stopped, Skip AI tapped): don't log a failure
        } catch (e: Exception) {
            null to (e.message ?: e::class.java.simpleName)
        }
        val latency = System.currentTimeMillis() - started
        val batchInfo = envelope?.batch
        if (envelope != null && batchInfo != null && envelope.result == null && envelope.error == null && httpError == null) {
            // A batch was accepted ("submitted") or is still running ("processing"): only the submit is a call worth a row.
            var callId: String? = null
            if (batchInfo.status == "submitted") {
                val m = records.meta(now = started)
                callId = m.id
                db.aiCalls().insert(
                    AiCallEntity(
                        id = m.id, userId = m.userId, createdAt = m.createdAt, recordedAt = m.recordedAt, job = job,
                        provider = envelope.provider ?: "claude", model = batchInfo.model ?: envelope.model, latencyMs = latency,
                        requestSummary = summary.take(500), response = AppJson.encodeToString(AiEnvelope.serializer(), envelope),
                        validation = "submitted", inputId = inputId, request = body.toString(),
                    ),
                )
                onWrite()
                AppLog.i("AI", "$job batch ${batchInfo.id} submitted (${batchInfo.model ?: envelope.model})")
            }
            return AiOutcome.Pending(batchInfo.id, batchInfo.status, batchInfo.model ?: envelope.model, callId)
        }
        val ok = envelope?.result != null && envelope.provider != null
        val error = if (ok) null else listOfNotNull(httpError, envelope?.error, envelope?.attempts?.joinToString("; ") { "${it.provider}: ${it.error}" })
            .joinToString(" · ").ifBlank { "unknown error" }
        val validation = when {
            ok -> "ok"
            envelope?.attempts?.any { it.validation == "invalid" } == true -> "invalid"
            else -> "error"
        }
        val m = records.meta(now = started)
        db.aiCalls().insert(
            AiCallEntity(
                id = m.id, userId = m.userId, createdAt = m.createdAt, recordedAt = m.recordedAt,
                job = job,
                provider = envelope?.provider ?: envelope?.attempts?.lastOrNull()?.provider,
                model = envelope?.model ?: envelope?.attempts?.lastOrNull()?.model,
                latencyMs = envelope?.latencyMs ?: latency,
                fallbackUsed = envelope?.fallbackUsed ?: false,
                requestSummary = summary.take(500),
                response = buildJsonObject {
                    put("result", envelope?.result ?: JsonNull)
                    put("attempts", AppJson.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(AiAttempt.serializer()), envelope?.attempts ?: emptyList()))
                    put("preference", pref)
                    envelope?.batch?.let { put("batch", AppJson.encodeToJsonElement(AiBatch.serializer(), it)) }
                }.toString(),
                validation = validation,
                error = error,
                inputId = inputId,
                request = body.toString(),
            ),
        )
        onWrite()
        lastCall = app.meanwhile.data.input.AiCallInfo(
            provider = envelope?.provider ?: envelope?.attempts?.lastOrNull()?.provider,
            model = envelope?.model ?: envelope?.attempts?.lastOrNull()?.model,
            latencyMs = latency, fallbackUsed = envelope?.fallbackUsed ?: false, error = error,
        )
        if (ok) {
            AppLog.i("AI", "$job ok · ${envelope.provider}/${envelope.model} · ${latency} ms" + if (envelope.fallbackUsed) " (fallback)" else "")
        } else {
            AppLog.w("AI", "$job failed after $latency ms: $error")
        }
        return if (ok) {
            _status.update { it.copy(lastOkAt = System.currentTimeMillis()) }
            AiOutcome.Ok(envelope.result, envelope.provider, envelope.model ?: "", latency, envelope.fallbackUsed, m.id)
        } else {
            _status.update { it.copy(lastErrorAt = System.currentTimeMillis(), lastError = error) }
            AiOutcome.Failed(error ?: "failed", m.id, envelope?.retryWith)
        }
    }

    private fun parse(text: String): AiEnvelope = AppJson.decodeFromString(AiEnvelope.serializer(), text)

    companion object {
        /**
         * Jobs Danny waits on, per provider: the function gives each one 60 s (1.4: Danny would rather
         * wait than time out — every step, which AI is working and the seconds show on screen, and
         * "skip" answers offline at any time).
         */
        const val FAST_TIMEOUT_MS = 65_000L
        const val LEARN_TIMEOUT_MS = 140_000L
        /** Submitting or polling a learn batch is quick; the review itself runs at Anthropic. */
        const val BATCH_TIMEOUT_MS = 30_000L

        fun str(value: String?): JsonElement = value?.let { JsonPrimitive(it) } ?: JsonNull
    }
}
