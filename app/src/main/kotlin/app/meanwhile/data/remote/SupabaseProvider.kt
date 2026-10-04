package app.meanwhile.data.remote

import app.meanwhile.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import kotlin.time.Duration.Companion.seconds

object SupabaseProvider {
    val isConfigured: Boolean
        get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    /** Null when the APK was built without Supabase settings: the app then runs local-only. */
    fun create(): SupabaseClient? {
        if (!isConfigured) return null
        return createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY,
        ) {
            // AI calls set their own (longer) per-request timeout.
            requestTimeout = 30.seconds
            install(Auth)
            install(Postgrest)
            install(Functions)
        }
    }
}
