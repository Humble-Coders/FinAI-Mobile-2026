package com.humblesolutions.finai.model

/** Where the Supabase session stands, as a screen needs to know it. */
enum class SessionState {
    LOADING,
    SIGNED_IN,
    SIGNED_OUT,

    /** A refresh failed (usually the network); the session still exists and will retry. */
    REFRESH_FAILED,
}
