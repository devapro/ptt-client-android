---
name: protocol-change
description: Lands a PTT wire-protocol change across both repos — the canonical spec, the server, the client, the on-device relay, and both serialization test suites. Use when a message type, field, enum value or @SerialName changes, or a message is added. Triggers on "change the protocol", "add a message", "bump the protocol version", or any edit to a Messages.kt.
argument-hint: "[what the protocol change is]"
---

You are making a change to the wire protocol shared by `ptt-client-android` and `ptt-server`.

**Why this skill exists:** there is **no shared artefact** between the two repos, and a third
implementation — the client's own on-device relay — implements the server side as well. Nothing in
either build catches drift. A rename that lands in three of the four places compiles cleanly on
both sides and fails only when a real client meets a real relay.

## What the change is

$ARGUMENTS

## The four implementations, in order

| # | File | Repo | Role |
|---|---|---|---|
| 1 | `../ptt-server/docs/protocol.md` | server | **canonical spec — changes first, always** |
| 2 | `../ptt-server/src/main/kotlin/.../protocol/Messages.kt` | server | the relay |
| 3 | `shared/src/commonMain/kotlin/com/github/devapro/pttdroid/network/protocol/Messages.kt` | client | the client; compiles for Android, desktop **and** iOS |
| 4 | `shared/src/jvmCommonMain/kotlin/com/github/devapro/pttdroid/internalserver/InternalPttServer.kt` | client | the on-device relay — JVM-only (Ktor CIO), Android + desktop, unreachable from iOS (`domain/canHostRelay` is `false` there) |

Plus the two test suites:

| File | Repo |
|---|---|
| `shared/src/commonTest/kotlin/com/github/devapro/pttdroid/network/ProtocolSerializationTest.kt` | client |
| the matching serialization test in `../ptt-server` | server |

---

## Phase 1 — Read before writing

```bash
# The canonical spec
cat ../ptt-server/docs/protocol.md

# All four implementations
cat ../ptt-server/src/main/kotlin/**/protocol/Messages.kt
cat shared/src/commonMain/kotlin/com/github/devapro/pttdroid/network/protocol/Messages.kt
cat shared/src/jvmCommonMain/kotlin/com/github/devapro/pttdroid/internalserver/InternalPttServer.kt

# Both test suites
cat shared/src/commonTest/kotlin/com/github/devapro/pttdroid/network/ProtocolSerializationTest.kt
ls ../ptt-server/src/test/kotlin/**/
```

Confirm the sibling repo is actually there and on the branch you expect:

```bash
git -C ../ptt-server status --short --branch
git -C ../ptt-server log --oneline -5
```

If `../ptt-server` is missing, **stop and say so.** Do not make a client-only protocol change and
call it done — that is exactly the failure this skill exists to prevent.

---

## Phase 2 — Decide the compatibility story

Answer these before editing anything, and put the answers in your Phase 3 summary:

1. **Is this backward compatible?** A new optional field with a default is. A renamed
   `@SerialName`, a field made required, a removed message type, or a changed enum value is **not**.
2. **If it is not compatible, does it need a version bump?** The spec defines how version
   negotiation works — read it rather than assuming. Adding audio compression, for instance, is
   called out in `docs/known-issues.md` as needing one.
3. **What happens to an old client against a new relay, and a new client against an old relay?**
   Name both directions. Users update the app and the relay at different times.
4. **Does the on-device relay need the same change?** Almost always yes — it is a relay. If not,
   say why.

---

## Phase 3 — Present the plan, then wait

Do not edit until the user has seen this:

```
## Protocol Change Plan

**Change**: <what, in one sentence>
**Compatible**: yes / no — <which direction breaks>
**Version bump**: needed / not needed — <why>

### 1. ../ptt-server/docs/protocol.md
<the spec edit — this lands first>

### 2. ../ptt-server/.../protocol/Messages.kt
<the server edit>

### 3. shared/src/commonMain/.../network/protocol/Messages.kt
<the client edit>

### 4. shared/src/jvmCommonMain/.../internalserver/InternalPttServer.kt
<the on-device relay edit>

### Tests
- ProtocolSerializationTest.kt — <the literal JSON to assert>
- ../ptt-server/<test> — <the matching assertion>

### Other docs
<docs/architecture.md, docs/audio-pipeline.md, or none — say which and why>
```

---

## Phase 4 — Edit, in this order

**The spec first.** `../ptt-server/docs/protocol.md` is canonical; the implementations follow it,
not each other. Editing code first and back-filling the spec produces a spec that documents a bug.

Then 2, 3 and 4. For each, check the details that do not fail a compile:

- **`@SerialName` values match byte for byte** across all three `Messages.kt`-equivalents. Kotlin
  will happily serialize `"audioFrame"` on one side and `"audio_frame"` on the other.
- **Nullability matches.** A field non-null on the sender and nullable on the receiver is fine; the
  reverse throws at parse time on a real message.
- **Defaults match.** A default on one side and not the other means the two disagree about what an
  absent field means.
- **Enum values match**, including their `@SerialName`s. An unknown value from the peer must not
  throw — check how the existing code handles it before adding one.
- **`ignoreUnknownKeys` is set consistently** on both sides' `Json` configuration; that is what
  makes additive changes safe.
- **`InternalPttServer` handles the message on the server side**, not just parses it. A new message
  type that the embedded relay parses and drops is a silent failure when the user hosts locally.
- **Nothing lands in `iosMain`.** The protocol types are `commonMain` — they compile for
  Kotlin/Native. No `java.*` types, no `kotlinx.serialization` feature that Native lacks.

---

## Phase 5 — Tests, with literal JSON

**Assert the literal expected JSON string, never a round-trip.** A round-trip test
(`decode(encode(x)) == x`) passes while both sides drift together — it verifies the codec, not the
contract. The existing `ProtocolSerializationTest` asserts literals; follow it.

Client side — `shared/src/commonTest/.../ProtocolSerializationTest.kt`. It is in `commonTest`, so
it runs on **both** `androidTarget` and `desktop`.

Server side — the matching test in `../ptt-server`. **The same literal string** must appear in
both. That shared literal is the only mechanical link between the two repos; if the two tests
assert different JSON, they are both passing against different contracts.

For a new message type add, at minimum:
- encode → exact JSON
- decode of that exact JSON → the expected object
- decode of a message with an unknown extra key → does not throw
- decode of a message missing an optional field → the documented default

---

## Phase 6 — Run both gates

```bash
# Client
./gradlew assembleDebug testDebugUnitTest lintDebug
./gradlew :shared:desktopTest
./gradlew -PenableIosTargets=true :shared:compileKotlinIosSimulatorArm64 :shared:compileKotlinIosArm64

# Server
cd ../ptt-server && ./gradlew build
```

The iOS compile matters here specifically: the protocol types live in `commonMain` and compile for
Kotlin/Native, and a serialization construct that works on the JVM does not always work there.

Use the `build-gate` agent for the client half if you want the Gradle output kept out of the
conversation.

### End-to-end check

A green build on both sides does not prove the wire agrees. If a relay is available:

```bash
cd ../ptt-server && ./gradlew run
curl -s localhost:8000/health
```

then run the client against it — an emulator reaches the host at `10.0.2.2`, never `localhost` —
and confirm the new message actually crosses. Also exercise the **on-device relay** path
(Settings → host locally), which is the implementation most likely to have been missed.

---

## Phase 7 — Docs and report

Update in the same change, if the change touches them:
- `docs/architecture.md` — if the message flow or the connection lifecycle changed
- `docs/audio-pipeline.md` — if the frame format, sample rate or framing changed
- `docs/known-issues.md` — if this closes a listed gap, or opens one
- `docs/platform-support.md`, `README.md` **and** `docs/index.html` — all three, if what a platform
  can do changed

Report:

```
## Protocol Change Complete

**Change**: <one sentence>
**Compatible**: <yes/no; which direction breaks>

### Edited
1. ../ptt-server/docs/protocol.md — <what>
2. ../ptt-server/.../Messages.kt — <what>
3. shared/src/commonMain/.../Messages.kt — <what>
4. shared/src/jvmCommonMain/.../InternalPttServer.kt — <what>

### Tests
- ProtocolSerializationTest.kt — <N assertions added; the literal>
- ../ptt-server/<test> — <same literal>

### Gates
- client: assembleDebug ✅ testDebugUnitTest ✅ :shared:desktopTest ✅ iOS compile ✅ lintDebug ✅
- server: build ✅
- end-to-end: <verified against a running relay / not verified — say which>

### Docs
<files updated, or "none needed — <why>">

### Not done
<anything left, explicitly>
```

---

## Rules

1. **The spec changes first.** Always.
2. **Four implementations or none.** A change in three is worse than a change in none, because it
   looks finished.
3. **Never `git commit` or `git push`** in either repo unless the user explicitly asks. Two repos
   means two histories to avoid polluting.
4. **Literal JSON in tests**, in both repos, identical.
5. **If `../ptt-server` is not present, stop.** Say what the client-side change would be and that
   it cannot be landed alone.
6. **Say what you did not verify.** "Both builds pass" is not "the wire agrees" — be explicit about
   which one you actually demonstrated.
