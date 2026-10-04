package app.meanwhile.domain.factors

import app.meanwhile.domain.cgm.CgmReading
import app.meanwhile.domain.profile.ActiveFactor
import app.meanwhile.domain.profile.FactorDefinition
import app.meanwhile.domain.profile.FactorKind
import app.meanwhile.domain.profile.Profile
import app.meanwhile.domain.profile.WindowType
import app.meanwhile.domain.time.ResetClock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** A multiplier in effect right now, with where it came from (for the breakdown). */
data class AppliedFactor(
    val factorId: String,
    val name: String,
    val weight: Double,
    val startedAt: Long,
    val expiresAt: Long?,
    val source: String,
    val note: String? = null,
)

/** A units-per-event factor (caffeine) waiting for the next dose. */
data class PendingUnitsEvent(val factorId: String, val units: Double, val amount: Double, val atEpochMillis: Long)

object FactorEngine {

    /** When an activation stops applying (null = never, e.g. bad definition). */
    fun expiry(active: ActiveFactor, def: FactorDefinition, reset: ResetClock): Instant? {
        val start = Instant.ofEpochMilli(active.startedAt)
        val nextReset = reset.nextAfter(start)
        val minutes = active.windowMinutes ?: def.window.minutes
        return when (def.window.type) {
            WindowType.UNTIL_RESET -> if (minutes != null) minOf(start.plusSeconds(minutes * 60L), nextReset) else nextReset
            WindowType.FIXED, WindowType.AFTER_LAST_TRIGGER -> {
                val end = start.plusSeconds((minutes ?: 1440) * 60L)
                if (def.window.survivesReset) end else minOf(end, nextReset)
            }
            WindowType.CONSUMED_BY_NEXT_DOSE, WindowType.PER_MEAL -> nextReset
        }
    }

    /**
     * Multipliers in effect at [now] from the profile's activations, plus F10 from CGM readings.
     * Non-stacking factors keep only their most recent activation.
     */
    fun applied(profile: Profile, now: Instant, zone: ZoneId, readings: List<CgmReading>): List<AppliedFactor> {
        val reset = ResetClock(zone, profile.resetHour)
        val out = mutableListOf<AppliedFactor>()
        val byFactor = profile.active
            .filter { it.startedAt <= now.toEpochMilli() }
            .groupBy { it.factorId }
        for ((id, activations) in byFactor) {
            val def = profile.factor(id) ?: continue
            if (!def.enabled) continue
            if (def.kind != FactorKind.MULTIPLIER && def.kind != FactorKind.AUTO_MULTIPLIER) continue
            val live = activations.filter { a -> expiry(a, def, reset)?.isAfter(now) == true }
            val chosen = if (def.window.stacks) live else listOfNotNull(live.maxByOrNull { it.startedAt })
            for (a in chosen) {
                val initial = a.weight ?: def.defaultWeight ?: continue
                val decay = a.decay ?: def.decay
                val minutes = Duration.between(Instant.ofEpochMilli(a.startedAt), now).toSeconds() / 60.0
                val weight = decay?.weightAt(initial, minutes) ?: initial
                out += AppliedFactor(id, def.name, weight, a.startedAt, expiry(a, def, reset)?.toEpochMilli(), a.source, a.note ?: a.preset)
            }
        }
        recentHypo(profile, now, readings)?.let { hypo ->
            out.removeAll { it.factorId == hypo.factorId }
            out += hypo
        }
        return out.sortedBy { it.factorId.removePrefix("F").toIntOrNull() ?: Int.MAX_VALUE }
    }

    /** F10: active for `window` minutes after the most recent reading below the threshold. */
    fun recentHypo(profile: Profile, now: Instant, readings: List<CgmReading>): AppliedFactor? {
        val def = profile.factors.firstOrNull { it.kind == FactorKind.AUTO_MULTIPLIER && it.window.type == WindowType.AFTER_LAST_TRIGGER && it.enabled }
            ?: return null
        val threshold = def.params["thresholdMgDl"] ?: 70.0
        val windowMin = (def.window.minutes ?: 720).toLong()
        val last = readings.filter { it.mgDl < threshold && !it.timestamp.isAfter(now) }.maxByOrNull { it.timestamp } ?: return null
        val until = last.timestamp.plus(Duration.ofMinutes(windowMin))
        if (!until.isAfter(now)) return null
        return AppliedFactor(
            def.id, def.name, def.defaultWeight ?: 0.80, last.timestamp.toEpochMilli(), until.toEpochMilli(), "auto",
            "last reading below ${threshold.toInt()}: ${last.mgDl} mg/dL",
        )
    }

    /**
     * Units-per-event factors (caffeine) waiting for the next dose: events after both the last logged
     * rapid-acting dose and the last daily reset.
     */
    fun pendingUnits(
        profile: Profile,
        now: Instant,
        zone: ZoneId,
        events: List<PendingUnitsEvent>,
        lastRapidDoseAt: Long?,
    ): List<PendingUnitsEvent> {
        val since = maxOf(lastRapidDoseAt ?: Long.MIN_VALUE, ResetClock(zone, profile.resetHour).lastAtOrBefore(now).toEpochMilli())
        val ids = profile.factors.filter { it.kind == FactorKind.UNITS_PER_EVENT && it.enabled }.map { it.id }.toSet()
        return events.filter { it.factorId in ids && it.atEpochMillis > since && it.atEpochMillis <= now.toEpochMilli() }
    }

    /** Drops activations that have expired by [now] (used when writing a new profile version). */
    fun prune(profile: Profile, now: Instant, zone: ZoneId): Profile {
        val reset = ResetClock(zone, profile.resetHour)
        val kept = profile.active.filter { a ->
            val def = profile.factor(a.factorId) ?: return@filter false
            expiry(a, def, reset)?.isAfter(now) == true
        }
        return profile.copy(active = kept)
    }

    /** F11 (spec §7.3): hours between start and end hour with readings above the threshold. */
    data class OvernightResult(val hours: Double, val weight: Double, val windowStart: Instant, val windowEnd: Instant)

    fun overnightHighs(profile: Profile, morning: Instant, zone: ZoneId, readings: List<CgmReading>): OvernightResult {
        val def = profile.factors.firstOrNull { it.id == "F11" }
        val p = def?.params.orEmpty()
        val startHour = (p["startHour"] ?: 22.0).toInt()
        val endHour = (p["endHour"] ?: 6.0).toInt()
        val threshold = p["thresholdMgDl"] ?: 180.0
        val minHours = p["minHours"] ?: 3.0
        val perHour = p["perHour"] ?: 0.05
        val maxWeight = p["maxWeight"] ?: 1.25
        val maxGap = Duration.ofMinutes((p["maxGapMinutes"] ?: 15.0).toLong())

        val local = morning.atZone(zone)
        var end = ZonedDateTime.of(local.toLocalDate(), LocalTime.of(endHour, 0), zone)
        if (end.isAfter(local)) end = end.minusDays(1)
        var start = ZonedDateTime.of(end.toLocalDate(), LocalTime.of(startHour, 0), zone)
        if (!start.isBefore(end)) start = start.minusDays(1)
        val ws = start.toInstant()
        val we = end.toInstant()

        val sorted = readings.sortedBy { it.timestamp }
        var seconds = 0L
        for ((i, r) in sorted.withIndex()) {
            if (r.mgDl <= threshold) continue
            val from = maxOf(r.timestamp, ws)
            val next = sorted.getOrNull(i + 1)?.timestamp ?: r.timestamp.plus(maxGap)
            val to = minOf(next, r.timestamp.plus(maxGap), we)
            if (to.isAfter(from)) seconds += Duration.between(from, to).toSeconds()
        }
        val hours = seconds / 3600.0
        val weight = if (hours >= minHours) minOf(1 + perHour * hours, maxWeight) else 1.0
        return OvernightResult(hours, weight, ws, we)
    }
}
