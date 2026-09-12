# FinAI — Mobile

[![CI](https://github.com/Humble-Coders/FinAI-Mobile-2026/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Humble-Coders/FinAI-Mobile-2026/actions/workflows/ci.yml)

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

# Google sign-in. Created in Google Cloud, then added to the Supabase Google
# provider's Authorized Client IDs. Not secrets — a client id ships inside every
# app — but they differ per environment, so they are not committed.
#
# webClientId is what Android sends as the serverClientId, and what Supabase
# validates the token's audience against. The ANDROID client id is never used in
# code: it exists so Google can match the app's signing certificate, and needs
# your debug SHA-1 registered against it.
google.webClientId=YOUR_WEB_CLIENT_ID.apps.googleusercontent.com
google.iosClientId=YOUR_IOS_CLIENT_ID.apps.googleusercontent.com
```

Until the Google ids are set, the provider buttons say so rather than failing
oddly. Sign in with Apple needs no id here — it validates against the bundle id
(`com.humblesolutions.finai`), which must be registered on the App ID with the
Sign in with Apple capability.

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
./gradlew :sharedLogic:podInstall   # placeholder framework + podspec + pod install
cd iosApp
xcodebuild -workspace iosApp.xcworkspace -scheme iosApp \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build
```

`SharedLogic.podspec` is generated, not committed — `podInstall` writes it before
running `pod install`. The real framework is built during `xcodebuild`, by a script
phase in that podspec, which is where SKIE runs.

## CI

Every pull request to `main`, and every push to it, runs `.github/workflows/ci.yml`:

| Job | Runner | Runs |
|---|---|---|
| **Android** | Linux | `:androidApp:assembleDebug`, `:sharedLogic:allTests` |
| **iOS** | macOS | the shared tests on the iOS simulator, then `podInstall` and `xcodebuild` |

The iOS job is the one that matters for SKIE: a change Swift can see but Kotlin
cannot — a new parameter with a default, a renamed member — compiles on Android and
fails only there. No secrets are needed; nothing is signed or published.

The toolchain is pinned, never inherited from the runner image: the JDK comes from
`gradle/gradle-daemon-jvm.properties`, and the macOS image and Xcode version are set
at the top of the iOS job. Bump Xcode there, deliberately, when the team moves.

## The rule that matters most

**Clients never talk to the database.** Supabase is used for **Auth and Storage only**;
all business data goes over Ktor to the backend API, which is the single place
authorization, entitlement gating and audit logging live. Postgrest and Realtime are
deliberately not installed.

**A change touching `sharedLogic` is not done until Android compiles and tests pass
*and* the iOS workspace builds** — SKIE breakages surface only in the iOS build.
