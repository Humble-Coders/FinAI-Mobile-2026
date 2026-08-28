# FinAI — Mobile (KMP)

## What this project is

FinAI is an AI-powered personal-finance platform — a **money coach and decision-support system**, not an expense tracker. Users upload bank statements and screenshots; the backend extracts and categorizes transactions, generates budgets from real behaviour, tracks goals and debt, scores financial health, and answers questions through an AI chatbot grounded in the user's actual numbers. This repo is the **mobile client**: Kotlin Multiplatform shared logic with **native UI on Android (Compose) and iOS (SwiftUI)**. The web client is a separate React app; the backend is a separate service. Full product spec: `docs/PRD.md`.

**Positioning constraint that shapes UI copy everywhere:** this is *educational guidance*, **not regulated financial advice**. Never write copy that gives individualized investment recommendations. Projections carry disclaimers.

---

## Architecture

Follow **`/humble-task-force:kmp-arch-v2`** — it is the authority on structure and conventions. Invoke it before writing shared code. This file records what is *specific to FinAI*.

```
Android (Compose)        iOS (SwiftUI)
ui/<feature>/            ui/ + viewmodel/
  Screen + VM + UiState    View + VM
        └──────────┬──────────┘
        ┌──────────▼───────────────────────┐
        │ sharedLogic (KMP) — the brain     │
        │ model/ repository/ usecase/ data/ │
        │ i18n/ config/ util/               │
        └──────────┬───────────────────────┘
                   │ HTTPS (Ktor)
        ┌──────────▼───────────────────────┐
        │ Render API  ←→  Supabase (DB)     │
        └──────────────────────────────────┘
```

**No shared UI. No expect/actual. No DI framework. No singleton repos.**

### Platform scope (decided)
- **In scope: Android + iOS.** Both ship; feature parity is the default.
- **Desktop is explicitly out of scope for this repo** — the web experience is the separate React app. This is a recorded decision, not a silent skip.
- **Done-rule:** a change touching `sharedLogic` is not done until **Android compiles + tests pass AND the iOS workspace `xcodebuild` succeeds**. SKIE breakages only surface in the iOS build. State both results in the PR.

### Module layout
```
sharedLogic/src/commonMain/kotlin/com/humblesolutions/finai/
├── model/       # data classes + derived logic + blocking reasons + folds/projections
├── repository/  # interfaces only
├── usecase/     # operations (@Throws) + pure engines (no repos)
├── data/        # Ktor impls against the Render API; Supabase Auth/Storage wrappers
├── i18n/        # Strings.kt (keys) + EnglishStrings.kt (values)
├── config/      # base URLs, constants, reserved ids
└── util/        # Money, dates, pure helpers

androidApp/src/main/kotlin/com/humblesolutions/finai/
├── MainActivity.kt  navigation/  util/  data/   ui/<feature>/  ui/components/  ui/theme/
iosApp/iosApp/
├── iOSApp.swift  config/  navigation/  viewmodel/  ui/  repository/
```

Because our backend is **HTTP + JSON**, essentially every repository is a **shared Ktor implementation in `sharedLogic/data/`** — implemented once, not per platform. The per-platform `data/` and `repository/` folders exist only for genuine native-SDK work: document/file pickers, camera, push tokens, biometrics.

### The network boundary — non-negotiable

- **The client NEVER talks to the database.** No `postgrest-kt`, no `realtime-kt`, no SQL, no direct table access. Authorization, entitlement gating, region gating and audit logging live in the Render API, in one enforceable place.
- The client uses Supabase for **exactly two things**: **Auth** (session + JWT) and **Storage** (document upload). Nothing else.
- All business data flows over **Ktor → Render API**, with the Supabase JWT as the bearer token.
- The scaffolding currently in the repo (`config/Supabase.kt` exposing `postgrest`/`realtime`, `repository/AuthRepository.kt`, the `sharedUI` module) is **disposable placeholder** from project generation — treat it as a clean canvas, not precedent. First ticket removes `sharedUI`, drops `postgrest-kt`/`realtime-kt` from the version catalog, and adds SKIE.

### `bind(session, config)`

VMs are constructed empty and wired in `bind`. For FinAI:
- **session** = the Supabase session (uid + JWT).
- **config** = the **capabilities payload** (`region`, `currency`, `locale`, `features`) the API returns after login — see PRD §4.6.

`bind` early-returns when uid **and** capabilities are unchanged; on a real change it rebuilds repos/use cases fresh and resets state. A plan upgrade or region change must never replay stale gating.

---

## Key rules

### Money
- **Money is a decimal string** end to end in Kotlin and Swift, through the shared `Money` util. Never `Double`. Never `Long` cents in client code.
- **API JSON also carries decimal strings.** The Render backend converts to/from integer minor units for Postgres storage — that conversion is the backend's job and happens only there.
- `Money.subtract` clamps at zero — correct for prices, **wrong for signed balances**. Use `signOf`/`abs`/`compare`/`signedSubtract` for anything that can go negative (debt balances, budget overruns, net worth).
- Normalize before comparing (`"1200" == "1200.00"`); blank/unparseable reads as `"0"`, never a crash.

### Who computes what
The PRD puts authoritative financial math on the server; the KMP guide puts decisions and projections in shared. Both hold, at this boundary:
- **The client never recomputes a number the server is authoritative for** — health score, budget allocations, goal projections, debt schedules, spending aggregates. Render computes them; the client displays them.
- **Shared logic owns everything else that could differ between platforms:** input validation, blocking reasons, folds/projections of API responses into screen state, filter/facet engines, formatting, category rollups for display.
- If Android and iOS could ever disagree about a label, a gate, or a derived figure, that code belongs in `sharedLogic`.

### Blocking reasons & no silent defaults
- Every refusable action (confirm a budget, create a goal, submit an upload) exposes **one** shared function returning a typed reason-or-null. The disabled button, the inline notice, and the use case's thrown error all read that same function.
- Money-critical inputs start **unanswered**, not defaulted: account selection, transaction dates, category assignment on corrections. Tri-state nullable in UI state; narrow to wire defaults only at the boundary.

### Strings
All user-facing text lives in `sharedLogic/i18n/`. **No string literals in Android or iOS code.** Region-specific copy (tax account names, disclaimers) comes from the **capabilities payload**, not from Kotlin constants — see below.

### Region & feature gating
- The API returns a capabilities payload; **clients render what it says**. No `if (country == "CA")` anywhere in client code, ever.
- The payload controls what is *shown*; the API independently enforces what is *allowed*. Never assume a hidden feature is a secured feature.
- **Never hard-block signup on region.** Features are gated; account creation is not.

### SKIE / Swift bridge
- `@Throws` on **every** public shared `suspend fun` that can throw — an undeclared Kotlin exception kills the iOS process. Keep the `ThrowsAnnotationGuardTest` (jvmTest source scan) green. List and rethrow `CancellationException`.
- Swift passes **every** argument (no Kotlin defaults surface) and cannot use `copy()`. Adding a field to a shared data class breaks every Swift initializer call site — grep for them in the same change.
- Shared test names must be **comma-free** (backticked names with commas fail native targets).

### Security & privacy (finance app — these are not optional)
- Only the Supabase **anon/publishable key** ships in the client. The **`service_role` key must never appear in this repo** in any form.
- Supabase URL/anon key and the API base URL come from **build config** (Gradle/`local.properties`, `Config.xcconfig`), not hardcoded source constants. No secrets committed, ever.
- **No document bytes, raw statement text, or unredacted account numbers in logs, crash reports, or analytics** — we tell users and regulators that documents are deleted; leaked copies in logs are the violation. See PRD Appendix A.
- Express consent screen before the first document upload. Consent events are logged server-side with the policy version.
- No PII in analytics events.

### Data & caching
- **Bounded** collections (categories, accounts, goals, budgets) — load once per bind, filter/search client-side against the cache via shared engines.
- **Unbounded** (transaction history) — server-side query objects in shared, cursor paging. A sort-direction flip is a **server re-walk**, never a reversal of loaded pages.
- Never zero out displayed figures on a failed refresh — stale-and-labelled beats a fabricated zero.
- Long-running work (statement extraction) is **asynchronous on the backend**. The client uploads, gets `status: queued`, and polls/observes upload status. Never block UI waiting for extraction.

### DON'T
- No `postgrest`/`realtime`/direct DB access from the client.
- No shared UI, no expect/actual, no DI framework, no singleton repos.
- No `Double` for money; no `Money.subtract` on signed balances.
- No user-facing string literals in platform code.
- No per-country branches in client code.
- No recomputing server-authoritative figures on the client.
- No swallowing `CancellationException`.
- No copy that reads as individualized financial advice.

---

## How we work (ticket workflow)

Process doc: `docs/PROCESS.md` (created by `/humble-task-force:setup-tickets`).

| Command | When |
|---|---|
| `/humble-task-force:draft-ticket <thing>` | Manager drafts a ticket from the PRD |
| `/humble-task-force:start-ticket` | Developer picks it up — context, plan, then code |
| `/humble-task-force:handoff` | Developer generates the handoff report from the real diff |
| `/humble-task-force:manager-review` | Manager reviews PR + handoff against acceptance criteria |
| `/humble-task-force:kmp-arch-v2` | **Invoke before writing shared code** |

### Adding a feature — order
1. `sharedLogic/model/` — models, derived logic, blocking reasons
2. `sharedLogic/repository/` — interface
3. `sharedLogic/data/` — Ktor impl against the Render API
4. `sharedLogic/usecase/` — use case, `@Throws` on public suspend funs
5. `sharedLogic/i18n/` — string keys + English values
6. `commonTest` — engines, folds, blocking reasons, money edge cases
7. Android: `ui/<feature>/` VM + UiState + Screen
8. iOS: `viewmodel/` + `ui/`
9. Navigation wiring per platform
10. **Verification matrix: Android build + tests, iOS `xcodebuild`**

---

## References

- `docs/PRD.md` — product spec, architecture decisions, decision log, compliance appendix
- `/humble-task-force:kmp-arch-v2` — KMP architecture guide (authoritative for structure)
- Backend: Render API service (separate repo) · Supabase project (Postgres, Auth, Storage, Queues, pgvector)
- Web client: separate React app
