package app.meanwhile.domain.iob

import app.meanwhile.domain.profile.IobCurve
import kotlin.math.exp

/** A logged rapid-acting injection. */
data class RapidDose(val units: Double, val atEpochMillis: Long)

/**
 * Exponential insulin activity model (spec §6; same family as Loop/OpenAPS). `t` is minutes after the
 * absorption delay; tp = peak, td = duration.
 */
object Iob {
    private class Shape(tp: Double, val td: Double) {
        val tau = tp * (1 - tp / td) / (1 - 2 * tp / td)
        val a = 2 * tau / td
        val s = 1 / (1 - a + (1 + a) * exp(-td / tau))
    }

    /** Fraction of a dose still on board [t] minutes after the delay. */
    fun fraction(t: Double, peakMin: Double, durationMin: Double): Double {
        if (t <= 0) return 1.0
        if (t >= durationMin) return 0.0
        val sh = Shape(peakMin, durationMin)
        val tau = sh.tau
        val a = sh.a
        val f = 1 - sh.s * (1 - a) * ((t * t / (tau * durationMin * (1 - a)) - t / tau - 1) * exp(-t / tau) + 1)
        return f.coerceIn(0.0, 1.0)
    }

    /** Insulin activity (fraction of the dose acting per minute) at [t]. */
    fun activity(t: Double, peakMin: Double, durationMin: Double): Double {
        if (t <= 0 || t >= durationMin) return 0.0
        val sh = Shape(peakMin, durationMin)
        return (sh.s / (sh.tau * sh.tau)) * t * (1 - t / durationMin) * exp(-t / sh.tau)
    }

    fun fractionSinceDose(minutesSinceDose: Double, curve: IobCurve): Double =
        fraction(minutesSinceDose - curve.delayMin, curve.peakMin, curve.durationMin)

    /** IOB from logged rapid-acting doses (long-acting is not counted). Future-dated doses count fully. */
    fun total(doses: List<RapidDose>, nowEpochMillis: Long, curve: IobCurve): Double =
        doses.sumOf { d -> d.units * fractionSinceDose((nowEpochMillis - d.atEpochMillis) / 60_000.0, curve) }
}
