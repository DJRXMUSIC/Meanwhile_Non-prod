package app.meanwhile.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.meanwhile.R
import app.meanwhile.data.dose.DoseContext
import app.meanwhile.data.input.PendingSecond
import app.meanwhile.data.profile.ProfileState
import app.meanwhile.domain.cgm.Trend
import app.meanwhile.domain.profile.Profile
import app.meanwhile.format.fmt
import app.meanwhile.format.formatTime
import app.meanwhile.ui.common.LocalAppContainer
import app.meanwhile.ui.common.SyncIndicator
import app.meanwhile.ui.common.rememberNow
import app.meanwhile.ui.nav.Routes
import app.meanwhile.ui.setup.rememberSetupState
import app.meanwhile.ui.speech.MicState
import app.meanwhile.ui.speech.rememberSpeechController
import app.meanwhile.ui.speech.speechBiasing
import app.meanwhile.ui.theme.LocalGlucoseColors
import app.meanwhile.ui.theme.forMgDl

/**
 * The conversation (1.4): Danny says or types what he's doing, the app shows each step while it works
 * and answers with one clear next action; everything is logged word for word. BG and the rest of the
 * live state stay one compact line at the top.
 */
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
                title = { NowLine(rememberBgSnapshot(), live, onTap = { onOpen(Routes.STATS) }) },
                actions = {
                    AiIndicator()
                    SyncIndicator(onClick = { onOpen(Routes.SETTINGS) })
                    IconButton(onClick = { onOpen(Routes.SETTINGS) }) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
        bottomBar = { Composer(vm, profileState.profile) },
    ) { padding ->
        val list = rememberLazyListState()
        val lastTurn = vm.turns.lastOrNull()
        // Follow the conversation: every new message, step and answer scrolls into view.
        LaunchedEffect(vm.turns.size, lastTurn?.steps?.size, lastTurn?.session?.cards?.size, lastTurn?.error) {
            val count = list.layoutInfo.totalItemsCount
            if (count > 0) list.animateScrollToItem(count - 1)
        }
        LazyColumn(
            state = list,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "status") {
                val setup = rememberSetupState()
                val bg = rememberBgSnapshot()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (setup.missing > 0) {
                        Card(onClick = { onOpen(Routes.SETUP) }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "Finish setup: ${setup.missing} permission${if (setup.missing == 1) "" else "s"} needed →",
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                    if (bg.reading != null && bg.stale) StaleBanner(bg.ageMinutes)
                    LiveDetails(live, profileState, queued, onOpen)
                    pendingSeconds.forEach { p -> PendingSecondCard(p, now.toEpochMilli(), vm) }
                }
            }
            if (vm.history.isNotEmpty()) {
                item(key = "history-label") {
                    Text("Earlier", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(vm.history, key = { "h-${it.id}" }) { HistoryLine(it) }
            }
            if (vm.turns.isEmpty()) {
                item(key = "hint") { EmptyHint() }
            }
            items(vm.turns, key = { it.id }) { turn -> TurnView(turn, profileState.profile, vm) }
            item(key = "bottom") { Spacer(Modifier.height(4.dp)) }
        }
    }
}

/** BG, trend, age and IOB in one line (tap: stats). */
@Composable
private fun NowLine(bg: BgSnapshot, live: DoseContext?, onTap: () -> Unit) {
    val colors = LocalGlucoseColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.clickable(onClick = onTap),
    ) {
        Text(
            bg.reading?.mgDl?.toString() ?: "—",
            fontSize = 30.sp, fontWeight = FontWeight.Bold, color = colors.forMgDl(bg.reading?.mgDl, bg.stale),
        )
        Text(Trend.arrow(bg.rate), fontSize = 22.sp, color = colors.forMgDl(bg.reading?.mgDl, bg.stale))
        Column {
            Text(
                bg.ageMinutes?.let { if (it < 1) "just now" else "$it min ago" } ?: "no reading",
                style = MaterialTheme.typography.labelSmall,
            )
            live?.let { Text("IOB ${fmt(it.iob, 1)} u", style = MaterialTheme.typography.labelSmall) }
        }
    }
}

/** Active factors and the profile, folded into one tappable line (1.4: less to read). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveDetails(live: DoseContext?, profile: ProfileState, queued: Int, onOpen: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val factors = live?.applied.orEmpty()
    val pending = live?.pendingUnits.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            buildString {
                append(
                    when (val n = factors.size + pending.size) {
                        0 -> "No active factors"
                        1 -> "1 active factor"
                        else -> "$n active factors"
                    },
                )
                append(" · profile ${profile.versionLabel}")
                if (queued > 0) append(" · $queued AI refinement${if (queued == 1) "" else "s"} queued")
                append(if (open) "  ▴" else "  ▾")
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable { open = !open },
        )
        if (open) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                factors.forEach { f ->
                    AssistChip(
                        onClick = { onOpen(Routes.PROFILE) },
                        label = { Text("${f.name} ${fmt(f.weight)}" + (f.expiresAt?.let { " · until ${formatTime(it)}" } ?: "")) },
                    )
                }
                pending.forEach { p -> AssistChip(onClick = { onOpen(Routes.PROFILE) }, label = { Text("${p.name} +${fmt(p.units)} u") }) }
            }
            ProfileCallout(profile.versionLabel, profile.version, queued, onClick = { onOpen(Routes.PROFILE) })
        }
    }
}

@Composable
private fun EmptyHint() {
    Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Tell me what you're doing.", style = MaterialTheme.typography.titleLarge)
        Text(
            "I'll log it and tell you the next best action — insulin, food, or nothing. Tap the mic or type.",
            style = MaterialTheme.typography.bodyMedium,
        )
        listOf(
            "“60 carbs 20 fat 30 protein”", "“pizza and a coffee”", "“took 6 units”", "“never mind, only 5”",
            "“BG 140, what should I do?”", "“took it”", "“went for a 3 mile run”",
        ).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
                Button(onClick = { vm.logSecond(p.proposalId, p.units) }) { Text("Took ${p.units} u") }
                TextButton(onClick = { vm.logSecond(p.proposalId, 0, skipped = true) }) { Text("Skip") }
            }
        }
    }
}

/**
 * Mic + text box. Sending clears the box and drops the keyboard; the mic shows every state (starting,
 * listening with a live level ring, hearing you with the words so far, transcribing) and ticks the
 * phone, and what it heard is sent as soon as Danny stops talking (Settings → Voice to turn that off).
 */
@Composable
private fun Composer(vm: MainViewModel, profile: Profile) {
    val context = LocalContext.current
    val view = LocalView.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val c = LocalAppContainer.current
    val settings by c.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val speech = rememberSpeechController()
    val biasing = remember(profile) { speechBiasing(profile) }

    fun sendTyped() {
        if (vm.text.isBlank()) return
        vm.send(vm.text, "text")
        keyboard?.hide()
        focus.clearFocus()
    }

    fun listen() {
        keyboard?.hide()
        focus.clearFocus()
        speech.start(
            biasing,
            onStateChange = { state ->
                when (state) {
                    MicState.LISTENING -> view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    MicState.PROCESSING -> view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    null -> view.performHapticFeedback(HapticFeedbackConstants.REJECT)
                    else -> Unit
                }
            },
        ) { heard, details ->
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            if (settings?.voiceAutoSend != false) vm.send(heard, "voice", details) else vm.text = heard
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listen()
    }
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MicStatus(speech.state, speech.partial, speech.level, speech.error)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = vm.text,
                    onValueChange = { vm.text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Say or type what you're doing…") },
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendTyped() }),
                )
                if (vm.text.isNotBlank()) {
                    IconButton(onClick = { sendTyped() }) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
                val ring = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                val level = speech.level
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(76.dp)
                        .drawBehind {
                            if (speech.active) drawCircle(ring, radius = size.minDimension / 2f * (0.78f + 0.22f * level))
                        },
                ) {
                    FilledIconButton(
                        onClick = {
                            when {
                                speech.active -> speech.stop()
                                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> listen()
                                else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        modifier = Modifier.size(64.dp),
                        colors = if (speech.active) {
                            IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error)
                        } else {
                            IconButtonDefaults.filledIconButtonColors()
                        },
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_mic),
                            contentDescription = if (speech.active) "Stop listening" else "Speak",
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
            }
        }
    }
}

/** One line that always says what the mic is doing. */
@Composable
private fun MicStatus(state: MicState, partial: String, level: Float, error: String?) {
    when {
        state == MicState.STARTING -> StatusLine("Starting the mic…", busy = true)
        state == MicState.LISTENING -> Column {
            StatusLine("Listening — go ahead", color = MaterialTheme.colorScheme.primary)
            LevelBar(level)
        }
        state == MicState.HEARING -> Column {
            StatusLine("Hearing you: " + partial.ifBlank { "…" }, color = MaterialTheme.colorScheme.primary)
            LevelBar(level)
        }
        state == MicState.PROCESSING -> StatusLine("Got it — writing it down… " + partial, busy = true)
        error != null && error != "Stopped" -> StatusLine(error, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun StatusLine(text: String, busy: Boolean = false, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (busy) androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 3)
    }
}

/** The microphone level as a bar — proof the phone hears something. */
@Composable
private fun LevelBar(level: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(level.coerceIn(0.03f, 1f))
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
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
