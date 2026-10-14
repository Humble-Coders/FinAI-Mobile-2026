# Handoff — ticket #55

**Ticket:** [#55](https://github.com/Humble-Coders/FinAI-Mobile-2026/issues/55) — [M4] Check and fix an import before it's saved

## Summary

**The check step.** A statement that has been read now stops at a new check step (`ImportStep.CHECK`) instead of being saved straight away.
- Every row is shown with the count and the totals.
- Tapping a row opens the existing editor sheet to fix the date, name, amount or in/out, or to **Remove** the row.
- Nothing reaches the person's records until **Save**.
- Edited rows are sent with confidence 100. Rows read with confidence below 70 (the backend's review line) say "Check this one" until they're fixed.

**After saving,** each row in "What we read" opens the same sheet, with the category picker, to fix where it was filed.

**Rows are kept in memory only.** If the app is ended mid-check:
- only a flag survives;
- the statement is read again (free since backend #73);
- the screen says the earlier changes weren't kept.

**Recovery:**
- a reading the server replaced (`409 import_superseded`) is read again rather than failing;
- a failed save returns to the rows with every edit kept.

**The new `own_transfer` review reason** now has words in Review and after a manual entry.

All of this is built on Android and iOS, with the rules in `sharedLogic`.

## Files changed

**Shared (`sharedLogic`)**
- `usecase/ImportCheck.kt` (new): the check step's rules.
  - `CheckRow`; `rowsOf`; `needsCheck` (with `CHECK_BELOW = 70`, which mirrors the backend's `LOW_CONFIDENCE`);
  - `edit`, using `ReviewQueue.blockingReason`; `remove`; `canSave`;
  - `rowsToSave`, where edited rows are sent as `CONFIRMED`;
  - the count and Save labels.
- `usecase/StatementImportFlow.kt`:
  - adds `ImportStep.CHECK`;
  - removes the unused `rowsToSave(accountId, parsed)`, which only ever sent the rows unchanged.
- `model/ApiException.kt`, `data/ApiErrorMapper.kt`: `ImportSuperseded`, mapped from `409 import_superseded`.
- `model/ReviewReason.kt`, `usecase/ReviewQueue.kt`: `OWN_TRANSFER` and its wording.
- `usecase/ManualEntry.kt`: `ManualEntrySaved.LOOKS_LIKE_A_TRANSFER`. A hand-typed payment paired as a transfer says so after saving.
- `i18n/Strings.kt`, `i18n/EnglishStrings.kt`: 22 new strings (the check step, Remove, the read-again notice, `own_transfer`).
- Tests:
  - `ImportCheckTest` (new, 8 tests);
  - `ApiErrorMapperTest` (+1), `ManualEntryTest` (+1);
  - `ReviewQueueTest` (the reasons now include `OWN_TRANSFER`);
  - `StatementImportFlowTest` (the removed helper's test is gone).

**Android**
- `ui/statementimport/StatementImportViewModel.kt`:
  - `send()` stops at `check()`;
  - `saveChecked()`, plus superseded and save-failure handling;
  - `askDiscard`, `keepChecking`, `dismissReadAgain`;
  - editing in both modes (`edit`, `saveEdit`, `deleteEditing`, new category);
  - the `KEY_CHECKING` flag, plus restoring after the app is ended.
- `ui/statementimport/StatementImportUiState.kt`: the check rows, the editing fields, the derived labels, and `editor` (the sheet state for either mode).
- `ui/statementimport/StatementImportScreen.kt`:
  - `CheckStep`, plus `CheckBar`, a Save/Discard bar pinned under the list;
  - the "Check this one" / "Changed by you" note;
  - the discard dialog and the editor sheet;
  - `ImportedRow` is now tappable, with a click label.
- `ui/statementimport/StatementImportRoute.kt`: Back on CHECK asks to discard; wires the new actions and `TransactionEditorActions`.
- `ui/edit/TransactionEditor.kt`: `showsCategory` and the delete wording keys, defaulted so Transactions and Review are unchanged.
- `StatementImportViewModelTest.kt`:
  - existing tests updated, because reading now ends at CHECK;
  - 10 new tests: edits and removal reach the request; refused edits; nothing left to save; only a flag in saved state; ended mid-check; superseded; failed save keeps edits; discard asks first; fixing a saved row; fixing the only waiting row stops asking for Review (added after review).

**iOS**
- `viewmodel/StatementImportViewModel.swift`: the same flow as Android. It conforms to `TransactionEditing`, and the scene-storage `Snapshot` gains an optional `checking` flag, so older snapshots still restore.
- `ui/StatementImportView.swift`: `checkStep`, the bottom `checkBar` (`safeAreaInset`), the discard alert, the `CorrectionSheet`, and tappable rows with a chevron and an accessibility hint.
- `ui/ReviewView.swift`: `TransactionEditing` gains `showsCategory` and the delete wording (`deleteKey`, `deleteConfirmTitleKey`, `deleteConfirmKey`, `deleteConfirmMessage`). Defaults in a protocol extension keep Review and Transactions unchanged.

## How to test

**Automated** (all run and passing locally):
```
./gradlew ktlintCheck
./gradlew :sharedLogic:testAndroidHostTest      # 608 tests, 0 failures
./gradlew :androidApp:testDebugUnitTest         # 293 tests, 0 failures
./gradlew :androidApp:assembleDebug
cd iosApp && LANG=en_US.UTF-8 xcodebuild -workspace iosApp.xcworkspace -scheme iosApp \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' build CODE_SIGNING_ALLOWED=NO   # BUILD SUCCEEDED
```

**By hand, on a device or emulator** (not run by Claude; the developer checks these). Use a **synthetic** statement only, because the dev AI key is a free tier. Ask the manager to add the test household to the unlimited-imports list.

1. Import a statement. After "Reading the transactions…", **Check what we read** appears with the count, money in and out, and rows by day. Nothing appears in Transactions yet.
2. **Edit a row:** tap it, change the amount, Save. The row shows "Changed by you", and the totals update.
3. **Remove a row:** tap it, then **Remove**, and confirm. It disappears, and the Save button's count drops.
4. **A doubtful row** (if the reader returned confidence below 70) shows "Check this one" until it's edited.
5. Press **Save N transactions**. "Statement imported" shows the rows as saved, including the edited amount and without the removed row.
6. **Fix a saved row:** tap it, choose a category, Save. The list re-reads, and Home updates.
7. **Discard:** start another import and press Back or Discard on the check screen. You're asked first; Cancel keeps the rows.
8. **Ended mid-check:**
   - Android: turn on "Don't keep activities", or run `adb shell am kill com.humblesolutions.finai` while on the check screen.
   - iOS: background the app and stop it from Xcode.

   Reopen. The statement is read again, the "We read the statement again…" notice shows, and **no** quota error appears (backend #73 is live).
9. **Review:** a card payment paired by backend #73 shows "Looks like money moved between your accounts".
10. Repeat the screens:
    - in light and dark;
    - at the largest font size;
    - in landscape;
    - on the smallest and largest phones.

    The Save bar stays above the navigation bar and home indicator.

## Acceptance criteria

| Criterion | Status | Evidence |
|---|---|---|
| The person sees every row before anything is saved; no save call before Save | **Met** | `a statement is read, sent, checked, then saved and summed up` asserts `imports.saved` is empty at CHECK. |
| Editing date, name, amount or in/out changes what is saved; removing leaves the row out; edited rows carry confidence 100 | **Met** | `an edit reaches the request as confirmed and a removed row is left out`; `ImportCheckTest.saving_sends_…` |
| Invalid edits refused with the same messages as manual entry | **Met** | `ImportCheck.edit` uses `ReviewQueue.blockingReason`, whose `CorrectionBlock` messages are manual entry's. Tested by `ImportCheckTest.an_edit_the_editor_would_refuse_…` and `an edit the editor refuses changes nothing`. |
| Rows under confidence 70 show "Check this one"; once edited they don't | **Met** | `ImportCheckTest.only_rows_read_with_doubt_…`, `the_line_is_the_servers_review_line`, `an_edit_changes_the_row_…` |
| With every row removed, Save is disabled and Discard is offered; Discard asks first | **Met** | `with every row removed there is nothing to save`, `leaving mid-check asks first` |
| After saving, each row opens the existing editor; a category changed there shows in the list; Home refreshes | **Met** (Android tested; iOS built, same logic) | `a saved row is fixed on the server and the list is read again`. `LedgerChanged.announce()` runs after the fix on both platforms. |
| Ended mid-check: restarts, says changes weren't kept, doesn't hit the limit; no row content in saved state or on disk | **Met** (Android tested; iOS built) | `the app killed mid-check reads the statement again and says so`, `only a flag is kept in saved state while checking, never a row`. The limit side is backend #73, merged and migrated on 2026-10-14. |
| Review shows `own_transfer` in words | **Met** | `ReviewQueue.reasonKey`, `ReviewQueueTest.everyReasonReadsDifferently` |
| Android and iOS match | **Met in code** | Same shared rules and the same flow, step by step. The screens are to be compared by hand (step 10). |
| Every new string is in `i18n`; no literals in platform code | **Met** | 22 keys in `Strings.kt` and `EnglishStrings.kt`. |
| ktlint, Android build and tests, iOS `xcodebuild` pass | **Met** | See How to test. |

## Deviations / decisions

1. **Validation uses `CorrectionDraft` and `ReviewQueue.blockingReason`** (agreed at `/start-ticket`), not `ManualEntry.blockingReason`. The check step's editor *is* the correction sheet, and its rules give manual entry's messages, so the ticket's intent of the same rules whichever way a row is entered holds.
2. **One sheet, not two.** The existing editor gets two options, defaulted: hide the category picker, and use "Remove" wording. That makes it serve unsaved rows too.
3. **A failed save returns to the check screen** with the error under Save and every edit kept, rather than going to the generic failure screen. The ticket's states call for "error (inline, typed text kept)". Retry from the failure screen also returns to the rows if they're still held.
4. **Save is pinned in a bottom bar** while checking, so a long statement doesn't bury it.
5. **A superseded reading is sent again from the document held in memory** (no second file read), then checked afresh with the read-again notice.
6. **Added `ManualEntrySaved.LOOKS_LIKE_A_TRANSFER`,** which wasn't in the ticket. Adding `OWN_TRANSFER` made manual entry's `savedAs` incomplete, and a typed payment paired as a transfer deserves to be told so rather than a generic "needs review".
7. **Removed `StatementImportFlow.rowsToSave`,** which was unused after this change.
8. **The summary still reports what was read.** "12 transactions found" counts parsed rows, not rows saved after removals.

**Changed after review** (PR #56):

9. **The Discard dialog is honest about the allowance.** The server still counts a read but unsaved import against the month (backend `_imports_this_month`); only a re-read within 24 hours is free (#73). The dialog now says: "Nothing from this statement will be saved. Importing again within a day won't use another of this month's imports."
10. **"Some of them need a look" follows fixes after saving.** Fixing or deleting a saved row that was waiting counts `needsReview` down by one and rewrites the summary. It counts down from the save's own figure, because the loaded list is only the import's first page, so recounting from it would be wrong for long statements.
11. **The "everything removed" text no longer offers "choose another statement".** The check step has no such button, and Discard closes the import.
12. **The summary chips wrap** (Android `FlowRow`, iOS `ViewThatFits`), so they don't clip at the largest font size. This covers the check step and "What we read".

## Open questions / follow-ups

- **Screens not verified by Claude.** No simulator or emulator was driven (team rule). The manual steps above, including light/dark, the largest font scale and the smallest/largest phones, are for the developer.
- **iOS has no unit tests for this view model.** Its logic mirrors Android's and the shared rules are tested, but iOS is verified by build only.
- **Backend follow-up (from review):** an explicit discard that gives the month's import back, capped like re-reads, so a person who discards the wrong statement and returns days later isn't refused. A product decision.
- **Optional, left out at `/start-ticket`:** backend #74's `earlier_needs_review`, the count of older rows an import sent back to Review, isn't shown.
- **Summary wording:** if people remove rows often, "found" may want a "you removed N" line.
- **Pre-existing:** the import screen formats money with the `en` locale on both platforms (unchanged here).
