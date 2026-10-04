package app.meanwhile.domain.cgm

import java.time.Duration
import java.time.Instant

object Trend {
    /**
     * Rate of change in mg/dL/min: least-squares slope of readings within [windowMinutes] of the newest
     * reading. Needs at least two readings spanning ≥ 4 minutes; otherwise falls back to the newest
     * reading's source-reported rate.
     */
    fun rate(readings: List<CgmReading>, windowMinutes: Long = 15): Double? {
        if (readings.isEmpty()) return null
        val sorted = readings.sortedBy { it.timestamp }
        val newest = sorted.last()
        val window = sorted.filter { Duration.between(it.timestamp, newest.timestamp).toMinutes() <= windowMinutes }
        val spanMin = Duration.between(window.first().timestamp, newest.timestamp).toSeconds() / 60.0
        if (window.size < 2 || spanMin < 4.0) return newest.trendRate
        val t0 = newest.timestamp.toEpochMilli()
        val xs = window.map { (it.timestamp.toEpochMilli() - t0) / 60_000.0 }
        val ys = window.map { it.mgDl.toDouble() }
        val mx = xs.average()
        val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        if (sxx == 0.0) return newest.trendRate
        val sxy = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) }
        return sxy / sxx
    }

    /** Arrow for a mg/dL/min rate, using the usual CGM thresholds. */
    fun arrow(rate: Double?): String = when {
        rate == null -> "?"
        rate >= 3.0 -> "⇈"
        rate >= 2.0 -> "↑"
        rate >= 1.0 -> "↗"
        rate > -1.0 -> "→"
        rate > -2.0 -> "↘"
        rate > -3.0 -> "↓"
        else -> "⇊"
    }

    /** Midpoint rate for a Nightscout direction name, when the source gives no numeric delta. */
    fun rateForDirection(direction: String?): Double? = when (direction) {
        "DoubleUp" -> 3.5
        "SingleUp" -> 2.5
        "FortyFiveUp" -> 1.5
        "Flat" -> 0.0
        "FortyFiveDown" -> -1.5
        "SingleDown" -> -2.5
        "DoubleDown" -> -3.5
        else -> null
    }

    fun ageMinutes(reading: CgmReading?, now: Instant): Long? =
        reading?.let { Duration.between(it.timestamp, now).toMinutes() }
}
