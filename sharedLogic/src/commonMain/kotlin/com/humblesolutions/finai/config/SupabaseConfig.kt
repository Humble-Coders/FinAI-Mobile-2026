package com.humblesolutions.finai.config

/**
 * Supabase project credentials.
 *
 * Fill these in from: Supabase Dashboard -> Project Settings -> API.
 *
 * The `anon` key is a *publishable* key — it is designed to ship inside client
 * apps. Your data is protected by Row Level Security policies, not by hiding
 * this key. Never put the `service_role` key here.
 */
object SupabaseConfig {
    const val URL: String = "https://REPLACE_ME.supabase.co"
    const val ANON_KEY: String = "REPLACE_ME_ANON_KEY"

    val isConfigured: Boolean
        get() = !URL.contains("REPLACE_ME") && !ANON_KEY.contains("REPLACE_ME")
}
