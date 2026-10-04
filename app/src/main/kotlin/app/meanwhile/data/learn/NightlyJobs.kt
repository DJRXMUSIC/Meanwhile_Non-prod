package app.meanwhile.data.learn

import android.content.Context
import app.meanwhile.data.RecordFactory
import app.meanwhile.data.ai.AiClient
import app.meanwhile.data.ai.AiOutcome
import app.meanwhile.data.ai.LearnCycleDto
import app.meanwhile.data.cgm.CgmRepository
import app.meanwhile.data.db.AppDatabase
import app.meanwhile.data.db.OutcomeEntity
import app.meanwhile.data.db.ProfileVersionEntity
import app.meanwhile.data.json.AppJson
import app.meanwhile.data.profile.ProfileRepository
import app.meanwhile.data.profile.ProfileSource
import app.meanwhile.data.profile.ProfileStatus
import app.meanwhile.data.settings.SettingsStore
import app.meanwhile.domain.factors.Activations
import app.meanwhile.domain.factors.FactorEngine
import app.meanwhile.domain.stats.Outcomes
import app.meanwhile.domain.time.ResetClock
import app.meanwhile.notify.Notifications
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

@Serializable
data class LearnResult(
    /** done | no_changes | failed */
    val status: String,
    val message: String,
    val versionId: String? = null,
    val observations: List<String> = emptyList(),
    val at: Long,
)

@Serializable
data class OvernightResultView(val hours: Double, val weight: Double, val versionId: String? = null, val at: Long)

/** 1 am learn cycle, 6 am overnight highs (F11), outcome tagging (spec §11). */
class NightlyJobs(
    private val context: Context,
    private val db: AppDatabase,
    private val records: RecordFactory,
    private val profiles: ProfileRepository,
    private val cgm: CgmRepository,
    private val ai: AiClient,
    private val settings: SettingsStore,
    private val onWrite: () -> Unit,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val learnLock = Mutex()
    private val payloads = LearnPayload(db)

    /** The night the most recent 1 am reset belongs to (its local date). */
    suspend fun nightOf(now: Instant = Instant.now()): Pair<LocalDate, Instant> {
        val reset = ResetClock(zone(), profiles.current().profile.resetHour).lastAtOrBefore(now)
        return LocalDate.ofInstant(reset, zone()) to reset
    }

    suspend fun learnResult(date: LocalDate): LearnResult? =
        settings.marker("learn_result_$date")?.let { runCatching { AppJson.decodeFromString(LearnResult.serializer(), it) }.getOrNull() }

    suspend fun learnDone(date: LocalDate): Boolean = settings.marker(KEY_LEARN_DONE) == date.toString()

    /** Runs tonight's learn cycle unless it already completed (alarm, worker, or catch-up on open). */
    suspend fun learnCycleIfNeeded(now: Instant = Instant.now(), force: Boolean = false): LearnResult? = learnLock.withLock {
        val (date, resetAt) = nightOf(now)
        if (!force && learnDone(date)) return@withLock learnResult(date)
        runLearnCycle(date, resetAt, now)
    }

    private suspend fun runLearnCycle(date: LocalDate, resetAt: Instant, now: Instant): LearnResult {
        tagOutcomes(now)
        // Step 1: the 1 am reset — expired activations drop out of the profile sent for analysis.
        val current = profiles.current()
        val profile = FactorEngine.prune(current.profile, resetAt, zone())
        val payload = payloads.build(profile, resetAt, zone(), now)
        var out = ai.call("learn_cycle", payload, null, "learn_cycle for $date", AiClient.LEARN_TIMEOUT_MS)
        if (out is AiOutcome.Failed && out.retryWith != null) {
            out = ai.call("learn_cycle", payload, null, "learn_cycle for $date (retry ${out.retryWith})", AiClient.LEARN_TIMEOUT_MS, preferenceWire = out.retryWith)
        }
        val result = when (out) {
            is AiOutcome.Failed -> LearnResult("failed", "Learn cycle couldn't run (${out.reason}). Yesterday's profile carried forward.", at = now.toEpochMilli())
            is AiOutcome.Ok -> {
                val dto = runCatching { AppJson.decodeFromJsonElement(LearnCycleDto.serializer(), out.result) }.getOrNull()
                if (dto == null) {
                    LearnResult("failed", "Learn cycle result couldn't be read. Yesterday's profile carried forward.", at = now.toEpochMilli())
                } else {
                    val changes = LearnMapping.changes(dto, current.profile)
                    if (changes.isEmpty()) {
                        LearnResult("no_changes", dto.summary.ifBlank { "No changes proposed." }, observations = dto.observations, at = now.toEpochMilli())
                    } else {
                        val proposed = app.meanwhile.domain.profile.ProfilePatch.apply(current.profile, changes).getOrDefault(current.profile)
                        val v = profiles.saveVersion(
                            proposed, ProfileSource.LEARN_CYCLE, ProfileStatus.PENDING,
                            summary = dto.summary.take(500), changes = changes, aiCallId = out.callId, now = now,
                        )
                        LearnResult("done", dto.summary, versionId = v.id, observations = dto.observations, at = now.toEpochMilli())
                    }
                }
            }
        }
        settings.setMarker("learn_result_$date", AppJson.encodeToString(LearnResult.serializer(), result))
        // A failure is still "done" for the alarm; the morning report offers a retry.
        settings.setMarker(KEY_LEARN_DONE, date.toString())
        Notifications.post(
            context, Notifications.ID_MORNING_REPORT, Notifications.CHANNEL_REPORTS, "Morning report ready",
            when (result.status) {
                "done" -> "Proposed profile changes to review: ${result.message}".take(200)
                "no_changes" -> "No profile changes proposed overnight."
                else -> result.message
            },
            destination = "morning", silent = true,
        )
        onWrite()
        return result
    }

    /** Spec §7.3: at 6 am, hours 10 pm–6 am above 180 → F11 weight until the 1 am reset. */
    suspend fun overnightIfNeeded(now: Instant = Instant.now()): OvernightResultView? {
        val z = zone()
        val today = LocalDate.ofInstant(now, z)
        val endHour = (profiles.current().profile.factor("F11")?.params?.get("endHour") ?: 6.0).toInt()
        val sixAm = ZonedDateTime.of(today, LocalTime.of(endHour, 0), z).toInstant()
        if (now.isBefore(sixAm)) return null
        if (settings.marker(KEY_F11_DONE) == today.toString()) {
            return settings.marker("f11_result_$today")?.let { runCatching { AppJson.decodeFromString(OvernightResultView.serializer(), it) }.getOrNull() }
        }
        val readings = cgm.recent(sixAm.minus(Duration.ofHours(10)))
        val state = profiles.current()
        val r = FactorEngine.overnightHighs(state.profile, sixAm, z, readings)
        var versionId: String? = null
        if (r.weight > 1.0) {
            val updated = Activations.activate(
                state.profile, "F11", sixAm.toEpochMilli(), "auto", weight = r.weight,
                note = String.format(java.util.Locale.US, "%.1f h above 180 overnight", r.hours),
            )
            versionId = profiles.saveVersion(
                updated, ProfileSource.AUTO_F11, ProfileStatus.ACCEPTED,
                String.format(java.util.Locale.US, "Overnight highs: %.1f h above 180 → F11 %.2f", r.hours, r.weight),
            ).id
        }
        val view = OvernightResultView(r.hours, r.weight, versionId, now.toEpochMilli())
        settings.setMarker("f11_result_$today", AppJson.encodeToString(OvernightResultView.serializer(), view))
        settings.setMarker(KEY_F11_DONE, today.toString())
        return view
    }

    /** Spec §11.4: BG at +2/+3/+4 h and min/max over 4 h for each logged rapid dose. */
    suspend fun tagOutcomes(now: Instant = Instant.now()) {
        val tagged = db.outcomes().taggedDoseIds().toSet()
        val doses = db.doses().since(now.minus(Duration.ofHours(48)).toEpochMilli())
            .filter { it.insulin == "rapid" && it.units > 0 && it.id !in tagged && Outcomes.ready(Instant.ofEpochMilli(it.givenAt), now) }
        if (doses.isEmpty()) return
        val readings = cgm.recent(Instant.ofEpochMilli(doses.minOf { it.givenAt }))
        val rows = doses.mapNotNull { d ->
            val at = Instant.ofEpochMilli(d.givenAt)
            val o = Outcomes.compute(at, readings)
            val stale = Duration.between(at, now).toHours() >= 24
            if (o.min4h == null && !stale) return@mapNotNull null // wait for back-fill
            val m = records.meta(recordedAt = at.plus(Duration.ofHours(4)).toEpochMilli(), now = now.toEpochMilli())
            OutcomeEntity(m.id, m.userId, m.createdAt, m.recordedAt, doseId = d.id, bg2h = o.bg2h, bg3h = o.bg3h, bg4h = o.bg4h, min4h = o.min4h, max4h = o.max4h)
        }
        if (rows.isNotEmpty()) {
            db.outcomes().insertAll(rows)
            onWrite()
        }
    }

    /** The learn-cycle proposal for [date] that's still waiting for Danny, if any. */
    suspend fun pendingProposal(date: LocalDate): ProfileVersionEntity? {
        val id = learnResult(date)?.versionId ?: return null
        val all = db.profileVersions().all()
        if (all.any { it.supersedesId == id }) return null
        return all.firstOrNull { it.id == id && it.status == ProfileStatus.PENDING }
    }

    suspend fun morningSeen(date: LocalDate) = settings.marker(KEY_MORNING_SEEN) == date.toString()

    suspend fun markMorningSeen(date: LocalDate) = settings.setMarker(KEY_MORNING_SEEN, date.toString())

    companion object {
        const val KEY_LEARN_DONE = "learn_done_for"
        const val KEY_F11_DONE = "f11_done_for"
        const val KEY_MORNING_SEEN = "morning_seen_for"
    }
}
