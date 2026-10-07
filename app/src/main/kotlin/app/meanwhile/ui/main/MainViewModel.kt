package app.meanwhile.ui.main

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.meanwhile.data.db.ConversationLogEntity
import app.meanwhile.data.input.AiProposalCard
import app.meanwhile.data.input.CardText
import app.meanwhile.data.input.ConversationContext
import app.meanwhile.data.input.DoseConfirmCard
import app.meanwhile.data.input.DoseLoggedCard
import app.meanwhile.data.input.DoseUnavailableException
import app.meanwhile.data.input.FactorPickerCard
import app.meanwhile.data.input.FactorUpdateCard
import app.meanwhile.data.input.FactorUpdater
import app.meanwhile.data.input.InfoCard
import app.meanwhile.data.input.InputSession
import app.meanwhile.data.input.MealDraft
import app.meanwhile.data.input.MealLoggedCard
import app.meanwhile.data.input.MealMacrosCard
import app.meanwhile.data.input.NbaCard
import app.meanwhile.data.input.ProposedFactorChange
import app.meanwhile.data.input.ResultCard
import app.meanwhile.data.input.Step
import app.meanwhile.data.input.VoiceDetails
import app.meanwhile.data.profile.ProfileSource
import app.meanwhile.data.profile.ProfileStatus
import app.meanwhile.di.AppContainer
import app.meanwhile.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant

/** One message and everything that happened because of it (1.4 conversation). */
data class Turn(
    val id: String,
    val text: String,
    /** voice | text */
    val via: String,
    val at: Long,
    val steps: List<Step> = emptyList(),
    val session: InputSession? = null,
    val error: String? = null,
    val finishedAt: Long? = null,
    /** 2.0: a notification the app sent by itself (advice), shown in the conversation as it arrives. */
    val notice: Boolean = false,
) {
    val working: Boolean get() = !notice && session == null && error == null
}

class MainViewModel(private val c: AppContainer) : ViewModel() {
    var text by mutableStateOf("")

    /** This app session's conversation, oldest first. */
    val turns = mutableStateListOf<Turn>()

    /** Earlier conversation (from the log), shown read-only above [turns]. */
    var history by mutableStateOf<List<ConversationLogEntity>>(emptyList())
        private set

    /** A card button is running (double taps are ignored). */
    var busy by mutableStateOf(false)
        private set

    /** The morning report is offered once per app session. */
    var morningPrompted = false

    /** Bumped after anything that changes doses/profile so dependent views refresh. */
    var tick by mutableIntStateOf(0)
        private set

    private val processing = Mutex()
    private var skip: CompletableDeferred<Unit>? = null
    private val openedAt = System.currentTimeMillis()

    init {
        viewModelScope.launch {
            history = runCatching { c.conversation.transcriptSince(openedAt - HISTORY_MS) }.getOrDefault(emptyList())
                .filter { it.recordedAt < openedAt }
                .takeLast(HISTORY_ITEMS)
        }
        viewModelScope.launch {
            c.db.conversation().notificationsSince(openedAt).collect { rows ->
                rows.filter { r -> turns.none { it.id == "n-${r.id}" } }.forEach { r ->
                    turns += Turn("n-${r.id}", r.text, "notification", r.recordedAt, finishedAt = r.recordedAt, notice = true)
                }
            }
        }
    }

    /** Sends a message: it appears at once, then each processing step shows as it happens. */
    fun send(raw: String = text, via: String = "text", voice: VoiceDetails? = null) {
        val t = raw.trim()
        if (t.isEmpty()) return
        if (via == "text") text = ""
        val turn = Turn("t-${System.nanoTime()}", t, via, System.currentTimeMillis())
        turns += turn
        viewModelScope.launch {
            // One message at a time, in the order sent.
            processing.withLock {
                val signal = CompletableDeferred<Unit>().also { skip = it }
                try {
                    val session = c.inputs.process(
                        t, via, now = Instant.now(), voice = voice, context = context(), skipAi = signal,
                        onStep = { step -> update(turn.id) { it.copy(steps = merge(it.steps, step)) } },
                    )
                    update(turn.id) { it.copy(session = session, finishedAt = System.currentTimeMillis()) }
                    linkAcrossTurns(session)
                    // Logged instantly by code; the AI's words follow (failures are quiet — the cards said it).
                    if (session.needsReply) {
                        val words = runCatching {
                            c.inputs.reply(session, skipAi = signal, onStep = { step -> update(turn.id) { it.copy(steps = merge(it.steps, step)) } })
                        }.getOrNull()
                        if (words != null) {
                            update(turn.id) { t -> t.copy(session = t.session?.let { s -> s.copy(cards = listOf(words) + s.cards) }, finishedAt = System.currentTimeMillis()) }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.e("Input", "message failed: ${e.message}", e)
                    val msg = "Something went wrong: ${e.message ?: e::class.java.simpleName}"
                    update(turn.id) { it.copy(error = msg, finishedAt = System.currentTimeMillis()) }
                    c.conversation.error(null, "“$t” → $msg")
                } finally {
                    skip = null
                    tick++
                }
            }
        }
    }

    /** "Skip AI": the running message finishes with the offline router and offline steps. */
    fun skipAi() {
        skip?.complete(Unit)
    }

    val skippable: Boolean get() = skip?.isCompleted == false

    private fun context(): ConversationContext {
        var latest: FactorUpdateCard? = null
        var at: Long? = null
        for (t in turns) {
            t.session?.cards?.filterIsInstance<FactorUpdateCard>()?.lastOrNull { !it.undone }?.let { latest = it; at = t.at }
        }
        return ConversationContext(latest, at)
    }

    private fun merge(steps: List<Step>, step: Step): List<Step> {
        val i = steps.indexOfFirst { it.id == step.id && it.startedAt == step.startedAt }
        return if (i >= 0) steps.toMutableList().also { it[i] = step } else steps + step
    }

    private fun update(turnId: String, f: (Turn) -> Turn) {
        val i = turns.indexOfFirst { it.id == turnId }
        if (i >= 0) turns[i] = f(turns[i])
    }

    /** A later message changed an earlier card (logged a suggestion, corrected a dose, undid a factor). */
    private fun linkAcrossTurns(session: InputSession) {
        for (card in session.cards) {
            when (card) {
                is DoseLoggedCard -> {
                    card.dose.proposalId?.let { pid -> markNba(pid, card.message, card.dose.id) }
                    card.previous?.let { prev ->
                        replaceWhere({ it is DoseLoggedCard && it.dose.id == prev.id && it.key != card.key }) {
                            (it as DoseLoggedCard).copy(replacedMessage = "Changed later: ${card.message}")
                        }
                    }
                }
                is MealLoggedCard -> markNba(card.proposalId, card.message, null)
                is InfoCard -> card.ref?.let { ref ->
                    replaceWhere({ it.key == ref && it is FactorUpdateCard }) { (it as FactorUpdateCard).copy(undone = true) }
                }
                else -> Unit
            }
        }
    }

    private fun markNba(proposalId: String, message: String, doseId: String?) {
        replaceWhere({ it is NbaCard && it.proposalId == proposalId && it.loggedMessage == null }) {
            (it as NbaCard).copy(loggedMessage = message, loggedDoseId = doseId)
        }
    }

    private fun replaceWhere(match: (ResultCard) -> Boolean, change: (ResultCard) -> ResultCard) {
        for (i in turns.indices) {
            val s = turns[i].session ?: continue
            if (s.cards.none(match)) continue
            turns[i] = turns[i].copy(session = s.copy(cards = s.cards.map { if (match(it)) change(it) else it }))
        }
    }

    fun replace(key: String, card: ResultCard) = replaceWhere({ it.key == key }) { card }

    private fun inputOf(card: ResultCard): String? = turns.firstOrNull { t -> t.session?.cards?.any { it.key == card.key } == true }?.session?.inputId

    // --- card actions -------------------------------------------------------------------------------

    fun switchPath(turn: Turn, path: String) {
        val s = turn.session ?: return
        act(null, "Switched “${s.raw}” to $path") {
            c.inputs.undoSideEffects(s)
            val redone = c.inputs.process(s.raw, s.via, forcedPath = path, supersedes = s.inputId, context = context())
            update(turn.id) { it.copy(session = redone, finishedAt = System.currentTimeMillis()) }
            linkAcrossTurns(redone)
            "Re-routed as $path"
        }
    }

    fun confirmMeal(card: MealMacrosCard, draft: MealDraft) = act(card, "Confirmed ${draft.description}: ${draft.carbsG.toInt()} C / ${draft.fatG.toInt()} F / ${draft.proteinG.toInt()} P") {
        val nba = c.nba.propose(draft, inputOf(card), bgOverride = card.bgOverride)
        replaceWhere({ it.key == card.key }) { nba }
        CardText.text(nba)
    }

    /** "I took 6 u" (or a different amount) on a Next Best Action. */
    fun logNba(card: NbaCard, nowUnits: Int, laterUnits: Int, reason: String?) = act(card, "Tapped: took $nowUnits u" + (if (laterUnits > 0) " (+ $laterUnits u later)" else "") + (reason?.takeIf { it.isNotBlank() }?.let { " — why: $it" } ?: "")) {
        val logged = c.nba.logProposal(card, nowUnits, laterUnits, reason)
        replace(card.key, card.copy(loggedMessage = logged.message, loggedDoseId = logged.dose.id))
        logged.message
    }

    /** "I ate it" on a carbs / no-insulin action; [extraCarbsG] null = as suggested. */
    fun ateIt(card: NbaCard, extraCarbsG: Double? = null) = act(card, "Tapped: ate it" + (extraCarbsG?.let { " (${it.toInt()} g)" } ?: "")) {
        val msg = if (extraCarbsG == null) c.nba.logMealOnly(card) else c.nba.logMealOnly(card, extraCarbsG)
        replace(card.key, card.copy(loggedMessage = msg))
        msg
    }

    fun recompute(card: NbaCard, bgOverride: Double?) = act(card, "Recalculated with BG ${bgOverride?.toInt()}") {
        val nba = c.nba.propose(card.meal, card.inputId, bgOverride = bgOverride)
        replace(card.key, nba)
        CardText.text(nba)
    }

    /** Undo a logged dose (logged at or after [loggedFrom]): a superseding 0 u row — nothing is deleted. */
    fun undoDose(doseId: String, loggedFrom: Long, cardKey: String) = act(null, "Tapped: undo dose ${doseId.take(8)}") {
        val current = c.db.doses().effectiveLoggedSince(loggedFrom).firstOrNull { it.id == doseId }
            ?: return@act "Already changed — nothing to undo"
        val logged = c.nba.correct(current, 0.0, null, null, "undone on screen")
        replaceWhere({ it.key == cardKey }) { card ->
            when (card) {
                is DoseLoggedCard -> card.copy(replacedMessage = logged.message)
                is NbaCard -> card.copy(loggedMessage = logged.message, loggedDoseId = null)
                else -> card
            }
        }
        logged.message
    }

    /** Edit a logged dose's units and/or time: a superseding row. */
    fun editDose(card: DoseLoggedCard, units: Double, minutesAgo: Long?) = act(card, "Edited dose: ${units} u" + (minutesAgo?.let { ", $it min ago" } ?: "")) {
        val current = c.db.doses().effectiveLoggedSince(card.dose.createdAt - 1).firstOrNull { it.id == card.dose.id }
            ?: return@act "Already changed — nothing to edit"
        val at = minutesAgo?.let { Instant.now().minus(Duration.ofMinutes(it)).toEpochMilli() }
        val logged = c.nba.correct(current, units, at, null, "edited on screen")
        replace(card.key, card.copy(replacedMessage = logged.message))
        val turnIndex = turns.indexOfFirst { t -> t.session?.cards?.any { it.key == card.key } == true }
        if (turnIndex >= 0) {
            val s = turns[turnIndex].session!!
            turns[turnIndex] = turns[turnIndex].copy(session = s.copy(cards = s.cards + DoseLoggedCard("dose-${logged.dose.id}", logged.dose, logged.message, previous = current)))
        }
        logged.message
    }

    fun dismiss(card: ResultCard) {
        viewModelScope.launch { c.conversation.action(inputOf(card), "Dismissed: ${CardText.text(card).take(200)}") }
        when (card) {
            is NbaCard -> replace(card.key, card.copy(dismissed = true))
            is DoseConfirmCard -> replace(card.key, card.copy(dismissed = true))
            else -> replaceWhere({ it.key == card.key }) { it }
        }
    }

    fun logDose(card: DoseConfirmCard, units: Double, insulin: String, givenAt: Long) = act(card, "Logged ${units} u $insulin from the form") {
        val logged = c.nba.logStated(units, insulin, givenAt, inputOf(card))
        replace(card.key, DoseLoggedCard("dose-${logged.dose.id}", logged.dose, logged.message))
        logged.message
    }

    fun undoFactors(card: FactorUpdateCard) = act(card, "Tapped: undo ${card.changes.joinToString { it.name }}") {
        c.factorUpdater.undo(card.outcome)
        replace(card.key, card.copy(undone = true))
        "Undone"
    }

    fun pickFactor(card: FactorPickerCard, factorId: String, preset: String?) = act(card, "Picked $factorId${preset?.let { " ($it)" } ?: ""}") {
        val outcome = c.factorUpdater.apply(
            listOf(FactorUpdater.Request(factorId, preset = preset, at = Instant.now())),
            inputId = inputOf(card), eventSource = "manual", versionSource = ProfileSource.MANUAL,
            summary = "Manual: $factorId${preset?.let { " ($it)" } ?: ""}",
        )
        val updated = FactorUpdateCard("fu-${card.key}", outcome.changes, outcome, ProfileSource.MANUAL, aiQueued = false)
        replace(card.key, updated)
        CardText.text(updated)
    }

    /** Apply the AI proposal's accepted (possibly edited) changes as one new version (spec §9.4). */
    fun decideAi(card: AiProposalCard, accepted: List<ProposedFactorChange>, edited: Boolean) =
        act(card, if (accepted.isEmpty()) "Rejected the AI proposal" else "Accepted ${accepted.size} AI change(s)" + if (edited) " (edited)" else "") {
            val now = Instant.now()
            if (accepted.isEmpty()) {
                val current = c.profiles.current().profile
                c.profiles.saveVersion(
                    current, ProfileSource.AI_UPDATE, ProfileStatus.REJECTED, "Rejected AI proposal: ${card.summary}".take(300),
                    changes = emptyList(), aiCallId = card.callId,
                )
                replace(card.key, card.copy(decision = ProfileStatus.REJECTED, decidedMessage = "Rejected — profile unchanged"))
                return@act "Rejected — profile unchanged"
            }
            val outcome = c.factorUpdater.apply(
                requests = accepted.map { ch ->
                    FactorUpdater.Request(
                        factorId = ch.factorId, action = ch.action, weight = ch.weight, preset = ch.preset, amount = ch.amount,
                        unitsAdd = ch.unitsAdd, windowMinutes = ch.windowMinutes, decay = ch.decay,
                        at = now.minusSeconds(60L * (ch.startedMinutesAgo ?: 0)), note = ch.reason,
                    )
                },
                inputId = card.inputId, eventSource = "ai", versionSource = ProfileSource.AI_UPDATE,
                summary = "AI: ${card.summary}".take(300), aiCallId = card.callId,
                newDefinitions = card.newDefinitions.filter { d -> accepted.any { it.factorId == d.id } },
                status = if (edited || accepted.size < card.changes.size) ProfileStatus.EDITED else ProfileStatus.ACCEPTED,
                now = now,
            )
            val message = "Applied ${accepted.size} change${if (accepted.size == 1) "" else "s"}" + (outcome.version?.let { " · v${it.version}" } ?: "")
            replace(card.key, card.copy(decision = if (edited) ProfileStatus.EDITED else ProfileStatus.ACCEPTED, decidedMessage = message))
            // Update Profile first, then Next Best Action with the updated profile (spec §9.2).
            val turn = turns.firstOrNull { t -> t.session?.cards?.any { it.key == card.key } == true }
            turn?.session?.cards?.filterIsInstance<NbaCard>()?.filter { it.loggedMessage == null && !it.dismissed }?.forEach { old ->
                replace(old.key, c.nba.propose(old.meal, old.inputId, bgOverride = old.bgOverride))
            }
            // Units added for caffeine & co. ride on the next meal's dose — nothing to inject now.
            if (accepted.any { it.unitsAdd != null }) "$message · added to your next meal's dose" else message
        }

    fun logSecond(proposalId: String, units: Int, skipped: Boolean = false) = act(null, if (skipped) "Skipped the second injection" else "Logged the second injection: $units u") {
        if (c.nba.logSecond(proposalId, units, skipped = skipped)) "Logged" else "Already logged"
    }

    /** Runs one card action at a time and writes what was tapped and what happened to the conversation log. */
    private fun act(card: ResultCard?, what: String, block: suspend () -> String) {
        val inputId = card?.let { inputOf(it) }
        if (busy) {
            viewModelScope.launch { c.conversation.action(inputId, "$what → ignored (still working on the last tap)") }
            return
        }
        busy = true
        viewModelScope.launch {
            try {
                val result = block()
                c.conversation.action(inputId, "$what → $result")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is DoseUnavailableException) AppLog.w("Input", e.message.orEmpty()) else AppLog.e("Input", "action failed: ${e.message}", e)
                val msg = if (e is DoseUnavailableException) e.message.orEmpty() else "Something went wrong: ${e.message ?: e::class.java.simpleName}"
                c.conversation.error(inputId, "$what → $msg")
                val err = InfoCard("err-${System.nanoTime()}", msg, isError = true)
                val i = turns.indexOfLast { it.session != null }
                if (i >= 0) {
                    val s = turns[i].session!!
                    turns[i] = turns[i].copy(session = s.copy(cards = s.cards + err))
                } else {
                    turns += Turn("t-${System.nanoTime()}", what, "tap", System.currentTimeMillis(), error = msg, finishedAt = System.currentTimeMillis())
                }
            } finally {
                busy = false
                tick++
            }
        }
    }

    fun clear() {
        turns.clear()
        history = emptyList()
    }

    private companion object {
        const val HISTORY_MS = 24 * 60 * 60 * 1000L
        const val HISTORY_ITEMS = 60
    }
}
