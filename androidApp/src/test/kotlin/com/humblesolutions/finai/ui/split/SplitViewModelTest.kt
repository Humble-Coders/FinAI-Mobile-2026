package com.humblesolutions.finai.ui.split

import androidx.lifecycle.SavedStateHandle
import com.humblesolutions.finai.model.AiConsentStatus
import com.humblesolutions.finai.model.AiPolicy
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.ExtractedDocument
import com.humblesolutions.finai.model.ExtractedLine
import com.humblesolutions.finai.model.ExtractedPage
import com.humblesolutions.finai.model.ReceiptItem
import com.humblesolutions.finai.model.ReceiptRead
import com.humblesolutions.finai.model.Split
import com.humblesolutions.finai.model.SplitContact
import com.humblesolutions.finai.model.SplitMode
import com.humblesolutions.finai.model.SplitParticipant
import com.humblesolutions.finai.model.SplitSummary
import com.humblesolutions.finai.model.SplitToSave
import com.humblesolutions.finai.repository.AiConsentRepository
import com.humblesolutions.finai.repository.SplitRepository
import com.humblesolutions.finai.repository.StatementReader
import com.humblesolutions.finai.usecase.SplitBlock
import com.humblesolutions.finai.usecase.SplitExpense
import com.humblesolutions.finai.usecase.SplitSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val PRIYA = SplitContact("c-priya", "Priya Shah", "+919876543210")

@OptIn(ExperimentalCoroutinesApi::class)
class SplitViewModelTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeSplits : SplitRepository {
        var contacts = mutableListOf(PRIYA)
        val added = mutableListOf<Pair<String, String>>()
        val receiptTexts = mutableListOf<String>()

        /** Answers to `readReceipt`, in turn: a read, or a failure to throw. */
        val receiptAnswers = ArrayDeque<Any>()
        var created: SplitToSave? = null
        var createAnswer: (SplitToSave) -> Split = { Split(id = "s1", title = it.title) }
        val settled = mutableListOf<Triple<String, String, Boolean>>()

        override suspend fun contacts() = contacts.toList()

        override suspend fun addContact(name: String, phone: String): SplitContact {
            added += name to phone
            return SplitContact("c-new", name, phone).also { contacts += it }
        }

        override suspend fun deleteContact(id: String) {
            contacts.removeAll { it.id == id }
        }

        override suspend fun readReceipt(text: String): ReceiptRead {
            receiptTexts += text
            return when (val next = receiptAnswers.removeFirst()) {
                is ApiException -> throw next
                else -> next as ReceiptRead
            }
        }

        var saved: Split? = null

        override suspend fun create(split: SplitToSave): Split {
            created = split
            return createAnswer(split).also { saved = it }
        }

        override suspend fun splits() = listOfNotNull(saved?.let { SplitSummary(id = it.id, title = it.title, total = it.total) })

        override suspend fun split(id: String) = saved!!

        override suspend fun settle(splitId: String, participantId: String, settled: Boolean): Split {
            this.settled += Triple(splitId, participantId, settled)
            val split = saved!!
            return split.copy(participants = split.participants.map { if (it.id == participantId) it.copy(settled = settled) else it }).also { saved = it }
        }

        override suspend fun delete(splitId: String) = Unit

        override fun close() = Unit
    }

    private class FakeConsent : AiConsentRepository {
        val agreed = mutableListOf<String>()
        override suspend fun policy() = AiPolicy(version = "ai-v1", body = "We send the receipt's text to an AI service.")
        override suspend fun consent(version: String) {
            agreed += version
        }
        override suspend fun status() = AiConsentStatus()
        override fun close() = Unit
    }

    private class FakeReader(private val lines: List<String>) : StatementReader {
        val read = mutableListOf<String>()
        override suspend fun read(source: String, password: String?, onPage: (Int, Int) -> Unit): ExtractedDocument {
            read += source
            return ExtractedDocument(pages = listOf(ExtractedPage(0, lines.map { ExtractedLine(it) })))
        }
        override fun close() = Unit
    }

    private val receiptLines = listOf("Blue Tokai", "Cappuccino 220.00", "Sandwich 340.00", "GST 28.00")

    private fun model(
        splits: FakeSplits = FakeSplits(),
        consent: FakeConsent = FakeConsent(),
        reader: FakeReader = FakeReader(receiptLines),
    ) = SplitViewModel(SavedStateHandle()) { LocalDate(2026, 11, 4) }.also {
        it.bind("user-1", "INR", "en") { SplitRepositories(splits, consent, reader) }
    }

    private val SplitViewModel.state get() = uiState.value

    @Test
    fun `continue is blocked until both the source and the mode are chosen`() {
        val model = model()

        model.continueFromStart()
        assertEquals(SplitStep.START, model.state.step)

        model.chooseSource(SplitSource.MANUAL)
        model.continueFromStart()
        assertEquals(SplitStep.START, model.state.step)
        assertFalse(model.state.canContinueFromStart)

        model.chooseMode(SplitMode.EQUAL)
        assertTrue(model.state.canContinueFromStart)
        model.continueFromStart()
        assertEquals(SplitStep.BILL, model.state.step)
        assertEquals("2026-11-04", model.state.draft.occurredOn)
    }

    @Test
    fun `typing a bill by items asks how many and shows that many rows`() {
        val model = model()
        model.chooseSource(SplitSource.MANUAL)
        model.chooseMode(SplitMode.ITEMS)
        model.continueFromStart()

        model.onItemCountChange("3")
        assertEquals(3, model.state.draft.items.size)
        assertEquals(SplitBlock.ITEM_NAME_MISSING, model.state.billBlock)

        model.onItemChange(0, "Pizza", "450")
        model.onItemChange(1, "Pasta", "320")
        model.onItemChange(2, "Lemonade", "90")
        model.onTitleChange("Dinner")
        // Who is on the bill and who had what are later steps: they do not stop this one.
        assertNull(model.state.billBlock)
        assertTrue(model.state.canContinueFromBill)

        // Emptying the field keeps the rows; a smaller number trims them.
        model.onItemCountChange("")
        assertEquals(3, model.state.draft.items.size)
        model.onItemCountChange("2")
        assertEquals(listOf("Pizza", "Pasta"), model.state.draft.items.map { it.name })

        model.continueFromBill()
        assertEquals(SplitStep.PEOPLE, model.state.step)
    }

    @Test
    fun `a receipt read on the phone fills the draft`() {
        val splits = FakeSplits()
        splits.receiptAnswers += ReceiptRead(
            merchant = "Blue Tokai",
            occurredOn = "2026-11-02",
            items = listOf(ReceiptItem("Cappuccino", 1, "220.00"), ReceiptItem("Sandwich", 1, "340.00")),
            tax = "28.00",
            total = "588.00",
            unreadable = 1,
        )
        val reader = FakeReader(receiptLines)
        val model = model(splits = splits, reader = reader)
        model.chooseSource(SplitSource.RECEIPT)
        model.chooseMode(SplitMode.ITEMS)
        model.continueFromStart()

        model.onReceiptPicked("content://receipt/1")

        assertEquals(listOf("content://receipt/1"), reader.read)
        assertEquals(receiptLines.joinToString("\n"), splits.receiptTexts.single())
        val state = model.state
        assertEquals(ReceiptStage.READ, state.receiptStage)
        assertEquals("Blue Tokai", state.draft.title)
        assertEquals("2026-11-02", state.draft.occurredOn)
        assertEquals(listOf("Cappuccino", "Sandwich"), state.draft.items.map { it.name })
        assertEquals("28.00", state.draft.tax)
        assertEquals(1, state.unreadable)
        assertTrue(state.canContinueFromBill)
    }

    @Test
    fun `consent asked for by the server is shown and the receipt read again once given`() {
        val splits = FakeSplits()
        splits.receiptAnswers += ApiException.ConsentRequired("ai-v1")
        splits.receiptAnswers += ReceiptRead(merchant = "Cafe", total = "600.00")
        val consent = FakeConsent()
        val model = model(splits = splits, consent = consent)
        model.chooseSource(SplitSource.RECEIPT)
        model.chooseMode(SplitMode.EQUAL)
        model.continueFromStart()

        model.onReceiptPicked("content://receipt/1")

        val ask = assertNotNull(model.state.consent)
        assertEquals("ai-v1", ask.policy?.version)
        assertEquals(ReceiptStage.PICK, model.state.receiptStage)

        // Nothing is pre-ticked: agreeing without the tick does nothing.
        model.agree()
        assertTrue(consent.agreed.isEmpty())

        model.onConsentTicked(true)
        model.agree()

        assertEquals(listOf("ai-v1"), consent.agreed)
        assertNull(model.state.consent)
        assertEquals(2, splits.receiptTexts.size)
        assertEquals(splits.receiptTexts[0], splits.receiptTexts[1])
        assertEquals(ReceiptStage.READ, model.state.receiptStage)
        assertEquals("600.00", model.state.draft.total)
    }

    @Test
    fun `a new person is saved and put on the bill`() {
        val splits = FakeSplits()
        val model = model(splits = splits)
        model.openPeople()
        assertEquals(listOf(PRIYA), model.state.contacts)

        model.openNewPerson()
        model.onNewPersonName("Rohan Mehta")
        model.onNewPersonPhone("+91 98111 22233")
        model.saveNewPerson()

        assertEquals(listOf("Rohan Mehta" to "+91 98111 22233"), splits.added)
        val state = model.state
        assertNull(state.newPerson)
        assertTrue(state.contacts.any { it.id == "c-new" })
        assertEquals(listOf(null, "c-new"), state.draft.people.map { it.contactId })
        assertEquals(1, state.chosenCount)
        // Opened from Start: Continue goes back there.
        model.continueFromPeople()
        assertEquals(SplitStep.START, model.state.step)
    }

    @Test
    fun `someone added can be taken off the bill but never you`() {
        val model = model()
        model.toggleContact(PRIYA)
        assertEquals(2, model.state.draft.people.size)

        model.removePerson(0)
        assertEquals(2, model.state.draft.people.size, "the person themselves stays")

        model.removePerson(1)
        assertEquals(listOf(null), model.state.draft.people.map { it.contactId })
    }

    private fun equalSplitWithPriya(model: SplitViewModel) {
        model.chooseSource(SplitSource.MANUAL)
        model.chooseMode(SplitMode.EQUAL)
        model.continueFromStart()
        model.onTitleChange("Dinner")
        model.onTotalChange("600")
        model.continueFromBill()
        model.toggleContact(PRIYA)
        model.continueFromPeople()
    }

    @Test
    fun `saving sends the draft as toSave builds it`() {
        val splits = FakeSplits()
        val model = model(splits = splits)
        equalSplitWithPriya(model)
        assertEquals(SplitStep.REVIEW, model.state.step)
        val expected = SplitExpense.toSave(model.state.draft, "INR")

        model.save()

        assertEquals(expected, splits.created)
        assertEquals("600.00", splits.created?.total)
        assertEquals(listOf(null, "c-priya"), splits.created?.participants?.map { it.contactId })
        val state = model.state
        assertEquals("s1", state.savedId)
        assertTrue(state.reviewingSaved)
        assertEquals(listOf(SplitStep.START, SplitStep.HISTORY, SplitStep.REVIEW), state.stack)
        // The bill is done: a fresh one waits behind the history.
        assertNull(state.draft.mode)
    }

    private fun savedSplit(share: String) = Split(
        id = "s1",
        title = "Dinner",
        occurredOn = "2026-11-04",
        mode = "equal",
        currency = "INR",
        total = "600.00",
        participants = listOf(
            SplitParticipant(id = "p-self", isSelf = true, name = "", share = "267.00"),
            SplitParticipant(id = "p-priya", contactId = "c-priya", name = "Priya Shah", phone = "+919876543210", share = share),
        ),
        owedToYou = share,
    )

    @Test
    fun `whatsapp carries the saved splits share not the preview`() {
        val splits = FakeSplits()
        // The server's answer differs from the even 300.00 the preview showed.
        splits.createAnswer = { savedSplit(share = "333.00") }
        val model = model(splits = splits)
        equalSplitWithPriya(model)
        assertEquals("₹300.00", model.state.previewRows[1].share)

        model.save()

        val priya = assertNotNull(model.state.participant("p-priya"))
        val link = assertNotNull(model.state.whatsAppLinkFor(priya))
        assertTrue(link.startsWith("https://wa.me/919876543210?text="))
        assertTrue("333.00" in link)
        assertFalse("300.00" in link)
        assertEquals(SplitExpense.whatsAppLink(priya.phone, SplitExpense.message(savedSplit("333.00"), priya, SplitExpense.Words("INR", "en"))), link)
    }

    @Test
    fun `marking someone paid sends it and shows the servers answer`() {
        val splits = FakeSplits()
        splits.createAnswer = { savedSplit(share = "300.00") }
        val model = model(splits = splits)
        equalSplitWithPriya(model)
        model.save()

        model.toggleSettled("p-priya")

        assertEquals(listOf(Triple("s1", "p-priya", true)), splits.settled)
        assertTrue(model.state.participant("p-priya")!!.settled)
        assertTrue(model.state.settling.isEmpty())

        // The person themselves is never settled.
        model.toggleSettled("p-self")
        assertEquals(1, splits.settled.size)
    }

    @Test
    fun `back walks the steps and leaves only from the start`() {
        val model = model()
        model.chooseSource(SplitSource.MANUAL)
        model.chooseMode(SplitMode.EQUAL)
        model.continueFromStart()

        assertTrue(model.back())
        assertEquals(SplitStep.START, model.state.step)
        assertFalse(model.back())
    }

    @Test
    fun `the draft survives the process being reclaimed for the same person`() {
        val saved = SavedStateHandle()
        val splits = FakeSplits()
        val first = SplitViewModel(saved) { LocalDate(2026, 11, 4) }
        first.bind("user-1", "INR", "en") { SplitRepositories(splits, FakeConsent(), FakeReader(receiptLines)) }
        first.chooseSource(SplitSource.MANUAL)
        first.chooseMode(SplitMode.ITEMS)
        first.continueFromStart()
        first.onItemCountChange("2")
        first.onItemChange(0, "Pizza", "450")
        first.toggleContact(PRIYA)
        first.toggleAssignee(0, 1)

        val again = SplitViewModel(saved) { LocalDate(2026, 11, 4) }
        again.bind("user-1", "INR", "en") { SplitRepositories(splits, FakeConsent(), FakeReader(receiptLines)) }

        assertEquals(first.state.draft, again.state.draft)
        assertEquals(SplitStep.BILL, again.state.step)

        // Someone else signing in on the phone starts clean.
        val other = SplitViewModel(saved) { LocalDate(2026, 11, 4) }
        other.bind("user-2", "INR", "en") { SplitRepositories(splits, FakeConsent(), FakeReader(receiptLines)) }
        assertNull(other.state.draft.mode)
        assertEquals(SplitStep.START, other.state.step)
    }
}
