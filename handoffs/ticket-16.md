# Handoff — ticket #16

**Ticket:** [#16 — \[M2\] Build the splash screen and signup flow](https://github.com/Humble-Coders/FinAI-Mobile-2026/issues/16)
**Branch:** `ticket-16-splash-and-signup` · **Base:** `main` (`0098139`) · **PR:** #21 · 72 files (19 shared, 26 Android, 24 iOS, 3 other)

## Summary

The app was the ticket-6 demo screen on both platforms. It now opens on a real
splash, routes on what the API says is outstanding, and carries every signup
route to a verified phone number.

The centre of it is **one pure routing function** in `sharedLogic`
(`usecase/OnboardingRouter.kt`): session state plus `/me` goes in, the next
screen comes out. Android and iOS both render its answer and neither infers a
step. It is tested as a full matrix — every session state against every
onboarding step, including ones this build does not know.

Both launch screens were wrong in dark mode and both are fixed at the level
that actually matters: the window, before any app code runs.

## Files changed

**Shared — the brain (19 files)**

| File | Why |
|---|---|
| `usecase/OnboardingRouter.kt` | `Destination`, `Screen`, and the one routing rule. |
| `util/DialCodes.kt` | Every E.164 calling code, and which to pre-select. No country names — see below. |
| `repository/AuthRepository.kt` | Provider sign-in, phone linking, terms, region, consent. |
| `data/SupabaseAuthRepository.kt` | Implements them; the two ticket-6 test aids removed. |
| `data/ApiErrorMapper.kt`, `data/SupabaseErrors.kt` | `phone_already_linked` and `terms_version_mismatch` become typed errors. |
| `model/` | `Me.terms`, `Terms`, `SocialProvider`, two new `ApiException` cases. |
| `i18n/` | Every label, plus `{0}` placeholder substitution. |
| `build.gradle.kts` | Generates `GoogleConfig` from `local.properties`. |

**Android (26 files)** — `navigation/AppNavigation.kt`, `ui/onboarding/*` (state, view model, five screens), `ui/components/*`, `ui/theme/*` from the approved palette, `auth/GoogleSignIn.kt`, the splash theme in `res/values{,-night}/`, and `MainActivity`. The demo is deleted.

**iOS (24 files)** — `navigation/RootView.swift`, `viewmodel/OnboardingViewModel.swift`, `ui/*` (the same screens in SwiftUI), `auth/AppleSignIn.swift` and `auth/GoogleSignIn.swift`, asset-catalog colours with dark variants, the declared launch screen, and the entitlement. The demo is deleted.

## How to test

```bash
./gradlew :androidApp:assembleDebug :sharedLogic:allTests
./gradlew :sharedLogic:podInstall && cd iosApp && xcodebuild -workspace iosApp.xcworkspace -scheme iosApp -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

Both were run on this branch: **85 Android host tests, 78 iOS simulator tests, 0 failures**, and `BUILD SUCCEEDED` on the iOS workspace.

The behaviour worth reading rather than running is `OnboardingRouterTest` — it is the matrix that stops the two platforms disagreeing.

## Acceptance criteria

| Criterion | Status |
|---|---|
| The routing function has a test for every session state and `onboarding_required` value including `UNKNOWN` | **Met** — `OnboardingRouterTest`, 14 cases |
| The demo screens, demo aids, `ContentView.swift` and `PlaceholderScreen.kt` are gone | **Met** |
| `ThrowsAnnotationGuardTest` passes | **Met** |
| No user-facing string literal in `androidApp` or `iosApp` | **Met except the wordmark**, which hardcodes `Fin` + `AI` on both platforms so the accent can be coloured. A brand mark, never translated — raised in review for the manager to exempt or change |
| Gradle and `xcodebuild` both pass | **Met** |
| Changing the dropdown's country changes only the dialling code | **Met** — `DialCodes` carries no region decision; `DialCodesTest` pins that CA and US share `+1` |
| The app never reaches home while `onboarding_required` is non-empty | **Met in code** — every step, known or unknown, has a destination that is not Home |
| Splash lasts only while the session restores and the first `/me` loads; slow shows progress | **Met in code** — `setKeepOnScreenCondition` on Android, `screen == .splash` on iOS; no timer on either |
| No white flash in dark mode on Android | **Met** — launch theme now takes `@color` with a `values-night` variant |
| **Phone route reaches home, on both platforms** | **Partly verified on Android** — code sent, code verified and a session created through Supabase; the router then reaches `Failed` because `/me` returns 503, which only happens for a signed-in caller. Everything past `/me` is still unverified |
| **Google and Apple routes** | **NOT VERIFIED** — no client ids exist yet |
| **Phone already taken shows the message and creates no second household** | **NOT VERIFIED** — needs a second test number |
| **Cold start with no flash of welcome** | **NOT VERIFIED** end to end — needs a live `/me` |
| Matches the approved design in light and dark | **Partly** — built from the splash design; only the splash was supplied |

## Deviations / decisions

- **The signup screens were designed, not matched.** Only the splash design was
  supplied. The PM approved deriving the rest from it (palette, wordmark, one
  green accent), so the screens follow that language rather than an approved
  comp. The remaining screens — region, update-required, error, home — were not
  mocked up at all.
- **App name is `FinAI` everywhere including the splash**, on the PM's
  instruction, though the supplied design reads "FinAI Advisor".
- **Country names come from the platform**, not `sharedLogic/i18n`. `Locale`
  already translates every region code; 240 hand-written English names would be
  wrong for most users and a permanent translation burden. Shared owns the
  dialling codes, which are the same everywhere.
- **Six code cells are decoration over one text field.** Six real fields break
  paste and fight autofill and the one-tap SMS suggestion.
- **`financial_setup` gets a placeholder screen** that still blocks home. 2.4
  (#17) replaces it with the wizard.
- **Sign in with Apple uses Apple's own button**, not the outlined
  `ProviderButton` beside it — Apple requires their button for that flow.
  `whiteOutline` is the closest permitted style.
- **The splash carries no logo.** The PM's logo vector was requested and has not
  arrived. Android uses a stand-in vector of the mark; iOS shows the brand
  ground with no image. Replacing them is one file each.
- **`Config.xcconfig` now sets `CODE_SIGN_ENTITLEMENTS`** rather than editing
  `project.pbxproj`, so the setting stays hand-editable.
- **One vertical scroll per screen, in the scaffold.** The consent screen
  originally nested a second one inside it on both platforms. Nesting does not
  crash the way a lazy list would, so the mistake is silent: the gesture goes to
  whichever claims it first. The terms now lay out in full and the page scrolls,
  which also keeps the Agree button reachable at the largest accessibility font
  sizes. Both scaffolds document the rule.

## Found by running it

Two defects that no test, no build and neither CI job could see. Both were
caught by installing on a Pixel 8 Pro (API 35) and looking at the screens.

- **Every screen heading was invisible in dark mode.** `FinAiTheme` called
  `MaterialTheme(...)` without a `Surface`, so `LocalContentColor` was never
  supplied from the colour scheme and fell back to Compose's default of BLACK.
  In light mode that looked right by accident; on the near-black dark ground it
  left the wordmark's `Fin`, `code_title`, `consent_title`, `region_title`,
  `phone_link_title`, `home_title` and the status headings unreadable. Fixed at
  the theme, which fixes every screen at once.
- **The dialling-code picker did not look tappable on Android.** It was bare
  text with a `clickable` modifier and no background, while iOS gave the same
  control a filled surface — so the platforms disagreed and the Android one
  read as a label. It now has the same filled surface.

- **The error screen was a dead end.** It offered Retry and nothing else, so a
  signed-in caller whose `/me` fails had no other screen to be on and no way
  back — every retry fails for as long as the server is down. It now also offers
  Sign out, which returns to the welcome screen because that needs nothing from
  the API. Found by verifying a phone number against a suspended backend and
  being unable to leave the screen.

- **iOS crashed on launch for every signed-out user.** `@Published var code`
  assigned to itself inside its own `didSet`; unlike a plain stored property,
  `@Published` routes that through the wrapper's setter and re-enters `didSet`,
  so it recursed until the stack overflowed (`EXC_BAD_ACCESS`, *"Thread stack
  size exceeded due to excessive recursion"*). `bind()` sets `code = ""` the
  moment Supabase reports no stored session, so the app died on the splash
  before ever showing signup. Found only by running it — the iOS build, the
  shared tests and CI were all green throughout.

Also confirmed on the device rather than asserted: the dark-mode launch screen
paints the near-black ground with no white flash, the splash hands over without
hanging, and a `/me` that returns 503 lands on the error screen with Retry —
the `Failed` destination working end to end against a genuinely dead backend.

- **Apple is offered on iOS only** (manager decision, 2026-09-11), so the
  Android welcome screen shows phone + Google and no Apple button. Someone who
  signed up with Apple on an iPhone signs in on Android with their number and
  lands in the same account — the verified number is the identity key. Offering
  it on Android would mean the OAuth web redirect flow (Services ID, signing
  key, browser round-trip) to reach an account they can already reach by typing
  their number. `PhoneScreen`'s unused `onApple` parameter and the
  `welcome_apple` string key are gone with it: Apple's own button supplies and
  localises its label, so nothing ever read that key.

## Open questions / follow-ups

- **Nothing that ends at `/me` has been exercised.** Render returns 503
  `x-render-routing: suspend`, and production is two migrations behind. Four
  acceptance criteria stay unverified until it is back.
- **The providers have never run.** No Google client ids exist and the Supabase
  providers are not configured, so that code compiles and nothing more. It is
  the one part of this branch with no evidence behind it.
- **Sign in with Apple needs the App ID registered** with that capability under
  team `53GDRR35QU`, or signing fails. The entitlement is declared; the portal
  side is not done.
- **`androidx-core = "1.19.0"` in the version catalog cannot be used** by this
  toolchain: it demands compileSdk 37 and AGP 9.1.0 against the project's 36 and
  9.0.1. Nothing referenced it, so it had never surfaced. Declaring `core-ktx`
  broke the build immediately; the dependency was dropped rather than bumping
  AGP mid-ticket. Worth fixing on its own.
- **Two Swift names do not follow the rule of thumb.** `ApiException.TermsChanged`
  and `Destination.Failed` stay **nested** in Swift, where `kmp-arch-v2` says
  nested types flatten. The generated apinotes are the authority; guessing cost
  a build.
- **The `UNKNOWN` step now has a real destination** — an update-required screen
  on both platforms, which is what the review of PR #20 asked for.
