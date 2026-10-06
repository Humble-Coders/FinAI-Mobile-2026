# Handoff — ticket #52

**Ticket:** [#52](https://github.com/Humble-Coders/FinAI-Mobile-2026/issues/52) — [M5] Goals: set savings targets and see what each one needs
**Branch:** `ticket-52-goals` · 3 commits · 42 files, +5166 / −153

## Summary

A **Goals** tab on Android and iOS where a household sets savings targets, edits them, adds money as it saves, reorders them by priority and deletes them. Each goal shows the server's plan in words: what it needs a month, when it will be done, and whether it is on track. Long-term goals say they count no investment growth and show the region's disclaimer from the server. Goals **takes Review's place on the bar** (Home · Transactions · Budget · Goals). Review still opens from Home, the import result and the Budget tab, now over the bar with a back arrow. Home gains a goals card below the budget section.

Everything is built against what backend #65 (5.1) actually merged, which is live on `finai-api`. Every figure is the server's, and every sentence is built once in shared `GoalEdit`, so the two apps cannot word a goal differently.

## Files changed

### Shared (`sharedLogic`)

| File | Why |
|---|---|
| `model/Goal.kt` *(new)* | `GoalsPage`, `Goal`, `GoalsBudget`, and the status, kind, horizon and budget-reason enums, each with an `UNKNOWN` fallback. Also `NewGoal`, and `GoalChanges` with its `clear…` flags. No currency field: goals carry none, so screens use the household's from capabilities. |
| `repository/GoalsRepository.kt`, `data/KtorGoalsRepository.kt` *(new)* | List, create, update, add, reorder, delete, disclaimer. The `PATCH` body is written by hand so a cleared field is sent as an explicit `null`. `no_disclaimer` (404) returns `null`. |
| `data/ApiErrorMapper.kt`, `model/ApiException.kt` | Map `goal_limit_reached` (409, with its limit), `date_in_past` and `order_mismatch`. |
| `usecase/GoalEdit.kt` *(new)* | Blocking reasons for a new goal, an edit and "add money". The exact changes an edit sends. Order moves. Every sentence: status, amounts, comparison, growth, screen-reader text. Home's card (`homeCard`). |
| `util/MoneyInput.kt` *(new)* | `BudgetEdit`'s "too precise vs not a number" check, moved out so goals reuse it. Budget behaviour is unchanged. |
| `usecase/BudgetEdit.kt` | Now calls `MoneyInput`. |
| `usecase/HomeInsights.kt` | The score breakdown's `goal_completion` part (backend #66, formula v2): its title and sentences. |
| `i18n/Strings.kt`, `EnglishStrings.kt` | 80 keys in a `// ── Goals (PRD F5, #52) ──` section. |
| Tests: `GoalEditTest` (25), `GoalTest` (6), `KtorGoalsRepositoryTest` (11), `ApiErrorMapperTest` (+3) | New coverage. |
| `usecase/HomeInsightsTest.kt` | One #46 assertion used `goal_completion` as its example of an *unknown* score part. That part is now known, so the test uses a genuinely unknown key and keeps its intent. |

### Android

| File | Why |
|---|---|
| `ui/goals/` *(new)*: `GoalsRoute`, `GoalsViewModel`, `GoalsUiState`, `GoalsScreen`, `GoalsSheets` | The tab, the editor, the add-to-goal sheet, delete confirmation and the date dialog (today onward). Saved state keeps the owner. |
| `util/GoalsChanged.kt` *(new)* | The "a goal moved" signal, emitted only after the server confirms. |
| `navigation/AppNavigation.kt` | `HomeRoute.GOALS`. One gate for every gated tab (`tabsFor` / `leavesGatedTab`). Review as a flow with `reviewReturnsTo`, plus its transition. The Goals bar item. |
| `ui/components/FormFields.kt` | `enabled` on `WizardField` and `AmountField`, defaulting to `true`, so fields can lock while saving. |
| `ui/dashboard/*` | `DashboardViewModel` reads goals when the capability is on and re-reads on `GoalsChanged`. `goalsCard` in UiState. `GoalsSection` in `HomeInsightsViews`, placed below the budget. `onOpenGoals` on the route. |
| `navigation/GatedTabsTest.kt` *(renamed from `BudgetTabTest`, 5 → 7 tests)* | Covers both gated tabs, Review leaving the bar, and where Review's back arrow goes. |
| `ui/goals/GoalsViewModelTest.kt` *(new, 20)* | View-model coverage, listed under the acceptance criteria. |

### iOS

| File | Why |
|---|---|
| `viewmodel/GoalsViewModel.swift`, `ui/GoalsView.swift`, `ui/GoalsSheets.swift` *(new)* | The same tab, editor (`Form`, all fields disabled while saving), add-to-goal sheet and delete confirmation. A JSON snapshot in `@SceneStorage` that carries the owner. |
| `util/GoalsChanged.swift` *(new)* | The signal. |
| `viewmodel/FeaturesViewModel.swift` | One gate for every gated tab: `shows(_:onTab:)`, `leaves(_:onTab:)`, `gates`. |
| `navigation/RootView.swift` | The Goals tab, `.review` as a flow with `reviewFrom` and `openReview()`, the shared `onChange(of: features.gates)`, and `goalsModel` unbound with the others. |
| `viewmodel/DashboardViewModel.swift`, `ui/DashboardView.swift`, `ui/HomeInsightsViews.swift` | Home's goals card. |

### Docs
`docs/tickets/M5.2-goals-ui.md`: the ticket as drafted. Ticket docs are tracked in this repo.

## How to test

```bash
git checkout ticket-52-goals
./gradlew ktlintCheck :androidApp:assembleDebug :androidApp:testDebugUnitTest
./gradlew :sharedLogic:testAndroidHostTest :sharedLogic:iosSimulatorArm64Test
cd iosApp && xcodebuild -workspace iosApp.xcworkspace -scheme iosApp \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build
```

Then run the app against the deployed `finai-api`, signed in to a test account with **synthetic data only**:

1. **The bar.** It reads Home · Transactions · Budget · Goals, with no Review tab.
2. **Review still reachable.** Open Review from Home's bell or action, from the end of an import, and from the Budget tab's unfiled note. Each opens over the bar with a back arrow. Back from Home or Budget returns there; back after an import returns Home.
3. **Create.** Tap "New goal". Save is off until you choose short or long term. Create "Car", short term, $6,000:
   - with a target date, it shows "Needs $… a month until …"
   - with only a monthly amount, it shows "Done by … at $… a month"
   - with both, it shows on track or behind, with **both** figures
4. **Validation.** In the editor, try an empty name, `abc`, `-5`, `10.999` and a date before today (the picker refuses it). Each gives its own reason, and Save stays off.
5. **Clearing a date.** Edit the goal, tap "Remove date", save. The date is **gone** after leaving and returning.
6. **Add money.** Use "Add money" on the goal. Saved and progress rise, and a goal reaching its target reads "Goal reached".
7. **Reorder.** Tap "Reorder", move a goal down, tap Done. Leave and return: the order sticks, and Home's card shows the new top three.
8. **Long term.** A long-term goal shows "Doesn't include investment growth" and the disclaimer text.
9. **Comparison.** With a ready budget, the field says "Your goals need … · your budget sets aside …", or the shortfall. While learning, it says why there's nothing to compare, **and goals still work**.
10. **Delete.** Delete asks first, then the goal goes from the list and from Home.
11. **Saving locks the editor.** Slow the network, tap Save, and try to type. Every field refuses input.
12. **Restore.** On Goals, open "New goal", type a name, press Home, then:
    - **Android:** run `adb shell am kill com.humblesolutions.finai` and reopen. Do **not** use Force stop, which discards saved state by design.
    - **iOS:** force-quit from the app switcher and reopen.

    You should be back on Goals with the editor and your text, and Home should not flash first.
13. **Gating.** With `goals` disabled for the household, neither the tab nor Home's card appears.
14. **Screen readers.** With TalkBack or VoiceOver, a goal reads once as one sentence. "Add money", "Move up" and "Move down" are actions on it.

Check light and dark, the smallest and largest phone, and the largest font scale.

## Acceptance criteria

| # | Criterion | Status |
|---|---|---|
| 1 | Bar is Home · Transactions · Budget · Goals, each gated. Review opens from Home, import and Budget with a back arrow to where it came from | **Met in code.** `GatedTabsTest` covers the bar and `reviewReturnsTo`. iOS is wired the same way in `RootView`. Needs manual step 2. |
| 2 | Date → monthly amount; amount → completion month; both → on track / behind with both figures, **neither subtracted** | **Met.** `GoalEditTest`'s status-line cases. Nothing in the diff subtracts two goal figures. |
| 3 | Add money raises saved and progress; reaching the target reads achieved | **Met.** Figures come from the server's answer, and "Goal reached" comes from `statusLine`. VM: `adding_money_sends_the_normalised_amount_and_closes_the_sheet`. |
| 4 | Reordering sticks; Home's card shows the new top three | **Met.** VM: `moving_a_goal_sends_the_new_order_and_shows_the_server_s`. Home re-reads on `GoalsChanged`. Shared `forHome` is tested. |
| 5 | Long-term goals show "Doesn't include investment growth" and the server's disclaimer | **Met.** `GoalEditTest`; VM: `a_long_term_goal_shows_the_disclaimer…`, `no_disclaimer_for_the_region_leaves_the_line_out`. |
| 6 | Comparison when ready; while learning, says why — goals keep working | **Met.** `GoalEditTest`'s comparison cases. Nothing gates goals on learning. |
| 7 | Save disabled with the right reason for every `GoalBlock`; the use case refuses the same | **Met.** `GoalEditTest`. The view model returns early on `editBlock != null`. |
| 8 | A failed save keeps the sheet and input; the 21st goal shows the limit's message | **Met.** VM: `a_failed_save_keeps_the_sheet_open…`, `at_twenty_open_goals…`, and `the_limit_can_answer_an_edit…`. That last one covers the edit path the ticket didn't foresee. |
| 9 | Delete confirms, removes, and updates the list and Home | **Met.** VM: `a_goal_is_deleted_only_once_confirmed`. Delete announces `GoalsChanged`. |
| 10 | Restored onto Goals, the person stays there | **Met in code, unverified on a device.** The gate is tested in `GatedTabsTest`, and the draft-through-`bind` restore in the VM tests. iOS relies on the same gate logic and the build. Needs manual step 12. |
| 11 | `goals` disabled hides the tab and Home's card; a 403 shows its own message | **Met.** `GatedTabsTest`, `goalsCard`'s capability check, and VM `a_403_shows_the_feature_s_own_reason`. |
| 12 | No figure computed on the client | **Met.** The only arithmetic is the bar fill from `progress_percent`. |
| 13 | ktlintCheck, Android build and tests, iOS `xcodebuild` pass; the PR states all three | **Met.** See Verification. |

**UI standards:** all built, none visually verified (no simulator runs).
- **Verifiable from the diff:**
  - every string goes through i18n
  - content width is capped at 560 on both platforms
  - every editor field is locked while saving (`enabled = !busy` on Android, `.disabled(model.saving)` on iOS)
  - amount fields use decimal pads with an on-keyboard Done
  - **Next** moves through the editor's fields
- **Not addressed:** reduce-motion. Progress bars are stock indicators with default animation.

## Deviations / decisions

1. **Reordering uses move buttons on both platforms, not drag.** The ticket suggested a drag handle on Android and native `.onMove` on iOS.
   - Compose has no list reorder of its own.
   - The iOS screen is a field and a sheet, not a `List`, so `.onMove` isn't available without rebuilding it as one.
   - The buttons are what screen readers need anyway, and the same control on both apps keeps them in step.

   **For the PO.**
2. **The real contract differs from the ticket's text.** The code follows backend #65 as merged:
   - The comparison's reason is a top-level `budget_reason`, and the figure is `need`.
   - **The 20-goal limit also answers edits**: lowering a reached goal's saved amount re-opens it.
   - `/legal/disclaimer` returns 404 when the region has none, which the app shows as no line.
3. **Edits send explicit `null`s.** The shared JSON drops nulls (`explicitNulls = false`), and the server treats a missing field as "unchanged". Without this, a removed date would silently survive. `KtorGoalsRepositoryTest` pins it, and the test was **checked to fail** when the null isn't sent.
4. **Goals have no currency of their own.** The server sends none, so screens format in the household's currency from capabilities.
5. **"Something went wrong" splits into two fields.** Load errors and refusals of an action (reorder, delete) are separate. While building, the first version showed an out-of-date reorder's message and then re-read the list, and the successful read wiped the message. A test caught it.
6. **The new-goal limit is checked on the device first.** "New goal" says so before anything is typed, and the editor opens already showing the reason. The server's 409 is still handled.
7. **The horizon starts unanswered**, because it decides whether the disclaimer shows (`CLAUDE.md` → no silent defaults).
8. **Naming.** `AddMoneySheet` already existed (#48's quick entry), so the goals sheet is `AddToGoalSheet` on both platforms. `GoalEdit.newGoal` became `goalToCreate`, because Kotlin/Native turns names starting `new` into `doNew…` for Swift.
9. **The iOS restore snapshot is JSON**, not #47's tab-separated string, because a goal's name is free text.
10. **The Home card sits below the budget section**, because goals are where the money set aside goes. It also shows reached goals when nothing else is left, so a card of nothing doesn't read as no goals at all.

## Open questions / follow-ups

- **#52 had no Product Owner review.** It was drafted without a brief, and `/review-ticket` wasn't run before building, by the developer's choice. Decisions the PO hasn't confirmed:
  - Goals replacing Review on the bar
  - reordering with buttons
  - where the Home card sits
- **Not run end to end.** I can't sign in to the live API, so nothing here has talked to the deployed 5.1. If 5.1's migration hasn't been applied to production, the goals routes would fail; the first live call will show it.
- **iOS has no automated tests.** The project has no iOS test target, so the iOS side is verified by the build alone. Manual steps 12 and 14 matter most there.
- **Backend follow-ups, recorded on #65:**
  - investment growth in long-term projections
  - how debts affect long-term goals
  - the regional disclaimer's text is still a draft placeholder

## Verification

```
./gradlew ktlintCheck                            clean
./gradlew :androidApp:assembleDebug              BUILD SUCCESSFUL
./gradlew :androidApp:testDebugUnitTest          281 tests, 0 failures
./gradlew :sharedLogic:testAndroidHostTest       598 tests, 0 failures (incl. the SKIE @Throws guard)
./gradlew :sharedLogic:iosSimulatorArm64Test     591 tests, 0 failures
xcodebuild … -sdk iphonesimulator                ** BUILD SUCCEEDED **
```

New with this ticket: 20 view-model tests, 2 more navigation tests (`GatedTabsTest`, 7 in all) and 45 shared tests (25 `GoalEditTest`, 6 `GoalTest`, 11 `KtorGoalsRepositoryTest`, 3 `ApiErrorMapperTest`).
