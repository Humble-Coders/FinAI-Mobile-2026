package com.humblesolutions.finai.model

/**
 * A sign-in provider whose SDK lives on the platform, not here.
 *
 * Each platform obtains the ID token natively — Credential Manager on Android,
 * AuthenticationServices or the Google SDK on iOS — and hands it to the shared
 * repository, which is the only place that talks to Supabase (kmp-arch-v2:
 * native SDK per platform, one shared repository behind it).
 *
 * Apple is offered on iOS only (manager decision, 2026-09-11). Someone who
 * signed up with Apple on an iPhone signs in on Android with their phone
 * number and lands in the same account — the verified number is the identity
 * key, not the provider.
 */
enum class SocialProvider {
    GOOGLE,
    APPLE,
}
