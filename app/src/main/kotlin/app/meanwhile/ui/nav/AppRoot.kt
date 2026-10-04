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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.meanwhile.data.remote.AuthState
import app.meanwhile.ui.auth.AuthScreen
import app.meanwhile.ui.debug.DoseDebugScreen
import app.meanwhile.ui.common.LocalAppContainer
import app.meanwhile.ui.main.MainScreen
import app.meanwhile.ui.morning.MorningReportScreen
import app.meanwhile.ui.profile.EditSettingsScreen
import app.meanwhile.ui.profile.JsonEditScreen
import app.meanwhile.ui.profile.ProfileScreen
import app.meanwhile.ui.profile.VersionDetailScreen
import app.meanwhile.ui.review.ReviewScreen
import app.meanwhile.ui.settings.ExportScreen
import app.meanwhile.ui.settings.SettingsScreen
import app.meanwhile.ui.setup.SetupScreen
import app.meanwhile.ui.stats.StatsScreen
import kotlinx.coroutines.launch

object Routes {
    const val MAIN = "main"
    const val SETTINGS = "settings"
    const val EXPORT = "export"
    const val SETUP = "setup"
    const val DEBUG_DOSE = "debug-dose"
    const val PROFILE = "profile"
    const val PROFILE_EDIT = "profile-edit"
    const val PROFILE_VERSION = "profile-version"
    const val PROFILE_JSON = "profile-json"
    const val REVIEW = "review"
    const val MORNING = "morning"
    const val STATS = "stats"

    fun profileVersion(id: String) = "$PROFILE_VERSION/$id"
    fun profileJson(path: String) = "$PROFILE_JSON?path=$path"
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
        composable(Routes.SETUP) {
            SetupScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.DEBUG_DOSE) {
            DoseDebugScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.STATS) {
            StatsScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.MORNING) {
            MorningReportScreen(onDone = { nav.popBackStack() })
        }
        composable(Routes.PROFILE) {
            ProfileScreen(onBack = { nav.popBackStack() }, onOpen = { nav.navigate(it) })
        }
        composable(Routes.PROFILE_EDIT) {
            EditSettingsScreen(onBack = { nav.popBackStack() })
        }
        composable(
            "${Routes.PROFILE_VERSION}/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            VersionDetailScreen(entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() }, onOpen = { nav.navigate(it) })
        }
        composable(
            "${Routes.REVIEW}/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            ReviewScreen(entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
        }
        composable(
            "${Routes.PROFILE_JSON}?path={path}",
            arguments = listOf(navArgument("path") { type = NavType.StringType; defaultValue = "" }),
        ) { entry ->
            JsonEditScreen(entry.arguments?.getString("path").orEmpty(), onBack = { nav.popBackStack() })
        }
    }
}
