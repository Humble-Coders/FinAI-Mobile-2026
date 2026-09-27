package com.humblesolutions.finai.config

/**
 * Limits the device has to know about because the server enforces them.
 *
 * Kept here rather than duplicated in each platform, and deliberately matching
 * `MAX_TEXT_CHARS` in the backend's `app/services/statements.py`. Two copies of
 * a limit is two chances to disagree, and the disagreement would show up as a
 * user waiting through a full OCR pass to be refused by a round trip.
 */
object StatementLimits {
    /**
     * The most redacted text `POST /statements/parse` accepts — roughly a
     * 70-page statement. Beyond this it answers 413, so the device checks
     * first and says something a person can act on.
     */
    const val MAX_TEXT_CHARS: Int = 200_000

    /**
     * The most pages `POST /statements/parse` accepts, matching `page_count`'s
     * bound in the backend's `StatementParseIn`.
     *
     * Checked on the device for the same reason as [MAX_TEXT_CHARS], and more
     * urgently: an over-long statement is refused after a fast parse, but an
     * over-paged scan is refused after minutes of OCR.
     */
    const val MAX_PAGES: Int = 500

    /**
     * How long to wait for `POST /statements/parse`, in milliseconds.
     *
     * Mirrors `PARSE_BUDGET_SECONDS = 180.0` in the backend's
     * `app/services/statements.py`, with thirty seconds of margin for the
     * request itself. The default client timeout is a minute, which is right
     * for signing in and wrong for this: a long statement fans out into many
     * model calls, and the server is allowed three minutes for them.
     *
     * Giving up first is worse than waiting. The server does not stop when the
     * client does — it finishes the import and spends the model call — so the
     * user is told it failed, retries, and pays for the same statement twice.
     *
     * Applies to the socket timeout as well as the request timeout. While the
     * server parses, nothing comes back down the connection, so a socket
     * bound left at the default ends the request at a minute no matter what
     * the request bound says.
     */
    const val PARSE_TIMEOUT_MS: Long = 210_000
}
