# Testing

## Layout

| Source set | Purpose |
|---|---|
| `shared/src/commonTest/`, `shared/src/jvmCommonTest/` | JVM unit tests in `:shared` (215 cases) — no device required. Moved here from `app/src/test/` in the Phase 5 Compose Multiplatform migration; run on **both** the `androidTarget` and `desktop` compilations |
| `features-api/settings/src/commonTest/` | `SettingsFormStateTest` (55) — lives next to the pure type under test |
| `features/main/src/commonTest/`, `features/settings/src/commonTest/`, `features/history/src/commonTest/` | Each feature's reducer tests, living with the reducers they cover: `MainReducersTest` (14), `SettingsReducersTest` (10), `HistoryReducersTest` (9), plus `SettingsExitObserverTest` (8) in `:features:settings` — the Settings screen's exit rule, which is a screen-root behaviour rather than a reducer's |
| `core/src/androidInstrumentedTest/` | `PTTButtonTest` — moved here with the `:core` widget it tests in step 5 of the module-graph refactor (before that it lived in `shared/src/androidInstrumentedTest/`). Needs a running device or emulator |
| `shared/src/androidInstrumentedTest/` | The TLS and settings-migration integration tests — needs a running device or emulator. Moved here from `app/src/androidTest/` in Phase 5; the network and settings code they exercise still lives in `:shared` |
| `features/main/src/androidInstrumentedTest/`, `features/settings/src/androidInstrumentedTest/` | `MainScreenTest` and `SettingsScreenTest` — moved with their screens into the feature modules in Phase 2 |

```bash
./gradlew testDebugUnitTest        # 303 JVM unit tests across five modules, on the androidTarget compilation — verified, 0 failures
./gradlew desktopTest              # the same 311 again, on the desktop compilation — unqualified, so :core/:ui-shell/:features-api:* no-op alongside the five that have tests
./gradlew :app:testDebugUnitTest     # 0 — nothing left in :app after Phase 5
./gradlew lintDebug :shared:lintDebug              # :app 21 pre-existing findings, :shared 0
ANDROID_SERIAL=<serial> ./gradlew :core:connectedDebugAndroidTest :shared:connectedDebugAndroidTest :features:main:connectedDebugAndroidTest :features:settings:connectedDebugAndroidTest   # 53 tests across the four modules: 47 UI (7 :core + 15 :features:main + 25 :features:settings) + 3 migration + 3 TLS (skipped without -Pandroid.testInstrumentationRunnerArguments.relayHost=...); ANDROID_SERIAL picks the device
```

The counts and test-class breakdown below are re-verified as of the legacy-layer deletion by
actually running each command, not by arithmetic on the table
(`testDebugUnitTest` and `desktopTest` each produce 311 tests — 215 `:shared`,
55 `:features-api:settings`, 14 `:features:main`, 18 `:features:settings`, 9 `:features:history` —
with 0 failures; `:core` has no unit tests, its only suite being the moved instrumented
`PTTButtonTest`;
the four `connectedDebugAndroidTest` tasks above produce 7 + 6 + 15 + 25 = 53 with 50 passing and
3 skipped).

## Unit tests (311, all passing, on both `androidTarget` and `desktop`)

| Test class | Tests | What it pins down |
|---|---|---|
| `network/ProtocolSerializationTest` | 9 | Exact JSON for every message type — this is a contract with a separate codebase, so the tests assert literal wire text, not just round-trips. Includes tolerance of unknown fields |
| `domain/ReconnectPolicyTest` | 4 | Backoff grows, respects the 30 s cap, never drops below the base, is genuinely jittered, and resets |
| `domain/PttControllerTest` | 49 | The floor state machine, driven through a fake `PttConnection`. Most importantly: **pressing PTT must not open the microphone** — only a server `floor{isSelf:true}` may start transmission. Also `floor_busy`, another user holding the floor disabling `canTalk`, release, incoming audio reaching the player, disconnect resetting state, and `clearError` dropping a stale error without dropping the session. Then the negative space: pressing while disconnected or while somebody else holds the floor sends nothing, a second press does not send a second request, releasing what we never held sends nothing, somebody else's grant clears our pending request, and losing the socket mid-transmission closes the microphone. Then the keepalive (known-issues #36), on virtual time: a socket that has gone quiet is probed with `ping` before it is given up on and redialled after three silent intervals; a `pong` puts the countdown back to zero; audio counts as proof of life, so a channel with traffic on it is never probed; the watchdog stops with the socket it was watching; and the `malformed_message` an older relay answers `ping` with is treated as proof of life rather than as a fault to show the user. Then the history wiring: a received transmission lands in the store as a record, a disconnect mid-message saves what arrived, taking the floor purges the records answered inside the 30 s window, live audio arriving silences a history replay, and a replay refuses to start while someone else holds the floor |
| `data/AppSettingsTest` | 26 | Channel clamping (the old UI allowed 0 and negatives), name URL-encoding (spaces, symbols, non-ASCII), truncation at 32 characters before the server can refuse it, the protocol version always being present, the `10.0.2.2` default, and the derived `serverHost`/`serverPort` pair never exposing which `ServerMode` produced it |
| `data/ServerModeTest` | 5 | `ServerMode.restore`'s one compatibility duty: an install with a stored address and no stored mode is Custom, never Default, even if the address happens to equal the built-in one only by coincidence |
| `data/ServerAddressTest` | 16 | `ServerAddress.parse` against everything people actually paste: a bare host, `host:port`, a whole URL with its own scheme and port, an IPv6 literal, credentials (rejected), whitespace from a clipboard, and a port outside the legal range |
| `data/ThemeModeTest` | 4 | Theme resolution against the system setting, and that an unknown stored value falls back to `SYSTEM` rather than crashing the settings read — settings outlive enum constants |
| `data/LanguageModeTest` | 4 | That `SYSTEM` carries no tag at all (so nothing is forced on a device whose locale is already right), that every explicit choice carries its BCP-47 tag, that stored values round-trip, and that an absent or unrecognised one falls back to `SYSTEM` — same reason as `ThemeModeTest`: settings outlive enum constants |
| `data/AudioOutputTest` | 3 | That a fresh install plays out of the **loudspeaker** (known-issues #34 — the old unconditional `USAGE_VOICE_COMMUNICATION` put a good number of devices on the earpiece at call volume), that stored values round-trip, and that an unknown one falls back to the speaker rather than to the quiet route |
| `ui/PttUiStatusTest` | 13 | The state→presentation mapping the app screen, the bubble, the widget and the notification all share. Precedence (holding the floor outranks everything; a dead transport outranks stale floor bookkeeping), that only `READY` offers a press, and that the control stays **live** while we hold the floor — the regression behind known-issues #20 |
| `audio/PcmGainTest` | 5 | The software volume desktop plays through: full gain hands back the very same array (so the default allocates nothing per frame), half gain halves every sample including negative ones, zero gain is silence rather than an untouched buffer, the ends of the 16-bit range survive, and an odd trailing byte is carried through. Little-endian byte order and sign extension are exactly the arithmetic that is wrong silently — a bug here is heard as distortion, not seen as a failure |
| `audio/FrameAccumulatorTest` | 8 | The re-chunking desktop and iOS both use to turn a capture API's arbitrary-sized reads into exact `AudioConfig.FRAME_BYTES` frames: exact frames, short reads spread over several calls, an oversized chunk spanning more than one frame, a remainder carried across calls — all hardware-independent, so it runs in `commonTest` |
| `network/KtorPttConnectionTest` | 2 | The OkHttp transport against a **real socket**, with the embedded relay as its peer: a keepalive probe goes end to end — welcome first (the WebSocket `pingInterval` added for keepalive leaves the handshake alone), then a `ping` written through `KtorPttConnection.send` comes back as a `pong` on the same event flow the controller watches; and with two real connections and a real `PttController` as sender, a receiver gets the controller's bip preamble bytes *before* the microphone audio frames that follow them |
| `internalserver/InternalPttServerTest` | 7 | The on-device relay over a **real socket**: audio relayed inside a channel and not across channels, one talker at a time, audio without the floor rejected, invalid channel refused, `ping` answered with `pong`, `welcome` carrying the shared audio parameters, and every peer getting a byte-for-byte-correct copy of every frame with three or more listeners — it is the third implementation of the server side, so the keepalive and fan-out correctness have to work against it too |
| `features/api/settings/SettingsFormStateTest` (`:features-api:settings`) | 55 | The settings form's validation and merge logic, reachable from `commonTest` for the first time now that it lives outside `SettingsScreen`: seeding from `AppSettings`, every `SettingsEdit` branch, the channel digit filter, `secure` following a pasted scheme versus falling back to the TLS switch, the `Tls` edit keeping a scheme-implied port, `addressProblem`/`customAddress` under Default vs Custom, every `*Error` boundary, `relayConflict`, and `toSettings()` carrying forward the fields the form does not edit |
| `features/settings/reducer/SettingsReducersTest` (`:features:settings`) | 10 | `Open` seeding the form from the repository, `Close` clearing it, `EditSettings` applying to the current form, `SaveSettings` persisting/closing/chaining to `Reconnect`/emitting `ShowMessage` on success and touching nothing when `hasError`, and `RequestOverlayPermission` emitting `UiEvent.RequestOverlayPermission` |
| `features/settings/SettingsExitObserverTest` (`:features:settings`) | 8 | The Settings screen root's exit rule (`SettingsExitObserver`), driven through the real view model, processor, reducers and repository: only a seeded → null **transition** pops (the null frames before `Open`'s disk read lands must not bounce out, and a `form == null` level would), a **rejected** save (known-issues #25's `hasError` guard) keeps the user in Settings with their edit, the arrow's `Close` and the edge it triggers produce exactly **one** pop, an unrelated `SettingsState` write while the form is seeded is not an exit, and a successful save still gets its `settings_saved` message out after the pop. `ReconnectReducer` is stood in for — see the class KDoc; that the seed `LaunchedEffect` runs once per entry is composition keying and needs a device |
| `features/main/reducer/MainReducersTest` (`:features:main`) | 14 | Main's nine reducers: `InitConnection`/`Speak` refusing to touch the launcher or controller without the mic grant (the "pressing PTT must never open the microphone" invariant, at the reducer level), `Speak` requesting the floor without opening the mic and a second press not re-requesting, `Disconnect` stopping the launcher, `SetChannel` persisting + reconnecting, clamping and skipping a channel the session already has, `DismissError` reaching the controller, `StopSpeak` releasing the floor, the audio-output and volume pairs separating apply-from-persist (a drag is many `SetPlaybackVolume`s and exactly one `SavePlaybackVolume` write) |

### Voice history

| Test class | Tests | What it pins down |
|---|---|---|
| `history/BackgroundRecordCaptureTest` | 10 | The capture state machine on virtual time: a backgrounded transmission becomes exactly one record bounded by the floor, a foreground one becomes none, and one that *started* in the foreground stays unrecorded even after the app backgrounds; the floor changing hands closes the old record and opens a new one; a record started in the background survives the app opening mid-message; a silent hold records nothing; a disconnect still saves the partial; the frame cap is two minutes of wire audio; and answering deletes every record that ended inside the 30 s window while older ones survive |
| `history/WavTest` | 6 | The hand-rolled RIFF/PCM16LE codec: a payload round-trips byte for byte, the header describes the wire format it wraps (sample rate, channels, byte rate read back field by field), an empty payload is still a valid file — and anything that is not our canonical layout is refused: too short to hold a header, tampered magic, a non-canonical `fmt` chunk or float format, a foreign sample rate, a `data` size that lies (negative, past EOF, or shorter than the payload, which reads only the declared part) |
| `history/HistoryPlaybackTest` | 5 | The replay pump: a record plays out frame by frame at exactly the wire rate (40 ms per frame on virtual time), stop falls silent on the next tick and clears the playing id, starting a second record cuts over from the first, a player that cannot be set up aborts the replay, and a record whose file is gone fails quietly |
| `history/DataStoreHistoryStoreTest` | 8 | The store over a **real** preferences DataStore and temp-dir file storage (`jvmCommonTest`): a written record reads back identical with its audio, the index stays newest-first and stops at the retention cap (FIFO eviction takes the files with it), `deleteRecord` removes index entry and file together, `deleteEndedAfter` purges exactly the records that ended inside the answer window, the seen-mark only moves forward, a corrupt index reads as empty and the next write heals it, `pruneOrphans` sweeps files the index no longer names, and a failed file write stores nothing and reports the failure |
| `history/HistoryBadgeTest` | 3 | The badge's unread derivation — a record is unread when it ended after `lastViewedAt`, `markSeen` clears it, and a record that ended before the last viewing never lights the badge |
| `features/history/reducer/HistoryReducersTest` (`:features:history`) | 9 | The five history reducers against stubs: `Open` marks everything seen at the clock's value (a clock behind the last visit does not resurrect seen records), `Close` changes nothing and leaves the playing record alone, `PlayRecord` looks the record up and starts the replay (and does nothing for an id that has vanished from the store), `StopRecordPlayback` silences a running replay (a no-op when nothing is playing), and `DeleteRecord` stops a playing record before deleting it |

These run in `commonTest`/`jvmCommonTest` with fakes for the transport, the audio contracts and the
history store; the Android-only wiring they stand in for — the started-Activity foreground count,
the real `AudioTrack` at wire rate, the files under `<filesDir>/history/` — is exercised on
emulators by [`voice-history-test-plan.md`](voice-history-test-plan.md).

### Transport security

| Test class | Tests | What it pins down |
|---|---|---|
| `network/PinnedTrustTest` | 13 | `PinnedTrustManager` against **real generated certificates**. The pinned one is accepted; a different one, a forged chain with the real certificate hidden behind a fake leaf, an empty chain, a null chain and an empty pin are all rejected. An expired certificate is rejected even when the fingerprint matches, and a not-yet-valid one says to check the clock. `getAcceptedIssuers()` stays empty so the manager cannot end up on OkHttp's chain-cleaning path. This is the one place where being wrong is silent: a trust manager that accepts everything looks exactly like a working one |
| `data/CertificatePinTest` | 11 | Every shape a fingerprint arrives in — colons or not, either case, spaces, dashes, wrapped lines from a terminal copy. A partial or non-hex value normalizes to *empty*, never to a pin that matches nothing |
| `internalserver/InternalPttServerAuthTest` | 4 | The on-device relay applies the same token gate as `ptt-server`, over a real socket. Hosting on a phone must not be a way to accidentally run an open relay |

`PinnedTrustTest` generates its certificates with `ktor-network-tls-certificates`, added as a
**test-only** dependency — it is not in the APK.

## Compose UI tests (47, all passing)

Run on a device: `ANDROID_SERIAL=<serial> ./gradlew :core:connectedDebugAndroidTest :shared:connectedDebugAndroidTest :features:main:connectedDebugAndroidTest :features:settings:connectedDebugAndroidTest`
— since Phase 2 the screen tests live with their screens, and since step 5 of the module-graph
refactor `PTTButtonTest` lives in `:core` with the widget it tests, so the 47 split 7 in `:core`
(`PTTButtonTest`), 15 in `:features:main` (`MainScreenTest`) and 25 in `:features:settings`
(`SettingsScreenTest`). Verified on both a 1080x2400 phone (API 35, portrait branch) and a
2560x1600 tablet (API 34, landscape branch).
The `:shared` instrumented source set also carries `data/settings/SettingsDataStoreMigrationTest` (3
tests, always runs) and `network/TlsRelayIntegrationTest` (3 tests, opt-in — see "Pinned TLS against
a real relay" below), which is where the full 53 across the four modules in the command at the top
of this page comes from.

| Test class | Module | Tests | What it pins down |
|---|---|---|---|
| `ui/PTTButtonTest` | `:core` | 7 | The gesture. The microphone request leaves on touch-**down**, not on release; **the release still fires when the button is disabled mid-press** and when the status changes mid-press (known-issues #20 — losing that release strands the talk floor with the microphone open); a dead control ignores touches and offers no click action; the face carries a word, not just a colour; TalkBack gets a toggle action |
| `ui/MainScreenTest` | `:features:main` | 15 | What the screen says and what it dispatches: ready/offline/receiving/pending wording, the offline card showing the address it cannot reach, the missing-permission case offering the fix, channel stepping and its disabled ends, the channel locked while transmitting, error dismissal, connect/disconnect, and the gear. Plus the audio-out pill: the live route named in words rather than only coloured, the earpiece key dispatching `SetAudioOutput` with the live one reading as selected, and a platform with one output device (`canRouteAudioOutput = false`) getting the slider and no route keys |
| `ui/SettingsScreenTest` | `:features:settings` | 25 | The form's two jobs: making a broken relay address impossible to save, and showing the URL it will actually dial. Default hides the address rather than pre-filling a field; choosing Custom reveals it and switching back keeps what was typed; a blank address, an out-of-range port and credentials in the URL each block Save with their own message; a pasted `https://` tunnel URL brings 443 with it and turns encryption on, and turning encryption back off keeps that port instead of silently dropping to 80. Plus the security fields: the fingerprint box appears only with encryption on, a half-typed fingerprint cannot be saved, an empty one can (a tunnel needs no pin), the token is masked and saved trimmed, and hosting a relay while asking for encryption is called out. Plus the appearance/language selectors: theme and language can each be forced independently of the system, and the stored value of each comes back as the selected segment |

These cover the layer the JVM tests cannot reach: gesture lifecycle, semantics, and the wiring
from a rendered control to a `MainAction`.

Not covered here: `HistoryScreen` and the top-bar badge have no Compose test — the rules they
display (badge derivation, play/stop/delete reachability) live in reducers the JVM tests cover, and
the screen itself is exercised on devices by the two-emulator plan below.

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

The voice-history feature extends this procedure with its own backgrounded-capture cases and a
regression pass over every step above: [`voice-history-test-plan.md`](voice-history-test-plan.md).
It reuses these same preconditions and helpers.

## Pinned TLS against a real relay (opt-in)

`androidTest/network/TlsRelayIntegrationTest` is the one thing JVM unit tests cannot settle:
Android's TLS stack is Conscrypt, not the JDK's, and OkHttp treats a hand-written trust manager
differently there. A pin that verifies correctly under `testDebugUnitTest` can still fail to
connect on a handset, so it is checked where it actually runs.

It needs a relay, so it skips itself unless pointed at one:

```bash
# start a TLS relay and read its fingerprint
cd ../ptt-server
docker compose -f docker-compose.yml -f docker-compose.tls.yml up -d --build
FP=$(docker exec ptt-server cat /app/certs/ptt.p12.sha256 | tr -d ':')

cd ../ptt-client-android
ANDROID_SERIAL=<serial> ./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.github.devapro.pttdroid.network.TlsRelayIntegrationTest \
  -Pandroid.testInstrumentationRunnerArguments.relayHost=10.0.2.2 \
  -Pandroid.testInstrumentationRunnerArguments.relayPort=8443 \
  -Pandroid.testInstrumentationRunnerArguments.relayFingerprint=$FP \
  -Pandroid.testInstrumentationRunnerArguments.relayToken=<the PTT_AUTH_TOKEN from .env>
```

Three cases: a pinned self-signed relay is reachable; the wrong fingerprint is refused *with a
message that names the problem*, because that string is what the user sees in the error banner;
and a relay that wants a token refuses a client without one.

## The release pipeline

`.github/workflows/release.yml` is not covered by a test, so its risky parts were exercised by
hand against a throwaway repository before it shipped: the tag-versus-`version.properties` check
(matching tag, mismatched tag, and a non-tag `workflow_dispatch`), the F-Droid index build over a
signed APK, and a **second** release proving the repository accumulates versions rather than
replacing them — that last one is the failure that would silently strand users on an old build.

The repository config is generated with a YAML dumper rather than a heredoc, which was verified
with a password containing `:`, `#` and braces; the heredoc version produced a file that parsed
as something else. The published branch was checked to contain no `config.yml`, no keystore and
no `.p12`.

## iOS

`shared/src/iosMain` and `ui-shell/src/iosMain` have no unit tests of their own — Kotlin/Native
tests for `iosArm64`/`iosSimulatorArm64` need a real Apple toolchain to *execute* (this repository's
regular machine and `ci.yml` are Linux, which can only frontend-compile iOS Kotlin — see
`docs/platform-support.md`).
`.github/workflows/ios.yml` (`macos-latest`) is the only place iOS code actually links and runs,
and even there the check is "does the framework link and does `iosApp.xcodeproj` build", not a test
suite. Everything `commonTest`/`jvmCommonTest` already covers (the domain layer, the reducers, the
protocol, settings parsing, TLS pinning logic) is exercised on iOS too in the sense that it is the
same source compiling against the same `expect`/`actual` seams — but the iOS-only code behind those
seams (`PttHttpClient.ios.kt`, `PinnedTrust.ios.kt`, `IosAudio.kt`, `SettingsDataStore.ios.kt`) is
unverified beyond "it frontend-compiles" until a real device or the macOS CI job exercises it.

## Gaps

- No instrumented tests for the service, overlay or widget — these are covered manually above.
  (All three are Android-only — see `docs/platform-support.md`.)
- No load test for the relay's drop-under-backpressure behaviour.
- The TLS integration test is opt-in and not part of the default gate, because it needs a relay
  it cannot start itself.
- No automated tests at all for `:desktopApp`'s or iOS's own thin entry points (`Main.kt`,
  `:ui-shell`'s `MainViewController.kt`) — everything they wire together is covered by `:shared`'s
  and the feature modules' tests, but the wiring itself is smoke-tested by hand
  (`:desktopApp:packageDeb` + running the result; `ios.yml`'s Xcode build).
- `deploy/deploy.sh` in the server repo has its preflight and failure paths exercised, and a
  `--dry-run` mode, but the remote half is not covered by an automated test — it needs a second
  host.

## Related

- [`voice-history-test-plan.md`](voice-history-test-plan.md) — the two-emulator run sheet for the
  history feature, plus the regression pass
- [`platform-support.md`](platform-support.md) — what each platform has and does not have, and
  where each number in this document comes from
