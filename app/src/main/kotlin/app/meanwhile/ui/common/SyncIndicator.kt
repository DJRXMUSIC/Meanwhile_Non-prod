package app.meanwhile.ui.common

import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.meanwhile.data.remote.AuthState
import app.meanwhile.data.settings.SyncStatus

/**
 * Sync chip (spec §12.1), shown only when something needs attention — all good means no chip, which
 * keeps the main screen quiet. Full status is in Settings → Cloud sync.
 */
@Composable
fun SyncIndicator(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalAppContainer.current
    val pending by c.db.sync().pendingCount().collectAsStateWithLifecycle(initialValue = 0)
    val status by c.settings.syncStatus.collectAsStateWithLifecycle(initialValue = SyncStatus())
    val auth by c.auth.state.collectAsStateWithLifecycle()
    val label = when {
        auth is AuthState.NotConfigured -> null // this build has no cloud at all; Settings explains
        auth !is AuthState.SignedIn -> "Not backed up"
        status.failingSince != null -> "Sync failing"
        pending > 0 -> "$pending to sync"
        else -> null
    } ?: return
    AssistChip(onClick = onClick, label = { Text(label) }, modifier = modifier)
}
