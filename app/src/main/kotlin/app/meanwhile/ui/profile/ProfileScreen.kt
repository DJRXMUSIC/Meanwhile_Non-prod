package app.meanwhile.ui.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.meanwhile.data.dose.DoseContext
import app.meanwhile.data.input.FactorUpdater
import app.meanwhile.data.profile.ProfileSource
import app.meanwhile.data.profile.ProfileState
import app.meanwhile.domain.iob.Iob
import app.meanwhile.domain.profile.FactorKind
import app.meanwhile.domain.profile.Profile
import app.meanwhile.format.fmt
import app.meanwhile.format.formatDateTime
import app.meanwhile.format.formatTime
import app.meanwhile.format.relativeTime
import app.meanwhile.ui.common.LocalAppContainer
import app.meanwhile.ui.common.ScreenScaffold
import app.meanwhile.ui.common.SectionCard
import app.meanwhile.ui.common.rememberNow
import app.meanwhile.ui.main.sourceLabel
import app.meanwhile.ui.nav.Routes
import app.meanwhile.ui.theme.LocalGlucoseColors
import kotlinx.coroutines.launch
import java.time.Instant

@Composable
fun ProfileScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val state by c.profiles.current.collectAsStateWithLifecycle(initialValue = ProfileState(Profile(), null))
    val versions by c.profiles.versions.collectAsStateWithLifecycle(initialValue = emptyList())
    val pendingFlow = remember { c.profiles.pendingFlow() }
    val pending by pendingFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val queued by c.db.aiQueue().countFlow().collectAsStateWithLifecycle(initialValue = 0)
    val now by rememberNow()
    var tick by remember { mutableIntStateOf(0) }
    val live by produceState<DoseContext?>(null, now, tick, state.version?.id) { value = c.doseContext.build() }
    var activating by remember { mutableStateOf(false) }
    val p = state.profile
    val aiColor = LocalGlucoseColors.current.aiProposed

    ScreenScaffold(title = "Profile", onBack = onBack) {
        SectionCard("Current: ${state.versionLabel}") {
            val v = state.version
            if (v == null) {
                Text("Spec starting values. The first change creates v1.")
            } else {
                Text("${v.status} · ${sourceLabel(v.source)} · ${relativeTime(v.createdAt)}")
                if (v.summary.isNotBlank()) Text(v.summary, style = MaterialTheme.typography.bodySmall)
            }
            if (queued > 0) Text("$queued AI refinement${if (queued == 1) "" else "s"} queued (runs when online)", color = aiColor)
            pending.forEach { pv ->
                Text(
                    "Pending: v${pv.version} from ${sourceLabel(pv.source)} — ${pv.summary.ifBlank { "review" }} →",
                    color = aiColor,
                    modifier = Modifier.clickable { onOpen(Routes.profileVersion(pv.id)) },
                )
            }
        }

        SectionCard("Active factors") {
            val l = live
            if (l == null) {
                Text("…")
            } else {
                if (l.applied.isEmpty() && l.pendingUnits.isEmpty()) Text("None — all factors at 1.00")
                l.applied.forEach { f ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${f.factorId} ${f.name}: ${fmt(f.weight)}", fontWeight = FontWeight.SemiBold)
                            Text(
                                "since ${formatTime(f.startedAt)}" + (f.expiresAt?.let { " · until ${formatDateTime(it)}" } ?: "") +
                                    " · ${f.source}" + (f.note?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (p.active.any { it.factorId == f.factorId }) {
                            TextButton(onClick = {
                                scope.launch {
                                    c.factorUpdater.apply(
                                        listOf(FactorUpdater.Request(f.factorId, action = "deactivate", at = Instant.now())),
                                        null, "manual", ProfileSource.MANUAL, "Manual: end ${f.name}",
                                    )
                                    tick++
                                }
                            }) { Text("End") }
                        }
                    }
                }
                l.pendingUnits.forEach { u -> Text("${u.factorId} ${u.name}: +${fmt(u.units)} u (${fmt(u.amount, 0)}) until the next dose") }
                Text("IOB now ${fmt(l.iob)} u", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { activating = true }) { Text("Activate a factor") }
        }

        SectionCard("Settings") {
            KeyValue("ICR", "1 u : ${fmt(p.dose.icr, 1)} g")
            KeyValue("ISF", "1 u : ${fmt(p.dose.isf, 0)} mg/dL")
            KeyValue("Target", "${fmt(p.dose.target, 0)} mg/dL")
            KeyValue("Combined cap", "${fmt(p.dose.combinedCap)}× (no floor)")
            KeyValue("Rounding", "nearest ${fmt(p.dose.unitIncrement, 0)} u, halves up")
            KeyValue("Lead time base", "${p.leadTime.baseMin} min (${p.leadTime.minMin}–${p.leadTime.maxMin})")
            KeyValue("Fat / protein", "K ${p.meal.kFatPerG} / ${p.meal.kProteinPerG} per g; low-carb < ${fmt(p.meal.lowCarbThresholdG, 0)} g: ${fmt(p.meal.fatGPerUnit, 0)} g / ${fmt(p.meal.proteinGPerUnit, 0)} g per u")
            KeyValue("Split", if (p.split.enabled) "fat ≥ ${fmt(p.split.minFatG, 0)} & protein ≥ ${fmt(p.split.minProteinG, 0)} & carbs ≥ ${fmt(p.split.minCarbsG, 0)}: ${(p.split.firstFraction * 100).toInt()}% now, rest at +${p.split.secondAfterMin} min" else "off")
            KeyValue("Daily reset", "${p.resetHour}:00")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onOpen(Routes.PROFILE_EDIT) }) { Text("Edit settings") }
                OutlinedButton(onClick = { onOpen(Routes.profileJson("")) }) { Text("Edit as JSON") }
            }
        }

        SectionCard("Insulin action (IOB curve)") {
            KeyValue("Delay / peak / duration", "${fmt(p.iob.delayMin, 0)} / ${fmt(p.iob.peakMin, 0)} / ${fmt(p.iob.durationMin, 0)} min")
            Text(
                listOf(0, 60, 120, 180, 240, 300).joinToString("  ") { t ->
                    "${t}m ${fmt(Iob.fraction(t.toDouble(), p.iob.peakMin, p.iob.durationMin))}"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Fraction remaining by minutes after the delay.", style = MaterialTheme.typography.labelSmall)
        }

        SectionCard("Factor definitions") {
            p.factors.forEach { def ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${def.id} ${def.name}" + if (!def.enabled) " (disabled)" else "", fontWeight = FontWeight.SemiBold)
                        Text(
                            listOfNotNull(
                                def.kind.name.lowercase().replace('_', ' '),
                                if (def.minWeight != null || def.maxWeight != null) "bounds ${def.minWeight?.let { fmt(it) } ?: "—"}–${def.maxWeight?.let { fmt(it) } ?: "—"}" else null,
                                def.defaultWeight?.let { "default ${fmt(it)}" },
                                def.unitsPerEvent?.let { "+${fmt(it)} u each" },
                                def.presets.takeIf { it.isNotEmpty() }?.entries?.joinToString(prefix = "presets ") { "${it.key} ${fmt(it.value)}" },
                                FactorUpdater.windowText(def, def.window.minutes),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { onOpen(Routes.profileJson("factors.${def.id}")) }) { Text("Edit") }
                }
            }
        }

        SectionCard("History") {
            if (versions.isEmpty()) Text("No versions yet.")
            versions.take(60).forEach { v ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(Routes.profileVersion(v.id)) }
                        .padding(vertical = 4.dp),
                ) {
                    Text("v${v.version} · ${v.status} · ${sourceLabel(v.source)}" + if (v.id == state.version?.id) " · current" else "", fontWeight = FontWeight.SemiBold)
                    Text("${formatDateTime(v.createdAt)} — ${v.summary}", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }

    if (activating) {
        ActivateFactorDialog(p, onDismiss = { activating = false }) { request ->
            activating = false
            scope.launch {
                c.factorUpdater.apply(listOf(request), null, "manual", ProfileSource.MANUAL, "Manual: ${request.factorId} ${request.action}")
                tick++
            }
        }
    }
}

@Composable
fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(key, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.6f))
    }
}

/** Manual factor activation (spec §14 M5). Bounds are shown as guidance only — any weight is accepted. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActivateFactorDialog(profile: Profile, onDismiss: () -> Unit, onApply: (FactorUpdater.Request) -> Unit) {
    val choices = profile.factors.filter { it.enabled && (it.kind == FactorKind.MULTIPLIER || it.kind == FactorKind.UNITS_PER_EVENT) }
    var selected by remember { mutableStateOf(choices.firstOrNull()?.id) }
    val def = choices.firstOrNull { it.id == selected }
    var preset by remember(selected) { mutableStateOf<String?>(null) }
    var weight by remember(selected, preset) { mutableStateOf(def?.let { (preset?.let { p -> it.presets[p] } ?: it.defaultWeight)?.let { w -> fmt(w) } } ?: "") }
    var window by remember(selected) { mutableStateOf(def?.window?.minutes?.toString() ?: "") }
    var minutesAgo by remember { mutableStateOf("0") }
    var amount by remember(selected) { mutableStateOf("1") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Activate a factor") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    choices.forEach { d -> FilterChip(selected = d.id == selected, onClick = { selected = d.id }, label = { Text(d.name) }) }
                }
                if (def != null) {
                    if (def.presets.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            def.presets.forEach { (k, v) -> FilterChip(selected = preset == k, onClick = { preset = k }, label = { Text("$k ${fmt(v)}") }) }
                        }
                    }
                    if (def.kind == FactorKind.UNITS_PER_EVENT) {
                        NumField("How many (× ${fmt(def.unitsPerEvent ?: 1.0)} u)", amount) { amount = it }
                    } else {
                        NumField("Weight (bounds ${def.minWeight?.let { fmt(it) } ?: "—"}–${def.maxWeight?.let { fmt(it) } ?: "—"})", weight) { weight = it }
                        NumField("Window minutes (blank = rule: ${FactorUpdater.windowText(def, def.window.minutes)})", window) { window = it }
                    }
                    NumField(if (def.id == "F7") "Workout ended … min ago" else "Started … min ago", minutesAgo) { minutesAgo = it }
                    OutlinedTextField(note, { note = it }, label = { Text("Note (type, intensity, …)") })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = def != null, onClick = {
                def?.let { d -> onApply(
                    FactorUpdater.Request(
                        factorId = d.id,
                        weight = if (d.kind == FactorKind.UNITS_PER_EVENT) null else weight.toDoubleOrNull(),
                        preset = preset,
                        amount = if (d.kind == FactorKind.UNITS_PER_EVENT) amount.toDoubleOrNull() ?: 1.0 else null,
                        windowMinutes = window.toIntOrNull()?.takeIf { it != d.window.minutes },
                        at = Instant.now().minusSeconds((minutesAgo.toLongOrNull() ?: 0) * 60),
                        note = note.ifBlank { null },
                    ),
                ) }
            }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NumField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { v -> onChange(v.filter { it.isDigit() || it == '.' || it == '-' }) }, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
    )
}
