# Voice history — two-emulator test plan

End-to-end verification of the voice-history feature on real Android builds, plus a regression
pass proving the pre-existing features still work. It is written to be executed by an agent with
an **Android emulator MCP** (tap, swipe, key input, screenshot, UI-tree dump, shell) against
**two already-running emulators**, with an `adb` fallback named for every step so the plan is
runnable by a human too.

The behaviour under test is specified in `architecture.md#history-capture` and summarised for
users in `features.md` §"Voice history". The JVM-side coverage of the same rules is in
`testing.md` §"Voice history"; this plan covers what fakes on a JVM cannot: the started-Activity
foreground count, real files on disk, the DataStore index surviving process death, and the UI as
rendered.

## Conventions

| | |
|---|---|
| **Device A** | the *receiver* — the phone whose background state and history are under test (the DUT). Assumed serial `emulator-5554`; confirm with `adb devices` |
| **Device B** | the *talker* — produces the transmissions A may or may not record. Assumed serial `emulator-5556` |
| `PKG` | `com.github.devapro.pttdroid` |
| "Open the app" | tap the launcher icon, or `adb -s <A> shell am start -n $PKG/.MainActivity` |
| "Background it" | `adb -s <A> shell input keyevent KEYCODE_HOME` |
| **Assert on state, not sound** | emulator microphones capture silence and playback is inaudible through the harness. A test passes because of UI text, content-desc, file lists and logcat — never because something was heard. Playing audio out loud is a bonus check, never a gate |

Prefer the MCP's UI-tree dump when it has one; the `uidump` helper below is the adb equivalent
and produces the same text.

## Prerequisites (run once)

1. **Relay on the host** — `cd ../ptt-server && ./gradlew run` (background it);
   check `curl -s localhost:8000/health`. Both emulators reach it as `10.0.2.2`.
2. **Install the current build on both devices**:
   `ANDROID_SERIAL=<serial> ./gradlew :app:installDebug`, then on each device:
   `adb -s <serial> shell pm grant $PKG android.permission.RECORD_AUDIO`
   and (API 33+) `adb -s <serial> shell pm grant $PKG android.permission.POST_NOTIFICATIONS`
   to skip the runtime dialogs.
3. **Settings on both** — open the app → gear → **Relay: Custom**, host `10.0.2.2`, port 8000,
   encryption off; **Channel 1**; display names distinct (`Alice` on A, `Bob` on B). Save and
   reconnect; the status card must read `Channel clear`.
4. **Baseline** — on A, `adb -s A shell run-as $PKG ls files/history/ 2>/dev/null` → note the
   count (may be absent — that is also zero). Record both logcat starts with
   `adb -s <serial> logcat -c` so every "logcat" assertion below reads only this session.

### Shared helpers

```bash
uidump()   { adb -s "$1" shell uiautomator dump /sdcard/ui.xml >/dev/null
             adb -s "$1" shell cat /sdcard/ui.xml \
             | grep -oE '(text|content-desc)="[^"]*"' | sort -u; }
shot()     { adb -s "$1" exec-out screencap -p > "$2"; }
hist()     { adb -s "$1" shell run-as $PKG ls files/history/; }                    # .wav list
index()    { adb -s "$1" shell run-as $PKG ls -l files/datastore/; }               # index file
talk_b()   { # talk on B for ~$2 s via its notification — the reliable hold
             adb -s B shell cmd statusbar expand-notifications
             uidump B | grep -q 'text="Talk"' && adb -s B shell input tap <Talk-x> <Talk-y>
             sleep "$2"
             adb -s B shell cmd statusbar expand-notifications
             adb -s B shell input tap <Stop-x> <Stop-y>; }
```

`Talk`/`Stop` coordinates come from the notification dump (re-dump after each expand; the shade
animation must settle first). For in-app holds instead: dump A's tree, find the node with
`content-desc="Toggle transmission"` (or the `HOLD` text), tap-and-hold its centre with
`adb -s A shell input swipe X Y X Y 5000`.

## Part A — voice history

Each case lists **Action** and **Pass when**. The pass criterion for "a record exists" is always
*both* the History-screen list and the `files/history/` count — the UI lying about the store (or
the reverse) is exactly the class of bug this plan exists to catch.

| # | Case | Action | Pass when |
|---|---|---|---|
| **H1** | Foreground never records | A open on Main (`Channel clear`). On B, hold PTT ~8 s, release | `uidump A` showed `Bob is talking` live; open A's history → the empty message `Messages received while the app was in the background are saved here`; `hist A` unchanged |
| **H2** | Background records | HOME on A. `talk_b 10`. Bring A to foreground | Top bar shows the badge (H3's screenshot method); history list has **1** row: sender `Bob`, a duration ≈ `0:08–0:12`; `hist A` gained one `.wav` |
| **H3** | Badge appears and clears on open | With H2's badge up: `shot A badge-on.png`; tap the icon with content-desc `Open message history`; back out with `Go back`; `shot A badge-off.png` | Badge dot visible in `badge-on.png`, gone in `badge-off.png` (top-bar crop diff). Opening history is the *only* thing that cleared it — no notification was ever raised (`adb -s A shell dumpsys notification --noredact | grep -ci $PKG` shows no history notification added during the run) |
| **H4** | Opening mid-message still saves | HOME on A. B holds the floor; ~5 s **into** the hold, launch A's activity (it comes to foreground *while* B talks); then B releases | History has 1 record (the foreground flip does not discard it — the flag is sampled at start) |
| **H5** | Foreground-started is never recorded | A open on Main. B starts talking; 5 s in, HOME A; B keeps talking 10 s more, releases | History stays empty; `hist A` unchanged |
| **H6** | Floor boundary: two talks = two records | HOME on A. `talk_b 6`; wait 3 s; `talk_b 6`; open A | **Two** rows, newest first, each ≈ 0:06 — not one 0:12. Rows read `Bob` (name arrives with the floor message) |
| **H7** | Play, pause-by-tap, natural end | On the history screen with H6's records (app stays foreground; nobody holds the floor). Tap `Play message` on the newest row; dump; tap again; dump. Then start a play and wait it out | First dump shows the row flipped to `Stop playback`; second flips back and playback stops; the natural end flips it back on its own after ≈ the row's duration. (Frames are silent — the icon state *is* the assertion) |
| **H8** | Live audio silences a replay | Start a play (as H7). Immediately `talk_b 5` | The replaying row's icon returns to `Play message` as soon as the transmission arrives; live receiving state (`Bob is talking`) shows normally afterwards |
| **H9** | Replay refuses while the floor is held | B holds the floor (notification Talk). On A's history screen, tap `Play message`; then B taps Stop | The icon never flips to `Stop playback` while the floor is held; `hist A` untouched |
| **H10** | Answering within 30 s purges the burst | From H6's state, HOME; make **three** ~3 s talks on B with ~5 s gaps (3 records, every end inside the last 30 s); open A; **within 30 s of the last one's end**, hold PTT on A until `You are on air`, release | History is **empty**; `hist A` lost those three files. The answer deleted the whole burst, not just one row |
| **H11** | Older records survive the answer | Repeat H10's three talks, then wait **>30 s** before answering on A | All three records still present after the answer |
| **H12** | Disconnect mid-message saves the partial | HOME on A. B holds the floor. **Kill the relay** (host: Ctrl-C / stop the Gradle task). Wait for A's logcat to report the dropped session; restart the relay; wait for both to reconnect | History has 1 record whose duration is clearly **shorter** than the hold (the pre-kill portion, not zero, not the whole thing) |
| **H13** | Everything survives a relaunch | Unread badge present (H2 state). `adb -s A shell am force-stop $PKG`; reopen the app; check history; close app; reopen again without opening history | After the first reopen the badge is there (unread state is derived from the stored `last_viewed`, not in-memory); opening history clears it and it **stays cleared** across the second reopen; the records are still listed |
| **H14** | Per-row delete | On the history screen, tap `Delete message` on an idle row → it disappears, `hist A` loses its file. Start playing another row, then tap `Delete message` on **that** row | Both gone; the playing row stops first (no `Stop playback` icon left on any row); live session unaffected |
| **H15** | Storage on disk is what we claim | With ≥1 record: `hist A`; pull one: `adb -s A exec-out run-as $PKG cat files/history/<name>.wav > /tmp/r.wav`; `xxd -l 44 /tmp/r.wav` | Filename ends `.wav`; header reads `RIFF`…`WAVE`, format `0100`, channels `0100`, rate `803e0000` (16000), bits `1000` (16); payload size = file size − 44 is a multiple of 1280 (our 40 ms frame) and ≈ 32 000 bytes per second of the row's shown duration. `index A` shows `history_index.preferences_pb` present and non-empty |
| **H16** | Orphan sweep | `adb -s A shell am force-stop $PKG`; `adb -s A shell run-as $PKG sh -c 'echo x > files/history/orphan.wav'`; start the app (service create prunes) | `orphan.wav` gone; indexed records still present |
| **S1** | 2-minute cap *(slow, optional)* | HOME on A. B: notification **Talk**, wait 150 s, **Stop** | One record, duration `2:00` exactly (capped, not 2:30) |
| **S2** | Retention cap 50 *(slow, scripted)* | Script: HOME on A; loop 55× { `talk_b 3`; sleep 2 }. Open A | History lists exactly **50** rows, newest first; `hist A` holds 50 files; the first 5 talks are gone |
| **S3** | Overlay/widget do not count as foreground *(optional)* | A on the home screen with the floating bubble visible (overlay drawn, app Activity *stopped*). `talk_b 8` | A record appears — only the Activity's started state counts as foreground |

**A-side session hygiene between cases:** the floor state carries across cases, so before every
case confirm `uidump A | grep -c 'Bob is talking'` is 0 and the status line is `Channel clear`.

## Part B — regression of pre-existing features

The same procedure `testing.md` §"Manual verification on two emulators" uses; run it fully after
Part A, on the same build. B-numbers map 1:1 to its steps 3–9, then the additions.

| # | Case | Pass when |
|---|---|---|
| R1 | **Relay + floor** (step 3): hold PTT on B | Server logs `Floor request … granted`; A shows `Bob is talking`, disabled control, reason `Someone else is talking` |
| R2 | **Channel isolation** (step 4): A→channel 2 | `/health` reports two channels; B's transmissions reach nothing on A (`Channel clear` stays) |
| R3 | **Background PTT** (step 5): HOME on A, notification **Talk** | `dumpsys activity activities` shows the launcher on top; B shows `Alice is talking`; `dumpsys activity services` shows `isForeground=true types=0x00000080` |
| R4 | **Floating bubble** (step 6): `appops set $PKG SYSTEM_ALERT_WINDOW allow`, enable in Settings, HOME | Bubble draws over the launcher; holding it turns it `ON AIR` and B sees the transmission; drag persists position; hidden while A's own app is foreground |
| R5 | **Widget** (step 7) | Widget shows live status; TALK flips it to Talking/`STOP` and B receives |
| R6 | **Reconnect** (step 8): kill the relay, restart it | Logcat on A shows growing jittered `retrying in …` delays; both clients rejoin unattended; history feature keeps working after the reconnect (re-run H2) |
| R7 | **Clean logs** (step 9) | `adb logcat -d \| grep -cE "FATAL EXCEPTION\|E AndroidRuntime"` → 0 on both; per-frame audio logging → 0 |
| R8 | **Settings save/reconnect**: change name, channel, then Save and reconnect | Snackbar shown, session reconnects with the new name visible to the peer (`<new name> is talking`) |
| R9 | **Theme + language**: force Dark; switch language to Russian and back | Whole UI re-themes/re-translates immediately on Android (settings screen labels change; gear/history labels re-translate) |
| R10 | **Error banner + clear**: relay down, note banner; restart, `Dismiss` | Banner names the failure; dismiss leaves the healthy session alone (`clearError` must not drop it) |
| R11 | **Keepalive**: both connected, channel silent > 45 s | Logcat shows `ping`/`pong` (or absence of redial); session does **not** flap |
| R12 | **Audio-out pill**: toggle `Play through the speaker` / `Play through the earpiece`; move volume | The live route name in the pill flips `SPEAKER`/`EARPIECE`; value survives relaunch |
| R13 | **Connect/Disconnect** from the status card | Disconnect stops the session and the service notification; Connect rejoins |
| R14 | **History did not break the session**: quick H2 sanity after all of the above | A record still arrives exactly as in Part A |

## Execution notes

- **Order:** Prerequisites → H1…H16 → S1/S2 (optional, budget ~20 min for S2) → Part B. Some
  cases consume earlier ones' state (H3 needs H2; H10–H11 need H6's flow) — run in order.
- **Failure protocol:** a failed case is logged with its ID and the `uidump`/screenshot artefacts
  and execution continues; only a crash (logcat `FATAL`) or a wedged emulator forces a stop —
  reboot the emulator, re-run that case's setup lines, note the reboot in the log.
- **Artefacts:** keep everything under `test-run-<date>/`: one `shot-<case>-<n>.png` per visual
  check, one `ui-<case>.xml` per dump used as evidence, `logcat-A.txt`/`logcat-B.txt` at the end.

### Result log

| Case | Result | Evidence | Notes |
|---|---|---|---|
| H1 | ☐ pass / ☐ fail | | |
| H2 | | | |
| … | | | |
| R14 | | | |

## Related

- [`testing.md`](testing.md) — automated coverage behind this plan, and the manual procedure
  Part B restates
- [`architecture.md`](architecture.md#history-capture) — the spec each H-case maps to:
  floor boundary, one-shot foreground sampling, the 30 s purge window, retention, playback rules
- [`features.md`](features.md) — the user-facing description of the same behaviour
