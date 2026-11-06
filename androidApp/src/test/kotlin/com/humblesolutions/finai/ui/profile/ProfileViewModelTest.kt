package com.humblesolutions.finai.ui.profile

import com.humblesolutions.finai.i18n.Strings
import com.humblesolutions.finai.model.AiConsentStatus
import com.humblesolutions.finai.model.AiPolicy
import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Me
import com.humblesolutions.finai.model.MeHousehold
import com.humblesolutions.finai.model.MeTerms
import com.humblesolutions.finai.model.MeUser
import com.humblesolutions.finai.model.SessionState
import com.humblesolutions.finai.model.SocialProvider
import com.humblesolutions.finai.model.Terms
import com.humblesolutions.finai.repository.AuthRepository
import com.humblesolutions.finai.repository.LegalRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val me = Me(
        user = MeUser(id = "user-1", email = "asha@example.com", phone = "+14165550100", displayName = "Asha Rao"),
        household = MeHousehold(id = "h-1", countryCode = "CA"),
        terms = MeTerms(version = "terms-v3", accepted = true),
    )

    /** The session and `/me`; only what Profile calls does anything. */
    private class FakeAuth(var current: Me) : AuthRepository {
        val session = MutableStateFlow(SessionState.SIGNED_IN)
        val names = mutableListOf<String>()
        var deleteFails: ApiException? = null
        var deletes = 0

        override val sessionState: Flow<SessionState> = session
        override suspend fun me(): Me = current
        override suspend fun updateName(name: String): Me {
            names += name
            current = current.copy(user = current.user.copy(displayName = name))
            return current
        }
        override suspend fun deleteAccount() {
            deletes++
            deleteFails?.let { throw it }
            // As the real one does: the session is dropped once the server has deleted.
            session.value = SessionState.SIGNED_OUT
        }
        override suspend fun setRegion(countryCode: String): Me {
            current = current.copy(household = current.household.copy(countryCode = countryCode))
            return current
        }

        override suspend fun signUpWithEmail(email: String, password: String) = Unit
        override suspend fun verifySignupCode(email: String, code: String) = Unit
        override suspend fun resendSignupCode(email: String) = Unit
        override suspend fun signInWithEmail(email: String, password: String) = Unit
        override suspend fun requestPasswordReset(email: String) = Unit
        override suspend fun verifyPasswordResetCode(email: String, code: String) = Unit
        override suspend fun setNewPassword(password: String) = Unit
        override suspend fun signInWithIdToken(provider: SocialProvider, idToken: String, nonce: String?) = Unit
        override suspend fun requestPhoneLink(phone: String) = Unit
        override suspend fun verifyPhoneLink(phone: String, code: String) = Unit
        override suspend fun signOut() = Unit
        override suspend fun terms(): Terms = Terms(version = "terms-v3", body = "The terms.")
        override suspend fun acceptTerms(version: String): Me = current
        override fun close() = Unit
    }

    private class FakeLegal(var consented: Boolean = false) : LegalRepository {
        var policyVersion = "ai-v2"
        val agreed = mutableListOf<String>()
        var withdrawals = 0

        override suspend fun terms() = Terms(version = "terms-v3", body = "The terms.")
        override suspend fun disclaimer(): Terms? = Terms(version = "ca-v1", body = "Educational guidance, not financial advice.")
        override suspend fun aiPolicy() = AiPolicy(version = policyVersion, body = "What is sent, and why.")
        override suspend fun aiStatus() = AiConsentStatus(consented = consented, version = policyVersion)
        override suspend fun consentToAi(version: String) {
            if (version != policyVersion) throw ApiException.AiPolicyChanged(policyVersion)
            agreed += version
            consented = true
        }
        override suspend fun withdrawAi(): AiConsentStatus {
            withdrawals++
            consented = false
            return AiConsentStatus(consented = false, version = policyVersion)
        }
        override fun close() = Unit
    }

    private fun model(auth: FakeAuth = FakeAuth(me), legal: FakeLegal = FakeLegal()) = ProfileViewModel().also {
        it.bind("user-1") { ProfileRepositories(auth, legal) }
    }

    @Test
    fun opening_shows_who_the_person_is_and_where_their_consents_stand() = runTest {
        val state = model(legal = FakeLegal(consented = true)).uiState.value

        assertEquals("Asha Rao", state.displayName)
        assertEquals("AR", state.initials)
        assertEquals("CA", state.countryCode)
        assertEquals("terms-v3", state.acceptedTerms)
        assertTrue(state.aiOn)
        assertEquals("Educational guidance, not financial advice.", state.disclaimer?.body)
    }

    // ── Name ────────────────────────────────────────────────────────────

    @Test
    fun a_name_is_saved_trimmed_and_said_once() = runTest {
        val auth = FakeAuth(me)
        val model = model(auth)
        var saved = 0

        model.openName()
        assertEquals("Asha Rao", model.uiState.value.nameDraft, "the sheet opens on the name as it is")
        model.onNameChange("  Asha Mehta  ")
        model.saveName { saved++ }

        assertEquals(listOf("Asha Mehta"), auth.names)
        assertEquals(1, saved, "the router is told, so Home's monogram follows")
        val state = model.uiState.value
        assertFalse(state.editingName)
        assertEquals("AM", state.initials)
        assertEquals(Strings.profile_saved, state.noticeKey)
    }

    @Test
    fun a_blank_name_is_refused_without_asking_the_server() = runTest {
        val auth = FakeAuth(me)
        val model = model(auth)
        var saved = 0

        model.openName()
        model.onNameChange("   ")
        model.saveName { saved++ }

        assertTrue(auth.names.isEmpty())
        assertEquals(0, saved)
        assertTrue(model.uiState.value.editingName, "the sheet stays open to fix it")
        assertEquals(Strings.profile_name_missing, model.uiState.value.nameErrorKey)
    }

    // ── AI processing ───────────────────────────────────────────────────

    @Test
    fun ai_is_turned_on_only_once_the_box_is_ticked() = runTest {
        val legal = FakeLegal(consented = false)
        val model = model(legal = legal)
        assertFalse(model.uiState.value.aiOn)

        model.openAiOn()
        assertEquals("ai-v2", model.uiState.value.aiPolicy?.version)
        model.agreeToAi()
        assertTrue(legal.agreed.isEmpty(), "nothing is agreed to before the box is ticked")

        model.onAiTicked(true)
        model.agreeToAi()

        assertEquals(listOf("ai-v2"), legal.agreed)
        val state = model.uiState.value
        assertTrue(state.aiOn)
        assertFalse(state.turningAiOn)
    }

    @Test
    fun a_policy_changed_while_open_is_shown_again_unticked() = runTest {
        val legal = FakeLegal(consented = false)
        val model = model(legal = legal)
        model.openAiOn()
        model.onAiTicked(true)

        legal.policyVersion = "ai-v3"
        model.agreeToAi()

        val state = model.uiState.value
        assertEquals("ai-v3", state.aiPolicy?.version)
        assertFalse(state.aiTicked, "agreeing again is deliberate")
        assertTrue(state.turningAiOn)
        assertFalse(state.aiOn)
    }

    @Test
    fun ai_is_turned_off_after_asking() = runTest {
        val legal = FakeLegal(consented = true)
        val model = model(legal = legal)

        model.askAiOff()
        assertTrue(model.uiState.value.confirmingAiOff)
        model.turnAiOff()

        assertEquals(1, legal.withdrawals)
        assertFalse(model.uiState.value.aiOn)
        assertFalse(model.uiState.value.confirmingAiOff)
    }

    // ── Deleting the account ────────────────────────────────────────────

    @Test
    fun delete_waits_for_the_word_then_clears_the_session() = runTest {
        val auth = FakeAuth(me)
        val model = model(auth)

        model.askDelete()
        model.onDeleteTyped("delete")
        model.deleteAccount()
        assertEquals(0, auth.deletes, "only the word, exactly, lets it go ahead")

        model.onDeleteTyped("DELETE")
        model.deleteAccount()

        assertEquals(1, auth.deletes)
        assertEquals(SessionState.SIGNED_OUT, auth.session.value, "the app routes to Welcome from the session")
        assertFalse(model.uiState.value.confirmingDelete)
        assertNull(model.uiState.value.deleteErrorKey)
    }

    @Test
    fun a_deletion_the_server_could_not_do_keeps_the_person_signed_in_and_says_why() = runTest {
        val auth = FakeAuth(me).apply { deleteFails = ApiException.DeletionUnavailable() }
        val model = model(auth)

        model.askDelete()
        model.onDeleteTyped("DELETE")
        model.deleteAccount()

        assertEquals(SessionState.SIGNED_IN, auth.session.value)
        val state = model.uiState.value
        assertTrue(state.confirmingDelete, "the dialog stays, with the reason")
        assertFalse(state.deleting)
        assertEquals(Strings.profile_delete_failed, state.deleteErrorKey)
    }
}
