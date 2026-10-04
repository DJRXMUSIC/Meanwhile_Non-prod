package app.meanwhile.ui.learning

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.meanwhile.data.db.LearningLogEntity
import app.meanwhile.data.learn.LearningEngine
import app.meanwhile.data.learn.LearningStatus
import app.meanwhile.data.settings.LearningAutonomy
import app.meanwhile.domain.learn.Verdict
import app.meanwhile.format.formatDateTime
import app.meanwhile.format.relativeTime
import app.meanwhile.ui.common.LocalAppContainer
import app.meanwhile.ui.common.ScreenScaffold
import app.meanwhile.ui.common.SectionCard
import app.meanwhile.ui.common.rememberSafeScope
import app.meanwhile.ui.profile.prettyJson
import app.meanwhile.ui.theme.LocalGlucoseColors
import kotlinx.coroutines.launch
import java.util.Locale

/** What Meanwhile learned, what it changed, and how each change turned out (1.3). */
@Composable
fun LearningScreen(onBack: () -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberSafeScope()
    var reload by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    val journal by c.db.learningLog().recentFlow(150).collectAsStateWithLifecycle(initialValue = emptyList())
    val status by produceState<LearningStatus?>(null, reload, journal.size) { value = c.learning.status() }
    val profileState by c.profiles.current.collectAsStateWithLifecycle(initialValue = null)
    val lookback = profileState?.profile?.learning?.lookbackDays ?: 14
    val ai = LocalGlucoseColors.current.aiProposed

    ScreenScaffold(title = "Learning", onBack = onBack) {
        Text(
            "Every dose's outcome is a lesson. Meanwhile tunes ICR, ISF and caffeine units from them on its own, " +
                "asks the AI for a deeper review every night and whenever new outcomes arrive, judges every change on " +
                "the outcomes that follow, and reverts it if they got worse.",
            style = MaterialTheme.typography.bodySmall,
        )

        val st = status
        SectionCard("Who decides") {
            val current = st?.autonomy
            LearningAutonomy.entries.forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    RadioButton(selected = current == a, onClick = {
                        scope.launch {
                            c.settings.update { it.copy(learningAutonomy = a) }
                            reload++
                        }
                    })
                    Column(Modifier.weight(1f)) {
                        Text(a.label, fontWeight = FontWeight.SemiBold)
                        Text(a.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text("Logging a dose is always yours.", style = MaterialTheme.typography.labelSmall)
        }

        SectionCard("Now") {
            if (st == null) {
                Text("Loading…")
            } else {
                val clean = st.lessons.count { it.clean }
                Text("${st.lessons.size} lessons in the last $lookback days ($clean clean).")
                Text(
                    "Last check " + (st.lastLocalRunAt?.let { relativeTime(it) } ?: "not yet") +
                        " · last AI review " + (st.lastAiReviewAt?.let { relativeTime(it) } ?: "not yet"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !running, onClick = {
                    running = true
                    message = "Learning…"
                    scope.launch {
                        try {
                            message = c.learning.runNow()
                        } finally {
                            running = false
                            reload++
                        }
                    }
                }) { Text(if (running) "Learning…" else "Learn now") }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)) }
            }
        }

        if (st != null) {
            SectionCard("What the outcomes say") {
                st.evidence.forEach { e ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${e.label}: ${LearningEngine.fmt(e.current)}", fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                e.implied == null -> "No clean lessons yet."
                                else -> String.format(Locale.US, "%d clean lesson%s point to %s", e.lessons, if (e.lessons == 1) "" else "s", LearningEngine.fmt(e.implied)) +
                                    if (e.freshSinceChange < e.needed) " · next step after ${e.needed - e.freshSinceChange} more" else " · ready"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            SectionCard("Being judged") {
                if (st.underEvaluation.isEmpty()) Text("No learned changes waiting for a verdict.")
                st.underEvaluation.forEach { o ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(o.entry.summary.substringBefore(" — "), fontWeight = FontWeight.SemiBold, color = ai)
                        Text(
                            "Applied ${formatDateTime(o.entry.recordedAt)} by ${sourceName(o.details.source)} · " +
                                when (o.evaluation.verdict) {
                                    Verdict.PENDING -> o.evaluation.reason
                                    else -> "verdict at the next check: ${o.evaluation.reason}"
                                },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (o.details.evidence.isNotBlank()) Text(o.details.evidence, style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = {
                            scope.launch {
                                message = c.learning.undo(o.entry.id)
                                reload++
                            }
                        }) { Text("Undo") }
                    }
                    HorizontalDivider()
                }
            }
        }

        SectionCard("Journal") {
            if (journal.isEmpty()) Text("Nothing learned yet — lessons start 4 hours after the first logged dose.")
            journal.forEach { JournalRow(it) }
        }
    }
}

@Composable
private fun JournalRow(e: LearningLogEntity) {
    var open by remember(e.id) { mutableStateOf(false) }
    val color = when (e.kind) {
        "reverted", "error", "revert_proposed" -> MaterialTheme.colorScheme.error
        "applied", "kept" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { open = !open },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("${formatDateTime(e.recordedAt)} · ${kindName(e.kind)}", style = MaterialTheme.typography.labelSmall, color = color)
        Text(e.summary, style = MaterialTheme.typography.bodySmall)
        if (open && e.details != "{}") {
            SelectionContainer {
                Text(prettyJson(e.details), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    HorizontalDivider()
}

private fun kindName(kind: String) = when (kind) {
    "lessons" -> "new lessons"
    "ai_review" -> "AI review"
    "applied" -> "changed"
    "proposed" -> "waiting for review"
    "kept" -> "kept"
    "reverted" -> "reverted"
    "revert_proposed" -> "revert proposed"
    "undone" -> "undone by you"
    "error" -> "problem"
    else -> kind
}

private fun sourceName(source: String) = when (source) {
    "auto_tune" -> "local tuning"
    "learn_cycle" -> "AI review"
    "auto_revert" -> "auto-revert"
    else -> source
}
