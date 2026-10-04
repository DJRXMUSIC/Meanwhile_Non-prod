package app.meanwhile.ui.profile

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.meanwhile.data.profile.ProfileSource
import app.meanwhile.data.profile.ProfileState
import app.meanwhile.data.profile.ProfileStatus
import app.meanwhile.domain.profile.Profile
import app.meanwhile.domain.profile.ProfileChange
import app.meanwhile.domain.profile.ProfileJson
import app.meanwhile.domain.profile.ProfilePatch
import app.meanwhile.ui.common.LocalAppContainer
import app.meanwhile.ui.common.ScreenScaffold
import app.meanwhile.ui.common.SectionCard
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private val prettyPrinter = Json { prettyPrint = true }

fun prettyJson(text: String): String =
    runCatching { prettyPrinter.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(text)) }.getOrDefault(text)

/** Every scalar setting by path (spec §1.6: everything tunable). */
private val SETTINGS = listOf(
    "Dose" to listOf(
        "dose.icr" to "ICR — grams per unit", "dose.isf" to "ISF — mg/dL per unit", "dose.target" to "Target BG",
        "dose.combinedCap" to "Combined multiplier cap", "dose.unitIncrement" to "Pen increment (u)",
    ),
    "Insulin action" to listOf("iob.delayMin" to "Delay (min)", "iob.peakMin" to "Peak (min)", "iob.durationMin" to "Duration (min)"),
    "Lead time" to listOf(
        "leadTime.baseMin" to "Base (min)", "leadTime.eatNowBelowBg" to "Eat now below BG", "leadTime.eatNowTrendAtOrBelow" to "Eat now at trend ≤ (mg/dL/min)",
        "leadTime.highBgStart" to "High BG starts at", "leadTime.highBgStepMgDl" to "… per mg/dL step", "leadTime.highBgStepMin" to "… add minutes per step",
        "leadTime.liquidOrSugaryMin" to "Liquid/sugary (min)", "leadTime.highFatG" to "High fat threshold (g)", "leadTime.highFatMin" to "High fat (min)",
        "leadTime.minMin" to "Minimum (min)", "leadTime.maxMin" to "Maximum (min)",
    ),
    "Fat & protein" to listOf(
        "meal.lowCarbThresholdG" to "Low-carb threshold (g carbs)", "meal.kFatPerG" to "K_FAT per g", "meal.kProteinPerG" to "K_PROTEIN per g",
        "meal.fatGPerUnit" to "Low-carb: fat g per unit", "meal.proteinGPerUnit" to "Low-carb: protein g per unit",
    ),
    "Split dose" to listOf(
        "split.minFatG" to "Fat ≥ (g)", "split.minProteinG" to "Protein ≥ (g)", "split.minCarbsG" to "Carbs ≥ (g)",
        "split.firstFraction" to "First injection fraction", "split.secondAfterMin" to "Second after (min)",
    ),
    "Daily reset" to listOf("resetHour" to "Reset hour (0–23)"),
)

@Composable
fun EditSettingsScreen(onBack: () -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val state by c.profiles.current.collectAsStateWithLifecycle(initialValue = ProfileState(Profile(), null))
    val values = remember { mutableStateMapOf<String, String>() }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.version?.id) {
        SETTINGS.flatMap { it.second }.forEach { (path, _) ->
            values[path] = ProfilePatch.get(state.profile, path)?.jsonPrimitive?.contentOrNull ?: ""
        }
    }
    ScreenScaffold(title = "Edit settings", onBack = onBack) {
        Text("Saved as a new profile version (source: manual). Danny's edits apply immediately.", style = MaterialTheme.typography.bodySmall)
        SETTINGS.forEach { (section, fields) ->
            SectionCard(section) {
                fields.forEach { (path, label) ->
                    OutlinedTextField(
                        value = values[path] ?: "",
                        onValueChange = { v -> values[path] = v.filter { it.isDigit() || it == '.' || it == '-' } },
                        label = { Text(label) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            val changes = SETTINGS.flatMap { it.second }.mapNotNull { (path, _) ->
                val old = ProfilePatch.get(state.profile, path)?.jsonPrimitive ?: return@mapNotNull null
                val text = values[path] ?: return@mapNotNull null
                if (text == old.contentOrNull) return@mapNotNull null
                val isInt = old.contentOrNull?.contains('.') == false
                val new = if (isInt) text.toDoubleOrNull()?.toLong()?.let { JsonPrimitive(it) } else text.toDoubleOrNull()?.let { JsonPrimitive(it) }
                new?.let { ProfileChange(path, old, it) }
            }
            ProfilePatch.apply(state.profile, changes).fold(
                onSuccess = { updated ->
                    scope.launch {
                        if (changes.isNotEmpty()) {
                            c.profiles.saveVersion(updated, ProfileSource.MANUAL, ProfileStatus.ACCEPTED, "Manual: " + changes.joinToString { it.path }, changes = changes)
                        }
                        onBack()
                    }
                },
                onFailure = { error = "Not saved: ${it.message}" },
            )
        }) { Text("Save") }
    }
}

/** Edit any part of the profile (or a whole factor definition) as JSON. Validated before saving. */
@Composable
fun JsonEditScreen(path: String, onBack: () -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val state by c.profiles.current.collectAsStateWithLifecycle(initialValue = ProfileState(Profile(), null))
    var text by remember { mutableStateOf("") }
    var loadedFor by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val key = "${state.version?.id}:$path"
    if (loadedFor != key) {
        loadedFor = key
        text = if (path.isBlank()) prettyJson(ProfileJson.encode(state.profile)) else prettyJson(ProfilePatch.get(state.profile, path)?.toString() ?: "{}")
    }
    ScreenScaffold(title = if (path.isBlank()) "Profile JSON" else path, onBack = onBack) {
        Text(
            "Advanced: change any value, add a factor (append to \"factors\"), or adjust windows/decay. Invalid JSON is rejected.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            val result = runCatching {
                if (path.isBlank()) ProfileJson.decode(text) else {
                    ProfilePatch.apply(state.profile, listOf(ProfileChange(path, ProfilePatch.get(state.profile, path), Json.parseToJsonElement(text)))).getOrThrow()
                }
            }
            result.fold(
                onSuccess = { updated ->
                    scope.launch {
                        if (updated != state.profile) {
                            c.profiles.saveVersion(updated, ProfileSource.MANUAL, ProfileStatus.ACCEPTED, "Manual JSON edit: ${path.ifBlank { "profile" }}")
                        }
                        onBack()
                    }
                },
                onFailure = { error = "Not saved: ${it.message}" },
            )
        }) { Text("Validate & save") }
    }
}
