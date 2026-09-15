---
name: qa-device
description: Validates an Android feature on a connected device or emulator using a provided test plan. Accepts free-form prose or structured step-by-step test plans (inline text or a file path), runs each test case in its own mobile-devices sub-agent with a normalized result contract, captures a screenshot at the key verification point and a screen recording of the whole case, and produces a QA report saved to a file. Use this skill whenever the user says "QA this feature", "run the test plan", "validate on device", "test this on the emulator", "check if [feature] works", "execute these test cases", or provides a test plan and wants it run against the app. Trigger even when the user doesn't say "QA" explicitly — if they describe test steps and want them validated on a real device, this is the skill. Formerly `/qa-feature` / `qa-feature`.
argument-hint: [test plan — free-form text, numbered steps, or a path to a .md/.txt file]
disable-model-invocation: true
---

You are running a QA validation session against the Android app on a connected device or emulator. The app is already installed — do not build or install it unless the user explicitly asks.

## Input

The test plan is provided below as the skill argument:

$ARGUMENTS

If the argument looks like a file path (e.g. ends in `.md` or `.txt`, or starts with `/` or `./`), read that file first to get the full test plan content.

---

## Phase 1 — Parse the Test Plan

Read the test plan and extract individual test cases. Each test case needs:

- **Title** — a short descriptive name
- **Steps** — ordered actions to perform (navigate, tap, scroll, input text, etc.)
- **Expected result** — what should be visible or true after the steps complete

**Handling free-form input**: If the test plan is prose (e.g. "Go to the Economic Calendar, check that events are listed, tap one and make sure the details screen opens"), break it into logical test cases. Don't over-split — related steps that verify one behavior belong in one test case.

**Handling structured input**: If steps already have numbered lists or "Expected:" labels, preserve that structure directly.

Print the parsed test cases for the user to review:

```
=== Parsed Test Cases ===

TC-1: [Title]
  Steps: [step 1] → [step 2] → ...
  Expected: [what to verify]

TC-2: [Title]
  ...

Total: N test cases
```

Ask: **"Shall I proceed with these test cases, or do you want to adjust anything?"**

Do not execute anything until the user confirms.

---

## Phase 2 — Prepare the Report Directory

Create a report directory before executing any test cases:

```
qa-reports/YYYY-MM-DD-HHmm-[feature-slug]/
├── screenshots/     (empty, sub-agents will populate this)
└── videos/          (empty, sub-agents will populate this)
```

- Derive `[feature-slug]` from the feature name or the test plan title (lowercase, hyphens, no spaces)
- Use the current date/time for the directory name so reports don't overwrite each other
- Create the directories: try `Bash mkdir -p`; if Bash is denied, create them implicitly by writing placeholder files with the `Write` tool (e.g. `qa-reports/…/screenshots/.gitkeep`, `qa-reports/…/videos/.gitkeep`)
- Remember the **absolute path** to the report directory — you will pass it verbatim to every sub-agent in Phase 3.

The final report will be written here as `report.md` in Phase 4.

---

## Phase 3 — Execute the Test Cases

Run test cases **sequentially** — the app state from one case may carry over to the next, and parallel runs on the same device would interfere.

For **each** test case, launch **one** `mobile-devices` sub-agent via the `Agent` tool (`subagent_type: "mobile-devices"`) — one invocation per case. Do not batch cases into a single sub-agent, and do not run cases via direct `mcp__mobile__*` tools in the parent conversation. This keeps screenshot / UI-tree noise out of the parent context and gives you a normalized result block that drops straight into the report.

### The per-case sub-agent prompt

Every sub-agent invocation MUST use the template below, filling in the bracketed fields. Do not paraphrase or shorten the execution protocol — the sub-agent relies on it verbatim to produce a parseable return.

```
You are executing ONE QA test case against the Android app on the connected device. Follow the steps, verify the expected result, and return only the structured block described at the end — no extra commentary.

Test case: TC-[N] — [title]
Starting app state: [either "cold start" for the first case, OR the previous case's EndState verbatim]
Package: com.fusionmedia.investing
Report dir (absolute path): [absolute-path-to-report-dir]

Steps:
1. [step]
2. [step]
...

Expected: [expected result]

Execution protocol:
1. Call `list_devices`; call `set_device` if not already set. If no device is connected, return `Verdict: BLOCKED` with a reason and stop.
2. Capture the device serial from `list_devices` — pass it to every adb command as `-s <serial>`.
3. If starting state is "cold start", `stop_app` then `launch_app` on the package. Otherwise assume the device is already on the screen described in `Starting app state` — do not reset.
4. Start a chained screen recorder in the background BEFORE executing the test steps.
   The loop auto-starts a new clip when the 180 s device cap is hit, so long cases are
   fully covered across `tc-[N].mp4`, `tc-[N]-part2.mp4`, etc.:
     nohup bash -c '
       p=1
       while [ ! -f /tmp/qa-rec-stop-[N] ] && [ "$p" -le 6 ]; do
         s=""; [ "$p" -gt 1 ] && s="-part$p"
         timeout 190 adb -s <serial> shell screenrecord --time-limit 180 --bit-rate 6000000 /sdcard/tc-[N]${s}.mp4 2>/dev/null
         p=$((p+1))
       done
     ' >/dev/null 2>&1 &
     echo $! > /tmp/qa-rec-pid-[N]
   If the app launch itself fails, skip video: touch /tmp/qa-rec-stop-[N] and pkill screenrecord.
5. Execute the steps in order using `find_and_tap` / `tap_by_text` / `input_text` / `wait_for_element` / etc. If a step fails (element missing, crash), continue to step 6 with the failure recorded — do not throw.
6. Take a screenshot at the key verification point:
     mcp__mobile__screenshot(path="[report-dir]/screenshots/tc-[N].png")
7. Stop the chained recorder, then pull all clip parts to the host:
     touch /tmp/qa-rec-stop-[N]
     adb -s <serial> shell pkill -SIGINT screenrecord 2>/dev/null || true
     sleep 3
     for _f in $(adb -s <serial> shell "cd /sdcard && ls tc-[N].mp4 tc-[N]-part*.mp4 2>/dev/null" | tr -d '\r'); do
       adb -s <serial> pull "/sdcard/$_f" "[report-dir]/videos/$_f"
       adb -s <serial> shell rm "/sdcard/$_f"
     done
     rm -f /tmp/qa-rec-stop-[N] /tmp/qa-rec-pid-[N]
   Use SIGINT (not SIGKILL) — SIGINT lets the encoder finalise the mp4 header. If no clips were
   produced, note it in the `Video:` field and continue; do not fail the whole case over a missing recording.
8. Collect error logs: `get_logs(package="com.fusionmedia.investing", level="E")`. Keep only lines relevant to this run.
9. If the app crashed, `launch_app` again so the next case can pick up cleanly.

Return EXACTLY this block, one field per line, no extra text before or after:

Verdict: PASS|FAIL|BLOCKED
Observed: <1–3 sentences describing what actually happened on screen, in the observed order>
Screenshot: [report-dir]/screenshots/tc-[N].png   (or "MISSING: <reason>")
Video: [report-dir]/videos/tc-[N].mp4[, [report-dir]/videos/tc-[N]-part2.mp4, ...]  (list all parts comma-separated; or "MISSING: <reason>")
Errors: <one line per relevant error, or "none">
EndState: <one short sentence describing which screen / state the app is on now, so the next case can pick up from here>
```

### Parent-side coordination

Between sub-agent invocations, the parent:

- Parses the returned block by field name — do not reflow prose or infer missing fields.
- Passes the previous case's `EndState` **verbatim** into the next case's `Starting app state`.
- If a case returns `Verdict: BLOCKED`, stop the run and report back to the user rather than continuing blindly.
- The `Video:` field may contain multiple comma-separated paths (`tc-[N].mp4, tc-[N]-part2.mp4, …`) for long cases that produced chained clips. Preserve all paths when writing Phase 4.
- Appends the parsed fields into the report structure in Phase 4.

If two consecutive cases are independent (no shared state), set the next case's `Starting app state` to `"cold start"` regardless of the previous EndState — the sub-agent will then `stop_app` + `launch_app` before recording.

### Fallback

If the `Agent` tool is unavailable in this session, run the same protocol inline in the parent conversation. The output schema above is still what you record in Phase 4.

---

## Phase 4 — Write the QA Report

Once all test cases are done, write `[report-dir]/report.md`:

````markdown
# QA Report — [Feature Name]

**Date**: YYYY-MM-DD HH:MM
**Device**: [device ID or name from first agent run]
**App**: com.fusionmedia.investing
**Overall result**: X / Y PASSED

---

## Summary

| # | Test Case | Result |
|---|-----------|--------|
| 1 | [title] | ✅ PASS |
| 2 | [title] | ❌ FAIL |
| … | … | … |

---

## Test Cases

### TC-1: [Title] — ✅ PASS

**Steps:**
1. [step]
2. [step]

**Expected:** [expected result]

**Observed:** [what the sub-agent reported — 1–3 sentences, taken from its `Observed:` field]

**Screenshot:** ![TC-1](screenshots/tc-1.png)

**Video:** [tc-1.mp4](videos/tc-1.mp4) *(add · [tc-1-part2.mp4](videos/tc-1-part2.mp4) · … for each additional clip)*

---

### TC-2: [Title] — ❌ FAIL

**Steps:**
…

**Expected:** …

**Observed:** [describe the failure — what was on screen vs. what was expected, from the sub-agent's `Observed:` field]

**Screenshot:** ![TC-2](screenshots/tc-2.png)

**Video:** [tc-2.mp4](videos/tc-2.mp4) *(add · [tc-2-part2.mp4](videos/tc-2-part2.mp4) · … for each additional clip)*

**Errors:**
```
[relevant log lines from the sub-agent's Errors: field]
```

---

## Notes

[Any observations that apply to the run overall — device state, skipped cases, flaky behavior, etc.]
````

After writing the report, tell the user:

```
QA report saved to: qa-reports/[dir]/report.md
Screenshots:        qa-reports/[dir]/screenshots/
Videos:             qa-reports/[dir]/videos/

Result: X / Y test cases PASSED
[List any failures with one-line descriptions]
```

---

## Text input — pick the right method

How you enter text matters when the test is about *input behavior* (not just the final value):

- **Latin / numeric fields, value only** — `mcp__mobile__input(text=...)` or `adb shell input text "..."` is fine and fast.
- **CJK (Korean / Japanese / Chinese), or ANY test of IME composition / cursor behavior** — you **must tap the on-screen keys**. `input text` and `input(text=...)` *commit* text directly and never drive the IME composition region (`setComposingText`), so they cannot reproduce composition-region bugs. Tapping real jamo keys is the only faithful path.
  - For Korean, generate the exact tap sequence with the bundled script instead of hand-deriving key coordinates:
    ```bash
    # Default: adb device-pixel taps (most robust, MCP-scaling-independent)
    python3 ${CLAUDE_SKILL_DIR}/scripts/hangul_to_taps.py 삼성전자
    # Or a mobile MCP flow-batch JSON to paste into mcp__mobile__flow(action=batch):
    python3 ${CLAUDE_SKILL_DIR}/scripts/hangul_to_taps.py --format mcp 삼성전자
    ```
    The script decomposes syllables, expands compound vowels/finals, and handles shift for double consonants. Coordinates are calibrated for the standard `sdk_gphone64` 1080x2400 emulator with Gboard 두벌식; re-calibrate `KEYS` in the script if keys land wrong.
- **Cheap composition verification** — read the `ui.tree` hint after each tap (`New: "삼" edittext`) to watch syllables assemble; you do **not** need a screenshot per keystroke. Screenshot only at the final verification point.
- The IME runs in its own window — keyboard keys are **not** in the app's `ui.tree`. Tap them by coordinate.

---

## Switching app editions

To exercise a feature in another in-app edition (language/region), switch via the intent-based script — far faster and more reliable than navigating More → Select Edition:

```bash
bash scripts/screenshots/switch_edition.sh <langID> [device_serial]
# e.g. Korean=18, Japanese=11, Chinese=6, German=8, Arabic=3, English=1
```

- Works in **debug/profiling builds only** (`LaunchDebugArgumentHandler`); market builds ignore the extra.
- `Lang.kt` is the source of truth for `langID` (`api-services/api-service-editions/.../Lang.kt`). The **qa-capture-lqa** skill derives its `enum → short code → langID` mapping from that file in `.claude/skills/qa-capture-lqa/scripts/lqa.py`; ENGLISH is the one edition whose short code (`WWW`) is not its country code.
- **Restore the original edition** at the end of the run so you leave the app in a known state.

---

## Recording video

Video recording is **built into the per-case sub-agent contract in Phase 3** — every test case produces one or more clips under `videos/` automatically. You do not need to run `screenrecord` from the parent.

Reference notes (the sub-agent already applies these):

- The sub-agent runs a **chained recording loop**: when `screenrecord`'s 180 s device cap is hit, the loop immediately starts a new clip (`tc-[N]-part2.mp4`, `tc-[N]-part3.mp4`, …) so long cases are fully covered. Stop the loop with `pkill -SIGINT screenrecord` after setting the stop flag — the loop exits cleanly and all clips are pulled to `videos/`.
- The inner `adb shell screenrecord` is wrapped with `timeout 190` so a hung ADB connection cannot block the loop forever — 190 s gives 10 s headroom beyond the 180 s limit before a hard kill.
- The loop caps at 6 parts (≈ 18 min total). Split longer cases into multiple test cases.
- Stop each clip with SIGINT (not SIGKILL) — SIGINT lets the encoder finalise the mp4 header before the process exits.
- `sleep 3` after pkill gives the device time to flush the mp4 header before pulling.
- All clip parts are listed comma-separated in the `Video:` return field and linked individually in the Phase 4 report.

---

## Behavioral Notes

- **App already installed**: Never trigger a build or install unless the user says so.
- **Reset between test cases**: If two test cases are independent, start each from the app home screen (`stop_app` then `launch_app`) so they don't contaminate each other. If the test plan is an end-to-end flow where cases continue from each other's state, preserve state between them — use your judgment based on how the plan is written.
- **Ambiguous expected results**: If an expected result is vague (e.g. "verify it works"), check that the primary screen content is visible and no crash occurred. Note what you checked in the report.
- **Screenshots**: At a minimum, take one screenshot per test case at the point of verification. For multi-step cases, take one at the start and one at the key assertion point.
- **No device connected**: If `list_devices` returns nothing, stop and tell the user to connect a device or start an emulator.
- **App not responding / crash**: Capture logs immediately and mark the test case as FAIL. Continue with the next test case after restarting the app.
- **Screenshot save failure**: Note it in the report and continue — don't let a screenshot failure block the QA run.
- **Coordinate spaces differ**: `mcp__mobile__input(action=tap, x, y)` interprets coordinates in the **screen-capture** pixel space (e.g. 432x960) and auto-scales to the device. `ui.tree` coordinates and `adb shell input tap` are **device** pixels (1080x2400). Read tap coordinates off the same capture you're tapping from. For on-screen keyboard keys, `adb shell input tap` (device pixels) is the most robust because it doesn't depend on capture scaling.
- **CJK IME setup**: the stock emulator Gboard ships **no** Korean/CJK subtype. Add one via Settings → System → Languages & input → On-screen keyboard → Gboard → Languages → **Add keyboard** → search the language → pick the layout (두벌식 for Korean) → Done. Then activate it on screen with the globe key or a long-press on the space bar. The plain AOSP Latin keyboard cannot compose Hangul/Kana/Hanzi at all.
- **Gboard floating/one-handed lockup**: if the keyboard collapses to a vertical or floating pill and the full keys won't render (it shows only mic/backspace/enter/emoji/lang), it's stuck in floating mode. Dragging the pill to the bottom can re-dock it; the reliable fix is `adb -s <serial> reboot`, wait for `sys.boot_completed`, then **re-add any custom IME layout** (a reboot/data-clear drops the added CJK layout). This is a keyboard glitch, not an app bug.
- **Post-reboot network lag**: after `adb reboot`, the app's API backend may stay unreachable for a while even though `adb shell ping 8.8.8.8` and `ping www.investing.com` already succeed (the app caches a "No Internet Connection" state). Restart the app (`stop` + `launch`) and retry data-dependent checks (search results, feeds) a couple of times before reporting a real failure. Distinguish this from a genuine bug in the report.
- **Black screenshots**: if captures come back solid black, the screen is asleep — wake it with `adb shell input keyevent 224` (KEYCODE_WAKEUP), then recapture.
