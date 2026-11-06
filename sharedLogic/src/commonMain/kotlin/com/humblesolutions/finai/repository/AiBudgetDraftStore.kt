package com.humblesolutions.finai.repository

/**
 * Where the suggested budget being edited is kept on the phone until it is
 * started, so leaving and coming back shows the person's edits without asking
 * the AI again (product decision, 2026-11-05: on the phone only).
 *
 * Holds the text from `AiBudgetEdit.encode`, one per signed-in user. A
 * platform implementation: SharedPreferences on Android, UserDefaults on iOS.
 */
interface AiBudgetDraftStore {
    fun load(userId: String): String?

    fun save(userId: String, draft: String)

    fun clear(userId: String)
}
