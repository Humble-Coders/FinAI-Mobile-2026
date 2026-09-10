package com.humblesolutions.finai.repository

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Me
import com.humblesolutions.finai.model.SessionState
import kotlinx.coroutines.flow.Flow
import kotlin.coroutines.cancellation.CancellationException

interface AuthRepository {

    /** Live session state. SKIE exposes it to Swift as an AsyncSequence. */
    val sessionState: Flow<SessionState>

    /**
     * Sends a one-time code to [phone]. Used here only to obtain a test session;
     * the real sign-in flows and their UI arrive in M2.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun requestPhoneCode(phone: String)

    @Throws(ApiException::class, CancellationException::class)
    suspend fun verifyPhoneCode(phone: String, code: String)

    @Throws(ApiException::class, CancellationException::class)
    suspend fun signOut()

    /** The caller and their household. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun me(): Me

    /** Releases the HTTP client. Built fresh per bind, never shared. */
    fun close()
}
