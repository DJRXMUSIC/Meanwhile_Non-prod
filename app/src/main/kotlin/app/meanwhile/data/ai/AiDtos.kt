package app.meanwhile.data.ai

import app.meanwhile.domain.profile.DecayRule
import app.meanwhile.domain.profile.DecayStep
import app.meanwhile.domain.profile.FactorDefinition
import app.meanwhile.domain.profile.FactorKind
import app.meanwhile.domain.profile.WindowRule
import app.meanwhile.domain.profile.WindowType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Edge Function envelope (spec §10.1). */
@Serializable
data class AiEnvelope(
    val result: JsonElement? = null,
    val provider: String? = null,
    val model: String? = null,
    @SerialName("latency_ms") val latencyMs: Long? = null,
    @SerialName("fallback_used") val fallbackUsed: Boolean = false,
    val attempts: List<AiAttempt> = emptyList(),
    val error: String? = null,
    @SerialName("retry_with") val retryWith: String? = null,
    /** Learn-cycle batch state (1.4); result is null until it ends. */
    val batch: AiBatch? = null,
)

@Serializable
data class AiBatch(
    val id: String,
    /** submitted | processing | ended */
    val status: String,
    val model: String? = null,
    val error: String? = null,
)

@Serializable
data class AiAttempt(
    val provider: String,
    val model: String = "",
    val ok: Boolean,
    val error: String? = null,
    @SerialName("latency_ms") val latencyMs: Long = 0,
    val validation: String? = null,
)

// --- route ---
@Serializable
data class RouteDto(val intents: List<RouteIntentDto>)

@Serializable
data class RouteIntentDto(val type: String, @SerialName("text_span") val textSpan: String, val confidence: Double = 1.0)

// --- estimate_meal ---
@Serializable
data class MealEstimateDto(
    @SerialName("carbs_g") val carbsG: Double,
    @SerialName("fat_g") val fatG: Double,
    @SerialName("protein_g") val proteinG: Double,
    @SerialName("liquid_or_sugary") val liquidOrSugary: Boolean = false,
    @SerialName("is_estimate") val isEstimate: Boolean = true,
    val notes: String = "",
)

// --- update_profile ---
@Serializable
data class UpdateProfileDto(
    val changes: List<FactorChangeDto> = emptyList(),
    @SerialName("new_factors") val newFactors: List<NewFactorDto> = emptyList(),
    val summary: String = "",
)

@Serializable
data class FactorChangeDto(
    @SerialName("factor_id") val factorId: String,
    val weight: Double? = null,
    @SerialName("window_minutes") val windowMinutes: Int? = null,
    @SerialName("decay_rule") val decayRule: DecayDto? = null,
    @SerialName("units_add") val unitsAdd: Double? = null,
    val reason: String = "",
    val action: String = "activate",
    @SerialName("started_minutes_ago") val startedMinutesAgo: Int? = null,
    val amount: Double? = null,
    val preset: String? = null,
)

@Serializable
data class NewFactorDto(val definition: FactorDefDto, val weight: Double? = null, val reason: String = "")

@Serializable
data class DecayDto(val steps: List<DecayStepDto>) {
    fun toDomain() = DecayRule(steps.map { DecayStep(it.fromMinutes, it.weight) })
}

@Serializable
data class DecayStepDto(@SerialName("from_minutes") val fromMinutes: Int, val weight: Double)

@Serializable
data class NamedValueDto(val name: String, val weight: Double = 0.0, val value: Double = 0.0)

@Serializable
data class WindowDto(
    val type: String,
    val minutes: Int? = null,
    val stacks: Boolean = false,
    @SerialName("survives_reset") val survivesReset: Boolean = false,
)

@Serializable
data class FactorDefDto(
    val id: String,
    val name: String,
    val kind: String,
    @SerialName("min_weight") val minWeight: Double? = null,
    @SerialName("max_weight") val maxWeight: Double? = null,
    @SerialName("default_weight") val defaultWeight: Double? = null,
    val presets: List<NamedValueDto> = emptyList(),
    @SerialName("units_per_event") val unitsPerEvent: Double? = null,
    val window: WindowDto,
    val decay: DecayDto? = null,
    val input: String = "factor_update",
    val keywords: List<String> = emptyList(),
    val params: List<NamedValueDto> = emptyList(),
    val description: String = "",
) {
    fun toDomain() = FactorDefinition(
        id = id, name = name,
        kind = runCatching { FactorKind.valueOf(kind) }.getOrDefault(FactorKind.MULTIPLIER),
        minWeight = minWeight, maxWeight = maxWeight, defaultWeight = defaultWeight,
        presets = presets.associate { it.name to it.weight },
        unitsPerEvent = unitsPerEvent,
        window = WindowRule(
            type = runCatching { WindowType.valueOf(window.type) }.getOrDefault(WindowType.FIXED),
            minutes = window.minutes, stacks = window.stacks, survivesReset = window.survivesReset,
        ),
        decay = decay?.toDomain(),
        input = input, keywords = keywords, params = params.associate { it.name to it.value }, description = description,
    )
}

// --- learn_cycle (M7) ---
@Serializable
data class LearnCycleDto(
    @SerialName("proposed_settings") val proposedSettings: ProposedSettingsDto = ProposedSettingsDto(),
    @SerialName("factor_changes") val factorChanges: List<LearnFactorChangeDto> = emptyList(),
    @SerialName("new_factors") val newFactors: List<LearnNewFactorDto> = emptyList(),
    @SerialName("setting_changes") val settingChanges: List<LearnSettingChangeDto> = emptyList(),
    val observations: List<String> = emptyList(),
    val summary: String = "",
)

@Serializable
data class ProposedSettingsDto(
    @SerialName("ICR") val icr: Double? = null,
    @SerialName("ISF") val isf: Double? = null,
    val target: Double? = null,
    @SerialName("lead_time_min") val leadTimeMin: Double? = null,
    @SerialName("iob_peak_min") val iobPeakMin: Double? = null,
    @SerialName("iob_duration_min") val iobDurationMin: Double? = null,
)

@Serializable
data class LearnFactorChangeDto(
    @SerialName("factor_id") val factorId: String,
    val field: String,
    val old: JsonElement? = null,
    val new: JsonElement? = null,
    val evidence: String = "",
)

@Serializable
data class LearnNewFactorDto(val id: String, val name: String, val definition: FactorDefDto, val evidence: String = "")

@Serializable
data class LearnSettingChangeDto(val path: String, val old: JsonElement? = null, val new: JsonElement? = null, val evidence: String = "")
