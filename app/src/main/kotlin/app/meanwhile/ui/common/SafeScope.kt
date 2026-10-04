package app.meanwhile.ui.common

import app.meanwhile.log.AppLog
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope

/**
 * [rememberCoroutineScope] for button actions: an exception (database, a bad profile, a missing
 * factor) shows a message instead of crashing the whole app.
 */
@Composable
fun rememberSafeScope(): CoroutineScope {
    val context = LocalContext.current.applicationContext
    return rememberCoroutineScope {
        CoroutineExceptionHandler { _, e ->
            AppLog.e("UI", "button action failed: ${e.message ?: e::class.java.simpleName}", e)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Something went wrong: ${e.message ?: e::class.java.simpleName}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
