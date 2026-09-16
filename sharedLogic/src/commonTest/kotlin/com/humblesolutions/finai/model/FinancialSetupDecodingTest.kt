package com.humblesolutions.finai.model

import com.humblesolutions.finai.data.FinAiJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The wire shape of `GET /financial-setup`, which the wizard renders from. */
class FinancialSetupDecodingTest {

    @Test
    fun `it decodes what the api sends`() {
        val json = """
            {
              "currency": "CAD",
              "income": "4000.00",
              "monthly_expense": "2500.00",
              "debts": [{"name":"Card","balance":"1500.00","minimum_payment":"50.00","interest_rate_percent":"19.99"}],
              "investments": [{"name":"TFSA","amount":"10000.00"}],
              "obligations": [{"name":"Rent","monthly_amount":"900.00"}]
            }
        """.trimIndent()

        val setup = FinAiJson.decodeFromString(FinancialSetup.serializer(), json)

        assertEquals("CAD", setup.currency)
        assertEquals("2500.00", setup.monthlyExpense)
        assertEquals("19.99", setup.debts.single().interestRatePercent)
        assertEquals("900.00", setup.obligations.single().monthlyAmount)
        assertTrue(setup.mandatoryComplete)
    }

    @Test
    fun `a wizard nobody has filled in yet decodes too`() {
        val setup = FinAiJson.decodeFromString(FinancialSetup.serializer(), """{"currency":"CAD"}""")

        assertNull(setup.income)
        assertTrue(setup.debts.isEmpty())
        assertTrue(!setup.mandatoryComplete)
    }

    @Test
    fun `a field this build does not know is ignored rather than fatal`() {
        val setup = FinAiJson.decodeFromString(
            FinancialSetup.serializer(),
            """{"currency":"CAD","income":"1.00","monthly_expense":"2.00","something_new":true}""",
        )

        assertEquals("1.00", setup.income)
    }
}
