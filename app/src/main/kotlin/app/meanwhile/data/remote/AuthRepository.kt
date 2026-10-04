package app.meanwhile.data.remote

import app.meanwhile.log.AppLog
import app.meanwhile.data.settings.SettingsStore
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull

sealed interface AuthState {
    data object Loading : AuthState
    data object NotConfigured : AuthState
    data object SignedOut : AuthState

    /** [offline] = session expired and couldn't refresh (no network); local use continues. */
    data class SignedIn(val userId: String, val email: String?, val offline: Boolean = false) : AuthState
}

class AuthRepository(
    private val client: SupabaseClient?,
    private val settings: SettingsStore,
    scope: CoroutineScope,
) {
    val configured: Boolean get() = client != null

    val state: StateFlow<AuthState> = client?.auth?.sessionStatus
        ?.map { status ->
            when (status) {
                is SessionStatus.Authenticated -> {
                    val user = status.session.user
                    if (user == null) {
                        AuthState.SignedOut
                    } else {
                        settings.setMarker(KEY_USER_ID, user.id)
                        settings.setMarker(KEY_EMAIL, user.email)
                        AuthState.SignedIn(user.id, user.email)
                    }
                }
                is SessionStatus.Initializing -> AuthState.Loading
                // The library drops the session when an expired token can't refresh (offline).
                // Keep working locally as the last known user; refresh retries in the background.
                is SessionStatus.RefreshFailure -> settings.marker(KEY_USER_ID)
                    ?.let { AuthState.SignedIn(it, settings.marker(KEY_EMAIL), offline = true) }
                    ?: AuthState.SignedOut
                is SessionStatus.NotAuthenticated -> {
                    if (status.isSignOut) {
                        settings.setMarker(KEY_USER_ID, null)
                        settings.setMarker(KEY_EMAIL, null)
                    }
                    AuthState.SignedOut
                }
            }
        }
        ?.stateIn(scope, SharingStarted.Eagerly, AuthState.Loading)
        ?: MutableStateFlow(AuthState.NotConfigured)

    /** User id for stamping new records: live session, else the last signed-in user. */
    suspend fun userIdForRecords(): String? = currentUserId() ?: settings.marker(KEY_USER_ID)

    /** Waits briefly for the stored session to load; returns the user id only with a usable session. */
    suspend fun awaitSessionUserId(timeoutMs: Long = 10_000): String? {
        val c = client ?: return null
        withTimeoutOrNull(timeoutMs) { c.auth.sessionStatus.first { it !is SessionStatus.Initializing } }
        return c.auth.currentUserOrNull()?.id
    }

    fun currentUserId(): String? = client?.auth?.currentUserOrNull()?.id

    suspend fun signIn(email: String, password: String): Result<Unit> = runAuth {
        requireNotNull(client).auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
    }

    /** Returns true when the new account is signed in right away (email confirmation off). */
    suspend fun signUp(email: String, password: String): Result<Boolean> = runAuth {
        val c = requireNotNull(client)
        c.auth.signUpWith(Email) {
            this.email = email.trim()
            this.password = password
        }
        c.auth.currentSessionOrNull() != null
    }

    suspend fun signOut(): Result<Unit> = runAuth { requireNotNull(client).auth.signOut() }

    private suspend fun <T> runAuth(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: RestException) {
        AppLog.w("Auth", "auth request failed: ${e.description ?: e.error}")
        Result.failure(IllegalStateException(e.description ?: e.error, e))
    } catch (e: Exception) {
        AppLog.w("Auth", "auth request failed: ${e.message}", e)
        Result.failure(e)
    }

    private companion object {
        const val KEY_USER_ID = "auth_user_id"
        const val KEY_EMAIL = "auth_email"
    }
}
