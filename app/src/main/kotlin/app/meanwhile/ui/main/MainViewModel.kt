package app.meanwhile.ui.main

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.meanwhile.data.input.AiProposalCard
import app.meanwhile.data.input.DoseConfirmCard
import app.meanwhile.data.input.DoseUnavailableException
import app.meanwhile.data.input.FactorPickerCard
import app.meanwhile.data.input.FactorUpdateCard
import app.meanwhile.data.input.FactorUpdater
import app.meanwhile.data.input.InfoCard
import app.meanwhile.data.input.InputSession
import app.meanwhile.data.input.MealDraft
import app.meanwhile.data.input.MealMacrosCard
import app.meanwhile.data.input.NbaCard
import app.meanwhile.data.input.ProposedFactorChange
import app.meanwhile.data.input.ResultCard
import app.meanwhile.data.profile.ProfileSource
import app.meanwhile.data.profile.ProfileStatus
import app.meanwhile.di.AppContainer
import app.meanwhile.domain.router.RouteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant

class MainViewModel(private val c: AppContainer) : ViewModel() {
    var text by mutableStateOf("")
    var via by mutableStateOf("text")
    var session by mutableStateOf<InputSession?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    /** The morning report is offered once per app session. */
    var morningPrompted = false

    /** Bumped after anything that changes doses/profile so dependent views refresh. */
    var tick by mutableIntStateOf(0)
        private set

    fun submit() {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        run {
            session = c.inputs.process(t, via)
            text = ""
            via = "text"
        }
    }

    fun switchPath(path: String) {
        val s = session ?: return
        run {
            c.inputs.undoSideEffects(s)
            session = c.inputs.process(s.raw, s.via, forcedPath = path, supersedes = s.inputId)
        }
    }

    fun confirmMeal(card: MealMacrosCard, draft: MealDraft) = run {
        replace(card.key, c.nba.propose(draft, session?.inputId))
    }

    fun logNba(card: NbaCard, nowUnits: Int, laterUnits: Int, reason: String?) = run {
        val msg = c.nba.logFromProposal(card, nowUnits, laterUnits, reason)
        replace(card.key, card.copy(loggedMessage = msg))
    }

    fun recompute(card: NbaCard, bgOverride: Double?) = run {
        replace(card.key, c.nba.propose(card.meal, card.inputId, bgOverride = bgOverride))
    }

    fun dismiss(card: ResultCard) {
        when (card) {
            is NbaCard -> replace(card.key, card.copy(dismissed = true))
            is DoseConfirmCard -> replace(card.key, card.copy(dismissed = true))
            else -> session = session?.let { s -> s.copy(cards = s.cards.filterNot { it.key == card.key }) }
        }
    }

    fun logDose(card: DoseConfirmCard, units: Double, insulin: String, givenAt: Long) = run {
        val msg = c.nba.logDose(units, insulin, givenAt, session?.inputId)
        replace(card.key, card.copy(units = units, insulin = insulin, givenAt = givenAt, loggedMessage = msg))
    }

    fun undoFactors(card: FactorUpdateCard) = run {
        c.factorUpdater.undo(card.outcome)
        replace(card.key, card.copy(undone = true))
    }

    fun pickFactor(card: FactorPickerCard, factorId: String, preset: String?) = run {
        val outcome = c.factorUpdater.apply(
            listOf(FactorUpdater.Request(factorId, preset = preset, at = Instant.now())),
            inputId = session?.inputId, eventSource = "manual", versionSource = ProfileSource.MANUAL,
            summary = "Manual: $factorId${preset?.let { " ($it)" } ?: ""}",
        )
        replace(card.key, FactorUpdateCard("fu-${card.key}", outcome.changes, outcome, ProfileSource.MANUAL, aiQueued = false))
    }

    /** Apply the AI proposal's accepted (possibly edited) changes as one new version (spec §9.4). */
    fun decideAi(card: AiProposalCard, accepted: List<ProposedFactorChange>, edited: Boolean) = run {
        val now = Instant.now()
        if (accepted.isEmpty()) {
            val current = c.profiles.current().profile
            c.profiles.saveVersion(
                current, ProfileSource.AI_UPDATE, ProfileStatus.REJECTED, "Rejected AI proposal: ${card.summary}".take(300),
                changes = emptyList(), aiCallId = card.callId,
            )
            replace(card.key, card.copy(decision = ProfileStatus.REJECTED, decidedMessage = "Rejected — profile unchanged"))
            return@run
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
        replace(
            card.key,
            card.copy(
                decision = if (edited) ProfileStatus.EDITED else ProfileStatus.ACCEPTED,
                decidedMessage = "Applied ${accepted.size} change${if (accepted.size == 1) "" else "s"}" +
                    (outcome.version?.let { " · v${it.version}" } ?: ""),
            ),
        )
        // Update Profile first, then Next Best Action with the updated profile (spec §9.2).
        session?.cards?.filterIsInstance<NbaCard>()?.filter { it.loggedMessage == null && !it.dismissed }?.forEach { nbaCard ->
            replace(nbaCard.key, c.nba.propose(nbaCard.meal, nbaCard.inputId))
        }
        val hasCoffeeNba = session?.cards?.any { it is NbaCard } == true
        if (!hasCoffeeNba && accepted.any { it.unitsAdd != null } && session?.cards?.none { it is MealMacrosCard } == true) {
            val nbaCard = c.nba.propose(MealDraft(description = "caffeine"), card.inputId)
            session = session?.let { s -> s.copy(cards = s.cards + nbaCard) }
        }
    }

    fun logSecond(proposalId: String, units: Int, skipped: Boolean = false) = run {
        c.nba.logSecond(proposalId, units, skipped = skipped)
    }

    fun clear() {
        session = null
    }

    fun replace(key: String, card: ResultCard) {
        session = session?.let { s -> s.copy(cards = s.cards.map { if (it.key == key) card else it }) }
    }

    /** Runs one action at a time: a second tap while one is running (e.g. double-tapping Log) is ignored. */
    private fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val s = session
                val text = if (e is DoseUnavailableException) e.message.orEmpty() else "Something went wrong: ${e.message ?: e::class.java.simpleName}"
                val err = InfoCard("err-${System.nanoTime()}", text, isError = true)
                session = s?.copy(cards = s.cards + err) ?: InputSession("", text, via, RouteResult(emptyList(), "offline"), listOf(err))
            } finally {
                busy = false
                tick++
            }
        }
    }
}
