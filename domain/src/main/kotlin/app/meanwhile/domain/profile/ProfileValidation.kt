package app.meanwhile.domain.profile

/**
 * Values the math can't work with (division by zero, NaN, an hour that doesn't exist). These are
 * not caution limits — any positive ICR is fine — they only stop a typo or a bad AI proposal from
 * turning every dose into 0 u or garbage without anyone noticing.
 */
object ProfileValidation {

    fun problems(p: Profile): List<String> = buildList {
        fun positive(path: String, v: Double) { if (!v.isFinite() || v <= 0) add("$path must be a number above 0 (is ${show(v)})") }
        fun finite(path: String, v: Double?) { if (v != null && !v.isFinite()) add("$path must be a number (is ${show(v)})") }
        fun hour(path: String, v: Number?) {
            if (v != null && (v.toDouble() !in 0.0..23.0 || v.toDouble() != Math.floor(v.toDouble()))) add("$path must be a whole hour 0–23 (is $v)")
        }

        positive("dose.icr", p.dose.icr)
        positive("dose.isf", p.dose.isf)
        finite("dose.target", p.dose.target)
        finite("dose.combinedCap", p.dose.combinedCap)
        positive("dose.unitIncrement", p.dose.unitIncrement)

        finite("iob.delayMin", p.iob.delayMin)
        if (p.iob.delayMin < 0) add("iob.delayMin can't be negative (is ${show(p.iob.delayMin)})")
        positive("iob.durationMin", p.iob.durationMin)
        positive("iob.peakMin", p.iob.peakMin)
        // The exponential activity curve (spec §6) only exists while peak < duration / 2.
        if (p.iob.peakMin.isFinite() && p.iob.durationMin.isFinite() && p.iob.peakMin > 0 && p.iob.peakMin * 2 >= p.iob.durationMin) {
            add("iob.peakMin (${show(p.iob.peakMin)}) must be less than half of iob.durationMin (${show(p.iob.durationMin)})")
        }

        finite("meal.lowCarbThresholdG", p.meal.lowCarbThresholdG)
        finite("meal.kFatPerG", p.meal.kFatPerG)
        finite("meal.kProteinPerG", p.meal.kProteinPerG)
        positive("meal.fatGPerUnit", p.meal.fatGPerUnit)
        positive("meal.proteinGPerUnit", p.meal.proteinGPerUnit)

        finite("split.minFatG", p.split.minFatG)
        finite("split.minProteinG", p.split.minProteinG)
        finite("split.minCarbsG", p.split.minCarbsG)
        if (!(p.split.firstFraction in 0.0..1.0)) add("split.firstFraction must be between 0 and 1 (is ${show(p.split.firstFraction)})")
        if (p.split.secondAfterMin < 0) add("split.secondAfterMin can't be negative (is ${p.split.secondAfterMin})")

        finite("leadTime.eatNowBelowBg", p.leadTime.eatNowBelowBg)
        finite("leadTime.eatNowTrendAtOrBelow", p.leadTime.eatNowTrendAtOrBelow)
        finite("leadTime.highBgStart", p.leadTime.highBgStart)
        positive("leadTime.highBgStepMgDl", p.leadTime.highBgStepMgDl)
        finite("leadTime.highFatG", p.leadTime.highFatG)
        if (p.leadTime.minMin > p.leadTime.maxMin) add("leadTime.minMin (${p.leadTime.minMin}) is above leadTime.maxMin (${p.leadTime.maxMin})")

        hour("resetHour", p.resetHour)

        val ids = p.factors.map { it.id }
        ids.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.forEach { add("factor id $it is used more than once") }
        for (f in p.factors) {
            val base = "factors.${f.id}"
            finite("$base.minWeight", f.minWeight)
            finite("$base.maxWeight", f.maxWeight)
            finite("$base.defaultWeight", f.defaultWeight)
            finite("$base.unitsPerEvent", f.unitsPerEvent)
            f.presets.forEach { (k, v) -> finite("$base.presets.$k", v) }
            f.params.forEach { (k, v) ->
                finite("$base.params.$k", v)
                if (k in HOUR_PARAMS) hour("$base.params.$k", v)
            }
            if ((f.window.minutes ?: 0) < 0) add("$base.window.minutes can't be negative")
            f.decay?.steps?.forEach { s ->
                finite("$base.decay weight", s.weight)
                if (s.fromMinutes < 0) add("$base.decay fromMinutes can't be negative")
            }
        }
        for (a in p.active) {
            finite("active.${a.factorId}.weight", a.weight)
            if ((a.windowMinutes ?: 0) < 0) add("active.${a.factorId}.windowMinutes can't be negative")
        }
    }

    /** Clock hours used by computed factors (F11's overnight window). */
    private val HOUR_PARAMS = setOf("startHour", "endHour")

    private fun show(v: Double) = if (v == Math.floor(v) && v.isFinite()) v.toLong().toString() else v.toString()
}
