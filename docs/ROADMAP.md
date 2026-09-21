# FinAI — Roadmap (v1)

Milestone and ticket breakdown of **PRD Phase 1** (the core money loop). Milestones map to the `[M?]` prefix in ticket titles.

**Scope of v1:** Android + iOS, built together, against the FastAPI backend. The React web client is **deferred** — it follows once the API has been proven by the mobile build (PRD §4.1).

**Repos:** `FinAI-Mobile-2026` (KMP mobile) · `Finance-backend` (FastAPI + Supabase)

---

## Milestones

| | Milestone | Done when you can… | PRD |
|---|---|---|---|
| **M1** | Foundation | A signed-in user exists with a household, and both clients reach the API | §4.2, §4.4 |
| **M2** | Onboarding | Sign up in ~30s and complete the financial setup wizard | F1 |
| **M3** | Money in | Import a statement and see correctly categorized transactions | F2, F3 |
| **M4** | Money understood | See a dashboard with an auto-generated budget and health score | F4, F6 |
| **M5** | Goals | Create and track short- and long-term goals | F5 |
| **M6** | AI coach | Ask the chatbot about real spending and get grounded answers | F7, F8 |
| **M7** | Monetization | Hit a paywall and upgrade | F9 |

Milestones are ordered by dependency, not preference. **M3 is the load-bearing one** — every downstream feature is only as good as the transaction data it produces.

---

## M1 — Foundation

No product intent to capture here; these go straight to `/draft-ticket` without a brief.

| # | Ticket | Repo |
|---|---|---|
| 1.1 | **Database schema + first migrations.** Households, users, accounts, transactions, categories, budgets, goals, debts, document uploads, entitlements, country packs. UUID keys, household-scoped, integer minor units, source-agnostic transactions. Includes the `extraction_jobs` queue as a guarded migration — also the first real test of Alembic through the transaction pooler | backend |
| 1.2 | **Auth wiring + household bootstrap.** Verify the Supabase JWT; create user + single-member household on first authenticated call | backend |
| 1.3 | **`/capabilities` backed by the database.** Replace the stub resolver with real country packs and plan entitlements; one resolver composing plan + region + rollout flags | backend |
| 1.4 | **CI workflow.** `pip install` + `pytest` on PRs. All three deploy failures during setup were CI-catchable | backend |
| 1.5 | **Repo cleanup.** Remove `sharedUI`, drop `postgrest-kt`/`realtime-kt`, add SKIE, delete the placeholder `AuthRepository` and `Supabase.kt` postgrest exposure | mobile |
| 1.6 | **Ktor API client + repository interfaces.** The shared `data/` layer every later feature builds on; Supabase Auth + Storage only, everything else over HTTP | mobile |
| 1.7 | **Mobile CI workflow.** Android build + shared tests on Linux, and **`xcodebuild` on macOS for every PR** — SKIE breakages surface only in the iOS build, so nothing else enforces the two-platform commitment | mobile |
| 1.8 | **Migration testing in CI.** Every migration applied, reversed and re-applied against a real Postgres, plus the database-backed tests, as their own CI job | backend |

M1 follow-ups with no roadmap ticket: [Finance-backend#23](https://github.com/Humble-Coders/Finance-backend/issues/23) (DSN diagnostic, 401 test, CI hardening) · [#14](https://github.com/Humble-Coders/FinAI-Mobile-2026/issues/14) (Kotlin linting, SHA-pinned actions). The rest are carried into the tickets below.

## M2 — Onboarding (F1)

| # | Ticket | Repo |
|---|---|---|
| 2.1 | Signup + **region resolution** — one path: every route ends with a verified phone, so region derives from it via libphonenumber with `+1` area-code disambiguation. Settings override; signup never hard-blocked on region. Also records **signup consent** with its policy version (PRD Appendix A.5 #1) | backend |
| 2.2 | Financial setup wizard persistence — income, debts, investments, obligations | backend |
| 2.3 | **Splash screen** + signup UI — phone OTP, Google, Apple. Social routes continue straight into the **phone + OTP step**; timezone/locale pre-select the country code. Region and consent steps | mobile |
| 2.4 | Financial setup wizard UI — the mandatory steps (income, monthly expense) cannot be skipped; the optional ones can (2.5) | mobile |
| 2.5 | **Mandatory financial setup** — adds monthly expense; income + monthly expense become an onboarding step (`financial_setup`) enforced server-side, alongside phone and region. Debts, investments and itemised obligations stay optional and editable later from the profile | backend + mobile |

**Carry-over from M1** — fold these into the ticket when drafting it.

- **2.1** — Region is still unset: the backend leaves `household.country_code` NULL even when a phone is present. Until it is set, `/me` and `/capabilities` disagree about onboarding — `/me` keys `onboarding_required` off `user.phone`, `/capabilities` off `country_code` — so a user with a verified phone is "done" by one and "needs phone" by the other. Check that the fictional 555-01xx test numbers pass libphonenumber, or test sign-ups never get a region. Once region resolves, Canadian users receive `disclaimer_version: ca-v1`, which no `disclaimer_version` row backs yet — seed it before any screen shows a disclaimer. *(backend `handoffs/ticket-11.md`, `ticket-12.md`; mobile `ticket-6.md`)*
- **2.3** — Remove the ticket-6 demo: `ApiDemoScreen` / `ApiDemoViewModel` / `ApiDemoUiState` (Android), `ApiDemoView` / `ApiDemoViewModel` (iOS), the two demo aids on `SupabaseAuthRepository` (`expireAccessTokenForTesting`, `tokenSecondsLeftForTesting`), and the now-unused `ContentView.swift` and `PlaceholderScreen.kt`. iOS view models must store their `Task`s and cancel them on unbind; the demo does not, and must not be copied. *(mobile `handoffs/ticket-6.md`, PR #11 review)*

## M3 — Money in (F2, F3)

**Re-cut 2026-09-21** after the decision to extract on the device (PRD §9). The document is never uploaded: the apps pull the text out locally, redact it locally, and post a few KB of redacted text to the API, which does the LLM row structuring, dedup and categorization inside the request. Gone with that: the Supabase Storage bucket, the `pgmq` queue, the Render worker, any document-AI vendor, and the 72-hour deletion job.

| # | Ticket | Repo |
|---|---|---|
| 3.1 | **Statement parse endpoint.** Takes redacted statement text, returns structured rows (date, description, amount, direction) with a confidence each; LLM called server-side only. Chunked so a long statement cannot monopolize a connection; quota-enforced (free tier: 1 import/month) | backend |
| 3.2 | **On-device extraction, shared redactor and the parse repository.** PDF text layer (PDFKit / PdfBox-Android) with on-device OCR fallback (Apple Vision / ML Kit bundled), password-protected PDFs, and the `sharedLogic` redactor that drops names, addresses, balances and all but the last 4 of an account number before anything is sent | mobile |
| 3.3 | Persist, dedup (DB constraint + fuzzy near-match), deterministic `normalized_description`, seeded category taxonomy, categorization on `(merchant, amount)` only, confidence flags — **plus minimal account create/list**, without which an import has nowhere to land | backend |
| 3.4 | Review queue endpoints + correction learning (per-household rules, never cross-user) | backend |
| ~~3.5~~ | ~~Source-document deletion job~~ — **removed**: nothing is ever received, so there is nothing to delete | — |
| 3.5 | **Manual transaction entry** (new, 2026-09-21). With no vision fallback this is the only way in when a document cannot be read, so it ships in M3 rather than M4 | backend + mobile |
| 3.6 | Import + progress UI — on-device extraction progress, then the parse call and its result | mobile |
| 3.7 | Transaction review and correction UI | mobile |

**Carry-over from M1** — fold these into the ticket when drafting it.

- **3.1** — Gate with the existing `require_feature` dependency rather than new gating code; so far it is proven only on a throwaway test route. This is also the first live `403 feature_unavailable`, which the mobile client handles by tests only. *(backend `handoffs/ticket-12.md`, mobile `ticket-6.md`)*
- **3.3** — `transaction.normalized_description` is part of the dedup key, but nothing produces it yet. It must be normalised deterministically, or dedup silently stops working. *(backend `handoffs/ticket-10.md`)*

**From the 2026-09-21 decision** — fold these in too.

- **3.2** — Measure the APK/IPA size increase before committing: ML Kit's bundled model is ~4 MB per script per ABI and PdfBox-Android is not small. If it lands badly, the Play Services variant of ML Kit trades size for a first-use download and a GMS dependency.
- **3.2 / 3.6** — Express consent before the **first import** (PRD Appendix A.5 #1), covering AI processing of financial data. It is a separate consent from the account one, recorded with its policy version — the same mechanism ticket 2.1 built for signup consent.
- **3.1** — The API key stays on the server. Nothing in the mobile repo may hold an LLM credential; the apps call our endpoint (PRD §6, Secrets & config).
- **3.4 / 3.7** — With no vision fallback, the review queue catches everything the model could not resolve, not only low-confidence rows. Its volume is the quality signal for the whole feature — worth a metric from day one.
- **3.6 / 3.7 / 3.5** — **No design is provided** for the M3 screens (manager decision, 2026-09-21): build against the tokens and components M2 established. The UI standards still apply in full and are embedded in each ticket.
- **3.1 / 3.6** — **Opt-in diagnostic text** (manager decision, 2026-09-21): on a failed or heavily-flagged import only, the user may choose to send that import's redacted text, kept 30 days, so the parser can be fixed. Never automatic, never on success, consented separately, exported and deleted with the account. It is the only thing retained from an import beyond the transactions.
- **3.6** — Three error codes go live in the client for the first time: `403 feature_unavailable`, `409 consent_required` and `429 import_quota_exceeded`. Each needs its own copy; a quota limit rendered as "something went wrong" generates support mail.

## M4 — Money understood (F4, F6)

| # | Ticket | Repo |
|---|---|---|
| 4.1 | Auto budget generator from real spending history | backend |
| 4.2 | Money Health Score engine (versioned formula, snapshot history) | backend |
| 4.3 | Dashboard aggregate endpoint with cached snapshots | backend |
| 4.4 | Dashboard UI incl. data-freshness indicator | mobile |
| 4.5 | Budget screens | mobile |

## M5 — Goals (F5)

| # | Ticket | Repo |
|---|---|---|
| 5.1 | Goals CRUD + deterministic projection math (contribution, timeline, progress) | backend |
| 5.2 | Goals UI — create, edit, prioritize | mobile |

## M6 — AI coach (F7, F8)

| # | Ticket | Repo |
|---|---|---|
| 6.1 | Chatbot: **tool-calling over SQL**, guardrails (guidance-not-advice, no individualized securities advice) | backend |
| 6.2 | Merchant embedding index (pgvector) for fuzzy queries | backend |
| 6.3 | Learning-phase gating — advanced recommendations withheld until enough history | backend |
| 6.4 | Chat UI | mobile |

**Carry-over from M1** — fold these into the ticket when drafting it.

- **6.2** — pgvector is installed on production (0.8.2), but no migration enables or uses it yet. CI's Postgres image (`ghcr.io/pgmq/pg17-pgmq`, pinned by digest) has no pgvector, so the first migration that does needs an image that provides it. *(backend `handoffs/ticket-10.md`, `ticket-15.md`)*

## M7 — Monetization (F9)

| # | Ticket | Repo |
|---|---|---|
| 7.1 | Entitlement enforcement on every gated endpoint; upload and chat quotas | backend |
| 7.2 | Billing integration | backend |
| 7.3 | Paywall and plan UI | mobile |

**Carry-over from M1** — fold these into the ticket when drafting it.

- **7.1 / 7.2** — Entitlements are read but never written: every household resolves to `free`, and the paid path exists only in tests until billing creates `subscription_entitlement` rows. *(backend `handoffs/ticket-12.md`)*

---

## Before launch

Not tied to a milestone — each must be done before real users sign up.

- **SMS provider.** Development signs in with Supabase test phone numbers, and the provider credentials are placeholders. Connect a real provider **and** remove or expire every test number — a test number with a fixed code is a sign-in path for anyone who knows it. *(mobile `handoffs/ticket-6.md`)*
- **LLM tier.** Development runs on a **free-tier API key** (manager decision, 2026-09-21), whose terms generally permit the provider to train on the inputs — which Appendix A.3 forbids for this data. The free key may only ever see synthetic fixtures. **Before a single real user's statement is parsed, swap to a paid, no-training API tier** and re-read that provider's terms. The client reads provider and model from settings, so the swap is configuration, not a release.
- **Staging.** Migrations run straight against production; there is no staging database. That has been safe only because there is no user data yet. *(backend `handoffs/ticket-10.md`, `ticket-15.md`)*
- **Branch protection.** CI is advisory in both repos until `main` requires its checks: backend `test` + `database` (free — the repo is public); mobile `Android` + `iOS` (needs the repo public or a paid plan). *(mobile `handoffs/ticket-8.md`, backend `handoffs/ticket-15.md`)*

---

## Process

- **M1** — technical enablement; `/draft-ticket` directly, no brief.
- **M2–M7** — each starts with `/draft-brief` (product intent), then `/read-brief` → `/draft-ticket` → `/review-ticket` → `/start-ticket` → `/handoff` → `/manager-review`. See `docs/PROCESS.md`.

Mobile tickets are not done until **Android compiles + tests pass AND the iOS workspace `xcodebuild` succeeds** (`CLAUDE.md`).

## Deferred

Web client · everything in PRD Phase 2 (bank linking, subscriptions, debt optimizer, safe-to-spend, alerts, weekly tasks) and Phase 3 (splits, challenges, simulator, investments, tax module, education hub, Family Plan).
