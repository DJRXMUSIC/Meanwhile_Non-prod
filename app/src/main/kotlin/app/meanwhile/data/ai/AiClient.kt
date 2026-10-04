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

    /** Usable right now: configured, signed in with a live session, network up. */
    fun reachable(): Boolean = client != null && network.online.value && auth.currentUserId() != null

    suspend fun call(
        job: String,
        payload: JsonObject,
        inputId: String?,
        summary: String,
        timeoutMs: Long,
        preference: AiProviderPreference? = null,
        preferenceWire: String? = null,
    ): AiOutcome {
        val c = client ?: return AiOutcome.Failed("AI not configured", null)
        if (!network.online.value) return AiOutcome.Failed("offline", null)
        auth.awaitSessionUserId(3_000) ?: return AiOutcome.Failed("not signed in", null)
        val pref = preferenceWire ?: (preference ?: settings.current().aiProvider).wire
        val started = System.currentTimeMillis()
        val body = buildJsonObject {
            put("job", job)
            put("payload", payload)
            put("provider_preference", pref)
        }
        val (envelope, httpError) = try {
            val text = withTimeout(timeoutMs + 15_000) {
                c.functions.invoke("ai") {
                    contentType(ContentType.Application.Json)
                    setBody(body.toString())
                    timeout {
                        requestTimeoutMillis = timeoutMs + 10_000
                        socketTimeoutMillis = timeoutMs + 10_000
                    }
                }.bodyAsText()
            }
            parse(text) to null
        } catch (e: RestException) {
            // Non-2xx: the body (with per-provider attempts) is in `error`.
            (runCatching { parse(e.error) }.getOrNull()) to "HTTP ${e.response.status.value}"
        } catch (e: TimeoutCancellationException) {
            null to "timed out after ${timeoutMs + 15_000} ms"
        } catch (e: CancellationException) {
            throw e // the caller went away (screen closed, worker stopped): don't log a failure
        } catch (e: Exception) {
            null to (e.message ?: e::class.java.simpleName)
        }
        val latency = System.currentTimeMillis() - started
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
                }.toString(),
                validation = validation,
                error = error,
                inputId = inputId,
            ),
        )
        onWrite()
        if (ok) {
            AppLog.i("AI", "$job ok · ${envelope!!.provider}/${envelope.model} · ${latency} ms" + if (envelope.fallbackUsed) " (fallback)" else "")
        } else {
            AppLog.w("AI", "$job failed after $latency ms: $error")
        }
        return if (ok) {
            _status.update { it.copy(lastOkAt = System.currentTimeMillis()) }
            AiOutcome.Ok(envelope!!.result!!, envelope.provider!!, envelope.model ?: "", latency, envelope.fallbackUsed, m.id)
        } else {
            _status.update { it.copy(lastErrorAt = System.currentTimeMillis(), lastError = error) }
            AiOutcome.Failed(error ?: "failed", m.id, envelope?.retryWith)
        }
    }

    private fun parse(text: String): AiEnvelope = AppJson.decodeFromString(AiEnvelope.serializer(), text)

    companion object {
        const val FAST_TIMEOUT_MS = 15_000L
        const val LEARN_TIMEOUT_MS = 140_000L

        fun str(value: String?): JsonElement = value?.let { JsonPrimitive(it) } ?: JsonNull
    }
}
