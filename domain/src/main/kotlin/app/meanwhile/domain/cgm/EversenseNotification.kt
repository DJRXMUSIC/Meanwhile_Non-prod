package app.meanwhile.domain.cgm

import kotlin.math.roundToInt

/**
 * The built-in Eversense interceptor's reading logic. The official Eversense app shows the current
 * glucose in its notification; the phone side hands over the notification's visible texts (title,
 * text, every visible TextView of a custom layout) and the content descriptions of its views, and this
 * turns them into a reading — or says exactly why not.
 *
 * Deliberately strict: a value counts only when one whole text element *is* the number (units and
 * arrows aside), and exactly one distinct value may be present. "Low glucose alert: 65 mg/dL",
 * predictions, times and battery levels never become readings.
 */
object EversenseNotification {
    const val SOURCE = "eversense"

    /** Eversense (Gen 1/2 and E3), older Eversense, and Eversense 365 (US). */
    val PACKAGES = setOf(
        "com.senseonics.gen12androidapp",
        "com.senseonics.androidapp",
        "com.senseonics.eversense365.us",
    )

    /** The sensor's reporting range; outside it the app shows LO / HI instead of a number. */
    const val MIN_MG_DL = 40
    const val MAX_MG_DL = 400
    private const val MG_PER_MMOL = 18.0182

    sealed interface Result {
        data class Reading(val mgDl: Int, val direction: String?) : Result
        data class Rejected(val reason: String) : Result
    }

    fun parse(texts: List<String>, descriptions: List<String> = emptyList()): Result {
        val cleaned = texts.map(::clean).filter { it.isNotEmpty() }
        val descs = descriptions.map(::clean).filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return Result.Rejected("empty notification")
        val mmol = (cleaned + descs).any { it.contains("mmol", ignoreCase = true) }

        cleaned.firstOrNull { it.equals("LO", true) || it.equals("HI", true) }?.let {
            return Result.Rejected("the sensor shows ${it.uppercase()} (outside $MIN_MG_DL–$MAX_MG_DL mg/dL)")
        }
        val values = cleaned.mapNotNull { valueOf(it, mmol) }.distinct()
        return when {
            values.isEmpty() -> Result.Rejected("no glucose value in: " + cleaned.joinToString(" | ").take(300))
            values.size > 1 -> Result.Rejected("more than one value (${values.joinToString()}) in: " + cleaned.joinToString(" | ").take(300))
            values.single() !in MIN_MG_DL..MAX_MG_DL -> Result.Rejected("${values.single()} mg/dL is outside the sensor range")
            else -> Result.Reading(values.single(), direction(cleaned, descs))
        }
    }

    private fun clean(s: String): String = s
        .replace(' ', ' ').replace(' ', ' ')
        .replace("⁠", "").replace("​", "").replace("‎", "").replace("‏", "")
        .trim()

    /** The number a text element consists of, in mg/dL (out-of-range values included, for a clear reason). */
    private fun valueOf(text: String, mmol: Boolean): Int? {
        val bare = text.filterNot(::isArrow)
            .replace(Regex("(?i)mmol/l|mg/dl"), "")
            .replace(Regex("[≤≥<>]"), "")
            .trim()
        return if (mmol) {
            Regex("""\d{1,2}([.,]\d)?""").matchEntire(bare)?.let { (bare.replace(',', '.').toDouble() * MG_PER_MMOL).roundToInt() }
        } else {
            Regex("""\d{2,3}""").matchEntire(bare)?.value?.toInt()
        }
    }

    private fun isArrow(c: Char) = c in '←'..'⇿' || c in '✀'..'➿' || c in '⤀'..'⥿' || c in '⬀'..'⯿'

    /** Nightscout direction from an arrow character, else from a trend description ("Rising rapidly"). */
    private fun direction(texts: List<String>, descriptions: List<String>): String? {
        for (c in (texts + descriptions).joinToString("")) ARROWS[c]?.let { return it }
        for (d in descriptions.map { it.lowercase() }) {
            val fast = listOf("rapid", "quick", "fast").any { it in d }
            when {
                "rising" in d || "increasing" in d -> return if (fast) "SingleUp" else "FortyFiveUp"
                "falling" in d || "decreasing" in d -> return if (fast) "SingleDown" else "FortyFiveDown"
                "stable" in d || "steady" in d -> return "Flat"
            }
        }
        return null
    }

    private val ARROWS = mapOf(
        '⇈' to "DoubleUp", '⇊' to "DoubleDown",
        '↑' to "SingleUp", '⬆' to "SingleUp", '↓' to "SingleDown", '⬇' to "SingleDown",
        '↗' to "FortyFiveUp", '⬈' to "FortyFiveUp", '➚' to "FortyFiveUp",
        '↘' to "FortyFiveDown", '⬊' to "FortyFiveDown", '➘' to "FortyFiveDown",
        '→' to "Flat", '➡' to "Flat",
    )
}
