package app.meanwhile.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.meanwhile.data.dose.DoseContext
import app.meanwhile.domain.dose.DoseEngine
import app.meanwhile.domain.dose.DoseInput
import app.meanwhile.domain.dose.FactorWeight
import app.meanwhile.domain.dose.PendingUnits
import app.meanwhile.domain.profile.FactorKind
import app.meanwhile.ui.common.LocalAppContainer
import app.meanwhile.ui.common.ScreenScaffold
import app.meanwhile.ui.common.SectionCard
import app.meanwhile.ui.common.fmt
import app.meanwhile.ui.dose.Breakdown

/**
 * M4 debug calculator: any input, the live context pre-filled, full breakdown. Nothing is logged.
 * Blank BG / IOB fields use the live values.
 */
@Composable
fun DoseDebugScreen(onBack: () -> Unit) {
    val c = LocalAppContainer.current
    var ctx by remember { mutableStateOf<DoseContext?>(null) }
    LaunchedEffect(Unit) { ctx = c.doseContext.build() }

    var carbs by remember { mutableStateOf("60") }
    var fat by remember { mutableStateOf("0") }
    var protein by remember { mutableStateOf("0") }
    var bg by remember { mutableStateOf("") }
    var trend by remember { mutableStateOf("") }
    var iob by remember { mutableStateOf("") }
    var cups by remember { mutableStateOf("0") }
    var liquid by remember { mutableStateOf(false) }
    val extra = remember { mutableStateMapOf<String, String>() }

    ScreenScaffold(title = "Dose calculator (debug)", onBack = onBack) {
        val live = ctx
        if (live == null) {
            Text("Loading live context…")
            return@ScreenScaffold
        }
        val profile = live.profile.profile
        Text(
            "Profile ${live.profile.versionLabel} · live BG ${live.latest?.mgDl ?: "—"} · " +
                "trend ${live.trendRate?.let { fmt(it, 1) } ?: "—"} · IOB ${fmt(live.iob)} u. Nothing here is logged.",
            style = MaterialTheme.typography.bodySmall,
        )
        SectionCard("Meal") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Carbs g", carbs, { carbs = it }, Modifier.weight(1f))
                NumberField("Fat g", fat, { fat = it }, Modifier.weight(1f))
                NumberField("Protein g", protein, { protein = it }, Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(liquid, { liquid = it })
                Text("Liquid / sugary")
            }
        }
        SectionCard("Context (blank = live)") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("BG", bg, { bg = it }, Modifier.weight(1f))
                NumberField("Trend /min", trend, { trend = it }, Modifier.weight(1f), signed = true)
                NumberField("IOB u", iob, { iob = it }, Modifier.weight(1f))
            }
            NumberField("Extra coffee cups", cups, { cups = it }, Modifier.fillMaxWidth())
        }
        SectionCard("Extra factors (tap to add; weight editable)") {
            profile.factors.filter { it.kind == FactorKind.MULTIPLIER && it.enabled }.forEach { def ->
                val selected = def.id in extra
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selected,
                        onClick = { if (selected) extra.remove(def.id) else extra[def.id] = fmt(def.defaultWeight ?: 1.0) },
                        label = { Text("${def.id} ${def.name}") },
                    )
                    if (selected) NumberField("w", extra[def.id] ?: "", { extra[def.id] = it }, Modifier.weight(1f))
                }
            }
        }

        val liveInput = live.input(
            carbsG = carbs.toDoubleOrNull() ?: 0.0,
            fatG = fat.toDoubleOrNull() ?: 0.0,
            proteinG = protein.toDoubleOrNull() ?: 0.0,
            liquidOrSugary = liquid,
            bgOverride = bg.toDoubleOrNull(),
        )
        val extraWeights = extra.mapNotNull { (id, w) ->
            w.toDoubleOrNull()?.let { FactorWeight(id, profile.factor(id)?.name ?: id, it, "debug") }
        }
        val extraCups = cups.toDoubleOrNull() ?: 0.0
        val unitsPerCup = profile.factor("F4")?.unitsPerEvent ?: 1.0
        val input: DoseInput = liveInput.copy(
            trendRate = trend.toDoubleOrNull() ?: liveInput.trendRate,
            iob = iob.toDoubleOrNull() ?: liveInput.iob,
            factors = liveInput.factors.filter { f -> extraWeights.none { it.factorId == f.factorId } } + extraWeights,
            pendingUnits = liveInput.pendingUnits +
                if (extraCups > 0) listOf(PendingUnits("F4", "Caffeine", extraCups * unitsPerCup, extraCups)) else emptyList(),
        )
        val result = DoseEngine.compute(input, profile)
        SectionCard("Result: ${result.finalUnits} u" + (result.split?.let { " (${it.firstUnits} now + ${it.secondUnits} at +${it.secondAfterMin} min)" } ?: "")) {
            result.suggestedCarbsG?.let { Text("Projected below target — consider ~$it g carbs", color = MaterialTheme.colorScheme.error) }
            Breakdown(input, result, profile)
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier, signed: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' || (signed && it == '-') }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}
