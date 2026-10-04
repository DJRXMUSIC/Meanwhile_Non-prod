package app.meanwhile.domain.profile

import kotlinx.serialization.Serializable

/**
 * Everything the dose engine uses (spec §8). Every number here is a *starting value*: the profile is
 * versioned, and the learn cycle / AI / Danny can change any field by path (see [ProfilePatch]).
 */
@Serializable
data class Profile(
    val dose: DoseSettings = DoseSettings(),
    val iob: IobCurve = IobCurve(),
    val meal: MealRules = MealRules(),
    val split: SplitRules = SplitRules(),
    val leadTime: LeadTimeRules = LeadTimeRules(),
    /** Local hour at which factors reset to baseline (spec §7.4). */
    val resetHour: Int = 1,
    val factors: List<FactorDefinition> = DefaultFactors.all,
    /** Factor activations currently in effect (expired ones are ignored and pruned on the next version). */
    val active: List<ActiveFactor> = emptyList(),
) {
    fun factor(id: String): FactorDefinition? = factors.firstOrNull { it.id == id }
}

@Serializable
data class DoseSettings(
    /** Insulin-to-carb ratio: grams covered by 1 unit. */
    val icr: Double = 10.0,
    /** Correction factor: mg/dL lowered by 1 unit. */
    val isf: Double = 25.0,
    val target: Double = 100.0,
    /** Upper cap on the combined multiplier; there is deliberately no floor. */
    val combinedCap: Double = 2.0,
    /** Pen increment; rounding is to the nearest increment, halves up, minimum 0. */
    val unitIncrement: Double = 1.0,
    val insulin: String = "Humalog",
)

/** Exponential insulin activity model (spec §6). */
@Serializable
data class IobCurve(
    val delayMin: Double = 10.0,
    val peakMin: Double = 75.0,
    val durationMin: Double = 300.0,
)

/** Fat/protein handling (spec §5.2). */
@Serializable
data class MealRules(
    val lowCarbThresholdG: Double = 15.0,
    val kFatPerG: Double = 0.0045,
    val kProteinPerG: Double = 0.0060,
    val fatGPerUnit: Double = 11.0,
    val proteinGPerUnit: Double = 25.0,
)

/** Split-dose rules for pens (spec §5.3). */
@Serializable
data class SplitRules(
    val enabled: Boolean = true,
    val minFatG: Double = 40.0,
    val minProteinG: Double = 25.0,
    val minCarbsG: Double = 15.0,
    val firstFraction: Double = 0.60,
    /** Second injection reminder, minutes after meal start. */
    val secondAfterMin: Int = 60,
)

/** Pre-bolus lead time rules (spec §5.4). */
@Serializable
data class LeadTimeRules(
    val baseMin: Int = 12,
    val eatNowBelowBg: Double = 90.0,
    val eatNowTrendAtOrBelow: Double = -2.0,
    val highBgStart: Double = 150.0,
    val highBgStepMgDl: Double = 50.0,
    val highBgStepMin: Int = 5,
    val liquidOrSugaryMin: Int = 5,
    val highFatG: Double = 40.0,
    val highFatMin: Int = -5,
    /** Minutes added while a factor is active, by factor id (F7 exercise: −5). */
    val factorMin: Map<String, Int> = mapOf("F7" to -5),
    val minMin: Int = 0,
    val maxMin: Int = 30,
)

@Serializable
enum class FactorKind {
    /** Carbs: drives the baseline via ICR. */
    BASELINE,

    /** Weight computed from meal grams (fat, protein). */
    MEAL_COMPUTED,

    /** Weight applied while active. */
    MULTIPLIER,

    /** Adds units per event, consumed by the next logged dose (caffeine). */
    UNITS_PER_EVENT,

    /** Weight computed by code from CGM data (recent hypo, overnight highs). */
    AUTO_MULTIPLIER,
}

@Serializable
enum class WindowType {
    /** Until the next daily reset (1 am). */
    UNTIL_RESET,

    /** For [WindowRule.minutes] from the event. */
    FIXED,

    /** Until the next logged rapid-acting dose (or the daily reset). */
    CONSUMED_BY_NEXT_DOSE,

    /** Part of one meal's calculation only. */
    PER_MEAL,

    /** For [WindowRule.minutes] after the most recent trigger (e.g. last reading < 70). */
    AFTER_LAST_TRIGGER,
}

@Serializable
data class WindowRule(
    val type: WindowType,
    val minutes: Int? = null,
    /** False: each new event replaces the previous one (no stacking). */
    val stacks: Boolean = false,
    /** True for factors that keep their own timer through the 1 am reset (F5, F7, F9, F10, F12). */
    val survivesReset: Boolean = false,
)

/** Stepwise weight decay by minutes since the event (alcohol, spec §7.2). */
@Serializable
data class DecayRule(val steps: List<DecayStep>) {
    /**
     * Weight [minutes] after an activation whose initial weight was [initial]. Steps are the default
     * curve; a different initial weight scales the distance from 1.0 proportionally.
     */
    fun weightAt(initial: Double, minutes: Double): Double {
        val sorted = steps.sortedBy { it.fromMinutes }
        val step = sorted.lastOrNull { minutes >= it.fromMinutes } ?: sorted.firstOrNull() ?: return initial
        val first = sorted.first().weight
        if (first == 1.0) return step.weight
        return 1.0 - (1.0 - initial) * (1.0 - step.weight) / (1.0 - first)
    }
}

@Serializable
data class DecayStep(val fromMinutes: Int, val weight: Double)

/** A factor as data (spec §7.5): new ones can come from an accepted AI proposal without an app update. */
@Serializable
data class FactorDefinition(
    val id: String,
    val name: String,
    val kind: FactorKind,
    val minWeight: Double? = null,
    val maxWeight: Double? = null,
    /** Weight code applies when activated without AI (offline fallback, spec §9.4). */
    val defaultWeight: Double? = null,
    /** Named levels, e.g. sleep good/ok/poor, stress/illness. */
    val presets: Map<String, Double> = emptyMap(),
    /** UNITS_PER_EVENT: units added per event unit (caffeine: 1 u per cup). */
    val unitsPerEvent: Double? = null,
    val window: WindowRule,
    val decay: DecayRule? = null,
    /** meal | factor_update | morning_report | auto */
    val input: String = "factor_update",
    /** Words the offline router recognises for this factor. */
    val keywords: List<String> = emptyList(),
    /** Extra tunables for computed factors (F10, F11). */
    val params: Map<String, Double> = emptyMap(),
    val description: String = "",
    val enabled: Boolean = true,
)

/** One activation in effect. */
@Serializable
data class ActiveFactor(
    val factorId: String,
    val weight: Double? = null,
    /** Epoch millis the effect starts (for exercise: when the workout ended). */
    val startedAt: Long,
    /** Overrides the definition's window length. */
    val windowMinutes: Int? = null,
    /** Overrides the definition's decay. */
    val decay: DecayRule? = null,
    /** ai | offline | manual | morning_report | auto */
    val source: String,
    val eventId: String? = null,
    val preset: String? = null,
    val note: String? = null,
)
