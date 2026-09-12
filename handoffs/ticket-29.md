# Handoff — ticket #29 (mobile half)

**Ticket:** [Humble-Coders/Finance-backend#29 — \[M2\] Make the core financial setup mandatory](https://github.com/Humble-Coders/Finance-backend/issues/29)
**Branch:** `ticket-29-mandatory-financial-setup` · **Base:** `main` (`ea107c5`) · **Implementation:** `71d4198`, `b18a0c7` · **PR:** #20 · 6 files (3 implementation, 3 documentation)

## Summary

`OnboardingStep` now knows every step the API can send. `FINANCIAL_SETUP` is the
new one from 2.5; `REGION` and `CONSENT` have been sent since #24 and were still
decoding to `UNKNOWN` — safe, because an unknown step counts as incomplete and
nobody slipped through the gate, but it left the app unable to route to two of
the four steps.

The routing and the screens are deliberately **not** here: this repo has no
navigation layer and no wizard to attach them to.

## Files changed

| File | Why |
|---|---|
| `sharedLogic/.../model/Capabilities.kt` | The three missing enum cases, declared in the order the server returns them, each documented with the condition that raises it. |
| `sharedLogic/.../model/CapabilitiesDecodingTest.kt` | Decodes the full four-step list; the existing unknown-step test still pins that an unrecognised value stays `UNKNOWN` and incomplete. |
| `sharedLogic/.../model/MeDecodingTest.kt` | `/me` reporting `financial_setup` alone. |
| `docs/PRD.md` | §9 decision log: the wizard records no status and has no skip endpoint. |
| `docs/tickets/M2.4-financial-setup-wizard-ui.md` | Five steps not four, no `status` field, no skip call, and routing straight to home once the gate clears. |
| `handoffs/ticket-29.md` | This report. |

## How to test

```bash
./gradlew :androidApp:assembleDebug :sharedLogic:allTests
```

Then, for the done-rule's iOS half:

```bash
./gradlew :sharedLogic:podInstall && cd iosApp && xcodebuild -workspace iosApp.xcworkspace -scheme iosApp -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

Both were run on this branch: Gradle **BUILD SUCCESSFUL** (including
`iosSimulatorArm64Test`), Xcode **BUILD SUCCEEDED**.

## Acceptance criteria

| Criterion | Status |
|---|---|
| `OnboardingStep.FINANCIAL_SETUP` exists and decodes | **Met** |
| An old build meeting an unknown step neither crashes nor silently passes the gate | **Met** — `keeps an onboarding step this build does not know` |
| Android compiles and tests pass; iOS `xcodebuild` succeeds | **Met** — both run locally; CI is the check on the PR |
| The wizard is the destination for the step, and cannot be escaped | **Deferred to 2.4 (#17)** — no router or wizard exists yet |
| The mandatory screens offer no Skip; the optional ones do | **Deferred to 2.4 (#17)** — already in that ticket's scope and acceptance criteria |
| Strings for the new field and changed copy | **Deferred to 2.4 (#17)** — see below |

## Deviations / decisions

- **Scope reduced to the shared contract**, on the manager's decision during
  planning. The mobile app today is still the ticket-6 demo screen: no
  `navigation/` package, no auth or wizard screens on either platform. Routing
  and screens land in 2.3 (#16) and 2.4 (#17).
- **No i18n keys added.** The ticket asked for strings for the new field and the
  changed copy, but there is no screen to label and 2.4 holds the design. Keys
  invented here would be guesses for 2.4 to redo, and `OnboardingStep` itself
  carries no `messageKey` (unlike `FeatureReason`), so nothing in this change
  needs one.
- **`REGION` and `CONSENT` added beyond the ticket's letter.** The ticket asked
  only for `FINANCIAL_SETUP`; adding one case while leaving two others decoding
  to `UNKNOWN` would have left the enum trailing the contract for no reason.

## Open questions / follow-ups

- **#17 now says route straight to home** once `financial_setup` clears, rather
  than re-offering the wizard when optional setup is unanswered. Nothing records
  whether an optional step was declined, so the old rule would have shown the
  wizard on every launch to anyone who skipped.
- **The wizard's shared model, repository and `Money` utility are still 2.4's**
  — this change touches none of them.
- **Not verified against a live API.** Render is suspended, so the new step has
  only been exercised against decoded JSON, not a real response.
