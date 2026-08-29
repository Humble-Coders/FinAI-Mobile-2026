package com.humblesolutions.finai.config

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage

/**
 * The Supabase client, shared by Android and iOS.
 *
 * **Auth and Storage only.** Clients never talk to the database — Postgrest and
 * Realtime are deliberately not installed, and must not be added. All business
 * data goes over HTTP to the backend API, which is the single place
 * authorization, entitlement gating, region gating and audit logging live
 * (PRD §4.2, CLAUDE.md → "The network boundary").
 *
 * Created lazily so the app can start, and fail with a clear message, when
 * [SupabaseConfig] has not been filled in.
 */
object Supabase {

    val client: SupabaseClient by lazy {
        require(SupabaseConfig.isConfigured) {
            "Supabase is not configured. Set supabase.url and supabase.anonKey in local.properties."
        }
        createSupabaseClient(
            supabaseUrl = SupabaseConfig.URL,
            supabaseKey = SupabaseConfig.ANON_KEY,
        ) {
            install(Auth)
            install(Storage)
        }
    }

    val auth get() = client.auth
    val storage get() = client.storage
}
