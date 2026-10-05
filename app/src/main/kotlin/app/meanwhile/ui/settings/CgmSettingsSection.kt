package app.meanwhile.ui.settings

import app.meanwhile.ui.common.rememberSafeScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.meanwhile.data.cgm.EversenseSource
import app.meanwhile.data.cgm.XdripWebSource
import app.meanwhile.data.settings.AppSettings
import app.meanwhile.service.CgmService
import app.meanwhile.ui.common.LocalAppContainer
import app.meanwhile.ui.common.SectionCard
import app.meanwhile.format.formatTime
import app.meanwhile.format.relativeTime
import app.meanwhile.ui.setup.rememberSetupState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
fun CgmSettingsSection(current: AppSettings) {
    val c = LocalAppContainer.current
    val scope = rememberSafeScope()
    var url by remember { mutableStateOf(current.xdripBaseUrl) }
    var path by remember { mutableStateOf(current.xdripPath) }
    var secret by remember { mutableStateOf(current.xdripApiSecret) }
    var poll by remember { mutableStateOf(current.xdripPollSeconds.toString()) }
    var stale by remember { mutableStateOf(current.staleMinutes.toString()) }
    var broadcast by remember { mutableStateOf(current.xdripBroadcastEnabled) }
    var result by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(current) {
        url = current.xdripBaseUrl
        path = current.xdripPath
        secret = current.xdripApiSecret
        poll = current.xdripPollSeconds.toString()
        stale = current.staleMinutes.toString()
        broadcast = current.xdripBroadcastEnabled
    }

    val feed by c.cgmStatus.collectAsStateWithLifecycle()
    val setup = rememberSetupState()
    SectionCard("CGM — Eversense app (built in)") {
        Text(
            when {
                !setup.eversenseAccess -> "Meanwhile needs notification access to read the Eversense app's glucose notification."
                feed.eversenseSavedAt != null -> "Working · last reading saved ${relativeTime(feed.eversenseSavedAt!!)}"
                else -> "Notification access granted · waiting for the Eversense app's next reading (every 5 min)."
            },
        )
        feed.eversenseMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        feed.eversenseTexts?.let {
            Text("What Meanwhile sees: $it", style = MaterialTheme.typography.bodySmall)
        }
        if (!setup.eversenseAccess) {
            Button(onClick = { EversenseSource.openAccessSettings(c.app) }) { Text("Allow notification access") }
            Text(
                "Switch greyed out? Settings → Apps → Meanwhile → ⋮ → Allow restricted settings, then try again. " +
                    "The Eversense app's own notifications must stay on.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    SectionCard("CGM — xDrip+ (optional: back-fills gaps)") {
        OutlinedTextField(url, { url = it }, label = { Text("Web service address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(path, { path = it }, label = { Text("Path") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            secret, { secret = it }, label = { Text("API secret (only if xDrip+ requires one)") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                poll, { poll = it.filter(Char::isDigit) }, label = { Text("Poll every (s)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                stale, { stale = it.filter(Char::isDigit) }, label = { Text("Stale after (min)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = broadcast, onCheckedChange = { broadcast = it })
            Text("Also listen to xDrip+ local broadcasts")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                val base = url.trim().ifBlank { AppSettings.DEFAULT_XDRIP_URL }
                if (XdripWebSource.urlFor(base, path.ifBlank { AppSettings.DEFAULT_XDRIP_PATH }) == null) {
                    result = "Not saved: the address must start with http:// (e.g. ${AppSettings.DEFAULT_XDRIP_URL})"
                    return@Button
                }
                scope.launch {
                    c.settings.update {
                        it.copy(
                            xdripBaseUrl = base,
                            xdripPath = path.trim().ifBlank { AppSettings.DEFAULT_XDRIP_PATH },
                            xdripApiSecret = secret.trim(),
                            xdripPollSeconds = poll.toIntOrNull() ?: 60,
                            staleMinutes = stale.toIntOrNull() ?: 15,
                            xdripBroadcastEnabled = broadcast,
                        )
                    }
                    result = "Saved"
                }
            }) { Text("Save") }
            OutlinedButton(onClick = {
                result = "Testing…"
                scope.launch {
                    result = try {
                        val s = c.settings.current().copy(xdripBaseUrl = url.trim(), xdripPath = path.trim(), xdripApiSecret = secret.trim())
                        val readings = c.xdripWeb.fetch(s, 3)
                        val last = readings.lastOrNull()
                        if (last == null) "Connected, but xDrip+ returned no readings" else
                            "OK — ${last.mgDl} mg/dL at ${formatTime(last.timestamp.toEpochMilli())}"
                    } catch (e: Exception) {
                        "Failed: ${e.message}"
                    }
                }
            }) { Text("Test connection") }
        }
        OutlinedButton(onClick = {
            CgmService.start(c.app)
            scope.launch { result = "Back-filled ${c.cgmIntake.backCapture()} readings" }
        }) { Text("Back-fill now") }
        result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
