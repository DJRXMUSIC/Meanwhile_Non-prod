package app.meanwhile.data.input

import app.meanwhile.data.RecordFactory
import app.meanwhile.data.db.AiQueueEntity
import app.meanwhile.data.db.AppDatabase
import app.meanwhile.data.db.FeedbackEntity
import app.meanwhile.data.db.InputEntity
import app.meanwhile.data.json.AppJson
import app.meanwhile.data.profile.ProfileRepository
import app.meanwhile.data.profile.ProfileSource
import app.meanwhile.domain.profile.FactorKind
import app.meanwhile.domain.profile.Profile
import app.meanwhile.domain.router.DoseIntent
import app.meanwhile.domain.router.FactorIntent
import app.meanwhile.domain.router.FeedbackIntent
import app.meanwhile.domain.router.MealIntent
import app.meanwhile.domain.router.OfflineRouter
import app.meanwhile.domain.router.RouteResult
import app.meanwhile.domain.router.RoutedIntent
import app.meanwhile.domain.util.UuidV7
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant

/**
 * Pluggable online steps (M6). Each returns null when the AI is unavailable, and the processor falls
 * back to the offline path (spec §10.6: AI is never required for a dose).
 */
interface AiHooks {
    suspend fun route(text: String, profile: Profile, inputId: String): RouteResult? = null
    suspend fun estimateMeal(description: String, profile: Profile, inputId: String): MealDraft? = null
    /** Returns a card proposing AI factor changes for Danny to accept, or null to use the offline fallback. */
    suspend fun updateProfile(text: String, intents: List<FactorIntent>, profile: Profile, inputId: String): ResultCard? = null
    val online: Boolean get() = false
}

/** One input → route → cards (spec §9). */
class InputProcessor(
    private val db: AppDatabase,
    private val records: RecordFactory,
    private val profiles: ProfileRepository,
    private val updater: FactorUpdater,
    private val nba: NbaService,
    private val onWrite: () -> Unit,
    private val ai: () -> AiHooks,
) {
    suspend fun process(
        raw: String,
        via: String,
        forcedPath: String? = null,
        supersedes: String? = null,
        now: Instant = Instant.now(),
    ): InputSession {
        val text = raw.trim()
        val profile = profiles.current().profile
        val inputId = UuidV7.string(now.toEpochMilli())
        val offline = OfflineRouter(profile.factors)
        val route = when {
            forcedPath != null -> forced(text, forcedPath, offline.route(text))
            else -> ai().route(text, profile, inputId) ?: offline.route(text)
        }
        val m = records.meta(now = now.toEpochMilli())
        db.inputs().insert(
            InputEntity(
                id = inputId, userId = m.userId, createdAt = m.createdAt, recordedAt = m.recordedAt,
                supersedesId = supersedes, rawText = text, via = via, router = route.router,
                routerResult = AppJson.encodeToString(ListSerializer(RoutedIntent.serializer()), route.intents),
                pathTaken = route.path, manualSwitch = forcedPath != null,
            ),
        )
        onWrite()

        val cards = mutableListOf<ResultCard>()
        for (f in route.intents.filterIsInstance<FeedbackIntent>()) {
            val fm = records.meta(now = now.toEpochMilli())
            db.feedback().insert(FeedbackEntity(fm.id, fm.userId, fm.createdAt, fm.recordedAt, text = f.text, context = "input:$inputId"))
            cards += FeedbackSavedCard("fb-${fm.id}", f.text)
        }
        for (d in route.intents.filterIsInstance<DoseIntent>()) {
            val at = now.minus(Duration.ofMinutes((d.minutesAgo ?: 0).toLong())).toEpochMilli()
            cards += DoseConfirmCard("dose-${UuidV7.string()}", d.units, d.insulin, at)
        }
        val factorIntents = route.intents.filterIsInstance<FactorIntent>()
        if (factorIntents.isNotEmpty()) cards += updateProfile(text, factorIntents, profile, inputId, now)
        if (forcedPath == "factor_update" && factorIntents.isEmpty()) cards += FactorPickerCard("pick-$inputId", text)

        val meal = route.intents.filterIsInstance<MealIntent>().firstOrNull()
        val coffeeOnly = meal == null && factorIntents.any { profile.factor(it.factorId)?.kind == FactorKind.UNITS_PER_EVENT }
        when {
            meal != null && meal.hasMacros -> cards += nba.propose(
                MealDraft(meal.description, meal.carbsG ?: 0.0, meal.fatG ?: 0.0, meal.proteinG ?: 0.0, meal.liquidOrSugary),
                inputId, now,
            )
            meal != null && CORRECTION.containsMatchIn(meal.description.lowercase()) -> cards += nba.propose(MealDraft("correction"), inputId, now)
            meal != null -> cards += mealNeedsMacros(meal, profile, inputId)
            coffeeOnly -> cards += nba.propose(MealDraft(description = "caffeine"), inputId, now)
        }
        return InputSession(inputId, text, via, route, cards, switched = forcedPath != null)
    }

    /** Online: AI proposal for Danny to accept. Offline: default weights now + queued AI refinement. */
    private suspend fun updateProfile(text: String, intents: List<FactorIntent>, profile: Profile, inputId: String, now: Instant): ResultCard {
        ai().updateProfile(text, intents, profile, inputId)?.let { return it }
        val outcome = updater.apply(
            requests = intents.map { updater.requestFrom(it, now) },
            inputId = inputId, eventSource = "offline", versionSource = ProfileSource.OFFLINE_FALLBACK,
            summary = "Offline: " + intents.joinToString { "${it.factorId} ${it.action}${it.preset?.let { p -> " ($p)" } ?: ""}" },
            now = now,
        )
        db.aiQueue().upsert(
            AiQueueEntity(
                id = UuidV7.string(), job = "update_profile",
                payload = buildJsonObject {
                    put("text", text)
                    put("intents", AppJson.encodeToJsonElement(ListSerializer(FactorIntent.serializer()), intents))
                }.toString(),
                inputId = inputId, fallbackVersionId = outcome.version?.id, createdAt = now.toEpochMilli(),
            ),
        )
        return FactorUpdateCard("fu-$inputId", outcome.changes, outcome, ProfileSource.OFFLINE_FALLBACK, aiQueued = true)
    }

    private suspend fun mealNeedsMacros(meal: MealIntent, profile: Profile, inputId: String): ResultCard {
        val estimate = ai().estimateMeal(meal.description, profile, inputId)
        return if (estimate != null) {
            MealMacrosCard("meal-$inputId", estimate.copy(liquidOrSugary = estimate.liquidOrSugary || meal.liquidOrSugary), hasNumbers = true,
                note = "AI estimate — confirm or edit before the dose is calculated")
        } else {
            MealMacrosCard(
                "meal-$inputId", MealDraft(description = meal.description, liquidOrSugary = meal.liquidOrSugary), hasNumbers = false,
                note = if (ai().online) "Couldn't estimate — enter carbs / fat / protein" else "Offline — enter carbs / fat / protein",
            )
        }
    }

    /** One-tap path switch (spec §9.2): re-route the same text as [path]. */
    private fun forced(text: String, path: String, parsed: RouteResult): RouteResult {
        val intents: List<RoutedIntent> = when (path) {
            "feedback" -> listOf(FeedbackIntent(text, text))
            "dose_given" -> parsed.intents.filterIsInstance<DoseIntent>().ifEmpty { listOf(DoseIntent(text, 0.0)) }
            "factor_update" -> parsed.intents.filterIsInstance<FactorIntent>()
            else -> listOf(
                parsed.intents.filterIsInstance<MealIntent>().firstOrNull() ?: MealIntent(text, description = text),
            )
        }
        return RouteResult(intents, OfflineRouter.ROUTER)
    }

    /** Undo side effects of a previous session before re-routing it. */
    suspend fun undoSideEffects(session: InputSession) {
        session.cards.filterIsInstance<FactorUpdateCard>().filterNot { it.undone }.forEach { updater.undo(it.outcome) }
    }

    private companion object {
        val CORRECTION = Regex("^(correction|correct|check|nba|what should i (take|do)|dose check)\\b")
    }
}
