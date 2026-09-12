package com.humblesolutions.finai.usecase

import com.humblesolutions.finai.model.ApiException
import com.humblesolutions.finai.model.Me
import com.humblesolutions.finai.model.OnboardingStep
import com.humblesolutions.finai.model.SessionState

/**
 * Where the app should be right now.
 *
 * The code-entry screen is deliberately absent: it is not a state the server
 * knows about. It is reached from [Step] `PHONE` once a code has been sent, and
 * that transition belongs to the phone screen.
 */
sealed class Destination {

    /**
     * This destination as one switchable value.
     *
     * A member rather than an extension because Swift reaches a member as a
     * plain property, while a top-level extension arrives as a static function
     * on a synthesised file class.
     */
    abstract val screen: Screen

    /** The session is restoring, or the first `/me` is still in flight. */
    data object Splash : Destination() {
        override val screen: Screen get() = Screen.SPLASH
    }

    /** Signed out: phone entry and the provider buttons. */
    data object Welcome : Destination() {
        override val screen: Screen get() = Screen.WELCOME
    }

    /** An onboarding step this build knows how to show. */
    data class Step(val step: OnboardingStep) : Destination() {
        override val screen: Screen
            get() = when (step) {
                OnboardingStep.PHONE -> Screen.PHONE
                OnboardingStep.REGION -> Screen.REGION
                OnboardingStep.CONSENT -> Screen.CONSENT
                OnboardingStep.FINANCIAL_SETUP -> Screen.FINANCIAL_SETUP
                // The router never builds this, but the type allows it.
                OnboardingStep.UNKNOWN -> Screen.UPDATE_REQUIRED
            }
    }

    /**
     * The API named a step this build does not know, so there is no screen to
     * send the user to and no way for them to finish onboarding — they need a
     * newer app. Never silently treated as "done": an unknown step means
     * something IS outstanding.
     */
    data object UpdateRequired : Destination() {
        override val screen: Screen get() = Screen.UPDATE_REQUIRED
    }

    /** Nothing outstanding. */
    data object Home : Destination() {
        override val screen: Screen get() = Screen.HOME
    }

    /** `/me` could not be loaded. The screen shows [error] and a Retry. */
    data class Failed(val error: ApiException) : Destination() {
        override val screen: Screen get() = Screen.FAILED
    }
}

/**
 * A [Destination] flattened to one value.
 *
 * Exists for Swift: a sealed class with associated values is awkward to switch
 * on across the bridge, whereas an enum is not. The payloads stay on
 * [Destination] for whoever needs them.
 */
enum class Screen {
    SPLASH,
    WELCOME,
    PHONE,
    REGION,
    CONSENT,
    FINANCIAL_SETUP,
    UPDATE_REQUIRED,
    HOME,
    FAILED,
}

/**
 * The single routing rule, read by Android and iOS alike.
 *
 * Pure, so the whole matrix is unit-tested without a network or a simulator,
 * and — more importantly — so the two platforms cannot drift apart. The server
 * already learned this lesson: `/me` and `/capabilities` once decided
 * onboarding separately and disagreed about a user with a verified phone
 * (FinAI-Mobile-2026#6), and the fix was one rule rather than two. Same here.
 *
 * The app **never infers a step**. It routes to the first entry the API lists,
 * in the order the API lists it.
 */
object OnboardingRouter {

    /**
     * @param me the last `/me` this session loaded, or null if none has arrived.
     * @param failure why the last `/me` attempt failed, or null.
     */
    fun destinationFor(
        session: SessionState,
        me: Me?,
        failure: ApiException?,
    ): Destination = when (session) {
        SessionState.LOADING -> Destination.Splash
        SessionState.SIGNED_OUT -> Destination.Welcome
        // A failed refresh keeps the session and retries, so it routes like a
        // signed-in user. Whether anything can actually be loaded is the
        // failure's business, below.
        SessionState.SIGNED_IN, SessionState.REFRESH_FAILED -> signedIn(me, failure)
    }

    private fun signedIn(me: Me?, failure: ApiException?): Destination {
        // The server rejected the token even after a refresh: the session is
        // gone whatever the session state still says. Signing in again is the
        // only way forward, and an error screen would offer a Retry that could
        // never succeed.
        if (failure is ApiException.Unauthorized) return Destination.Welcome

        if (me == null) {
            return if (failure != null) Destination.Failed(failure) else Destination.Splash
        }

        // A `/me` already loaded outranks a later failure: a refresh that fails
        // while the user is deep in onboarding must not throw them out to an
        // error screen over data we already hold.
        val next = me.onboardingRequired.firstOrNull() ?: return Destination.Home
        return if (next == OnboardingStep.UNKNOWN) {
            Destination.UpdateRequired
        } else {
            Destination.Step(next)
        }
    }
}
