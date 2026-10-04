package app.meanwhile

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.meanwhile.notify.Notifications
import app.meanwhile.service.CgmService
import app.meanwhile.ui.common.LocalAppContainer
import app.meanwhile.ui.nav.AppRoot
import app.meanwhile.data.settings.ThemeMode
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
                val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
                val dark = when (settings?.themeMode) {
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                    else -> isSystemInDarkTheme()
                }
                // Status/navigation bar icons follow the app's theme, not just the phone's.
                DisposableEffect(dark) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                        navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                    )
                    onDispose {}
                }
                MeanwhileTheme(darkTheme = dark) {
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

// Same scrims as the androidx defaults for three-button navigation.
private val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
