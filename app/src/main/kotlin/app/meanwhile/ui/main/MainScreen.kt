package app.meanwhile.ui.main

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.meanwhile.R
import app.meanwhile.data.dose.DoseContext
import app.meanwhile.data.input.PendingSecond
import app.meanwhile.data.profile.ProfileState
import app.meanwhile.format.fmt
import app.meanwhile.format.formatTime
import app.meanwhile.ui.common.LocalAppContainer
import app.meanwhile.ui.common.SyncIndicator
import app.meanwhile.ui.common.rememberNow
import app.meanwhile.ui.nav.Routes
import app.meanwhile.ui.setup.rememberSetupState
import app.meanwhile.ui.speech.rememberSpeechController
import app.meanwhile.ui.speech.speechBiasing
import app.meanwhile.domain.profile.Profile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onOpen: (String) -> Unit) {
    val c = LocalAppContainer.current
    val vm: MainViewModel = viewModel { MainViewModel(c) }
    val profileState by c.profiles.current.collectAsStateWithLifecycle(initialValue = ProfileState(Profile(), null))
    val queued by c.db.aiQueue().countFlow().collectAsStateWithLifecycle(initialValue = 0)
    val now by rememberNow()
    val live by produceState<DoseContext?>(null, now, vm.tick, profileState.version?.id) { value = c.doseContext.build() }
    val pendingSeconds by produceState(emptyList<PendingSecond>(), now, vm.tick) { value = c.nba.pendingSeconds() }
    LaunchedEffect(Unit) {
        if (!vm.morningPrompted) {
            vm.morningPrompted = true
            val (date, _) = c.nightly.nightOf()
            // Not on a fresh install: the report is pointless until there's at least one reading.
            if (!c.nightly.morningSeen(date) && c.cgm.latestNow() != null) onOpen(Routes.MORNING)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Meanwhile") },
                // One button (spec §15: keep the main screen minimal). The chips only appear when
                // something needs attention; BG opens Stats, the profile line opens Profile.
                actions = {
                    AiIndicator()
                    SyncIndicator(onClick = { onOpen(Routes.SETTINGS) })
                    IconButton(onClick = { onOpen(Routes.SETTINGS) }) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
        bottomBar = { InputBar(vm) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val setup = rememberSetupState()
            if (setup.missing > 0) {
                Card(onClick = { onOpen(Routes.SETUP) }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Finish setup: ${setup.missing} permission${if (setup.missing == 1) "" else "s"} needed →",
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            BgHeader(rememberBgSnapshot(), onTap = { onOpen(Routes.STATS) })
            LiveStatus(live, onOpen)
            ProfileCallout(profileState.versionLabel, profileState.version, queued, onClick = { onOpen(Routes.PROFILE) })
            pendingSeconds.forEach { p -> PendingSecondCard(p, now.toEpochMilli(), vm) }
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.session?.let { s ->
                PathHeader(s, onSwitch = vm::switchPath)
                s.cards.forEach { card -> ResultCardView(card, profileState.profile, vm) }
                TextButton(onClick = vm::clear) { Text("Clear") }
            }
        }
    }
}

/** IOB, active factors with weights, pending caffeine (spec §15 compact view). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveStatus(live: DoseContext?, onOpen: (String) -> Unit) {
    if (live == null) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("IOB ${fmt(live.iob)} u", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (live.applied.isEmpty() && live.pendingUnits.isEmpty()) {
                Text("No active factors", style = MaterialTheme.typography.bodySmall)
            }
            live.applied.forEach { f ->
                AssistChip(
                    onClick = { onOpen(Routes.PROFILE) },
                    label = { Text("${f.name} ${fmt(f.weight)}" + (f.expiresAt?.let { " · until ${formatTime(it)}" } ?: "")) },
                )
            }
            live.pendingUnits.forEach { p ->
                AssistChip(onClick = { onOpen(Routes.PROFILE) }, label = { Text("${p.name} +${fmt(p.units)} u") })
            }
        }
    }
}

@Composable
private fun PendingSecondCard(p: PendingSecond, now: Long, vm: MainViewModel) {
    val due = p.dueAt <= now
    Card(
        colors = if (due) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer) else CardDefaults.cardColors(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Second injection: ${p.units} u " + if (due) "due now (since ${formatTime(p.dueAt)})" else "at ${formatTime(p.dueAt)}",
                style = MaterialTheme.typography.titleSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.logSecond(p.proposalId, p.units) }) { Text("Log ${p.units} u") }
                TextButton(onClick = { vm.logSecond(p.proposalId, 0, skipped = true) }) { Text("Skip") }
            }
        }
    }
}

@Composable
private fun InputBar(vm: MainViewModel) {
    val context = LocalContext.current
    val speech = rememberSpeechController()
    val c = LocalAppContainer.current
    val profileState by c.profiles.current.collectAsStateWithLifecycle(initialValue = ProfileState(Profile(), null))
    // Recognition leans toward dosing vocabulary and this profile's factor keywords.
    val biasing = remember(profileState.version?.id) { speechBiasing(profileState.profile) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) speech.start(biasing) { vm.text = it; vm.via = "voice" }
    }
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(12.dp),
        ) {
            speech.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (speech.listening) Text("Listening… ${speech.partial}", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = vm.text,
                    onValueChange = { vm.text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("“60 carbs 20 fat”, “had a coffee”, “took 6 units”…") },
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (vm.text.isNotBlank() && !vm.busy) vm.submit() }),
                )
                IconButton(enabled = vm.text.isNotBlank() && !vm.busy, onClick = vm::submit) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
                FilledIconButton(
                    onClick = {
                        when {
                            speech.listening -> speech.stop()
                            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ->
                                speech.start(biasing) { vm.text = it; vm.via = "voice" }
                            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    modifier = Modifier.size(64.dp),
                    colors = if (speech.listening) {
                        IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)
                    } else {
                        IconButtonDefaults.filledIconButtonColors()
                    },
                ) {
                    Icon(painterResource(R.drawable.ic_mic), contentDescription = if (speech.listening) "Stop" else "Speak")
                }
            }
        }
    }
}

/**
 * "AI offline" indicator (spec §10.6): AI configured but the network is down, there's no live session,
 * or the most recent AI call failed.
 */
@Composable
fun AiIndicator() {
    val c = LocalAppContainer.current
    if (c.supabase == null) return
    val online by c.network.online.collectAsStateWithLifecycle()
    val auth by c.auth.state.collectAsStateWithLifecycle()
    val status by c.ai.status.collectAsStateWithLifecycle()
    val signedIn = auth is app.meanwhile.data.remote.AuthState.SignedIn && !(auth as app.meanwhile.data.remote.AuthState.SignedIn).offline
    val lastFailed = (status.lastErrorAt ?: 0) > (status.lastOkAt ?: 0)
    if (!online || !signedIn || lastFailed) {
        Text("AI offline", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(end = 4.dp))
    }
}
