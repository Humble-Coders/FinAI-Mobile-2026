package com.humblesolutions.finai.data

import android.content.Context
import com.humblesolutions.finai.repository.AiBudgetDraftStore

/** The suggested budget's draft in SharedPreferences, one entry per user. */
class PrefsAiBudgetDraftStore(context: Context) : AiBudgetDraftStore {

    private val prefs = context.applicationContext.getSharedPreferences("ai_budget_drafts", Context.MODE_PRIVATE)

    override fun load(userId: String): String? = prefs.getString(userId, null)

    override fun save(userId: String, draft: String) = prefs.edit().putString(userId, draft).apply()

    override fun clear(userId: String) = prefs.edit().remove(userId).apply()
}
