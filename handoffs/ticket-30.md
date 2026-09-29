# Ticket #30 — [M3] Add a transaction by hand

## Summary

A person can now type in a single transaction on Android and on iOS. The screen opens from the home screen, and #31 can open it from the "nothing could be read" state. The account, date, amount and direction all start **unanswered**. The date has a one-tap **Today** beside it, and the category is optional ("Choose for me" leaves it to the backend). Save stays disabled until one shared rule (`ManualEntry.blockingReason`) passes, and the notice under Save gives that same reason. If the server says the entry matches an existing one, the screen shows what it matched and offers **Yes, keep both** (the same entry sent again with `allow_duplicate`) or **No, cancel**. Accounts can be added without leaving the screen, and an account's kind also starts unanswered. What was typed survives rotation and the OS killing the app: `SavedStateHandle` on Android, scene storage on iOS. Both are tied to the user who typed it.

**Not end to end yet:** `POST /transactions` is backend #38, which is still open. Accounts and categories already work against the live API. Saving will show an error until #38 is deployed. The client is built to #38's amended contract and tested against fakes of it.

## Files changed

### Shared: the rules, written once
| File | Why |
|---|---|
| `usecase/ManualEntry.kt` | `ManualEntryDraft` (every money-critical field nullable); `ManualEntryBlock`; `blockingReason`, `notice` (unanswered fields wait until the form is touched), `request` (null while blocked, so the submit path cannot send what the button refused), `duplicateMessage`, `today()`. |
| `usecase/NewAccountForm.kt` | `NewAccountDraft(name, kind: AccountKind? = null)` and its blocking reasons. `MAX_NAME_LENGTH = 255` matches `AccountIn.name`. |
| `model/Account.kt`, `model/Category.kt`, `model/ManualTransaction.kt` | Wire models. `AccountKind` / `TransactionDirection` use the project's `WireEnumSerializer` with an `UNKNOWN` fallback. `NewTransaction` / `NewAccount` force their required fields onto the wire with `@EncodeDefault(ALWAYS)` (see Deviations). `AccountKind.labelKey` gives both apps the same labels. |
| `repository/{Accounts,Categories,Transactions}Repository.kt`, `data/Ktor*Repository.kt` | `GET`/`POST /accounts`, `GET /categories`, `POST /transactions`; `@Throws` on each suspend fun; `close()`. |
| `model/ApiException.kt`, `data/ApiErrorMapper.kt` | `duplicate_transaction` → `DuplicateTransaction(match)`, decoding `duplicate_of` or null. `duplicate_account_name` → `DuplicateAccountName`. |
| `util/Dates.kt` | `display` ("Sep 12, 2026", with the part order set by the `date_display` string) and `parse` (ISO, or null). |
| `i18n/Strings.kt`, `i18n/EnglishStrings.kt` | Every string on both screens, including month abbreviations and the account-form reasons. |
| `build.gradle.kts` | `kotlinx-datetime` changes from `implementation` to `api`, because `ManualEntryDraft.occurredOn` is a `LocalDate` that the apps hold. |

### Android
| File | Why |
|---|---|
| `ui/manualentry/ManualEntryUiState.kt` | The gates (`block`, `notice`, `canSave`, `canCreateAccount`) are computed from shared, so a plain constructor can test them. |
| `ui/manualentry/ManualEntryViewModel.kt` | `bind(userId)` builds fresh repositories. Every edit writes the draft to `SavedStateHandle` with an owner id, and a restore for a different user is dropped. A restored account or category that no longer exists becomes unanswered again. There is a generation guard against late replies, and a guard against sending twice while a save is in flight. After a save the account stays chosen and every other field resets. `discard()` is for leaving on purpose. It works even mid-request: a save or account-create still running when the person leaves cannot write into the next visit's clean form (an account created that way is listed, not chosen). |
| `ui/manualentry/ManualEntryScreen.kt` | Account sheet (with "Add an account"), new-account sheet (kind as `FilterChip`s, none selected), `DatePickerDialog` that refuses days after today, Today `FilterChip`, direction as a `SingleChoiceSegmentedButtonRow` with nothing selected, category sheet, duplicate `AlertDialog`, and a retry screen when accounts cannot load. Selected controls use the theme's inverse surface rather than green, which marks Save as the screen's one primary action. (The focused-field border and cursor stay green, as in the setup wizard.) The new-account sheet cannot be swiped or backed away while its account is being created. The retry button is shown only when a retry can help. |
| `ui/components/FormFields.kt` | `AmountField`, `WizardField`, `FieldLabel` and `fieldFrame` moved here unchanged from `SetupScreen.kt`. `WizardField` gains optional `placeholder` / `isError` / `onDone`, whose defaults keep the wizard as it was. New `PickerField`. |
| `ui/setup/SetupScreen.kt` | Imports the moved fields; no behaviour change. |
| `navigation/AppNavigation.kt`, `ui/home/HomeScreen.kt` | Home shows "Add a transaction". Sign out uses the text colour, so the new button is the one green control. Whether the screen is open is `rememberSaveable`. `ManualEntryRoute(userId, fromUnreadable, onClose)` is `internal`, so #31 can call it. |

### iOS
| File | Why |
|---|---|
| `viewmodel/ManualEntryViewModel.swift` | Mirrors Android, reading the same shared rules. `snapshot` / `bind(userId:restoring:)` carry the draft through scene storage, owner-checked. |
| `ui/ManualEntryView.swift` | `List` sheets for account and category, a `Form` sheet for a new account, a graphical `DatePicker` limited to `...today`, a segmented `Picker` with an optional selection (nothing chosen), the duplicate `.alert`, and a keyboard toolbar with **Done** for the number pad. `@SceneStorage("manual_entry.draft")`. "Add an account" opens its sheet from the account list's `onDismiss`, because SwiftUI drops a sheet presented while another is still closing. |
| `ui/components/FormFields.swift` | The setup wizard's fields moved here (they were `private`). `WizardField` gains `placeholder`/`isError`/`submitLabel`/`onSubmit`, whose defaults keep the wizard as it was. New `PickerField`. |
| `ui/SetupView.swift` | Loses the moved fields; no behaviour change. |
| `navigation/RootView.swift`, `ui/StatusViews.swift` | Home opens the screen. `@SceneStorage("manual_entry.open")` records whether it is open. The coin loader covers the first load. |

### Tests
| File | What |
|---|---|
| `commonTest/usecase/ManualEntryTest.kt` | 19 tests: every block reason in form order, the notice timing, `request` null while blocked, amount normalised with no `Double`, and the duplicate sentence and its fallback. |
| `commonTest/usecase/NewAccountFormTest.kt` | 9 tests: kind starts unanswered, `UNKNOWN` is not an answer, trimmed name, the 255 limit, and every choosable kind has a label. |
| `commonTest/util/DatesTest.kt` | 4 tests: display, month table edges, and parsing, including the impossible date `2026-02-30`. |
| `commonTest/data/KtorManualEntryRepositoriesTest.kt` | 13 tests: request paths and bodies, including `everyRequiredFieldIsSentEvenAtItsDefault`, plus 409 decoding with and without `duplicate_of`. |
| `androidApp/src/test/.../ManualEntryViewModelTest.kt` | 25 tests: opening unanswered, load failure and retry, categories optional, blocked Save sends nothing, the exact request sent, state after a save, a failed save keeps the text, one send per double press, duplicate keep and cancel, process-death restore (draft and new-account sheet), no restore for another user, a vanished account becoming unanswered, discard, a late reply after a rebind, inline account create including a name already taken, leaving mid-save and mid-create, and no retry offered when no user is bound. |

## How to test

Automated (none of these boots a simulator or emulator):

```bash
./gradlew :sharedLogic:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug
```

```bash
cd iosApp && xcodebuild -workspace iosApp.xcworkspace -scheme iosApp -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

Results on this branch: shared **231 tests, 0 failures**; Android unit **58 tests, 0 failures** (25 new); `assembleDebug` succeeded; iOS **BUILD SUCCEEDED**. `:sharedLogic:iosSimulatorArm64Test` was **not** run locally, because it boots a simulator. CI runs it.

Mutation checks on the Android model. Each change was reverted afterwards, and each was caught by exactly the test named for it:
- Skip writing an edit to saved state → `a half-typed entry comes back after the process is killed`.
- Restore regardless of owner → `a restored entry is never handed to someone else`, plus the late-reply test.
- Drop the in-flight guard → `Save pressed twice while the first is on its way sends once`.
- Never pass `allowDuplicate` → `a duplicate asks and keeping it resends the same entry marked as meant`.
- Don't bump the entry counter on discard → `leaving while a save is on its way keeps the next visit clean` and `an account created after leaving is listed but not chosen`.

Earlier, the shared client tests showed that without `@EncodeDefault` three request tests fail.

By hand, on a device (not run by the developer, per the no-simulator rule):
1. Sign in and reach Home. Tap **Add a transaction**.
2. The form opens empty. It shows the Account and Date placeholders, neither Money out nor Money in is selected, Save is disabled, and there is **no** red notice yet.
3. Type an amount. The notice now reads "Choose the account this came out of or went into." and the account box turns red.
4. Tap Account, then **Add an account**. On iOS especially, check that the new-account sheet actually appears once the list has closed. Type a name. **Add account** stays disabled until a type is chosen, because none is preselected. Add it: the sheet closes and the new account is chosen. On Android, try swiping the sheet down straight after tapping Add account: it must stay up until the account is created. Try the same name again, and it says "You already have an account with that name."
5. Tap Date. Days after today cannot be picked. Tap **Today**, and the date fills in.
6. Amount: a decimal pad appears. On iOS a **Done** bar sits above it. The field stays visible above the keyboard on a small phone.
7. Rotate part-way through. Everything typed stays.
8. Process death:
   - Android: turn on Developer options → *Don't keep activities*, background the app and return. Or background it and run `adb shell am kill com.humblesolutions.finai`, then reopen. The screen and the draft come back.
   - iOS: background the app, stop it from Xcode, and relaunch. Swiping it away in the app switcher clears scene storage by design. The screen and the draft come back.
9. Save: **needs backend #38 deployed.** Until then, expect an error message and the typed values kept. Once #38 is live:
   - You see "Transaction saved". The account stays chosen and everything else clears.
   - Enter the same transaction again, and the alert names the one it matched.
   - **Yes, keep both** saves it. **No, cancel** saves nothing and keeps the form.
10. Back from the screen, then reopen it. It starts clean.
11. Repeat in dark mode and at the largest text size.

## Acceptance criteria

| Criterion | Status |
|---|---|
| A transaction typed in saves and appears with `source = manual`; the amount round-trips as a decimal string, with no `Double` | **Client met, end to end blocked.** The amount is a string from the field to the wire (`Money.normalize`). The test asserts `"1,200.5"` is sent as `"1200.50"`. Saving and `source = manual` depend on backend #38, which is not built yet. |
| Save disabled until valid, reason shown inline, covered by tests | **Met.** `ManualEntry.blockingReason` drives the button, the notice and `request`, with shared and VM tests. |
| Amount uses the number pad with a Done accessory and stays visible above the keyboard | **Met in code, not seen on a device.** Android uses `KeyboardType.Decimal` and `imePadding()`. iOS uses `.decimalPad`, a keyboard toolbar with **Done**, and a scroll view. Check with step 6. |
| A future date is refused before the request is made | **Met.** The shared rule, the pickers refusing future days, and tests. |
| A duplicate returns 409 and the screen shows what it matched, with both ways out | **Client met.** Decoding, message, keep (resend with `allow_duplicate`) and cancel are tested against the #38 contract. The live 409 needs #38. |
| ~~Reachable from 3.6's unreadable state~~ (moved to #31) | Entry point exposed: `ManualEntryRoute(userId, fromUnreadable = true, onClose)` on Android and `ManualEntryView(model:userId:fromUnreadable:onClose:)` on iOS. |
| Rotation and process death do not lose what was typed | **Android met, with tests. iOS met in code** (scene storage). There is no iOS unit-test target, so check with steps 7–8. |
| Android compiles and tests pass, `testAndroidHostTest` passes, iOS `xcodebuild` succeeds | **Met.** 231 / 58 / BUILD SUCCEEDED. |

## Deviations / decisions

- **Required fields are forced onto the wire.** `FinAiJson` omits values equal to their defaults. Without `@EncodeDefault(ALWAYS)`, every "money out" entry (`direction = DEBIT`) and every chequing account would have been sent without the field and refused.
- **After a save the account stays chosen, and everything else goes back to unanswered.** This suits typing in several lines from one statement. The date is cleared on purpose.
- **Leaving the screen with Back discards the draft.** Rotation and process death keep it. A draft that came back after a deliberate exit would be stale.
- **Categories failing to load does not block the screen.** The category is optional and the backend chooses one. Accounts failing to load shows a retry screen, because there is nothing to file into.
- **The iOS date picker always shows a highlighted day** (a graphical `DatePicker` cannot be empty), but nothing is taken until **Done**. The Android picker opens with no day selected when no date has been chosen yet, and on the chosen date otherwise.
- **Description length counts UTF-16 units** against the server's 512 code points. That is only ever stricter (an emoji counts twice) and never lets through something the server would refuse.
- **`NewAccountForm.NAME_MAX` → `MAX_NAME_LENGTH`.** `NAME_MAX` is a C macro, and the generated iOS header would not compile. Only the iOS build showed this.
- **Selected chips and segments use the inverse surface**, not green, because the brand keeps one primary green control per screen. For the same reason, Sign out on the home screen now uses the text colour on both platforms.
- The setup wizard's field components moved into `ui/components/` on both platforms so they can be reused. Their defaults keep the wizard unchanged.

## Open questions / follow-ups

- **Backend #38 must ship** (`POST /transactions`, the 409 body with `duplicate_of`, and `allow_duplicate`) before this ticket can be seen working end to end. #38 now states that the match goes under `detail` (`{"detail": {"code": "duplicate_transaction", "duplicate_of": {...}}}`), which is what the phone reads, and asks for a test pinning that shape.
- The manager still has to decide whether merging a duplicate (the unbuilt `may_merge_onto`) gets its own ticket.
- iOS has no unit-test target. Its restore path is covered by the shared rules plus the manual steps. A test target would let the iOS model be tested like Android's.
- Dates display in English order from the shared `date_display` string. A French template comes with the French strings.
