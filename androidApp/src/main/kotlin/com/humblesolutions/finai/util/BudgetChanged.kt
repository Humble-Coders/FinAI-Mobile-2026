package com.humblesolutions.finai.util

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * "An allocation moved" — emitted when a budget line is set or reset (#47),
 * collected by whatever shows one.
 *
 * Separate from [LedgerChanged] rather than folded into it, because the two
 * point opposite ways and carry different news:
 *
 *  - [LedgerChanged] means the **spending** moved. The Budget tab collects it:
 *    an import changes what every line has spent against it.
 *  - This means the **allocation** moved. Nothing in the ledger changed, so
 *    announcing it on [LedgerChanged] would make the transaction list and
 *    every figure screen re-read the server for rows that are exactly as they
 *    were — several calls and a visible refresh, to show what is already on
 *    screen.
 *
 * Home's budget card (#46) collects both, which is the one screen that cares
 * about either.
 *
 * **Emit only after the server has confirmed the write**, and `tryEmit` into a
 * buffered flow, both for [LedgerChanged]'s reasons.
 */
object BudgetChanged {

    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Collected by screens that show a budget. */
    val events: SharedFlow<Unit> = _events.asSharedFlow()

    /** Called by a writer once the server has confirmed the change. */
    fun announce() {
        _events.tryEmit(Unit)
    }
}
