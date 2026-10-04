package app.meanwhile.ui.morning

import app.meanwhile.ui.common.rememberSafeScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.meanwhile.data.cgm.toDomain
import app.meanwhile.data.db.ProfileVersionEntity
import app.meanwhile.data.input.FactorUpdater
import app.meanwhile.data.learn.LearnResult
import app.meanwhile.data.learn.OvernightResultView
import app.meanwhile.data.learn.ProposalFollow
import app.meanwhile.data.profile.ProfileSource
import app.meanwhile.data.profile.ProfileState
import app.meanwhile.domain.profile.Profile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.meanwhile.data.profile.changes
import app.meanwhile.domain.stats.GlucoseStats
import app.meanwhile.domain.stats.GlucoseSummary
import app.meanwhile.format.fmt
import app.meanwhile.ui.common.LocalAppContainer
import app.meanwhile.ui.common.ScreenScaffold
import app.meanwhile.ui.common.SectionCard
import app.meanwhile.ui.review.ChangeReviewList
import app.meanwhile.ui.review.ReviewState
import app.meanwhile.ui.theme.LocalGlucoseColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private data class Glance(
    val stats: GlucoseSummary,
    val rapidDoses: Int,
    val longDoses: Int,
    val followed: Int,
    val overridden: Int,
    val notLogged: Int,
)

/** Spec §11.3: shown on the first open after 1 am. */
@Composable
fun MorningReportScreen(onDone: () -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberSafeScope()
    val ai = LocalGlucoseColors.current.aiProposed
    var date by remember { mutableStateOf<LocalDate?>(null) }
    var running by remember { mutableStateOf(false) }
    var learn by remember { mutableStateOf<LearnResult?>(null) }
    var pending by remember { mutableStateOf<ProfileVersionEntity?>(null) }
    var glance by remember { mutableStateOf<Glance?>(null) }
    var overnight by remember { mutableStateOf<OvernightResultView?>(null) }
    var sleep by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var learned by remember { mutableStateOf<List<app.meanwhile.data.db.LearningLogEntity>>(emptyList()) }
    var openIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val profileState by c.profiles.current.collectAsStateWithLifecycle(initialValue = ProfileState(Profile(), null))

    LaunchedEffect(reload) {
        val zone = ZoneId.systemDefault()
        val (d, reset) = c.nightly.nightOf()
        date = d
        // Catch-up: run the learn cycle before showing proposals if 1 am didn't complete (spec §11.1).
        if (!c.nightly.learnDone(d)) {
            running = true
            try {
                c.nightly.learnCycleIfNeeded()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Learn cycle failed: ${e.message ?: e::class.java.simpleName}"
            } finally {
                running = false
            }
        }
        learn = c.nightly.learnResult(d)
        pending = c.nightly.pendingProposal(d)
        // What learning changed on its own since the reset (auto-applied or reverted).
        learned = c.learning.appliedSince(reset.minus(java.time.Duration.ofHours(24)))
        openIds = c.learning.openChangeIds()
        overnight = c.nightly.overnightIfNeeded()
        sleep = c.profiles.current().profile.active
            .lastOrNull { it.factorId == "F8" && it.startedAt >= reset.toEpochMilli() }?.preset
        val y = d.minusDays(1)
        val from = y.atStartOfDay(zone).toInstant()
        val to = d.atStartOfDay(zone).toInstant()
        val readings = c.db.cgm().between(from.toEpochMilli(), to.toEpochMilli()).map { it.toDomain() }
        val doses = c.db.doses().between(from.toEpochMilli(), to.toEpochMilli() - 1)
        val proposals = c.db.proposals().between(from.toEpochMilli(), to.toEpochMilli() - 1)
        val byProposal = c.db.doses().since(from.toEpochMilli()).filter { it.proposalId != null }.groupBy { it.proposalId!! }
        val states = proposals.map { ProposalFollow.classify(it.finalUnits, byProposal[it.id].orEmpty()) }
        glance = Glance(
            GlucoseStats.summarize(readings, from, to),
            rapidDoses = doses.count { it.insulin == "rapid" && it.units > 0 },
            longDoses = doses.count { it.insulin == "long" },
            followed = states.count { it == ProposalFollow.FOLLOWED },
            overridden = states.count { it == ProposalFollow.OVERRIDDEN },
            notLogged = states.count { it == ProposalFollow.NOT_LOGGED },
        )
    }

    ScreenScaffold(title = "Morning report", onBack = onDone) {
        date?.let { Text("Night of ${it.minusDays(1)} → $it", style = MaterialTheme.typography.labelMedium) }

        SectionCard("1 · How did you sleep?") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("good" to "Good", "ok" to "OK", "poor" to "Poor").forEach { (preset, label) ->
                    FilterChip(
                        selected = sleep == preset,
                        onClick = {
                            scope.launch {
                                c.factorUpdater.apply(
                                    listOf(FactorUpdater.Request("F8", preset = preset, at = Instant.now())),
                                    inputId = null, eventSource = "morning_report", versionSource = ProfileSource.SLEEP_CHECKIN,
                                    summary = "Sleep check-in: $label",
                                )
                                sleep = preset
                            }
                        },
                        label = { Text(label) },
                    )
                }
            }
            sleep?.let { s ->
                val w = profileState.profile.factor("F8")?.presets?.get(s)
                Text("Sleep factor ${w?.let { fmt(it) } ?: ""} applied until 1 am.", style = MaterialTheme.typography.bodySmall)
            }
        }

        SectionCard("2 · Proposed changes from the learn cycle") {
            when {
                running -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator()
                    Text("Running last night's learn cycle… (up to 2 min)")
                }
                learn == null -> Text("The learn cycle hasn't run yet.")
                learn?.status == "failed" -> {
                    Text(learn?.message ?: "", color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = {
                        scope.launch {
                            running = true
                            try {
                                c.nightly.learnCycleIfNeeded(force = true)
                            } finally {
                                running = false
                                reload++
                            }
                        }
                    }) { Text("Retry now") }
                }
                else -> {
                    Text(learn?.message ?: "", color = ai)
                    learn?.observations?.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    val p = pending
                    if (learned.isNotEmpty()) {
                        Text("Changed automatically — each one is judged on the next outcomes and reverted if they get worse:", style = MaterialTheme.typography.bodySmall)
                        learned.forEach { e ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(e.summary.substringBefore(" — "), color = if (e.kind == "reverted") MaterialTheme.colorScheme.error else ai, modifier = Modifier.weight(1f))
                                if (e.id in openIds) {
                                    TextButton(onClick = {
                                        scope.launch {
                                            message = c.learning.undo(e.id)
                                            openIds = c.learning.openChangeIds()
                                        }
                                    }) { Text("Undo") }
                                }
                            }
                        }
                    }
                    if (p == null) {
                        if (learned.isEmpty()) Text(if (learn?.status == "no_changes") "No changes proposed." else "Nothing waiting for review.")
                    } else {
                        val state = remember(p.id) { ReviewState(p.changes()) }
                        ChangeReviewList(state)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { state.setAll("accepted") }) { Text("Accept all") }
                            OutlinedButton(onClick = { state.setAll("rejected") }) { Text("Reject all") }
                        }
                        Button(onClick = {
                            val decisions = state.toDecisions()
                            if (decisions == null) {
                                message = "An edited value isn't valid JSON."
                            } else {
                                scope.launch {
                                    c.review.decide(p, decisions).fold(
                                        onSuccess = { v ->
                                            message = "Saved v${v.version} (${v.status})"
                                            pending = null
                                        },
                                        onFailure = { message = "Couldn't apply: ${it.message}" },
                                    )
                                }
                            }
                        }) { Text("Apply decisions") }
                    }
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }

        SectionCard("3 · Yesterday at a glance") {
            val g = glance
            if (g == null) {
                Text("…")
            } else {
                Text("Time in range ${fmt(g.stats.timeInRangePct, 0)}%", fontWeight = FontWeight.SemiBold, color = LocalGlucoseColors.current.inRange)
                Text("Below 70: ${fmt(g.stats.timeBelow70Pct, 1)}% · Above 180: ${fmt(g.stats.timeAbove180Pct, 0)}%")
                g.stats.meanMgDl?.let { Text("Mean ${fmt(it, 0)} mg/dL · ${g.stats.readings} readings") }
                Text("Doses: ${g.rapidDoses} rapid, ${g.longDoses} long-acting")
                Text("Proposals: ${g.followed} followed, ${g.overridden} overridden, ${g.notLogged} not logged")
            }
            overnight?.let { o ->
                Text(
                    "Overnight (10 pm–6 am): ${fmt(o.hours, 1)} h above 180" + if (o.weight > 1.0) " → F11 ${fmt(o.weight)} until 1 am" else " → no F11",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Button(onClick = {
            scope.launch {
                date?.let { c.nightly.markMorningSeen(it) }
                onDone()
            }
        }) { Text("Done") }
        TextButton(onClick = onDone) { Text("Not now (show again next time)") }
    }
}
