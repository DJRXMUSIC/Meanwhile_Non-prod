package app.meanwhile.domain.router

import app.meanwhile.domain.profile.CoffeeSettings
import java.util.Locale

/** What the milk in some coffees adds, as food the dose math covers (2.0). */
data class CoffeeFood(val description: String, val carbsG: Double, val fatG: Double, val proteinG: Double, val milkCups: Double)

object CoffeeMilk {
    private val BLACK = Regex("\\b(black|no milk|without milk|no cream)\\b")

    /** Anything else in the cup isn't plain whole milk: estimated like any food described in words. */
    private val OTHER = Regex(
        "\\b(oat|almond|soy|coconut|skim|2%|two percent|low[- ]fat|cream|creamer|half and half|half-and-half|sugar|syrup|honey|" +
            "caramel|vanilla|hazelnut|pumpkin|whipped|lattes?|cappuccinos?|mochas?|frapp\\w*|macchiatos?|condensed)\\b",
    )

    /** "1/4 cup milk", "half a cup of whole milk", "0.5 cups milk each". */
    private val STATED = Regex("(\\d+\\s*/\\s*\\d+|\\d*\\.?\\d+|half)\\s*(?:a\\s+)?cups?\\s+(?:of\\s+)?(?:whole\\s+)?milk(\\s+(?:in\\s+)?each)?")

    /**
     * The milk for "had a coffee" / "2 coffees" / "coffee with 1/4 cup milk": the profile's amount per
     * cup unless the words say black or give an amount (total, or "each"). Null when the words name
     * something else — oat milk, cream, sugar, a latte … — which is estimated like described food.
     */
    fun food(text: String, cups: Double?, s: CoffeeSettings): CoffeeFood? {
        val t = text.lowercase(Locale.US)
        val n = cups?.takeIf { it > 0 } ?: 1.0
        val label = if (n == 1.0) "coffee" else "${fmt(n)} coffees"
        if (BLACK.containsMatchIn(t)) return CoffeeFood("$label, black", 0.0, 0.0, 0.0, 0.0)
        if (OTHER.containsMatchIn(t)) return null
        val stated = STATED.find(t)
        val milk = if (stated != null) amount(stated.groupValues[1]) * (if (stated.groupValues[2].isNotBlank()) n else 1.0) else s.milkCupsPerCoffee * n
        val what = if (stated == null) "${cupsText(s.milkCupsPerCoffee)} cup whole milk each" else "${cupsText(milk)} cup whole milk"
        return CoffeeFood("$label ($what)", milk * s.milkCarbsPerCup, milk * s.milkFatPerCup, milk * s.milkProteinPerCup, milk)
    }

    private val CUP_WORDS = setOf(
        "milk", "whole", "black", "no", "without", "with", "cream", "creamer", "oat", "almond", "soy", "coconut", "skim",
        "sugar", "splash", "dash", "little", "bit", "of", "a", "an", "and", "cup", "cups", "each", "in", "half", "some",
        "my", "usual", "the", "hot", "iced", "large", "small", "mug", "regular", "syrup", "honey", "sweetener", "stevia",
    )

    /** True when [words] only describe what's in the cup ("milk", "black", "with a splash of milk"). */
    fun aboutTheCup(words: String): Boolean {
        val tokens = words.lowercase(Locale.US).split(Regex("[^a-z0-9/%.]+")).filter { it.isNotBlank() }
        return tokens.all { it in CUP_WORDS || it.matches(Regex("[0-9/.%]+")) }
    }

    private fun amount(raw: String): Double {
        if (raw == "half") return 0.5
        val parts = raw.replace(" ", "").split('/')
        return if (parts.size == 2) {
            (parts[0].toDoubleOrNull() ?: 0.0) / (parts[1].toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0)
        } else {
            raw.toDoubleOrNull() ?: 0.0
        }
    }

    private fun cupsText(c: Double): String = when (c) {
        0.125 -> "1/8"
        0.25 -> "1/4"
        0.5 -> "1/2"
        0.75 -> "3/4"
        else -> fmt(c)
    }

    private fun fmt(x: Double) =
        if (x == Math.floor(x)) x.toLong().toString() else String.format(Locale.US, "%.2f", x).trimEnd('0').trimEnd('.')
}
