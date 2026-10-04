package app.meanwhile.data.dose

import app.meanwhile.data.cgm.CgmRepository
import app.meanwhile.data.db.AppDatabase
import app.meanwhile.data.profile.ProfileRepository
import app.meanwhile.data.profile.ProfileState
import app.meanwhile.domain.cgm.CgmReading
import app.meanwhile.domain.cgm.Trend
import app.meanwhile.domain.dose.DoseEngine
import app.meanwhile.domain.dose.DoseInput
import app.meanwhile.domain.dose.PendingUnits
import app.meanwhile.domain.factors.AppliedFactor
import app.meanwhile.domain.factors.FactorEngine
import app.meanwhile.domain.factors.PendingUnitsEvent
import app.meanwhile.domain.iob.Iob
import app.meanwhile.domain.iob.RapidDose
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Everything live that feeds Next Best Action, captured at one instant. */
data class DoseContext(
    val at: Instant,
    val profile: ProfileState,
    val latest: CgmReading?,
    val trendRate: Double?,
    val bgAgeMinutes: Long?,
    val iob: Double,
    val applied: List<AppliedFactor>,
    val pendingUnits: List<PendingUnits>,
    val lastRapidDoseAt: Long?,
) {
    fun input(
        carbsG: Double = 0.0,
        fatG: Double = 0.0,
        proteinG: Double = 0.0,
        liquidOrSugary: Boolean = false,
        bgOverride: Double? = null,
    ) = DoseInput(
        carbsG = carbsG, fatG = fatG, proteinG = proteinG, liquidOrSugary = liquidOrSugary,
        bg = bgOverride ?: latest?.mgDl?.toDouble(),
        trendRate = trendRate,
        iob = iob,
        factors = DoseEngine.weights(applied),
        pendingUnits = pendingUnits,
    )
}

class DoseContextBuilder(
    private val db: AppDatabase,
    private val cgm: CgmRepository,
    private val profiles: ProfileRepository,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    suspend fun build(now: Instant = Instant.now()): DoseContext {
        val state = profiles.current()
        val p = state.profile
        val readings = cgm.recent(now.minus(Duration.ofHours(13)))
        val latest = readings.lastOrNull() ?: cgm.latestNow()
        val trend = Trend.rate(readings.filter { Duration.between(it.timestamp, now).toMinutes() <= 30 })
        val lookback = now.minus(Duration.ofMinutes(p.iob.durationMin.toLong() + p.iob.delayMin.toLong() + 5))
        val rapid = db.doses().since(lookback.toEpochMilli()).filter { it.insulin == "rapid" }
        val iob = Iob.total(rapid.map { RapidDose(it.units, it.givenAt) }, now.toEpochMilli(), p.iob)
        val lastRapid = db.doses().latestRapid()?.givenAt
        val events = db.factorEvents().since(now.minus(Duration.ofDays(2)).toEpochMilli())
            .filter { it.unitsAdd != null && it.action != "deactivate" }
            .map { PendingUnitsEvent(it.factorId, it.unitsAdd ?: 0.0, amountOf(it.details), it.recordedAt) }
        val pending = FactorEngine.pendingUnits(p, now, zone(), events, lastRapid)
            .groupBy { it.factorId }
            .map { (id, list) ->
                PendingUnits(id, p.factor(id)?.name ?: id, list.sumOf { it.units }, list.sumOf { it.amount })
            }
        return DoseContext(
            at = now,
            profile = state,
            latest = latest,
            trendRate = trend,
            bgAgeMinutes = latest?.let { Duration.between(it.timestamp, now).toMinutes() },
            iob = iob,
            applied = FactorEngine.applied(p, now, zone(), readings),
            pendingUnits = pending,
            lastRapidDoseAt = lastRapid,
        )
    }

    private fun amountOf(details: String): Double =
        Regex("\"(cups|amount|count)\"\\s*:\\s*([0-9.]+)").find(details)?.groupValues?.get(2)?.toDoubleOrNull() ?: 1.0
}
