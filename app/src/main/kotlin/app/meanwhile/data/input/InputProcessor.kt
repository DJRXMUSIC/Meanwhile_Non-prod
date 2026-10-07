package app.meanwhile.data.input

import app.meanwhile.data.RecordFactory
import app.meanwhile.data.db.AiQueueEntity
import app.meanwhile.data.db.AppDatabase
import app.meanwhile.data.db.FeedbackEntity
import app.meanwhile.data.db.InputEntity
import app.meanwhile.data.json.AppJson
import app.meanwhile.data.profile.ProfileRepository
import app.meanwhile.data.profile.ProfileSource
import app.meanwhile.domain.nba.ActionKind
import app.meanwhile.domain.profile.FactorKind
import app.meanwhile.domain.profile.Profile
import app.meanwhile.domain.router.BgIntent
import app.meanwhile.domain.router.DoseCorrectionIntent
import app.meanwhile.domain.router.DoseIntent
import app.meanwhile.domain.router.FactorIntent
import app.meanwhile.domain.router.FeedbackIntent
import app.meanwhile.domain.router.FollowedIntent
import app.meanwhile.domain.router.MealIntent
import app.meanwhile.domain.router.OfflineRouter
import app.meanwhile.domain.router.RouteResult
import app.meanwhile.domain.router.RoutedIntent
import app.meanwhile.domain.util.UuidV7
import app.meanwhile.format.formatUnits
import app.meanwhile.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/** What the last AI call answered with, for the step line ("Gemini 3.8 Flash · 1.8 s"). */
data class AiCallInfo(val provider: String?, val model: String?, val latencyMs: Long, val fallbackUsed: Boolean, val error: String?)

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
    /** The most recent AI call's provider, model and timing (null when none ran). */
    fun lastCall(): AiCallInfo? = null
}

/** The latest undoable things in this conversation that only the screen knows about (1.4 "cancel that"). */
data class ConversationContext(
    val lastFactorUpdate: FactorUpdateCard? = null,
    val lastFactorUpdateAt: Long? = null,
)

/**
 * One message → understood → acted on → answered (spec §9; 1.4 conversation). Every step is reported
 * live through `onStep` and written to the conversation log with the message and the answer.
 */
class InputProcessor(
    private val db: AppDatabase,
    private val records: RecordFactory,
    private val profiles: ProfileRepository,
    private val updater: FactorUpdater,
    private val nba: NbaService,
    private val onWrite: () -> Unit,
    private val conversation: ConversationLog,
    private val ai: () -> AiHooks,
) {
    suspend fun process(
        raw: String,
        via: String,
        forcedPath: String? = null,
        supersedes: String? = null,
        now: Instant = Instant.now(),
        voice: VoiceDetails? = null,
        context: ConversationContext = ConversationContext(),
        skipAi: Deferred<Unit>? = null,
        onStep: (Step) -> Unit = {},
    ): InputSession {
        val started = System.currentTimeMillis()
        val text = raw.trim()
        val profile = profiles.current().profile
        val inputId = UuidV7.string(now.toEpochMilli())
        conversation.message(
            inputId, text, via, voice, now,
            buildJsonObject {
                forcedPath?.let { put("forced_path", it) }
                supersedes?.let { put("supersedes_input", it) }
            },
        )
        val steps = Steps(inputId, onStep)
        val offline = OfflineRouter(profile.factors)
        val offlineRoute = offline.route(text)

        val route = when {
            forcedPath != null -> forced(text, forcedPath, offlineRoute)
            // Corrections and "took it" are read by code — they decide what gets logged as insulin,
            // so a different reading by the AI must never turn "only 5" into a second dose.
            offlineRoute.intents.any { it is DoseCorrectionIntent || it is FollowedIntent } -> {
                steps.done("understand", "Understood", describe(offlineRoute))
                offlineRoute
            }
            // 1.4: when code reads every part with certainty ("took 6 units", "60 carbs 20 fat",
            // "BG 140", "2 coffees", "what should I do?") the AI round trip adds nothing but waiting.
            confident(offlineRoute) -> {
                steps.done("understand", "Understood", describe(offlineRoute) + " · instantly, no AI needed")
                offlineRoute
            }
            else -> steps.run("understand", if (ai().online) "Understanding (AI)" else "Understanding") {
                val viaAi = if (ai().online) skippable(skipAi) { ai().route(text, profile, inputId) } else null
                val r = viaAi ?: offlineRoute
                r to describe(r) + " · " + (if (viaAi != null) aiLine() else offlineReason(skipAi))
            }
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
        try {
            act(text, route, profile, inputId, now, context, skipAi, steps, cards, forcedPath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DoseUnavailableException) {
            AppLog.w("Input", e.message.orEmpty())
            cards += InfoCard("err-$inputId", e.message.orEmpty(), isError = true)
        }
        val session = InputSession(inputId, text, via, route, cards, switched = forcedPath != null)
        conversation.reply(session, System.currentTimeMillis() - started)
        return session
    }

    private suspend fun act(
        text: String,
        route: RouteResult,
        profile: Profile,
        inputId: String,
        now: Instant,
        context: ConversationContext,
        skipAi: Deferred<Unit>?,
        steps: Steps,
        cards: MutableList<ResultCard>,
        forcedPath: String?,
    ) {
        for (f in route.intents.filterIsInstance<FeedbackIntent>()) {
            val fm = records.meta(now = now.toEpochMilli())
            db.feedback().insert(FeedbackEntity(fm.id, fm.userId, fm.createdAt, fm.recordedAt, text = f.text, context = "input:$inputId"))
            cards += FeedbackSavedCard("fb-${fm.id}", f.text)
            steps.done("feedback", "Saved to feedback")
        }
        for (c in route.intents.filterIsInstance<DoseCorrectionIntent>()) {
            cards += steps.run("correct", "Correcting") { correction(c, text, inputId, now, context).let { it to CardText.text(it) } }
        }
        for (f in route.intents.filterIsInstance<FollowedIntent>()) {
            cards += steps.run("log", "Logging what you did") { followed(f, inputId, now).let { it to CardText.text(it) } }
        }
        for (d in route.intents.filterIsInstance<DoseIntent>()) {
            if (d.units <= 0.0) {
                cards += DoseConfirmCard("dose-${UuidV7.string()}", 0.0, d.insulin, now.minus(Duration.ofMinutes((d.minutesAgo ?: 0).toLong())).toEpochMilli())
                continue
            }
            cards += steps.run("log", "Logging ${formatUnits(d.units)}") { stated(d, text, inputId, now).let { it to it.message } }
        }

        val bg = route.intents.filterIsInstance<BgIntent>().firstOrNull()?.mgDl
        val factorIntents = route.intents.filterIsInstance<FactorIntent>()
        if (factorIntents.isNotEmpty()) {
            cards += steps.run("profile", if (ai().online) "Updating your profile (AI)" else "Updating your profile") {
                updateProfile(text, factorIntents, profile, inputId, now, skipAi).let { it to CardText.text(it) }
            }
        }
        if (forcedPath == "factor_update" && factorIntents.isEmpty()) cards += FactorPickerCard("pick-$inputId", text)

        val meal = route.intents.filterIsInstance<MealIntent>().firstOrNull()
        val coffeeOnly = meal == null && factorIntents.any { profile.factor(it.factorId)?.kind == FactorKind.UNITS_PER_EVENT } &&
            cards.none { it is AiProposalCard }
        val bgOnly = meal == null && bg != null && cards.none { it is AiProposalCard || it is DoseLoggedCard }
        when {
            meal != null && meal.hasMacros -> cards += nbaStep(
                steps, MealDraft(meal.description, meal.carbsG ?: 0.0, meal.fatG ?: 0.0, meal.proteinG ?: 0.0, meal.liquidOrSugary), inputId, now, bg,
            )
            meal != null && CORRECTION.containsMatchIn(meal.description.lowercase()) -> cards += nbaStep(steps, MealDraft(OfflineRouter.CHECK_DESCRIPTION), inputId, now, bg)
            meal != null -> cards += steps.run("estimate", if (ai().online) "Estimating the meal (AI)" else "Reading the meal") {
                mealNeedsMacros(meal, profile, inputId, bg, skipAi).let { it to CardText.text(it) }
            }
            coffeeOnly -> cards += nbaStep(steps, MealDraft(description = "caffeine"), inputId, now, bg)
            bgOnly -> cards += nbaStep(steps, MealDraft(OfflineRouter.CHECK_DESCRIPTION), inputId, now, bg)
        }
    }

    private suspend fun nbaStep(steps: Steps, draft: MealDraft, inputId: String, now: Instant, bg: Double?): NbaCard =
        steps.run("nba", "Working out your next best action") {
            val card = nba.propose(draft, inputId, now, bgOverride = bg)
            card to "${card.action.headline} · ${card.computeMs} ms"
        }

    /** "took 6 units": logged as said; linked to the open suggestion when it was for insulin. */
    private suspend fun stated(d: DoseIntent, text: String, inputId: String, now: Instant): DoseLoggedCard {
        val at = now.minus(Duration.ofMinutes((d.minutesAgo ?: 0).toLong()))
        val open = if (d.insulin == "rapid" && d.units == Math.floor(d.units)) nba.openProposal(now)?.takeIf { it.action.insulin } else null
        val logged = if (open != null) {
            val units = d.units.roundToInt()
            val split = open.result.split
            val later = if (split != null && units < open.result.finalUnits) split.secondUnits else 0
            nba.logProposal(
                open, units, later, reason = if (units != (split?.firstUnits ?: open.result.finalUnits)) "said: “$text”" else null,
                now = now, givenAt = at, inputId = inputId,
            ).let { it.copy(message = it.message + " (for: ${open.action.headline.lowercase()})") }
        } else {
            nba.logStated(d.units, d.insulin, at.toEpochMilli(), inputId, now = now)
        }
        return DoseLoggedCard("dose-${logged.dose.id}", logged.dose, logged.message)
    }

    /** "took it" / "done" / "ate it": whatever the open suggestion said, logged. */
    private suspend fun followed(f: FollowedIntent, inputId: String, now: Instant): ResultCard {
        val open = nba.openProposal(now)
            ?: return InfoCard("info-$inputId", "Nothing to mark as done — there's no suggestion from the last 90 min. Say what you took, e.g. “took 6 units”.")
        val at = now.minus(Duration.ofMinutes((f.minutesAgo ?: 0).toLong()))
        return when (open.action.kind) {
            ActionKind.TAKE_INSULIN, ActionKind.SPLIT_INSULIN -> {
                val logged = nba.logProposal(open, open.action.unitsNow, open.action.unitsLater, null, now = now, givenAt = at, inputId = inputId)
                DoseLoggedCard("dose-${logged.dose.id}", logged.dose, logged.message + " (as suggested)")
            }
            ActionKind.TREAT_LOW, ActionKind.EAT_CARBS, ActionKind.EAT_NO_INSULIN ->
                MealLoggedCard("meal-${open.proposalId}", open.proposalId, nba.logMealOnly(open, now = at))
            ActionKind.NOTHING, ActionKind.CHECK_BG -> InfoCard("info-$inputId", "Nothing to log for that one — it didn't ask for insulin or food.")
        }
    }

    /** "never mind, only 5" / "I didn't take any" / "cancel that". */
    private suspend fun correction(c: DoseCorrectionIntent, text: String, inputId: String, now: Instant, context: ConversationContext): ResultCard {
        val target = nba.correctable(now, c.insulin)
        // "cancel that" right after a factor update (and no dose words) undoes the factor update.
        val factor = context.lastFactorUpdate?.takeIf { !it.undone }
        if (c.units == 0.0 && factor != null && !DOSE_WORDS.containsMatchIn(text.lowercase()) &&
            (target == null || (context.lastFactorUpdateAt ?: 0L) > target.createdAt)
        ) {
            updater.undo(factor.outcome, now)
            return InfoCard("info-$inputId", "Undone: " + factor.changes.joinToString { it.name }, ref = factor.key)
        }
        if (target == null) {
            // "took it 20 min ago" style time fix with nothing logged yet: log the open suggestion at that time.
            if (c.units == null && c.minutesAgo != null) return followed(FollowedIntent(text, c.minutesAgo), inputId, now)
            return InfoCard(
                "info-$inputId",
                if (c.units == 0.0) "Nothing to cancel — no dose was logged in the last ${NbaService.CORRECTION_WINDOW.toHours()} h."
                else "There's no dose from the last ${NbaService.CORRECTION_WINDOW.toHours()} h to change. To log one, say “took ${c.units?.let { formatUnits(it) } ?: "6 units"}”.",
            )
        }
        val newAt = c.minutesAgo?.let { now.minus(Duration.ofMinutes(it.toLong())).toEpochMilli() }
        if ((c.units == null || c.units == target.units) && (newAt == null || newAt == target.givenAt)) {
            return InfoCard("info-$inputId", "That's already ${formatUnits(target.units)} — nothing changed.")
        }
        val logged = nba.correct(target, c.units, newAt, inputId, "said: “$text”", now)
        return DoseLoggedCard("dose-${logged.dose.id}", logged.dose, logged.message, previous = target)
    }

    /** Online: AI proposal for Danny to accept. Offline: default weights now + queued AI refinement. */
    private suspend fun updateProfile(
        text: String,
        intents: List<FactorIntent>,
        profile: Profile,
        inputId: String,
        now: Instant,
        skipAi: Deferred<Unit>?,
    ): ResultCard {
        if (ai().online) skippable(skipAi) { ai().updateProfile(text, intents, profile, inputId) }?.let { return it }
        val known = intents.filter { profile.factor(it.factorId) != null }
        if (known.isEmpty()) return FactorPickerCard("pick-$inputId", text)
        val outcome = updater.apply(
            requests = known.map { updater.requestFrom(it, now) },
            inputId = inputId, eventSource = "offline", versionSource = ProfileSource.OFFLINE_FALLBACK,
            summary = "Offline: " + known.joinToString { "${it.factorId} ${it.action}${it.preset?.let { p -> " ($p)" } ?: ""}" },
            now = now,
        )
        db.aiQueue().upsert(
            AiQueueEntity(
                id = UuidV7.string(), job = "update_profile",
                payload = buildJsonObject {
                    put("text", text)
                    put("intents", AppJson.encodeToJsonElement(ListSerializer(FactorIntent.serializer()), known))
                }.toString(),
                inputId = inputId, fallbackVersionId = outcome.version?.id, createdAt = now.toEpochMilli(),
            ),
        )
        return FactorUpdateCard("fu-$inputId", outcome.changes, outcome, ProfileSource.OFFLINE_FALLBACK, aiQueued = true)
    }

    private suspend fun mealNeedsMacros(meal: MealIntent, profile: Profile, inputId: String, bg: Double?, skipAi: Deferred<Unit>?): ResultCard {
        val estimate = if (ai().online) skippable(skipAi) { ai().estimateMeal(meal.description, profile, inputId) } else null
        return if (estimate != null) {
            MealMacrosCard(
                "meal-$inputId", estimate.copy(liquidOrSugary = estimate.liquidOrSugary || meal.liquidOrSugary), hasNumbers = true,
                note = "AI estimate — confirm or edit before the dose is calculated", bgOverride = bg,
            )
        } else {
            MealMacrosCard(
                "meal-$inputId", MealDraft(description = meal.description, liquidOrSugary = meal.liquidOrSugary), hasNumbers = false,
                note = if (ai().online) "Couldn't estimate — enter carbs / fat / protein" else "Offline — enter carbs / fat / protein",
                bgOverride = bg,
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
        // A dose this message logged is cancelled (append-only) before the same words are re-routed.
        session.cards.filterIsInstance<DoseLoggedCard>().filter { it.replacedMessage == null && it.dose.units > 0 }.forEach { card ->
            val current = db.doses().effectiveLoggedSince(card.dose.createdAt - 1).firstOrNull { it.id == card.dose.id } ?: return@forEach
            nba.correct(current, 0.0, null, session.inputId, "re-routed with the path switch")
        }
    }

    /** Every intent read by code with certainty: numbers, a dose, a factor keyword, a BG, a question. */
    private fun confident(r: RouteResult): Boolean =
        r.intents.isNotEmpty() && r.intents.all { it.confidence >= CONFIDENT && (it !is FactorIntent || it.factorId.isNotBlank()) }

    /** Runs an AI call unless Danny taps "Skip AI" first (then the offline path answers). */
    private suspend fun <T> skippable(skip: Deferred<Unit>?, block: suspend () -> T?): T? {
        if (skip == null) return block()
        if (skip.isCompleted) return null
        return coroutineScope {
            val call = async { block() }
            val result = select<T?> {
                call.onAwait { it }
                skip.onAwait { null }
            }
            if (call.isActive) call.cancel()
            result
        }
    }

    private fun aiLine(): String = ai().lastCall()?.let { c ->
        listOfNotNull(c.model ?: c.provider, "${String.format(java.util.Locale.US, "%.1f", c.latencyMs / 1000.0)} s", if (c.fallbackUsed) "fallback" else null).joinToString(" · ")
    } ?: "AI"

    private fun offlineReason(skip: Deferred<Unit>?): String = when {
        skip?.isCompleted == true -> "offline (AI skipped)"
        !ai().online -> "offline"
        else -> "offline — AI didn't answer" + (ai().lastCall()?.error?.let { ": ${it.take(80)}" } ?: "")
    }

    private fun describe(r: RouteResult): String = r.intents.joinToString(" + ") { i ->
        when (i) {
            is MealIntent -> if (i.description == OfflineRouter.CHECK_DESCRIPTION) "what to do now" else "meal"
            is FactorIntent -> "factor"
            is DoseIntent -> "dose"
            is FeedbackIntent -> "feedback"
            is DoseCorrectionIntent -> "correction"
            is FollowedIntent -> "done"
            is BgIntent -> "BG ${i.mgDl.roundToInt()}"
            else -> i.type
        }
    }.ifEmpty { "nothing recognised" }

    /** Reports each step live and writes it to the conversation log. */
    private inner class Steps(private val inputId: String, private val onStep: (Step) -> Unit) {
        suspend fun <T> run(id: String, label: String, block: suspend () -> Pair<T, String?>): T {
            val start = System.currentTimeMillis()
            onStep(Step(id, label, StepState.RUNNING, startedAt = start))
            try {
                val (value, detail) = block()
                val step = Step(id, label, StepState.DONE, detail, start, System.currentTimeMillis())
                onStep(step)
                conversation.step(inputId, step)
                return value
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val step = Step(id, label, StepState.FAILED, e.message ?: e::class.java.simpleName, start, System.currentTimeMillis())
                onStep(step)
                conversation.step(inputId, step)
                throw e
            }
        }

        suspend fun done(id: String, label: String, detail: String? = null) {
            val now = System.currentTimeMillis()
            val step = Step(id, label, StepState.DONE, detail, now, now)
            onStep(step)
            conversation.step(inputId, step)
        }
    }

    private companion object {
        /** Offline intents at or above this are certain (food described in words is 0.8 or less). */
        const val CONFIDENT = 0.9
        val CORRECTION = Regex("^(correction|correct|check|nba|what should i (take|do)|dose check)\\b")
        val DOSE_WORDS = Regex("\\b(took|take|taken|insulin|units?|dose|shot|bolus|inject|humalog|lantus|long[- ]?acting)\\b")
    }
}
