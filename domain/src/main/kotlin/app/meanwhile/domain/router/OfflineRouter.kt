package app.meanwhile.domain.router

import app.meanwhile.domain.profile.FactorDefinition
import app.meanwhile.domain.profile.FactorKind

/**
 * Deterministic keyword/regex classifier used when the AI router is unavailable (spec §9.2).
 * Factor keywords come from the profile's factor definitions, so AI-added factors are routable too.
 */
class OfflineRouter(private val factors: List<FactorDefinition>) {

    fun route(input: String): RouteResult {
        val text = input.trim()
        val lower = text.lowercase()

        feedback(text, lower)?.let { return RouteResult(listOf(it), ROUTER) }

        val consumed = mutableListOf<IntRange>()
        val intents = mutableListOf<RoutedIntent>()

        dose(lower, consumed)?.let { intents += it }
        val macros = macros(lower, consumed)
        intents += factorIntents(lower, consumed)

        val liquid = LIQUID_WORDS.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(lower) }
        if (macros != null) {
            intents += macros.copy(liquidOrSugary = liquid, description = leftover(lower, consumed))
        } else {
            val rest = leftover(lower, consumed)
            val mealCue = MEAL_CUES.any { Regex("\\b$it\\b").containsMatchIn(lower) }
            if (rest.isNotBlank() && (mealCue || intents.none { it is DoseIntent })) {
                intents += MealIntent(
                    textSpan = rest, description = rest, liquidOrSugary = liquid,
                    confidence = if (mealCue) 0.8 else if (intents.isEmpty()) 0.5 else 0.6,
                )
            }
        }
        if (intents.isEmpty()) {
            intents += MealIntent(textSpan = text, description = text, confidence = 0.2)
        }
        // Factor updates first, then the meal (spec §9.2: Update Profile before Next Best Action).
        return RouteResult(intents.sortedBy { ORDER.indexOf(it.type) }, ROUTER)
    }

    private fun feedback(text: String, lower: String): FeedbackIntent? {
        val prefix = FEEDBACK_PREFIXES.firstOrNull { lower.startsWith(it) } ?: return null
        val body = text.substring(prefix.length).trimStart(' ', ':', '-', ',', '.').trim()
        return FeedbackIntent(textSpan = text, text = body.ifEmpty { text })
    }

    private fun dose(lower: String, consumed: MutableList<IntRange>): DoseIntent? {
        val longWord = Regex("\\b(long[- ]?acting|basal|lantus|tresiba|levemir|toujeo|basaglar|semglee)\\b").find(lower)
        val patterns = listOf(
            // "took my long-acting 22", "lantus 22"
            Regex("\\b(?:(?:took|take|gave|injected|did)\\s+)?(?:my\\s+)?(?:long[- ]?acting|basal|lantus|tresiba|levemir|toujeo|basaglar|semglee)(?:\\s+(?:dose|shot|insulin))?\\s*(?:of\\s+)?($NUM)\\s*(?:u|units?)?\\b"),
            // "took 22 units of lantus", "took 7 units", "bolused 5u", "humalog 6"
            Regex("\\b(?:took|take|taken|gave|give|injected|inject|bolused|bolus|dosed|did|shot)\\b[^0-9]{0,25}?($NUM)\\s*(?:u|units?)\\b(?:\\s*(?:of\\s+)?(?:my\\s+)?(?:humalog|rapid|insulin|long[- ]?acting|basal|lantus|tresiba|levemir|toujeo|basaglar|semglee))?"),
            Regex("\\b(?:humalog|lyumjev|novolog|fiasp|admelog|apidra)\\s*($NUM)\\s*(?:u|units?)?\\b"),
            Regex("\\b(?:took|injected|bolused|dosed)\\s+($NUM)\\s*$"),
            Regex("\\b($NUM)\\s*(?:u|units?)\\b"),
        )
        for (re in patterns) {
            val m = re.find(lower) ?: continue
            val units = parseNumber(m.groupValues[1]) ?: continue
            consumed += m.range
            longWord?.let { consumed += it.range }
            val ago = minutesAgo(lower, consumed)
            val isLong = longWord != null && (m.range.first <= longWord.range.last + 30)
            return DoseIntent(textSpan = m.value.trim(), units = units, insulin = if (isLong) "long" else "rapid", minutesAgo = ago)
        }
        return null
    }

    private fun macros(lower: String, consumed: MutableList<IntRange>): MealIntent? {
        fun find(names: String, short: String): Double? {
            val after = Regex("($NUM)\\s*(?:g|gm|grams?)?\\s*(?:of\\s+)?(?:$names)\\b").find(lower)
            val before = Regex("\\b(?:$names)\\s*[:=]?\\s*($NUM)\\s*(?:g|gm|grams?)?\\b").find(lower)
            val abbrev = Regex("\\b($NUM)\\s*$short\\b").find(lower)
            val m = listOfNotNull(after, before, abbrev).minByOrNull { it.range.first } ?: return null
            consumed += m.range
            return parseNumber(m.groupValues[1])
        }
        val carbs = find("carbs?|carbohydrates?|carb", "c")
        val fat = find("fats?", "f")
        val protein = find("proteins?|pro", "p")
        if (carbs == null && fat == null && protein == null) return null
        val span = consumed.let { if (it.isEmpty()) lower else lower.substring(it.minOf { r -> r.first }, it.maxOf { r -> r.last } + 1) }
        return MealIntent(textSpan = span, carbsG = carbs ?: 0.0, fatG = fat ?: 0.0, proteinG = protein ?: 0.0)
    }

    private fun factorIntents(lower: String, consumed: MutableList<IntRange>): List<FactorIntent> {
        val out = mutableListOf<FactorIntent>()
        for (def in factors) {
            if (!def.enabled || def.kind == FactorKind.BASELINE || def.kind == FactorKind.MEAL_COMPUTED || def.kind == FactorKind.AUTO_MULTIPLIER) continue
            val hit = def.keywords.sortedByDescending { it.length }.firstNotNullOfOrNull { kw ->
                Regex("\\b${Regex.escape(kw)}(?:s|es)?\\b").findAll(lower).firstOrNull { m ->
                    consumed.none { r -> m.range.first <= r.last && r.first <= m.range.last }
                }
            } ?: continue
            consumed += hit.range
            out += when (def.id) {
                "F4" -> FactorIntent(hit.value, def.id, amount = countBefore(lower, hit.range, consumed) ?: 1.0)
                "F5" -> {
                    val drink = Regex("\\b(beer|wine|cocktail|margarita|vodka|whiske?y|tequila|shot|seltzer|gin|rum|ipa)").find(hit.value)?.value ?: hit.value
                    FactorIntent(hit.value, def.id, amount = countBefore(lower, hit.range, consumed) ?: 1.0, details = mapOf("drink" to drink))
                }
                "F6" -> if (hit.value == "water") FactorIntent(hit.value, def.id, action = "deactivate", preset = "hydrated")
                else FactorIntent(hit.value, def.id, preset = "dehydrated")
                "F7" -> exercise(lower, hit, consumed, def)
                "F8" -> FactorIntent(hit.value, def.id, preset = sleepQuality(lower))
                "F9" -> {
                    val ill = Regex("\\b(sick|ill|fever|flu|covid|cold)\\b").containsMatchIn(lower)
                    val over = Regex("\\b(better|recovered|no longer|not .{0,10}anymore|over it)\\b").containsMatchIn(lower)
                    FactorIntent(hit.value, def.id, action = if (over) "deactivate" else "activate", preset = if (ill) "illness" else "stress")
                }
                else -> FactorIntent(hit.value, def.id, amount = countBefore(lower, hit.range, consumed))
            }
        }
        return out
    }

    private fun exercise(lower: String, hit: MatchResult, consumed: MutableList<IntRange>, def: FactorDefinition): FactorIntent {
        val details = linkedMapOf<String, String>()
        val word = hit.value
        details["type"] = when {
            Regex("ran|run|jog").containsMatchIn(word) -> "run"
            Regex("lift|weights|gym").containsMatchIn(word) -> "strength"
            Regex("walk|hike").containsMatchIn(word) -> "walk"
            Regex("bike|cycl|spin|peloton").containsMatchIn(word) -> "cycling"
            Regex("swim|swam").containsMatchIn(word) -> "swim"
            else -> word
        }
        Regex("($NUM)\\s*(miles?|mi|km|kilometers?|k)\\b").find(lower)?.let {
            consumed += it.range
            details["distance"] = "${it.groupValues[1]} ${it.groupValues[2]}"
        }
        Regex("($NUM)\\s*(hours?|hrs?|h|minutes?|mins?|m)\\b(?!\\s*ago)").find(lower)?.let {
            consumed += it.range
            val n = parseNumber(it.groupValues[1]) ?: 0.0
            val minutes = if (it.groupValues[2].startsWith("h")) n * 60 else n
            details["duration_min"] = minutes.toInt().toString()
        }
        Regex("\\b(easy|light|moderate|hard|intense|heavy|tempo|sprints?)\\b").find(lower)?.let {
            consumed += it.range
            details["intensity"] = it.value
        }
        return FactorIntent(word, def.id, minutesAgo = minutesAgo(lower, consumed), details = details)
    }

    private fun sleepQuality(lower: String): String = when {
        Regex("\\b(didn'?t|did not|not|never|couldn'?t|could not|hardly)\\b[^.]{0,12}\\b(well|good|great|much)\\b").containsMatchIn(lower) -> "poor"
        Regex("\\b(bad|badly|poor|poorly|terrible|terribly|awful|barely|little|no) ?(sleep|slept)?").containsMatchIn(lower) &&
            Regex("slept|sleep").containsMatchIn(lower) && !Regex("\\b(well|great|good)\\b").containsMatchIn(lower) -> "poor"
        Regex("\\b(ok|okay|fine|alright|so-so|meh)\\b").containsMatchIn(lower) -> "ok"
        Regex("\\b(well|great|good|amazing)\\b").containsMatchIn(lower) -> "good"
        else -> "ok"
    }

    /** "2 coffees", "two beers", "a glass of wine", "couple of beers". */
    private fun countBefore(lower: String, hit: IntRange, consumed: MutableList<IntRange>): Double? {
        val before = lower.substring(0, hit.first)
        val m = Regex("(${NUM}|${NUMBER_WORDS.keys.joinToString("|")})\\s+(?:(?:cups?|glass(?:es)?|cans?|pints?|bottles?|mugs?|shots?)\\s+(?:of\\s+)?)?(?:(?:large|small|big|iced|hot|double|light|red|white)\\s+)*$").find(before)
            ?: return null
        consumed += m.range
        return parseNumber(m.groupValues[1])
    }

    private fun minutesAgo(lower: String, consumed: MutableList<IntRange>): Int? {
        val m = Regex("($NUM|an?|half an?)\\s*(hours?|hrs?|h|minutes?|mins?|m)\\s+ago").find(lower) ?: return null
        consumed += m.range
        val n = when (m.groupValues[1]) {
            "a", "an" -> 1.0
            "half a", "half an" -> 0.5
            else -> parseNumber(m.groupValues[1]) ?: return null
        }
        return (if (m.groupValues[2].startsWith("h")) n * 60 else n).toInt()
    }

    private fun leftover(lower: String, consumed: List<IntRange>): String {
        val chars = lower.toCharArray()
        consumed.forEach { r -> for (i in r) if (i in chars.indices) chars[i] = ' ' }
        return String(chars).split(Regex("[^a-z0-9'%-]+"))
            .filter { it.isNotBlank() && it !in STOPWORDS && it.toDoubleOrNull() == null }
            .joinToString(" ")
    }

    private fun parseNumber(s: String): Double? = s.toDoubleOrNull() ?: NUMBER_WORDS[s]

    companion object {
        const val ROUTER = "offline"
        private const val NUM = "\\d+(?:\\.\\d+)?"
        private val ORDER = listOf("feedback", "dose_given", "factor_update", "meal")
        val FEEDBACK_PREFIXES = listOf("feedback", "app note", "idea", "bug")
        private val NUMBER_WORDS = mapOf(
            "a" to 1.0, "an" to 1.0, "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0, "five" to 5.0,
            "six" to 6.0, "couple" to 2.0, "a couple" to 2.0, "double" to 2.0,
        )
        private val LIQUID_WORDS = listOf(
            "juice", "soda", "coke", "pepsi", "sprite", "lemonade", "smoothie", "shake", "milkshake", "candy",
            "sugary", "liquid", "gatorade", "slushie", "frappuccino", "sweet tea", "glucose",
        )
        private val MEAL_CUES = listOf("ate", "eat", "eating", "breakfast", "lunch", "dinner", "snack", "meal", "having")
        private val STOPWORDS = setOf(
            "a", "an", "the", "and", "with", "had", "have", "having", "i", "i'm", "im", "just", "some", "my", "of", "for",
            "to", "it", "is", "was", "am", "be", "been", "feeling", "feel", "felt", "very", "really", "so", "too", "today",
            "tonight", "this", "morning", "afternoon", "evening", "now", "got", "get", "bit", "little", "pretty", "kinda",
            "quite", "went", "go", "did", "do", "at", "on", "in", "after", "before", "also", "plus", "then", "about",
            "around", "like", "ago", "minutes", "minute", "mins", "min", "hours", "hour", "g", "grams", "gram", "cup", "cups",
            "glass", "glasses", "can", "pint", "bottle", "mug", "of", "ate", "eat", "eating", "breakfast", "lunch",
            "dinner", "snack", "meal", "large", "small", "big", "iced", "hot", "double", "u", "units", "unit", "my",
            "drank", "drink", "drinking", "for", "out", "up", "workout", "session", "miles", "mile", "km", "slept",
            "night", "last", "well", "badly", "poorly", "bad", "poor", "good", "great", "ok", "okay", "fine", "yes", "no",
            "finished", "finish", "done", "ended", "end", "started", "start", "didn't", "didnt", "don't", "dont", "not",
            "back", "home", "work", "came", "kind", "sort", "lot", "lots", "much", "many", "maybe", "probably", "think",
            "guess", "since", "while", "during", "yesterday", "already", "still", "again", "first", "second", "few",
            "sleep", "sleeping", "woke", "slow", "fast", "easy", "hard", "quick", "long", "short", "from", "with", "by",
        )
    }
}
