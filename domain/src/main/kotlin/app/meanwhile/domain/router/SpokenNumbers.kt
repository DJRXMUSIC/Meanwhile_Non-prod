package app.meanwhile.domain.router

/**
 * Converts spoken number words to digits — "sixty carbs twenty fat" → "60 carbs 20 fat",
 * "six and a half units" → "6.5 units" — so the deterministic parsers (spec §9.2) see the same
 * input whether Danny typed or spoke. Expects lowercase text; everything else passes through.
 */
object SpokenNumbers {

    private val UNITS = mapOf(
        "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9,
    )
    private val TEENS = mapOf(
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
        "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
    )
    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "seventy" to 70,
        "eighty" to 80, "ninety" to 90,
    )

    private val U = UNITS.keys.joinToString("|")
    private val TEEN = TEENS.keys.joinToString("|")
    private val TEN = TENS.keys.joinToString("|")

    // "two hundred and fifty five" | "a hundred" | "twenty-five" | "fifteen" | "sixty" | "seven".
    // The "and" after "hundred" is only consumed together with a following number, so
    // "a hundred and then some" keeps its "and".
    private val WORD = Regex(
        "\\b(?:" +
            "(?:(?<h>$U|a)\\s+)?hundred(?:(?:\\s+and)?\\s+(?:(?<ht>$TEN)(?:[\\s-](?<htu>$U))?|(?<hs>$TEEN|$U)))?" +
            "|(?<t>$TEN)(?:[\\s-](?<tu>$U))?" +
            "|(?<teen>$TEEN)" +
            "|(?<u>$U)" +
            ")\\b",
    )

    private val POINT = Regex("\\b(\\d+)\\s+point\\s+(\\d+)\\b")
    private val AND_A_HALF = Regex("\\b(\\d+)(?:\\.(\\d+))?\\s+and\\s+a\\s+half\\b")

    fun normalize(text: String): String {
        var out = WORD.replace(text) { m ->
            val value = when {
                m.value.contains("hundred") -> {
                    val prefix = m.groups["h"]?.value?.let { if (it == "a") 1 else UNITS.getValue(it) } ?: 1
                    val rest = m.groups["ht"]?.value?.let { TENS.getValue(it) + (m.groups["htu"]?.value?.let(UNITS::getValue) ?: 0) }
                        ?: m.groups["hs"]?.value?.let { TEENS[it] ?: UNITS.getValue(it) } ?: 0
                    prefix * 100 + rest
                }
                m.groups["t"] != null -> TENS.getValue(m.groups["t"]!!.value) + (m.groups["tu"]?.value?.let(UNITS::getValue) ?: 0)
                m.groups["teen"] != null -> TEENS.getValue(m.groups["teen"]!!.value)
                else -> UNITS.getValue(m.groups["u"]!!.value)
            }
            value.toString()
        }
        out = POINT.replace(out) { m -> "${m.groupValues[1]}.${m.groupValues[2]}" }
        out = AND_A_HALF.replace(out) { m ->
            if (m.groupValues[2].isEmpty()) "${m.groupValues[1]}.5" else m.value // "2.5 and a half" is left alone
        }
        return out
    }
}
