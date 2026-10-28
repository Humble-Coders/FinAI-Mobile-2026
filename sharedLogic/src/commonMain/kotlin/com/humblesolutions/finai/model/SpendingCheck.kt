package com.humblesolutions.finai.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A month's spending against income under 50/30/20, adjusted, and the AI's
 * advice on it (`/budgets/{month}/spending-check`). Every amount and
 * percentage is the server's; the advice is words only.
 */
@Serializable
data class SpendingCheck(
    val month: String = "",
    val currency: String = "",
    @SerialName("month_ended")
    val monthEnded: Boolean = false,
    val income: String = "0",
    val needs: String = "0",
    val wants: String = "0",
    val saved: String = "0",
    /** Spending with no category yet, in neither share. */
    val unsorted: String = "0",
    @SerialName("needs_percent")
    val needsPercent: Int = 0,
    @SerialName("wants_percent")
    val wantsPercent: Int = 0,
    @SerialName("saved_percent")
    val savedPercent: Int = 0,
    @SerialName("needs_aim_percent")
    val needsAimPercent: Int = 50,
    @SerialName("wants_aim_percent")
    val wantsAimPercent: Int = 30,
    @SerialName("savings_aim_percent")
    val savingsAimPercent: Int = 20,
    @SerialName("needs_over")
    val needsOver: Boolean = false,
    @SerialName("wants_over")
    val wantsOver: Boolean = false,
    @SerialName("savings_short")
    val savingsShort: Boolean = false,
    /** The month's last check; empty until one has been run. */
    val headline: String? = null,
    val tips: List<String> = emptyList(),
    val categories: List<CategoryTip> = emptyList(),
    @SerialName("made_at")
    val madeAt: String? = null,
    /** The figures changed since that check: running again is worth it. */
    val stale: Boolean = false,
    val stored: Boolean = true,
    @SerialName("runs_left")
    val runsLeft: Int = 0,
) {
    val hasAdvice: Boolean get() = headline != null || tips.isNotEmpty() || categories.isNotEmpty()
}

/** The AI's tip for one category spent in this month. */
@Serializable
data class CategoryTip(
    @SerialName("category_id")
    val categoryId: String = "",
    val slug: String = "",
    val tip: String = "",
)
