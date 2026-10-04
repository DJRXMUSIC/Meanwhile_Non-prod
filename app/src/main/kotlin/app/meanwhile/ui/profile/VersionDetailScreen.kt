package app.meanwhile.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.meanwhile.data.db.ProfileVersionEntity
import app.meanwhile.data.profile.ProfileSource
import app.meanwhile.data.profile.ProfileState
import app.meanwhile.data.profile.ProfileStatus
import app.meanwhile.data.profile.changes
import app.meanwhile.data.profile.decodedProfile
import app.meanwhile.domain.profile.Profile
import app.meanwhile.domain.profile.display
import app.meanwhile.format.formatDateTime
import app.meanwhile.ui.common.LocalAppContainer
import app.meanwhile.ui.common.ScreenScaffold
import app.meanwhile.ui.common.SectionCard
import app.meanwhile.ui.main.sourceLabel
import kotlinx.coroutines.launch

@Composable
fun VersionDetailScreen(id: String, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val v by produceState<ProfileVersionEntity?>(null, id) { value = c.profiles.byId(id) }
    val current by c.profiles.current.collectAsStateWithLifecycle(initialValue = ProfileState(Profile(), null))
    var message by remember { mutableStateOf<String?>(null) }
    var showJson by remember { mutableStateOf(false) }

    ScreenScaffold(title = v?.let { "Profile v${it.version}" } ?: "Profile version", onBack = onBack) {
        val e = v
        if (e == null) {
            Text("Loading…")
        } else {
            SectionCard("v${e.version} · ${e.status}") {
                Text("${sourceLabel(e.source)} · created ${formatDateTime(e.createdAt)}")
                e.decidedAt?.let { Text("Decided ${formatDateTime(it)}") }
                if (e.summary.isNotBlank()) Text(e.summary)
                if (e.id == current.version?.id) Text("This is the current profile.", color = MaterialTheme.colorScheme.primary)
            }
            SectionCard("Changes") {
                val changes = e.changes()
                if (changes.isEmpty()) Text("No changes recorded.")
                changes.forEach { ch ->
                    Text("${ch.path}: ${ch.old.display()} → ${ch.new.display()}" + (ch.decision?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.bodySmall)
                    ch.reason?.let { Text("  $it", style = MaterialTheme.typography.labelSmall) }
                }
            }
            if (e.status == ProfileStatus.PENDING) {
                SectionCard("Review") {
                    Text("Proposed changes — review them in the morning report or the AI proposal card.")
                    Button(onClick = { onOpen(app.meanwhile.ui.nav.Routes.REVIEW + "/${e.id}") }) { Text("Review proposal") }
                }
            } else if (e.status in ProfileStatus.APPLIED && e.id != current.version?.id) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            val saved = c.profiles.saveVersion(
                                e.decodedProfile(), ProfileSource.MANUAL, ProfileStatus.ACCEPTED, "Reverted to v${e.version}",
                            )
                            message = "Saved as v${saved.version}"
                        }
                    }) { Text("Revert to this version") }
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            TextButton(onClick = { showJson = !showJson }) { Text(if (showJson) "Hide full profile" else "Show full profile JSON") }
            if (showJson) {
                SelectionContainer {
                    Text(prettyJson(e.profile), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(onClick = onBack) { Text("Back") }
        }
    }
}
