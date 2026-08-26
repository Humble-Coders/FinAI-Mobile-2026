package com.humblesolutions.finai.repository

import com.humblesolutions.finai.config.Supabase
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import kotlinx.coroutines.flow.Flow

/**
 * Supabase-backed authentication. Shared by Android and iOS.
 */
class AuthRepository {

    /** Emits every session change — use this to drive the logged-in / logged-out UI. */
    val sessionStatus: Flow<SessionStatus> = Supabase.auth.sessionStatus

    val currentUser: UserInfo?
        get() = Supabase.auth.currentUserOrNull()

    suspend fun signUp(email: String, password: String) {
        Supabase.auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }
    }

    suspend fun signIn(email: String, password: String) {
        Supabase.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    suspend fun sendPasswordReset(email: String) {
        Supabase.auth.resetPasswordForEmail(email)
    }

    suspend fun signOut() {
        Supabase.auth.signOut()
    }
}
