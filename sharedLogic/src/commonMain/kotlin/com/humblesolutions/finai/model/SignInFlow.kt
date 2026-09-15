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

/**
 * A sign-in method waiting to be moved onto an existing account.
 *
 * Created when the phone step finds the number already belongs to another
 * account: the new sign-in made an empty account (the orphan), and the person
 * is about to sign in to their real one. After that, [orphanToken] proves to
 * the API they held the orphan too, so it can be removed and [provider] linked.
 *
 * Held in memory only. If the app dies mid-way the orphan simply stays, and
 * signing in with the same method again starts over.
 *
 * @property orphanToken the orphan session's access token. A credential —
 *   never log it, never persist it.
 * @property provider the method to link afterwards, or null when the orphan
 *   was an email signup, which has nothing to link: the person just uses the
 *   existing account's own sign-in.
 */
data class PendingLink(
    val orphanToken: String = "",
    val provider: SocialProvider? = null,
)
