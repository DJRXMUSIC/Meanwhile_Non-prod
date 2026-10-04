package app.meanwhile.ui.settings

import app.meanwhile.ui.common.rememberSafeScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.meanwhile.data.export.ExportResult
import app.meanwhile.ui.common.LocalAppContainer
import app.meanwhile.ui.common.ScreenScaffold
import app.meanwhile.ui.common.SectionCard
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(onBack: () -> Unit) {
    val c = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberSafeScope()
    val today = LocalDate.now()
    var start by remember { mutableStateOf(today.minusDays(29)) }
    var end by remember { mutableStateOf(today) }
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ExportResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    if (picking) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            initialSelectedEndDateMillis = end.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        val s = state.selectedStartDateMillis
                        if (s != null) {
                            start = utcDate(s)
                            end = utcDate(state.selectedEndDateMillis ?: s)
                            result = null
                        }
                        picking = false
                    },
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) {
            DateRangePicker(state = state, modifier = Modifier.weight(1f))
        }
    }

    ScreenScaffold(title = "Export", onBack = onBack) {
        SectionCard("Date range") {
            Text("$start → $end")
            OutlinedButton(onClick = { picking = true }) { Text("Change dates") }
        }
        Button(
            enabled = !busy,
            onClick = {
                busy = true
                error = null
                scope.launch {
                    val zone = ZoneId.systemDefault()
                    val from = start.atStartOfDay(zone).toInstant().toEpochMilli()
                    val to = end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
                    runCatching { c.exporter.export(from, to) }
                        .onSuccess { result = it }
                        .onFailure { error = it.message }
                    busy = false
                }
            },
        ) { Text(if (busy) "Exporting…" else "Build export") }
        error?.let { Text("Export failed: $it", color = MaterialTheme.colorScheme.error) }

        result?.let { r ->
            SectionCard("All tables") {
                Text("${r.rowsByTable.values.sum()} rows in ${r.csvByTable.size} CSV files")
                Button(onClick = { context.startActivity(c.exporter.shareIntent(r.zip)) }) { Text("Share zip") }
            }
            SectionCard("Single table") {
                r.csvByTable.forEach { (table, file) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("$table (${r.rowsByTable[table] ?: 0})")
                        TextButton(onClick = { context.startActivity(c.exporter.shareIntent(file)) }) { Text("Share CSV") }
                    }
                }
            }
        }
    }
}

private fun utcDate(epochMillis: Long): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).toLocalDate()

