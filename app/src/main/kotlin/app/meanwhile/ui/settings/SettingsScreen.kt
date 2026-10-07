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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import app.meanwhile.format.formatTime
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onOpen(Routes.PROFILE) }) { Text("Profile") }
                Button(onClick = { onOpen(Routes.LEARNING) }) { Text("Learning") }
                Button(onClick = { onOpen(Routes.STATS) }) { Text("Stats") }
            }
        }

        SectionCard("Account") {
            when (val a = auth) {
                AuthState.NotConfigured -> Text(
                    "This build has no Supabase settings, so everything stays on the phone. " +
                        "Add the SUPABASE_ACCESS_TOKEN GitHub secret (docs/INSTALL.md) and install the next release.",
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
            SectionCard("AI") {
                val status by c.ai.status.collectAsStateWithLifecycle()
                Text("Dose math never depends on the AI — it only understands what you say, estimates meals and learns.", style = MaterialTheme.typography.bodySmall)
                Text("Day to day (your messages, meal estimates, factor updates)", style = MaterialTheme.typography.titleSmall)
                Text("Gemini = Gemini 3.8 Flash; Claude = Claude Opus 5.5. The first one gets up to 60 s, then the other; the screen shows which one is working.", style = MaterialTheme.typography.bodySmall)
                AiProviderPreference.entries.forEach { pref ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = st.aiProvider == pref, onClick = { scope.launch { c.settings.update { it.copy(aiProvider = pref) } } })
                        Text(pref.label.removeSuffix(" (default)") + if (pref == AiProviderPreference.GEMINI_FIRST) " (default)" else "")
                    }
                }
                Text("Learning (every night, and after new dose outcomes)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Claude = Claude Opus 5.5 at max effort, sent as a batch so it can think as long as it needs (results usually " +
                        "within an hour; Gemini Pro steps in if it fails). Gemini = Gemini Pro, answered directly.",
                    style = MaterialTheme.typography.bodySmall,
                )
                AiProviderPreference.entries.forEach { pref ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = st.learnProvider == pref, onClick = { scope.launch { c.settings.update { it.copy(learnProvider = pref) } } })
                        Text(pref.label.removeSuffix(" (default)") + if (pref == AiProviderPreference.CLAUDE_FIRST) " (default)" else "")
                    }
                }
                status.lastOkAt?.let { Text("Last AI success ${relativeTime(it)}", style = MaterialTheme.typography.bodySmall) }
                status.lastError?.let { Text("Last AI error: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            SectionCard("Voice") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = st.voiceAutoSend, onCheckedChange = { on -> scope.launch { c.settings.update { it.copy(voiceAutoSend = on) } } })
                    Text("Send what I say as soon as I stop talking")
                }
                Text(
                    "Off: what the mic heard goes into the text box for you to check and send. Either way, “never mind, only 5” fixes a dose.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            AdviceSection()
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

        SectionCard("Diagnostics") {
            val problems by produceState(0) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { c.diagnostics.problemCount() } }
            Text(
                if (problems == 0) "No warnings or errors in the last 24 h." else "$problems warnings/errors in the last 24 h.",
                color = if (problems == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            )
            Text(
                "Something off? Copy the diagnostics report and paste it into Claude (or any AI coding assistant) — " +
                    "it has the app version, device, health of every part, grouped errors with stack traces and the log. No keys or passwords.",
                style = MaterialTheme.typography.bodySmall,
            )
            val context = androidx.compose.ui.platform.LocalContext.current
            val clipboard = LocalClipboardManager.current
            var diagMessage by remember { mutableStateOf<String?>(null) }
            var captureTick by remember { mutableStateOf(0) }
            val captureSince by produceState<java.time.Instant?>(null, captureTick) { value = c.diagnostics.captureStartedAt() }
            Text(
                "Every log line and the whole conversation also go to your Supabase project with each sync (tables app_logs, " +
                    "conversation_log, ai_calls), so you can just say when it happened.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (captureSince == null) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            c.diagnostics.startCapture()
                            captureTick++
                            diagMessage = "Capture started — reproduce the problem, then come back and tap Copy for AI"
                        }
                    }) { Text("Start fresh capture") }
                } else {
                    Text("Capturing since ${captureSince?.let { formatTime(it.toEpochMilli()) }}", modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        scope.launch {
                            c.diagnostics.stopCapture()
                            captureTick++
                            diagMessage = "Capture stopped"
                        }
                    }) { Text("Stop") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        clipboard.setText(AnnotatedString(c.diagnostics.report(tailLines = 150)))
                        diagMessage = "Copied — paste it into your AI assistant"
                    }
                }) { Text("Copy for AI") }
                OutlinedButton(onClick = {
                    scope.launch { context.startActivity(c.diagnostics.shareIntent(c.diagnostics.writeReport())) }
                }) { Text("Share") }
                OutlinedButton(onClick = { onOpen(Routes.LOG) }) { Text("Log") }
            }
            diagMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        }

        SectionCard("Data") {
            Text("Export any date range as CSV (one file per table, plus a zip of all).")
            Button(onClick = { onOpen(Routes.EXPORT) }) { Text("Export…") }
        }
        ImportSection()

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

/**
 * 2.0: the app's own suggestions (lows, carbs, corrections) — profile values, so each switch saves a
 * new profile version like any other edit. The numbers are in Profile → Edit settings → Notifications.
 */
@Composable
private fun AdviceSection() {
    val c = LocalAppContainer.current
    val scope = rememberSafeScope()
    val state by c.profiles.current.collectAsStateWithLifecycle(initialValue = null)
    val p = state?.profile ?: return
    fun set(path: String, on: Boolean) = scope.launch {
        val current = c.profiles.current().profile
        val old = app.meanwhile.domain.profile.ProfilePatch.get(current, path) ?: return@launch
        val change = app.meanwhile.domain.profile.ProfileChange(path, old, kotlinx.serialization.json.JsonPrimitive(on))
        app.meanwhile.domain.profile.ProfilePatch.apply(current, listOf(change)).onSuccess { updated ->
            c.profiles.saveVersion(
                updated, app.meanwhile.data.profile.ProfileSource.MANUAL, app.meanwhile.data.profile.ProfileStatus.ACCEPTED,
                "Manual: $path ${if (on) "on" else "off"}", changes = listOf(change),
            )
        }
    }
    SectionCard("Suggestions on their own") {
        Text(
            "After each CGM reading the app works out what to do right now and, when it matters, sends a notification. " +
                "It never logs anything — you still say what you did.",
            style = MaterialTheme.typography.bodySmall,
        )
        listOf(
            Triple("alerts.enabled", p.alerts.enabled, "Send suggestions"),
            Triple("alerts.lows", p.alerts.lows, "Lows, now or within ${p.nba.predictMinutes} min"),
            Triple("alerts.carbs", p.alerts.carbs, "Carbs when insulin on board takes a falling BG below ${p.alerts.carbsProjectedBelowMgDl.toInt()}"),
            Triple(
                "alerts.corrections", p.alerts.corrections,
                "Corrections at BG ≥ ${p.alerts.correctionAboveMgDl.toInt()}, ${p.alerts.correctionMinMinutesSinceDose} min after the last dose",
            ),
        ).forEachIndexed { i, (path, on, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = on, enabled = i == 0 || p.alerts.enabled, onCheckedChange = { set(path, it) })
                Text(label)
            }
        }
    }
}

/**
 * 2.0: bring in the old Meanwhile app's export (CGM + doses) so stats, the AI and learning start from
 * Danny's real history; its settings are offered, never applied without his tap.
 */
@Composable
private fun ImportSection() {
    val c = LocalAppContainer.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberSafeScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<app.meanwhile.data.importer.ImportResult?>(null) }
    var applied by remember { mutableStateOf<String?>(null) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        status = "Reading the file…"
        scope.launch {
            try {
                val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        c.importer.import(input) { p -> status = p }
                    }
                }
                result = r
                status = r.summary
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                app.meanwhile.log.AppLog.e("Import", "import failed: ${e.message}", e)
                status = "Couldn't import that file: ${e.message ?: e::class.java.simpleName}"
            } finally {
                busy = false
            }
        }
    }
    SectionCard("Import history") {
        Text(
            "Bring in the old Meanwhile app's export (.json): CGM readings and doses, so stats, the AI and learning start " +
                "from your real history. Importing twice adds nothing; doses from after you started logging here are skipped.",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(enabled = !busy, onClick = { pick.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
            Text(if (busy) "Importing…" else "Import from file…")
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        val r = result
        val state by c.profiles.current.collectAsStateWithLifecycle(initialValue = null)
        val current = state?.profile
        if (r != null && current != null && !r.settings.isEmpty) {
            val changes = r.settings.changes(current)
            if (changes.isEmpty()) {
                Text("Your profile already has the old app's settings.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("The old app's settings:", style = MaterialTheme.typography.titleSmall)
                changes.forEach { ch -> Text("${ch.path}: ${ch.old} → ${ch.new}", style = MaterialTheme.typography.bodySmall) }
                Button(enabled = applied == null, onClick = {
                    scope.launch {
                        val now = c.profiles.current().profile
                        val fresh = r.settings.changes(now)
                        app.meanwhile.domain.profile.ProfilePatch.apply(now, fresh).onSuccess { updated ->
                            val problems = app.meanwhile.domain.profile.ProfileValidation.problems(updated)
                            if (problems.isNotEmpty()) {
                                applied = "Not applied: " + problems.joinToString("; ")
                                return@onSuccess
                            }
                            val v = c.profiles.saveVersion(
                                updated, app.meanwhile.data.profile.ProfileSource.MANUAL, app.meanwhile.data.profile.ProfileStatus.ACCEPTED,
                                "Manual: settings from the old app", changes = fresh,
                            )
                            c.conversation.action(null, "Applied the old app's settings → " + fresh.joinToString { "${it.path} ${it.new}" })
                            applied = "Applied as profile v${v.version}"
                        }.onFailure { applied = "Not applied: ${it.message}" }
                    }
                }) { Text("Use these settings") }
                applied?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
