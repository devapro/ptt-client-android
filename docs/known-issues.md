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
| 14 | Two AGP template stub tests, no CI | 136 unit tests (on both the Android and desktop targets) + 39 Compose UI tests, and four workflows: CI, iOS, tagged releases, and Pages |
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

See [`platform-support.md`](platform-support.md) for the full Android/desktop/iOS matrix these
overlap with.

- **No accounts.** The access token is one shared secret for everybody: no per-handset
  credentials, no revocation, no audit trail. Changing it means telling everyone the new one.
- **Pinning does not rotate.** Replacing the relay's keypair means re-pairing every client. Fine
  for a handful of handsets; a permanently public relay wants a real certificate or a tunnel.
- **The on-device relay is plaintext only.** Turning on encryption while hosting locally is
  called out in Settings, but the embedded relay does not serve `wss://` — generating and
  managing a keystore on a handset is a lot for what it buys.
- **No audio compression.** Raw 16 kHz mono PCM is ~32 kB/s. Fine on a LAN, wasteful over the
  internet — Opus would be the natural next step, and would need a protocol version bump.
- **No public test server**, which is what makes **Relay → Default** thin in *this* build: the
  address it ships with is `ws://10.0.2.2:8000`, the emulator's route to the development machine,
  so on a real handset Default reaches nothing until there is somewhere to point it. That address
  is a build setting (`relay.properties`), not a constant, so a fork with a relay ships a working
  Default; there just is not one to put here. Deliberately out of scope — the server repo ships a
  `Dockerfile`, three compose files and `deploy/deploy.sh` so a deployment is one command.
- **The service, the overlay and the widget have no automated tests.** The button, the main screen
  and settings do (39 Compose UI tests); the three background surfaces are still verified by hand,
  because each needs a real service, a `WindowManager` window, or a host launcher.
- **Legacy launcher rasters are generated, not designed.** `mipmap-*/ic_launcher.png` (API 24–25
  only) is rendered by a script, since no image tooling is available here; API 26+ uses the
  adaptive vector icon. Regenerate from a real asset pipeline if one appears.
- **No landscape layout for Settings** beyond scrolling — the form is a single column at any size.
- **No jitter buffer** beyond `AudioTrack`'s own, and no packet reordering or loss concealment.
- **iOS's certificate pin does not check the certificate's validity window** (Phase 7a,
  `network/tls/PinnedTrust.ios.kt`/`PttHttpClient.ios.kt`). The JVM path's `PinnedTrustManager`
  rejects an expired-but-correctly-pinned certificate with a distinguishable message
  ("The relay's certificate expired on..."); the iOS `handleChallenge` callback only compares the
  SHA-256 of the presented DER certificate against the stored pin and otherwise defers to
  `NSURLCredential.credentialForTrust`, so an expired certificate that still matches the pin is
  accepted. Closing this needs a DER `Validity` (notBefore/notAfter) parser running against the
  bytes `leafCertificateDer` already extracts — not built yet; out of scope for Phase 7a/7b, whose
  goal was a compiling iOS framework and real audio, not full pinning parity.
- **iOS has no cross-app overlay window, no home-screen widget equivalent, no
  notification-driven transmit toggle** (Phase 7b). Android's three "talk without opening the app"
  surfaces (`overlay/OverlayBubbleView`, `widget/PttWidget`, the foreground-service notification's
  action button — see `docs/architecture.md`) all depend on platform APIs iOS does not expose to
  third-party apps the same way: `WindowManager`-style always-on-top windows do not exist on iOS;
  a home-screen widget equivalent would need a separate WidgetKit extension target (its own
  process, its own tiny UI, no direct method calls into the running app — a materially bigger
  addition than Android's Glance widget) and could not do hold-to-talk regardless, for the same
  RemoteViews-style discrete-tap reason Android's own widget can't (see the "widget cannot do
  hold-to-talk" gotcha below); and iOS notification actions cannot open the microphone from a
  background handler. **Backgrounded operation on iOS is real as of Phase 7b** but works
  differently from Android's foreground service: it depends on `UIBackgroundModes: audio` in
  `Info.plist` plus an active `AVAudioSession` (configured by `IosVoiceRecorder`/`IosVoicePlayer`
  themselves — see `IosPttSessionLauncher`'s KDoc), not a foreground service the OS can be asked to
  keep alive; there is no iOS notification with a transmit-toggle action the way
  `service/PttNotifications` provides on Android.

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
  `TrustAllX509TrustManager`, `BadHostnameVerifier`, `AllowAllHostnameVerifier`) and it is right
  to. `network/tls/PinnedTrust.kt` (now in `:shared`'s jvmCommonMain, shared with the desktop
  target — see `docs/architecture.md`) used to carry `@SuppressLint` for these; that annotation is
  Android-only, so the four checks are disabled in `:shared`'s lint config
  (`shared/build.gradle.kts`) instead, with the justification recorded there. Do not add more
  without the same justification.
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
- **`:app`'s release build currently ships with `isMinifyEnabled = false`, so R8 does not run and
  the two keep rules below are dormant.** They stay in `app/proguard-rules.pro` because a minified
  `:app:assembleRelease` needs them and nothing in this repo's own code would suggest either one.
  Both were found by actually installing and launching a minified release build (see
  `docs/fdroid.md` / the Phase 8 F-Droid reproducibility check, from before minification was
  reverted), not by reading R8's warnings alone — the first is a build failure, the second is a
  launch-time crash:
  - Ktor's client-core carries a debugger-presence check
    (`io.ktor.util.debug.IntellijIdeaDebugDetector`) that references
    `java.lang.management.ManagementFactory`/`RuntimeMXBean` — real JVM classes that do not exist on
    Android and are never reached at runtime there. R8 refuses to proceed on the missing classes
    unless told they're expected: `-dontwarn java.lang.management.ManagementFactory` /
    `RuntimeMXBean` (`app/proguard-rules.pro`).
  - `androidx.work` (a transitive dependency — nothing in this app calls WorkManager directly; it
    arrives via `androidx.datastore`/`androidx.glance`) auto-initializes via
    `androidx.startup.InitializationProvider`, which builds a Room database (`WorkDatabase`)
    reflectively: `androidx.room.util.KClassUtil.findAndInstantiateDatabaseImpl` looks up the
    generated `WorkDatabase_Impl` by name and calls `getDeclaredConstructor().newInstance()`.
    `work-runtime`'s own consumer rule (`-keep class * extends androidx.room.RoomDatabase { void
    <init>(); }`) only protects the no-arg constructor and was not enough — the app crashed at
    launch with `Failed to create an instance of androidx.work.impl.WorkDatabase`
    (`InstantiationException`, the exact exception `KClassUtil` catches when the reflective
    `newInstance()` fails). Fixed by keeping the whole class:
    `-keep class * extends androidx.room.RoomDatabase { *; }` plus
    `-keep class androidx.work.impl.WorkDatabase_Impl { *; }` (`app/proguard-rules.pro`). Verified
    by installing the resulting signed release APK on an emulator and confirming the app launches
    and renders — this is exactly the class of failure a debug build, or an unminified release
    build, never shows.
- **Compose Multiplatform 1.12.0 exposes no public API to point resource lookup at an arbitrary
  locale.** `ComposeEnvironment`/`LocalComposeEnvironment` are `internal`, and
  `LanguageQualifier`/`RegionQualifier`/`ScriptQualifier` are `@InternalResourceApi` — none of them
  reachable from app code without an opt-in that isn't actually offered here. `stringResource()`
  resolves through `androidx.compose.ui.text.intl.Locale.current`, which is a live re-read of the
  platform's own locale primitive (`android.os.LocaleList.getDefault()`,
  `java.util.Locale.getDefault()`, `NSLocale.preferredLanguages` respectively) — but that read is
  **not** Compose-observable state, so setting the platform locale alone changes nothing on screen
  until something else forces a recomposition. The supported pattern, used by the **Settings →
  Language** setting (`data/settings/LanguageMode.kt`): apply the platform locale *during*
  composition — `remember(language) { applyLanguagePreference(language) }`, not a
  `LaunchedEffect`, which only runs *after* the first pass has already resolved every string
  against the old locale — then wrap the affected content in `key(language) { ... }` so Compose
  discards and rebuilds the subtree against it (desktop's `Main.kt`, iOS's `ui/App.kt`). Android
  instead forces a real configuration change via `Context.attachBaseContext()` +
  `Activity.recreate()` (`LocaleApplier.android.kt`), which is the only one of the three that also
  gets a real `res/` configuration (layout direction, plurals) rather than just swapping which
  Compose-resources string table is read. Deleting the `key()`, or moving the apply call into a
  `LaunchedEffect`, silently breaks language switching rather than failing loudly — don't
  rediscover this by "cleaning up" what looks like a redundant `key()`.
- **On Linux, `javax.sound.sampled`'s "default" line can silently reach the wrong device.**
  `DesktopVoiceRecorder`/`DesktopVoicePlayer` (Phase 6) use `AudioSystem.getLine(info)` on purpose
  — not a hardcoded mixer — because that is the only choice that is portable across a user's
  actual machine. But JavaSound's ALSA provider resolves the literal PCM name `"default"`, and on a
  box where PulseAudio/PipeWire owns it, `"default"` follows whatever that server's own default
  *source* is pointed at — which is not necessarily a microphone. `AudioSystem.isLineSupported`
  returns true, `open()`/`start()` succeed, `read()` returns a normal byte count: nothing in the
  API surface distinguishes this from a working capture device, so it cannot be detected or worked
  around from the code. The fix, if it happens, is entirely in the audio server's own default-input
  routing (e.g. `pactl set-default-source` / `wpctl set-default`), not in this class. Verified on
  one such box: the exact same `javax.sound.sampled` calls returned all-zero samples through the
  "default" line while an explicit hardware mixer on the same box captured a genuine non-zero
  signal — see `docs/audio-pipeline.md#desktop-capture--playback`.
