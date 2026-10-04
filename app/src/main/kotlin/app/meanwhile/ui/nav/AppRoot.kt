package app.meanwhile.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.meanwhile.data.remote.AuthState
import app.meanwhile.ui.auth.AuthScreen
import app.meanwhile.ui.common.LocalAppContainer
import app.meanwhile.ui.main.MainScreen
import app.meanwhile.ui.settings.ExportScreen
import app.meanwhile.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

object Routes {
    const val MAIN = "main"
    const val SETTINGS = "settings"
    const val EXPORT = "export"
}

@Composable
fun AppRoot(openRequest: String?, onOpenHandled: () -> Unit) {
    val c = LocalAppContainer.current
    val auth by c.auth.state.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()

    val s = settings
    when {
        s == null || auth is AuthState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        auth is AuthState.SignedOut && !s.authSkipped -> AuthScreen(
            onSkip = { scope.launch { c.settings.update { it.copy(authSkipped = true) } } },
        )
        else -> AppNavHost(openRequest, onOpenHandled)
    }
}

@Composable
private fun AppNavHost(openRequest: String?, onOpenHandled: () -> Unit) {
    val nav = rememberNavController()
    LaunchedEffect(openRequest) {
        if (openRequest != null) {
            if (openRequest != Routes.MAIN) nav.navigate(openRequest) { launchSingleTop = true }
            onOpenHandled()
        }
    }
    NavHost(navController = nav, startDestination = Routes.MAIN) {
        composable(Routes.MAIN) {
            MainScreen(onOpen = { nav.navigate(it) { launchSingleTop = true } })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() }, onOpen = { nav.navigate(it) { launchSingleTop = true } })
        }
        composable(Routes.EXPORT) {
            ExportScreen(onBack = { nav.popBackStack() })
        }
    }
}
