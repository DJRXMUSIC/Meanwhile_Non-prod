package app.meanwhile.data.learn

import android.content.Context
import app.meanwhile.data.RecordFactory
import app.meanwhile.data.ai.AiClient
import app.meanwhile.data.ai.AiOutcome
import app.meanwhile.data.ai.LearnCycleDto
import app.meanwhile.data.db.AppDatabase
import app.meanwhile.data.db.LearningLogEntity
import app.meanwhile.data.db.ProfileVersionEntity
import app.meanwhile.data.input.ProposalSnapshot
import app.meanwhile.data.json.AppJson
import app.meanwhile.data.json.isoOf
import app.meanwhile.data.profile.ProfileRepository
import app.meanwhile.data.profile.ProfileSource
import app.meanwhile.data.profile.ProfileStatus
import app.meanwhile.data.profile.changes
import app.meanwhile.data.profile.decodedProfile
import app.meanwhile.data.settings.LearningAutonomy
import app.meanwhile.data.settings.SettingsStore
import app.meanwhile.domain.dose.DoseResult
import app.meanwhile.domain.learn.ChangeEvaluator
import app.meanwhile.domain.learn.Evaluation
import app.meanwhile.domain.learn.Lesson
import app.meanwhile.domain.learn.LessonKind
import app.meanwhile.domain.learn.Lessons
import app.meanwhile.domain.learn.MealRow
import app.meanwhile.domain.learn.ProposalFacts
import app.meanwhile.domain.learn.TrackedChange
import app.meanwhile.domain.learn.Tuner
import app.meanwhile.domain.learn.Verdict
import app.meanwhile.domain.profile.FactorKind
import app.meanwhile.domain.profile.Profile
import app.meanwhile.domain.profile.ProfileChange
import app.meanwhile.domain.profile.ProfilePatch
import app.meanwhile.domain.profile.ProfileValidation
import app.meanwhile.domain.stats.DoseRow
import app.meanwhile.domain.stats.OutcomeRow
import app.meanwhile.log.AppLog
import app.meanwhile.notify.Notifications
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** The journal's `details` for an `applied` change. */
@Serializable
data class AppliedDetails(
    val path: String,
    val old: JsonElement? = null,
    val new: JsonElement? = null,
    val evidence: String = "",
    val source: String,
    val moreInsulin: Boolean? = null,
)

data class ApplyOutcome(
    val applied: List<ProfileChange>,
    val appliedVersion: ProfileVersionEntity?,
    val pending: List<ProfileChange>,
    val pendingVersion: ProfileVersionEntity?,
)

/** One tunable value and where the evidence stands (Learning screen, diagnostics, AI payload). */
data class EvidenceLine(
    val path: String,
    val label: String,
    val current: Double,
    val implied: Double?,
    val lessons: Int,
    val freshSinceChange: Int,
    val needed: Int,
)

data class OpenChange(val entry: LearningLogEntity, val details: AppliedDetails, val evaluation: Evaluation)

data class LearningStatus(
    val autonomy: LearningAutonomy,
    val lessons: List<Lesson>,
    val evidence: List<EvidenceLine>,
    val underEvaluation: List<OpenChange>,
    val lastLocalRunAt: Long?,
    val lastAiReviewAt: Long?,
)

/**
 * Continuous learning (1.3). After every batch of dose outcomes: document the new lessons, judge
 * every open learned change (keep / revert), and let the local tuner move ICR, ISF and
 * units-per-event toward what the outcomes imply. An AI review runs every night and also mid-day
 * once enough new evidence arrived. What may apply on its own is Danny's choice
 * ([LearningAutonomy]); everything is written to the learning journal (`learning_log`).
 */
class LearningEngine(
    private val context: Context,
    private val db: AppDatabase,
    private val records: RecordFactory,
    private val profiles: ProfileRepository,
    private val ai: AiClient,
    private val settings: SettingsStore,
    private val onWrite: () -> Unit,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    /** Guards profile changes and the journal's open/closed state. Never held across a network call. */
    private val lock = Mutex()
    /** One AI review at a time (nightly or incremental). */
    private val aiLock = Mutex()
    private val payloads = LearnPayload(db)
    private val profileByVersion = ConcurrentHashMap<String, Profile>()

    // --- lessons -----------------------------------------------------------------------------

    /** Lessons from the local records over the lookback window, oldest first. */
    suspend fun lessons(now: Instant = Instant.now(), profile: Profile? = null): List<Lesson> {
        val p = profile ?: profiles.current().profile
        val from = now.minus(Duration.ofDays(p.learning.lookbackDays.toLong() + 1)).toEpochMilli()
        val proposals = db.proposals().since(from).mapNotNull { e ->
            val snapshot = runCatching { AppJson.decodeFromString(ProposalSnapshot.serializer(), e.inputSnapshot) }.getOrNull()
            val result = runCatching { AppJson.decodeFromString(DoseResult.serializer(), e.breakdown) }.getOrNull()
            if (snapshot == null || result == null) null else ProposalFacts(e.id, e.recordedAt, snapshot.doseInput, result, profileFor(e.profileVersionId))
        }
        val doses = db.doses().since(from).map { DoseRow(it.id, it.givenAt, it.insulin, it.units, it.proposedUnits, it.proposalId) }
        val outcomes = db.outcomes().since(from).map { OutcomeRow(it.doseId, it.bg2h, it.bg3h, it.bg4h, it.min4h, it.max4h) }
        val meals = db.meals().between(from - HOUR_MS, now.toEpochMilli() + 5 * HOUR_MS).map { MealRow(it.recordedAt, proposalIdOf(it.details)) }
        return Lessons.build(proposals, doses, outcomes, meals, zone(), p.learning)
    }

    private suspend fun profileFor(versionId: String?): Profile {
        if (versionId == null) return Profile()
        profileByVersion[versionId]?.let { return it }
        val p = db.profileVersions().byId(versionId)?.let { runCatching { it.decodedProfile() }.getOrNull() } ?: Profile()
        profileByVersion[versionId] = p
        return p
    }

    private fun proposalIdOf(details: String): String? = runCatching {
        AppJson.parseToJsonElement(details).jsonObject["proposal_id"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    // --- the loop ------------------------------------------------------------------------------

    /** After outcome tagging (every ~15 min, app start, watchdog). Cheap when nothing is new. */
    suspend fun afterOutcomes(now: Instant = Instant.now()) {
        lock.withLock { localCycle(now, force = false) }
        maybeAiReview(now, force = false)
    }

    /** "Learn now" (Learning screen): the local cycle and an AI review regardless of spacing. */
    suspend fun runNow(now: Instant = Instant.now()): String {
        val local = lock.withLock { localCycle(now, force = true) }
        val review = maybeAiReview(now, force = true)
        return listOfNotNull(local, review).joinToString(" · ").ifBlank { "Nothing new to learn yet" }
    }

    private suspend fun localCycle(now: Instant, force: Boolean): String? {
        val profile = profiles.current().profile
        val lessons = lessons(now, profile)
        val parts = mutableListOf<String>()

        // 1. Document lessons not seen before.
        val seen = settings.marker(KEY_SEEN)?.split(',')?.toSet().orEmpty()
        val fresh = lessons.filter { it.doseId !in seen }
        if (fresh.isNotEmpty()) {
            journal(
                "lessons", lessonSummary(fresh),
                buildJsonObject { put("lessons", AppJson.encodeToJsonElement(ListSerializer(Lesson.serializer()), fresh)) },
                now = now,
            )
            settings.setMarker(KEY_SEEN, lessons.joinToString(",") { it.doseId })
            parts += "${fresh.size} new lesson${plural(fresh.size)}"
        }

        // 2. Judge every open learned change.
        for (entry in db.learningLog().openChanges()) {
            val d = details(entry) ?: continue
            val eval = ChangeEvaluator.evaluate(TrackedChange(entry.id, d.path, entry.recordedAt, d.moreInsulin), lessons, profile.learning)
            when (eval.verdict) {
                Verdict.PENDING -> Unit
                Verdict.KEEP -> {
                    journal("kept", "Kept ${label(d.path, profile)} ${show(d.old)} → ${show(d.new)}: ${eval.reason}", evalJson(eval), supersedes = entry.id, now = now)
                    parts += "kept ${label(d.path, profile)}"
                }
                Verdict.REVERT -> revert(entry, d, eval, now)?.let { parts += it }
            }
        }

        // 3. Tune from the evidence (only when something new arrived, or on demand).
        if (fresh.isNotEmpty() || force) {
            val current = profiles.current().profile
            val waiting = pendingPaths()
            val steps = Tuner.propose(lessons, current, lastChanged(now), now.toEpochMilli()).filter { it.path !in waiting }
            if (steps.isNotEmpty()) {
                val changes = steps.map { ProfileChange(it.path, JsonPrimitive(it.old), JsonPrimitive(it.new), reason = it.evidence) }
                val summary = "Learned from outcomes: " + steps.joinToString("; ") { "${label(it.path, current)} ${fmt(it.old)} → ${fmt(it.new)}" }
                parts += describe(applyLocked(changes, ProfileSource.AUTO_TUNE, summary, null, now), current)
            }
        }
        settings.setMarker(KEY_LAST_LOCAL, now.toEpochMilli().toString())
        return parts.joinToString(" · ").ifBlank { null }
    }

    /** Mid-day AI review once [LearningSettings.aiMinNewLessons] new clean lessons arrived. */
    private suspend fun maybeAiReview(now: Instant, force: Boolean): String? {
        if (!ai.reachable()) return null
        return aiLock.withLock {
            val profile = profiles.current().profile
            val s = profile.learning
            val last = settings.marker(KEY_LAST_AI)?.toLongOrNull()
            val spaced = last == null || now.toEpochMilli() - last >= (s.aiMinHoursBetween * HOUR_MS).toLong()
            val lessons = lessons(now, profile)
            val reviewed = settings.marker(KEY_AI_SEEN)?.split(',')?.toSet().orEmpty()
            val freshClean = lessons.count { it.clean && it.doseId !in reviewed }
            if (!force && (!spaced || freshClean < s.aiMinNewLessons)) return@withLock null
            aiReview("incremental", now, lessons, windowEnd = now, analysisProfile = profile).message
        }
    }

    /** The nightly AI review (called by [NightlyJobs] at the 1 am reset). */
    suspend fun nightlyReview(now: Instant, windowEnd: Instant, analysisProfile: Profile): LearnResult =
        aiLock.withLock { aiReview("nightly", now, lessons(now), windowEnd, analysisProfile) }

    private suspend fun aiReview(mode: String, now: Instant, lessons: List<Lesson>, windowEnd: Instant, analysisProfile: Profile): LearnResult {
        val current = profiles.current().profile
        val payload = payloads.build(analysisProfile, windowEnd, zone(), now, mode, learningContext(lessons, current, now))
        var out = ai.call("learn_cycle", payload, null, "learn_cycle ($mode)", AiClient.LEARN_TIMEOUT_MS)
        if (out is AiOutcome.Failed && out.retryWith != null) {
            out = ai.call("learn_cycle", payload, null, "learn_cycle ($mode, retry ${out.retryWith})", AiClient.LEARN_TIMEOUT_MS, preferenceWire = out.retryWith)
        }
        settings.setMarker(KEY_LAST_AI, now.toEpochMilli().toString())
        val outcome = out
        val result = when (outcome) {
            is AiOutcome.Failed -> {
                journal("error", "AI review ($mode) failed: ${outcome.reason}", aiCallId = outcome.callId, now = now)
                LearnResult("failed", "Learn cycle couldn't run (${outcome.reason}). The current profile carries on.", at = now.toEpochMilli())
            }
            is AiOutcome.Ok -> {
                val dto = runCatching { AppJson.decodeFromJsonElement(LearnCycleDto.serializer(), outcome.result) }.getOrNull()
                if (dto == null) {
                    journal("error", "AI review ($mode) returned something unreadable", aiCallId = outcome.callId, now = now)
                    LearnResult("failed", "Learn cycle result couldn't be read. The current profile carries on.", at = now.toEpochMilli())
                } else {
                    settings.setMarker(KEY_AI_SEEN, lessons.joinToString(",") { it.doseId })
                    val changes = LearnMapping.changes(dto, current)
                    journal(
                        "ai_review", "AI review ($mode): ${dto.summary.ifBlank { "no summary" }}",
                        buildJsonObject {
                            put("mode", mode)
                            put("summary", dto.summary)
                            putJsonArray("observations") { dto.observations.forEach { add(JsonPrimitive(it)) } }
                            put("changes_proposed", changes.size)
                        },
                        aiCallId = outcome.callId, now = now,
                    )
                    if (changes.isEmpty()) {
                        LearnResult("no_changes", dto.summary.ifBlank { "No changes proposed." }, observations = dto.observations, at = now.toEpochMilli())
                    } else {
                        val o = lock.withLock { applyLocked(changes, ProfileSource.LEARN_CYCLE, dto.summary.ifBlank { "AI review ($mode)" }, outcome.callId, now) }
                        LearnResult(
                            status = if (o.pendingVersion != null) "done" else "applied",
                            message = dto.summary.ifBlank { describe(o, current) },
                            versionId = o.pendingVersion?.id,
                            observations = dto.observations,
                            at = now.toEpochMilli(),
                        )
                    }
                }
            }
        }
        onWrite()
        return result
    }

    // --- applying, tracking, reverting -----------------------------------------------------------

    /** Applies what autonomy allows now, proposes the rest for review. */
    suspend fun apply(changes: List<ProfileChange>, source: String, summary: String, aiCallId: String?, now: Instant = Instant.now()): ApplyOutcome =
        lock.withLock { applyLocked(changes, source, summary, aiCallId, now) }

    private suspend fun applyLocked(changes: List<ProfileChange>, source: String, summary: String, aiCallId: String?, now: Instant): ApplyOutcome {
        val autonomy = settings.current().learningAutonomy
        val current = profiles.current().profile
        // Keep the changes that apply and leave the dose math valid, each with its true old value.
        var probe = current
        val valid = mutableListOf<ProfileChange>()
        for (ch in changes) {
            val next = ProfilePatch.apply(probe, listOf(ch)).getOrNull() ?: continue
            if (ProfileValidation.problems(next).isNotEmpty()) {
                AppLog.w("Learn", "dropped ${ch.path} → ${show(ch.new)}: would break the dose math")
                continue
            }
            valid += ch.copy(old = ProfilePatch.get(probe, ch.path))
            probe = next
        }
        val auto = valid.filter { allowsAuto(autonomy, it.path) }
        val ask = valid.filterNot { allowsAuto(autonomy, it.path) }

        var appliedVersion: ProfileVersionEntity? = null
        if (auto.isNotEmpty()) {
            val annotated = auto.map { it.copy(decision = "auto") }
            val updated = ProfilePatch.apply(current, annotated).getOrThrow()
            val v = profiles.saveVersion(updated, source, ProfileStatus.ACCEPTED, "Learned: $summary".take(300), changes = annotated, aiCallId = aiCallId, now = now)
            annotated.forEach { trackApplied(it, source, v.id, aiCallId, now) }
            appliedVersion = v
            Notifications.post(
                context, Notifications.ID_LEARNING, Notifications.CHANNEL_REPORTS, "Profile learned",
                annotated.joinToString(" · ") { "${label(it.path, updated)} ${show(it.old)} → ${show(it.new)}" }.take(200),
                destination = "learning", silent = true,
            )
        }
        var pendingVersion: ProfileVersionEntity? = null
        if (ask.isNotEmpty()) {
            val base = profiles.current().profile
            val proposed = ProfilePatch.apply(base, ask).getOrDefault(base)
            val v = profiles.saveVersion(proposed, source, ProfileStatus.PENDING, summary.take(300), changes = ask, aiCallId = aiCallId, now = now)
            journal(
                "proposed", "Waiting for your review: " + ask.joinToString { "${label(it.path, base)} ${show(it.old)} → ${show(it.new)}" },
                buildJsonObject { put("changes", AppJson.encodeToJsonElement(ListSerializer(ProfileChange.serializer()), ask)) },
                profileVersionId = v.id, aiCallId = aiCallId, now = now,
            )
            pendingVersion = v
            Notifications.post(
                context, Notifications.ID_LEARNING, Notifications.CHANNEL_REPORTS, "Learned changes to review",
                ask.joinToString(" · ") { "${label(it.path, base)} ${show(it.old)} → ${show(it.new)}" }.take(200),
                destination = "review/${v.id}",
            )
        }
        return ApplyOutcome(auto, appliedVersion, ask, pendingVersion)
    }

    /** Danny accepted learned changes from a review: watch them like auto-applied ones. */
    suspend fun trackReviewed(version: ProfileVersionEntity, applied: List<ProfileChange>, now: Instant = Instant.now()) = lock.withLock {
        applied.forEach { trackApplied(it, version.source, version.id, version.aiCallId, now) }
    }

    private suspend fun trackApplied(ch: ProfileChange, source: String, versionId: String, aiCallId: String?, now: Instant) {
        val d = AppliedDetails(
            path = ch.path, old = ch.old, new = ch.new, evidence = ch.reason.orEmpty(), source = source,
            moreInsulin = ChangeEvaluator.moreInsulin(ch.path, ch.old.numberOrNull(), ch.new.numberOrNull()),
        )
        journal(
            "applied", "${label(ch.path)} ${show(ch.old)} → ${show(ch.new)}" + (ch.reason?.let { " — $it" } ?: ""),
            AppJson.encodeToJsonElement(AppliedDetails.serializer(), d).jsonObject,
            profileVersionId = versionId, aiCallId = aiCallId, now = now,
        )
    }

    private suspend fun revert(entry: LearningLogEntity, d: AppliedDetails, eval: Evaluation, now: Instant): String? {
        val profile = profiles.current().profile
        val name = label(d.path, profile)
        if (ProfilePatch.get(profile, d.path) != d.new) {
            journal("kept", "$name changed again since; no longer tracked", evalJson(eval), supersedes = entry.id, now = now)
            return null
        }
        val change = ProfileChange(d.path, d.new, d.old ?: JsonNull, reason = "Reverted: ${eval.reason}")
        val restored = ProfilePatch.apply(profile, listOf(change)).getOrNull()?.takeIf { ProfileValidation.problems(it).isEmpty() }
        if (restored == null) {
            journal("kept", "$name couldn't be reverted cleanly; kept", evalJson(eval), supersedes = entry.id, now = now)
            return null
        }
        if (!allowsAuto(settings.current().learningAutonomy, d.path)) {
            val v = profiles.saveVersion(restored, ProfileSource.AUTO_REVERT, ProfileStatus.PENDING, "Revert $name? ${eval.reason}".take(300), changes = listOf(change), now = now)
            journal("revert_proposed", "Proposed reverting $name ${show(d.new)} → ${show(d.old)}: ${eval.reason}", evalJson(eval), supersedes = entry.id, profileVersionId = v.id, now = now)
            Notifications.post(context, Notifications.ID_LEARNING, Notifications.CHANNEL_REPORTS, "Revert a learned change?", "$name: ${eval.reason}".take(200), destination = "review/${v.id}")
            return "proposed reverting $name"
        }
        val v = profiles.saveVersion(restored, ProfileSource.AUTO_REVERT, ProfileStatus.ACCEPTED, "Reverted $name: ${eval.reason}".take(300), changes = listOf(change.copy(decision = "auto")), now = now)
        journal("reverted", "Reverted $name ${show(d.new)} → ${show(d.old)}: ${eval.reason}", evalJson(eval), supersedes = entry.id, profileVersionId = v.id, now = now)
        Notifications.post(context, Notifications.ID_LEARNING, Notifications.CHANNEL_REPORTS, "Learned change reverted", "$name back to ${show(d.old)}: ${eval.reason}".take(200), destination = "learning")
        return "reverted $name"
    }

    /** Danny undoes a learned change (Learning screen / morning report). */
    suspend fun undo(entryId: String, now: Instant = Instant.now()): String = lock.withLock {
        val entry = db.learningLog().byId(entryId) ?: return@withLock "Not found"
        val d = details(entry) ?: return@withLock "Nothing to undo"
        if (db.learningLog().openChanges().none { it.id == entryId }) return@withLock "Already closed"
        val profile = profiles.current().profile
        val name = label(d.path, profile)
        val change = ProfileChange(d.path, ProfilePatch.get(profile, d.path), d.old ?: JsonNull, reason = "Undone by Danny")
        val restored = ProfilePatch.apply(profile, listOf(change)).getOrThrow()
        val problems = ProfileValidation.problems(restored)
        require(problems.isEmpty()) { "Can't undo $name: " + problems.joinToString("; ") }
        val v = profiles.saveVersion(restored, ProfileSource.MANUAL, ProfileStatus.ACCEPTED, "Undid learned change: $name", changes = listOf(change), now = now)
        journal("undone", "Undid $name → ${show(d.old)}", supersedes = entryId, profileVersionId = v.id, now = now)
        "Undone: $name back to ${show(d.old)}"
    }

    // --- status ----------------------------------------------------------------------------------

    suspend fun status(now: Instant = Instant.now()): LearningStatus {
        val profile = profiles.current().profile
        val lessons = lessons(now, profile)
        val changed = lastChanged(now)
        val open = db.learningLog().openChanges().mapNotNull { e ->
            details(e)?.let { d -> OpenChange(e, d, ChangeEvaluator.evaluate(TrackedChange(e.id, d.path, e.recordedAt, d.moreInsulin), lessons, profile.learning)) }
        }
        return LearningStatus(
            autonomy = settings.current().learningAutonomy,
            lessons = lessons,
            evidence = evidence(lessons, profile, changed, now),
            underEvaluation = open,
            lastLocalRunAt = settings.marker(KEY_LAST_LOCAL)?.toLongOrNull(),
            lastAiReviewAt = settings.marker(KEY_LAST_AI)?.toLongOrNull(),
        )
    }

    /** Learned changes applied since [from] (morning report). */
    suspend fun appliedSince(from: Instant): List<LearningLogEntity> =
        db.learningLog().since(from.toEpochMilli()).filter { it.kind == "applied" || it.kind == "reverted" }

    suspend fun openChangeIds(): Set<String> = db.learningLog().openChanges().map { it.id }.toSet()

    private fun evidence(lessons: List<Lesson>, profile: Profile, changed: Map<String, Long>, now: Instant): List<EvidenceLine> {
        val s = profile.learning
        val window = lessons.filter { it.clean && it.atMillis >= now.toEpochMilli() - s.lookbackDays * DAY_MS }
        fun line(path: String, current: Double, values: List<Pair<Long, Double>>): EvidenceLine {
            val usable = values.filter { it.second.isFinite() && it.second > 0 }
            val since = changed[path] ?: Long.MIN_VALUE
            return EvidenceLine(
                path, label(path, profile), current,
                implied = usable.map { it.second }.sorted().takeIf { it.isNotEmpty() }?.let { Tuner.quantile(it, 0.5) },
                lessons = usable.size, freshSinceChange = usable.count { it.first > since }, needed = s.minLessons,
            )
        }
        return buildList {
            add(line("dose.icr", profile.dose.icr, window.mapNotNull { l -> l.impliedIcr?.let { l.atMillis to it } }))
            add(line("dose.isf", profile.dose.isf, window.mapNotNull { l -> l.impliedIsf?.let { l.atMillis to it } }))
            profile.factors.filter { it.kind == FactorKind.UNITS_PER_EVENT && it.unitsPerEvent != null }.forEach { f ->
                add(line("factors.${f.id}.unitsPerEvent", f.unitsPerEvent!!, window.filter { it.unitsFactorId == f.id }.mapNotNull { l -> l.impliedUnitsPerEvent?.let { l.atMillis to it } }))
            }
        }
    }

    /** What the AI reviewer gets on top of the raw data: the lessons, the evidence, what's being judged. */
    private suspend fun learningContext(lessons: List<Lesson>, profile: Profile, now: Instant): JsonObject {
        val changed = lastChanged(now)
        val open = db.learningLog().openChanges()
        val recent = db.learningLog().since(now.minus(Duration.ofDays(14)).toEpochMilli())
            .filter { it.kind in setOf("applied", "kept", "reverted", "undone", "revert_proposed") }
        val autonomy = settings.current().learningAutonomy.wire
        val evidenceLines = evidence(lessons, profile, changed, now)
        // Everything is fetched above: no suspend calls inside the JSON builders (see M6 in DECISIONS).
        return buildJsonObject {
            put("autonomy", autonomy)
            putJsonArray("lessons") {
                lessons.forEach { l ->
                    addJsonObject {
                        put("at", isoOf(l.atMillis)); put("local_hour", l.localHour); put("kind", l.kind.name.lowercase())
                        put("carbs_g", l.carbsG); put("fat_g", l.fatG); put("protein_g", l.proteinG)
                        l.bgAtDose?.let { put("bg_at_dose", it) }
                        put("units_given", l.unitsGiven); put("units_proposed", round2(l.unitsProposed))
                        l.unitsNeeded?.let { put("units_needed", round2(it)) }
                        l.endBg?.let { put("end_bg", it) }; l.min4h?.let { put("min_4h", it) }; l.max4h?.let { put("max_4h", it) }
                        put("went_low", l.wentLow)
                        putJsonArray("factors") { l.factors.forEach { add(JsonPrimitive(it)) } }
                        put("clean", l.clean); l.excludedBecause?.let { put("excluded_because", it) }
                        l.impliedIcr?.let { put("implied_icr", round2(it)) }; l.impliedIsf?.let { put("implied_isf", round2(it)) }
                        l.impliedUnitsPerEvent?.let { put("implied_units_per_event", round2(it)); put("units_factor", l.unitsFactorId) }
                    }
                }
            }
            putJsonArray("evidence") {
                evidenceLines.forEach { e ->
                    addJsonObject {
                        put("path", e.path); put("current", e.current); e.implied?.let { put("implied_median", round2(it)) }
                        put("clean_lessons", e.lessons); put("new_since_last_change", e.freshSinceChange)
                    }
                }
            }
            putJsonArray("under_evaluation") {
                open.forEach { e ->
                    details(e)?.let { d ->
                        addJsonObject {
                            put("path", d.path); d.old?.let { put("old", it) }; d.new?.let { put("new", it) }
                            put("applied_at", isoOf(e.recordedAt)); put("source", d.source)
                        }
                    }
                }
            }
            putJsonArray("recent_journal") {
                recent.forEach { e -> addJsonObject { put("at", isoOf(e.recordedAt)); put("kind", e.kind); put("summary", e.summary) } }
            }
        }
    }

    // --- helpers -----------------------------------------------------------------------------------

    /** When each profile path last changed or was decided on (applied or rejected). */
    private suspend fun lastChanged(now: Instant): Map<String, Long> {
        val out = HashMap<String, Long>()
        db.profileVersions().between(now.minus(Duration.ofDays(120)).toEpochMilli(), now.toEpochMilli())
            .filter { it.status in ProfileStatus.APPLIED || it.status == ProfileStatus.REJECTED }
            .forEach { v -> v.changes().forEach { ch -> out[ch.path] = maxOf(out[ch.path] ?: Long.MIN_VALUE, v.recordedAt) } }
        return out
    }

    /** Paths that already have a learned proposal waiting for Danny (don't stack another). */
    private suspend fun pendingPaths(): Set<String> =
        db.profileVersions().pendingUndecided().flatMap { v -> v.changes().map { it.path } }.toSet()

    private fun allowsAuto(autonomy: LearningAutonomy, path: String): Boolean = when (autonomy) {
        LearningAutonomy.AUTO -> true
        LearningAutonomy.AUTO_FACTORS -> !isCore(path)
        LearningAutonomy.ASK -> false
    }

    private fun details(entry: LearningLogEntity): AppliedDetails? =
        runCatching { AppJson.decodeFromString(AppliedDetails.serializer(), entry.details) }.getOrNull()

    private suspend fun journal(
        kind: String,
        summary: String,
        details: JsonObject = JsonObject(emptyMap()),
        supersedes: String? = null,
        profileVersionId: String? = null,
        aiCallId: String? = null,
        now: Instant,
    ) {
        val m = records.meta(now = now.toEpochMilli())
        db.learningLog().insert(
            LearningLogEntity(
                id = m.id, userId = m.userId, createdAt = m.createdAt, recordedAt = m.recordedAt, supersedesId = supersedes,
                kind = kind, summary = summary.take(500), details = details.toString(),
                profileVersionId = profileVersionId, aiCallId = aiCallId,
            ),
        )
        AppLog.i("Learn", "$kind: ${summary.take(200)}")
        onWrite()
    }

    private fun evalJson(e: Evaluation): JsonObject = AppJson.encodeToJsonElement(Evaluation.serializer(), e).jsonObject

    private fun lessonSummary(fresh: List<Lesson>): String {
        val clean = fresh.filter { it.clean }
        val byKind = clean.groupingBy { it.kind }.eachCount().entries.joinToString(", ") { "${it.value} ${kindLabel(it.key)}" }
        val err = clean.mapNotNull { it.errorUnits }.takeIf { it.isNotEmpty() }?.average()
        return buildString {
            append("${fresh.size} new lesson${plural(fresh.size)}")
            if (clean.isNotEmpty()) append(" ($byKind)")
            if (fresh.size > clean.size) append(", ${fresh.size - clean.size} confounded")
            err?.let { append(String.format(Locale.US, "; average %+.1f u vs what was needed", it)) }
            val lows = fresh.count { it.wentLow }
            if (lows > 0) append("; $lows went low")
        }
    }

    private fun describe(o: ApplyOutcome, profile: Profile): String = buildList {
        if (o.applied.isNotEmpty()) add("applied " + o.applied.joinToString { "${label(it.path, profile)} ${show(it.old)} → ${show(it.new)}" })
        if (o.pending.isNotEmpty()) add("proposed " + o.pending.joinToString { label(it.path, profile) })
    }.joinToString("; ")

    companion object {
        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 86_400_000L
        const val KEY_SEEN = "learning_seen_doses"
        const val KEY_AI_SEEN = "learning_ai_seen_doses"
        const val KEY_LAST_LOCAL = "learning_last_local"
        const val KEY_LAST_AI = "learning_last_ai"

        /** ICR, ISF, target, insulin curve: what "Automatic for factors" leaves to Danny. */
        fun isCore(path: String) = path.startsWith("dose.") || path.startsWith("iob.")

        fun label(path: String, profile: Profile? = null): String = when (path) {
            "dose.icr" -> "ICR"
            "dose.isf" -> "ISF"
            "dose.target" -> "Target"
            "dose.combinedCap" -> "Multiplier cap"
            "iob.peakMin" -> "Insulin peak"
            "iob.durationMin" -> "Insulin duration"
            "leadTime.baseMin" -> "Pre-bolus base"
            else -> Regex("^factors\\.([^.]+)\\.(.+)$").find(path)?.let { m ->
                val name = profile?.factor(m.groupValues[1])?.name ?: m.groupValues[1]
                val field = when (m.groupValues[2]) {
                    "unitsPerEvent" -> "units each"
                    "defaultWeight" -> "default weight"
                    else -> m.groupValues[2]
                }
                "$name $field"
            } ?: path
        }

        fun show(v: JsonElement?): String = when {
            v == null || v is JsonNull -> "—"
            v is JsonPrimitive && v.doubleOrNull != null -> fmt(v.doubleOrNull!!)
            v is JsonPrimitive -> v.content
            else -> v.toString().take(40)
        }

        fun fmt(x: Double): String = if (x == Math.floor(x) && kotlin.math.abs(x) < 1e6) x.toLong().toString() else String.format(Locale.US, "%.2f", x).trimEnd('0').trimEnd('.')

        private fun kindLabel(k: LessonKind) = when (k) {
            LessonKind.MEAL -> "meal"
            LessonKind.CORRECTION -> "correction"
            LessonKind.UNITS_PER_EVENT -> "caffeine"
            LessonKind.OTHER -> "other"
        }

        private fun plural(n: Int) = if (n == 1) "" else "s"
        private fun round2(x: Double) = Math.round(x * 100) / 100.0
        private fun JsonElement?.numberOrNull(): Double? = (this as? JsonPrimitive)?.doubleOrNull
    }
}
