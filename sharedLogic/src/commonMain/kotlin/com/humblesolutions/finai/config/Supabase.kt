package com.humblesolutions.finai.config

import com.humblesolutions.finai.model.ConfigurationProblem
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.storage.Storage

/**
 * The Supabase client, shared by Android and iOS.
 *
 * **Auth and Storage only.** Clients never talk to the database — Postgrest and
 * Realtime are deliberately not installed, and must not be added. All business
 * data goes over HTTP to the backend API, which is the single place
 * authorization, entitlement gating, region gating and audit logging live
 * (PRD §4.2, CLAUDE.md → "The network boundary").
 *
 * One client for the app: it owns the persisted session. Repositories never
 * reach for it themselves — platform wiring passes it in.
 */
object Supabase {

    /**
     * Why the client cannot be built, or null when it can. Check before touching
     * auth; render [ConfigurationProblem.messageKey] otherwise.
     */
    val configurationProblem: ConfigurationProblem?
        get() = ConfigurationProblem.check(SupabaseConfig.URL, SupabaseConfig.ANON_KEY)

    // Only initialised through clientOrNull(), after the check above, so it never
    // runs with REPLACE_ME values. It used to `require` the config inside this
    // lazy, which threw on first access — and from Swift an undeclared Kotlin
    // exception terminates the process. A property getter cannot declare @Throws,
    // so the fix is to never throw here at all.
    private val client: SupabaseClient by lazy {
        createSupabaseClient(
            supabaseUrl = SupabaseConfig.URL,
            supabaseKey = SupabaseConfig.ANON_KEY,
        ) {
            install(Auth)
            install(Storage)
        }
    }

    /** The client, or null when this build is not configured — see [configurationProblem]. */
    fun clientOrNull(): SupabaseClient? = if (configurationProblem == null) client else null
}
