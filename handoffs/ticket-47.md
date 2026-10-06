# Handoff — ticket #47

**Ticket:** [#47](https://github.com/Humble-Coders/FinAI-Mobile-2026/issues/47) — [M4] Budget screens: see the month's budget and change any line
**PR:** [#50](https://github.com/Humble-Coders/FinAI-Mobile-2026/pull/50) · branch `ticket-47-budget-screens` · 4 commits, 29 files, +4687 / −9

## Summary

A fourth tab, **Budget**, between Transactions and Review, on Android and iOS. It shows one month's budget — every category with what has been spent against its allocation — and lets any line be set by hand or put back to the server's suggestion. The month selector reaches earlier months, which keep the budgets they had.

Everything is modelled against the **merged and deployed** backend ([Finance-backend#61](https://github.com/Humble-Coders/Finance-backend/pull/61), live in `finai-api`) rather than the ticket's prose, which differs in three load-bearing ways — recorded under *Deviations*. No figure is computed on the client: the ordering, the bar fractions and every refusal come from shared `BudgetEdit`, and the amounts are formatted from strings the server sent.

The only subtraction anywhere is `BudgetLine.overBy`, which is the difference of two figures the server sent, because this endpoint carries no `over` field.

## Files changed

### Shared (`sharedLogic`) — the half both apps read

| File | Why |
|---|---|
| `model/Budget.kt` *(new, 234)* | `Budget`, `BudgetLine`, `BudgetStatus`, `LearningProgress`, `LearningNeeds`, decoding `BudgetOut`. Defaults on every field. Per-line derivations: `fraction`, `isOver`, `overBy`, `differsFromSuggestion`. |
| `repository/BudgetRepository.kt` *(new)* | `get` / `setLine` / `resetLine`, each `@Throws`. The doc records that all three answer with the whole budget. |
| `data/KtorBudgetRepository.kt` *(new)* | The three calls over Ktor, one implementation for both platforms. |
| `data/ApiErrorMapper.kt` *(+9)* | Maps `not_budgetable`, `invalid_month`, `month_in_future`; they previously fell through to a generic `Validation`. |
| `model/ApiException.kt` *(+28)* | The three types those codes map to, each with its own message key. |
| `usecase/BudgetEdit.kt` *(new, 206)* | `blockingReason` / `blockingReasonForNew`, the `ordered` fold with `BudgetOrder`, `forHome`, `months`, `monthKeyOf`, `isBudgetable`. |
| `i18n/Strings.kt`, `EnglishStrings.kt` *(+93)* | 37 keys: the screen, the editor, the picker, five blocking reasons, three refusals, two screen-reader sentences, and the shared "Still learning" copy. |

### Android

| File | Why |
|---|---|
| `ui/budget/BudgetUiState.kt` *(new, 221)* | The screen's reading of a budget: labels, fractions, the pickable list, `editBlock`. |
| `ui/budget/BudgetViewModel.kt` *(new, 298)* | Load, month change, edit, save, reset, gating, `SavedStateHandle`. |
| `ui/budget/BudgetScreen.kt` *(new, 383)* | Green field header + white sheet of lines, in Home's language. |
| `ui/budget/BudgetSheets.kt` *(new, 217)* | The line editor `ModalBottomSheet` and the category picker. |
| `ui/budget/BudgetRoute.kt` *(new)* | Wires the model to the screen and the two sheets. |
| `ui/components/LearningCard.kt` *(new, 177)* | The "Still learning" card, on the field and on a sheet. Built for #46 to reuse. |
| `util/BudgetChanged.kt` *(new)* | The allocation-moved signal. |
| `navigation/FeaturesViewModel.kt` *(new)* | Reads capabilities once so the bar can be built from them. |
| `navigation/AppNavigation.kt` *(+56/−9)* | `HomeRoute.BUDGET`, `tabsFor(capabilities)`, the route, the bar item, and `ALL_TABS` so a tab switch still cross-fades. |
| `ui/budget/BudgetViewModelTest.kt` *(new)* | 22 tests. |
| `navigation/BudgetTabTest.kt` *(new)* | 5 tests: the tab gate, including a restore before capabilities land. |

### iOS

| File | Why |
|---|---|
| `viewmodel/BudgetViewModel.swift` *(new, 358)* | The Android model's counterpart, including `snapshot` / `restore`. |
| `ui/BudgetView.swift` *(new, 336)* | The same screen: field header, month strip, sheet of lines. |
| `ui/BudgetSheets.swift` *(new, 155)* | The `.sheet` editor (a `Form` with a keyboard Done) and the picker. |
| `ui/components/LearningCard.swift` *(new, 133)* | `LearningFieldCard` and `LearningCard`, matching Android. |
| `util/BudgetChanged.swift` *(new)* | The notification. |
| `viewmodel/FeaturesViewModel.swift` *(new)* | The gate. |
| `navigation/RootView.swift` *(+28)* | `HomeRoute.budget`, the tab, the models, the unbind, and the route added to the exhaustive switch that chooses tabs vs a full-screen flow. |

New Swift files needed no project edit: `iosApp` is a `PBXFileSystemSynchronizedRootGroup`.

### Docs

`docs/tickets/M4.4-…md` and `M4.5-…md` were untracked in the working tree; ticket docs are tracked in this repo (10 already committed), so they are included.

## How to test

```bash
git checkout ticket-47-budget-screens
./gradlew ktlintCheck :androidApp:assembleDebug :androidApp:testDebugUnitTest
./gradlew :sharedLogic:iosSimulatorArm64Test :sharedLogic:testAndroidHostTest
cd iosApp && xcodebuild -workspace iosApp.xcworkspace -scheme iosApp \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build
```

Then, on a device or simulator, signed in as a household with at least one complete month and 20 transactions (synthetic data only):

1. **The tab is there.** Home · Transactions · Budget · Review.
2. **The month reads.** Totals, expected income, the savings line, each category with "spent of allocated" and a bar. Over-budget lines carry a warning mark *and* an "Over by $N" label.
3. **An edit sticks.** Tap Groceries, set 500, Save. The row shows $500 with "Suggested $440" under it. Leave the tab, come back — still $500.
4. **Save refuses with a reason.** In the editor try empty, `abc`, `-50`, `500.999`, then the unchanged amount. Each gives its own line, and Save stays off.
5. **Reset.** Reopen Groceries, tap "Use suggestion ($440)". The row returns to 440 and the note goes.
6. **Add a category.** "Add a category" → the picker omits Income and Transfers and anything already budgeted. Pick one, set an amount, Save; the line appears.
7. **A failed save keeps your typing.** Turn off the network, change an amount, Save. The sheet stays open with the amount and an error.
8. **Earlier months.** Tap a previous month in the strip; its kept budget loads.
9. **Unfiled.** With uncategorised spending, the sheet says so and "Review them" opens the Review tab.
10. **Learning.** On a household under the threshold: the "Still learning" card with correct progress, *plus* any hand-set lines and "Add a category".
11. **Gating.** With `auto_budget` disabled for the household, the tab is absent.
12. **The draft survives.** On the Budget tab, open a line and type an amount, then press Home to background the app.
    - **Android:** `adb shell am kill com.humblesolutions.finai`, then reopen. **Not** Settings → Force stop: that discards saved state by design, and the draft vanishing then is correct.
    - **iOS:** force-quit from the app switcher, then reopen.

    Expected on both: back on the Budget tab with the editor showing what you typed, the line's figures current, and the budget re-read.

Also check, in both themes and at the largest font scale, on the smallest and largest phone: nothing clips, long category names ellipsize, touch targets stay ≥48dp/44pt.

## Acceptance criteria

| # | Criterion | Status |
|---|---|---|
| 1 | Budget tab shows totals, savings, shortfall, each line with spent of allocated, over-budget labelled in words | **Built** — needs the manual pass above; not verified on a device |
| 2 | Changing a line sticks through regeneration | **Built** — save path covered by `a_saved_line_sends_the_amount_and_takes_the_budget_that_comes_back`; the persistence itself is the server's and is tested in #56 |
| 3 | "Use suggestion" resets to the server's current suggestion | **Met** — `using_the_suggestion_resets_the_line` |
| 4 | "Add a category" works; `income`/`transfers` not offered | **Met** — `the_picker_offers_neither_income_transfers_nor_a_category_already_budgeted`, `a_category_that_cannot_hold_a_line_is_refused_before_the_request` |
| 5 | Save disabled with the right reason for empty / not a number / negative / too precise / unchanged, from one shared function | **Met** — `BudgetEditTest` (8 tests) + `a_draft_the_screen_would_refuse_is_never_sent` |
| 6 | A failed save keeps the sheet open with the input and the error; nothing half-applied | **Met** — `a_failed_save_keeps_the_sheet_open_with_what_was_typed` |
| 7 | Below the threshold, the learning card with correct progress, and no empty budget | **Met** — `a_learning_household_keeps_the_lines_it_set_by_hand`; see *Deviations* for what "no empty budget" was taken to mean |
| 8 | `auto_budget` disabled hides the tab; a 403 shows its own message | **Met** — the tab is built from `tabsFor(capabilities)` in `AppNavigation.kt` / `features.budgetEnabled` in `RootView.swift` (`BudgetTabTest`); the 403 by `a_403_shows_the_feature_s_own_reason` |
| 9 | After a save or reset, Home's budget card shows the new figure | **Not met — moved to #46.** There is no Home budget card yet. This ticket emits `BudgetChanged` (tested, including that a failed write announces nothing); #46 subscribes and tests the round trip |
| 10 | "Transactions not filed" opens the Review tab | **Met** — `onReviewUnfiled` / `onReview` wired to the Review route on both platforms |
| 11 | Previous months reachable and show their kept budgets | **Met** — month strip + `showMonth`; `changing_month_reads_that_month` |
| 12 | No figure computed on the client | **Met** — the only arithmetic is `BudgetLine.overBy` (a difference of two server figures) and the bar ratios, which are never shown |
| 13 | ktlintCheck, Android build and tests, iOS `xcodebuild` all pass; the PR states all three | **Met** — see below |

### UI standards

Built to the standard and **not visually verified** (no simulator run, per standing instruction): design-system reuse, light/dark, native components, edge-to-edge and insets, responsive and the 560 cap, keyboard behaviour, truncation, every state, accessibility, i18n, architecture.

Two are verifiable from the diff and are met: **every string goes through `sharedLogic/i18n`** (no literals in platform code) and **content width is capped at 560** on both platforms.

**Motion / reduce-motion is not specifically addressed.** The bars are stock `LinearProgressIndicator` / `ProgressView` and animate on value change with the system default; nothing custom was added, and nothing reads the reduce-motion setting. Worth a look if the PO cares.

## Deviations / decisions

1. **A write is not followed by a read.** The ticket says "after a save or a reset, re-read the month". `PUT` and `DELETE` both answer with the whole recomputed `BudgetOut`, so the response goes straight into state. Re-reading would cost a round trip, and patching the single line locally would briefly show totals and other suggestions in a combination that never existed.

2. **`BudgetChanged`, not `LedgerChanged`.** The ticket left the choice to the developer and asked it be stated. The two point opposite ways: this screen *listens* to `LedgerChanged` (an import moves what each line has spent) and *emits* `BudgetChanged` (an allocation moving does not touch the ledger). One signal would make the transaction list and every figure screen re-read for rows that did not change.

3. **Learning shows the card *and* any hand-set lines.** The ticket reads as card-only. The server deliberately returns hand-set lines while learning, and #56 made `PUT` create a line for a category with no history "so manual budgeting is always available" — PRD **F9**. Card-only would hide someone's own figures and make "Add a category" unreachable. **Confirmed with the manager before building.**

4. **Precision gets its own reason.** `Money.normalize` declines `"500.999"` and `"five hundred"` with the same `null`, so excess precision surfaced as "that isn't an amount". `BudgetEdit.isTooPrecise` tells them apart by re-reading the draft at a wider scale, only after the currency's own scale has refused it, so `"1,200"` is still twelve hundred. (An earlier note in this branch's history claimed `normalize` *truncates* excess decimals — it does not; it declines them.)

5. **One ordering fold with a parameter.** #46 wants Home's lines by how close they are to their allocation, #47 by spend. Both put over-budget first; `BudgetOrder` is the only difference, so the shared half cannot drift. `BudgetEdit.forHome` is there for #46.

6. **The tab is gated above the screen**, by a small `FeaturesViewModel` per platform. A tab has to be absent before anything behind it opens. This is the first use of `Capabilities.isEnabled`, unused in `sharedLogic` since M1. Unknown means hidden. The screen holds no gating state of its own: a 403 that still arrives shows the feature's reason through `errorKey`.

7. **`LearningCard` is a component, not inline markup.** Doing 4.5 before 4.4 flipped the ownership the tickets assumed — #46 was to build it and this to reuse it.

8. **The server's `MAX_LINE_MINOR_UNITS` 422 is unreachable from this client**, so no blocking reason mirrors it: `Money.MAX_WHOLE_DIGITS` caps input at 12 whole digits, five orders of magnitude below the server's ceiling. A 13-digit entry reads as "that isn't an amount", consistent with every other money field in the app.

## Open questions / follow-ups

- **#46 and #47 specify different secondary orderings** for the same lines — "nearest their allocation" vs "by spent". If deliberate (Home shows the most urgent four, the tab shows everything), it is fine as built. If not, Home and the Budget tab will order the same budget differently on screen. **For the PO at `/review-ticket`.**
- **AC 9 moves to #46**, along with the `BudgetChanged` round-trip test. The handoff for #46 should say which signal it subscribed to.
- **The navigation decision needs PO confirmation** — a fourth tab rather than a link from Home was a manager decision of 2026-10-04.
- **Nothing was run on a simulator.** The manual pass above has not been performed by me.

## Fixed after manager review

The first review of this PR found the saved-state restore — claimed as working in this report and the PR — **did not work on either platform**. The first `bind` after a restore treated the same person as a new one and reset the state, throwing the restored draft away. The test covering it passed because it never called `bind`, which a real restore always does.

| Finding | Fix |
|---|---|
| Restore wiped by `bind` (both platforms) | Android: `boundTo` lives in the `SavedStateHandle`, and `bind` separates "reset because the user changed" from "build because nothing is built". iOS: the scene snapshot carries the owner and `restore(from:)` sets it. |
| The test that missed it | Now binds the restored model, and asserts the figures are re-read rather than restored. Confirmed to **fail** against the original bug before the fix went back in. Plus a test that a different person does not inherit the draft. |
| `settle()` wiped categories on a failed categories call | `categories.ifEmpty { it.categories }`, as `failed()` and iOS already did. Tested. |
| iOS kept a server error under the field while the person retyped | `draftAmount`'s `didSet` clears it, as Android's `onAmountChange` does. |
| `available` computed, never read | Removed on both platforms; the tab is the gate. |
| Android rows likely announced twice | `clearAndSetSemantics`, matching iOS's `.accessibilityElement(children: .ignore)`. |
| Empty sheet flashed under the first-load coin | Sheet not drawn while loading with no budget yet. |
| `budget_unavailable` string, `BudgetChanged.version` (iOS) | Removed — nothing read either. |

**The iOS restore fix has no automated test**: the project has no iOS test target. It is verified by the build and needs manual step 12 below on a device.

## Fixed after the second review

| Finding | Fix |
|---|---|
| **Android sent a person restored onto Budget back to Home.** The saved route came back as Budget before capabilities had been read, and the gate treated "not read yet" as "off" — so the restored draft sat unseen until the next tap on Budget. | `tabsFor` keeps Budget for someone already on it until the payload says no, and `leavesBudget` only fires on a payload that has been read. Both are now `internal` and tested in `BudgetTabTest`; the restore case was confirmed to **fail** against the old logic. On iOS the explicit bounce could not happen (its `onChange` never fires for the first value), but a `TabView` selection matching no tab was left to SwiftUI — **this row originally said "iOS was not affected", which was not verified.** See the third review. |
| A restored editor kept a stale copy of its line | `settle` swaps the line for its current self by `categoryId`, keeping what was typed. Tested on Android; iOS by build. |
| Step 12 said "force-stop" next to `am kill` | Reworded — Force stop discards saved state by design. |

## Fixed after the third review

| Finding | Fix |
|---|---|
| **The amount field stayed editable while a save was in flight**, against the UI standard "saving: busy button, inputs locked". Typing during a slow save left the field showing one figure while the server answered about another. | Android: `AmountField` gains `enabled` (default `true`, so every other screen is unchanged) and the editor passes `!busy`. iOS: `.disabled(model.busy)` on the field. |
| **iOS restore window, unverified.** After a restore the scene-stored route is `"budget"` before the Budget tab exists in the `TabView`, and what SwiftUI does with a selection matching no tab was not established. | Not left to SwiftUI. `FeaturesViewModel.showsBudget(onBudget:)` keeps the tab for someone already on it while capabilities are unknown, and `leavesBudget(onBudget:)` fires only on a payload that says off — the same rule as Android's `tabsFor` / `leavesBudget`. Navigation reacts to `budgetGate` (unknown / on / off), because from "not read" to "off" the tab itself never changes. The now-unused `budgetEnabled` was removed. **Built, not run**: there is no iOS test target. |
| The snapshot's comment said nothing in it could contain a tab; a household's category name can | Tabs are taken out of the name when writing; the comment says why that is safe — `settle` replaces the line once the budget is read. |

## Verification

```
./gradlew ktlintCheck                            clean
./gradlew :androidApp:assembleDebug              BUILD SUCCESSFUL
./gradlew :androidApp:testDebugUnitTest          252 tests, 0 failures
./gradlew :sharedLogic:iosSimulatorArm64Test     517 tests, 0 failures
./gradlew :sharedLogic:testAndroidHostTest       524 tests, 0 failures (incl. the SKIE @Throws guard)
xcodebuild -workspace iosApp.xcworkspace -scheme iosApp -sdk iphonesimulator
                                                 ** BUILD SUCCEEDED **
```

New with this ticket: 22 Android view-model tests, 5 navigation tests and 28 shared tests (17 in `BudgetEditTest`, 11 in `BudgetTest` — the latter decoding the real `BudgetOut` shapes for ready, learning and shortfall, plus a payload missing everything optional and an unknown status).
