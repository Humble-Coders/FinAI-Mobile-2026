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
}
