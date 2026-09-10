# Handoff — ticket #8

**Ticket:** [#8 — \[M1\] Add a CI workflow for the mobile apps](https://github.com/Humble-Coders/FinAI-Mobile-2026/issues/8)
**Branch:** `ticket-8-mobile-ci` · **Base:** `main` (`8975b12`) · **Implementation:** `b068ff0`
**Verification:** green run [34480883022](https://github.com/Humble-Coders/FinAI-Mobile-2026/actions/runs/34480883022) on #12 · red checks on throwaway PR [#13](https://github.com/Humble-Coders/FinAI-Mobile-2026/pull/13) (closed, never merged)

## Summary

Every pull request to `main`, and every push to it, now runs two jobs in parallel. **Android** (Linux) builds the debug APK and runs the shared tests; **iOS** (macOS) runs the shared tests on the iOS simulator, installs pods and runs `xcodebuild` for the simulator. The iOS job is the one that matters for SKIE: a change Swift can see but Kotlin cannot compiles on Android and fails only there — verified by making exactly such a change. The toolchain is pinned rather than inherited: the JDK is read from `gradle/gradle-daemon-jvm.properties`, and the macOS image and Xcode version are fixed in the workflow. No secrets; read-only permissions.

Writing it exposed a real bug in the README: its iOS steps fail on a fresh clone, because the podspec is generated and gitignored. Fixed.

## Files changed

| File | Why |
|---|---|
| `.github/workflows/ci.yml` | The two jobs. Android: `:androidApp:assembleDebug :sharedLogic:allTests`. iOS: Xcode selected and asserted, Kotlin/Native toolchain cached, `:sharedLogic:iosSimulatorArm64Test`, `:sharedLogic:podInstall`, `xcodebuild` without signing. Concurrency cancels superseded runs on a PR but never on `main`, where each commit gets its own group so every merge is verified; each job has a timeout (iOS 20 min, about 5× a cold run); test reports upload on failure |
| `.github/actions/setup-build/action.yml` | Shared by both jobs, so the JDK pin is read in one place: maps the file's vendor to a `setup-java` distribution (an unmapped vendor fails the job), then sets up Gradle with the `basic` cache provider |
| `README.md` | CI badge and a CI section; the iOS build steps now use `:sharedLogic:podInstall` |

## How to test

1. Open any PR against `main` — both **Android** and **iOS** start automatically. On #12: Android 5m 47s, iOS 3m 33s, both cold.
2. The same steps locally, from a fresh clone (no `local.properties`):
   ```bash
   ./gradlew :androidApp:assembleDebug :sharedLogic:allTests
   ./gradlew :sharedLogic:iosSimulatorArm64Test :sharedLogic:podInstall
   cd iosApp && xcodebuild -workspace iosApp.xcworkspace -scheme iosApp -sdk iphonesimulator \
     -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
   ```
3. The red checks — see [#13](https://github.com/Humble-Coders/FinAI-Mobile-2026/pull/13): commit (a) removes `@Throws` from `KtorCapabilitiesRepository.fetch()`; commit (b) adds a defaulted constructor parameter.

## Acceptance criteria

| Criterion | Status | Evidence |
|---|---|---|
| Opening a pull request runs both jobs automatically | ✅ Met | #12 triggered run 34480883022 (`pull_request`), both jobs |
| The Android job builds the debug APK and runs `sharedLogic` tests | ✅ Met | `> Task :androidApp:assembleDebug`, `> Task :sharedLogic:testAndroidHostTest` — the 53 host tests, `ThrowsAnnotationGuardTest` among them. `iosSimulatorArm64Test SKIPPED` there by design: Kotlin/Native iOS targets need macOS |
| The iOS job completes `xcodebuild` on a simulator destination | ✅ Met | `** BUILD SUCCEEDED **`; inside it the podspec's script phase ran `:sharedLogic:syncFramework` and `linkPodDebugFrameworkIosSimulatorArm64` |
| **The iOS job actually catches a SKIE failure** | ✅ Met | On [#13](https://github.com/Humble-Coders/FinAI-Mobile-2026/pull/13), commit (b) — a defaulted constructor parameter — left Android green and failed the iOS job at *Build the iOS app* with `ApiDemoViewModel.swift:45:111: error: missing argument for parameter 'retries' in call` ([run 34482374059](https://github.com/Humble-Coders/FinAI-Mobile-2026/actions/runs/34482374059)). See below |
| The workflow requires no repository secrets | ✅ Met | No `secrets.` reference; `permissions: contents: read`; the generated config holds `REPLACE_ME` placeholders (checked in a fresh clone) |
| The JDK version in CI matches `gradle/gradle-daemon-jvm.properties` | ✅ Met | Read at run time: `JDK pinned by gradle/gradle-daemon-jvm.properties: AZUL 21 (setup-java distribution: zulu)`; `setup-java` installed Zulu 21.0.12 and Gradle's daemon-JVM discovery used it — no second download |
| The Xcode version is pinned in the workflow, not inherited | ✅ Met | `XCODE_VERSION: "26.2"` selected with `xcode-select` and asserted; the log shows `Build version 17C52` — the same build as the team's Xcode. The image is pinned too (`macos-26`) |
| A red job blocks the PR | ❌ **Not enforceable on the current plan** | The repo is **private**; GitHub answers branch rules with *"Upgrade to GitHub Pro or make this repository public to enable this feature."* Red jobs show on the PR but do not block merging. A manager decision — see below |
| The README badge reflects real status | ⚠️ After merge | The badge tracks `ci.yml` on `main`, which has no run until this merges; the first push-to-`main` run gives it a status |

### The SKIE check — which job catches what

The ticket suggests verifying by removing `@Throws`. That alone cannot turn the iOS job red: a missing `@Throws` compiles on every platform and only **crashes at runtime** on iOS. What catches it is `ThrowsAnnotationGuardTest` — a host test, so it runs in the **Android** job. Both directions were therefore verified, on throwaway PR [#13](https://github.com/Humble-Coders/FinAI-Mobile-2026/pull/13):

| Commit | Change | Android | iOS |
|---|---|---|---|
| (a) | `@Throws` removed from `KtorCapabilitiesRepository.fetch()` | 🔴 **red** — `ThrowsAnnotationGuardTest … FAILED`, 53 tests completed, 1 failed ([run 34481619923](https://github.com/Humble-Coders/FinAI-Mobile-2026/actions/runs/34481619923)) | ✅ green — compiles; only crashes at runtime |
| (b) | `@Throws` restored; `retries: Int = 1` added to its public constructor | ✅ green — `BUILD SUCCESSFUL` | 🔴 **red** at *Build the iOS app* — `ApiDemoViewModel.swift:45:111: error: missing argument for parameter 'retries' in call` ([run 34482374059](https://github.com/Humble-Coders/FinAI-Mobile-2026/actions/runs/34482374059)) |

(b) is the failure this ticket exists for: Kotlin callers compile, but Kotlin default arguments do not reach Swift, so `ApiDemoViewModel.swift:45` no longer compiles — Android green, iOS red. Both results were predicted by running the same changes locally first. The separate "revert → both green" run was skipped to save macOS minutes: #12's own green run is that state, on identical code.

## Deviations / decisions

1. **`runs-on: macos-26`, not `macos-latest`.** `-latest` moves to the next macOS on GitHub's schedule, and a newer image may not carry our Xcode; pinning image and Xcode together means the toolchain changes only when we choose.
2. **Xcode is selected with `sudo xcode-select`**, not `maxim-lobanov/setup-xcode` (which the ticket gives as an example) — no third-party code in the pipeline. The version is asserted after selection.
3. **Xcode 26.2, not the image default 26.6** — it is what the team develops and verifies on. The image ships the iOS 26.2 simulator runtime the native tests need.
4. **The iOS job also runs `:sharedLogic:iosSimulatorArm64Test`.** On Linux, `allTests` skips the 46 native tests with nothing but a warning in the log (`Native task 'iosSimulatorArm64Test' is disabled`), so without this they would run nowhere. They also catch what only native targets reject, such as a comma in a test name.
5. **The `@Throws` check was verified in both directions** — see above.
6. **`cache-provider: basic` for `setup-gradle`.** The default provider is proprietary: free for public repositories, a "Free Preview" for private ones on terms that can change. `basic` is the MIT-licensed wrapper over `actions/cache`.
7. **`:sharedLogic:podInstall` instead of `generateDummyFramework` + `pod install`.** The podspec is generated and gitignored (`.gitignore:25`), so on a fresh clone the README's old steps stop at `[!] No podspec found for SharedLogic in ../sharedLogic` — reproduced. `podInstall` runs the dummy framework, the podspec and `pod install` in order. The README was wrong the same way and is fixed.
8. **The JDK pin is read by a small composite action** both jobs use, instead of repeating the parsing.
9. **The ticket assumed a public repo; it is private.** That changes the cost and the branch-protection criterion — see below.

## Open questions / follow-ups

- **Manager decision — visibility or plan.** While the repo is private on the current plan, branch protection is unavailable and macOS minutes draw down the allowance fast (the ticket's own note: about 10× Linux). Options: make it public (the backend already is; nothing secret is committed here), upgrade the plan, or accept red-but-unblocking checks. The workflow needs no change either way.
- **Cold runs until merge.** `setup-gradle` never writes its cache from a PR (verified: *"Entry not saved: cache is read-only"*), so every PR run is cold for Gradle until a push-to-`main` run seeds it. The Kotlin/Native cache is written per ref by `actions/cache`; a PR's entry is visible only to that PR.
- **Actions are pinned by major version tag** (`@v6`, `@v7`), as in the backend. Pinning to commit SHAs is a hardening step worth a follow-up.
- **Bumping Xcode:** change `XCODE_VERSION` and, when needed, `runs-on`, together — and check the image lists a simulator runtime for that Xcode.
- **Kotlin linting** (ktlint/detekt) stays out of scope, as the ticket says; worth its own ticket now that the structure has settled.
