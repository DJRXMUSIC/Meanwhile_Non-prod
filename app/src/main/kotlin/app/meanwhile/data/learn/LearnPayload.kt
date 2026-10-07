package app.meanwhile.data.learn

import app.meanwhile.data.cgm.toDomain
import app.meanwhile.data.db.AppDatabase
import app.meanwhile.data.json.AppJson
import app.meanwhile.data.json.isoOf
import app.meanwhile.domain.profile.Profile
import app.meanwhile.domain.profile.ProfileJson
import app.meanwhile.domain.stats.GlucoseStats
import app.meanwhile.domain.stats.GlucoseSummary
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Builds the learn-cycle payload (spec §11.1 step 2) from the local DB: the 24 h before [windowEnd]
 * in detail, a 14-day summary, and (1.3) the learning context — lessons, evidence, changes under
 * evaluation and recent verdicts — so every review builds on what was already learned.
 */
class LearnPayload(private val db: AppDatabase) {

    suspend fun build(
        profile: Profile,
        windowEnd: Instant,
        zone: ZoneId,
        now: Instant,
        mode: String = "nightly",
        learning: JsonObject? = null,
    ): JsonObject {
        val dayStart = windowEnd.minus(Duration.ofHours(24))
        val start14 = windowEnd.minus(Duration.ofDays(14))
        val readings14 = db.cgm().between(start14.toEpochMilli(), windowEnd.toEpochMilli()).map { it.toDomain() }
        val readings24 = readings14.filter { !it.timestamp.isBefore(dayStart) }
        val doses14 = db.doses().effectiveBetween(start14.toEpochMilli(), windowEnd.toEpochMilli())
        val meals14 = db.meals().between(start14.toEpochMilli(), windowEnd.toEpochMilli())
        val events14 = db.factorEvents().between(start14.toEpochMilli(), windowEnd.toEpochMilli())
        val proposals14 = db.proposals().between(start14.toEpochMilli(), windowEnd.toEpochMilli())
        val outcomes = db.outcomes().since(start14.toEpochMilli()).associateBy { it.doseId }
        val dosesByProposal = doses14.filter { it.proposalId != null }.groupBy { it.proposalId!! }
        val dayStartMs = dayStart.toEpochMilli()

        fun followed(proposalId: String, finalUnits: Int): String =
            ProposalFollow.classify(finalUnits, dosesByProposal[proposalId].orEmpty())

        return buildJsonObject {
            put("mode", mode)
            put("now", isoOf(now.toEpochMilli()))
            put("timezone", zone.id)
            learning?.let { put("learning", it) }
            put("window_end", isoOf(windowEnd.toEpochMilli()))
            put("profile", ProfileJson.tree(profile))
            putJsonObject("last_24h") {
                putJsonArray("cgm") { readings24.forEach { r -> addJsonObject { put("t", isoOf(r.timestamp.toEpochMilli())); put("mg", r.mgDl) } } }
                putJsonArray("meals") {
                    meals14.filter { it.recordedAt >= dayStartMs }.forEach { m ->
                        addJsonObject {
                            put("at", isoOf(m.recordedAt)); put("description", m.description); put("carbs_g", m.carbsG)
                            put("fat_g", m.fatG); put("protein_g", m.proteinG); put("liquid_or_sugary", m.liquidOrSugary); put("is_estimate", m.isEstimate)
                        }
                    }
                }
                putJsonArray("factor_events") {
                    events14.filter { it.recordedAt >= dayStartMs }.forEach { e ->
                        addJsonObject {
                            put("at", isoOf(e.recordedAt)); put("factor_id", e.factorId); put("action", e.action); put("source", e.source)
                            e.weight?.let { put("weight", it) }; e.windowMinutes?.let { put("window_minutes", it) }; e.unitsAdd?.let { put("units_add", it) }
                            put("details", AppJson.parseToJsonElement(e.details))
                        }
                    }
                }
                putJsonArray("doses") {
                    doses14.filter { it.givenAt >= dayStartMs }.forEach { d ->
                        addJsonObject {
                            put("at", isoOf(d.givenAt)); put("insulin", d.insulin); put("units", d.units)
                            d.proposedUnits?.let { put("proposed_units", it) }; d.splitPart?.let { put("split_part", it) }
                            d.overrideReason?.let { put("override_reason", it) }
                            outcomes[d.id]?.let { o ->
                                putJsonObject("outcome") {
                                    o.bg2h?.let { put("bg_2h", it) }; o.bg3h?.let { put("bg_3h", it) }; o.bg4h?.let { put("bg_4h", it) }
                                    o.min4h?.let { put("min_4h", it) }; o.max4h?.let { put("max_4h", it) }
                                }
                            }
                        }
                    }
                }
                putJsonArray("proposals") {
                    proposals14.filter { it.recordedAt >= dayStartMs }.forEach { p ->
                        addJsonObject {
                            put("at", isoOf(p.recordedAt)); put("final_units", p.finalUnits); p.leadTimeMin?.let { put("lead_time_min", it) }
                            put("input", AppJson.parseToJsonElement(p.inputSnapshot)); put("breakdown", AppJson.parseToJsonElement(p.breakdown))
                            put("followed", followed(p.id, p.finalUnits))
                            put("doses_given", buildJsonArray { dosesByProposal[p.id].orEmpty().forEach { d -> addJsonObject { put("units", d.units); d.overrideReason?.let { put("override_reason", it) } } } })
                        }
                    }
                }
                put("stats", AppJson.encodeToJsonElement(GlucoseSummary.serializer(), GlucoseStats.summarize(readings24, dayStart, windowEnd)))
            }
            putJsonObject("summary_14d") {
                put("stats", AppJson.encodeToJsonElement(GlucoseSummary.serializer(), GlucoseStats.summarize(readings14, start14, windowEnd)))
                putJsonArray("daily") {
                    var day = LocalDate.ofInstant(start14, zone)
                    val last = LocalDate.ofInstant(windowEnd, zone)
                    while (!day.isAfter(last)) {
                        val from = day.atStartOfDay(zone).toInstant()
                        val to = day.plusDays(1).atStartOfDay(zone).toInstant()
                        val st = GlucoseStats.summarize(readings14, from, to)
                        val dayDoses = doses14.filter { it.givenAt >= from.toEpochMilli() && it.givenAt < to.toEpochMilli() }
                        addJsonObject {
                            put("date", day.toString()); put("tir_pct", st.timeInRangePct); put("below70_pct", st.timeBelow70Pct)
                            put("above180_pct", st.timeAbove180Pct); st.meanMgDl?.let { put("mean", it) }; put("coverage_min", st.coveredMinutes)
                            put("rapid_units", dayDoses.filter { it.insulin == "rapid" }.sumOf { it.units })
                            put("long_units", dayDoses.filter { it.insulin == "long" }.sumOf { it.units })
                            put("carbs_g", meals14.filter { it.recordedAt >= from.toEpochMilli() && it.recordedAt < to.toEpochMilli() }.sumOf { it.carbsG })
                        }
                        day = day.plusDays(1)
                    }
                }
                putJsonArray("hourly_mean_mg") {
                    val byHour = readings14.groupBy { it.timestamp.atZone(zone).hour }
                    for (h in 0..23) add(kotlinx.serialization.json.JsonPrimitive(byHour[h]?.map { it.mgDl }?.average()))
                }
                putJsonObject("proposals") {
                    val states = proposals14.map { followed(it.id, it.finalUnits) }
                    put("count", states.size); put("followed", states.count { it == "followed" })
                    put("overridden", states.count { it == "overridden" }); put("not_logged", states.count { it == "not_logged" })
                }
                putJsonArray("override_reasons") { doses14.mapNotNull { it.overrideReason }.filter { it != "skipped" }.distinct().take(50).forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
                putJsonArray("dose_outcomes") {
                    doses14.filter { it.insulin == "rapid" }.mapNotNull { d -> outcomes[d.id]?.let { d to it } }.forEach { (d, o) ->
                        addJsonObject {
                            put("at", isoOf(d.givenAt)); put("units", d.units); d.proposedUnits?.let { put("proposed_units", it) }
                            o.bg2h?.let { put("bg_2h", it) }; o.bg3h?.let { put("bg_3h", it) }; o.bg4h?.let { put("bg_4h", it) }
                            o.min4h?.let { put("min_4h", it) }; o.max4h?.let { put("max_4h", it) }
                        }
                    }
                }
            }
        }
    }
}
