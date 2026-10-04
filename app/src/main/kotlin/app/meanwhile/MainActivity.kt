package app.meanwhile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.meanwhile.notify.Notifications
import app.meanwhile.service.CgmService
import app.meanwhile.ui.common.LocalAppContainer
import app.meanwhile.ui.nav.AppRoot
import app.meanwhile.ui.theme.MeanwhileTheme

class MainActivity : ComponentActivity() {
    private var openRequest by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) openRequest = intent.getStringExtra(Notifications.EXTRA_OPEN)
        val container = (application as MeanwhileApp).container
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                MeanwhileTheme {
                    AppRoot(openRequest = openRequest, onOpenHandled = { openRequest = null })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Foreground: always allowed to (re)start the CGM service.
        CgmService.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(Notifications.EXTRA_OPEN)?.let { openRequest = it }
    }
}
