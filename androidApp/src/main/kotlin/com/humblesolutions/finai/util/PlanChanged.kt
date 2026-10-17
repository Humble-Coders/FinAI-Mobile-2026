package com.humblesolutions.finai.util

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * "The plan moved" — emitted when its choices are saved or a debt is edited
 * (#76), collected by Home's plan card and the Goals tab's suggested amounts.
 *
 * Its own signal for [GoalsChanged]'s reason: nothing in the ledger changed.
 * **Emit only after the server has confirmed the write.**
 */
object PlanChanged {

    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val events: SharedFlow<Unit> = _events.asSharedFlow()

    fun announce() {
        _events.tryEmit(Unit)
    }
}
