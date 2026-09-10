package com.humblesolutions.finai.model

import com.humblesolutions.finai.i18n.Strings

/**
 * Why the app cannot reach its backend, or null when it can — the blocking
 * reason for everything that needs a session. Screens check it before touching
 * auth and render [messageKey], rather than crashing.
 */
enum class ConfigurationProblem(val messageKey: String) {
    SUPABASE_NOT_CONFIGURED(Strings.error_not_configured);

    companion object {
        /** Pure, so it can be tested without the generated build configuration. */
        fun check(supabaseUrl: String, anonKey: String): ConfigurationProblem? =
            if (supabaseUrl.isBlank() || anonKey.isBlank() ||
                supabaseUrl.contains("REPLACE_ME") || anonKey.contains("REPLACE_ME")
            ) SUPABASE_NOT_CONFIGURED else null
    }
}
