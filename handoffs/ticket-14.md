# Ticket #14 — M1 follow-ups: Kotlin linting and SHA-pinned actions

## Summary

Three M1 carry-overs that had no roadmap ticket of their own. ktlint now runs
in CI and fails the build, configured so that what it rejects is worth
rejecting. Every GitHub Action is pinned to a commit SHA, with a Dependabot
config so the pins can still move. actionlint runs as the Android job's first
step.

The work is five commits, deliberately ordered so the large one is boring: the
configuration lands first, the two things ktlint found but could not fix are
their own commit, and only then does the 66-file reformat land — pure
`ktlintFormat` output, listed in `.git-blame-ignore-revs`.

The configuration mattered more than the tool. Out of the box ktlint reported
**2,224** violations, which is a number nobody acts on. Switching the code
style to `intellij_idea` took it to **699**; two `.editorconfig` lines took it
to **263**; `--format` took it to **3**. The two lines are not suppressions of
real findings — they turn off a rule that was wrong about this codebase in two
specific places (below).

## Files changed

### Lint configuration

| File | Why |
|---|---|
| `.editorconfig` *(new)* | The whole configuration. Sets the code style, and disables two rules where the rule is wrong here, not the code. |
| `gradle/libs.versions.toml` | Pins `ktlint = "1.8.0"` and adds `ktlint-cli`, so the formatter is versioned like every other dependency. |
| `build.gradle.kts` | `ktlintCheck` / `ktlintFormat` as two `JavaExec` tasks over the CLI, covering `androidApp/src`, `sharedLogic/src` and every `**/*.kts`. |

Two settings in `.editorconfig` carry the weight:

- **`ktlint_function_naming_ignore_when_annotated_with = Composable`** — 98
  findings. `@Composable` functions are PascalCase by Compose convention; the
  rule assumes they are ordinary functions.
- **`ktlint_standard_property-naming = disabled`, scoped to `i18n/`** — 336
  findings. The constants in `Strings.kt` are lowercase because the constant's
  *name is the wire key*; renaming them to `SCREAMING_CASE` would change what
  is looked up. Scoped to that one directory so the rule still applies
  everywhere else.

Together: 434 of the 699, and neither was a real defect. Blanket-disabling
either rule repo-wide would have been the easy version and the wrong one.

**Code style: `intellij_idea`, not ktlint's default `ktlint_official`.** The
two differ by roughly 1,500 findings on this tree, almost all of it
`ktlint_official`'s multiline-expression and function-signature wrapping. The
codebase was written against IntelliJ's formatter, which is also what Android
Studio applies on ⌥⌘L — picking the other style would have meant the IDE and
CI disagreeing on every save.

### What ktlint found that it could not fix (`f05dcab`)

Separate from the reformat commit on purpose: the reformat is in
`.git-blame-ignore-revs`, and blame should still land on these.

- **`ui/theme/Color.kt` → `FinAiPalette.kt`** — the file held one top-level
  declaration, `FinAiPalette`. Renamed with `git mv`; no imports moved, because
  imports name the symbol.
- **`usecase/ImportStatement.kt`** — a KDoc block above `StatementRefusal`
  described `StatementTooLong`, a class three declarations further down, while
  that class carried a thinner one-liner. The orphan is deleted and its detail
  merged into `StatementTooLong`'s own KDoc.

### The reformat (`25b0b9c`, `b983b14`)

66 files, +488 −435, pure `ktlintFormat` output — no behaviour, no API, no
string changed. `.git-blame-ignore-revs` names it so `git blame` and GitHub
look through it. GitHub reads that file with no setup; local git needs
`git config blame.ignoreRevsFile .git-blame-ignore-revs` once, which the file
itself says.

### CI (`e0fd08b`)

| File | Why |
|---|---|
| `.github/workflows/ci.yml` | actionlint step, ktlintCheck step, three actions pinned. |
| `.github/actions/setup-build/action.yml` | The other two actions pinned. |
| `.github/dependabot.yml` *(new)* | The bump path the pins need. |

- **Pinned actions** — all five (`checkout`, `upload-artifact`, `cache`,
  `setup-java`, `gradle/actions/setup-gradle`) now name a commit SHA with the
  release as a trailing `# v7` comment. A tag is a pointer its owner can
  repoint; that is how `tj-actions/changed-files` reached ~23,000 repositories
  in March 2025 without any of them changing a line.
- **`.github/dependabot.yml`** — not in the ticket, added because pinning
  without it is a trap: a SHA never moves, so an upstream fix never arrives
  either. Monthly, grouped into one PR, actions only. Dependabot rewrites the
  SHA and the comment together. Two `directories` entries, not one `directory`:
  `/` covers `.github/workflows/` and a ROOT `action.yml` only, so the
  composite action at `.github/actions/setup-build/` needs its own glob.
- **actionlint, first step** — a wrong workflow is wrong before anything is
  built, and the check costs seconds. Installed from the release tarball with
  its SHA-256 verified, rather than via a third-party action: the step arguing
  for pinned SHAs should not pull in an unpinned dependency to do it.
- **ktlintCheck, before the build** — seconds against minutes, and forgetting
  `ktlintFormat` is the common failure.

## How to test

```bash
git checkout ticket-14-lint-and-pinned-actions

# 1. Lint is clean, and is actually looking at something.
./gradlew ktlintCheck            # BUILD SUCCESSFUL

# 2. It fails on a real violation — break one and watch it.
printf '\nval  x = 1\n' >> build.gradle.kts
./gradlew ktlintCheck            # fails, naming build.gradle.kts:<line>
git checkout build.gradle.kts

# 3. The reformat changed no behaviour.
./gradlew :androidApp:assembleDebug :androidApp:testDebugUnitTest
./gradlew :sharedLogic:testAndroidHostTest

# 4. Blame looks through the reformat.
git config blame.ignoreRevsFile .git-blame-ignore-revs
git blame sharedLogic/src/commonMain/kotlin/com/humblesolutions/finai/util/Money.kt | head
# no line attributed to 25b0b9c

# 5. Every action is pinned — this should print nothing.
grep -rn 'uses: [a-z].*@v[0-9]' .github/
```

For iOS, `./gradlew :sharedLogic:podInstall` then, from `iosApp/`:

```bash
xcodebuild -workspace iosApp.xcworkspace -scheme iosApp \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build
```

## Verification run

| | |
|---|---|
| `./gradlew ktlintCheck` | BUILD SUCCESSFUL |
| `:androidApp:assembleDebug` | BUILD SUCCESSFUL |
| sharedLogic host tests | 298 tests, 0 failures |
| androidApp unit tests | 109 tests, 0 failures |
| sharedLogic iOS tests | 291 tests, 0 failures |
| iOS `xcodebuild` | ** BUILD SUCCEEDED ** |
| actionlint, shellcheck on PATH | clean, before and after the new steps |

The actionlint step was run verbatim against this tree with shellcheck on
`PATH`, because `ubuntu-latest` ships shellcheck and actionlint then checks
every `run:` block — a local run without it would not have proven the workflows
pass. A deliberately wrong digest aborts the step before the lint runs.

## Acceptance criteria

| Criterion | Status |
|---|---|
| Kotlin linting (ktlint and/or detekt) in the Android CI job | **Met** — ktlint; detekt deferred, see below |
| Pin GitHub Actions to commit SHAs in `ci.yml` and `setup-build/action.yml` | **Met** — all five, both files |
| `actionlint` on the workflows | **Met** — Android job's first step |

## Deviations / decisions

- **ktlint only; detekt deferred.** The ticket allows "ktlint and/or detekt".
  They are different tools — ktlint is formatting, detekt is static analysis
  with a far larger and more opinionated rule set, and a meaningful baseline
  decision on an existing codebase. Shipping both at once would have made the
  reformat commit impossible to review. Not filed as a follow-up ticket; raise
  it if it is wanted.
- **A Gradle task of our own rather than a ktlint plugin.** The common plugins
  (JLLeitschuh, jmfayard) are a third-party layer over the same CLI, with their
  own release cadence and their own Gradle-version compatibility. Two
  `JavaExec` tasks against the pinned CLI are about 25 lines and one fewer
  thing that can break on a Gradle upgrade.
  - The configuration asks for the **shaded** `ktlint-cli` artifact explicitly.
    This is not a preference: ktlint publishes a plain jar and a shaded one, the
    plain one resolves without the parser and rule jars it needs, and the
    ambiguity is a hard resolution failure rather than a warning.
- **`.github/dependabot.yml` is beyond the ticket's text.** Reasoning above.
  Easy to drop if unwanted — it is one file and touches nothing else.
- **actionlint does not read `.github/actions/*/action.yml`.** Composite
  actions are outside what it parses. The step's comment says so rather than
  implying cover it does not give; changes to `setup-build` are still reviewed
  by eye.

## Open questions / follow-ups

- **Check Dependabot once after merge — nothing on this PR can.** Dependabot
  reads its config only from the default branch, so neither CI nor any local
  command exercises `.github/dependabot.yml` while it sits on a branch. After
  merge: Insights → Dependency graph → Dependabot. A malformed `directories`
  entry surfaces there and nowhere else, which is the same quiet-failure shape
  the config was added to fix, one level up.
  - If no PRs appear within the month, the cause is likelier to be that
    Dependabot is off in the org's settings — this repository is private, and
    that switch belongs to the org owner. Either way the pins are only as good
    as the thing that bumps them.
- **detekt**, if wanted — see above.
- **The `# v7` comments are maintained by hand if Dependabot never runs.** A
  SHA with a stale comment is worse than no comment; whoever bumps one by hand
  should update both halves.

## Review round 1 — what changed

Four fixes, two of them cases of the PR only half doing its own job.

- **ktlint was skipping two of the four build files.** `*.kts` is not
  recursive; it matched the repository root and missed `androidApp/` and
  `sharedLogic/`. Caught by planting a violation in
  `androidApp/build.gradle.kts` and watching the check pass. Now `**/*.kts`
  with `!**/build/**`. The two newly covered files needed formatting; that is
  the whole of their diff.
- **Dependabot was skipping the composite action.** `directory: "/"` means
  `.github/workflows/` plus a ROOT `action.yml`, and ours is nested, so
  `setup-java` and `setup-gradle` would never have been bumped — silently.
  Now `directories` (only the plural globs) with `/` and
  `/.github/actions/*`.
- **`CLAUDE.md`'s done-rule and verification matrix now name `ktlintCheck`**,
  so following the doc no longer ends in a red build.
- **`ktlintCheck` is now up-to-date-able.** It had inputs but no outputs, so it
  re-ran every time and the inputs bought nothing. A marker file fixes it:
  second run is UP-TO-DATE in 378 ms against 4 s. Invalidation was verified,
  not assumed — a `.kt` edit, a module `.kts` edit and an `.editorconfig`
  touch each re-run it, and the first two catch a planted violation.

Re-verified after the fixes: `ktlintCheck` passes, 298 shared + 109 Android
tests with 0 failures, iOS `xcodebuild` succeeds, actionlint clean with
shellcheck on `PATH`.
