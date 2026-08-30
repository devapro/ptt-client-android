# Known issues

## Fixed in the refactor — do not reintroduce

| # | Was | Now |
|---|---|---|
| 1 | **Build failed on a fresh clone**: the manifest set `android:fullBackupContent="@xml/backup_rules"` but the resource was missing *and* gitignored | `res/xml/backup_rules.xml` exists and is tracked |
| 2 | **Server ignored channels** — `/channel/*` was routed but the wildcard was never read, so every client on every channel shared one broadcast group and channel switching was cosmetic | `/channel/{channelId}` is parsed and validated; each channel is its own group. Covered by tests in both repos |
| 3 | **Serial fan-out with cross-session failure** — `send()` was awaited per peer inside the sender's read loop, so one slow peer stalled everyone, and a write failure to peer B ended peer A's session | Per-session bounded `Channel<Frame>(64, DROP_OLDEST)` + one writer coroutine each; broadcast is a non-suspending `trySend` |
| 4 | `maxFrameSize = Long.MAX_VALUE` — unbounded allocation | Bounded, and oversized frames are rejected with `frame_too_large` |
| 5 | **Short reads transmitted stale bytes** — the whole 8192-byte buffer was sent regardless of `read()`'s return | `buffer.copyOf(read)` (`audio/VoiceRecorder.kt`) |
| 6 | **A released `AudioTrack` stayed reachable** — `stopPlay()` released it but left the field non-null, so the next frame wrote to a dead object | `release()` nulls the field; `prepare()`/`release()` are idempotent |
| 7 | `VoiceRecorder.destroy()` was never called, and `create()` allocated without releasing the previous instance | Symmetric lifecycle owned by `PttController` |
| 8 | **Connection errors were swallowed** — the reconnect call in `onError` was commented out | `KtorPttConnection` emits `Disconnected` with the cause; the controller reconnects |
| 9 | **Reconnect was a flat `delay(1000L)` forever** | `domain/ReconnectPolicy` — exponential backoff, full jitter, 30 s cap |
| 10 | **Per-frame logging in release builds** — Timber was planted unconditionally and both audio paths logged every buffer | No logging on the frame path; `Timber.plant` gated on `BuildConfig.DEBUG` |
| 11 | `UtilPermission.resultListeners` was never cleared and retained the Activity; legacy `onRequestPermissionsResult` | Activity Result API; both permission classes deleted |
| 12 | Channel could go to 0 and negative | Clamped to 1..99 in the UI, the reducer and the settings layer |
| 13 | `Theme.kt` set the deprecated `window.statusBarColor`, a no-op under targetSdk 36's enforced edge-to-edge | `enableEdgeToEdge()` + `safeDrawingPadding()` |
| 14 | Two AGP template stub tests, no CI | 103 unit + 32 UI tests, and three workflows: CI, tagged releases, and Pages |
| 15 | Deprecated `android.preference.PreferenceManager` | DataStore (`data/settings/`) |
| 16 | Dead code: `PTTWebSocketListener`, unused permission constants, unused Ktor dependency | Removed; Ktor is now the actual client |
| 17 | Hardcoded `ws://192.168.100.4:8000` | User-configurable host/port in Settings |
| 18 | Session lifecycle owned by `MainActivity.onStart/onStop`, so backgrounding killed a live transmission | Owned by `PttForegroundService` via `PttController` |
| 19 | `ActionProcessor` re-validated reducer uniqueness on **every** dispatch and scanned the set linearly | Validated once at construction; reducers indexed by `KClass` |
| 20 | **The floor could be stranded, microphone open, until the app was restarted.** A disabled Compose button drops its gesture detector; tying the PTT button's `enabled` flag to "the channel is free" made it disable at the instant the floor was granted, tearing down the in-flight press so the release half of the gesture never ran. Nobody else on the channel could talk again | The gesture uses `awaitEachGesture` with `pointerInput(Unit)` and the release in a `finally`, so it cannot be skipped; `PttUiStatus.isControlLive` keeps the control live while we hold the floor. Pinned by `PttUiStatusTest` |
| 21 | Stock Compose-wizard theme: template purple, dynamic colour on, and `Type.kt`/`Color.kt` still carrying their commented-out template blocks. Meaning-bearing state colours were hardcoded per surface while everything else followed the wallpaper | A purpose-built dark-first palette with dynamic colour off, and one `ui/PttUiStatus` shared by the screen, the bubble, the widget and the notification. See [`ui-design.md`](ui-design.md) |
| 22 | `MainEvent.ShowMessage` was collected and dropped (`-> Unit`), so `settings_saved` was an unused string and saving silently did nothing visible | Snackbar host in both screens; `SaveSettingsReducer` emits it |
| 23 | `lastError` was small red text with no way to clear it — a refusal from twenty minutes ago kept looking like a live fault | Dismissible `ErrorBanner` + `MainAction.DismissError` → `PttController.clearError()` |
| 24 | No way to connect or disconnect from the UI at all: the session started as a side effect of granting the microphone permission | Connect/Disconnect in the status card |
| 25 | Saving settings ran six suspending repository writes from a Compose callback — six DataStore commits, six emissions, and a reconnect could read a new host with the old port | `SettingsRepository.save` is one transaction, dispatched as `MainAction.SaveSettings` |
| 26 | The floating bubble stayed on screen over the app itself, landing on top of the button it duplicates | `OverlayController.setAppVisible`, driven by the Activity's start/stop |
| 28 | **The floating bubble was `200` raw pixels** — a different physical size on every device, thumb-sized at 1x and a pinhead at 4x | Sized in dp (100.dp) from `displayMetrics.density` |
| 29 | The bubble showed no channel, so the one surface visible while another app is in front could not tell you which channel a press would go out on | It carries the channel number, a microphone glyph struck through when a press would do nothing, and the state word |
| 30 | **Undefined Material colour roles fell back to the baseline purple scheme.** `secondaryContainer` and the rest were never set, so a lavender chip appeared inside the segmented control on an otherwise green-and-slate screen | Every role is defined in both schemes (`ui/theme/Theme.kt`) |
| 31 | No Compose UI tests at all — `ui-test-junit4` was on the classpath and unused, so gesture lifecycle and semantics were verified only by hand | 24 instrumented tests across the button, the main screen and settings, run on a phone and a tablet |
| 32 | **`peers` arrived before `welcome`.** The protocol spec says `welcome` is the first thing a client sees, but the join broadcast sent the new session its own peer count first — in all three implementations. Found by an integration test that read the first control frame and got `Peers` | The join broadcast excludes the joiner (`PttChannel.broadcastLocked(exceptId = ...)` in the server repo, `ServerChannel.broadcastPeers(exceptId = ...)` here); the count is already in `welcome`. Pinned by a test in `ChannelRelayTest` |
| 33 | **A `ToggleRow`'s label was inert.** Only the switch itself responded, so the text saying what the setting does was not a target for touch or for a screen reader — and on a tablet the switch sits at the far edge of a 640dp form | The whole row is `toggleable` with `Role.Switch`; the `Switch` takes `onCheckedChange = null` so there is one target, not two |
| 27 | `InternalPttServerTest` raced: it released the floor on one socket and immediately requested it on another, with no ordering guarantee between them, so the server was free to answer `floor_busy` | The test waits for the release broadcast before requesting |

## Still open

- **No accounts.** The access token is one shared secret for everybody: no per-handset
  credentials, no revocation, no audit trail. Changing it means telling everyone the new one.
- **Pinning does not rotate.** Replacing the relay's keypair means re-pairing every client. Fine
  for a handful of handsets; a permanently public relay wants a real certificate or a tunnel.
- **The on-device relay is plaintext only.** Turning on encryption while hosting locally is
  called out in Settings, but the embedded relay does not serve `wss://` — generating and
  managing a keystore on a handset is a lot for what it buys.
- **No audio compression.** Raw 16 kHz mono PCM is ~32 kB/s. Fine on a LAN, wasteful over the
  internet — Opus would be the natural next step, and would need a protocol version bump.
- **No public test server.** Deliberately out of scope; the server repo ships a `Dockerfile`,
  three compose files and `deploy/deploy.sh` so a deployment is one command.
- **The service, the overlay and the widget have no automated tests.** The button, the main screen
  and settings do (32 Compose UI tests); the three background surfaces are still verified by hand,
  because each needs a real service, a `WindowManager` window, or a host launcher.
- **Legacy launcher rasters are generated, not designed.** `mipmap-*/ic_launcher.png` (API 24–25
  only) is rendered by a script, since no image tooling is available here; API 26+ uses the
  adaptive vector icon. Regenerate from a real asset pipeline if one appears.
- **No landscape layout for Settings** beyond scrolling — the form is a single column at any size.
- **No jitter buffer** beyond `AudioTrack`'s own, and no packet reordering or loss concealment.

## Gotchas

- **`compileSdk` must stay 36** and two AGP 9 opt-out flags must stay in `gradle.properties`. See
  [`build-and-run.md`](build-and-run.md#build-constraints--do-not-helpfully-bump-these) — several
  current AndroidX versions silently require compileSdk 37.
- **Cleartext must be permitted.** `ws://` is blocked by Android's network security policy;
  `res/xml/network_security_config.xml` allows it. The old Java-WebSocket client bypassed the policy
  entirely, so this only surfaced after moving to OkHttp. It stays permitted because `ws://` is
  still the default on a LAN — `wss://` is opt-in per relay, not a global switch.
- **A pinned trust manager must advertise no accepted issuers.** Returning them puts it on
  OkHttp's chain-cleaning path, which tries to build a chain up to a known root; a self-signed
  certificate has none, and the connection fails with `SSLPeerUnverifiedException` even though the
  fingerprint matched. The cleaner only runs when a `CertificatePinner` is configured, and none is
  — the trust manager decides.
- **A masked text field still reports its raw value to the accessibility tree.** `InputText`
  carries the real string whatever the `VisualTransformation` does, so a UI test asserting
  "the token is not visible" passes whether or not anything is hidden. Assert on the rendered
  bullets instead.
- **HTTP strips leading and trailing whitespace from header values.** A token that differs from
  the real one only by surrounding spaces gets in, which is why the settings layer trims before
  storing rather than relying on the comparison.
- **Lint flags every hand-written trust manager and hostname verifier** (`CustomX509TrustManager`,
  `BadHostnameVerifier`) and it is right to. The two suppressions in `network/tls/PinnedTrust.kt`
  are annotated with why; do not add more without the same justification.
- **A microphone foreground service cannot be started from the background** on Android 14+. Start it
  from a visible Activity or an exempt gesture (notification action, widget tap); the widget and
  overlay only toggle transmit on an already-running service.
- **The widget cannot do hold-to-talk.** RemoteViews deliver only discrete clicks, so it is a
  toggle. Real press-and-hold lives in the app and the floating bubble.
- **`WindowManager` calls must be on the main thread**, but the session scope is on
  `Dispatchers.IO` — `OverlayController` keeps its own main-thread scope.
- **A freshly placed widget has no state** until something changes, so `provideGlance` seeds it from
  the controller.
- **Emulator microphones capture silence.** Verify frame flow and floor state, not audibility.
