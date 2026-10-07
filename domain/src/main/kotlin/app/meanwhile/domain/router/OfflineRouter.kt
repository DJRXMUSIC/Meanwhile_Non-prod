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
        // Spoken numbers become digits first, so voice and typed input parse identically.
        val lower = SpokenNumbers.normalize(text.lowercase())

        feedback(text, lower)?.let { return RouteResult(listOf(it), ROUTER) }
        // "never mind, I only took 5" / "I didn't take any": a correction is the whole message.
        correction(lower)?.let { return RouteResult(listOf(it), ROUTER) }

        val consumed = mutableListOf<IntRange>()
        val intents = mutableListOf<RoutedIntent>()

        bg(lower, consumed)?.let { intents += it }
        val dose = dose(lower, consumed)
        if (dose != null) intents += dose else followed(lower, consumed)?.let { intents += it }
        val macros = macros(lower, consumed)
        intents += factorIntents(lower, consumed)

        val liquid = LIQUID_WORDS.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(lower) }
        if (macros != null) {
            intents += macros.copy(liquidOrSugary = liquid, description = leftover(lower, consumed))
        } else {
            val rest = leftover(lower, consumed)
            val mealCue = MEAL_CUES.any { Regex("\\b$it\\b").containsMatchIn(lower) }
            val asking = CHECK.containsMatchIn(lower)
            // In a question ("should I take insulin for a sandwich?") only the food is the meal.
            val food = if (asking) rest.split(' ').filter { it.isNotBlank() && it !in QUESTION_WORDS }.joinToString(" ") else rest
            when {
                // "what should I do?", "correction", "BG 140 — anything?": a Next Best Action with no food.
                asking && food.isBlank() -> intents += MealIntent(textSpan = text, description = CHECK_DESCRIPTION, confidence = 0.9)
                food.isNotBlank() && (mealCue || intents.none { it is DoseIntent || it is FollowedIntent || it is BgIntent }) ->
                    intents += MealIntent(
                        textSpan = food, description = food, liquidOrSugary = liquid,
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
            Regex("\\btook\\s+the\\s+($NUM)\\b(?!\\s*(?:g\\b|grams?|carbs?|min|minutes?|hours?|hrs?))"),
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

    /**
     * A correction of a dose logged a moment ago (1.4). Only clear corrections count: a cancel ("I
     * didn't take any", "scratch that"), or a trigger word with the new number ("never mind, only 5",
     * "make that 4", "no, 5"). Food numbers ("actually it was 60 carbs") are a new meal, not this.
     */
    fun correction(lowerNormalized: String): DoseCorrectionIntent? {
        val lower = lowerNormalized.trim()
        if (QUESTION.containsMatchIn(lower) || MACRO_WORD.containsMatchIn(lower)) return null
        val insulin = when {
            LONG_WORD.containsMatchIn(lower) -> "long"
            RAPID_WORD.containsMatchIn(lower) -> "rapid"
            else -> null
        }
        val consumed = mutableListOf<IntRange>()
        val ago = minutesAgo(lower, consumed)
        val negated = NEGATED_DOSE.find(lower)
        // Numbers that are units: not "not 6", not "didn't take 6", not minutes or food counts.
        val numbers = UNIT_NUMBER.findAll(lower)
            .filter { m -> consumed.none { r -> m.range.first in r } }
            .filter { m -> negated == null || m.range.first !in negated.range.last..(negated.range.last + 3) }
            .toList()
        if (negated != null) {
            val after = numbers.firstOrNull { it.range.first > negated.range.last }
            return DoseCorrectionIntent(lower, units = after?.let { parseNumber(it.groupValues[1]) } ?: 0.0, insulin = insulin, minutesAgo = ago)
        }
        val trigger = CORRECTION_TRIGGER.find(lower) ?: return null
        val number = numbers.firstOrNull { it.range.first >= trigger.range.first } ?: numbers.firstOrNull()
        if (number == null) {
            return when {
                CANCEL.containsMatchIn(lower) -> DoseCorrectionIntent(lower, units = 0.0, insulin = insulin)
                ago != null && DOSE_WORD.containsMatchIn(lower) -> DoseCorrectionIntent(lower, units = null, insulin = insulin, minutesAgo = ago)
                else -> null
            }
        }
        val words = lower.split(Regex("[^a-z0-9.']+")).filter { it.isNotBlank() }
        val clear = DOSE_WORD.containsMatchIn(lower) || STRONG_TRIGGER.containsMatchIn(lower) ||
            (words.size <= 3 && words.last() == number.groupValues[1])
        if (!clear) return null
        return DoseCorrectionIntent(lower, units = parseNumber(number.groupValues[1]), insulin = insulin, minutesAgo = ago)
    }

    /**
     * The AI decided [span] corrects a dose: read it the same way, without needing a trigger word —
     * the first units number, else a cancel.
     */
    fun correctionFrom(span: String): DoseCorrectionIntent {
        val lower = SpokenNumbers.normalize(span.lowercase()).trim()
        correction(lower)?.let { return it }
        val consumed = mutableListOf<IntRange>()
        val ago = minutesAgo(lower, consumed)
        val number = UNIT_NUMBER.findAll(lower).firstOrNull { m -> consumed.none { r -> m.range.first in r } }
        val insulin = when {
            LONG_WORD.containsMatchIn(lower) -> "long"
            RAPID_WORD.containsMatchIn(lower) -> "rapid"
            else -> null
        }
        return DoseCorrectionIntent(
            span, units = number?.let { parseNumber(it.groupValues[1]) } ?: if (ago != null) null else 0.0,
            insulin = insulin, minutesAgo = ago,
        )
    }

    /** "took it", "did it", "ate it", or just "done": Danny did what the last suggestion said. */
    private fun followed(lower: String, consumed: MutableList<IntRange>): FollowedIntent? {
        val m = FOLLOWED.find(lower) ?: FOLLOWED_ALONE.find(lower) ?: return null
        consumed += m.range
        return FollowedIntent(m.value.trim(), minutesAgo(lower, consumed))
    }

    /** "BG 140", "blood sugar is 85", "I'm at 210": a BG for this message's Next Best Action. */
    private fun bg(lower: String, consumed: MutableList<IntRange>): BgIntent? {
        val m = BG.find(lower) ?: return null
        val value = parseNumber(m.groupValues[1].ifEmpty { m.groupValues[2] }) ?: return null
        if (value < 20 || value > 600) return null
        consumed += m.range
        return BgIntent(m.value.trim(), value)
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
        private val ORDER = listOf("feedback", "dose_correction", "dose_given", "followed", "bg_reading", "factor_update", "meal")
        val FEEDBACK_PREFIXES = listOf("feedback", "app note", "idea", "bug")

        /** A Next Best Action request with no food: the processor checks for this description. */
        const val CHECK_DESCRIPTION = "correction"
        private val CHECK = Regex(
            "\\b(what should i (?:do|take|eat)|what do i (?:do|take|need)|should i (?:take|eat|bolus|correct|do|dose)|do i need|" +
                "how much (?:should|do) i|correction|correct|check|nba|dose check|next best action|what now|anything to do)\\b",
        )
        private val QUESTION_WORDS = setOf(
            "what", "should", "how", "much", "need", "do", "does", "take", "eat", "bolus", "correct", "correction", "check",
            "nba", "dose", "next", "best", "action", "now", "anything", "something", "insulin", "i", "me", "to", "for", "it",
            "a", "any", "my", "is", "there", "can", "would", "you", "please", "ok", "okay", "so", "and", "then",
        )
        private val QUESTION = Regex("\\b(should i|what should|how much|do i need|what do i|can i)\\b")
        private val NEGATED_DOSE = Regex(
            "\\b(?:didn'?t|did not|never|haven'?t|have not|forgot to|not)\\s+(?:actually\\s+|really\\s+|end up\\s+|get to\\s+|even\\s+)?" +
                "(?:take|took|taken|taking|inject|injected|bolus|bolused|dose|dosed|give|gave)\\b",
        )
        private val CORRECTION_TRIGGER = Regex(
            "\\b(never ?mind|nvm|actually|sorry|oops|wait|scratch that|cancel|undo|i meant|meant|make (?:that|it)|change (?:that|it)|" +
                "it was|that was|only took|i only|instead)\\b|^(?:no|nope)\\b|\\b\\d+(?:\\.\\d+)?\\s*(?:u|units?)?\\s*,?\\s*not\\s+\\d",
        )
        private val STRONG_TRIGGER = Regex("\\b(never ?mind|nvm|make (?:that|it)|i meant|change (?:that|it)|only took|scratch that|instead)\\b")
        private val CANCEL = Regex(
            "\\b(never ?mind|nvm|scratch that|cancel(?: that| it| the dose)?|undo(?: that| it)?|forget (?:that|it)|ignore (?:that|it)|" +
                "delete (?:that|it)|remove (?:that|it))\\b",
        )
        private val DOSE_WORD = Regex(
            "\\b(took|take|taken|inject(?:ed)?|bolus(?:ed)?|dose[ds]?|shot|units?|insulin|humalog|lyumjev|novolog|fiasp|lantus|" +
                "tresiba|levemir|toujeo|basaglar|semglee|long[- ]?acting|basal)\\b",
        )
        private val MACRO_WORD = Regex("\\b(carbs?|carbohydrates?|fats?|proteins?|grams?)\\b|\\d\\s*g\\b")
        private val LONG_WORD = Regex("\\b(long[- ]?acting|basal|lantus|tresiba|levemir|toujeo|basaglar|semglee)\\b")
        private val RAPID_WORD = Regex("\\b(humalog|lyumjev|novolog|fiasp|admelog|apidra|rapid|bolus)\\b")
        private val UNIT_NUMBER = Regex(
            "(?<!not )(?<![\\d.])(\\d+(?:\\.\\d+)?)(?![\\d.])(?!\\s*(?:min|mins|minutes?|hours?|hrs?|h\\b|g\\b|grams?|carbs?|mg|%|am\\b|pm\\b|" +
                "o'?clock|beers?|coffees?|drinks?|cups?|glass|miles?|km))",
        )
        private val FOLLOWED = Regex(
            "\\b(?:just\\s+)?(?:took|did|injected|bolused|dosed|gave|ate|had|followed|finished|logged)\\s+" +
                "(?:it|that|them|this|those|the\\s+(?:dose|insulin|shot|carbs|snack|correction|bolus|units?|juice|glucose))\\b",
        )
        private val FOLLOWED_ALONE = Regex("^(?:ok(?:ay)?|yes|yep|yeah|alright|sure|cool)?[\\s,.!]*(?:done|all done|did it|finished)[\\s.!]*$")
        private val BG = Regex(
            "\\b(?:bg|blood sugar|blood glucose|glucose|sugar|fingerstick|finger stick|meter|reading|cgm)(?:'s)?\\s*" +
                "(?:is|was|of|at|reads|says|shows|:|=)?\\s*(?:at\\s+)?(\\d{2,3})\\b(?!\\s*(?:u\\b|units?|g\\b|grams?|carbs?|min|minutes?))" +
                "|\\bi'?m\\s+(?:at|sitting at|reading)\\s+(\\d{2,3})\\b",
        )
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
            "actually", "sorry", "oops", "wait", "never", "mind", "nevermind", "ok", "okay", "yes", "yeah", "yep", "hey",
        )
    }
}
