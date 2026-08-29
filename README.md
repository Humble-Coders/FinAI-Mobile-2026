# FinAI — Mobile

Kotlin Multiplatform client for **FinAI**, an AI money-coach platform. Users upload
bank statements; the backend extracts and categorizes transactions, generates budgets
from real behaviour, tracks goals and debt, scores financial health, and answers
questions through a chatbot grounded in their actual numbers.

**Shared Kotlin logic, native UI on both platforms** — Jetpack Compose on Android,
SwiftUI on iOS, bridged by SKIE. There is no shared UI module.

- Product spec: [`docs/PRD.md`](docs/PRD.md)
- Architecture and rules: [`CLAUDE.md`](CLAUDE.md) — read before writing code
- How we work: [`docs/PROCESS.md`](docs/PROCESS.md) · Roadmap: [`docs/ROADMAP.md`](docs/ROADMAP.md)

## Modules

| Module | What it holds |
|---|---|
| `sharedLogic` | The brain — `model/`, `repository/`, `usecase/`, `data/`, `i18n/`, `config/`, `util/` |
| `androidApp` | Compose UI, `ui/<feature>/` per screen |
| `iosApp` | SwiftUI views and view models |

## Requirements

- **JDK 21** (Azul) — pinned in `gradle/gradle-daemon-jvm.properties`; Gradle downloads it automatically on first build
- Android SDK (compileSdk 36, minSdk 24)
- **Xcode and CocoaPods** for the iOS app

## Setup

Create `local.properties` at the repo root (untracked):

```properties
sdk.dir=/path/to/Android/sdk

# Optional — the build generates REPLACE_ME placeholders when these are absent
supabase.url=https://YOUR_PROJECT.supabase.co
supabase.anonKey=YOUR_PUBLISHABLE_KEY
```

Only the **publishable** (anon) key belongs here. The `service_role` key must never
enter this repo. `SupabaseConfig.kt` is generated from these values at build time,
never committed.

## Building

```bash
./gradlew :androidApp:assembleDebug     # Android app
./gradlew :sharedLogic:allTests         # shared tests, Android + iOS targets
```

iOS:

```bash
./gradlew :sharedLogic:generateDummyFramework
cd iosApp && pod install
xcodebuild -workspace iosApp.xcworkspace -scheme iosApp \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build
```

## The rule that matters most

**Clients never talk to the database.** Supabase is used for **Auth and Storage only**;
all business data goes over Ktor to the backend API, which is the single place
authorization, entitlement gating and audit logging live. Postgrest and Realtime are
deliberately not installed.

**A change touching `sharedLogic` is not done until Android compiles and tests pass
*and* the iOS workspace builds** — SKIE breakages surface only in the iOS build.
