package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Me
import com.humblesolutions.finai.model.OnboardingStep
import com.humblesolutions.finai.model.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The full matrix. Both platforms route from this one function, so anything it
 * gets wrong is wrong twice — and a wrong answer here either strands a user
 * outside the app or lets them past onboarding that the server still requires.
 */
class OnboardingRouterTest {

    private fun me(vararg steps: OnboardingStep) = Me(onboardingRequired = steps.toList())

    private fun route(
        session: SessionState,
        me: Me? = null,
        failure: ApiException? = null,
    ) = OnboardingRouter.destinationFor(session, me, failure)

    private val network = ApiException.Network(RuntimeException("offline"))
    private val unauthorized = ApiException.Unauthorized("token rejected")

    @Test
    fun `a restoring session waits on the splash whatever else is known`() {
        assertEquals(Destination.Splash, route(SessionState.LOADING))
        assertEquals(Destination.Splash, route(SessionState.LOADING, me()))
        assertEquals(Destination.Splash, route(SessionState.LOADING, me(OnboardingStep.PHONE)))
        assertEquals(Destination.Splash, route(SessionState.LOADING, me(), network))
    }

    @Test
    fun `a signed out user goes to welcome even if a stale me is still held`() {
        assertEquals(Destination.Welcome, route(SessionState.SIGNED_OUT))
        assertEquals(Destination.Welcome, route(SessionState.SIGNED_OUT, me()))
    }

    @Test
    fun `a signed in user with no me yet waits on the splash`() {
        assertEquals(Destination.Splash, route(SessionState.SIGNED_IN))
    }

    @Test
    fun `nothing outstanding is home`() {
        assertEquals(Destination.Home, route(SessionState.SIGNED_IN, me()))
    }

    @Test
    fun `every known step routes to itself`() {
        val known = OnboardingStep.entries.filter { it != OnboardingStep.UNKNOWN }
        assertTrue(known.size >= 4, "expected phone region consent and financial setup")
        for (step in known) {
            assertEquals(
                Destination.Step(step),
                route(SessionState.SIGNED_IN, me(step)),
                "step ${step.wire}",
            )
        }
    }

    @Test
    fun `a step this build does not know asks the user to update`() {
        assertEquals(
            Destination.UpdateRequired,
            route(SessionState.SIGNED_IN, me(OnboardingStep.UNKNOWN)),
        )
    }

    @Test
    fun `an unknown step never reads as done`() {
        val destination = route(SessionState.SIGNED_IN, me(OnboardingStep.UNKNOWN))
        assertTrue(destination != Destination.Home, "an unknown step must not let the user through")
    }

    @Test
    fun `the first outstanding step wins`() {
        assertEquals(
            Destination.Step(OnboardingStep.PHONE),
            route(SessionState.SIGNED_IN, me(OnboardingStep.PHONE, OnboardingStep.CONSENT)),
        )
        // A known step ahead of an unknown one is still answerable.
        assertEquals(
            Destination.Step(OnboardingStep.PHONE),
            route(SessionState.SIGNED_IN, me(OnboardingStep.PHONE, OnboardingStep.UNKNOWN)),
        )
        // An unknown one first blocks: there is no screen to show for it.
        assertEquals(
            Destination.UpdateRequired,
            route(SessionState.SIGNED_IN, me(OnboardingStep.UNKNOWN, OnboardingStep.PHONE)),
        )
    }

    @Test
    fun `a failure with nothing loaded shows the error`() {
        assertEquals(
            Destination.Failed(network),
            route(SessionState.SIGNED_IN, null, network),
        )
    }

    @Test
    fun `a rejected token sends the user back to welcome rather than an error`() {
        // Retry could never succeed: the session is gone whatever sessionState says.
        assertEquals(Destination.Welcome, route(SessionState.SIGNED_IN, null, unauthorized))
        assertEquals(Destination.Welcome, route(SessionState.SIGNED_IN, me(), unauthorized))
    }

    @Test
    fun `a me already loaded outranks a later failure`() {
        // A refresh failing mid-onboarding must not throw the user out to an
        // error screen over data already held.
        assertEquals(
            Destination.Step(OnboardingStep.CONSENT),
            route(SessionState.SIGNED_IN, me(OnboardingStep.CONSENT), network),
        )
        assertEquals(Destination.Home, route(SessionState.SIGNED_IN, me(), network))
    }

    @Test
    fun `a failed refresh routes exactly like a signed in session`() {
        for (me in listOf(me(), me(OnboardingStep.PHONE), me(OnboardingStep.UNKNOWN))) {
            assertEquals(
                route(SessionState.SIGNED_IN, me),
                route(SessionState.REFRESH_FAILED, me),
            )
        }
        assertEquals(Destination.Splash, route(SessionState.REFRESH_FAILED))
    }

    @Test
    fun `every session state is answered`() {
        for (session in SessionState.entries) {
            for (me in listOf(null, me(), me(OnboardingStep.PHONE))) {
                for (failure in listOf(null, network, unauthorized)) {
                    route(session, me, failure)
                }
            }
        }
    }
}
