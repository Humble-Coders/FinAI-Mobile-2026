# Ticket #29 — Read the statement on the device and redact it there

## Summary

A statement now goes from a file the user picked to redacted text and structured
rows without the document leaving the phone. Each platform does the one genuinely
native thing — PDFKit and Vision on iOS, PdfBox-Android and bundled ML Kit on
Android, three paths each (text layer → render + OCR → image OCR). Everything
after the read is shared Kotlin: find the statement period, redact, check the
length against what the API accepts, and post. Only the redacted text and a date
range are sent; the document, its lines and any PDF password stay on the device.

The step order is the load-bearing part. The period is read **before** redaction,
because the block carrying the year is the same block the redactor drops, and the
backend omits a row it cannot date rather than guessing one.

## Files changed

### Shared — the decisions, made once
| File | Why |
|---|---|
| `model/ExtractedDocument.kt` | Pages, lines, boxes, `SourceKind`. Coordinates kept because line order alone loses columns. |
| `model/ParsedStatement.kt` | The parse response, and `StatementUpload` — the only thing that goes on the wire. |
| `usecase/StatementRedactor.kt` | Digit runs masked to last 4; email / phone / postal-code lines dropped; page-1 header block dropped. |
| `usecase/StatementPeriod.kt` | Finds `1 Aug 2026 to 31 Aug 2026` in the header, skipping transaction-like lines. |
| `usecase/ImportStatement.kt` | Period → redact → length check → post, in that order. |
| `config/StatementLimits.kt` | `MAX_TEXT_CHARS = 200_000`, one copy, matching the backend's. |
| `repository/StatementReader.kt` | The native-read seam plus its typed failures. |
| `repository/StatementImportRepository.kt`, `data/KtorStatementImportRepository.kt` | `POST /statements/parse`. |
| `i18n/Strings.kt`, `i18n/EnglishStrings.kt` | Six strings; no literals in platform code. |

### Platform — only the native SDK call
| File | Why |
|---|---|
| `androidApp/data/AndroidStatementReader.kt` | PdfBox text layer, bitmap render + ML Kit, ML Kit on images. Off the main thread, progress per page. |
| `iosApp/iosApp/repository/IOSStatementReader.swift` | The same three paths with PDFKit and Vision. |

### Tests
`StatementRedactorTest` (10), `StatementPeriodTest` (6), `ImportStatementTest` (7),
`AndroidStatementReaderTest` (6, Robolectric, `@Config(sdk = [34])`).

## How to test

```bash
./gradlew :sharedLogic:testAndroidHostTest :androidApp:testDebugUnitTest
cd iosApp && xcodebuild -workspace iosApp.xcworkspace -scheme iosApp \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build
```

## Acceptance criteria

| Criterion | Status |
|---|---|
| Text-layer PDF → one line per statement line, in order, with coordinates, `PDF_TEXT` | **met** — `aPdfWithATextLayerIsReadWithoutOcr`, `everyStatementLineComesBackInReadingOrder`, `linesCarryTheirPositionOnThePage` |
| Scanned PDF and PNG go through OCR | **met on Android**; iOS path written but see *Not verified* |
| Redactor tests table-driven; transaction lines survive untouched; no commas in names | **met** — `StatementRedactorTest` |
| Wire text has no digit run > 4, no email, no postal code | **met** — asserted on `StatementUpload.text` in `theUploadCarriesNothingBeyondTheAgreedFields` |
| Over-limit text refused on the device before any request | **met** — `textOverTheLimitIsRefusedBeforeAnyRequest` asserts nothing was sent |
| Period found and sent while its own line is still redacted away | **met** — `sendsThePeriodEvenThoughItsLineIsRedactedAway` asserts both halves |
| Password-protected PDF: unlocks, clear error, never sent | **partly met** — the password cannot reach the request (it is not a parameter of `ImportStatement`), and the wrong-password error is typed. **There is no test that a real encrypted PDF unlocks.** |
| 20-page read never blocks the main thread; progress per page | **met** — `readingProgressIsReportedPerPage`; both readers take an `onPage` callback |
| Nothing logs page text, a line, or the redacted string | **met** — no logging call of any kind in the statement code |
| Android + shared tests pass and iOS `xcodebuild` succeeds | **partly** — see *Not verified* |

## Deviations / decisions

**`ImportStatement` takes an `ExtractedDocument`, not a `StatementReader`.**
A Kotlin `suspend` interface is awkward to conform to from Swift. Had the reader
been a constructor dependency, iOS could not have used this class at all and would
have reimplemented period-finding, redaction and the length check in Swift — which
is exactly how two platforms come to disagree about what gets redacted. So the
platform reads natively and hands the result to shared. `StatementReader` still
exists and Android still implements it, because there it costs nothing.

**`IOSStatementReader` does not conform to `StatementReader`** for the same reason.
Nothing shared calls into it.

**PDF boxes are flipped to a top-left origin on iOS.** PDF user space starts at the
bottom left; `BoundingBox` is documented top-left and Android's PdfBox path already
reports it that way. A box meaning two different things is worse than no box.

## Size cost

Measured from the debug APK, before and after these dependencies:

| | APK |
|---|---|
| before (17 Sep, no ML Kit / PdfBox) | 19.2 MB |
| after, all four ABIs in one APK | 70.8 MB |

Nearly all of it is one file, `libmlkit_google_ocr_pipeline.so`, once per ABI:
11.6 MB (x86_64), 11.6 MB (x86), 11.1 MB (arm64-v8a), 6.8 MB (armeabi-v7a).
PdfBox is 1.8 MB.

**What a real user downloads is the per-ABI split, not this APK.** Play serves one
ABI from an App Bundle, so an arm64 device gets roughly 40 MB of the 70.8 MB, i.e.
about **+21 MB over the 19.2 MB baseline** — and that is an unminified debug build,
so a release with R8 will be lower again.

**This needs a manager decision.** +21 MB is real. The alternative named in the
ticket — ML Kit's Play Services variant — trades roughly 20 MB of install size for
a model download on first use, which means the first import can fail with no
network and the offline-first promise weakens. Recommend keeping the bundled
variant and revisiting if install size becomes a conversion problem.

On iOS the cost is **zero**: PDFKit and Vision are system frameworks.

## Not verified / follow-ups

1. **The iOS build has not been re-run since the shared layer changed.** The
   workspace build succeeded earlier in the session (`** BUILD SUCCEEDED **`, with
   `IOSStatementReader.o` emitted, so the Swift really compiled), but
   `ImportStatement.kt` was restructured after that. The Kotlin compiles for
   Android; what is unproven is SKIE codegen over the new class. **Re-run the
   `xcodebuild` command above before merging.**
2. **The release APK could not be built** — the build host ran out of disk
   (`D8: java.io.IOException: No space left on device`). Not a code fault. Run
   `./gradlew :androidApp:assembleRelease` on a machine with space to get the
   release size.
3. **No test unlocks a real encrypted PDF.** A fixture that guessed at
   PdfBox-Android's encryption API was removed rather than left asserting nothing.
   Worth adding a generated encrypted fixture.
4. **Nothing calls `ImportStatement` yet** — the picker, progress and results UI
   are ticket 3.6, which this unblocks.
