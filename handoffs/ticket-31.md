# Handoff — ticket #31

**Ticket:** [#31](https://github.com/Humble-Coders/FinAI-Mobile-2026/issues/31) — [M3] Import a statement — the screen

## Summary

A person can now import a statement on Android and iOS:
1. Choose the account (required, never defaulted, with a new one addable in place).
2. Pick a PDF or image, a photo, or take a photo.
3. Consent to AI processing the first time.
4. Watch it read, with "Reading page 3 of 12…" under the app's one coin loader.
5. See what came out: how many were found, already recorded, needing review, or unreadable.

The rows are saved to the chosen account straight after parsing (manager decision). Every way it can end has its own words and offers the next useful thing:
- **Retry** where trying again can help, re-sending the already-read text rather than reading again;
- **Type transactions in instead** wherever the statement can't be imported, which opens #30 with its from-unreadable note;
- after an import the server read and failed on, an **unticked** offer to send its redacted text for diagnostics.

No extraction logic was added. The 3.2 readers and `ImportStatement` do the reading and redacting. The decisions live once, in shared `StatementImportFlow`.

## Files changed

### Shared
| File | Why |
|---|---|
| `model/ApiException.kt`, `data/ApiErrorMapper.kt` | Each import refusal is its own error: `ConsentRequired` (409), `AiPolicyChanged` (409), `ImportQuotaExceeded` (429, carrying `limit` and `resets_at`), `TooManyTransactions` (the second 413, distinct from `statement_too_long`), `ParseFailed` (502), `ImportUnavailable` (503 `ai_processing_unavailable`). Unknown bodies still fall back safely. |
| `repository/AiConsentRepository.kt`, `data/KtorAiConsentRepository.kt`, `model/AiPolicy.kt` | Read the AI policy, consent to the version shown, read the status (#42). Reusable by the future Settings row. |
| `repository/StatementImportRepository.kt`, `data/KtorStatementImportRepository.kt`, `model/ParsedStatement.kt` | `save(importId, rows)` calls `POST /statements/{id}/transactions`. `StatementUpload.keepTextForDiagnostics` stays off the wire unless true. `ParsedStatement.textRetainedUntil`. |
| `usecase/ImportStatement.kt` | Passes the diagnostics answer through. |
| `usecase/StatementImportFlow.kt` | `ImportStep`, and `ImportFailure` (each value with its message, whether a retry is offered, whether manual entry is offered, whether diagnostics are offered). Also `problemFor`, `needsConsent`, `problemAfterParse` (zero rows is a failure), `rowsToSave`, `message` (the quota message includes the reset date), `summary` (lines that aren't zero, singular or plural), `readingProgress`, `diagnosticsThanks`. |
| `i18n/*` | Every line on both screens. |

### Android
| File | Why |
|---|---|
| `ui/statementimport/StatementImportViewModel.kt`, `…UiState.kt` | The flow. Account, file and "a read was under way" go in `SavedStateHandle`, owner-checked. The document and password are held in memory only. A retry re-sends, or re-saves, rather than re-reading. `discard()` is for leaving. |
| `ui/statementimport/StatementImportScreen.kt`, `…Route.kt` | The steps. Pickers are `OpenDocument` (PDF and image), `PickVisualMedia`, and `TakePicture` through a `FileProvider`. Access to picked files is kept with `takePersistableUriPermission` where the provider allows. A camera photo is deleted when the import finishes or is abandoned, never just on a rotation. |
| `ui/components/AppLoader.kt` | `LoaderSignal(…, caption)` draws a caption under the coin, as a polite live region. |
| `ui/components/AccountSheets.kt` | The account list and new-account sheet, moved from manual entry and shared by both screens. |
| `AndroidManifest.xml`, `res/xml/statement_captures.xml` | A non-exported `FileProvider`, limited to `cache/statement-captures/`. There is no camera permission, because the system camera takes the photo. |
| `navigation/AppNavigation.kt`, `ui/home/HomeScreen.kt` | The home route is a saveable enum. "Import a statement" is primary; "Add a transaction" is secondary. |

### iOS
| File | Why |
|---|---|
| `viewmodel/StatementImportViewModel.swift` | The same flow and rules. A picked file is copied into the app's temp folder (`ImportFiles`), so it can be read again after a restore; the copy is deleted when the import finishes or is abandoned. Scene-storage snapshot is owner-checked. |
| `ui/StatementImportView.swift` | `.fileImporter`, `PhotosPicker` (converted to JPEG), the camera where one exists, `SecureField`, and a checkbox style that nothing pre-ticks. |
| `ui/components/CameraPicker.swift` | A UIKit camera wrapper, since SwiftUI has none. |
| `ui/components/AppLoader.swift` | `LoaderHost(caption:)`, announced to VoiceOver as it changes. |
| `ui/components/AccountSheets.swift` | `AccountListSheet`, `ChoiceRow`, and `NewAccountSheet` behind a `NewAccountHost` protocol, shared with manual entry. |
| `navigation/RootView.swift`, `ui/StatusViews.swift`, `ui/ManualEntryView.swift`, `Info.plist` | Home route as a scene-stored enum, home buttons, the shared sheets, and `NSCameraUsageDescription`. |

### Tests
- Shared (**266**, 26 new):
  - `ApiErrorMapperTest`: every new code, and the fallbacks.
  - `KtorAiConsentRepositoryTest`: paths, version sent, the mismatch error.
  - `KtorStatementImportRepositoryTest`: the diagnostics flag stays off the wire unless true; the save path and body.
  - `StatementImportFlowTest`: 12 tests.
  - `ImportStatementTest`: the flag passes through.
- Android (**78**, 16 new): `StatementImportViewModelTest`.

## How to test

```bash
./gradlew :sharedLogic:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug
```

This gives shared **266** and Android **78**, 0 failures.

```bash
cd iosApp && xcodebuild -workspace iosApp.xcworkspace -scheme iosApp -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

This gives BUILD SUCCEEDED. No simulator was run. `:sharedLogic:iosSimulatorArm64Test` is left to CI.

**Break-it checks.** Each change was reverted afterwards, and each was caught by exactly the test for it:

| Change | What caught it |
|---|---|
| The two 413s merged | `the two 413s are two different errors` |
| Diagnostics offered after a quota refusal | `diagnosticsAreOfferedOnlyWhereTheServerReadAndFailed` |
| Diagnostics sent without the tick | `after a failed read the offer appears unticked and declining sends nothing` |
| Consent box pre-ticked | `consent is asked before the first import and never ticked for the person` |
| Retry reads again | `a model failure is retried by sending again not by reading again` |
| A killed read not resumed | `the app killed mid-read reads the same file again for the same account` |

**By hand, on a device, with synthetic statements only.** **Production refuses every import right now** (503 until `LLM_NO_TRAINING_TIER` is on), so point a debug build at a **local backend**: set `api.baseUrl` in `local.properties` on Android, and the equivalent on iOS. You can also test against production to see the "not available yet" state.
1. Home → **Import a statement**. **Continue** is disabled until an account is chosen. Add one from the sheet; it's selected.
2. **Choose a PDF or image**, with a synthetic PDF. The coin shows with "Reading page 1 of N…", then "Reading the transactions…", then "Saving…".
3. First time on this account: the consent screen shows the server's text with the box unticked. **Agree and continue** is disabled until it's ticked. Tick, agree, and the import carries on. Import again: consent isn't asked.
4. The result: "N transactions found", plus a needs-review line if any.
5. A password-protected synthetic PDF prompts. A wrong password says so; the right one reads.
6. Rotate during the read: it continues. On Android, turn on *Don't keep activities*, background mid-read and return: the same file is read again.
7. States. Each shows its own message and offers:
   - a text file renamed `.pdf` → unreadable;
   - an image with no text → nothing readable, with **Type transactions in instead** (opens manual entry with the "we couldn't read that statement" note);
   - an import after the month's quota → "You can import again on …";
   - airplane mode → network, with **Try again**;
   - production → "isn't available yet".
8. A statement the backend returns no rows for: the failure shows the **unticked** "Help us read statements like this one?" box. **Send** is disabled until it's ticked. Ticked and sent, it thanks you with the date the text is kept until.
9. Light and dark, the largest text size, VoiceOver and TalkBack: the progress caption and the result are spoken.

## Acceptance criteria

| Criterion | Status |
|---|---|
| End to end on both platforms: synthetic PDF → progress → rows; the file never leaves the device; the only request is the parse call, with no digit run longer than 4 | **Met in tests; the device run is pending.** The view-model test asserts the upload text has no `\d{5,}` and doesn't contain the file's URI; 3.2's `ImportStatementTest` asserts the redaction. Precisely, **two** requests carry statement content: the parse (redacted text) and then the save (the parsed rows back, to file them). Neither carries the file. |
| The account step can't be skipped; a new account is selectable without leaving the flow | **Met.** Android tests `the account step cannot be skipped` and `a new account is chosen without leaving the flow`. iOS uses the same gate (`canContinueFromAccount`). |
| Consent asked once, before the first import, never again after it's recorded | **Met.** `consent is asked before the first import…` and `once consent is recorded it is not asked again`. |
| 403, 409, 429, both 413s, 502 and network each render their own message, proven over the UI state | **Met.** `each server refusal renders its own message` (Android) and `everyServerRefusalTheTicketNamesHasItsOwnMessage` (shared). A 409 isn't a message: it's the consent step, which is tested. The production-only 503 is covered too. |
| Rotation or backgrounding mid-read doesn't lose the picked file or restart the read | **Met for rotation and backgrounding.** The read runs in the view model, which outlives both. If the OS **kills** the app mid-read, the file and account are kept but the read starts again (tested). The document is never written to disk, by design. |
| A password-protected PDF prompts, accepts the password, and it never leaves the device | **Met.** `a locked PDF prompts and the password is used on the device only`: never in saved state, never in the upload. |
| The send-the-text offer appears only on a failed or heavily-flagged import, unticked, and declining sends nothing | **Partly, by decision.** Unticked, declining sends nothing, and offered only once are all tested. It appears after a **failed** import only, not a mostly-flagged one that saved rows (manager decision, 2026-09-30): the backend keeps text only from the parse request, a re-parse is needed, and the monthly quota would refuse one after a successful import. |
| From nothing-could-be-read, manual entry is one tap away, exercised by a test | **Met in the rules; the tap itself is by inspection.** `when nothing can be read manual entry is offered…` asserts the offer. The button calls `onTypeInstead`, which routes to manual entry with `fromUnreadable = true`. The app has no UI-test harness to press it. |
| Nothing logs page text, a row or the redacted payload | **Met.** The HTTP client logs headers only (`LogLevel.HEADERS`, with Authorization sanitized), and debug builds only. There's no other logging in these screens. |
| Android compiles and tests pass, `testAndroidHostTest` passes, iOS `xcodebuild` succeeds | **Met.** 266 / 78 / BUILD SUCCEEDED. |

## Deviations / decisions

These are the manager's decisions of 2026-09-30:
- **Diagnostics are offered after failed imports only** (see above).
- **Rows are saved straight after parsing.** The needs-review count only exists after saving, and corrections belong to review (#32).
- **The production 503** (`ai_processing_unavailable`) has its own message and offers manual entry. The ticket didn't list it, but it's what production answers today.
- **One PR, in staged commits:** shared, then Android, then iOS.

Also:
- **The result is a summary, not a row list.** #32 (review) isn't built. The screen says the waiting rows are kept for when review arrives.
- **The loader gained a caption on both platforms.** That's how "use AppLoader, don't invent a second spinner" and "show what's happening" were both honoured.
- **The account sheets moved into shared components** on both platforms. Manual entry behaves the same.
- **Home changed.** "Import a statement" became the primary action and "Add a transaction" secondary, keeping one green control per screen.
- **`NSCameraUsageDescription` is the one string not from the shared i18n**, because iOS reads it from `Info.plist` before any code runs.
- **iOS copies a picked file into the app's temp folder.** Security-scoped access ends with the picker, so the file couldn't otherwise be read after a restore. It never leaves the device, and it's deleted when the import ends or is abandoned.

## Open questions / follow-ups

- **Production can't import until `LLM_NO_TRAINING_TIER` is on**, which needs ai-v2 dated first (#42).
- **Diagnostics after a mostly-flagged import** would need a backend endpoint that attaches text to an existing import, with no re-parse and no quota.
- **#32 (review)**: the result should link to it once it exists.
- **The iOS view model has no unit-test target**, as with #30. Its rules come from the tested shared layer, and its plumbing is covered by the device steps.
