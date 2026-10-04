package com.humblesolutions.finai.usecase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatementCurrencyTest {

    @Test
    fun rupees_into_a_dollar_account_are_caught() {
        assertEquals("INR", StatementCurrency.foreign("Payment of ₹70 completed", "CAD"))
        assertEquals("INR", StatementCurrency.foreign("Paid Rs. 500 to shop", "cad"))
        assertEquals("EUR", StatementCurrency.foreign("Total €12.40", "CAD"))
        assertEquals("GBP", StatementCurrency.foreign("£3.10 TESCO", "USD"))
    }

    @Test
    fun the_accounts_own_currency_is_not_foreign() {
        assertNull(StatementCurrency.foreign("Payment of ₹70 completed", "INR"))
    }

    @Test
    fun a_dollar_sign_names_no_currency_and_is_never_a_reason_to_refuse() {
        assertNull(StatementCurrency.foreign("Loblaws -$86.40", "CAD"))
        assertNull(StatementCurrency.foreign("Loblaws -$86.40", "INR"))
    }

    @Test
    fun without_an_account_currency_nothing_is_refused() {
        assertNull(StatementCurrency.foreign("₹70", null))
        assertNull(StatementCurrency.foreign("₹70", ""))
    }

    @Test
    fun a_word_merely_containing_the_letters_is_not_a_currency() {
        assertNull(StatementCurrency.foreign("TEARS FOR FEARS · GBPTICKETS", "CAD"))
        assertNull(StatementCurrency.foreign("Mrs. Smith 12 Mars St", "CAD"))
    }
}
