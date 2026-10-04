package app.meanwhile.data.input

import app.meanwhile.data.db.ProfileVersionEntity
import app.meanwhile.domain.dose.DoseInput
import app.meanwhile.domain.dose.DoseResult
import app.meanwhile.domain.router.RouteResult
import kotlinx.serialization.Serializable

/** One processed input and the result cards it produced (spec §9). */
data class InputSession(
    val inputId: String,
    val raw: String,
    val via: String,
    val route: RouteResult,
    val cards: List<ResultCard>,
    /** True when this input replaced an earlier routing via the path switch. */
    val switched: Boolean = false,
)

sealed interface ResultCard {
    val key: String
}

data class FeedbackSavedCard(override val key: String, val text: String) : ResultCard

data class FactorChangeView(
    val factorId: String,
    val name: String,
    /** activate | deactivate | add */
    val action: String,
    val weight: Double?,
    val units: Double?,
    val window: String,
    val note: String?,
)

data class FactorUpdateCard(
    override val key: String,
    val changes: List<FactorChangeView>,
    val outcome: FactorUpdater.Outcome,
    /** offline_fallback | manual | ai_update */
    val source: String,
    val aiQueued: Boolean,
    val undone: Boolean = false,
) : ResultCard

@Serializable
data class MealDraft(
    val description: String = "",
    val carbsG: Double = 0.0,
    val fatG: Double = 0.0,
    val proteinG: Double = 0.0,
    val liquidOrSugary: Boolean = false,
    val isEstimate: Boolean = false,
    /** JSON with estimate provenance (provider/model/notes). */
    val estimateDetails: String = "{}",
)

/** Food described without numbers: ask for (or confirm estimated) carbs/fat/protein. */
data class MealMacrosCard(
    override val key: String,
    val draft: MealDraft,
    val hasNumbers: Boolean,
    val note: String? = null,
    val estimating: Boolean = false,
) : ResultCard

data class NbaCard(
    override val key: String,
    val proposalId: String,
    val inputId: String?,
    val meal: MealDraft,
    val input: DoseInput,
    val result: DoseResult,
    val profileLabel: String,
    val profileVersion: ProfileVersionEntity?,
    val bgAgeMinutes: Long?,
    val bgStale: Boolean,
    val computedAt: Long,
    val computeMs: Long,
    val loggedMessage: String? = null,
    val dismissed: Boolean = false,
) : ResultCard

data class DoseConfirmCard(
    override val key: String,
    val units: Double,
    /** rapid | long */
    val insulin: String,
    val givenAt: Long,
    val loggedMessage: String? = null,
    val dismissed: Boolean = false,
) : ResultCard

/** No factor was recognised for a "factor update": let Danny pick one. */
data class FactorPickerCard(override val key: String, val text: String) : ResultCard

data class InfoCard(override val key: String, val message: String, val isError: Boolean = false) : ResultCard

/** One factor change the AI proposes (spec §9.4 online). Danny accepts, edits or rejects each. */
data class ProposedFactorChange(
    val factorId: String,
    val name: String,
    val kind: app.meanwhile.domain.profile.FactorKind,
    val action: String,
    val weight: Double?,
    val windowMinutes: Int?,
    val decay: app.meanwhile.domain.profile.DecayRule?,
    val unitsAdd: Double?,
    val amount: Double?,
    val preset: String?,
    val startedMinutesAgo: Int?,
    val reason: String,
    val isNewFactor: Boolean = false,
    /** The factor definition's bounds, to flag a proposed weight outside them (spec §7: AI sets weights within bounds). */
    val minWeight: Double? = null,
    val maxWeight: Double? = null,
)

data class AiProposalCard(
    override val key: String,
    val inputId: String,
    val text: String,
    val callId: String,
    val provider: String,
    val model: String,
    val fallbackUsed: Boolean,
    val summary: String,
    val changes: List<ProposedFactorChange>,
    val newDefinitions: List<app.meanwhile.domain.profile.FactorDefinition>,
    /** accepted | edited | rejected once decided. */
    val decision: String? = null,
    val decidedMessage: String? = null,
) : ResultCard
