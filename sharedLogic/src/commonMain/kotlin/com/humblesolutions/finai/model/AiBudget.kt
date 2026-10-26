package com.humblesolutions.finai.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The first budget the AI suggests, from setup's answers (`POST /budgets/ai/setup`).
 *
 * Every figure was checked by the server: the lines, the Debt line and
 * [savings] add up to [income] exactly, and each line holds its commitments.
 */
@Serializable
data class AiProposal(
    val month: String = "",
    val currency: String = "",
    val income: String = "0",
    val lines: List<AiProposalLine> = emptyList(),
    val savings: String = "0",
    val needs: String = "0",
    val wants: String = "0",
    @SerialName("needs_percent")
    val needsPercent: Int = 0,
    @SerialName("wants_percent")
    val wantsPercent: Int = 0,
    @SerialName("savings_percent")
    val savingsPercent: Int = 0,
    val note: String? = null,
    @SerialName("attempts_left")
    val attemptsLeft: Int = 0,
)

@Serializable
data class AiProposalLine(
    @SerialName("category_id")
    val categoryId: String = "",
    val slug: String = "",
    val name: String = "",
    /** `need`, `want`, or `debt` — the minimum payments, which follow their own rule. */
    val kind: String = "need",
    val amount: String = "0",
    /** What the person's commitments in the category already hold: the least the line can be. */
    val committed: String = "0",
    val reason: String? = null,
    /** What the person usually spends here a month — the median of their last three months — or null on a new account. */
    val usual: String? = null,
) {
    val isDebt: Boolean get() = kind == "debt"
}

/** The budget the person starts with: the AI's lines as they edited them. */
@Serializable
data class AiAccept(
    val lines: List<AiAcceptLine> = emptyList(),
    val savings: String? = null,
)

@Serializable
data class AiAcceptLine(
    @SerialName("category_id")
    val categoryId: String = "",
    val amount: String = "",
)
