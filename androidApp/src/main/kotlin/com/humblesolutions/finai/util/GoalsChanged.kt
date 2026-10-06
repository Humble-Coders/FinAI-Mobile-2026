package com.humblesolutions.finai.util

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * "A goal moved" — emitted when one is created, edited, topped up, reordered
 * or deleted (#52), collected by Home's goals card.
 *
 * Its own signal, as [BudgetChanged] is: nothing in the ledger changed, so
 * announcing it as [LedgerChanged] would make every figure screen re-read the
 * server for rows exactly as they were. And goals do not listen to
 * [LedgerChanged] either — what is saved is what the person entered, not
 * something an import moves.
 *
 * **Emit only after the server has confirmed the write**, and `tryEmit` into a
 * buffered flow, both for [LedgerChanged]'s reasons.
 */
object GoalsChanged {

    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Collected by screens that show goals. */
    val events: SharedFlow<Unit> = _events.asSharedFlow()

    /** Called by a writer once the server has confirmed the change. */
    fun announce() {
        _events.tryEmit(Unit)
    }
}
