# Handoff — ticket #6

**Ticket:** [#6 — \[M1\] Build the shared API client and repository layer](https://github.com/Humble-Coders/FinAI-Mobile-2026/issues/6)
**Branch:** `ticket-6-api-client` · **Base:** `main` (`0bf1a2a`) · **Implementation:** `4b51f7d`

## Summary

The shared layer every M2 feature will read and write through. Because the backend is plain HTTP + JSON, the repositories are **Ktor implementations in `sharedLogic/data/`, written once for Android and iOS**. The client never touches the database: Supabase supplies the session, and every business call goes to the Render API with its token.

Every request carries a token that is valid *now* — one within 30 seconds of expiry is refreshed before sending, and a 401 triggers exactly one refresh-and-retry. Every failure becomes a typed `ApiException`, so screens never see a status code; every public suspend function declares `@Throws`, enforced by a guard test, so nothing can kill the iOS process.

The ticket's added scope is fixed too: a fresh clone's `REPLACE_ME` Supabase config used to throw from a lazy property on first access — fatal on iOS. It now reports a configuration problem and the app shows **"This build isn't connected to a backend yet."**

A throwaway demo screen on both platforms signs in with a test session, calls `/capabilities`, and renders the payload — verified live on an Android emulator and an iOS simulator.

## Files changed

### Shared — network layer (`sharedLogic/.../data/`)
| File | Why |
|---|---|
| `FinAiHttpClient.kt` | The one Ktor client: JSON, 15s connect / 60s request timeouts, debug-only header logging with `Authorization` redacted, the `SessionBearer` plugin, the single 401 refresh-and-retry, and `sendMapped`/`getJson`, which turn every failure into `ApiException` |
| `ApiErrorMapper.kt` | Status + body → `ApiException`. Reads the body on 403, because FastAPI answers a *missing* token with 403 "Not authenticated" |
| `SupabaseTokenSource.kt` | The token from the Supabase session; refreshes before expiry, behind a `Mutex` so concurrent requests cannot each spend the single-use refresh token |
| `TokenFreshness.kt` | The pure expiry-with-margin rule, testable without a clock |
| `SupabaseAuthRepository.kt` | Session state as a `Flow`, test-session phone sign-in, sign-out, `/me`; plus two demo aids (below) |
| `KtorCapabilitiesRepository.kt` | `GET /capabilities` |
| `SupabaseErrors.kt` | Supabase SDK failures → `ApiException`, so screens see one error type |
| `FinAiJson.kt` | The one JSON config: unknown keys ignored, nulls coerced to defaults |

### Shared — contracts (`model/`, `repository/`, `config/`)
| File | Why |
|---|---|
| `model/ApiException.kt` | Sealed: `Unauthorized`, `FeatureUnavailable(feature, reason)`, `Forbidden`, `NotFound`, `Validation`, `Server`, `Network`, `NotConfigured`, `Unexpected` — each with an i18n `messageKey`; messages never carry response bodies |
| `model/Capabilities.kt`, `model/Me.kt` | The two payloads, defaults on every field; `FeatureReason` / `OnboardingStep` decode via `fromWire` with a fallback; `blockingReason(featureKey)` |
| `model/WireEnumSerializer.kt` | Routes enum decoding through `fromWire`, so an unknown server value never fails decoding |
| `model/ConfigurationProblem.kt`, `model/SessionState.kt` | Blocking reason for an unconfigured build; session state as screens need it |
| `repository/AuthRepository.kt`, `CapabilitiesRepository.kt`, `SessionTokenSource.kt` | Interfaces; every suspend function `@Throws(ApiException, CancellationException)` |
| `config/Supabase.kt` | **The added-scope fix:** the throwing lazy `client` (and its `auth`/`storage` getters) replaced by `configurationProblem` and `clientOrNull()` |

### Shared — strings
| File | Why |
|---|---|
| `i18n/Strings.kt`, `i18n/EnglishStrings.kt` | 35 keys: 9 errors, 5 feature reasons, 21 for the demo screen |

### Tests
| File | Why |
|---|---|
| `commonTest/.../data/FinAiHttpClientTest.kt` | 12 tests on a mock engine: bearer header, base-URL joining, signed-out 403, refresh-and-retry, no retry loop, failed refresh, feature gate, network failure, undecodable body, cancellation, **token and body never logged**, logging off |
| `commonTest/.../data/ApiErrorMapperTest.kt`, `TokenFreshnessTest.kt` | Every status mapping including both kinds of 403; the expiry boundary |
| `commonTest/.../model/CapabilitiesDecodingTest.kt`, `MeDecodingTest.kt`, `ConfigurationProblemTest.kt` | Real payloads, unknown enum values, missing fields, extra fields, nulls |
| `androidHostTest/.../ThrowsAnnotationGuardTest.kt` | Fails the build on a public suspend fun without `@Throws(..., CancellationException::class)` |
| `androidHostTest/.../StringsCoverageTest.kt` | Every `Strings` key has an English value — read by reflection, not from a hand-kept list |

### Build
| File | Why |
|---|---|
| `gradle/libs.versions.toml` | Ktor content negotiation, logging, kotlinx JSON, mock engine; coroutines-test |
| `sharedLogic/build.gradle.kts` | Those dependencies; `generateSupabaseConfig` → `generateAppConfig`, which also writes `ApiConfig.BASE_URL` from `api.baseUrl` in `local.properties`, defaulting to the Render URL |
| `androidApp/build.gradle.kts` | `buildConfig = true`, so `BuildConfig.DEBUG` gates logging |

### Throwaway demo (removed in M2)
| File | Why |
|---|---|
| `androidApp/.../ui/demo/ApiDemoScreen.kt`, `ApiDemoViewModel.kt`, `ApiDemoUiState.kt`; `MainActivity.kt` | Compose demo; repositories built in `bind`, closed in `onCleared` |
| `iosApp/iosApp/viewmodel/ApiDemoViewModel.swift`, `ui/ApiDemoView.swift`; `iOSApp.swift` | SwiftUI demo; session state consumed as an `AsyncSequence`; typed errors recovered from `NSError.userInfo["KotlinException"]` |

## How to test

Prerequisites: `supabase.url` and `supabase.anonKey` in `local.properties` (optionally `api.baseUrl`), and a Supabase test phone number — **Authentication → Sign In / Providers → Phone → Test Phone Numbers and OTPs**, e.g. `14165550100=123456`.

```bash
git checkout ticket-6-api-client
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :sharedLogic:allTests :androidApp:assembleDebug        # 45 host + 43 iOS tests, 0 failures
cd iosApp && xcodebuild -workspace iosApp.xcworkspace -scheme iosApp -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

On either app: enter `+14165550100` → **Send code** → `123456` → **Sign in** → **Load capabilities** renders the payload. **Expire token, then load** shows *token life right after expiring* ≈ −60 and *after the load* ≈ 3600 — the jump is the refresh.

The guard: delete `@Throws` from `KtorCapabilitiesRepository.fetch()` → `./gradlew :sharedLogic:testAndroidHostTest` fails naming `KtorCapabilitiesRepository.kt:18 fetch`. Restore it.

Unconfigured: build from a copy without the Supabase keys → the app shows "This build isn't connected to a backend yet."

## Acceptance criteria

| Criterion | Status | Evidence |
|---|---|---|
| `./gradlew :androidApp:assembleDebug` and `:sharedLogic:allTests` succeed | ✅ Met | 45 host tests + 43 iOS simulator tests, 0 failures, 0 skipped |
| **`xcodebuild` on `iosApp` succeeds** | ✅ Met | `BUILD SUCCEEDED` — both with real keys and from a copy with none |
| Android calls `/capabilities` with a real token and renders the payload | ✅ Met | Test-number sign-in → payload rendered in Compose; request log shows `GET /capabilities` → `200` |
| iOS does the same via SwiftUI | ✅ Met | Same flow on an iPhone 17 Pro simulator, payload rendered in SwiftUI |
| An expired token triggers a refresh and the request still succeeds | ✅ Met — live on Android | *Expire token, then load*: token life **−60 s → 3598 s**, with one `GET /capabilities` → `200` and no 401 — refreshed before sending. The SDK's auto-refresh is off for that session, so the jump is this code. Retry-after-401 covered by `FinAiHttpClientTest`. The same step on iOS was blocked by the network — see follow-ups |
| A 403 surfaces as the typed feature-unavailable error | ✅ Met — by tests | `ApiErrorMapperTest` and `FinAiHttpClientTest`. Not demonstrable live: no backend endpoint is feature-gated yet |
| `ThrowsAnnotationGuardTest` passes, and **fails** when `@Throws` is removed | ✅ Met | Removed from `fetch()` → red, naming `KtorCapabilitiesRepository.kt:18 fetch`; restored byte-identical → green |
| Release builds log no tokens and no response bodies | ✅ Met | Release `BuildConfig.DEBUG = false`, so the logger is never installed; `logs nothing when logging is off`; in debug, headers only — seen live as `-> Authorization: ***`; `never logs the token or the response body` |
| No `postgrest` / `realtime` usage, no direct database access | ✅ Met | The only mentions are the comment in `Supabase.kt` saying they are not installed |
| No user-facing string literal in `androidApp` or `iosApp` | ✅ Met | Remaining literals are empty-string defaults, the `"en"` language code and the `"KotlinException"` userInfo key. Android's launcher `app_name` in `res/values/strings.xml` predates this ticket and is required as a resource |
| The base URL comes from build configuration | ✅ Met | `ApiConfig.BASE_URL` generated from `api.baseUrl`; setting it produced the override, removing it restored the default |
| *(Added scope)* launching the iOS app with `REPLACE_ME` config does not crash | ✅ Met | Built from a copy with no `local.properties` — generated constants checked by value — app alive, no crash report, message shown |

## Deviations / decisions

1. **The guard lives in `androidHostTest`, not `jvmTest`.** `sharedLogic` has no JVM target; the Android target's host tests already run on the JVM.
2. **The guard is stricter than the ticket:** every public suspend function needs `@Throws`, and must list `CancellationException`. A network call throws without any literal `throw` in the source, so the ticket's rule would miss exactly the functions this ticket adds.
3. **403 is decided by the body.** The live API answers a missing token with 403 `{"detail":"Not authenticated"}` (FastAPI's bearer check) and a gated feature with 403 `{"detail":{"code":"feature_unavailable",…}}`. Mapping on status alone would tell a signed-out user a feature is disabled. Other 403s become `Forbidden`, added to the sealed type.
4. **Feature keys stay strings;** reasons and onboarding steps are enums with a fallback. Adding a feature is a database row — an enum would make it a release. A missing `enabled` reads as off; a key the payload never mentions reads as `UNKNOWN_FEATURE`.
5. **`content` is typed** (`taxAccounts`, `disclaimerVersion`) rather than a raw JSON tree, which crosses the Swift bridge badly; unknown keys are ignored.
6. **60-second request timeout,** because Render's free tier can take most of a minute to wake.
7. **Platforms pass the logging flag** (`BuildConfig.DEBUG`, `#if DEBUG`) — no `expect/actual`.
8. **Two demo-only aids on `SupabaseAuthRepository`, not on the interface:** `expireAccessTokenForTesting()` and `tokenSecondsLeftForTesting()`. The second exists because a locally expired token is still accepted by the server, so a successful request alone cannot prove a refresh happened. Both go with the demo in M2.
9. **The test session is a Supabase test phone number,** with placeholder SMS provider credentials — a manager decision: a real SMS provider is connected at launch, not before.

## Open questions / follow-ups

- **iOS live run of *Expire token, then load*.** At 17:42 the iOS request timed out: this Mac could not connect to Render's Cloudflare front end at all (`/healthz` from the Mac failed too, while GitHub was reachable). The app handled it as designed — the typed network error, no crash, no hang. The refresh code is shared Kotlin, proven live on Android; re-run on iOS once Render is reachable.
- **`onboarding_required: ["phone"]` is shown to a user who has a verified phone** — seen live. The backend derives it from `household.country_code`, which nothing sets until phone → region in **2.1**. Backend behaviour, flagged in the #12 review.
- **No live endpoint returns `feature_unavailable` yet.** The first gated endpoint will exercise that path end to end.
- **Before launch:** replace the placeholder SMS credentials with a real provider **and** remove or expire the test numbers — a fixed code is a sign-in path for anyone who knows it.
- **2.1:** check the fictional 555-01xx test numbers pass libphonenumber validation, or test sign-ups never get a region.
- **M2 cleanup:** remove the demo screens and the two demo aids. `ContentView.swift` and `PlaceholderScreen.kt` are now unused (the latter only by `MainActivity`'s preview).
