package app.meanwhile.ui.setup

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.meanwhile.notify.Notifications
import app.meanwhile.ui.common.LocalAppContainer
import app.meanwhile.ui.common.ScreenScaffold
import app.meanwhile.ui.common.SectionCard
import app.meanwhile.ui.common.relativeTime

/** What still needs granting for reliable background operation (spec §13.3). */
data class SetupState(
    val notifications: Boolean,
    val batteryExempt: Boolean,
    val exactAlarms: Boolean,
    val microphone: Boolean,
) {
    val missing: Int get() = listOf(notifications, batteryExempt, exactAlarms, microphone).count { !it }

    companion object {
        fun read(context: Context): SetupState {
            val pm = context.getSystemService(PowerManager::class.java)
            val am = context.getSystemService(AlarmManager::class.java)
            return SetupState(
                notifications = Notifications.canPost(context),
                batteryExempt = pm.isIgnoringBatteryOptimizations(context.packageName),
                exactAlarms = am.canScheduleExactAlarms(),
                microphone = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED,
            )
        }
    }
}

/** Re-reads [SetupState] every time the screen resumes (after returning from system settings). */
@Composable
fun rememberSetupState(): SetupState {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    return remember(tick) { SetupState.read(context) }
}

@SuppressLint("BatteryLife") // a sideloaded medical-support app that must not be killed
@Composable
fun SetupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = LocalAppContainer.current
    // Permission dialogs pause/resume the activity, so rememberSetupState refreshes by itself.
    val state = rememberSetupState()
    val feed by c.cgmStatus.collectAsStateWithLifecycle()
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    ScreenScaffold(title = "Setup", onBack = onBack) {
        Text(
            "Meanwhile runs a foreground service so it never misses a CGM reading, fires exact alarms for the " +
                "1 am learn cycle, 6 am overnight check and split-dose reminders, and listens for your voice.",
            style = MaterialTheme.typography.bodyMedium,
        )
        SetupRow(
            "Notifications",
            "Live BG notification, split-dose reminders, morning report, stale-CGM and sync alerts.",
            state.notifications,
        ) { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        SetupRow(
            "Unrestricted battery",
            "Stops Android from pausing the CGM service or delaying alarms. Tap, then choose Allow.",
            state.batteryExempt,
        ) {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
            )
        }
        SetupRow(
            "Exact alarms",
            "1 am learn cycle, 6 am overnight check, split-dose reminders at the exact minute.",
            state.exactAlarms,
        ) {
            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
        }
        SetupRow(
            "Microphone",
            "Speak meals, factors and doses. Recognition runs on the phone.",
            state.microphone,
        ) { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }

        SectionCard("xDrip+ feed") {
            Text(
                when (feed.webOk) {
                    true -> "Connected · last reply ${feed.lastWebOkAt?.let { relativeTime(it) } ?: "—"}"
                    false -> "Can't reach xDrip+: ${feed.webMessage}"
                    null -> "Not checked yet"
                },
            )
            feed.lastBroadcastAt?.let { Text("Last broadcast reading ${relativeTime(it)}") }
            feed.lastBackCapture?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(
                "In xDrip+: Settings → Inter-app settings → turn on \"xDrip Web Service\" and " +
                    "\"Broadcast locally\"; set \"Identify receiver\" to app.meanwhile.v4. " +
                    "Connection details are under Settings → CGM.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SetupRow(title: String, why: String, done: Boolean, onFix: () -> Unit) {
    SectionCard(title) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(why, style = MaterialTheme.typography.bodySmall)
            }
            if (done) Text("✓ Done", color = MaterialTheme.colorScheme.primary) else Button(onClick = onFix) { Text("Allow") }
        }
    }
}
