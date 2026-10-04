package app.meanwhile.domain.time

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** The daily factor reset (spec §7.4) at [hour]:00 local time. */
class ResetClock(private val zone: ZoneId, private val hour: Int = 1) {
    /** First reset strictly after [t]. */
    fun nextAfter(t: Instant): Instant {
        val local = t.atZone(zone)
        var candidate = ZonedDateTime.of(local.toLocalDate(), LocalTime.of(hour, 0), zone)
        if (!candidate.toInstant().isAfter(t)) candidate = candidate.plusDays(1)
        return candidate.toInstant()
    }

    /** Most recent reset at or before [t]. */
    fun lastAtOrBefore(t: Instant): Instant {
        val local = t.atZone(zone)
        var candidate = ZonedDateTime.of(local.toLocalDate(), LocalTime.of(hour, 0), zone)
        if (candidate.toInstant().isAfter(t)) candidate = candidate.minusDays(1)
        return candidate.toInstant()
    }
}
