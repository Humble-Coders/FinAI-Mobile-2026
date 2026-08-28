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
| **M3** | Money in | Upload a statement and see correctly categorized transactions | F2, F3 |
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

## M2 — Onboarding (F1)

| # | Ticket | Repo |
|---|---|---|
| 2.1 | Signup + **region resolution** — two paths: libphonenumber with `+1` disambiguation for phone signups, **device signals** (SIM country, timezone, OS region, storefront, IP) with one-tap confirmation for Google/Apple. Settings override; signup never hard-blocked on region | backend |
| 2.2 | Financial setup wizard persistence — income, debts, investments, obligations | backend |
| 2.3 | Signup UI — phone OTP, Google, Apple — plus the one-tap region confirmation. **Lazy phone prompt** for social signups (never blocking) | mobile |
| 2.4 | Financial setup wizard UI (skippable) | mobile |

## M3 — Money in (F2, F3)

| # | Ticket | Repo |
|---|---|---|
| 3.1 | Document upload endpoint → Storage → queue enqueue; returns `queued` immediately | backend |
| 3.2 | Extraction pipeline: document-AI table extraction → **redaction** → LLM normalization of ambiguous rows | backend |
| 3.3 | Dedup (DB constraint + fuzzy near-match) + categorization on `(merchant, amount)` only + confidence flags | backend |
| 3.4 | Review queue endpoints + correction learning (per-household rules, never cross-user) | backend |
| 3.5 | Source-document deletion job — on user confirmation or 72h, whichever first | backend |
| 3.6 | Upload + processing-status UI (poll/observe `document_upload.status`) | mobile |
| 3.7 | Transaction review and correction UI | mobile |

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

## M7 — Monetization (F9)

| # | Ticket | Repo |
|---|---|---|
| 7.1 | Entitlement enforcement on every gated endpoint; upload and chat quotas | backend |
| 7.2 | Billing integration | backend |
| 7.3 | Paywall and plan UI | mobile |

---

## Process

- **M1** — technical enablement; `/draft-ticket` directly, no brief.
- **M2–M7** — each starts with `/draft-brief` (product intent), then `/read-brief` → `/draft-ticket` → `/review-ticket` → `/start-ticket` → `/handoff` → `/manager-review`. See `docs/PROCESS.md`.

Mobile tickets are not done until **Android compiles + tests pass AND the iOS workspace `xcodebuild` succeeds** (`CLAUDE.md`).

## Deferred

Web client · everything in PRD Phase 2 (bank linking, subscriptions, debt optimizer, safe-to-spend, alerts, weekly tasks) and Phase 3 (splits, challenges, simulator, investments, tax module, education hub, Family Plan).
