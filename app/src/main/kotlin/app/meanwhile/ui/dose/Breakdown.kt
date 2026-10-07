package app.meanwhile.ui.dose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.meanwhile.domain.dose.DoseInput
import app.meanwhile.domain.dose.DoseResult
import app.meanwhile.domain.profile.Profile
import app.meanwhile.format.fmt

/** Full transparent breakdown of a recommendation (spec §9.5): every term the engine used. */
@Composable
fun Breakdown(input: DoseInput, result: DoseResult, profile: Profile, modifier: Modifier = Modifier) {
    val d = profile.dose
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Line("Carbs ${fmt(input.carbsG, 0)} g ÷ ICR ${fmt(d.icr, 1)}", signed(result.carbDose))
        if (result.lowCarbMode && (input.fatG > 0 || input.proteinG > 0)) {
            Line("Low-carb mode (< ${fmt(profile.meal.lowCarbThresholdG, 0)} g carbs)", "")
            if (input.fatG > 0) Line("  Fat ${fmt(input.fatG, 0)} g ÷ ${fmt(profile.meal.fatGPerUnit, 0)}", signed(result.fatUnits))
            if (input.proteinG > 0) Line("  Protein ${fmt(input.proteinG, 0)} g ÷ ${fmt(profile.meal.proteinGPerUnit, 0)}", signed(result.proteinUnits))
        }
        val bg = input.bg
        if (bg != null) {
            Line("Correction (${fmt(bg, 0)} − ${fmt(d.target, 0)}) ÷ ISF ${fmt(d.isf, 0)}", signed(result.correction))
        } else {
            Line("Correction (no BG)", "0.00")
        }
        Line("Insulin on board", signed(-result.iob))
        if (result.cobUnits != 0.0) Line("Carbs still absorbing ÷ ICR ${fmt(d.icr, 1)}", signed(result.cobUnits))
        if (result.unexplainedUnits != 0.0) {
            Line(
                (if (result.unexplainedUnits > 0) "Rising" else "Falling") + " more than logged insulin & food explain ÷ ISF ${fmt(d.isf, 0)}",
                signed(result.unexplainedUnits),
            )
        }
        HorizontalDivider()
        Line("Baseline", fmt(result.baseline), bold = true)
        if (result.terms.isEmpty()) {
            Line("No active factors", "× 1.00")
        } else {
            result.terms.forEach { t ->
                Line("${t.factorId} ${t.name}${t.note?.let { " · $it" } ?: ""}", "${fmt(t.weight)} (${signed(t.contribution)})")
            }
            val capNote = when {
                result.capped -> " (capped from ${fmt(result.combinedUncapped)})"
                result.clampedAtZero -> " (clamped from ${fmt(result.combinedUncapped)})"
                else -> ""
            }
            Line("Combined 1 + Σ(w − 1)$capNote", "× ${fmt(result.combined)}", bold = true)
        }
        result.pendingUnits.forEach { p ->
            Line("${p.factorId} ${p.name} (${fmt(p.amount, 0)})", signed(p.units))
        }
        HorizontalDivider()
        Line("Raw = baseline × combined + added", fmt(result.raw))
        Line("Rounded to nearest ${fmt(d.unitIncrement, 0)} u (halves up, min 0)", "${result.finalUnits} u", bold = true)
        result.leadTimeMin?.let { lead ->
            Line("Lead time: ${result.leadTimeSteps.joinToString(" · ")}", "$lead min")
        }
        result.warnings.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun Line(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
    }
}

private fun signed(x: Double): String = (if (x >= 0) "+" else "−") + fmt(kotlin.math.abs(x))
