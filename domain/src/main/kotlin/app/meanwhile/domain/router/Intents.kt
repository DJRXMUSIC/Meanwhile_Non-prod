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
