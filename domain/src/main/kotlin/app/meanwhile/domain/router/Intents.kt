package app.meanwhile.domain.router

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Router output (spec §9.2). Same shape whether it came from the AI or the offline classifier. */
@Serializable
data class RouteResult(
    val intents: List<RoutedIntent>,
    /** ai | offline */
    val router: String,
) {
    val path: String get() = intents.joinToString(",") { it.type }
}

@Serializable
sealed interface RoutedIntent {
    val type: String
    val textSpan: String
    val confidence: Double
}

@Serializable
@SerialName("meal")
data class MealIntent(
    override val textSpan: String,
    val carbsG: Double? = null,
    val fatG: Double? = null,
    val proteinG: Double? = null,
    val liquidOrSugary: Boolean = false,
    val description: String = "",
    override val confidence: Double = 1.0,
) : RoutedIntent {
    override val type: String get() = "meal"
    val hasMacros: Boolean get() = carbsG != null || fatG != null || proteinG != null
}

@Serializable
@SerialName("factor_update")
data class FactorIntent(
    override val textSpan: String,
    val factorId: String,
    /** activate | deactivate */
    val action: String = "activate",
    val preset: String? = null,
    /** Cups, drinks, … */
    val amount: Double? = null,
    /** Minutes ago the event happened / ended (e.g. workout ended 30 min ago). */
    val minutesAgo: Int? = null,
    /** Workout type, duration_min, distance, intensity, drink type, … */
    val details: Map<String, String> = emptyMap(),
    override val confidence: Double = 1.0,
) : RoutedIntent {
    override val type: String get() = "factor_update"
}

@Serializable
@SerialName("dose_given")
data class DoseIntent(
    override val textSpan: String,
    val units: Double,
    /** rapid | long */
    val insulin: String = "rapid",
    val minutesAgo: Int? = null,
    override val confidence: Double = 1.0,
) : RoutedIntent {
    override val type: String get() = "dose_given"
}

@Serializable
@SerialName("feedback")
data class FeedbackIntent(
    override val textSpan: String,
    val text: String,
    override val confidence: Double = 1.0,
) : RoutedIntent {
    override val type: String get() = "feedback"
}

/**
 * Danny corrects or cancels a dose he already logged (1.4): "never mind, I only took 5",
 * "I didn't take any", "make that 4", "cancel that". Applied as a new dose row that supersedes the
 * old one — nothing is edited or deleted.
 */
@Serializable
@SerialName("dose_correction")
data class DoseCorrectionIntent(
    override val textSpan: String,
    /** The corrected units; 0 = he didn't take it; null = only the time changes. */
    val units: Double?,
    /** rapid | long — which dose; null = the most recent one. */
    val insulin: String? = null,
    val minutesAgo: Int? = null,
    override val confidence: Double = 1.0,
) : RoutedIntent {
    override val type: String get() = "dose_correction"
}

/** "took it", "done", "ate it": Danny did what the last Next Best Action said (1.4). */
@Serializable
@SerialName("followed")
data class FollowedIntent(
    override val textSpan: String,
    val minutesAgo: Int? = null,
    override val confidence: Double = 1.0,
) : RoutedIntent {
    override val type: String get() = "followed"
}

/** "BG 140", "I'm at 85": a BG to use for this message's Next Best Action instead of the CGM. */
@Serializable
@SerialName("bg_reading")
data class BgIntent(
    override val textSpan: String,
    val mgDl: Double,
    override val confidence: Double = 1.0,
) : RoutedIntent {
    override val type: String get() = "bg_reading"
}
