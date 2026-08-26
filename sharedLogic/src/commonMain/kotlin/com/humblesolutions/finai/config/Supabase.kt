package com.humblesolutions.finai.config

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage

/**
 * Single Supabase client for the whole app, shared by Android and iOS.
 *
 * Created lazily so the app can start (and fail with a clear message) even
 * before [SupabaseConfig] has been filled in.
 */
object Supabase {

    val client: SupabaseClient by lazy {
        require(SupabaseConfig.isConfigured) {
            "Supabase is not configured. Set URL and ANON_KEY in SupabaseConfig.kt"
        }
        createSupabaseClient(
            supabaseUrl = SupabaseConfig.URL,
            supabaseKey = SupabaseConfig.ANON_KEY,
        ) {
            install(Auth)
            install(Postgrest)
            install(Storage)
            install(Realtime)
        }
    }

    val auth get() = client.auth
    val postgrest get() = client.postgrest
    val storage get() = client.storage
    val realtime get() = client.realtime
}
