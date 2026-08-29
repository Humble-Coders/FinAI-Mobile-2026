# Handoff — ticket #5

**Ticket:** [#5 — \[M1\] Clean up the generated scaffolding](https://github.com/Humble-Coders/FinAI-Mobile-2026/issues/5)
**Branch:** `ticket-5-cleanup-scaffolding` · **Base:** `main`

## Summary

Removed the two patterns the KMP template shipped that contradict the architecture, so the next developer can't copy them. `Supabase.kt` no longer installs Postgrest or Realtime — it installs Auth and Storage only, and the dependencies are gone from the version catalog and `sharedLogic`. The `sharedUI` Compose Multiplatform module is deleted; Android now owns its Compose UI under `androidApp/ui/`, and `iosApp` keeps the SwiftUI it already had.

SKIE is configured and demonstrably working: both apps render their labels through the shared Kotlin `LocalizationRegistry`, which on iOS is reached via SKIE-generated Swift bindings. `SupabaseConfig.kt` is now generated at build time from untracked `local.properties` rather than committed as a source constant.

Verified on both platforms — Android and iOS built, tested, launched and screenshotted.

## Files changed

### Removed — the misleading scaffolding
| File | Why |
|---|---|
| `sharedUI/**` (4 files) | Compose Multiplatform shared UI; architecture is native UI per platform |
| `settings.gradle.kts` | dropped `include(":sharedUI")` |
| `sharedLogic/.../repository/AuthRepository.kt` | email/password placeholder; M2 builds the real one for phone OTP + Google + Apple |
| `sharedLogic/.../config/SupabaseConfig.kt` | committed constants replaced by a generated file |
| `Platform.kt`, `Platform.android.kt`, `Platform.ios.kt` | template leftovers using `expect/actual`, which `kmp-arch-v2` forbids |
| `Greeting.kt`, `GreetingUtil.kt` | template leftovers |
| 4 generated placeholder tests | replaced by a real one (below) |

### Changed
| File | Why |
|---|---|
| `sharedLogic/.../config/Supabase.kt` | installs **Auth and Storage only**, with a comment stating why Postgrest/Realtime must not return |
| `gradle/libs.versions.toml` | removed `supabase-postgrest` and `supabase-realtime`; added SKIE **0.10.14** |
| `sharedLogic/build.gradle.kts` | applies SKIE (analytics off); adds the `generateSupabaseConfig` task and its generated source dir |
| `androidApp/build.gradle.kts` | depends on `:sharedLogic` instead of `:sharedUI`; declares Compose deps directly (they previously arrived transitively through `sharedUI`) |
| `androidApp/.../MainActivity.kt` | hosts the new theme + placeholder screen |
| `iosApp/iosApp/ContentView.swift` | drops the `Greeting()` demo; renders shared i18n strings |
| `README.md` | describes this project, its setup and the network-boundary rule, instead of the KMP template |

### Added
| File | Why |
|---|---|
| `sharedLogic/.../i18n/Strings.kt`, `EnglishStrings.kt`, `LocalizationRegistry.kt` | all user-facing text lives in shared code; lets the placeholder screens avoid platform string literals |
| `sharedLogic/.../data/.gitkeep` | the one package from the architecture that didn't already exist |
| `sharedLogic/src/commonTest/.../i18n/LocalizationRegistryTest.kt` | keeps `:sharedLogic:allTests` meaningful; comma-free name, required by native test targets |
| `androidApp/.../ui/PlaceholderScreen.kt`, `ui/Strings.kt`, `ui/theme/Theme.kt` | Android's own Compose UI, with light/dark honoured from the start |

## How to test

```bash
git checkout ticket-5-cleanup-scaffolding
```

Create `local.properties` at the repo root (untracked):

```properties
sdk.dir=/Users/<you>/Library/Android/sdk
```

Supabase values may be omitted — the build generates `REPLACE_ME` placeholders.

```bash
./gradlew :androidApp:assembleDebug
./gradlew :sharedLogic:allTests
```

iOS (a Mac with Xcode and CocoaPods is required):

```bash
./gradlew :sharedLogic:generateDummyFramework
cd iosApp && pod install
xcodebuild -workspace iosApp.xcworkspace -scheme iosApp \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build
```

Spot checks:

```bash
grep -rniE "postgrest|realtime" --include="*.kt" --include="*.kts" --include="*.toml" . | grep -v /build/
```

Only two hits, both in the explanatory comment in `Supabase.kt`. Then launch either app: both should show "FinAI / Foundation in place. Features land in M2."

## Acceptance criteria

| Criterion | Status |
|---|---|
| `:androidApp:assembleDebug` succeeds | ✅ Met |
| `:sharedLogic:allTests` succeeds | ✅ Met — Android host **and** iOS simulator targets |
| iOS workspace builds (`xcodebuild`) | ✅ Met — `** BUILD SUCCEEDED **` |
| `sharedUI` gone from filesystem and `settings.gradle.kts` | ✅ Met |
| Grep for postgrest/realtime returns no hits | ⚠️ **Partial by choice** — two hits remain, both in the doc comment explaining why they are absent. No code, dependency or import references. See Deviations |
| `Supabase.kt` installs only `Auth` and `Storage` | ✅ Met |
| No Supabase URL or key hardcoded in a `.kt` source file | ✅ Met — generated at build time from `local.properties` |
| SKIE applied and produces a framework the iOS app links | ✅ Met — Swift reaches Kotlin objects through SKIE bindings |
| `commonMain` package structure matches the architecture | ✅ Met — `model/`, `repository/`, `usecase/`, `data/`, `i18n/`, `config/`, `util/` |
| App launches on an Android emulator and the iOS simulator | ✅ Met — both launched and screenshotted, no crashes |

## Deviations / decisions

1. **SKIE 0.10.14, not the version first tried.** SKIE **0.10.6 fails configuration outright** against Kotlin 2.4.10 — it supports up to 2.2.10. 0.10.14 works. Relevant to ticket #8 (mobile CI), and a standing reminder that SKIE lags new Kotlin releases, so a Kotlin upgrade can block on it.

2. **The grep criterion is met in substance, not literally.** Two matches survive, both inside the doc comment on `Supabase.kt` warning that Postgrest and Realtime must not be re-added. Deleting the comment to satisfy a literal grep would remove the very guardrail this ticket exists to leave behind. Flagged for the reviewer rather than quietly resolved.

3. **i18n was seeded here, ahead of ticket #6.** The review of #5 explicitly allowed a hardcoded string in the placeholder screen. Seeding `Strings` / `EnglishStrings` / `LocalizationRegistry` (~45 lines) meant not introducing one at all, which matches `kmp-arch-v2`. #6 extends these files rather than creating them.

4. **`Platform.kt` and its two actuals were deleted, though the ticket doesn't name them.** They are template leftovers *and* use `expect/actual`, which `kmp-arch-v2` forbids. Leaving a forbidden pattern in place would defeat the ticket's purpose.

5. **One smoke test was kept.** The ticket says to remove the generated placeholder tests; removing all of them would leave `:sharedLogic:allTests` passing while running nothing. `LocalizationRegistryTest` covers real behaviour and keeps the task honest until #6 adds more.

6. **Generated config via a Gradle task, not BuildKonfig.** `expect/actual` is forbidden, so the choice was a ~25-line Gradle task or a third-party plugin. The task adds no dependency and matches the architecture's low-machinery style.

7. **Only `data/` was created.** `model/`, `repository/`, `usecase/` and `util/` already existed on `main` from the template.

## Open questions / follow-ups

- **The live iOS Simulator panel needs a one-time Xcode setting** requiring sudo: `sudo xcode-select -s /Applications/Xcode.app/Contents/Developer`. Verification here used `xcrun simctl` headlessly instead. Not a blocker for this ticket; worth fixing before UI work in M2.
- **A fresh clone cannot run `xcodebuild` directly** — `:sharedLogic:generateDummyFramework` then `pod install` must run first, or the podspec raises. Ticket **#8** (mobile CI) must include those steps or the iOS job will fail on a clean runner.
- **`local.properties` must reach each developer.** Documented in the README; only the publishable anon key belongs there, never `service_role`.
- **No Kotlin linting.** Deliberate for this ticket (see #8's out-of-scope), but the structure has now settled, so it is a reasonable follow-up.
