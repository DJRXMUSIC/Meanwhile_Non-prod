package app.meanwhile.domain.cgm

import java.time.Duration
import java.time.Instant

/**
 * Decides whether a reading seen in a notification is a new reading. A notification carries no
 * reading time and may be re-posted for reasons other than a new value, and xDrip+ may deliver the
 * same sensor reading too; Eversense measures every 5 minutes.
 */
object ReadingGate {
    /** Any reading (any source) this close is the same sensor reading. */
    val SAME_READING: Duration = Duration.ofMinutes(2)

    /** The same value this close is a re-post, not a new reading. */
    val REPOST: Duration = Duration.ofSeconds(270)

    /**
     * A value repeated this many times in a row (35 min) is treated as stuck — a notification still
     * showing the last value after the transmitter lost the sensor — until it changes.
     */
    const val MAX_IDENTICAL = 7

    sealed interface Decision {
        data object Accept : Decision
        data class Skip(val reason: String, val stuck: Boolean = false) : Decision
    }

    fun decide(mgDl: Int, at: Instant, source: String, recent: List<CgmReading>): Decision {
        fun gap(r: CgmReading) = Duration.between(r.timestamp, at).abs()
        recent.firstOrNull { gap(it) < SAME_READING }?.let {
            return Decision.Skip("already have ${it.mgDl} mg/dL from ${it.source} at ${it.timestamp}")
        }
        recent.firstOrNull { it.mgDl == mgDl && gap(it) < REPOST }?.let {
            return Decision.Skip("same value re-posted (${it.timestamp})")
        }
        val run = recent.filter { it.source == source && it.timestamp.isBefore(at) }
            .sortedByDescending { it.timestamp }
            .takeWhile { it.mgDl == mgDl }
            .size
        if (run >= MAX_IDENTICAL) {
            return Decision.Skip("value stuck at $mgDl mg/dL for $run readings — check the Eversense app and transmitter", stuck = true)
        }
        return Decision.Accept
    }
}
