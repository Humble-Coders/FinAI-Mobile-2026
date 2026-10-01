# Handoff — ticket #32

**Ticket:** [#32](https://github.com/Humble-Coders/FinAI-Mobile-2026/issues/32) — [M3] Review and correct the extracted transactions

## Summary

The review queue is built on Android and iOS. It lists the rows extraction could not resolve, grouped by date, each showing what it is, what it cost, its category, and **why** it is here. From there:

- **Confirm all** in one tap, counting only the rows it can actually clear.
- **Fix a row** — date, amount, direction, description, category — with a picker over the shared taxonomy and the household's own, and a way to add one.
- **Delete** what was never a transaction, with a 5-second undo.

A category correction teaches a rule, and when that moves other waiting rows the screen says so and re-reads the queue. Home offers the queue, and an import that leaves rows waiting now hands straight off to it.

## Files changed

### Shared
| File | Why |
|---|---|
| `usecase/ReviewQueue.kt` (new) | The rules both apps read: which rows Confirm all can clear, what to say afterwards, each row's reason, what a duplicate matched, category names, the correction draft and its blocking reasons, a patch of only what changed, and what a correction did beyond its row. |
| `model/ReviewPage.kt` (new) | `ReviewPage` with its opaque cursor, `TransactionPatch`, and the three outcomes (correct, confirm, delete), plus `ConfirmRows` and `NewCategory`. |
| `model/ManualTransaction.kt` | `Transaction` gains `duplicate_of` and `extraction_confidence`, so one model is both a typed-in entry and a review row. |
| `repository/TransactionsRepository.kt` + its Ktor client | `review(cursor)`, `correct`, `confirm`, `confirmAll`, `delete` — all `/transactions/*`, so one client rather than two. |
| `repository/CategoriesRepository.kt` + its client | `create(name)`. |
| `data/FinAiHttpClient.kt` | `patchJson`, `deleteJson` and a bodyless `postEmpty`. |
| `model/ApiException.kt`, `data/ApiErrorMapper.kt` | A category name already used (naming the one that exists), a name with no letters, and a correction that would duplicate an existing row. |
| `i18n/*` | Every line on the screen, plus the shared taxonomy's names keyed by slug (looked up by computed key, so they do not grep as used). The now-unused `import_review_later` is removed. |

### Android
| File | Why |
|---|---|
| `ui/review/ReviewUiState.kt`, `ReviewViewModel.kt` | The queue, its paging, per-row work, the correction sheet and the undo window. A correction in progress goes into `SavedStateHandle`, owner-checked. |
| `ui/review/ReviewScreen.kt`, `ReviewRoute.kt` | The list, the correction sheet reusing the wizard's fields, the category and new-category sheets, and the undo bar. |
| `navigation/AppNavigation.kt`, `ui/home/HomeScreen.kt` | A `REVIEW` route, and a Review transactions entry on home. |
| `ui/statementimport/*` | The import result hands off to the queue instead of saying review will arrive. |

### iOS
| File | Why |
|---|---|
| `viewmodel/ReviewViewModel.swift`, `ui/ReviewView.swift` (new) | The same flow and rules in SwiftUI. The correction goes into scene storage, owner-checked. |
| `navigation/RootView.swift`, `ui/StatusViews.swift`, `ui/StatementImportView.swift` | The route, the home entry, and the hand-off. |

### Tests
- Shared **298** (32 new): `ReviewQueueTest` (20) and `KtorReviewRepositoryTest` (12).
- Android **109** (28 new): `ReviewViewModelTest`.

## How to test

```bash
./gradlew :sharedLogic:testAndroidHostTest :androidApp:testDebugUnitTest :androidApp:assembleDebug
```

Shared **298** and Android **109**, 0 failures.

```bash
cd iosApp && xcodebuild -workspace iosApp.xcworkspace -scheme iosApp -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

BUILD SUCCEEDED. No simulator was run; `:sharedLogic:iosSimulatorArm64Test` is left to CI.

**Break-it checks.** Each was reverted afterwards, and each was caught by exactly the test named.

| Change | What caught it |
|---|---|
| Confirm all counts uncategorized rows | `confirmAllCountsOnlyTheRowsItCanActuallyClear`, `withNothingConfirmableTheButtonAsksForACategoryInstead` |
| The message trusts what was sent | `whatIsSaidAfterwardsComesFromWhatTheServerCleared` |
| Reload after every correction | `theQueueIsRereadOnlyWhenOtherRowsMoved` |
| Delete sent at once, no window | `undo inside the window sends nothing at all` |
| A failed confirm all is not rolled back | `a failed confirm all rolls the rows back visibly` |
| A row that still needs review is dropped | `confirming one uncategorized row leaves it asking for a category` |
| The correction is not restored | `a half-typed correction comes back after the process is killed` |
| Announcements append instead of replacing | `each action says its own thing rather than stacking lines` |
| A refresh blanks the screen | `re-reading after an action keeps the list up instead of blanking it` (caught mid-flight; the end state looks the same either way) |
| A failed refresh wipes the list | `a refresh that fails keeps the rows and says so` |
| A string key put into `announcements` raw | `every announcement is a sentence, never a string key` |

**By hand, on a device,** against a **local backend** with synthetic data (production refuses imports until the no-training tier is on, though the queue itself works against anything with rows in it):
1. Import a statement that leaves rows waiting, then tap **Review transactions** on the result. Home offers the same entry.
2. The list groups by date. Each row shows its reason, and a suspected duplicate shows what it matched.
3. **Confirm all** counts only the rows with a category. Rows without one stay, asking for one.
4. Tap a row, change its category, save. If other waiting rows share the merchant, the screen says "also applied to N others" and the list updates without a manual refresh.
5. Change an amount to `1,234.5` and save: it stores as `1234.50`. The number pad has a Done accessory and the field stays above the keyboard.
6. Delete a row, then **Undo** inside 5 seconds: it comes back and nothing was sent. Delete another and leave the screen: it goes.
7. Rotate mid-correction, and (Android) background with *Don't keep activities* on: what was typed is still there.
8. Light and dark, the largest text size, VoiceOver and TalkBack.

## Acceptance criteria

| Criterion | Status |
|---|---|
| Every reason renders distinctly; paging neither skips nor repeats | **Met.** `everyReasonReadsDifferently`; `paging follows the cursor and never lists a row twice` (keyset cursor, and a repeated row is filtered). |
| Confirm-all clears every row **that has a category**, shows that count, rolls back visibly; rows without one stay and are never counted | **Met.** Four tests, including a partial confirm re-reading the queue. |
| A category correction updates the row, reports how many others it moved, and the screen reflects both without a manual refresh | **Met.** `a correction that moved other rows says so and re-reads the queue`. |
| Editing an amount uses the number pad, stays above the keyboard, round-trips exactly as a decimal string, with a test asserting no `Double` | **Met.** `anAmountIsAlwaysADecimalStringAndNeverRounded` and `an amount correction round-trips as a decimal string`. Every amount goes through `Money`; there is no `Double` in the path. The keyboard behaviour is in code (`AmountField` plus `imePadding`, and a Done accessory on iOS) — check on a device. |
| Deleting offers undo, and undo restores the row | **Met.** Three tests: undo sends nothing, the window closing sends it, leaving sends it. |
| A suspected duplicate shows what it matched before the user can act | **Met.** `aSuspectedDuplicateSaysWhatItMatched`, and the row draws it above its actions. |
| Rotation, configuration change and process death lose neither the in-progress edit nor the scroll position | **Partly, by decision.** The edit survives all three (three tests). Scroll position is restored within the first page; a queue paged deep reloads from the top — see Deviations. |
| Every derived rule is covered by UiState unit tests, not by inspecting the screen | **Met.** 24 view-model tests over the state, plus 20 on the shared rules. |
| Android compiles and tests pass, `testAndroidHostTest` passes, iOS `xcodebuild` succeeds | **Met.** 298 / 109 / BUILD SUCCEEDED. |

## Deviations / decisions

The manager decided these on 2026-10-01:
1. **Undo is client-side, and leaving sends the delete.** The server's delete is permanent with no undo, so the only honest window is to hold the request. Five seconds; deleting another row sends the one waiting.
2. **The queue is re-read only when the server says other rows moved.** It returns a count, not which rows, so a reload is the only correct answer — and the common correction moves nothing, so it costs nothing. The corrected row itself always comes back in the response.
3. **The shared taxonomy is translated by slug**, falling back to the server's name for a slug this build does not know. A household's own category always shows the name the person typed.
4. **Scroll restore after process death** keeps the edit exactly and the position within the first page. A deep-scrolled queue reloads from the top; re-fetching every page would mean several requests on launch, and those rows may have been answered meanwhile.

Also:
- **The review endpoints joined `TransactionsRepository`** rather than getting a repository of their own: they are all `/transactions/*`, and a second client would mean a second HTTP client per bind.
- **One `Transaction` model** serves both a typed-in entry and a review row, now that it carries `duplicate_of` and `extraction_confidence`.
- **Home always offers the queue**, rather than fetching a count first: home has no count to ask for, and the queue's own empty state ("nothing needs review") is a good answer either way.
- **A category name already used selects the existing category** instead of refusing, since asking the person to think of another name is busywork.

**From the manager review (PR #41):**
- **Each action's message replaces the last.** They were appended, so working down a queue stacked lines above the Confirm-all button that nothing ever cleared (`dismissAnnouncements` existed and was wired, but no screen called it). On iOS the list was also keyed by the message text, which repeats verbatim — duplicate ids in a `ForEach` drop rows — so it is keyed by position now.
- **A re-read after an action no longer blanks the screen.** `load(refresh = true)` keeps the list up and shows a thin progress line instead of the coin, and a refresh that fails keeps the rows it had rather than falling back to the error screen. Only opening the screen earns the coin.
- **The paging cursor goes through Ktor's parameter** rather than being glued into the path. It is URL-safe base64 today; the day it is not, a `+` would reach the server as a space and paging would break silently.
- **A string key no longer reaches the screen.** The taken-name branch put `e.messageKey` into `announcements` on Android while iOS resolved it, so that one message read as `review_category_exists`. `announcements` holds finished text; the key is resolved before it goes in. The test asks the general question — does this string resolve to something other than itself? — so any future key put in raw fails too.
- **Four strings that were on no screen are gone** (`review_confirm_failed`, `review_needs_category_next`, `review_row_failed`, `review_delete_failed`). Row errors render the API's own message.

## Open questions / follow-ups

- **Deep-scroll restore** (decision 4) could remember the cursors and replay them, if the queue ever routinely runs to several pages.
- **The iOS view model has no unit-test target**, as with #30 and #31. Its rules come from the tested shared layer; its plumbing is covered by the device steps.
- **An import finishing** is announced on the row that finished it. There is no separate "statement done" screen; M4's history is where that would live.
