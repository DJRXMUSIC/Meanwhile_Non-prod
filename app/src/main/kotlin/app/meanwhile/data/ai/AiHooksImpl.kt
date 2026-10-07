package app.meanwhile.data.ai

import app.meanwhile.data.db.AppDatabase
import app.meanwhile.data.input.AiHooks
import app.meanwhile.data.input.AiProposalCard
import app.meanwhile.data.input.MealDraft
import app.meanwhile.data.input.ProposedFactorChange
import app.meanwhile.data.input.ResultCard
import app.meanwhile.data.json.AppJson
import app.meanwhile.data.json.isoOf
import app.meanwhile.domain.profile.FactorKind
import app.meanwhile.domain.profile.Profile
import app.meanwhile.domain.profile.ProfileJson
import app.meanwhile.data.input.AiCallInfo
import app.meanwhile.domain.router.BgIntent
import app.meanwhile.domain.router.DoseIntent
import app.meanwhile.domain.router.FactorIntent
import app.meanwhile.domain.router.FeedbackIntent
import app.meanwhile.domain.router.FollowedIntent
import app.meanwhile.domain.router.MealIntent
import app.meanwhile.domain.router.OfflineRouter
import app.meanwhile.domain.router.RouteResult
import app.meanwhile.domain.router.RoutedIntent
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Online steps of the input pipeline (spec §9.2–9.4, §10). Each returns null to fall back offline. */
class AiHooksImpl(
    private val ai: AiClient,
    private val db: AppDatabase,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : AiHooks {

    override val online: Boolean get() = ai.reachable()

    override fun lastCall(): AiCallInfo? = ai.lastCall

    override fun activity() = ai.activity

    override suspend fun converse(
        text: String,
        profile: Profile,
        inputId: String,
        state: JsonObject,
        done: List<String>,
        history: List<app.meanwhile.data.db.ConversationLogEntity>,
    ): app.meanwhile.data.input.ConverseResult? {
        if (!ai.reachable()) return null
        val offline = OfflineRouter(profile.factors)
        val payload = conversePayload(text, profile, state, done, history)
        val out = ai.call("converse", payload, inputId, "converse: $text", AiClient.FAST_TIMEOUT_MS) as? AiOutcome.Ok ?: return null
        val dto = runCatching { AppJson.decodeFromJsonElement(ConverseDto.serializer(), out.result) }.getOrNull() ?: return null
        val route = if (done.isEmpty()) RouteResult(dto.intents.flatMap { i -> toIntents(i, text, offline) }, "ai") else null
        return app.meanwhile.data.input.ConverseResult(dto.reply.trim(), route, out.model)
    }

    /** What the converse job is sent (kept separate so it is tested without a network). */
    internal suspend fun conversePayload(
        text: String,
        profile: Profile,
        state: JsonObject,
        done: List<String>,
        history: List<app.meanwhile.data.db.ConversationLogEntity>,
        now: Instant = Instant.now(),
    ): JsonObject {
        val offline = OfflineRouter(profile.factors)
        val since = now.minus(Duration.ofHours(48)).toEpochMilli()
        val recent = recentContext(since)
        val meals = db.meals().between(since, Long.MAX_VALUE)
        return buildJsonObject {
            base(profile, now).forEach { (k, v) -> put(k, v) }
            put("text", text)
            put("state", state)
            putJsonArray("done") { done.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            putJsonArray("recent_conversation") {
                history.takeLast(30).forEach { e ->
                    addJsonObject { put("at", isoOf(e.recordedAt)); put("who", if (e.role == "user") "danny" else "app"); put("kind", e.kind); put("text", e.text) }
                }
            }
            put("recent", JsonObject(recent + ("meals" to kotlinx.serialization.json.buildJsonArray {
                meals.forEach { m ->
                    addJsonObject {
                        put("at", isoOf(m.recordedAt)); put("description", m.description)
                        put("carbs_g", m.carbsG); put("fat_g", m.fatG); put("protein_g", m.proteinG)
                    }
                }
            })))
            put("profile", profileJson(profile))
            if (done.isEmpty()) put("offline_guess", AppJson.encodeToJsonElement(ListSerializer(RoutedIntent.serializer()), offline.route(text).intents))
        }
    }

    private fun base(profile: Profile, now: Instant = Instant.now()) = mapOf(
        "now" to isoOf(now.toEpochMilli()),
        "timezone" to zone().id,
    )

    private fun profileJson(profile: Profile) = ProfileJson.tree(profile)

    private fun toIntents(i: RouteIntentDto, full: String, offline: OfflineRouter): List<RoutedIntent> {
        val span = i.textSpan.ifBlank { full }
        val parsed = offline.route(span).intents
        return when (i.type) {
            "feedback" -> listOf(parsed.filterIsInstance<FeedbackIntent>().firstOrNull() ?: FeedbackIntent(full, offline.route(full).intents.filterIsInstance<FeedbackIntent>().firstOrNull()?.text ?: span, i.confidence))
            "dose_given" -> listOf(
                parsed.filterIsInstance<DoseIntent>().firstOrNull()?.copy(confidence = i.confidence)
                    ?: parsed.filterIsInstance<FollowedIntent>().firstOrNull()?.copy(confidence = i.confidence)
                    ?: DoseIntent(span, 0.0, confidence = i.confidence),
            )
            // 1.4: the numbers in a correction are read by code, never taken from the AI.
            "dose_correction" -> listOf(offline.correctionFrom(span).copy(confidence = i.confidence))
            "followed" -> listOf(parsed.filterIsInstance<FollowedIntent>().firstOrNull()?.copy(confidence = i.confidence) ?: FollowedIntent(span, confidence = i.confidence))
            "bg_reading" -> listOfNotNull(
                parsed.filterIsInstance<BgIntent>().firstOrNull()?.copy(confidence = i.confidence)
                    ?: Regex("\\b(\\d{2,3})\\b").find(span)?.groupValues?.get(1)?.toDouble()?.takeIf { it in 20.0..600.0 }?.let { BgIntent(span, it, i.confidence) },
            )
            "factor_update" -> parsed.filterIsInstance<FactorIntent>().map { it.copy(confidence = i.confidence) }
                .ifEmpty { listOf(FactorIntent(span, factorId = "", confidence = i.confidence)) }
            else -> listOf(
                parsed.filterIsInstance<MealIntent>().firstOrNull()?.copy(textSpan = span, confidence = i.confidence)
                    ?: MealIntent(span, description = span, confidence = i.confidence),
            )
        }
    }

    override suspend fun estimateMeal(description: String, profile: Profile, inputId: String): MealDraft? {
        if (!ai.reachable() || description.isBlank()) return null
        val recent = db.meals().between(Instant.now().minus(Duration.ofDays(60)).toEpochMilli(), Long.MAX_VALUE)
            .filter { it.description.isNotBlank() }.takeLast(40)
        val payload = buildJsonObject {
            base(profile).forEach { (k, v) -> put(k, v) }
            put("description", description)
            putJsonArray("recent_meals") {
                recent.forEach { m ->
                    addJsonObject {
                        put("description", m.description)
                        put("carbs_g", m.carbsG)
                        put("fat_g", m.fatG)
                        put("protein_g", m.proteinG)
                    }
                }
            }
        }
        val out = ai.call("estimate_meal", payload, inputId, "estimate: $description", AiClient.FAST_TIMEOUT_MS) as? AiOutcome.Ok ?: return null
        val dto = runCatching { AppJson.decodeFromJsonElement(MealEstimateDto.serializer(), out.result) }.getOrNull() ?: return null
        val provenance = buildJsonObject {
            put("provider", out.provider)
            put("model", out.model)
            put("ai_call_id", out.callId)
            put("fallback_used", out.fallbackUsed)
            put("original", buildJsonObject {
                put("carbs_g", dto.carbsG)
                put("fat_g", dto.fatG)
                put("protein_g", dto.proteinG)
            })
            put("notes", dto.notes)
        }
        return MealDraft(description, dto.carbsG, dto.fatG, dto.proteinG, dto.liquidOrSugary, isEstimate = true, estimateDetails = provenance.toString())
    }

    override suspend fun updateProfile(text: String, intents: List<FactorIntent>, profile: Profile, inputId: String): ResultCard? {
        if (!ai.reachable()) return null
        val out = requestUpdate(text, intents, profile, inputId, Instant.now()) ?: return null
        val (dto, ok) = out
        val changes = toProposed(dto, profile)
        if (changes.isEmpty()) return null
        return AiProposalCard(
            key = "ai-$inputId", inputId = inputId, text = text, callId = ok.callId, provider = ok.provider, model = ok.model,
            fallbackUsed = ok.fallbackUsed, summary = dto.summary, changes = changes,
            newDefinitions = dto.newFactors.map { it.definition.toDomain() },
        )
    }

    /** Shared with the offline-queue processor. */
    suspend fun requestUpdate(text: String, intents: List<FactorIntent>, profile: Profile, inputId: String?, at: Instant): Pair<UpdateProfileDto, AiOutcome.Ok>? {
        val since = at.minus(Duration.ofHours(48)).toEpochMilli()
        val payload = buildJsonObject {
            base(profile, at).forEach { (k, v) -> put(k, v) }
            put("text", text)
            put("profile", profileJson(profile))
            put("offline_guess", AppJson.encodeToJsonElement(ListSerializer(FactorIntent.serializer()), intents))
            put("recent", recentContext(since))
        }
        val out = ai.call("update_profile", payload, inputId, "update_profile: $text", AiClient.FAST_TIMEOUT_MS) as? AiOutcome.Ok ?: return null
        val dto = runCatching { AppJson.decodeFromJsonElement(UpdateProfileDto.serializer(), out.result) }.getOrNull() ?: return null
        return dto to out
    }

    private suspend fun recentContext(since: Long): JsonObject {
        val events = db.factorEvents().since(since)
        val doses = db.doses().effectiveSince(since)
        val outcomes = db.outcomes().since(since)
        return buildRecent(events, doses, outcomes)
    }

    private fun buildRecent(
        events: List<app.meanwhile.data.db.FactorEventEntity>,
        doses: List<app.meanwhile.data.db.DoseEntity>,
        outcomes: List<app.meanwhile.data.db.OutcomeEntity>,
    ): JsonObject = buildJsonObject {
        putJsonArray("factor_events") {
            events.forEach { e ->
                addJsonObject {
                    put("at", isoOf(e.recordedAt)); put("factor_id", e.factorId); put("action", e.action)
                    e.weight?.let { put("weight", it) }; e.unitsAdd?.let { put("units_add", it) }; put("source", e.source)
                    put("details", AppJson.parseToJsonElement(e.details))
                }
            }
        }
        putJsonArray("doses") {
            doses.forEach { d ->
                addJsonObject { put("at", isoOf(d.givenAt)); put("insulin", d.insulin); put("units", d.units) }
            }
        }
        putJsonArray("outcomes") {
            outcomes.forEach { o ->
                addJsonObject {
                    put("dose_id", o.doseId); o.bg2h?.let { put("bg_2h", it) }; o.bg3h?.let { put("bg_3h", it) }
                    o.bg4h?.let { put("bg_4h", it) }; o.min4h?.let { put("min_4h", it) }; o.max4h?.let { put("max_4h", it) }
                }
            }
        }
    }

    fun toProposed(dto: UpdateProfileDto, profile: Profile): List<ProposedFactorChange> {
        val newDefs = dto.newFactors.map { it.definition.toDomain() }
        val all = profile.factors + newDefs
        val changes = dto.changes.mapNotNull { ch ->
            val def = all.firstOrNull { it.id == ch.factorId } ?: return@mapNotNull null
            ProposedFactorChange(
                factorId = def.id, name = def.name, kind = def.kind, action = ch.action,
                weight = if (def.kind == FactorKind.UNITS_PER_EVENT) null else ch.weight,
                windowMinutes = ch.windowMinutes, decay = ch.decayRule?.toDomain(),
                unitsAdd = ch.unitsAdd ?: if (def.kind == FactorKind.UNITS_PER_EVENT) (ch.amount ?: 1.0) * (def.unitsPerEvent ?: 1.0) else null,
                amount = ch.amount, preset = ch.preset, startedMinutesAgo = ch.startedMinutesAgo, reason = ch.reason,
                isNewFactor = newDefs.any { it.id == def.id }, minWeight = def.minWeight, maxWeight = def.maxWeight,
            )
        }
        // A new factor without an explicit change still gets activated with its proposed weight.
        val implicit = dto.newFactors.filter { nf -> changes.none { it.factorId == nf.definition.id } }.map { nf ->
            val def = nf.definition.toDomain()
            ProposedFactorChange(
                def.id, def.name, def.kind, "activate", nf.weight ?: def.defaultWeight, def.window.minutes, def.decay,
                null, null, null, null, nf.reason, isNewFactor = true, minWeight = def.minWeight, maxWeight = def.maxWeight,
            )
        }
        return changes + implicit
    }
}
