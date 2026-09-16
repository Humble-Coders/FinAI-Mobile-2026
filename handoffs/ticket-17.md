# Handoff — ticket #17

**Ticket:** [#17 — [M2] Build the financial setup wizard](https://github.com/Humble-Coders/FinAI-Mobile-2026/issues/17)

## Summary

The wizard that stands between signup and the app: monthly income, then monthly
expenses with obligations, then debts and investments. The first two figures are
mandatory — the API keeps reporting `financial_setup` until both are stored — and
everything itemised is optional. Every step saves, because `PUT /financial-setup`
replaces what the wizard owns, so closing the app resumes where it left off and a
failed save never loses what was typed.

Amounts are decimal strings from the text field to the wire, through a new shared
`Money`; no `Double` and no cents appear in client code. The rules for which step
is blocked and why live in shared `SetupWizard`, so the disabled button, the
notice beneath it and the save path cannot drift apart, and Android and iOS
cannot disagree.

The three screens follow the PM's design: step label, progress dots, back arrow,
cards, and one wide illustration with each step showing its own third so the path
runs on from screen to screen.

**Revised after manager review of PR #26 (2026-09-16).** Six things changed: Skip
is drawn only on the step that is optional in full; the inline notice waits until
a figure has been touched; the Android view model became a real `ViewModel` so a
rotation cannot lose a half-typed figure; currency *and* locale now come from the
capabilities payload; the list rows show what they add up to rather than how many
rows they hold; and `androidApp` gained the unit-test source set the ticket asks
for. What the earlier version of this report claimed, and the diff did not
support, is corrected in **Acceptance criteria** below.

## Files changed

**Shared — money**
- `util/Money.kt` — normalise, validate, compare and format decimal strings; the
  currency's symbol and decimal places. `"1.234"` is refused rather than guessed.
  `add` sums two decimal strings digit by digit, so a total never arrives via
  binary floating point.
- `util/MoneyTest.kt` — blanks, separators, too many decimals, huge values, zero,
  and addition (including `0.10 + 0.20`, the case that gives `Double` away).

**Shared — the wizard**
- `model/FinancialSetup.kt` — the wire shapes of `GET/PUT /financial-setup`.
- `repository/FinancialSetupRepository.kt`, `data/KtorFinancialSetupRepository.kt` —
  the two calls; the request omits `currency`, which the server decides.
- `usecase/SetupWizard.kt` — the steps, one blocking-reason function, where to
  resume, the payload every save carries, `SetupStep.isOptional` (which step may
  carry a Skip at all) and `total` (what a list adds up to).
- `model/ApiException.kt`, `data/ApiErrorMapper.kt` — `invalid_amount` becomes a
  typed error naming the field. **Nothing reads that field yet** — see follow-ups.
- `i18n/` — every label the wizard shows, plus `setup_step_counter` so the "1/3"
  counter is not built in platform code; `setup_none_yet` and `setup_items_added`
  removed with the row counts they served.
- Tests: `SetupWizardTest`, `FinancialSetupDecodingTest`,
  `KtorFinancialSetupRepositoryTest` (mock engine), `ApiErrorMapperTest`.

**Android**
- `ui/setup/SetupUiState.kt`, `SetupViewModel.kt`, `SetupScreen.kt` — state, the
  repository's lifecycle, the three steps and the itemised-list editor.
- `ui/setup/SetupUiStateTest.kt` — **new**; the derived rules (`canContinue`,
  `canSkip`, `notice`, `totalOf`, `canKeepRows`) asserted with a plain
  constructor, no Android and no coroutines.
- `androidApp/build.gradle.kts` — the unit-test dependencies that source set needs.
- `navigation/AppNavigation.kt` — `financial_setup` routes to the wizard, which is
  now held as a `ViewModel()` rather than a remembered object.
- `ui/components/FinAiComponents.kt` — `GradientButton` moved here from the
  welcome screen, so both use one control.
- `res/drawable/ic_back.xml`, `ic_chevron_right.xml`, `drawable-*/setup_path.webp`.
- `ui/onboarding/StatusScreens.kt` — the placeholder screen removed.

**iOS**
- `viewmodel/SetupViewModel.swift`, `ui/SetupView.swift` — the same, in SwiftUI,
  plus a keyboard toolbar: a number pad has no return key, so without it there was
  no way off the keyboard.
- `navigation/RootView.swift` — routes `financialSetup` to the wizard.
- `Assets.xcassets/SetupPath.imageset` — the illustration as HEIC.
- `ui/StatusViews.swift` — the placeholder view removed.

**Design**
- `design/illustration/setup-path-source.png` — the PM's full-resolution artwork,
  kept for re-exporting.

## How to test

1. `./gradlew :androidApp:assembleDebug :androidApp:testDebugUnitTest :sharedLogic:testAndroidHostTest`
   — the shared module has no JVM target, so its host tests are
   `testAndroidHostTest`, not `jvmTest`. Last run: **131 shared cases and 8
   Android cases, 0 failures**, `assembleDebug` succeeded.
   `:sharedLogic:allTests` also runs `iosSimulatorArm64Test`, which boots a
   simulator; it has **not** been run locally, and CI is what covers it.
2. `cd iosApp && LANG=en_US.UTF-8 xcodebuild -workspace iosApp.xcworkspace -scheme iosApp -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build`
   — last run: `** BUILD SUCCEEDED **`. (`pod install` only when the Podfile
   changes; it has not.)
3. On a device, signed in and past the phone and consent steps:
   - Step 1 shows **no** error before you type, and no Skip; type `1,200` and it
     is sent as `1200.00`.
   - Type `abc` into a figure: the notice appears then, not before.
   - Step 2 wants an expense figure and carries **no Skip**; open Obligations, add
     a row, save it, and the row on the step shows the total, not a count.
   - Step 3 offers Skip; take it and the app reaches home. Leave a half-finished
     debt row and take Skip anyway: it still goes through.
   - Rotate the phone mid-figure on Android: the figure is still there.
   - iOS, tap an amount field: the keyboard has a **Done** button.
   - Kill the app on step 2 and reopen: it resumes at step 2 with step 1's figure.
   - Turn off the network on a Continue: the error shows and the typed figure stays.

## Acceptance criteria

The ticket's wording, unedited.

- [x] **A user can complete every step. The income and monthly-expense steps offer
      no Skip**, and the app cannot reach home until both are saved; the optional
      steps can each be skipped, and the user still lands on home. — Skip is drawn
      only where `SetupStep.isOptional`, which is the last step alone. Obligations
      (on the mandatory expense step) are skipped by leaving the list empty; see
      deviations.
- [x] Killing the app **before both figures are saved** and reopening resumes at
      the saved state, with the figures intact. — `SetupWizard.resumeAt`; not yet
      confirmed on a device.
- [x] **Once both figures are saved the gate has cleared**, so a relaunch goes
      straight to home; the optional steps are not offered again. — not yet
      confirmed on a device.
- [x] Amounts are decimal strings from the text field to the wire; `util/Money`
      has tests for blanks, separators, too many decimals, huge values and zero;
      **no `Double` in the diff**.
- [x] The Continue button, the inline message and the save path all read the same
      blocking-reason function. — all three read `SetupWizard.blockingReason`; the
      notice additionally waits for the figure to be touched, which changes when
      it is shown, not what it says.
- [x] Validation and network errors show inline and keep the user's input.
- [x] **Currency and formatting come from capabilities; nothing branches on
      country.** — `KtorCapabilitiesRepository` supplies both currency and locale;
      the `/financial-setup` currency is the fallback when that call fails.
- [ ] **Matches the approved design in light and dark mode, and meets the UI
      standards. — NOT VERIFIED.** Nothing has been run on a device or in dark
      mode. This is the one criterion the manager must close.
- [x] `./gradlew …` passes and the iOS `xcodebuild` succeeds (**CI green on both
      jobs**). — run 35096979555 on `480288f`: Android passed in 6m19s, iOS in
      2m57s. That run is also what covers `iosSimulatorArm64Test`, which is not
      run locally.
- [x] No user-facing string literal in `androidApp` or `iosApp`. — the "1/3"
      counter was the last one; it is `setup_step_counter` now.
- [x] *(Scope → Tests)* Money edge cases; blocking reasons per step; repository
      decoding and error mapping on a mock engine; **Android `UiState` derived
      rules**. — the Android source set exists and runs 8 cases.

## Deviations / decisions

- **Three screens, five data areas.** The design shows three steps; the ticket
  defines five. Obligations, debts and investments each became a row that opens an
  itemised list (manager decision, 2026-09-16), so step 2 shows a row with a
  chevron where the mockup had a second amount field.
- **Skip appears only on the last step.** The ticket says the two mandatory steps
  carry no Skip at all. Because the three-screen layout puts optional obligations
  on the mandatory expense step, an explicit Skip there could not do what it said
  — with the expense blank it cleared the obligations and then refused to move,
  which is a control that does nothing. Obligations are skipped by leaving the
  list empty, and the last step, which is optional in full, keeps its Skip.
- **The notice waits for a touch.** The blocking reason is unchanged and still
  drives the disabled button, but a step no longer opens with a red "Enter your
  monthly income to continue." before anything has been typed.
- **Currency from capabilities, with a fallback.** The ticket names capabilities;
  the setup response also carries a currency. Capabilities wins, the response
  stands in when that call fails, and nothing reads the device.
- **List rows show totals, not counts**, which needed `Money.add`. Decimal-string
  addition, never `Double`.
- **Android holds the wizard in a `ViewModel`.** A remembered object died with the
  composition, so a rotation lost the figure being typed. Process death still
  reloads from the server — that is the resume behaviour the ticket specifies.
- **iOS moves the path per step; Android tracks the finger.** Android's pager
  exposes a drag offset, so the illustration slides 1:1; SwiftUI's paged `TabView`
  exposes none, so iOS animates between thirds on settle. The continuity is
  visible on both; only Android's follows mid-drag.
- **An unanswered figure is `null`, not zero**, and half-finished rows are dropped
  rather than refused — a skipped question and an unasked one stay the same fact (#29).
- **`"1.234"` is refused.** Three decimals and a thousands separator cannot be told
  apart, and guessing is out by a factor of a thousand.
- **The illustration is raster, not SVG.** It is painted, with soft gradients; a
  tracer produces a larger file with visible banding. WebP on Android and HEIC on
  iOS, 109 KB in total against a 972 KB source.
- **Stacked on `welcome-redesign`** (PR #25, now merged); this PR has been
  retargeted to `main`. Retargeting alone does not start CI: `ci.yml` declares no
  `types:`, so `pull_request` defaults to opened/synchronize/reopened, and a base
  change fires `edited`. Closing and reopening the PR is what fired the run.

## Open questions / follow-ups

- **Nothing has run on a device.** Layout against the mockup, dark mode, the
  path's alignment between steps and keyboard behaviour are all unverified.
- **`ApiException.InvalidAmount` carries the field the server refused, and no
  screen reads it yet.** Highlighting the offending row is the follow-up that
  makes the typed error worth having.
- `iosApp.xcodeproj/project.pbxproj` is modified in the working tree and is **not
  part of this branch's commits**: Xcode rewrote `DEVELOPMENT_TEAM = "${TEAM_ID}"`
  to a literal team id, which must not be committed.
- `KtorFinancialSetupRepositoryTest` has three pre-existing "unnecessary `!!`"
  warnings, untouched here.
