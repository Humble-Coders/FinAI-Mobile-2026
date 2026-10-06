# Handoff — ticket #46

**Ticket:** [#46](https://github.com/Humble-Coders/FinAI-Mobile-2026/issues/46) — [M4] Show the health score, budget, spending and data freshness on Home

## Summary

Home now reads backend #58's new `/dashboard` fields and draws four M4 sections on Android and iOS:

- **A freshness line** under the hero figure: "As of Oct 2, 2026 · last import 3 days ago". When the newest transaction is more than 30 days old, it becomes a glass pill nudging the person to import, and opens the import.
- **The Money Health Score card** on the green field: a native ring, the change since last month, and a note when the score is held. Tapping it opens a breakdown sheet read from `GET /health-score`, where a part the server couldn't score says "Not counted yet".
- **"This month's budget"** on the sheet: the totals and the four lines over or nearest their allocation. Over-budget lines are marked in words and with an icon. "See budget" opens the Budget tab from #47.
- **"Where it went":** spending by category, the top five, with "Show all".

While the household is still learning, one "Still learning" card replaces the score and the budget, and spending by category still shows.

All wording lives in shared code (`usecase/HomeInsights.kt`), and both apps call it. It orders, selects and words what the server sent, and computes no figure. A gated section is drawn only when the capability is on **and** the field is present, so an older server draws Home as before. The eye toggle masks every new amount; the score isn't money and stays visible.

## Files changed

**Shared (`sharedLogic`)**

| File | Why |
|---|---|
| `model/Dashboard.kt` | `spendByCategory`, `budget`, `healthScore`, `asOf` and `learning`, all defaulted, so older payloads decode. |
| `model/HealthScore.kt` (new) | `CategorySpend`, `DataFreshness`, `ScoreNotice`, `DashboardScore`, plus `HealthScore` and `ScoreComponent` for the breakdown. A component's `inputs` is ignored, because no sentence needs it. |
| `model/Budget.kt` | `BudgetLine.over`: nullable, sent on `/dashboard`'s budget only. |
| `repository/HealthScoreRepository.kt`, `data/KtorHealthScoreRepository.kt` (new) | `GET /health-score`, with `@Throws` on the suspend function. |
| `usecase/HomeInsights.kt` (new) | **Sections:** `sections(…)` and `sectionsNow(…)` produce `HomeSections` (freshness, learning, score card, budget card and spending card). **Breakdown:** `breakdown(…)`. **Gating:** constants for the two feature keys, the top-five and four-line limits, and the 30-day staleness threshold. |
| `i18n/Strings.kt`, `i18n/EnglishStrings.kt` | 43 `home_*` strings: the section titles, freshness wording, score labels and accessibility text, the breakdown, one title and two sentences per score part, and the disclaimer. Retry and the budget's line wording reuse existing keys. |
| `commonTest/.../HomeInsightsTest.kt` (new) | 20 tests, decoding real `/dashboard` and `/health-score` JSON. |

**Android**

| File | Why |
|---|---|
| `ui/dashboard/HomeInsightsViews.kt` (new) | **Composables:** `FreshnessText`, `ScoreFieldCard` (with a `CircularProgressIndicator` ring), `BudgetSection` (with `LinearProgressIndicator` bars), `SpendingSection`, and `ScoreBreakdownSheet` (a `ModalBottomSheet`). **Actions:** the `InsightActions` holder. |
| `ui/dashboard/DashboardScreen.kt` | **In the field:** the freshness line under the hero, and the learning card or the score card after the chart. **In the sheet:** the budget and spending sections above the recent rows. **Also:** the breakdown sheet; `Accent` is now `internal` so the new file can reuse it. |
| `ui/dashboard/DashboardUiState.kt` | **New state:** `capabilities`, `spendingExpanded`, and the breakdown's open, data, loading and failed fields. **Derived:** `sections` and `breakdownView`. |
| `ui/dashboard/DashboardViewModel.kt` | Keeps the capabilities the dashboard already fetched; a failed read keeps the last ones. Adds a `HealthScoreRepository`, `openBreakdown`, `closeBreakdown`, `retryBreakdown` and `toggleSpending`, and re-reads the month on `BudgetChanged`. |
| `ui/dashboard/DashboardRoute.kt`, `navigation/AppNavigation.kt` | `onOpenBudget`, passed only when the bar has a Budget tab. |
| `test/.../DashboardInsightsTest.kt` (new) | 7 view model tests. |

**iOS**

| File | Why |
|---|---|
| `ui/HomeInsightsViews.swift` (new) | `FreshnessText`, `ScoreFieldCard` (with a system `Gauge`, `.accessoryCircularCapacity`), `BudgetSection` (with `ProgressView` bars), `SpendingSection` and `ScoreBreakdownSheet`. |
| `ui/DashboardView.swift` | The same placement as Android, plus a `.sheet` for the breakdown. `Accent` is no longer `private`. |
| `viewmodel/DashboardViewModel.swift` | The same state and actions as Android, a `BudgetChanged` observer, and `sections` and `breakdownView` calling `HomeInsights`. |
| `navigation/RootView.swift` | `onOpenBudget`, only when `features.showsBudget`. |
| `viewmodel/MoneyDetailViewModel.swift`, `viewmodel/BudgetViewModel.swift` | Each Swift `Dashboard(...)` and `BudgetLine(...)` call now passes the new arguments, because SKIE requires every one. |

## How to test

1. Run `./gradlew ktlintCheck :androidApp:assembleDebug :androidApp:testDebugUnitTest :sharedLogic:testAndroidHostTest`. Expect 552 shared tests passing, including `HomeInsightsTest` (20), and the Android tests passing, including `DashboardInsightsTest` (7).
2. Build iOS:
   ```
   cd iosApp
   xcodebuild -workspace iosApp.xcworkspace -scheme iosApp -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' build
   ```
   It should end with `BUILD SUCCEEDED`.
3. **On a device or simulator** (not done here; see below):
   - Sign in with a household past the threshold. Import a synthetic CAD statement covering a full past month, with at least 20 rows.
   - Check that Home shows the freshness line, the score card, the budget section and "Where it went".
   - Tap the score card, check the breakdown, then close it.
   - Tap "See budget", which should open the Budget tab.
   - Tap the eye, and check every new amount is masked while the score stays visible.
   - Use a fresh household with fewer than 20 rows, and check a single "Still learning" card with spending still shown.
   - Check light and dark mode, the largest font size, a small and a large phone, and landscape.

## Acceptance criteria

| Criterion | Status |
|---|---|
| Past the threshold, Home shows the freshness line, the score card, the budget section (totals, up to 4 lines, over lines marked) and "Where it went" (top 5, expandable), matching on both platforms | ✅ Built on both platforms from the same `HomeInsights` output; ⏳ **not run on a device or simulator** |
| Tapping the score card opens a breakdown: each part with its score and a plain sentence, the formula version and the disclaimer; unavailable parts read "not counted yet", not zero | ✅ Met (`the_breakdown_lists_every_part_and_never_shows_an_unscored_one_as_zero`) |
| Below the threshold, one "Still learning" card replaces score and budget, with correct progress; spending still shows | ✅ Met. It reuses #47's `LearningFieldCard`; tested by `learning_replaces_…` and `one_learning_card_even_if_a_score_still_arrives`. |
| Against a server without the new fields, Home looks exactly as today | ✅ Met in test (`a_payload_from_before_m4_draws_nothing_new`): every section is null. Production already serves 4.3, so this case is covered by the test only. |
| A feature disabled in capabilities hides its section even when the field is present | ✅ Met. Unknown capabilities hide both too. |
| The eye toggle masks every new money figure | ✅ Met (`hidden_amounts_mask_every_budget_figure`, `hidden_amounts_mask_spending_too`); the score stays visible. |
| No figure is computed on the client | ✅ Met; see the arithmetic notes under Deviations. |
| A dataset more than 30 days stale shows the line as a nudge to import | ✅ Met. The boundary is tested: 30 days isn't stale, 31 is. |
| Every new string is in `i18n`, with no literals in platform code | ✅ Met. Platform code uses only `Strings` keys; the only literals are icon names. |
| `ktlintCheck`, Android build and tests, and iOS `xcodebuild` pass | ✅ Met locally. iOS was built before main was merged in; that merge added only Kotlin tests and a handoff. |

Each shared rule was also checked by breaking it, and a test failed every time. The rules broken were: ungating the score, the staleness boundary, a score beside the learning card, an unscored part shown as zero, unordered budget lines, and unmasked amounts.

## Deviations / decisions

- **Built on #47's work (PR #50, merged during this ticket) rather than duplicating it:**
  - the ordering for Home (`BudgetEdit.forHome`);
  - `LearningFieldCard`;
  - `BudgetChanged`;
  - the `Budget` and `LearningProgress` models.

  So **"See budget" is live** and opens the Budget tab, rather than staying hidden until 4.5 as the ticket expected.
- **Score sentences describe what a part measures, without a figure.** The ticket's example, "You kept 12% of your income", needs a rate the server doesn't send, and the app may not calculate one. Each sentence states what the part measures (for example, "Keeping 20% or more counts as full marks"), and the row shows the server's 0–100 score.
- **Arithmetic, all presentation:**
  - The score's change is `score − previous_score`, two integers the server sent; the ticket lists the change label among the allowed folds.
  - Bar lengths are ratios: `BudgetLine.fraction` from #47 and `score / 100`.
  - "Over by" uses the server's `over`. `overBy` is only a fallback for budgets without that field.
- **Capabilities come from Home's own existing fetch.** The dashboard already read them on every load for the locale. They're now kept in state rather than taken from the navigation's `FeaturesViewModel`. A failed read keeps the last payload.
- **The held score (backend #62)** shows a note on the card: "Sep isn't imported yet · score from Sep 15, 2026". It's worded from the notice's `code`, not the server's English `message`.
- **When the server is ready but sends no score** (nothing scorable, or nothing to hold), no score card is drawn.
- **iOS ring:** a system `Gauge` with `.accessoryCircularCapacity`, enlarged slightly with `scaleEffect(1.15)`. It's native, so there's no custom drawing.

## Open questions / follow-ups

- **Not run on a device or simulator** (standing rule). Every visual check in the ticket's UI standards still needs doing by hand: both themes, the largest font size, the smallest and largest phones, landscape, and each state.
- **The part sentences are tied to formula v1** (for example, "20% or more"). When the formula changes to v2, these strings need revisiting along with it.
- **iOS `Gauge` at the largest Dynamic Type sizes:** worth a look on a device, since that style is designed for small sizes.
