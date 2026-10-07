package app.meanwhile.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.meanwhile.R
import app.meanwhile.data.db.ConversationLogEntity
import app.meanwhile.data.input.ConversationLog
import app.meanwhile.data.input.DoseLoggedCard
import app.meanwhile.data.input.MealLoggedCard
import app.meanwhile.data.input.NbaCard
import app.meanwhile.data.input.Step
import app.meanwhile.data.input.StepState
import app.meanwhile.domain.cgm.Trend
import app.meanwhile.domain.nba.ActionKind
import app.meanwhile.domain.profile.Profile
import app.meanwhile.domain.router.OfflineRouter
import app.meanwhile.format.fmt
import app.meanwhile.format.formatTime
import app.meanwhile.format.formatUnits
import app.meanwhile.ui.common.rememberNow
import app.meanwhile.ui.dose.Breakdown
import app.meanwhile.ui.theme.LocalGlucoseColors
import app.meanwhile.ui.theme.forMgDl
import java.util.Locale
import kotlin.math.roundToInt

/** One message: what Danny said, what the app is doing about it, and the answer. */
@Composable
fun TurnView(turn: Turn, profile: Profile, vm: MainViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        UserBubble(turn.text, turn.via, turn.at)
        StepsView(turn, vm)
        turn.error?.let { AppBubble(it, isError = true) }
        turn.session?.let { s ->
            s.cards.forEach { card -> ResultCardView(card, profile, vm) }
            RouteFooter(turn, vm)
        }
    }
}

@Composable
private fun UserBubble(text: String, via: String, at: Long, muted: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth(0.85f)) {
            Surface(
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp),
                color = if (muted) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    text, style = if (muted) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (via == "voice") Icon(painterResource(R.drawable.ic_mic), contentDescription = "said", modifier = Modifier.size(12.dp))
                Text(
                    (if (via == "voice") "said" else if (via == "text") "typed" else via) + " · " + formatTime(at),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun AppBubble(text: String, isError: Boolean = false, muted: Boolean = false, at: Long? = null) {
    Column(Modifier.fillMaxWidth(0.9f)) {
        Surface(
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp),
            color = when {
                isError -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ) {
            Text(
                text,
                style = if (muted) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        at?.let { Text(formatTime(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** A line of the earlier conversation (from the log): read-only. */
@Composable
fun HistoryLine(e: ConversationLogEntity) {
    when (e.kind) {
        ConversationLog.KIND_MESSAGE -> UserBubble(e.text, if (e.details.contains("\"via\":\"voice\"")) "voice" else "text", e.recordedAt, muted = true)
        ConversationLog.KIND_REPLY -> AppBubble(e.text, muted = true, at = e.recordedAt)
        ConversationLog.KIND_ERROR -> AppBubble(e.text, isError = true, muted = true, at = e.recordedAt)
        else -> Text(
            "• ${e.text}", style = MaterialTheme.typography.labelSmall, fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** What the app is doing right now (each step live), folded to one line once answered. */
@Composable
private fun StepsView(turn: Turn, vm: MainViewModel) {
    if (turn.working) {
        val now by rememberNow(500)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (turn.steps.isEmpty()) StepRow(Step("received", "Got it — starting", StepState.RUNNING, startedAt = turn.at), now.toEpochMilli())
            turn.steps.forEach { StepRow(it, now.toEpochMilli()) }
            val running = turn.steps.lastOrNull { it.state == StepState.RUNNING }
            if (running != null && running.label.endsWith("(AI)") && now.toEpochMilli() - running.startedAt > 10_000) {
                TextButton(onClick = vm::skipAi) { Text("Don't wait — answer without AI") }
            }
        }
    } else if (turn.steps.isNotEmpty()) {
        var open by remember(turn.id) { mutableStateOf(false) }
        val total = (turn.finishedAt ?: turn.at) - turn.at
        val failed = turn.steps.count { it.state == StepState.FAILED }
        Column {
            Text(
                (if (failed > 0) "⚠ " else "✓ ") + "Done in ${seconds(total)} · ${turn.steps.size} step${if (turn.steps.size == 1) "" else "s"}" + if (open) " ▴" else " ▾",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { open = !open },
            )
            if (open) turn.steps.forEach { StepRow(it, null) }
        }
    }
}

@Composable
private fun StepRow(step: Step, now: Long?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (step.state) {
            StepState.RUNNING -> CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            StepState.DONE -> Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            StepState.FAILED -> Text("✕", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        }
        val elapsed = when {
            step.endedAt != null -> seconds(step.endedAt - step.startedAt)
            now != null -> "${((now - step.startedAt) / 1000).coerceAtLeast(0)} s"
            else -> ""
        }
        Text(
            step.label + (if (step.state == StepState.RUNNING) "…" else "") + (step.detail?.let { " — $it" } ?: "") + "  $elapsed",
            style = MaterialTheme.typography.bodySmall,
            color = if (step.state == StepState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** How the message was understood, with the one-tap path switch (spec §9.2) — out of the way. */
@Composable
private fun RouteFooter(turn: Turn, vm: MainViewModel) {
    val s = turn.session ?: return
    var open by remember(turn.id) { mutableStateOf(false) }
    Column {
        Text(
            "Wrong? Treat it as something else" + if (open) " ▴" else " ›",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable { open = !open },
        )
        if (open) PathHeader(s, onSwitch = { vm.switchPath(turn, it) })
    }
}

private fun seconds(ms: Long) = if (ms < 1000) "$ms ms" else String.format(Locale.US, "%.1f s", ms / 1000.0)

/**
 * The Next Best Action, clear first (1.4): one action in big type, one line of timing, the buttons
 * that log it. Everything behind it — BG used, the full dose math, the profile, why this action —
 * is one tap away under "Why?".
 */
@Composable
fun AnswerCard(card: NbaCard, profile: Profile, vm: MainViewModel) {
    val a = card.action
    var why by remember(card.key) { mutableStateOf(false) }
    var editing by remember(card.key) { mutableStateOf(false) }
    var bgEdit by remember(card.key) { mutableStateOf(false) }
    var carbsEdit by remember(card.key) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val (container, onContainer) = when (a.kind) {
        ActionKind.TAKE_INSULIN, ActionKind.SPLIT_INSULIN -> scheme.primaryContainer to scheme.onPrimaryContainer
        ActionKind.TREAT_LOW -> scheme.errorContainer to scheme.onErrorContainer
        ActionKind.EAT_CARBS, ActionKind.EAT_NO_INSULIN -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        ActionKind.NOTHING, ActionKind.CHECK_BG -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container, contentColor = onContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Next best action", style = MaterialTheme.typography.labelMedium)
            Text(a.headline, fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
            if (a.detail.isNotBlank()) Text(a.detail, style = MaterialTheme.typography.titleMedium)
            if (card.bgStale && a.kind != ActionKind.CHECK_BG) {
                Text(
                    "⚠ The CGM reading is ${card.bgAgeMinutes?.let { "$it min" } ?: "too"} old — check your BG first",
                    color = scheme.error, fontWeight = FontWeight.SemiBold,
                )
            }
            when {
                card.loggedMessage != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✓ ${card.loggedMessage}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    val doseId = card.loggedDoseId
                    if (doseId != null) {
                        TextButton(onClick = { vm.undoDose(doseId, card.computedAt - 1, card.key) }) { Text("Undo") }
                    }
                }
                card.dismissed -> Text("Dismissed — nothing logged", style = MaterialTheme.typography.bodySmall)
                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (a.kind) {
                        ActionKind.TAKE_INSULIN, ActionKind.SPLIT_INSULIN -> {
                            Button(onClick = { vm.logNba(card, a.unitsNow, a.unitsLater, null) }) { Text("I took ${a.unitsNow} u") }
                            OutlinedButton(onClick = { editing = true }) { Text("Different amount") }
                        }
                        ActionKind.TREAT_LOW, ActionKind.EAT_CARBS, ActionKind.EAT_NO_INSULIN -> {
                            Button(onClick = { vm.ateIt(card) }) { Text(if ((a.carbsG ?: 0) > 0) "I ate ${a.carbsG} g" else "I ate it") }
                            OutlinedButton(onClick = { carbsEdit = true }) { Text("Different amount") }
                        }
                        ActionKind.CHECK_BG -> Button(onClick = { bgEdit = true }) { Text("Enter my BG") }
                        ActionKind.NOTHING -> Unit
                    }
                }
            }
            TextButton(onClick = { why = !why }) { Text(if (why) "Hide the details" else "Why? (full breakdown)") }
            if (why) Audit(card, onUseOtherBg = { bgEdit = true })
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
    if (carbsEdit) {
        val hasMeal = card.meal.carbsG > 0
        NumberPromptDialog(
            if (hasMeal) "Extra carbs on top of the meal (g)" else "Carbs you ate (g)", (a.carbsG ?: 0).toString(), onDismiss = { carbsEdit = false },
        ) {
            carbsEdit = false
            vm.ateIt(card, it)
        }
    }
}

/** Everything behind the action: decision path, BG used, meal, profile and the full dose math. */
@Composable
private fun Audit(card: NbaCard, onUseOtherBg: () -> Unit) {
    val colors = LocalGlucoseColors.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        card.action.why.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        val bg = card.input.bg
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when {
                    card.bgOverride != null -> "BG you gave: ${card.bgOverride.roundToInt()}"
                    bg != null -> "BG used: ${bg.roundToInt()} ${Trend.arrow(card.input.trendRate)}"
                    else -> "No BG"
                },
                color = colors.forMgDl(bg?.roundToInt(), card.bgStale), fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = onUseOtherBg) { Text("Use another BG") }
        }
        if (card.meal.carbsG > 0 || card.meal.fatG > 0 || card.meal.proteinG > 0) {
            Text(
                "${card.meal.description.takeIf { it.isNotBlank() && it != OfflineRouter.CHECK_DESCRIPTION } ?: "Meal"}: " +
                    "${fmt(card.meal.carbsG, 0)} C / ${fmt(card.meal.fatG, 0)} F / ${fmt(card.meal.proteinG, 0)} P" +
                    (if (card.meal.liquidOrSugary) " · liquid/sugary" else "") + (if (card.meal.isEstimate) " · AI estimate (confirmed)" else ""),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        ProfileCallout(card.profileLabel, card.profileVersion)
        Breakdown(card.input, card.result, profileFor(card))
        Text("Calculated in ${card.computeMs} ms · ${formatTime(card.computedAt)}", style = MaterialTheme.typography.labelSmall)
    }
}

/** A dose Danny said he took (already logged), or a correction of one. */
@Composable
fun DoseLoggedView(card: DoseLoggedCard, vm: MainViewModel) {
    var editing by remember(card.key) { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                (if (card.dose.units > 0) "✓ " else "") + card.message,
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            )
            card.previous?.let {
                Text("Was ${formatUnits(it.units)} at ${formatTime(it.givenAt)} — kept in the record, no longer counted", style = MaterialTheme.typography.bodySmall)
            }
            if (card.replacedMessage != null) {
                Text(card.replacedMessage, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
            } else {
                Row {
                    if (card.dose.units > 0) TextButton(onClick = { vm.undoDose(card.dose.id, card.dose.createdAt - 1, card.key) }) { Text("Undo") }
                    TextButton(onClick = { editing = true }) { Text(if (card.dose.units > 0) "Edit" else "Log an amount") }
                }
            }
        }
    }
    if (editing) {
        EditLoggedDoseDialog(card, onDismiss = { editing = false }) { units, minutesAgo ->
            editing = false
            vm.editDose(card, units, minutesAgo)
        }
    }
}

@Composable
private fun EditLoggedDoseDialog(card: DoseLoggedCard, onDismiss: () -> Unit, onSave: (Double, Long?) -> Unit) {
    var units by remember { mutableStateOf(if (card.dose.units > 0) fmt(card.dose.units, if (card.dose.units % 1.0 == 0.0) 0 else 1) else "") }
    var minutesAgo by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change this dose") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Logged: ${formatUnits(card.dose.units)} at ${formatTime(card.dose.givenAt)}. The change is a new record; the old one stays.")
                OutlinedTextField(
                    units, { units = it.filter { ch -> ch.isDigit() || ch == '.' } }, label = { Text("Units (0 = didn't take it)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    minutesAgo, { minutesAgo = it.filter(Char::isDigit) }, label = { Text("Taken how many minutes ago? (blank = keep the time)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            val u = units.toDoubleOrNull()
            TextButton(enabled = u != null && u >= 0, onClick = { onSave(u ?: 0.0, minutesAgo.toLongOrNull()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun MealLoggedView(card: MealLoggedCard) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Text("✓ ${card.message}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
    }
}
