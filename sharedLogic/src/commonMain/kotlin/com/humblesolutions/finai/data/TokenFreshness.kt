package com.humblesolutions.finai.data

/** Whether a token should be refreshed before use. Pure, so it is tested without a clock or a session. */
internal object TokenFreshness {

    /** Refresh this far ahead of expiry, so a token cannot lapse while a request is in flight. */
    const val MARGIN_SECONDS: Long = 30

    fun isStale(expiresAtEpochSeconds: Long, nowEpochSeconds: Long, marginSeconds: Long = MARGIN_SECONDS): Boolean =
        expiresAtEpochSeconds - marginSeconds <= nowEpochSeconds
}
