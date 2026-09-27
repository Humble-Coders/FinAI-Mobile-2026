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
| `usecase/StatementRedactor.kt` | Digit runs of five or more masked to last 4; amounts cut out first so none can be rewritten; identifiers masked in place on transaction lines and dropped with the line elsewhere; page-1 header block dropped; dropped lines counted. |
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
`StatementRedactorTest` (22), `StatementPeriodTest` (11), `ImportStatementTest` (12),
`AndroidStatementReaderTest` (10, Robolectric, `@Config(sdk = [34])`).

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
| Scanned PDF and PNG go through OCR | **not met.** No automated test covers either OCR path on either platform — ML Kit will not start under Robolectric and Vision has no test harness — and the emulator is off limits here, so the code has never been executed. Manual steps below. |
| Redactor tests table-driven; transaction lines survive untouched; no commas in names | **met** — `StatementRedactorTest` |
| Wire text has no digit run > 4, no email, no postal code | **met** — masking triggers at five digits, and the fixture now carries 5-, 6- and 7-digit references so the assertion is not vacuous |
| Over-limit text refused on the device before any request | **met** — `textOverTheLimitIsRefusedBeforeAnyRequest` asserts nothing was sent |
| Period found and sent while its own line is still redacted away | **met** — `sendsThePeriodEvenThoughItsLineIsRedactedAway` asserts both halves |
| Password-protected PDF: unlocks, clear error, never sent | **met** for a text-layer PDF — `aLockedStatementOpensWithItsPassword`, `theWrongPasswordSaysSoRatherThanFailingObscurely` and `aLockedStatementWithNoPasswordAsksForOne` run against a genuinely encrypted fixture. The password cannot reach a request at all: it is not a parameter of `ImportStatement`. A locked *scanned* PDF takes the OCR path, which is unexecuted. |
| 20-page read never blocks the main thread; progress per page | **met** — `readingProgressIsReportedPerPage`; both readers take an `onPage` callback |
| Nothing logs page text, a line, or the redacted string | **met** — no logging call of any kind in the statement code |
| Android + shared tests pass and iOS `xcodebuild` succeeds | **met** — 161 shared, 28 Android, and CI's iOS job green (run 36317571132) |

## Review fixes (round 1)

Four defects found in review, all in the redaction rules:

1. **The mask rewrote amounts.** `(?:\d[ -]?){6,}\d` let a digit run cross a
   single space into the next column: `CHQ 1234567 10.99` became
   `CHQ ••••6710.99`, turning a $10.99 charge into $6710.99. Amounts are now cut
   out of the line before any rule runs and put back verbatim, so no rule can
   reach inside one. Done by splitting rather than regex lookaround, because
   Kotlin/Native's engine does not support lookbehind the way the JVM's does and
   CI builds iOS without running the shared tests on it.
2. **The wire criterion was not met.** Masking triggered at seven digits while
   the criterion is "no run longer than four"; 5- and 6-digit runs passed
   through, and the test asserting it passed only because the fixture contained
   neither. Masking now triggers at five, and the fixture carries all three
   lengths.
3. **A postal-code-shaped merchant reference deleted a transaction.**
   `TIM HORTONS A1B2C3 4.25` was dropped whole. A transaction line now keeps its
   row and has the identifier masked in place; only non-transaction lines are
   dropped.
4. **Nothing counted what was dropped.** `StatementRedactor.of()` returns a
   `Redaction(text, droppedLines)` and `ImportStatement.execute` reports it
   through `onRedacted`, so a silently short import is detectable. `redact()`
   keeps the ticket's `ExtractedDocument -> String` signature.

Mutation-checked: reintroducing the original rules fails
`anAmountBesideAChequeNumberIsNotRewritten`, `aFiveDigitReferenceIsMasked`,
`aSixDigitReferenceIsMasked` and `theUploadCarriesNothingBeyondTheAgreedFields`,
and nothing else. The first of those initially did **not** fail — it asserted
`endsWith("10.99")`, which `••••6710.99` satisfies — so it was tightened to an
exact match.

## Review fixes (round 2)

The round-1 amount fix bought correctness with three privacy holes, all found
in review before merge:

1. **SIN-shaped numbers stopped being masked — a regression.** Narrowing the
   grouped rule to four-digit groups to kill the amount-crossing bug also
   dropped 3-3-3, which is exactly how a Canadian SIN is printed. `123 456 789`
   was masked before the round-1 fix and not after. The rule now takes three or
   more groups of three to five digits.
2. **Anything with two decimal places was protected as an "amount".** The
   pattern had no bound on the integer part, so `WIRE REF 1234567.89` went out
   untouched. An amount is now at most six digits before the point.
3. **Amount protection was not token-bounded**, so `ACCT 06012-5004321.00` had
   its tail read as an amount and only the `06012` masked — the identifying
   digits survived. Amounts are now matched as whole tokens.

**The test helper was hiding #2.** `assertNoLongDigitRun` stripped anything
amount-shaped before looking for runs, so the leak was erased by the strip and
the check reported clean. It now mirrors the masking rules instead of
second-guessing them.

Mutation-checked both ways: restoring the round-1 rules fails
`aSocialInsuranceNumberIsMasked`, `anIdentifierWrittenWithDecimalsIsStillMasked`
and `anAccountNumberIsNotPartlySavedByATrailingAmount`; removing the per-token
amount protection fails the four round-1 tests. Neither mutation touches
anything else.

## Review fixes (round 3)

Review turned to the Android reader, which the first two rounds had not looked at.

1. **A password-protected scanned PDF crashed.** The scan path reopened the file
   from its URI, dropping the password, and the platform's `PdfRenderer` cannot
   open an encrypted PDF at all — so it threw `SecurityException`, which is not
   the exception type the interface declares, and the screen would never have
   caught it. The document is now opened once, with the password, and both paths
   use it; rendering moved to PdfBox's own `PDFRenderer`. iOS never had this bug
   — it kept the unlocked document — so this also closes a platform divergence
   in the one layer where the platforms are allowed to differ.
2. **The OCR recogniser was never closed.** `StatementReader` now has `close()`,
   and it only closes a recogniser that was actually built, so a reader that
   only ever saw text-layer PDFs does not construct an ML Kit engine in order to
   release it.
3. **Password failures were detected by matching "password" in the exception
   message.** Now caught as `InvalidPasswordException`. When the string match
   missed, a wrong password surfaced as "unsupported file" and the user had no
   way to try again.
4. **Two raw `IOException`s escaped the declared contract** — from opening an
   image and from rendering a page. Both wrapped.

This also closed the oldest gap on the ticket: PdfBox can build an encrypted PDF
in the test, so **three tests now exercise a genuinely locked statement** — right
password, wrong password, and no password.

## Review fixes (round 4)

Review reached `StatementPeriod`, which the first three rounds never opened, and
re-read the round-3 change.

1. **The period could be sent backwards.** `find` took the first two dates on a
   line in the order they matched, unchecked. `Statement date: 5 Sep 2026
   Period: 1 Aug 2026 to 31 Aug 2026` yielded start 2026-09-05, end 2026-08-01,
   and `Closing … opening …` inverted outright. Both are ordinary header
   shapes. The backend uses this to supply the year the redactor strips, so a
   reversed period dates the statement wrong or empties the import — the exact
   failure the field exists to prevent. Dates are now returned earliest first.
2. **No date was checked for existing.** `32 Aug 2026` became `2026-08-32` and
   went out as a period bound; the ISO branch would have passed `2026-13-45`
   through untouched. Day and month are now validated against the real calendar,
   leap years included.
3. **Round 3's own fix had narrowed a catch too far.** Replacing
   `catch (Exception)` with `catch (IOException)` let everything PdfBox throws
   off a damaged file — `IllegalArgumentException`, `IndexOutOfBoundsException`,
   an NPE off a malformed xref — escape as itself, which is not the type the
   interface promises. The broad fallback is back, under the typed password
   catch, and a null input stream is handled rather than reaching
   `PDDocument.load`.
4. **Progress filled, reset, then crawled.** The text-layer pass reported
   per page before knowing whether it was the answer; when it was not, OCR
   started counting from one. The text pass is silent now and reports once on
   success, leaving the running count to the slow path. Changed on **both**
   platforms, so they still behave alike.

Mutation-checked: taking the dates in matched order and dropping the calendar
check fails exactly the four tests named for those shapes.

## Review fixes (round 5)

Review checked the wire contract against the backend's own schema for the first
time. `source_kind`'s values and `MAX_TEXT_CHARS` agree exactly; two bounds the
server enforces were unknown to the device.

1. **An over-paged statement was sent and refused.** `page_count` is
   `Field(ge=1, le=500)` in `StatementParseIn`, and the device checked only text
   length — so a 501-page scan was fully OCR'd, then 422'd. That is the wait the
   local text check exists to prevent, and on the OCR path it is minutes.
   **The round-5 check did not actually prevent it** — see round 6.
   `StatementLimits.MAX_PAGES` now sits beside `MAX_TEXT_CHARS`, checked first,
   because pages is the bound a person can act on.
2. **Empty text was posted.** `text` is `Field(min_length=1)`. Redaction returns
   `""` when page 1 holds nothing that reads as a transaction and there are no
   later pages, and the server answered with a validation error that means
   nothing to a reader. The device now says so itself, using the dropped-line
   count added in round 1 — which is what that count was for.
3. **iOS line boxes drifted on accented text.** `raw.count` counts Characters
   while `characterBounds(at:)` indexes UTF-16; they agree only for ASCII. A
   decomposed accent is one Character and two UTF-16 units, so on a French
   statement the index slipped and every later line on the page took the wrong
   box. Latent — nothing reads the coordinates yet and they never reach the
   wire — and now correct.

The three local refusals share a `StatementRefusal` interface, so a screen
cannot handle two of them and forget the third.

## Review fixes (round 6)

1. **The page limit was checked in the wrong layer.** Round 5 put it in
   `ImportStatement`, which receives a document the reader has *already* read —
   so on a 501-page scan every page was still rendered and OCR'd, and only the
   network round trip was saved. The commit message, the PR comment and this
   report all described a benefit the change did not deliver. The check now sits
   in both readers, immediately after the document opens and before a page is
   touched; the use-case check stays as a backstop for a document supplied from
   anywhere else. `AndroidStatementReaderTest` asserts the progress callback
   fired **zero** times, so the test guards where the check is, not just that it
   exists.
2. **413 fell through to "something went wrong".** It is the statement
   endpoint's own refusal, and the device prevents it — but the two copies of
   the limit live in different repositories and can drift, which is the whole
   reason `StatementLimits` exists. Mapped to `ApiException.StatementTooLarge`,
   carrying the same "import one month at a time" string.

## Review fixes (round 7)

**The client gave up on a parse before the server was done with it.** The
default request timeout is 60 s and the backend allows itself
`PARSE_BUDGET_SECONDS = 180.0`. A statement taking 61-180 s to parse timed out
on the phone while the server carried on, finished the import and spent the
model call — so the user was told it failed, retried, and paid for the same
statement twice. Dedup does not cover this: rows are not stored until the user
confirms them, so what repeats is the import record and the model spend.

`POST /statements/parse` now carries its own `PARSE_TIMEOUT_MS = 210_000`, in
`StatementLimits` beside the other two numbers that mirror the backend. The
global 60 s is unchanged and should stay — it is right for signing in, and a
hung auth call should not hang for three minutes.

**Round 8 correction:** the first attempt raised only `requestTimeoutMillis`
and left `socketTimeoutMillis` at the default minute. While the server parses
nothing comes back down the connection, so the socket bound ended the call at
sixty seconds regardless — the fix did not work, and the test did not notice
because it asserted the field that had been set rather than the waiting it was
supposed to buy. Both bounds are raised now, and both are asserted.

Also checked and clean, which is worth recording: `FinAiHttpClient` logs at
`LogLevel.HEADERS`, so no request body — and therefore no redacted statement
text — can reach a log, and `sanitizeHeader` keeps the bearer token out too.

## Verify OCR by hand

The two paths no test can reach, on a device or emulator:

1. Print a statement to PDF and re-scan it (or photograph one) so the file has
   no text layer. Import it. Expect rows, and `source = ocr` on the request.
2. Import a PNG screenshot of a statement. Same expectation.
3. Lock a scanned PDF with a password and import it. Expect the password prompt,
   then rows — this is the path that used to crash.

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

**Decided 2026-09-27: keep the bundled variant.** +21 MB is real and was
weighed against it. The alternative named in the ticket — ML Kit's Play Services
variant — trades roughly 20 MB of install size for a model download on first
use, which means the first import can fail with no network, and it does not work
at all on a device without Play Services. Paying the size to keep the first
import working offline is the trade we want.

Worth revisiting if install size starts costing conversions. The switch is
contained — the dependency, plus a download state and its failure handling in
the Android reader and the import screen — so it is a change, not a rewrite, and
nothing here is built in a way that assumes the model is local.

On iOS the cost is **zero**: PDFKit and Vision are system frameworks.

## Verification

`:sharedLogic:testAndroidHostTest` 183 tests, `:androidApp:testDebugUnitTest` 32 tests,
and the iOS workspace build — all green on CI run 36317571132, which ran
`:sharedLogic:syncFramework` and compiled `IOSStatementReader.swift`.

## Follow-ups

1. **The release APK could not be built** — the build host ran out of disk
   (`D8: java.io.IOException: No space left on device`). Not a code fault. Run
   `./gradlew :androidApp:assembleRelease` on a machine with space to get the
   release size.
2. **Neither OCR path has ever run.** Not a unit-testable gap: ML Kit does not
   start under Robolectric, Vision has no test harness, and running the
   emulator is not permitted here. Both were written against the SDK docs and
   compile, and nothing more than that is claimed. See *Verify OCR by hand*.
3. **Nothing calls `ImportStatement` yet** — the picker, progress and results UI
   are ticket 3.6, which this unblocks.
