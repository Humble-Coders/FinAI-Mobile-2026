package com.humblesolutions.finai.model

/**
 * The signed-out half of the app: what the welcome screen is doing.
 *
 * None of this is a server state, so the router does not model it — like a
 * sent code, it is a sub-state of the screen that owns it. It lives here
 * rather than per platform so Android and iOS name the same stages.
 */
enum class WelcomeMode {
    CREATE_ACCOUNT,
    SIGN_IN,
}

/** Forgot password, one stage at a time. */
enum class ResetStage {
    /** Asking which email to send the code to. */
    REQUEST,

    /** A code was sent; waiting for it. */
    CODE,

    /**
     * The code verified, which signs the user in with a recovery session.
     * Shown in preference to wherever the router would send a signed-in user,
     * so nobody reaches the app without having chosen the new password.
     */
    NEW_PASSWORD,
}
