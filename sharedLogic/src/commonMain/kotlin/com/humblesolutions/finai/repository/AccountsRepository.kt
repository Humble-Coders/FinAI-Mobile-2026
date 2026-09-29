package com.humblesolutions.finai.repository

import com.humblesolutions.finai.model.Account
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.NewAccount
import kotlin.coroutines.cancellation.CancellationException

/**
 * The household's accounts (Finance-backend 3.3). Used by manual entry (#30)
 * and by the import screen's account step (#31).
 *
 * Every public suspend function declares `@Throws`: from Swift an undeclared
 * Kotlin exception terminates the process (kmp-arch-v2 → SKIE).
 */
interface AccountsRepository {

    /** Oldest first, as the server returns them, so the list does not reshuffle. */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun list(): List<Account>

    /**
     * Raises [ApiException.DuplicateAccountName] for a name the household
     * already uses — two accounts for one real account would split its
     * statements into piles that cannot see each other's duplicates.
     */
    @Throws(ApiException::class, CancellationException::class)
    suspend fun create(account: NewAccount): Account

    /** Releases the HTTP client. Built fresh per bind, never shared. */
    fun close()
}
