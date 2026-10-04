package app.meanwhile.domain.stats

import app.meanwhile.domain.cgm.CgmReading
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

/** Time-weighted glucose statistics over a window. */
@Serializable
data class GlucoseSummary(
    val readings: Int,
    /** Minutes of CGM coverage counted (each reading counts until the next one, capped at [maxGapMin]). */
    val coveredMinutes: Double,
    val timeInRangePct: Double,
    val timeBelow70Pct: Double,
    val timeBelow54Pct: Double,
    val timeAbove180Pct: Double,
    val timeAbove250Pct: Double,
    val meanMgDl: Double?,
    val sdMgDl: Double?,
    val minMgDl: Int?,
    val maxMgDl: Int?,
    /** Glucose management indicator (%), from the mean. */
    val gmi: Double?,
)

object GlucoseStats {
    fun summarize(
        readings: List<CgmReading>,
        from: Instant,
        to: Instant,
        low: Int = 70,
        high: Int = 180,
        maxGapMin: Long = 15,
    ): GlucoseSummary {
        val sorted = readings.filter { !it.timestamp.isBefore(from) && it.timestamp.isBefore(to) }.sortedBy { it.timestamp }
        var total = 0.0
        var inRange = 0.0
        var below70 = 0.0
        var below54 = 0.0
        var above180 = 0.0
        var above250 = 0.0
        var weightedSum = 0.0
        for ((i, r) in sorted.withIndex()) {
            val next = sorted.getOrNull(i + 1)?.timestamp ?: r.timestamp.plus(Duration.ofMinutes(maxGapMin))
            val end = minOf(next, r.timestamp.plus(Duration.ofMinutes(maxGapMin)), to)
            val minutes = Duration.between(r.timestamp, end).toSeconds() / 60.0
            if (minutes <= 0) continue
            total += minutes
            weightedSum += r.mgDl * minutes
            when {
                r.mgDl < 54 -> { below54 += minutes; below70 += minutes }
                r.mgDl < low -> below70 += minutes
                r.mgDl <= high -> inRange += minutes
                r.mgDl <= 250 -> above180 += minutes
                else -> { above250 += minutes; above180 += minutes }
            }
        }
        fun pct(x: Double) = if (total > 0) 100.0 * x / total else 0.0
        val mean = if (total > 0) weightedSum / total else null
        val sd = if (sorted.size > 1) {
            val m = sorted.map { it.mgDl.toDouble() }.average()
            kotlin.math.sqrt(sorted.sumOf { (it.mgDl - m) * (it.mgDl - m) } / (sorted.size - 1))
        } else {
            null
        }
        return GlucoseSummary(
            readings = sorted.size, coveredMinutes = total,
            timeInRangePct = pct(inRange), timeBelow70Pct = pct(below70), timeBelow54Pct = pct(below54),
            timeAbove180Pct = pct(above180), timeAbove250Pct = pct(above250),
            meanMgDl = mean, sdMgDl = sd, minMgDl = sorted.minOfOrNull { it.mgDl }, maxMgDl = sorted.maxOfOrNull { it.mgDl },
            gmi = mean?.let { 3.31 + 0.02392 * it },
        )
    }
}

/** BG outcomes after a dose (spec §11.4). */
@Serializable
data class DoseOutcome(val bg2h: Int?, val bg3h: Int?, val bg4h: Int?, val min4h: Int?, val max4h: Int?)

object Outcomes {
    /** True once the 4 h window (plus a small margin for the last reading) has passed. */
    fun ready(doseAt: Instant, now: Instant, marginMin: Long = 10): Boolean =
        !now.isBefore(doseAt.plus(Duration.ofMinutes(240 + marginMin)))

    /** Readings nearest to +2/+3/+4 h (within [toleranceMin]), and min/max over the 4 h after. */
    fun compute(doseAt: Instant, readings: List<CgmReading>, toleranceMin: Long = 10): DoseOutcome {
        fun near(hours: Long): Int? {
            val target = doseAt.plus(Duration.ofHours(hours))
            return readings.filter { kotlin.math.abs(Duration.between(target, it.timestamp).toMinutes()) <= toleranceMin }
                .minByOrNull { kotlin.math.abs(Duration.between(target, it.timestamp).toSeconds()) }?.mgDl
        }
        val window = readings.filter { it.timestamp.isAfter(doseAt) && !it.timestamp.isAfter(doseAt.plus(Duration.ofHours(4))) }
        return DoseOutcome(near(2), near(3), near(4), window.minOfOrNull { it.mgDl }, window.maxOfOrNull { it.mgDl })
    }
}
