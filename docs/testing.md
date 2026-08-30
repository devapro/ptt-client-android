# Testing

## Layout

| Source set | Purpose |
|---|---|
| `app/src/test/` | JVM unit tests — no device required |
| `app/src/androidTest/` | Compose UI tests — needs a running device or emulator |

```bash
./gradlew testDebugUnitTest      # 65 tests
./gradlew lintDebug              # must be clean
./gradlew connectedDebugAndroidTest   # 24 UI tests; ANDROID_SERIAL picks the device
```

## Unit tests (65, all passing)

| Test class | Tests | What it pins down |
|---|---|---|
| `network/ProtocolSerializationTest` | 7 | Exact JSON for every message type — this is a contract with a separate codebase, so the tests assert literal wire text, not just round-trips. Includes tolerance of unknown fields |
| `domain/ReconnectPolicyTest` | 4 | Backoff grows, respects the 30 s cap, never drops below the base, is genuinely jittered, and resets |
| `domain/PttControllerTest` | 22 | The floor state machine, driven through a fake `PttConnection`. Most importantly: **pressing PTT must not open the microphone** — only a server `floor{isSelf:true}` may start transmission. Also `floor_busy`, another user holding the floor disabling `canTalk`, release, incoming audio reaching the player, disconnect resetting state, and `clearError` dropping a stale error without dropping the session. Then the negative space: pressing while disconnected or while somebody else holds the floor sends nothing, a second press does not send a second request, releasing what we never held sends nothing, somebody else's grant clears our pending request, and losing the socket mid-transmission closes the microphone |
| `data/AppSettingsTest` | 10 | Channel clamping (the old UI allowed 0 and negatives), URL construction, name URL-encoding (spaces, symbols, non-ASCII), truncation at 32 characters before the server can refuse it, the protocol version always being present, and the `10.0.2.2` default |
| `data/ThemeModeTest` | 4 | Theme resolution against the system setting, and that an unknown stored value falls back to `SYSTEM` rather than crashing the settings read — settings outlive enum constants |
| `ui/PttUiStatusTest` | 13 | The state→presentation mapping the app screen, the bubble, the widget and the notification all share. Precedence (holding the floor outranks everything; a dead transport outranks stale floor bookkeeping), that only `READY` offers a press, and that the control stays **live** while we hold the floor — the regression behind known-issues #20 |
| `internalserver/InternalPttServerTest` | 5 | The on-device relay over a **real socket**: channel isolation, one-talker-at-a-time, audio without the floor rejected, invalid channel refused |

## Compose UI tests (24, all passing)

Run on a device: `ANDROID_SERIAL=<serial> ./gradlew connectedDebugAndroidTest`. Verified on both a
1080x2400 phone (API 35, portrait branch) and a 2560x1600 tablet (API 34, landscape branch).

| Test class | Tests | What it pins down |
|---|---|---|
| `ui/PTTButtonTest` | 7 | The gesture. The microphone request leaves on touch-**down**, not on release; **the release still fires when the button is disabled mid-press** and when the status changes mid-press (known-issues #20 — losing that release strands the talk floor with the microphone open); a dead control ignores touches and offers no click action; the face carries a word, not just a colour; TalkBack gets a toggle action |
| `ui/MainScreenTest` | 11 | What the screen says and what it dispatches: ready/offline/receiving/pending wording, the offline card showing the address it cannot reach, the missing-permission case offering the fix, channel stepping and its disabled ends, the channel locked while transmitting, error dismissal, connect/disconnect, and the gear |
| `ui/SettingsScreenTest` | 6 | A blank host or an out-of-range port cannot be saved, a valid form saves what was typed, the exact `ws://` URL is shown, and the theme choice round-trips |

These cover the layer the JVM tests cannot reach: gesture lifecycle, semantics, and the wiring
from a rendered control to a `MainAction`.

Testability came from two seams: `network/PttConnection` (so the transport can be faked) and
`audio/AudioContracts.kt` (`VoiceRecorderContract`/`VoicePlayerContract`, since `AudioRecord` and
`AudioTrack` do not exist on the JVM). `PttController` also takes a `settingsProvider` lambda rather
than the DataStore-backed repository, keeping the domain layer free of an Android `Context`.

## Manual verification on two emulators

Unit tests cannot cover the foreground service, the overlay window or the widget. The procedure
used, and what to look for:

1. **Start a relay** on the host: `cd ../ptt-server && ./gradlew run`; check `curl -s localhost:8000/health`.
2. **Install on two devices**, ideally different API levels (e.g. 35 and 34) to cover the
   foreground-service behaviour changes. Set both to host `10.0.2.2`, channel 1.
3. **Relay + floor**: hold PTT on one. The server should log `Floor request … granted`; the other
   device should show `<name> is talking`, a disabled PTT control and "Someone else is talking".
4. **Channel isolation**: put them on different channels and repeat. `/health` should report two
   channels, and the other device must stay idle. (Against the pre-refactor server it would *not*
   have — every channel shared one broadcast group.)
5. **Background PTT**: press HOME on the sender, then tap **Talk** in the notification. Confirm via
   `adb shell dumpsys activity activities` that the launcher is the top activity, and that the
   receiver still shows the transmission. `dumpsys activity services` should report
   `isForeground=true types=0x00000080` (microphone).
6. **Floating bubble**: enable it in Settings (`adb shell appops set <pkg> SYSTEM_ALERT_WINDOW allow`
   to skip the permission screen), press HOME, and confirm the bubble draws over the launcher. Hold
   it to talk — it should turn red, the system microphone privacy indicator should appear, and the
   peer should see the transmission. Drag it and confirm the position survives.
7. **Widget**: long-press the home screen → Widgets → PTTdroid → drag it out. Confirm it shows live
   status, then tap TALK and confirm the peer sees it and the widget flips to "Talking"/"STOP".
8. **Reconnect**: kill the server. Logcat should show growing jittered delays
   (`retrying in 500 ms`, `758`, `1867`, …) rather than a constant 1 s spin. Restart the server and
   confirm both clients rejoin on their own.
9. **Regression**: `adb logcat -d | grep -cE "FATAL EXCEPTION|E AndroidRuntime"` should be 0, and
   grepping for per-frame audio logging should return 0.

**Emulator microphones usually capture silence.** Assert on frame flow, floor transitions and UI
state — not on hearing anything.

Useful for scripted checks:

```bash
adb -s <serial> shell uiautomator dump /sdcard/ui.xml
adb -s <serial> shell cat /sdcard/ui.xml | grep -oE 'text="[^"]*"'
adb -s <serial> exec-out screencap -p > shot.png
adb -s <serial> shell input swipe X Y X Y 5000     # press and hold
```

## Gaps

- No Compose UI tests yet (`ui-test-junit4` is on the classpath and unused).
- No instrumented tests for the service, overlay or widget — these are covered manually above.
- No load test for the relay's drop-under-backpressure behaviour.
