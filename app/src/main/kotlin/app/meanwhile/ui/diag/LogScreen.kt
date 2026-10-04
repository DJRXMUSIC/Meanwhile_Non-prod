package app.meanwhile.ui.diag

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.meanwhile.log.AppLog
import app.meanwhile.ui.common.LocalAppContainer
import app.meanwhile.ui.common.ScreenScaffold
import app.meanwhile.ui.common.rememberSafeScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The app log, newest first, with a one-tap copy of the full diagnostics report for an AI assistant. */
@Composable
fun LogScreen(onBack: () -> Unit) {
    val c = LocalAppContainer.current
    val scope = rememberSafeScope()
    val clipboard = LocalClipboardManager.current
    var problemsOnly by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val entries by produceState(emptyList<AppLog.Entry>(), reload) {
        value = withContext(Dispatchers.IO) { AppLog.entries().asReversed().take(800) }
    }

    ScreenScaffold(
        title = "App log",
        onBack = onBack,
        actions = {
            TextButton(onClick = {
                scope.launch {
                    clipboard.setText(AnnotatedString(c.diagnostics.report(tailLines = 150)))
                    message = "Diagnostics copied — paste it into your AI assistant"
                }
            }) { Text("Copy for AI") }
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !problemsOnly, onClick = { problemsOnly = false }, label = { Text("All") })
            FilterChip(selected = problemsOnly, onClick = { problemsOnly = true }, label = { Text("Problems") })
            TextButton(onClick = { reload++ }) { Text("Refresh") }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        val shown = if (problemsOnly) entries.filter { it.isProblem } else entries
        if (shown.isEmpty()) Text(if (problemsOnly) "No warnings or errors logged." else "The log is empty.")
        SelectionContainer {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                shown.forEach { e ->
                    Text(
                        e.toString(),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                        color = when (e.level) {
                            AppLog.Level.ERROR -> MaterialTheme.colorScheme.error
                            AppLog.Level.WARN -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}
