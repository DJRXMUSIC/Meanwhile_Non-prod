package app.meanwhile.data.stats

import app.meanwhile.data.cgm.toDomain
import app.meanwhile.data.db.AppDatabase
import app.meanwhile.data.input.ProposalSnapshot
import app.meanwhile.data.json.AppJson
import app.meanwhile.domain.stats.AiAccuracy
import app.meanwhile.domain.stats.AiCallRow
import app.meanwhile.domain.stats.AiDecisionRow
import app.meanwhile.domain.stats.AiModelStats
import app.meanwhile.domain.stats.DoseRow
import app.meanwhile.domain.stats.EstimateRow
import app.meanwhile.domain.stats.FollowStats
import app.meanwhile.domain.stats.GlucoseStats
import app.meanwhile.domain.stats.GlucoseSummary
import app.meanwhile.domain.stats.OutcomeRow
import app.meanwhile.domain.stats.ProposalRow
import app.meanwhile.domain.stats.Reliability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class StatsSnapshot(
    val days: Int,
    val today: GlucoseSummary,
    val window: GlucoseSummary,
    val daily: List<Pair<LocalDate, GlucoseSummary>>,
    val goalStreakDays: Int,
    val follow: FollowStats,
    val ai: List<AiModelStats>,
    val nbaComputeMs: List<Long>,
    val computedInMs: Long,
)

/** Reliability stats (spec §14 M8), computed locally from the append-only records. */
class StatsRepository(private val db: AppDatabase) {

    suspend fun compute(days: Int = 14, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): StatsSnapshot =
        withContext(Dispatchers.Default) {
            val started = System.nanoTime()
            val today = LocalDate.ofInstant(now, zone)
            val from = today.minusDays(days.toLong() - 1).atStartOfDay(zone).toInstant()
            val fromMs = from.toEpochMilli()
            val readings = db.cgm().between(fromMs, now.toEpochMilli()).map { it.toDomain() }
            val daily = (0 until days).map { i ->
                val d = today.minusDays((days - 1 - i).toLong())
                val s = d.atStartOfDay(zone).toInstant()
                val e = minOf(d.plusDays(1).atStartOfDay(zone).toInstant(), now)
                d to GlucoseStats.summarize(readings, s, e)
            }
            val proposals = db.proposals().since(fromMs)
            val doses = db.doses().since(fromMs - Duration.ofHours(6).toMillis())
            val outcomes = db.outcomes().since(fromMs)
            val follow = Reliability.follow(
                proposals.map { ProposalRow(it.id, it.recordedAt, it.finalUnits) },
                doses.map { DoseRow(it.id, it.givenAt, it.insulin, it.units, it.proposedUnits, it.proposalId) },
                outcomes.map { OutcomeRow(it.doseId, it.bg2h, it.bg3h, it.bg4h, it.min4h, it.max4h) },
            )
            val calls = db.aiCalls().since(fromMs)
            val callById = calls.associateBy { it.id }
            val decisions = db.profileVersions().all()
                .filter { it.aiCallId != null && it.createdAt >= fromMs && it.status in setOf("accepted", "edited", "rejected") }
                .mapNotNull { v -> callById[v.aiCallId]?.let { AiDecisionRow(it.provider, it.model, v.status) } }
            val estimates = db.meals().between(fromMs, now.toEpochMilli()).filter { it.isEstimate }.mapNotNull { m ->
                runCatching {
                    val est = AppJson.parseToJsonElement(m.details).jsonObject["estimate"]!!.jsonObject
                    val original = est["original"]!!.jsonObject["carbs_g"]!!.jsonPrimitive.doubleOrNull!!
                    EstimateRow(est["provider"]?.jsonPrimitive?.contentOrNull, est["model"]?.jsonPrimitive?.contentOrNull, original, m.carbsG)
                }.getOrNull()
            }
            val ai = AiAccuracy.byModel(
                calls.map { AiCallRow(it.provider, it.model, it.job, it.latencyMs, it.fallbackUsed, it.validation) },
                decisions, estimates,
            )
            val computeMs = proposals.mapNotNull {
                runCatching { AppJson.decodeFromString(ProposalSnapshot.serializer(), it.inputSnapshot).computeMs }.getOrNull()
            }
            StatsSnapshot(
                days = days,
                today = daily.last().second,
                window = GlucoseStats.summarize(readings, from, now),
                daily = daily,
                goalStreakDays = Reliability.goalStreak(daily.map { it.first.toString() to it.second }),
                follow = follow,
                ai = ai,
                nbaComputeMs = computeMs,
                computedInMs = (System.nanoTime() - started) / 1_000_000,
            )
        }
}
