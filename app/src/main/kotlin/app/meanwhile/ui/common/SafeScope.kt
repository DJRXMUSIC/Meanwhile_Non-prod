package app.meanwhile.ui.common

import android.os.Handler
import android.os.Looper
import android.util.Log
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
            Log.e("Meanwhile", "UI action failed", e)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Something went wrong: ${e.message ?: e::class.java.simpleName}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
