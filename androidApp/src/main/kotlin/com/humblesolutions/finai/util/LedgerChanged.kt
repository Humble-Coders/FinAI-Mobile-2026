package com.humblesolutions.finai.util

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * "Money moved" — emitted by whatever changed it, collected by whatever shows
 * it (kmp-arch-v2, cross-screen refresh).
 *
 * The dashboard reads a month from the server once and then sits there. Import
 * a statement, type a transaction in, confirm or delete a row in the queue, and
 * the figures behind it are stale the moment that screen closes — a person
 * imports 24 transactions, comes back, and home still says what it said
 * before, which reads as the import not having worked.
 *
 * A bus rather than a result passed back through navigation: four screens
 * change the ledger and more will, and threading a result through each route
 * means every new writer has to remember to. Emitting is one line at the point
 * the server said yes.
 *
 * **Emit only after the server has confirmed the write.** An optimistic signal
 * makes the dashboard re-read and show the figures it already had, which looks
 * exactly like the write being lost.
 *
 * `extraBufferCapacity` so a writer never suspends on emit: `tryEmit` into a
 * zero-buffer `SharedFlow` with no collector drops the event, and the writer
 * is usually finishing up as its screen goes away.
 */
object LedgerChanged {

    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Collected by screens that show figures derived from transactions. */
    val events: SharedFlow<Unit> = _events.asSharedFlow()

    /** Called by a writer once the server has confirmed the change. */
    fun announce() {
        _events.tryEmit(Unit)
    }
}
