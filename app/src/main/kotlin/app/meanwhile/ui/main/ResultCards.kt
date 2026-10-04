package app.meanwhile.ui.main

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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.meanwhile.data.input.AiProposalCard
import app.meanwhile.data.input.DoseConfirmCard
import app.meanwhile.data.input.FactorPickerCard
import app.meanwhile.data.input.FactorUpdateCard
import app.meanwhile.data.input.FeedbackSavedCard
import app.meanwhile.data.input.InfoCard
import app.meanwhile.data.input.InputSession
import app.meanwhile.data.input.MealDraft
import app.meanwhile.data.input.MealMacrosCard
import app.meanwhile.data.input.NbaCard
import app.meanwhile.data.input.ResultCard
import app.meanwhile.domain.cgm.Trend
import app.meanwhile.domain.profile.FactorKind
import app.meanwhile.domain.profile.Profile
import app.meanwhile.format.fmt
import app.meanwhile.format.formatTime
import app.meanwhile.format.relativeTime
import app.meanwhile.ui.dose.Breakdown
import app.meanwhile.ui.theme.LocalGlucoseColors
import app.meanwhile.ui.theme.forMgDl
import kotlin.math.roundToInt

private val PATHS = listOf("meal" to "Meal", "factor_update" to "Factor", "dose_given" to "Dose", "feedback" to "Feedback")

private fun pathLabel(type: String) = when (type) {
    "meal" -> "Next Best Action"
    "factor_update" -> "Update Profile"
    "dose_given" -> "Dose log"
    "feedback" -> "Feedback log"
    else -> type
}

/** Which path the input took, with a one-tap switch (spec §9.2). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PathHeader(session: InputSession, onSwitch: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "“${session.raw}” → " + session.route.intents.joinToString(" → ") { pathLabel(it.type) }.ifEmpty { "nothing recognised" } +
                " · ${session.route.router} router" + if (session.switched) " · switched" else "",
            style = MaterialTheme.typography.labelMedium,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val current = session.route.intents.map { it.type }.toSet()
            PATHS.forEach { (type, label) ->
                FilterChip(selected = type in current, onClick = { if (type !in current || current.size > 1) onSwitch(type) }, label = { Text(label) })
            }
        }
    }
}

@Composable
fun ResultCardView(card: ResultCard, profile: Profile, vm: MainViewModel) {
    when (card) {
        is NbaCard -> NbaCardView(card, vm)
        is FactorUpdateCard -> FactorUpdateCardView(card, onUndo = { vm.undoFactors(card) })
        is MealMacrosCard -> MealMacrosCardView(card, onConfirm = { vm.confirmMeal(card, it) })
        is DoseConfirmCard -> DoseConfirmCardView(card, vm)
        is FeedbackSavedCard -> SimpleCard("Saved to feedback", card.text)
        is FactorPickerCard -> FactorPickerCardView(card, profile) { id, preset -> vm.pickFactor(card, id, preset) }
        is InfoCard -> SimpleCard(if (card.isError) "Problem" else "Note", card.message, isError = card.isError)
        is AiProposalCard -> AiProposalCardView(card, vm)
    }
}

/** AI-proposed factor changes: every value is marked AI-proposed until Danny accepts (spec §15). */
@Composable
private fun AiProposalCardView(card: AiProposalCard, vm: MainViewModel) {
    val ai = LocalGlucoseColors.current.aiProposed
    val checked = remember(card.key) { androidx.compose.runtime.mutableStateListOf(*Array(card.changes.size) { true }) }
    val weights = remember(card.key) { androidx.compose.runtime.mutableStateListOf(*card.changes.map { it.weight?.let { w -> fmt(w) } ?: "" }.toTypedArray()) }
    val windows = remember(card.key) { androidx.compose.runtime.mutableStateListOf(*card.changes.map { it.windowMinutes?.toString() ?: "" }.toTypedArray()) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI proposal · Update Profile", style = MaterialTheme.typography.titleSmall)
            Text(
                "${card.provider} · ${card.model}" + if (card.fallbackUsed) " (fallback)" else "",
                style = MaterialTheme.typography.labelSmall, color = ai,
            )
            if (card.summary.isNotBlank()) Text(card.summary, color = ai)
            card.changes.forEachIndexed { i, ch ->
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(
                        checked = checked[i], enabled = card.decision == null, onCheckedChange = { checked[i] = it },
                    )
                    Text(
                        "${ch.factorId} ${ch.name}" + (if (ch.isNewFactor) " (new factor)" else "") + " · ${ch.action}",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (ch.action != "deactivate") {
                    if (ch.unitsAdd != null) {
                        Text("+${fmt(ch.unitsAdd)} u until the next dose" + (ch.amount?.let { " (${fmt(it, 0)})" } ?: ""), color = ai)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                weights[i], { v -> weights[i] = v.filter { it.isDigit() || it == '.' } }, label = { Text("Weight") },
                                singleLine = true, enabled = card.decision == null, modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            )
                            OutlinedTextField(
                                windows[i], { v -> windows[i] = v.filter(Char::isDigit) }, label = { Text("Window min") },
                                singleLine = true, enabled = card.decision == null, modifier = Modifier.weight(1f),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                        }
                        val w = weights[i].toDoubleOrNull()
                        when {
                            weights[i].isNotBlank() && w == null -> Text("Weight isn't a number", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            windows[i].isNotBlank() && windows[i].toIntOrNull() == null -> Text("Window isn't a whole number of minutes", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            w != null && ((ch.minWeight != null && w < ch.minWeight) || (ch.maxWeight != null && w > ch.maxWeight)) -> Text(
                                "Outside this factor's bounds (${ch.minWeight?.let { fmt(it) } ?: "–"} to ${ch.maxWeight?.let { fmt(it) } ?: "–"})",
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                ch.decay?.let { d -> Text("Decay: " + d.steps.joinToString { "${it.fromMinutes}m→${fmt(it.weight)}" }, style = MaterialTheme.typography.bodySmall, color = ai) }
                ch.startedMinutesAgo?.takeIf { it > 0 }?.let { Text("Started $it min ago", style = MaterialTheme.typography.bodySmall) }
                if (ch.reason.isNotBlank()) Text(ch.reason, style = MaterialTheme.typography.bodySmall, color = ai)
            }
            if (card.decision != null) {
                Text("✓ ${card.decidedMessage ?: card.decision}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            } else {
                // A typo in an edited field must not silently fall back to the default weight/window.
                val unreadable = card.changes.indices.any { i ->
                    checked[i] && card.changes[i].unitsAdd == null && card.changes[i].action != "deactivate" &&
                        ((weights[i].isNotBlank() && weights[i].toDoubleOrNull() == null) || (windows[i].isNotBlank() && windows[i].toIntOrNull() == null))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !unreadable, onClick = {
                        var edited = false
                        val accepted = card.changes.mapIndexedNotNull { i, ch ->
                            if (!checked[i]) return@mapIndexedNotNull null
                            val w = weights[i].toDoubleOrNull()
                            val win = windows[i].toIntOrNull()
                            if (w != ch.weight || win != ch.windowMinutes) {
                                if (ch.unitsAdd == null && ch.action != "deactivate") edited = true
                            }
                            if (ch.unitsAdd != null || ch.action == "deactivate") ch else ch.copy(weight = w, windowMinutes = win)
                        }
                        vm.decideAi(card, accepted, edited)
                    }) { Text("Accept") }
                    TextButton(onClick = { vm.decideAi(card, emptyList(), false) }) { Text("Reject all") }
                }
            }
        }
    }
}

@Composable
private fun SimpleCard(title: String, body: String, isError: Boolean = false) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isError) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun NbaCardView(card: NbaCard, vm: MainViewModel) {
    val r = card.result
    val colors = LocalGlucoseColors.current
    var editing by remember { mutableStateOf(false) }
    var showBreakdown by remember { mutableStateOf(true) }
    var bgEdit by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Next Best Action", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${r.finalUnits} u", fontSize = 56.sp, fontWeight = FontWeight.Bold)
                Column(Modifier.padding(bottom = 10.dp)) {
                    r.split?.let { Text("${it.firstUnits} u now + ${it.secondUnits} u at +${it.secondAfterMin} min", fontWeight = FontWeight.SemiBold) }
                    r.leadTimeMin?.let { Text(if (it == 0) "Eat now" else "Inject, then eat in $it min") }
                }
            }
            r.suggestedCarbsG?.let {
                Text("Projected below target — consider ~$it g carbs", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            }
            // BG used for this calculation
            val bg = card.input.bg
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (bg != null) "BG ${bg.roundToInt()} ${Trend.arrow(card.input.trendRate)}" else "BG unknown",
                    color = colors.forMgDl(bg?.roundToInt(), card.bgStale), fontWeight = FontWeight.SemiBold,
                )
                Text(
                    buildString {
                        card.input.trendRate?.let { append("${fmt(it, 1)}/min · ") }
                        append(card.bgAgeMinutes?.let { "$it min old" } ?: "no reading")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { bgEdit = true }) { Text("Use other BG") }
            }
            if (card.bgStale) {
                Text("⚠ CGM reading is stale (over 15 min) — check before dosing", color = MaterialTheme.colorScheme.error)
            }
            if (card.meal.description.isNotBlank() || card.meal.carbsG > 0) {
                Text(
                    "${card.meal.description.ifBlank { "Meal" }}: ${fmt(card.meal.carbsG, 0)} C / ${fmt(card.meal.fatG, 0)} F / " +
                        "${fmt(card.meal.proteinG, 0)} P" + (if (card.meal.liquidOrSugary) " · liquid/sugary" else "") +
                        (if (card.meal.isEstimate) " · AI estimate (confirmed)" else ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            ProfileCallout(card.profileLabel, card.profileVersion)
            TextButton(onClick = { showBreakdown = !showBreakdown }) { Text(if (showBreakdown) "Hide breakdown" else "Show breakdown") }
            if (showBreakdown) Breakdown(card.input, r, profileFor(card))
            Text("Calculated in ${card.computeMs} ms · ${formatTime(card.computedAt)}", style = MaterialTheme.typography.labelSmall)
            when {
                card.loggedMessage != null -> Text("✓ ${card.loggedMessage}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                card.dismissed -> Text("Dismissed — nothing logged", style = MaterialTheme.typography.bodySmall)
                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        vm.logNba(card, r.split?.firstUnits ?: r.finalUnits, r.split?.secondUnits ?: 0, null)
                    }) { Text("Log dose as shown") }
                    OutlinedButton(onClick = { editing = true }) { Text("Edit amount") }
                    TextButton(onClick = { vm.dismiss(card) }) { Text("Dismiss") }
                }
            }
        }
    }
    if (editing) {
        EditDoseDialog(card, onDismiss = { editing = false }) { now, later, reason ->
            editing = false
            vm.logNba(card, now, later, reason)
        }
    }
    if (bgEdit) {
        NumberPromptDialog("BG for this calculation (mg/dL)", "", onDismiss = { bgEdit = false }) {
            bgEdit = false
            vm.recompute(card, it)
        }
    }
}

/** The proposal stores the profile it used; the breakdown needs its numbers. */
@Composable
private fun profileFor(card: NbaCard): Profile =
    remember(card.proposalId) {
        card.profileVersion?.let { runCatching { app.meanwhile.domain.profile.ProfileJson.decode(it.profile) }.getOrNull() } ?: Profile()
    }

@Composable
fun ProfileCallout(label: String, version: app.meanwhile.data.db.ProfileVersionEntity?, queued: Int = 0, onClick: (() -> Unit)? = null) {
    val text = buildString {
        append("Profile $label")
        if (version != null) append(" · updated ${relativeTime(version.createdAt)} by ${sourceLabel(version.source)}")
        if (queued > 0) append(" · $queued AI refinement${if (queued == 1) "" else "s"} queued")
        if (onClick != null) append(" ›")
    }
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

fun sourceLabel(source: String) = when (source) {
    "learn_cycle" -> "learn cycle"
    "ai_update" -> "AI update"
    "offline_fallback" -> "offline fallback"
    "auto_f11" -> "auto (overnight highs)"
    "sleep_checkin" -> "sleep check-in"
    else -> source
}

@Composable
private fun EditDoseDialog(card: NbaCard, onDismiss: () -> Unit, onLog: (Int, Int, String?) -> Unit) {
    val split = card.result.split
    var now by remember { mutableStateOf((split?.firstUnits ?: card.result.finalUnits).toString()) }
    var later by remember { mutableStateOf((split?.secondUnits ?: 0).toString()) }
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit amount") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Proposed: ${card.result.finalUnits} u" + (split?.let { " (${it.firstUnits} + ${it.secondUnits})" } ?: ""))
                OutlinedTextField(now, { now = it.filter(Char::isDigit) }, label = { Text("Units now") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(later, { later = it.filter(Char::isDigit) }, label = { Text("Second injection later (0 = none)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(reason, { reason = it }, label = { Text("Why? (optional)") })
            }
        },
        confirmButton = {
            val nowUnits = now.toIntOrNull()
            val laterUnits = if (later.isEmpty()) 0 else later.toIntOrNull()
            TextButton(enabled = nowUnits != null && laterUnits != null, onClick = { onLog(nowUnits ?: 0, laterUnits ?: 0, reason) }) { Text("Log") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun NumberPromptDialog(title: String, initial: String, onDismiss: () -> Unit, onValue: (Double) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value, { value = it.filter { ch -> ch.isDigit() || ch == '.' } }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        },
        confirmButton = { TextButton(enabled = value.toDoubleOrNull() != null, onClick = { value.toDoubleOrNull()?.let(onValue) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FactorUpdateCardView(card: FactorUpdateCard, onUndo: () -> Unit) {
    val ai = LocalGlucoseColors.current.aiProposed
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Profile updated · ${sourceLabel(card.source)}" + (card.outcome.version?.let { " · v${it.version}" } ?: ""),
                style = MaterialTheme.typography.titleSmall,
            )
            card.changes.forEach { ch ->
                val what = when (ch.action) {
                    "add" -> "+${fmt(ch.units ?: 0.0)} u"
                    "deactivate" -> "ended (1.00)"
                    else -> "weight ${ch.weight?.let { fmt(it) } ?: "—"}"
                }
                Text("${ch.factorId} ${ch.name}: $what · ${ch.window}" + (ch.note?.let { " · $it" } ?: ""))
            }
            if (card.aiQueued) Text("AI refinement queued — you'll review it when online", color = ai, style = MaterialTheme.typography.bodySmall)
            if (card.undone) Text("Undone", color = MaterialTheme.colorScheme.error) else TextButton(onClick = onUndo) { Text("Undo") }
        }
    }
}

@Composable
private fun MealMacrosCardView(card: MealMacrosCard, onConfirm: (MealDraft) -> Unit) {
    var carbs by remember(card.key) { mutableStateOf(if (card.hasNumbers) fmt(card.draft.carbsG, 0) else "") }
    var fat by remember(card.key) { mutableStateOf(if (card.hasNumbers) fmt(card.draft.fatG, 0) else "") }
    var protein by remember(card.key) { mutableStateOf(if (card.hasNumbers) fmt(card.draft.proteinG, 0) else "") }
    var liquid by remember(card.key) { mutableStateOf(card.draft.liquidOrSugary) }
    val ai = LocalGlucoseColors.current.aiProposed
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Meal: ${card.draft.description.ifBlank { "—" }}", style = MaterialTheme.typography.titleSmall)
            card.note?.let { Text(it, color = if (card.draft.isEstimate) ai else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Triple("Carbs g", carbs) { v: String -> carbs = v }, Triple("Fat g", fat) { v: String -> fat = v }, Triple("Protein g", protein) { v: String -> protein = v })
                    .forEach { (label, value, set) ->
                        OutlinedTextField(
                            value, { set(it.filter { ch -> ch.isDigit() || ch == '.' }) }, label = { Text(label) }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                        )
                    }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(liquid, { liquid = it })
                Text("Liquid / sugary")
            }
            // "45..5" must never silently become 0 g of carbs.
            val unreadable = listOf("carbs" to carbs, "fat" to fat, "protein" to protein).filter { (_, v) -> v.isNotEmpty() && v.toDoubleOrNull() == null }
            if (unreadable.isNotEmpty()) {
                Text("Not a number: ${unreadable.joinToString { it.first }}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(
                enabled = (carbs.isNotEmpty() || fat.isNotEmpty() || protein.isNotEmpty()) && unreadable.isEmpty(),
                onClick = {
                    onConfirm(
                        card.draft.copy(
                            carbsG = carbs.toDoubleOrNull() ?: 0.0, fatG = fat.toDoubleOrNull() ?: 0.0,
                            proteinG = protein.toDoubleOrNull() ?: 0.0, liquidOrSugary = liquid,
                        ),
                    )
                },
            ) { Text(if (card.draft.isEstimate) "Confirm & calculate dose" else "Calculate dose") }
        }
    }
}

@Composable
private fun DoseConfirmCardView(card: DoseConfirmCard, vm: MainViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Log a dose", style = MaterialTheme.typography.titleSmall)
            if (card.loggedMessage != null) {
                Text("✓ ${card.loggedMessage}", color = MaterialTheme.colorScheme.primary)
            } else if (card.dismissed) {
                Text("Dismissed — nothing logged")
            } else {
                DoseConfirmFields(card, vm)
            }
        }
    }
}

@Composable
private fun DoseConfirmFields(card: DoseConfirmCard, vm: MainViewModel) {
    var units by remember(card.key) { mutableStateOf(if (card.units > 0) fmt(card.units, 0) else "") }
    var long by remember(card.key) { mutableStateOf(card.insulin == "long") }
    var minutesAgo by remember(card.key) { mutableStateOf(((System.currentTimeMillis() - card.givenAt) / 60_000).coerceAtLeast(0).toString()) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(units, { units = it.filter { ch -> ch.isDigit() || ch == '.' } }, label = { Text("Units") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
        OutlinedTextField(minutesAgo, { minutesAgo = it.filter(Char::isDigit) }, label = { Text("Min ago") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !long, onClick = { long = false }, label = { Text("Rapid (Humalog)") })
        FilterChip(selected = long, onClick = { long = true }, label = { Text("Long-acting") })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = (units.toDoubleOrNull() ?: 0.0) > 0, onClick = {
            val at = System.currentTimeMillis() - (minutesAgo.toLongOrNull() ?: 0) * 60_000
            vm.logDose(card, units.toDoubleOrNull() ?: 0.0, if (long) "long" else "rapid", at)
        }) { Text("Log") }
        TextButton(onClick = { vm.dismiss(card) }) { Text("Dismiss") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FactorPickerCardView(card: FactorPickerCard, profile: Profile, onPick: (String, String?) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Which factor?", style = MaterialTheme.typography.titleSmall)
            Text("Couldn't tell from “${card.text}”. Pick one — the default weight is applied (edit later in Profile).", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                profile.factors.filter { it.enabled && (it.kind == FactorKind.MULTIPLIER || it.kind == FactorKind.UNITS_PER_EVENT) }.forEach { def ->
                    if (def.presets.isEmpty()) {
                        AssistChip(onClick = { onPick(def.id, null) }, label = { Text("${def.id} ${def.name}") })
                    } else {
                        def.presets.keys.forEach { preset ->
                            AssistChip(onClick = { onPick(def.id, preset) }, label = { Text("${def.name}: $preset") })
                        }
                    }
                }
            }
        }
    }
}
