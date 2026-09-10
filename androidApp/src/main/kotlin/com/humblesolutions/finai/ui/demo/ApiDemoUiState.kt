package com.humblesolutions.finai.ui.demo

import com.humblesolutions.finai.model.Capabilities
import com.humblesolutions.finai.model.ConfigurationProblem
import com.humblesolutions.finai.model.SessionState

/** Throwaway demo state (ticket #6). Derived gates live here so they are testable with plain constructors. */
data class ApiDemoUiState(
    /** Set when this build cannot reach its backend; the screen shows it instead of crashing. */
    val configurationProblem: ConfigurationProblem? = null,
    val session: SessionState = SessionState.LOADING,
    val phone: String = "",
    val code: String = "",
    val codeSent: Boolean = false,
    val busy: Boolean = false,
    val capabilities: Capabilities? = null,
    val errorKey: String? = null,
    /** Refresh evidence: token life in seconds right after expiring it, and after the load. */
    val tokenLifeAfterExpire: Long? = null,
    val tokenLifeAfterLoad: Long? = null,
) {
    val canSendCode: Boolean get() = !busy && phone.isNotBlank()
    val canVerify: Boolean get() = !busy && codeSent && code.isNotBlank()
}
