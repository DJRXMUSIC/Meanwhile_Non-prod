package app.meanwhile.ui.settings

import app.meanwhile.ui.common.rememberSafeScope
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material3.TextButton
import app.meanwhile.ui.theme.Palettes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.meanwhile.BuildConfig
import app.meanwhile.data.remote.AuthState
import app.meanwhile.data.settings.AiProviderPreference
import app.meanwhile.data.settings.ThemeMode
import app.meanwhile.data.settings.SyncStatus
import app.meanwhile.data.sync.SyncOutcome
import app.meanwhile.ui.common.LocalAppContainer
import app.meanwhile.ui.common.ScreenScaffold
import app.meanwhile.ui.common.SectionCard
import app.meanwhile.format.relativeTime
import app.meanwhile.ui.nav.Routes
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberSafeScope()
    val auth by c.auth.state.collectAsStateWithLifecycle()
    val sync by c.settings.syncStatus.collectAsStateWithLifecycle(initialValue = SyncStatus())
    val pending by c.db.sync().pendingCount().collectAsStateWithLifecycle(initialValue = 0)
    val rejected by c.db.sync().rejectedCount().collectAsStateWithLifecycle(initialValue = 0)
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    var noteMessage by remember { mutableStateOf<String?>(null) }
    val appSettings by c.settings.settings.collectAsStateWithLifecycle(initialValue = null)

    ScreenScaffold(title = "Settings", onBack = onBack) {
        SectionCard("Profile & stats") {
            Text(
                "Also on the main screen: tap the glucose number for stats, the profile line for the profile.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onOpen(Routes.PROFILE) }) { Text("Profile & factors") }
                Button(onClick = { onOpen(Routes.STATS) }) { Text("Stats") }
            }
        }

        SectionCard("Account") {
            when (val a = auth) {
                AuthState.NotConfigured -> Text(
                    "This build has no Supabase settings, so everything stays on the phone. " +
                        "Add SUPABASE_URL and SUPABASE_ANON_KEY as GitHub secrets and install a new release.",
                )
                is AuthState.SignedIn -> {
                    Text("Signed in as ${a.email ?: a.userId}" + if (a.offline) " (offline — will reconnect)" else "")
                    val clipboard = LocalClipboardManager.current
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("User id: ${a.userId.take(13)}…", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { clipboard.setText(AnnotatedString(a.userId)) }) { Text("Copy") }
                    }
                    OutlinedButton(onClick = { scope.launch { c.auth.signOut() } }) { Text("Sign out") }
                }
                else -> {
                    Text("Not signed in — records stay on this phone until you sign in.")
                    Button(onClick = { scope.launch { c.settings.update { it.copy(authSkipped = false) } } }) {
                        Text("Sign in or create account")
                    }
                }
            }
        }

        SectionCard("Cloud sync") {
            Text("Last successful sync: " + (sync.lastSuccessAt?.let { relativeTime(it) } ?: "never"))
            Text("Items pending: $pending")
            if (rejected > 0) {
                Text(
                    "$rejected records were rejected by the server and kept only on this phone (see Export).",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            sync.failingSince?.let { Text("Failing since ${relativeTime(it)}", color = MaterialTheme.colorScheme.error) }
            sync.lastError?.let { Text("Last error: $it", style = MaterialTheme.typography.bodySmall) }
            Button(onClick = {
                syncMessage = "Syncing…"
                scope.launch {
                    syncMessage = when (c.sync.syncOnce()) {
                        SyncOutcome.SUCCESS -> "Sync complete"
                        SyncOutcome.NOT_CONFIGURED -> "Cloud sync isn't configured in this build"
                        SyncOutcome.NOT_SIGNED_IN -> "Sign in to sync"
                        SyncOutcome.FAILED -> "Sync failed — will retry automatically"
                    }
                }
            }) { Text("Sync now") }
            syncMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }

        SectionCard("Setup") {
            Text("Permissions and background reliability (battery, exact alarms, notifications, microphone).")
            Button(onClick = { onOpen(Routes.SETUP) }) { Text("Open setup checklist") }
        }

        appSettings?.let { CgmSettingsSection(it) }

        appSettings?.let { st ->
            SectionCard("AI provider") {
                val status by c.ai.status.collectAsStateWithLifecycle()
                Text("Which model the AI layer tries first. Dose math never depends on it.", style = MaterialTheme.typography.bodySmall)
                AiProviderPreference.entries.forEach { pref ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = st.aiProvider == pref, onClick = { scope.launch { c.settings.update { it.copy(aiProvider = pref) } } })
                        Text(pref.label)
                    }
                }
                status.lastOkAt?.let { Text("Last AI success ${relativeTime(it)}", style = MaterialTheme.typography.bodySmall) }
                status.lastError?.let { Text("Last AI error: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }

        appSettings?.let { st ->
            SectionCard("Appearance") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = st.themeMode == mode,
                            onClick = { scope.launch { c.settings.update { it.copy(themeMode = mode) } } },
                            label = { Text(mode.label) },
                        )
                    }
                }
                val dark = when (st.themeMode) {
                    ThemeMode.DARK -> true
                    ThemeMode.LIGHT -> false
                    else -> isSystemInDarkTheme()
                }
                Text("Color palette", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Palettes.all.forEach { p ->
                        val selected = st.palette == p.id
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(p.swatch(dark))
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.outline,
                                    shape = CircleShape,
                                )
                                .clickable { scope.launch { c.settings.update { it.copy(palette = p.id) } } },
                        ) {
                            if (selected) {
                                Icon(Icons.Filled.Check, contentDescription = "${p.label} (selected)", tint = Color.White)
                            }
                        }
                    }
                }
                Text(Palettes.byId(st.palette).label, style = MaterialTheme.typography.bodySmall)
            }
        }

        SectionCard("Data") {
            Text("Export any date range as CSV (one file per table, plus a zip of all).")
            Button(onClick = { onOpen(Routes.EXPORT) }) { Text("Export…") }
        }

        SectionCard("Dose calculator (debug)") {
            Text("Try any inputs against the live profile and see the full breakdown. Nothing is logged.")
            OutlinedButton(onClick = { onOpen(Routes.DEBUG_DOSE) }) { Text("Open calculator") }
        }

        SectionCard("App note") {
            Text("Ideas, bugs or notes about the app — saved to the feedback log.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("e.g. the result card should show…") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(enabled = note.isNotBlank(), onClick = {
                    val text = note.trim()
                    scope.launch {
                        c.addFeedback(text, "settings")
                        note = ""
                        noteMessage = "Saved"
                    }
                }) { Text("Save note") }
                noteMessage?.let { Text(it) }
            }
        }

        Text(
            "Meanwhile ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
