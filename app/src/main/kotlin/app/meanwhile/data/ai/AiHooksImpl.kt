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
import app.meanwhile.domain.router.DoseIntent
import app.meanwhile.domain.router.FactorIntent
import app.meanwhile.domain.router.FeedbackIntent
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

    private fun base(profile: Profile, now: Instant = Instant.now()) = mapOf(
        "now" to isoOf(now.toEpochMilli()),
        "timezone" to zone().id,
    )

    private fun profileJson(profile: Profile) = ProfileJson.tree(profile)

    override suspend fun route(text: String, profile: Profile, inputId: String): RouteResult? {
        if (!ai.reachable()) return null
        val offline = OfflineRouter(profile.factors)
        val guess = offline.route(text)
        val payload = buildJsonObject {
            base(profile).forEach { (k, v) -> put(k, v) }
            put("text", text)
            put("profile", profileJson(profile))
            put("offline_guess", AppJson.encodeToJsonElement(ListSerializer(RoutedIntent.serializer()), guess.intents))
        }
        val out = ai.call("route", payload, inputId, "route: $text", AiClient.FAST_TIMEOUT_MS) as? AiOutcome.Ok ?: return null
        val dto = runCatching { AppJson.decodeFromJsonElement(RouteDto.serializer(), out.result) }.getOrNull() ?: return null
        if (dto.intents.isEmpty()) return null
        // The AI decides *what* each part is; numbers inside each span are parsed deterministically.
        val intents = dto.intents.flatMap { i -> toIntents(i, text, offline) }
        return RouteResult(intents, "ai")
    }

    private fun toIntents(i: RouteIntentDto, full: String, offline: OfflineRouter): List<RoutedIntent> {
        val span = i.textSpan.ifBlank { full }
        val parsed = offline.route(span).intents
        return when (i.type) {
            "feedback" -> listOf(parsed.filterIsInstance<FeedbackIntent>().firstOrNull() ?: FeedbackIntent(full, offline.route(full).intents.filterIsInstance<FeedbackIntent>().firstOrNull()?.text ?: span, i.confidence))
            "dose_given" -> listOf(parsed.filterIsInstance<DoseIntent>().firstOrNull()?.copy(confidence = i.confidence) ?: DoseIntent(span, 0.0, confidence = i.confidence))
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
        val doses = db.doses().since(since)
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
