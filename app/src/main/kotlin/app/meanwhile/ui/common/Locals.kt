package app.meanwhile.ui.common

import androidx.compose.runtime.staticCompositionLocalOf
import app.meanwhile.di.AppContainer

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
