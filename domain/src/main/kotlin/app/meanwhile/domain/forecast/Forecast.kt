package app.meanwhile.domain.forecast

import app.meanwhile.domain.iob.Iob
import app.meanwhile.domain.iob.RapidDose
import app.meanwhile.domain.profile.Profile
import kotlinx.serialization.Serializable

/** One CGM value for the forecast (epoch ms, mg/dL). */
data class GlucosePoint(val atEpochMillis: Long, val mgDl: Double)

/** Carbs from a logged meal, eaten at [atEpochMillis]. */
data class CarbEntry(val carbsG: Double, val atEpochMillis: Long)

/**
 * What the CGM and the records say about the next few hours (2.0), as two terms of the dose math:
 * - [cobUnits]: carbs from logged meals still to absorb ÷ ICR — insulin already on board is meant for them.
 * - [unexplainedUnits]: the CGM's slope minus what logged insulin and carbs should have done, carried
 *   forward and fading out ([unexplainedMgDl]) ÷ ISF. Positive = rising more than the records explain
 *   (food that wasn't logged); negative = falling more (exercise, a stronger day).
 * Every number that went into it is kept so the breakdown can show it.
 */
@Serializable
data class Forecast(
    val carbsOnBoardG: Double = 0.0,
    val cobUnits: Double = 0.0,
    /** Least-squares CGM slope over the lookback (mg/dL/min); null when there weren't enough readings. */
    val observedRate: Double? = null,
    /** What logged insulin should have done over the lookback (mg/dL/min, ≤ 0). */
    val insulinRate: Double? = null,
    /** What logged carbs should have done over the lookback (mg/dL/min, ≥ 0). */
    val carbRate: Double? = null,
    /** observed − insulin − carbs (mg/dL/min). */
    val unexplainedRate: Double? = null,
    /**
     * Grams of logged carbs that absorbed faster than the linear model: a rise beyond it while carbs are
     * still on board is those carbs arriving early (taken off [carbsOnBoardG]), not something new.
     */
    val absorbedEarlyG: Double = 0.0,
    val unexplainedMgDl: Double = 0.0,
    val unexplainedUnits: Double = 0.0,
    val readingsUsed: Int = 0,
    /** Why there is no unexplained term, in words (null when there is one). */
    val note: String? = null,
) {
    companion object {
        val NONE = Forecast(note = "forecast off")
    }
}

object Forecaster {

    /** Fraction of a meal's carbs absorbed [minutes] after it was eaten (linear after the delay). */
    fun carbsAbsorbed(minutes: Double, delayMin: Double, absorptionMin: Double): Double =
        ((minutes - delayMin) / absorptionMin).coerceIn(0.0, 1.0)

    fun compute(
        nowEpochMillis: Long,
        readings: List<GlucosePoint>,
        doses: List<RapidDose>,
        meals: List<CarbEntry>,
        profile: Profile,
    ): Forecast {
        val f = profile.forecast
        if (!f.enabled) return Forecast.NONE
        val d = profile.dose
        val curve = profile.iob
        fun minutesSince(at: Long, t: Long) = (t - at) / 60_000.0

        val cobG = meals.sumOf { m ->
            m.carbsG * (1 - carbsAbsorbed(minutesSince(m.atEpochMillis, nowEpochMillis), f.carbDelayMin, f.carbAbsorptionMin))
        }
        val cobUnits = if (d.icr > 0) cobG / d.icr else 0.0

        val from = nowEpochMillis - (f.lookbackMin * 60_000).toLong()
        val window = readings.filter { it.atEpochMillis in from..nowEpochMillis }.sortedBy { it.atEpochMillis }
        val newest = readings.filter { it.atEpochMillis <= nowEpochMillis }.maxByOrNull { it.atEpochMillis }
        val base = Forecast(carbsOnBoardG = cobG, cobUnits = cobUnits, readingsUsed = window.size)
        when {
            newest == null -> return base.copy(note = "no CGM readings")
            minutesSince(newest.atEpochMillis, nowEpochMillis) > f.maxReadingAgeMin ->
                return base.copy(note = "newest reading is ${minutesSince(newest.atEpochMillis, nowEpochMillis).toInt()} min old")
            window.size < f.minReadings -> return base.copy(note = "only ${window.size} readings in the last ${f.lookbackMin.toInt()} min")
            minutesSince(window.first().atEpochMillis, window.last().atEpochMillis) < f.lookbackMin / 2 ->
                return base.copy(note = "readings span less than half of the last ${f.lookbackMin.toInt()} min")
        }
        val t0 = window.first().atEpochMillis
        val t1 = window.last().atEpochMillis
        val observed = slope(window) ?: return base.copy(note = "the CGM slope can't be computed")
        val spanMin = minutesSince(t0, t1)

        // Units absorbed between t0 and t1 from doses given by then; each lowers BG by ISF.
        val absorbedUnits = doses.filter { it.atEpochMillis <= t1 }.sumOf { dose ->
            dose.units * (
                Iob.fractionSinceDose(minutesSince(dose.atEpochMillis, t0), curve) -
                    Iob.fractionSinceDose(minutesSince(dose.atEpochMillis, t1), curve)
                )
        }
        val insulinRate = -absorbedUnits * d.isf / spanMin
        // Grams absorbed between t0 and t1; each raises BG by ISF / ICR.
        val absorbedG = meals.filter { it.atEpochMillis <= t1 }.sumOf { m ->
            m.carbsG * (
                carbsAbsorbed(minutesSince(m.atEpochMillis, t1), f.carbDelayMin, f.carbAbsorptionMin) -
                    carbsAbsorbed(minutesSince(m.atEpochMillis, t0), f.carbDelayMin, f.carbAbsorptionMin)
                )
        }
        val carbRate = if (d.icr > 0) absorbedG * (d.isf / d.icr) / spanMin else 0.0
        var unexplained = observed - insulinRate - carbRate
        // Dynamic absorption (as in Loop): a faster rise while logged carbs are on board uses them up first.
        var early = 0.0
        if (unexplained > 0 && cobG > 0 && d.isf > 0) {
            val riseAsG = unexplained * spanMin * d.icr / d.isf
            early = minOf(riseAsG, cobG)
            unexplained -= early * (d.isf / d.icr) / spanMin
        }
        val cobLeft = cobG - early
        // Carried on and fading linearly to zero over carryMin: the area under that line.
        val mgDl = unexplained * f.carryMin / 2
        return base.copy(
            carbsOnBoardG = cobLeft, cobUnits = if (d.icr > 0) cobLeft / d.icr else 0.0, absorbedEarlyG = early,
            observedRate = observed, insulinRate = insulinRate, carbRate = carbRate, unexplainedRate = unexplained,
            unexplainedMgDl = mgDl, unexplainedUnits = if (d.isf > 0) mgDl / d.isf else 0.0,
        )
    }

    private fun slope(points: List<GlucosePoint>): Double? {
        val t0 = points.first().atEpochMillis
        val xs = points.map { (it.atEpochMillis - t0) / 60_000.0 }
        val ys = points.map { it.mgDl }
        val mx = xs.average()
        val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        if (sxx == 0.0) return null
        return xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx
    }
}
