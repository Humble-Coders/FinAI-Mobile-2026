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

## Files changed

**Shared — money**
- `util/Money.kt` — normalise, validate, compare and format decimal strings; the
  currency's symbol and decimal places. `"1.234"` is refused rather than guessed.
- `util/MoneyTest.kt` — blanks, separators, too many decimals, huge values, zero.

**Shared — the wizard**
- `model/FinancialSetup.kt` — the wire shapes of `GET/PUT /financial-setup`.
- `repository/FinancialSetupRepository.kt`, `data/KtorFinancialSetupRepository.kt` —
  the two calls; the request omits `currency`, which the server decides.
- `usecase/SetupWizard.kt` — the steps, one blocking-reason function, where to
  resume, and the payload every save carries.
- `model/ApiException.kt`, `data/ApiErrorMapper.kt` — `invalid_amount` becomes a
  typed error naming the field, so a row can be highlighted.
- `i18n/` — every label the wizard shows; the placeholder's strings removed.
- Tests: `SetupWizardTest`, `FinancialSetupDecodingTest`,
  `KtorFinancialSetupRepositoryTest` (mock engine), `ApiErrorMapperTest`.

**Android**
- `ui/setup/SetupUiState.kt`, `SetupViewModel.kt`, `SetupScreen.kt` — state, the
  repository's lifecycle, the three steps and the itemised-list editor.
- `navigation/AppNavigation.kt` — `financial_setup` now routes to the wizard.
- `ui/components/FinAiComponents.kt` — `GradientButton` moved here from the
  welcome screen, so both use one control.
- `res/drawable/ic_back.xml`, `ic_chevron_right.xml`, `drawable-*/setup_path.webp`.
- `ui/onboarding/StatusScreens.kt` — the placeholder screen removed.

**iOS**
- `viewmodel/SetupViewModel.swift`, `ui/SetupView.swift` — the same, in SwiftUI.
- `navigation/RootView.swift` — routes `financialSetup` to the wizard.
- `Assets.xcassets/SetupPath.imageset` — the illustration as HEIC.
- `ui/StatusViews.swift` — the placeholder view removed.

**Design**
- `design/illustration/setup-path-source.png` — the PM's full-resolution artwork,
  kept for re-exporting.

## How to test

1. `./gradlew :androidApp:assembleDebug :sharedLogic:allTests` — builds, and 127
   shared test cases pass on the JVM plus the same set on the iOS simulator target.
2. `cd iosApp && LANG=en_US.UTF-8 pod install && xcodebuild -workspace iosApp.xcworkspace -scheme iosApp -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build`
3. On a device, signed in and past the phone and consent steps:
   - Step 1 refuses to continue until income is an amount; type `1,200` and it is
     sent as `1200.00`.
   - Step 2 wants an expense figure; open Obligations, add a row, save it.
   - Step 3 offers Skip; take it and the app reaches home.
   - Kill the app on step 2 and reopen: it resumes at step 2 with step 1's figure.
   - Turn off the network on a Continue: the error shows and the typed figure stays.

## Acceptance criteria

- [x] `util/Money` with `commonTest` coverage; no `Double` anywhere
- [x] Models and repository with `@Throws(ApiException, CancellationException)`
- [x] One blocking-reason function per step, read by the button, the notice and the save
- [x] Screens for the steps; the mandatory ones carry no Skip, the optional ones do
- [x] Save after every step, sending the whole wizard; a failed save keeps the input
- [x] Routing: the wizard is the `financial_setup` destination and clears when saved
- [x] All labels in `sharedLogic/i18n`; currency and formatting from the server
- [x] Tests: Money edge cases, blocking reasons, repository decoding and error mapping
- [ ] **Matches the design in light and dark, verified on devices — not done**
- [x] Android and iOS builds pass

## Deviations / decisions

- **Three screens, five data areas.** The design shows three steps; the ticket
  defines five. Obligations, debts and investments each became a row that opens an
  itemised list (manager decision, 2026-09-16), so step 2 shows a row with a
  chevron where the mockup had a second amount field.
- **Skip is explicit** on the optional parts, per the manager, rather than treating
  a blank field as the skip. On step 2 it drops the obligations; on step 3 it
  finishes with both lists empty.
- **An unanswered figure is `null`, not zero**, and half-finished rows are dropped
  rather than refused — a skipped question and an unasked one stay the same fact (#29).
- **`"1.234"` is refused.** Three decimals and a thousands separator cannot be told
  apart, and guessing is out by a factor of a thousand.
- **The illustration is raster, not SVG.** It is painted, with soft gradients; a
  tracer produces a larger file with visible banding. WebP on Android and HEIC on
  iOS, 109 KB in total against a 972 KB source.
- **Stacked on `welcome-redesign`** (PR #25), whose components it reuses. This PR
  targets that branch and will retarget to `main` when #25 merges.

## Open questions / follow-ups

- **Nothing has run on a device.** Layout against the mockup, the path's alignment
  between steps and keyboard behaviour on the amount fields are all unverified.
- The list rows show how many items were added rather than their total; totalling
  needs decimal addition in `Money`, which nothing else wants yet.
- The locale for formatting is the default `en`; wiring it to the capabilities
  payload's `locale` is a small follow-up.
