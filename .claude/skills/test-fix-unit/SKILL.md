---
name: test-fix-unit
description: Fixes failing unit tests after a production change and adds tests for the paths that change introduced. Does NOT touch production code. Use when tests break after a code change, or when a change landed with no test. Covers both compilation targets — commonTest runs on androidTarget and desktop.
argument-hint: "[test class, package, or 'all']"
---

You make this repo's test suite correct and complete after production code changed: fix what broke,
and cover what the change introduced. **You do not modify production code.**

## Scope

$ARGUMENTS — a test class (`ProtocolSerializationTest`), a package (`domain`), or nothing (all).

## The test topology — read this first

| Suite | Location | Compiled for | Command |
|---|---|---|---|
| Unit (136) | `shared/src/commonTest/` | androidTarget **and** desktop — the **same source twice** | `./gradlew testDebugUnitTest` **and** `./gradlew :shared:desktopTest` |
| JVM-only unit | `shared/src/jvmCommonTest/` | Android + desktop | same two commands |
| Compose UI (39) | `shared/src/androidInstrumentedTest/` | a device | `ANDROID_SERIAL=<s> ./gradlew :shared:connectedDebugAndroidTest` |
| Migration (1) | `androidInstrumentedTest/.../SettingsDataStoreMigrationTest.kt` | a device | same |
| Pinned TLS (3, opt-in) | `androidInstrumentedTest/.../TlsRelayIntegrationTest.kt` | a device + a real TLS relay; skipped without one | same |

**`commonTest` compiling twice is the thing to internalise.** A test can pass on one target and
fail on the other, and that asymmetry is usually a real bug — a JVM-only API that slipped into
`commonTest`, or a platform difference in the code under test. Always run both.

---

## Step 1 — Run and read

Use the `build-gate` agent so the Gradle output stays out of the conversation:

```
Agent(subagent_type: "build-gate",
      prompt: "Run ./gradlew testDebugUnitTest and ./gradlew :shared:desktopTest. Report every
               failure with its assertion message, file and line, and say which target failed.")
```

If everything passes, say so and go to **Step 5** (coverage of the change) — do not stop.

---

## Step 2 — Diagnose each failure

For each:
1. Read the test.
2. Read the production file it exercises, and the diff that changed it
   (`git diff HEAD -- <path>`).
3. Name the root cause: a changed threshold, a changed signature, a new required field, a renamed
   `@SerialName`, a changed default, a platform difference.

**If it failed on only one target, that is the finding.** Say which, and why the two differ, before
changing anything.

---

## Step 3 — Fix the tests, not the code

**Only edit files under `shared/src/commonTest/`, `shared/src/jvmCommonTest/` or
`shared/src/androidInstrumentedTest/`.**

If a fix requires a production change, **stop** and tell the user which file, what change, and why.
Then wait. A test bent to fit broken code is worse than a failing test.

Conventions (`docs/testing.md`, `docs/conventions.md`):
- **Descriptive backtick names** describing the behaviour, not the method.
- **No `// Given / When / Then` comments.**
- **Extract `val expected` before the assertion**; do not inline it.
- **Compare the whole object**, not one field — a reducer test asserts the whole
  `Result(state, nextAction, event)`.
- **`ProtocolSerializationTest` asserts the literal expected JSON**, never a round-trip. A
  round-trip passes while both sides of the wire drift together.
- **No `Thread.sleep`** — use the coroutines test APIs.
- **No hardcoded host or port**, exactly as in production code.
- **Never assert a masked field is hidden by reading its text value** — `InputText` carries the
  real string whatever the `VisualTransformation` does, so the assertion passes either way. Assert
  on the rendered bullets (`docs/known-issues.md` § Gotchas).
- **Never assert audibility** — emulator microphones capture silence. Assert frame flow and floor
  state.

Source-set placement:
- Platform-independent → `commonTest`.
- Needs a JVM API (`javax.net.ssl`, Ktor CIO, `InternalPttServer`) → `jvmCommonTest`. A JVM API in
  `commonTest` breaks the iOS compilation of the test source set.
- Needs a device or Compose UI → `androidInstrumentedTest`.

---

## Step 4 — Re-run both targets

```
Agent(subagent_type: "build-gate",
      prompt: "Run ./gradlew testDebugUnitTest and ./gradlew :shared:desktopTest and report.")
```

Repeat Steps 2–4 until both are green. **Both**, not whichever one you started with.

---

## Step 5 — Cover what the change introduced

There is no JaCoCo in this repo, so work from the diff rather than a coverage report:

```bash
git diff HEAD --stat
git diff HEAD -- shared/src/commonMain app/src/main
```

For each changed production class, ask what the change added that nothing asserts:

| Changed | Add a test for |
|---|---|
| a reducer | each action path, and the whole `Result` it returns |
| `PttController` | the state transition, and the floor grab/release pairing |
| `network/protocol/Messages.kt` | the **literal JSON**, both directions, plus an unknown-key case — and say explicitly whether the server-side counterpart test was updated too |
| `AppSettings` / `ServerMode` | the derived `serverHost`/`serverPort`, and `restore` treating a stored address with no stored mode as Custom |
| `ui/PttUiStatus` | every state's colour and wording |
| `ReconnectPolicy` | backoff growth, the cap, and the reset |
| `FrameAccumulator` | a frame spanning two reads, a partial frame at flush |
| a new `when` branch, sealed variant or enum value | that branch |
| a new failure path | the failure, not only the happy path |

If something is untestable because it touches a platform class directly, **extract an interface** —
`audio/AudioContracts.kt` is the established pattern — and say so rather than skipping it. That is
a production change, so it goes back to the user under the Step 3 rule.

Write the tests, then re-run Step 4.

---

## Step 6 — Report

```
## Unit Tests

### Fixed
- `<Test>.<case>` — <root cause>; failed on <androidTarget / desktop / both>

### Added
- `<Test>.<case>` — covers <what the change introduced>

### Results
- testDebugUnitTest: <N> passed
- :shared:desktopTest: <N> passed

### Needs a production change (not made)
- `<file>` — <what and why>

### Not covered
- <what, and why — e.g. needs an interface extraction, or is one of the documented gaps in
  docs/testing.md § Gaps: the service, the overlay and the widget have no automated tests>

### Counts
<docs/testing.md and CLAUDE.md state 136 unit / 39 UI / 43 instrumented — updated / unchanged>
```

---

## Rules

1. **Never edit production code.** Report what needs changing and wait.
2. **Always run both targets.** A pass on one is half a result.
3. **Literal JSON in protocol tests**, and say whether the server repo's counterpart was updated.
4. **Never `git commit` or `git push`** unless asked.
5. **Report what you did not cover**, explicitly. `docs/testing.md` § Gaps lists what is untested
   on purpose — the foreground service, the overlay and the widget — and that is not a failure to
   fix here.
