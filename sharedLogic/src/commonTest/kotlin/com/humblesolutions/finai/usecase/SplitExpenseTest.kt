package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.ReceiptItem
import com.humblesolutions.finai.model.ReceiptRead
import com.humblesolutions.finai.model.Split
import com.humblesolutions.finai.model.SplitContact
import com.humblesolutions.finai.model.SplitMode
import com.humblesolutions.finai.model.SplitParticipant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SplitExpenseTest {

    private val rohan = SplitContact("c-rohan", "Rohan Sharma", "+919876543210")
    private val priya = SplitContact("c-priya", "Priya", "+919123456789")

    private fun draft(mode: SplitMode) = SplitExpense.withPerson(
        SplitExpense.withPerson(SplitExpense.withMode(SplitExpense.start("2026-10-12"), mode), rohan),
        priya,
    ).copy(title = "The Coffee House")

    @Test
    fun `nothing is chosen for the person`() {
        val fresh = SplitExpense.start("2026-10-12")

        assertNull(fresh.mode)
        assertNull(fresh.source)
        assertEquals(SplitBlock.NO_MODE, SplitExpense.blockingReason(fresh, "INR"))
        assertTrue(fresh.people.single().isSelf)
    }

    @Test
    fun `equally only the total is needed`() {
        val equal = draft(SplitMode.EQUAL)

        assertEquals(SplitBlock.TOTAL_MISSING, SplitExpense.blockingReason(equal, "INR"))
        val ready = equal.copy(total = "745.50")
        assertNull(SplitExpense.blockingReason(ready, "INR"))
        assertEquals(listOf("248.50", "248.50", "248.50"), SplitExpense.preview(ready, "INR"))
    }

    @Test
    fun `the number of items makes that many rows and keeps what was typed`() {
        val two = SplitExpense.withItem(SplitExpense.withItemCount(draft(SplitMode.ITEMS), 2), 0, "Latte", "160")

        val four = SplitExpense.withItemCount(two, 4)
        assertEquals(4, four.items.size)
        assertEquals("Latte", four.items[0].name)
        assertEquals(1, SplitExpense.withItemCount(four, 1).items.size)
    }

    @Test
    fun `an unassigned item blocks the split and is said`() {
        var items = SplitExpense.withItemCount(draft(SplitMode.ITEMS), 2)
        items = SplitExpense.withItem(items, 0, "Cappuccino", "180")
        items = SplitExpense.withItem(items, 1, "Latte", "160")
        items = SplitExpense.toggled(items, 0, 1)

        assertEquals(SplitBlock.ITEM_UNASSIGNED, SplitExpense.blockingReason(items, "INR"))
        assertEquals("160.00", SplitExpense.unassigned(items, "INR"))
        val done = SplitExpense.everyone(items, 1)
        assertNull(SplitExpense.blockingReason(done, "INR"))
        assertEquals("340.00", SplitExpense.total(done, "INR"))
    }

    @Test
    fun `removing someone takes them off every item`() {
        var items = SplitExpense.withItem(SplitExpense.withItemCount(draft(SplitMode.ITEMS), 1), 0, "Tea", "30")
        items = SplitExpense.toggled(SplitExpense.toggled(items, 0, 1), 0, 2)

        val without = SplitExpense.withoutPerson(items, 1)
        assertEquals(listOf("You", "Priya"), without.people.map { SplitExpense.label(it) })
        assertEquals(setOf(1), without.items[0].assignees, "Priya moved up to 1")
        assertEquals(without, SplitExpense.withoutPerson(without, 0), "never the person themselves")
    }

    @Test
    fun `the same person is on the bill once`() {
        assertEquals(3, SplitExpense.withPerson(draft(SplitMode.EQUAL), rohan).people.size)
    }

    @Test
    fun `a receipt fills the total or the items`() {
        val read = ReceiptRead(
            merchant = "The Coffee House",
            occurredOn = "2026-10-12",
            items = listOf(ReceiptItem("Cappuccino", 1, "180.00"), ReceiptItem("Latte", 2, "320.00")),
            tax = "35.50",
            total = "745.50",
        )

        val equal = SplitExpense.fromReceipt(draft(SplitMode.EQUAL).copy(title = ""), read)
        assertEquals("745.50" to "The Coffee House", equal.total to equal.title)
        assertTrue(equal.items.isEmpty())
        val items = SplitExpense.fromReceipt(draft(SplitMode.ITEMS), read)
        assertEquals(listOf("Cappuccino", "2 × Latte"), items.items.map { it.name })
        assertEquals("35.50", items.tax)
    }

    @Test
    fun `it is sent with the person themselves first and amounts normalised`() {
        var items = SplitExpense.withItem(SplitExpense.withItemCount(draft(SplitMode.ITEMS), 1), 0, "Tea", "30")
        items = SplitExpense.everyone(items, 0).copy(tax = "1.5")

        val sent = SplitExpense.toSave(items, "INR")
        assertEquals(listOf(null, "c-rohan", "c-priya"), sent.participants.map { it.contactId })
        assertEquals("30.00" to listOf(0, 1, 2), sent.items[0].amount to sent.items[0].participants)
        assertEquals("1.50", sent.tax)
        assertNull(sent.total)
    }

    private val split = Split(
        id = "s1",
        title = "The Coffee House",
        occurredOn = "2026-10-12",
        currency = "INR",
        total = "745.50",
        participants = listOf(
            SplitParticipant("p0", null, true, "", null, "220.00"),
            SplitParticipant("p1", "c-rohan", false, "Rohan Sharma", "+919876543210", "305.00", items = listOf("Cappuccino", "Chicken Sandwich")),
        ),
    )

    @Test
    fun `each person's message says their share and what for`() {
        val words = SplitExpense.Words("INR", "en")

        val message = SplitExpense.message(split, split.participants[1], words)

        assertTrue(message.startsWith("Hi Rohan Sharma!"))
        assertTrue(message.contains("Your share of The Coffee House (12 Oct 2026) is ₹305.00."))
        assertTrue(message.contains("For: Cappuccino, Chicken Sandwich"))
        assertTrue(message.contains("Total bill: ₹745.50"))
        assertTrue(SplitExpense.groupMessage(split, words).contains("You: ₹220.00"))
    }

    @Test
    fun `the WhatsApp link carries the number's digits and the message encoded`() {
        val link = SplitExpense.whatsAppLink("+91 98765 43210", "Hi Rohan! ₹305.00")

        assertEquals("https://wa.me/919876543210?text=Hi%20Rohan%21%20%E2%82%B9305.00", link)
        assertNull(SplitExpense.whatsAppLink(null, "x"))
    }

    @Test
    fun `a monogram is the first and last initials`() {
        assertEquals("RS", SplitExpense.initials("Rohan  Sharma"))
        assertEquals("P", SplitExpense.initials("priya"))
        assertEquals("AV", SplitExpense.initials("Aman K Verma"))
    }

    @Test
    fun `the bill step checks the bill alone`() {
        val items = SplitExpense.withItem(SplitExpense.withItemCount(SplitExpense.withMode(SplitExpense.start("2026-10-12"), SplitMode.ITEMS), 1), 0, "Tea", "30").copy(title = "Chai")

        assertNull(SplitExpense.billBlockingReason(items, "INR"), "nobody on it, nothing assigned: the bill is still fine")
        assertEquals(SplitBlock.NO_PEOPLE, SplitExpense.blockingReason(items, "INR"))
        assertEquals(SplitBlock.ITEM_NAME_MISSING, SplitExpense.billBlockingReason(SplitExpense.withItemAdded(items), "INR"))
        assertEquals(SplitBlock.NO_TITLE, SplitExpense.billBlockingReason(items.copy(title = ""), "INR"))
    }

    @Test
    fun `an item misread from a receipt can be removed`() {
        val two = SplitExpense.withItem(SplitExpense.withItemCount(draft(SplitMode.ITEMS), 2), 1, "Mint", "5")

        assertEquals(listOf("Mint"), SplitExpense.withoutItem(two, 0).items.map { it.name })
    }

    @Test
    fun `assigned and unassigned add up to the items`() {
        var items = SplitExpense.withItemCount(draft(SplitMode.ITEMS), 2)
        items = SplitExpense.withItem(SplitExpense.withItem(items, 0, "Cappuccino", "180"), 1, "Latte", "160")
        items = SplitExpense.toggled(items, 0, 1)

        assertEquals("180.00", SplitExpense.assigned(items, "INR"))
        assertEquals("160.00", SplitExpense.unassigned(items, "INR"))
    }

    @Test
    fun `people are found by name or by digits`() {
        assertTrue(SplitExpense.matches(rohan, "roh"))
        assertTrue(SplitExpense.matches(rohan, "98765"))
        assertTrue(SplitExpense.matches(rohan, ""))
        assertFalse(SplitExpense.matches(rohan, "priya"))
        assertEquals("1 person selected", SplitExpense.selectedLine(1))
        assertEquals("3 people selected", SplitExpense.selectedLine(3))
    }

    @Test
    fun `a read with nothing in it is empty`() {
        assertTrue(SplitExpense.isEmpty(ReceiptRead(), SplitMode.EQUAL))
        assertFalse(SplitExpense.isEmpty(ReceiptRead(total = "10.00"), SplitMode.EQUAL))
        assertTrue(SplitExpense.isEmpty(ReceiptRead(total = "10.00"), SplitMode.ITEMS), "a total is no use by items")
    }
}
