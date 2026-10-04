package app.meanwhile.domain.profile

/** Factor table, spec §7.1 (starting values). */
object DefaultFactors {
    val carbs = FactorDefinition(
        id = "F1", name = "Carbs", kind = FactorKind.BASELINE,
        window = WindowRule(WindowType.PER_MEAL), input = "meal",
        description = "Baseline dose via ICR; also drives lead time.",
    )
    val fat = FactorDefinition(
        id = "F2", name = "Fat", kind = FactorKind.MEAL_COMPUTED, minWeight = 1.0,
        window = WindowRule(WindowType.PER_MEAL), input = "meal",
        description = "Carbs ≥ 15 g: weight 1 + K_FAT × fat_g. Low-carb: fat_g / 11 units. Split trigger.",
    )
    val protein = FactorDefinition(
        id = "F3", name = "Protein", kind = FactorKind.MEAL_COMPUTED, minWeight = 1.0,
        window = WindowRule(WindowType.PER_MEAL), input = "meal",
        description = "Carbs ≥ 15 g: weight 1 + K_PROTEIN × protein_g. Low-carb: protein_g / 25 units. Split trigger.",
    )
    val caffeine = FactorDefinition(
        id = "F4", name = "Caffeine", kind = FactorKind.UNITS_PER_EVENT, unitsPerEvent = 1.0,
        window = WindowRule(WindowType.CONSUMED_BY_NEXT_DOSE),
        keywords = listOf("coffee", "coffees", "espresso", "latte", "cappuccino", "americano", "cold brew", "caffeine", "macchiato", "mocha"),
        description = "+1 u per cup, once, at the time of drinking. Coffee alone can trigger a dose.",
    )
    val alcohol = FactorDefinition(
        id = "F5", name = "Alcohol", kind = FactorKind.MULTIPLIER, maxWeight = 1.0, defaultWeight = 0.70,
        window = WindowRule(WindowType.FIXED, minutes = 240, stacks = false, survivesReset = true),
        decay = DecayRule(
            listOf(DecayStep(0, 0.70), DecayStep(60, 0.75), DecayStep(120, 0.80), DecayStep(180, 0.85)),
        ),
        keywords = listOf(
            "beer", "beers", "wine", "glass of wine", "cocktail", "cocktails", "vodka", "whiskey", "whisky",
            "tequila", "margarita", "shot", "shots", "hard seltzer", "seltzers", "gin", "rum", "alcohol", "ipa", "drinks",
        ),
        description = "Weight by hours since the most recent drink; a new drink resets (no stacking).",
    )
    val hydration = FactorDefinition(
        id = "F6", name = "Hydration", kind = FactorKind.MULTIPLIER, minWeight = 1.0, maxWeight = 1.10, defaultWeight = 1.10,
        presets = mapOf("dehydrated" to 1.10, "hydrated" to 1.00),
        window = WindowRule(WindowType.UNTIL_RESET),
        keywords = listOf("dehydrated", "thirsty", "water"),
        description = "Dehydrated raises; logging water returns toward 1.00.",
    )
    val exercise = FactorDefinition(
        id = "F7", name = "Exercise", kind = FactorKind.MULTIPLIER, minWeight = 0.70, maxWeight = 1.10, defaultWeight = 0.85,
        window = WindowRule(WindowType.FIXED, minutes = 1440, survivesReset = true),
        keywords = listOf(
            "ran", "run", "running", "jog", "jogged", "lifted", "lift", "lifting", "weights", "gym", "workout",
            "worked out", "walked", "walk", "hike", "hiked", "bike", "biked", "cycling", "cycled", "swim", "swam",
            "yoga", "tennis", "basketball", "soccer", "pickleball", "exercise", "exercised", "peloton", "spin",
        ),
        description = "Logged when a workout ends, with workout type/duration/intensity. 24 h window.",
    )
    val sleep = FactorDefinition(
        id = "F8", name = "Sleep", kind = FactorKind.MULTIPLIER, minWeight = 1.0, maxWeight = 1.25,
        presets = mapOf("good" to 1.00, "ok" to 1.10, "poor" to 1.25), defaultWeight = 1.10,
        window = WindowRule(WindowType.UNTIL_RESET), input = "morning_report",
        keywords = listOf("slept", "sleep"),
        description = "3-level choice on the morning report, applied immediately.",
    )
    val stress = FactorDefinition(
        id = "F9", name = "Stress & illness", kind = FactorKind.MULTIPLIER, minWeight = 1.0, maxWeight = 1.20,
        presets = mapOf("stress" to 1.10, "illness" to 1.20), defaultWeight = 1.10,
        window = WindowRule(WindowType.FIXED, minutes = 1440, survivesReset = true),
        keywords = listOf("stressed", "stress", "anxious", "sick", "ill", "fever", "flu", "covid", "a cold"),
        description = "Up to 24 h per episode.",
    )
    val recentHypo = FactorDefinition(
        id = "F10", name = "Recent hypoglycemia", kind = FactorKind.AUTO_MULTIPLIER, minWeight = 0.80, maxWeight = 1.00,
        defaultWeight = 0.80,
        window = WindowRule(WindowType.AFTER_LAST_TRIGGER, minutes = 720, survivesReset = true),
        input = "auto", params = mapOf("thresholdMgDl" to 70.0),
        description = "Auto: active for 12 h after the last CGM reading below 70.",
    )
    val overnightHighs = FactorDefinition(
        id = "F11", name = "Overnight highs", kind = FactorKind.AUTO_MULTIPLIER, minWeight = 1.0, maxWeight = 1.25,
        window = WindowRule(WindowType.UNTIL_RESET), input = "auto",
        params = mapOf(
            "startHour" to 22.0, "endHour" to 6.0, "thresholdMgDl" to 180.0, "minHours" to 3.0,
            "perHour" to 0.05, "maxWeight" to 1.25, "maxGapMinutes" to 15.0,
        ),
        description = "Computed at 6 am: hours 10 pm–6 am above 180; ≥ 3 h → min(1 + 0.05 × h, 1.25).",
    )
    val sunburn = FactorDefinition(
        id = "F12", name = "Sunburn", kind = FactorKind.MULTIPLIER, minWeight = 2.0, maxWeight = 2.0, defaultWeight = 2.0,
        window = WindowRule(WindowType.FIXED, minutes = 1440, survivesReset = true),
        keywords = listOf("sunburn", "sunburned", "sunburnt"),
        description = "Rare. 24 h.",
    )

    val all: List<FactorDefinition> = listOf(
        carbs, fat, protein, caffeine, alcohol, hydration, exercise, sleep, stress, recentHypo, overnightHighs, sunburn,
    )
}
