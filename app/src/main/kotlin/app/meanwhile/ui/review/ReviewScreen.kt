package app.meanwhile.ui.review

import app.meanwhile.ui.common.rememberSafeScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.meanwhile.data.ai.ChangeDecision
import app.meanwhile.data.db.ProfileVersionEntity
import app.meanwhile.data.profile.ProfileStatus
import app.meanwhile.data.profile.changes
import app.meanwhile.domain.profile.ProfileChange
import app.meanwhile.domain.profile.display
import app.meanwhile.format.formatDateTime
import app.meanwhile.ui.common.LocalAppContainer
import app.meanwhile.ui.common.ScreenScaffold
import app.meanwhile.ui.common.SectionCard
import app.meanwhile.ui.main.sourceLabel
import app.meanwhile.ui.theme.LocalGlucoseColors
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Per-change decision state for a list of proposed changes. */
class ReviewState(val changes: List<ProfileChange>) {
    val decisions: SnapshotStateList<String> = mutableStateListOf(*Array(changes.size) { "accepted" })
    val edits: SnapshotStateList<String> = mutableStateListOf(*changes.map { it.new.display() }.toTypedArray())

    fun setAll(decision: String) {
        for (i in decisions.indices) decisions[i] = decision
    }

    /** Null when an edited value isn't valid JSON. */
    fun toDecisions(): List<ChangeDecision>? = changes.mapIndexed { i, ch ->
        val d = decisions[i]
        val edited = if (d == "edited") runCatching { Json.parseToJsonElement(edits[i]) }.getOrNull() ?: return null else null
        ChangeDecision(ch, d, edited)
    }
}

fun humanPath(path: String): String = when (path) {
    "dose.icr" -> "ICR (g per unit)"
    "dose.isf" -> "ISF (mg/dL per unit)"
    "dose.target" -> "Target BG"
    "dose.combinedCap" -> "Combined multiplier cap"
    "leadTime.baseMin" -> "Lead time base (min)"
    "iob.peakMin" -> "IOB peak (min)"
    "iob.durationMin" -> "IOB duration (min)"
    "meal.kFatPerG" -> "Fat weight per g (K_FAT)"
    "meal.kProteinPerG" -> "Protein weight per g (K_PROTEIN)"
    else -> when {
        path.startsWith("active.") -> "Activate ${path.removePrefix("active.")}"
        path.startsWith("factors.") -> "Factor ${path.removePrefix("factors.")}"
        else -> path
    }
}

@Composable
fun ChangeReviewList(state: ReviewState, enabled: Boolean = true) {
    val ai = LocalGlucoseColors.current.aiProposed
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.changes.forEachIndexed { i, ch ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(humanPath(ch.path), fontWeight = FontWeight.SemiBold)
                Text("${ch.old.display()} → ${ch.new.display()}", color = ai, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                ch.reason?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("accepted" to "Accept", "edited" to "Edit", "rejected" to "Reject").forEach { (value, label) ->
                        FilterChip(selected = state.decisions[i] == value, enabled = enabled, onClick = { state.decisions[i] = value }, label = { Text(label) })
                    }
                }
                if (state.decisions[i] == "edited") {
                    OutlinedTextField(
                        state.edits[i], { state.edits[i] = it }, label = { Text("New value (JSON)") },
                        modifier = Modifier.fillMaxWidth(), enabled = enabled,
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

/** Review a pending profile version (AI refinement or learn-cycle proposal). */
@Composable
fun ReviewScreen(id: String, onBack: () -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberSafeScope()
    val version by produceState<ProfileVersionEntity?>(null, id) { value = c.profiles.byId(id) }
    val decided by produceState(false, id) { value = c.db.profileVersions().supersededBy(id) != null }
    var message by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }

    ScreenScaffold(title = "Review proposal", onBack = onBack) {
        val v = version
        if (v == null) {
            Text("Loading…")
        } else {
            val state = remember(v.id) { ReviewState(v.changes()) }
            SectionCard("${sourceLabel(v.source)} · v${v.version}") {
                Text(formatDateTime(v.createdAt), style = MaterialTheme.typography.labelSmall)
                Text(v.summary, color = LocalGlucoseColors.current.aiProposed)
                if (v.status != ProfileStatus.PENDING || decided) Text("Already decided.", color = MaterialTheme.colorScheme.primary)
            }
            val canDecide = v.status == ProfileStatus.PENDING && !decided && !done
            SectionCard("Changes") {
                if (state.changes.isEmpty()) Text("No changes proposed.")
                ChangeReviewList(state, enabled = canDecide)
            }
            if (canDecide) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { state.setAll("accepted") }) { Text("Accept all") }
                    OutlinedButton(onClick = { state.setAll("rejected") }) { Text("Reject all") }
                }
                Button(onClick = {
                    val decisions = state.toDecisions()
                    if (decisions == null) {
                        message = "An edited value isn't valid JSON (numbers like 1.15 are fine)."
                    } else {
                        scope.launch {
                            c.review.decide(v, decisions).fold(
                                onSuccess = { saved ->
                                    done = true
                                    message = "Saved v${saved.version} (${saved.status})"
                                },
                                onFailure = { message = "Couldn't apply: ${it.message}" },
                            )
                        }
                    }
                }) { Text("Apply decisions") }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
    }
}
