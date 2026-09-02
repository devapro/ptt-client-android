---
name: qa-device
description: Runs a test plan against ptt-client-android on connected devices — one mobile-devices sub-agent per case, screenshot and recording each, then a QA report. Handles the two-device talk-floor cases and the held press a tap cannot exercise. Use for "QA this", "test on the emulator", "check the floor handoff", or any test steps to run on a device.
argument-hint: "[test plan — prose, numbered steps, or a path to a .md/.txt file]"
disable-model-invocation: true
---

You are running a QA session against `ptt-client-android` on connected devices or emulators. The
app is assumed installed — do not build or install unless the user asks.

## Input

$ARGUMENTS

If that looks like a file path (ends in `.md`/`.txt`, or starts with `/` or `./`), read it first.

---

## What makes QA on this app different

Read all four before parsing the plan — each one changes how a case must be written.

1. **It needs a relay.** Nothing works without one. Either start the sibling server or use the
   app's own on-device relay:
   ```bash
   cd ../ptt-server && ./gradlew run          # then:
   curl -s localhost:8000/health
   ```
   **An emulator reaches the host machine at `10.0.2.2`, never `localhost`** — inside the emulator,
   `localhost` *is* the emulator. `10.0.2.2:8000` is already the shipped default
   (`relay.properties`). On a physical handset, Default reaches nothing — set a Custom address.

2. **Anything about the talk floor needs two participants.** One device cannot demonstrate an
   exclusive resource. See `docs/testing.md` § "Manual verification on two emulators". A case
   written for one device can verify connection state, settings and the UI — never the floor.

3. **The primary control is press-and-hold, and a tap does not exercise it.** Use
   `input` (`action: "long_press"`) with an explicit duration, or a zero-distance timed swipe:
   ```bash
   adb -s <serial> shell input swipe <x> <y> <x> <y> 3000   # a 3-second hold
   ```
   A `tap` on the PTT button proves nothing about the gesture, which is where this app's worst
   defect class lives (`docs/known-issues.md` #20 — a lost release strands the floor with the
   microphone open).

4. **Emulator microphones capture silence.** Never write a case whose expected result is
   audibility. Verify **frame flow and floor state** instead — the UI readout on both devices, and
   the lifecycle log lines (connect, disconnect, floor grant/release, device open/close, logged at
   info). There is deliberately **no per-frame logging**, so the absence of a line per frame is
   correct, not a failure.

### The four surfaces

`ui/PttUiStatus` is the single mapping from `PttState` to a colour and a word, and four surfaces
render it: the **app screen**, the Android **floating bubble**, the Glance **widget**, and the
foreground-service **notification**. A case that says "the status is green" is ambiguous — always
name the surface. A state-change case is much stronger when it checks two surfaces, because a
drift between them is a real bug this architecture is designed to prevent.

Those three background surfaces also have **no automated tests** (`docs/testing.md` § Gaps), which
makes them the highest-value thing a manual pass can cover.

### Permissions and platform gates

- `-g` on install grants runtime permissions. Without RECORD_AUDIO the app cannot transmit and the
  failure looks like a relay problem.
- POST_NOTIFICATIONS is revocable from API 33 — a missing notification may be a permission, not a
  bug.
- The overlay needs SYSTEM_ALERT_WINDOW, which is a **settings toggle, not a runtime grant**. If
  the bubble never appears, check that first.
- **A microphone foreground service cannot be started from the background** on Android 14+. Start
  transmission from the visible Activity, the notification action, or a widget tap — never from a
  backgrounded state, and do not report that as a bug.
- **The widget is a toggle, not hold-to-talk.** RemoteViews deliver only discrete clicks. Expected
  behaviour, not a defect.

---

## Phase 1 — Parse the plan

Extract per case: **Title**, **Steps**, **Expected result**, and **Devices** (one or two).

For prose input, break it into logical cases without over-splitting — related steps verifying one
behaviour are one case. For structured input, preserve the structure.

Then print for review:

```
=== Parsed Test Cases ===

TC-1: [title]                                  [1 device]
  Steps: [step] → [step]
  Expected: [what to verify, on which surface]

TC-2: [title]                                  [2 devices — A: <serial>, B: <serial>]
  Steps (A): ...
  Steps (B): ...
  Expected: [floor state on both]

Total: N cases (M need two devices)

Relay: <running at ... / on-device relay / NOT AVAILABLE>
Devices: <serials found>
```

Flag these before proceeding:
- a case about the floor that lists one device — it cannot verify what it claims
- a case whose expected result is audibility — rewrite it as frame flow + floor state
- a `tap` on the PTT control where a hold was meant
- no relay available — say so; most cases will be BLOCKED

Ask: **"Shall I proceed with these cases, or adjust anything?"** Do not execute until confirmed.

---

## Phase 2 — Prepare the report directory

```
.claude/data/qa-reports/YYYY-MM-DD-HHmm-<slug>/
├── screenshots/
└── videos/
```

`.claude/data/` is gitignored, so reports do not pollute the tree. Derive `<slug>` from the plan's
subject. Remember the **absolute path** — you pass it verbatim to every sub-agent.

Record the environment once, for the report header:

```bash
adb devices -l
curl -s localhost:8000/health || echo "no host relay"
adb -s <serial> shell getprop ro.build.version.release
```

---

## Phase 3 — Execute

Run cases **sequentially** — app state carries over, and parallel runs on one device interfere.

Launch **one `mobile-devices` sub-agent per case** via the `Agent` tool
(`subagent_type: "mobile-devices"`). Do not batch cases into one agent, and do not drive
`mcp__mobile__*` from the parent — that is what keeps screenshot and UI-tree noise out of the
parent context and gives you a parseable result block.

### Single-device case prompt

```
You are executing ONE QA test case against ptt-client-android on the connected device. Follow the
steps, verify the expected result, and return only the structured block at the end — no extra
commentary.

Test case: TC-[N] — [title]
Starting app state: [either "cold start" for the first case, OR the previous case's EndState verbatim]
Package: com.github.devapro.pttdroid
Device serial: [serial]
Relay: [address, and whether it is reachable]
Report dir (absolute): [path]

Steps:
1. [step]
...

Expected: [expected result, naming the surface — app screen / bubble / widget / notification]

Execution protocol:
1. `device` (action: "list"); `device` (action: "set") if needed. If no device, return
   `Verdict: BLOCKED` and stop.
2. If starting state is "cold start", `app` (action: "stop") then (action: "launch") on the
   package. Otherwise assume the device is on the screen described and do not reset.
3. Start a chained recorder in the background BEFORE the steps. The device caps screenrecord at
   180 s, so the loop starts a new clip when it hits the cap:
     nohup bash -c '
       p=1
       while [ ! -f /tmp/ptt-qa-stop-[N] ] && [ "$p" -le 6 ]; do
         s=""; [ "$p" -gt 1 ] && s="-part$p"
         timeout 190 adb -s [serial] shell screenrecord --time-limit 180 --bit-rate 6000000 /sdcard/tc-[N]${s}.mp4 2>/dev/null
         p=$((p+1))
       done
     ' >/dev/null 2>&1 &
   If launch itself fails, skip video: touch /tmp/ptt-qa-stop-[N] and pkill screenrecord.
4. `system` (action: "clear_logs") so the lifecycle lines you read afterwards belong to this case.
5. Execute the steps. **For the PTT control use a HELD press, never a tap** — `input`
   (action: "long_press") with an explicit duration, or
   `adb -s [serial] shell input swipe <x> <y> <x> <y> <ms>` (identical start and end = a timed
   hold). Read coordinates off `ui` (action: "tree") — those are device pixels, as is
   `adb shell input tap`; `input` (action: "tap", x, y) is in screen-capture pixel space and is
   auto-scaled. If a step fails, record it and continue — do not throw.
6. Screenshot at the verification point: `screen` (action: "capture"), then save with
   `adb -s [serial] exec-out screencap -p > [report-dir]/screenshots/tc-[N].png`
   (the MCP capture returns the image into your context and writes nothing to disk).
7. Stop the recorder and pull the clips:
     touch /tmp/ptt-qa-stop-[N]
     adb -s [serial] shell pkill -SIGINT screenrecord 2>/dev/null || true
     sleep 3
     for _f in $(adb -s [serial] shell "cd /sdcard && ls tc-[N].mp4 tc-[N]-part*.mp4 2>/dev/null" | tr -d '\r'); do
       adb -s [serial] pull "/sdcard/$_f" "[report-dir]/videos/$_f"
       adb -s [serial] shell rm "/sdcard/$_f"
     done
     rm -f /tmp/ptt-qa-stop-[N]
   Use SIGINT, not SIGKILL — SIGINT lets the encoder finalise the mp4 header. A missing recording
   is a note, not a case failure.
8. Read the lifecycle log:
   `system` (action: "logs", package: "com.github.devapro.pttdroid")
   Extract only the lines about connect, disconnect, floor grant/release, device open/close, plus
   any errors. **There is no per-frame logging by design** — do not report its absence as a
   failure, and do not report a per-frame log line as normal (that would be a defect).
9. If the app crashed, `app` (action: "launch") again so the next case starts cleanly.

Return EXACTLY this block, one field per line:

Verdict: PASS|FAIL|BLOCKED
Observed: <1–3 sentences on what actually happened, in order>
Surfaces: <which of app screen / bubble / widget / notification you checked, and what each showed>
Screenshot: [report-dir]/screenshots/tc-[N].png   (or "MISSING: <reason>")
Video: [report-dir]/videos/tc-[N].mp4[, ...-part2.mp4]   (list all parts; or "MISSING: <reason>")
Lifecycle: <the connect/disconnect/floor/device lines, or "none">
Errors: <one line per relevant error, or "none">
EndState: <one sentence on which screen/state the app is in now>
```

### Two-device case prompt

For a floor case, launch **one** sub-agent that drives **both** devices — a handoff cannot be
verified by two agents that cannot see each other's timing. Extend the prompt with:

```
Device A serial: [serial]   (the talker)
Device B serial: [serial]   (the listener)

Both devices must be connected to the SAME relay. Verify that first — read Settings on each, or
confirm both show Connected — and return BLOCKED if they are not.

Floor protocol:
1. Confirm both are Connected and on the same channel.
2. On A, begin a HELD press and keep it held.
3. While A is still holding, read B's state — B must show a remote speaker, and B's own control
   must be refused. Screenshot B here; this is the verification point.
4. Release on A. Confirm A returns to idle and B's remote-speaker indication clears.
5. Now hold on B and confirm it is granted — this is what proves the floor was actually released
   and not merely hidden.
6. Report the state of A and B at each step, by serial.

Return the same block, with `Observed` and `Surfaces` naming device A and device B separately.
```

### Parent-side coordination

- Parse the returned block **by field name**. Do not reflow prose or infer a missing field.
- Pass the previous case's `EndState` **verbatim** as the next case's `Starting app state`. If the
  two cases are independent, use `"cold start"` instead.
- A `Verdict: BLOCKED` stops the run — report to the user rather than continuing blindly. A missing
  relay or a second device that is not connected is the usual cause, and every later case would
  block for the same reason.
- `Video:` may list several comma-separated parts; keep all of them.

---

## Phase 4 — Write the report

Write `[report-dir]/report.md`:

````markdown
# QA Report — [subject]

**Date**: YYYY-MM-DD HH:MM
**App**: com.github.devapro.pttdroid
**Devices**: A = [serial] (Android [ver]), B = [serial] (Android [ver])
**Relay**: [address] — [reachable / on-device / unavailable]
**Overall**: X / Y PASSED

---

## Summary

| # | Test Case | Devices | Result |
|---|-----------|---------|--------|
| 1 | [title] | A | ✅ PASS |
| 2 | [title] | A + B | ❌ FAIL |

---

## Test Cases

### TC-1: [title] — ✅ PASS

**Steps:** 1. … 2. …

**Expected:** …

**Observed:** [from the sub-agent's `Observed:`]

**Surfaces checked:** [from `Surfaces:` — e.g. "app screen: amber/Connecting; notification: amber/Connecting"]

**Screenshot:** ![TC-1](screenshots/tc-1.png)

**Video:** [tc-1.mp4](videos/tc-1.mp4)

**Lifecycle:**
```
[the connect/disconnect/floor/device lines]
```

---

### TC-2: [title] — ❌ FAIL

… same shape, with:

**Errors:**
```
[relevant log lines]
```

---

## Notes

[Run-wide observations: relay availability, permissions granted, whether the overlay toggle was on,
device state, flakiness, anything skipped.]

## Not verified

[Explicitly: audibility was not and cannot be verified on emulators; any case that needed a second
device and did not get one; anything BLOCKED.]
````

Then tell the user:

```
QA report:  .claude/data/qa-reports/<dir>/report.md
Screenshots: .../screenshots/    Videos: .../videos/

Result: X / Y PASSED
[each failure, one line]
```

---

## Behavioural notes

- **Never build or install** unless asked. If you must:
  `./gradlew assembleDebug && adb -s <serial> install -r -g app/build/outputs/apk/debug/app-debug.apk`
  — the `-g` matters.
- **Never conclude anything about audibility.** Emulator microphones capture silence.
- **Name the surface** in every state observation. Four surfaces render the same state.
- **A held press, not a tap**, for the PTT control.
- **Coordinate spaces differ**: `ui` (action: "tree") coordinates and `adb shell input tap` are
  device pixels; `input` (action: "tap", x, y) is screen-capture pixel space and auto-scales. Read
  taps off the same space you are tapping in.
- **Black screenshots** mean the screen is asleep — `adb shell input keyevent 224` (WAKEUP), then
  recapture.
- **A connection failure with a matching certificate fingerprint** is usually the pinned trust
  manager (`docs/known-issues.md`); a `ws://` connection refused outright is usually the network
  security config or a wrong host. Distinguish these in the report rather than calling both "cannot
  connect".
- **Restore state at the end** — leave the relay setting, the channel, and the overlay toggle as
  you found them, and say in the Notes if you could not.
- **Ambiguous expected results**: if a case says "verify it works", check that the primary screen
  renders, the state readout is coherent across the surfaces you can see, and nothing crashed —
  and write down exactly what you checked.
