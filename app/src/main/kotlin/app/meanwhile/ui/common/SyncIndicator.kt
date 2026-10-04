package app.meanwhile.ui.common

import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.meanwhile.data.remote.AuthState
import app.meanwhile.data.settings.SyncStatus

/** Small sync status chip (spec §12.1): last successful sync and items pending. */
@Composable
fun SyncIndicator(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalAppContainer.current
    val pending by c.db.sync().pendingCount().collectAsStateWithLifecycle(initialValue = 0)
    val status by c.settings.syncStatus.collectAsStateWithLifecycle(initialValue = SyncStatus())
    val auth by c.auth.state.collectAsStateWithLifecycle()
    val label = when {
        auth is AuthState.NotConfigured -> "Local only"
        auth !is AuthState.SignedIn -> "Sync off"
        status.failingSince != null -> "Sync failing · $pending"
        pending > 0 -> "$pending pending"
        status.lastSuccessAt != null -> "Synced"
        else -> "Not synced yet"
    }
    AssistChip(onClick = onClick, label = { Text(label) }, modifier = modifier)
}
