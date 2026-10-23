# Handoff — ticket #76 (app, stage 3)

**Ticket:** [Finance-backend#76](https://github.com/Humble-Coders/Finance-backend/issues/76) — [M5] Your plan: where the money left over goes, debt payoff first

The ticket is delivered in three PRs. Stages 1 and 2 are the backend (Finance-backend PR #77,
merged; PR #78, the plan). This is stage 3, the app. The backend's stages are recorded in
`Finance-backend/handoffs/ticket-76.md`.

## Summary

**Your plan** shows this month's money left over (the budget's Savings line) and how it splits:
1. the safety buffer;
2. each goal's share;
3. the extra debt payment, and which debt it goes to first;
4. "Save or invest".

Under the split are the four choices, each with its value, one line of why, and the suggestion
when the person's choice differs from it. "Change" opens a sheet to set them. The plan's guidance
line and the region's disclaimer close the page.

**Your debts** shows:
- the debt-free month, the total interest, and the interest saved against paying only minimums;
- the avalanche/snowball switch, with what the other order would cost;
- each debt in payoff order, with its step, payoff month and interest. Below the order come the
  debts that only get their minimum, then those with no rate ("Add the interest rate").
- under each debt: its promotion, where its balance came from and when, its linked account, and
  the reminder when the balance is stale.

Tapping a debt edits its details: name, type, minimum, rate, a promotion, and the linked card or
loan account. "Update balance" is a separate sheet with one amount.

**Entry points** (shown only while capabilities enable `debt_plan`):
- a "Where your extra money goes" card on Home;
- "See where it goes" under the Budget tab's Savings line;
- "See your payoff plan" on Home's Debts screen, which opens Your debts directly;
- each goal on the Goals tab says "Your plan suggests $X this month" beside its own figures.

**Statement balances.** The phone now reads a statement's closing balance before redacting, as it
already reads the period. When a debt is linked to the import's account and the statement closed
after that debt's balance date, the check screen asks: "This statement says you owed $X on
<date>. Update <debt> to this balance?" The switch starts off. Only with a yes does the save send
`closing_balance` and `closing_date`. The summary then says the balance was updated.

All of this is built on both apps. The rules, the folds and every sentence are in `sharedLogic`.

## Files changed

**Shared (`sharedLogic`)**
- `model/Debts.kt` (new):
  - `DebtsPage`, `DebtRecord`, and the `DebtKind` and `BalanceSource` enums, which tolerate
    unknown values;
  - `DebtChanges`, with `clear…` flags, because the server clears a field only when sent an
    explicit null.
- `model/Plan.kt` (new):
  - `Plan`, `PlanPreferences`, `PlanSuggested`, `PlanSplit`, `PlanGoalShare`, `PlanDebt`,
    `PlanTotals`;
  - `PlanStatus` and `PlanStrategy`, where an unknown strategy reads as avalanche;
  - `PlanPreferencesToSave`.
- `repository/DebtsRepository.kt` and `repository/PlanRepository.kt` (new), with
  `data/KtorDebtsRepository.kt` and `data/KtorPlanRepository.kt`:
  - the `PATCH` and `PUT` bodies are written by hand: an explicit null clears a field, and a null
    buffer means "no buffer";
  - a promotion is only ever sent as a pair.
- `usecase/DebtEdit.kt` (new):
  - `DebtDraft`, `DebtBlock` and `BalanceBlock`;
  - `blockingReason` and `changes` (only what changed is sent; `"120"` equals `"120.00"`);
  - `blockingReasonForBalance` (zero is allowed; an unchanged figure is refused, because the
    server keeps the date of an unchanged balance);
  - `linkable` (cards and loans only);
  - the words: kinds, source line, stale line, promotion line, icons.
- `usecase/YourPlan.kt` (new): the plan folded into rows and sentences:
  - the headline (left over / nothing / shortfall / learning);
  - the split rows, the choice rows, the debt rows in payoff order, and the totals and
    comparison lines;
  - Home's card, and a goal's suggested amount;
  - the choices' rules: `blockingReason`, `toSave`, `bufferGoalNote`, and `strategyOnly`, which
    is null until the first save; see Deviations.
- `usecase/StatementBalance.kt` (new):
  - `find` reads "New balance / Closing balance / Statement balance / Balance due" and a date (the
    statement date, else the period's end). A credit balance or an undated one gives nothing.
  - `offer` returns the debt linked to the account, only when the statement is newer.
  - `question` gives the check screen's sentence.
- `usecase/ImportStatement.kt`: `onClosingBalance`, called before redaction. Never for a
  screenshot.
- `usecase/StatementPeriod.kt`: `datesIn` is now internal, so `StatementBalance` can reuse it.
- `model/ParsedStatement.kt`: `RowsToSave.closingBalance`/`closingDate` (left off the wire when
  null) and `SaveOutcome.debtsUpdated`.
- `usecase/ImportCheck.kt`: `rowsToSave(…, closingBalance)`.
- `usecase/StatementImportFlow.kt`: the "balance updated" summary line.
- `util/Percent.kt` (new): the server's percentage rule (0–100, two decimals) on `Money`'s digits.
- `i18n/Strings.kt`, `i18n/EnglishStrings.kt`: 118 new keys.

**Android**
- `ui/plan/` (new):
  - `PlanUiState`, with every gate as a computed value;
  - `PlanViewModel`: one model for both screens. It keeps what was typed in `SavedStateHandle`,
    and announces `PlanChanged` (and `GoalsChanged` after a choices save);
  - `PlanRoute`, `PlanScreen`, `PlanSheets` (the choices, debt details and balance sheets).
- `util/PlanChanged.kt` (new).
- `navigation/AppNavigation.kt`:
  - `HomeRoute.PLAN` and `PLAN_DEBTS`, with `planFrom` to go back to;
  - `leavesPlan` sends the person home if the feature turns off.
- Dashboard (`DashboardViewModel`, `DashboardUiState`, `DashboardScreen`, `HomeInsightsViews`,
  `DashboardRoute`): the plan card. It reloads when the plan or a goal changes.
- Goals (`GoalsViewModel`, `GoalsUiState`, `GoalsScreen`): the suggested amount, which is also
  part of each goal's spoken description.
- Budget (`BudgetRoute`, `BudgetScreen`): the Savings line link.
- Money detail (`MoneyDetailRoute`, `MoneyDetailScreen`): the Debts screen link.
- Statement import (`StatementImportViewModel`, `StatementImportUiState`, `StatementImportScreen`,
  `StatementImportRoute`): the balance card on the check screen, and the confirmed balance in the
  save.

**iOS**
- `viewmodel/PlanViewModel.swift`, `ui/PlanView.swift`, `ui/PlanSheets.swift`,
  `util/PlanChanged.swift` (new): the same screens and rules. A scene-storage snapshot is kept
  while a sheet is open.
- `navigation/RootView.swift`:
  - the `plan` and `planDebts` routes, with `planFrom`;
  - the loader for the plan's first read;
  - home if the feature turns off.
- `viewmodel/FeaturesViewModel.swift`: `planFeature` and `Gates.plan`.
- Dashboard, Goals, Budget, MoneyDetail and StatementImport views and models: the same changes as
  Android. The `SaveOutcome` initializer gains `debtsUpdated` (SKIE).

**Tests**
- Shared (71 new):
  - `KtorPlanRepositoriesTest` (10): exact paths and bodies, explicit nulls, the promotion pair,
    `goal_limit_reached`, no disclaimer, unknown enums;
  - `YourPlanTest` (15), `DebtEditTest` (12), `StatementBalanceTest` (8), `PercentTest` (4);
  - `ImportStatementTest` (+2): the balance is read before redaction and never sent; a screenshot
    has none.
- Android (13 new):
  - `PlanViewModelTest` (10): loading, a failed refresh keeping the plan, choices saves and
    refusals, the strategy switch before and after the first save, debt edits, a balance update,
    saved state;
  - `StatementImportViewModelTest` (+3): the balance is offered and sent only with a yes; left
    unticked it isn't sent; no linked debt means no offer.

## How to test

1. Run `./gradlew ktlintCheck` (clean).
2. Run `./gradlew :sharedLogic:testAndroidHostTest` (670 pass) and
   `./gradlew :androidApp:testDebugUnitTest` (310 pass).
3. Run `./gradlew :androidApp:assembleDebug` (builds).
4. Run the iOS build (succeeds):
   `xcodebuild -workspace iosApp/iosApp.xcworkspace -scheme iosApp -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' build`
5. **By hand, on a device or emulator.** This needs backend PR #78 merged and production migrated
   to `f2a9c47e1b05`; until then `debt_plan` isn't in capabilities and the entry points stay
   hidden.
   1. **Home:** the "Where your extra money goes" card shows. Tap it.
   2. **Your plan:** the figure matches the Budget tab's "Set aside". The split adds up. Open
      "Change": with no buffer goal yet, the sheet says saving keeps the buffer as a goal. Save,
      and the Goals tab shows an Emergency fund goal.
   3. **Your debts:** give a debt a rate, and it moves into the order with a payoff month. A debt
      with no rate goes last with "Add the interest rate". Switch to "Smallest balance first":
      before the first save the choices sheet opens instead; after it, the switch saves at once.
   4. **Update balance:** an unchanged amount keeps Save off. A new amount re-dates the balance
      ("Balance you updated, <today>").
   5. **Link** a debt to a credit-card account. Import a statement for that account with a
      "New balance" line, closing after the debt's balance date. The check screen asks to update
      it. Leave it off, and nothing changes. Turn it on and save: the summary says the balance was
      updated, and Your debts says "Balance from a statement".
   6. **Goals:** each goal given a share says "Your plan suggests …".
   7. **Budget:** "See where it goes" under Set aside opens the plan. **Home → Debts:** "See your
      payoff plan" opens Your debts, and Back returns to Debts.
   8. Check light and dark themes, a large text size, and VoiceOver/TalkBack. Each debt and each
      split row reads as one sentence, and "Update balance" is an action.

## Acceptance criteria (the ticket's app part)

| Criterion | Status |
|---|---|
| "Your plan": the money left over, the split, the four choices each with its suggestion and one line of why, the disclaimer link | **Met:** `PlanScreen` / `PlanView`. The disclaimer's text is on the page rather than behind a link; see Deviations. |
| Debts screen: payoff order with month and interest; interest saved; debt-free date; the switch with snowball's extra cost; per debt edit, update balance, link, and last updated with its source | **Met:** `DebtsScreen` / `PlanView`, `YourPlan.debtRows`, `DebtEdit.sourceLine` |
| A debt with no rate goes last with "Add the interest rate" | **Met:** `YourPlan.debtRows`, tested |
| A balance older than 45 days and not from a statement shows a reminder | **Met:** `DebtEdit.staleLine` shows the server's `balance_stale` |
| Entry points: Home card, Budget Savings link, Goals' suggested amount | **Met** on both apps |
| Balances from statements: the phone reads the balance, the person confirms it, the save carries it | **Met:** `StatementBalance`, the check screen's card, `rowsToSave`, tested |
| Shared models, repositories and folds; every string in `i18n`; every Swift initializer of changed shared types updated | **Met:** 118 keys; the `SaveOutcome` initializer updated; the iOS build passes |
| Android and iOS match | **Met** as built. Check by hand (step 5). |
| `ktlintCheck`, the Android build and tests, and the iOS `xcodebuild` pass | **Met locally** (above) |

## Deviations / decisions

1. **One view model and route for both screens.** On Your debts, the strategy switch has to open
   Your plan's choices editor before the first save (decision 2 below), and both screens read the
   same plan. Back on Your debts returns to Your plan, unless the person came straight from Home's
   Debts screen.
2. **The switch never makes the first save.** The first save of the choices keeps the buffer as an
   Emergency fund goal. The ticket says that goal is made only when the person confirms it, so
   before the first save the switch opens the editor with the new strategy chosen. After the first
   save, it saves only the strategy (`YourPlan.strategyOnly`).
3. **The disclaimer is shown on the page, not behind a link.** Goals already shows it this way. The
   plan's own "guidance, not financial advice" line is always there, with the region's text from
   `/legal/disclaimer` under it.
4. **Debts can't be added or deleted from Your debts.** The setup wizard adds them. `POST` and
   `DELETE /debts` exist on the server, but the ticket's screen lists editing, updating the
   balance and linking. This is a follow-up.
5. **"Update balance" refuses the same amount.** The server keeps the date of an unchanged balance,
   so re-entering it wouldn't clear the stale reminder. It now says so instead of appearing to
   work.
6. **The balance question shows only when the server would apply it:** there's a debt linked to
   the account, and the statement closed after its balance date. A screenshot never has one, and a
   credit balance isn't offered.
7. **Home's card is always this month's plan**, whatever month Home is showing. The plan is about
   the money still to come.
8. **Entry points stay hidden until capabilities are read**, as with the gated tabs. If the feature
   turns off while the plan is open, the app goes home.
9. **Built in the app's style** (decision 5). There were no mockups: it uses the Goals tab's field
   and sheet, light and dark tokens, native sheets and controls, safe-area insets, a decimal
   keyboard with Done, and single-sentence accessibility.

## Open questions / follow-ups

- **Depends on backend PR #78** being merged, and on production being migrated to `f2a9c47e1b05`.
  Until then the plan's entry points stay hidden.
- **The balance reader is a pattern match.** Try it on real card statements, and tell me any label
  it misses ("Total new balance", a French statement, and so on).
- Adding and deleting debts from Your debts (see Deviation 4).
- A server-side way to confirm an unchanged balance as still current (see Deviation 5).
