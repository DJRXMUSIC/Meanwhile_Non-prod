package app.meanwhile.ui.main

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.meanwhile.data.input.DoseConfirmCard
import app.meanwhile.data.input.FactorPickerCard
import app.meanwhile.data.input.FactorUpdateCard
import app.meanwhile.data.input.FactorUpdater
import app.meanwhile.data.input.InfoCard
import app.meanwhile.data.input.InputSession
import app.meanwhile.data.input.MealDraft
import app.meanwhile.data.input.MealMacrosCard
import app.meanwhile.data.input.NbaCard
import app.meanwhile.data.input.ResultCard
import app.meanwhile.data.profile.ProfileSource
import app.meanwhile.di.AppContainer
import app.meanwhile.domain.router.RouteResult
import kotlinx.coroutines.launch
import java.time.Instant

class MainViewModel(private val c: AppContainer) : ViewModel() {
    var text by mutableStateOf("")
    var via by mutableStateOf("text")
    var session by mutableStateOf<InputSession?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

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

    fun logSecond(proposalId: String, units: Int, skipped: Boolean = false) = run {
        c.nba.logSecond(proposalId, units, skipped = skipped)
    }

    fun clear() {
        session = null
    }

    fun replace(key: String, card: ResultCard) {
        session = session?.let { s -> s.copy(cards = s.cards.map { if (it.key == key) card else it }) }
    }

    private fun run(block: suspend () -> Unit) {
        busy = true
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                val s = session
                val err = InfoCard("err-${System.nanoTime()}", "Something went wrong: ${e.message ?: e::class.java.simpleName}", isError = true)
                session = s?.copy(cards = s.cards + err) ?: InputSession("", text, via, RouteResult(emptyList(), "offline"), listOf(err))
            } finally {
                busy = false
                tick++
            }
        }
    }
}
