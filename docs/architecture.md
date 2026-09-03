# Architecture — ptt-client-android

Namespace `com.github.devapro.pttdroid`. Self-rolled MVI for the UI, with all session state owned
by one application-scoped controller.

**Compose Multiplatform, four modules, three shipped platforms.** `:shared` is a Kotlin
Multiplatform module (`androidTarget` + `jvm("desktop")` + `iosArm64`/`iosSimulatorArm64`, the last
two behind a build guard — see the iOS paragraph below) holding everything platform-independent:
`domain/`, `mvi/`, `model/`, `data/settings/`, `network/`, the Compose UI (`ui/`), the ten
`reducer/`s, `MainActivityViewModel`, and the platform-independent half of the Koin graph
(`di/SharedDi.kt`). A `PttLog` Kermit facade replaces Timber for all of this code, since Timber is
Android-only. Below `commonMain`, three more source sets carry platform-specific code:

- **`jvmCommonMain`** — the intermediate source set shared by `androidMain` and `desktopMain` (both
  `dependsOn` it), for JVM-only code neither Android nor desktop needs to duplicate:
  `KtorPttConnection`'s OkHttp-backed `createPttHttpClient`/`describePlatformCause`
  (`PttHttpClient.jvm.kt`), `network/tls/PinnedTrust.kt` (`javax.net.ssl`), and
  `internalserver/InternalPttServer` (the on-device relay, a Ktor CIO server). None of these three
  exist on iOS — `javax.net.ssl` and Ktor's CIO engine are JVM-only APIs — so this source set is
  what lets Android and desktop share them without iOS being forced to provide a stub. `androidMain`
  and `desktopMain` themselves hold only what's left after that: each platform's own `DataStore`
  wiring and Koin module.
- **`androidMain`** — `createAndroidSettingsDataStore(Context)`, `SharedDiAndroid.kt`.
- **`desktopMain`** — `DesktopVoiceRecorder`/`DesktopVoicePlayer` (`javax.sound.sampled`),
  `createDesktopSettingsDataStore()`, `SharedDiDesktop.kt`.

`:app` is the Android application launcher: `MainActivity`, `service/`, `overlay/`, `widget/`, the
Android-only half of DI (`di/AppDi.kt` — `VoiceRecorder`, `VoicePlayer`, `OverlayController`,
`ServicePttSessionLauncher`), and everything else genuinely Android-only (audio capture/playback,
the foreground service, the widget, the overlay) — 13 Kotlin files in total; everything else that
used to live here moved to `:shared` over the course of the migration. `:desktopApp` hosts the real
shared UI in a `Window {}` and starts Koin itself; its `PttSessionLauncher` just calls
`PttController.start()`/`stop()` directly (no foreground-service concept to start first). See
[`audio-pipeline.md`](audio-pipeline.md#desktop-capture--playback) for its recorder/player. The
package map below marks which module each package lives in; treat it as the source of truth over
the paragraph above.

**iOS.** `:shared` declares `iosArm64()`/`iosSimulatorArm64()` targets (**not**
`iosX64()` — Compose Multiplatform publishes no `iosX64` artifact variant, confirmed against Maven
Central for every release back to 1.8.2; see the comment in `shared/build.gradle.kts`), each
producing a static `PTTdroidShared` framework, but only when the build can actually compile them
— a real Mac host, or `-PenableIosTargets=true` (passed by `.github/workflows/ios.yml`'s macOS
runner). This machine is Linux, which cannot link or run Kotlin/Native Apple binaries, so the
targets are absent from a plain `./gradlew build` here; see the guard in
`shared/build.gradle.kts`. Linux *can*, however, frontend-compile (klib, not link) Apple targets —
`-PenableIosTargets=true :shared:compileKotlinIosSimulatorArm64`/`compileKotlinIosArm64` both
succeed here as of Phase 7b, and were the primary feedback loop for everything under `iosMain`.
`iosMain` holds iOS actuals for the same three seams every other platform has:
`createPttHttpClient`/`describePlatformCause` (Darwin engine + a hand-rolled DER-SHA-256 pinning
check — see the Transport security section below), the settings `DataStore`
(`<Documents>/settings.preferences_pb`), and `IosPttSessionLauncher` (calls
`PttController.start()`/`stop()` directly, like desktop). `audio/IosAudio.kt`'s
`IosVoiceRecorder`/`IosVoicePlayer` are real `AVAudioEngine`-backed implementations as of Phase 7b
— capture taps the hardware's native format and converts to 16 kHz mono PCM16 via an explicit
`AVAudioConverter`; playback is an `AVAudioSourceNode` pull render node fed by a small ring buffer.
See [`audio-pipeline.md`](audio-pipeline.md#ios-capture--playback) for the full picture. `ui/App.kt`'s
`App()` composable is the iOS entry point's UI root (`iosMain/.../MainViewController.kt` →
`iosApp/iosApp/ContentView.swift`); it is new rather than a refactor of `MainActivity`/
`:desktopApp`'s `Main.kt` into a shared root — see `App()`'s own KDoc for why. Because none of
this can be *linked or run* on this Linux machine — only frontend-compiled — iOS correctness is
still established by `.github/workflows/ios.yml`, a macOS CI job — treat anything under `iosMain`/
`iosApp/` as compile-verified but link/runtime-unverified until that job is green.

## The central idea

`domain/PttController` is the **sole owner** of the WebSocket connection, the microphone and the
speaker, and it exposes one `StateFlow<PttState>`. It is hosted by `service/PttForegroundService`,
so it outlives any Activity.

This is what makes the three "talk without opening the app" surfaces possible — the Activity, the
floating bubble and the home-screen widget are all just observers of the same state and callers of
the same methods:

```
                       ┌──────────────────────────────┐
   MainActivity ──────▶│                              │
   OverlayBubbleView ─▶│  PttController               │──▶ KtorPttConnection ──▶ ws://…
   PttWidget ─────────▶│  StateFlow<PttState>         │──▶ VoiceRecorder (mic)
   Notification ──────▶│                              │──▶ VoicePlayer (speaker)
                       └──────────────────────────────┘
                                    ▲
                       owned by PttForegroundService
```

Previously this lived in `MainActivity.onStart`/`onStop`, which meant backgrounding the app tore
down a transmission in flight.

## Package map

| Package | Contents | Module |
|---|---|---|
| `domain/` | `PttController`, `PttState` + `ConnectionStatus`, `ReconnectPolicy`, `PttSessionLauncher`, `canHostRelay` (`expect val` — see below) — commonMain; `canHostRelay = true` — jvmCommonMain; `IosPttSessionLauncher`, `canHostRelay = false` — iosMain | `:shared` |
| `network/` | `PttConnection` (interface), `PttEndpoint`, `protocol/Messages.kt` — all commonMain; `KtorPttConnection` (commonMain, calls the `createPttHttpClient`/`describePlatformCause` `expect`s); `tls/PinnedTrust.kt`, `PttHttpClient.jvm.kt` — jvmCommonMain (JVM-only: OkHttp, `javax.net.ssl`); `tls/PinnedTrust.ios.kt`, `PttHttpClient.ios.kt` — iosMain (Darwin engine, `CommonCrypto`/`Security` cinterop) | `:shared` |
| `audio/` | `AudioConfig`, `AudioContracts` (contracts), `FrameAccumulator` — commonMain; `VoiceRecorder`, `VoicePlayer` (Android `AudioRecord`/`AudioTrack`) — `:app`; `DesktopVoiceRecorder`, `DesktopVoicePlayer` (`javax.sound.sampled`) — `:shared` desktopMain; `IosVoiceRecorder`, `IosVoicePlayer` (`AVAudioEngine`/`AVAudioConverter`/`AVAudioSourceNode`, Phase 7b) — `:shared` iosMain | `:shared` commonMain / desktopMain / iosMain, and `:app` |
| `service/` | `PttForegroundService`, `PttNotifications`, `PttServiceCommands` | `:app` |
| `overlay/` | `OverlayController`, `OverlayBubbleView` | `:app` |
| `widget/` | `PttWidget`, `PttWidgetAction`, `PttWidgetReceiver`, `PttWidgetUpdater` | `:app` |
| `internalserver/` | `InternalPttServer` — optional on-device relay (Ktor CIO server, JVM-only, unreachable from iOS — see `domain/canHostRelay`) | `:shared` jvmCommonMain |
| `data/settings/` | `AppSettings`, `ServerMode`, `ServerAddress`, `ThemeMode`, `LanguageMode`, `CertificatePin`, `SettingsRepository` (takes a `DataStore<Preferences>` directly) — commonMain; `createAndroidSettingsDataStore(Context)`/`createDesktopSettingsDataStore()`/`createIosSettingsDataStore()` — plain platform functions, not `expect`/`actual`, called only from each platform's own DI module — see [Transport security](#transport-security) below for the trust-manager seam and the "Settings storage" note for the DataStore one | `:shared` |
| `mvi/` | `ActionProcessor`, `Reducer`, `MviViewModel` | `:shared` commonMain |
| `model/` | `MainAction`, `ScreenState`, `MainEvent` | `:shared` commonMain |
| `reducer/` | one reducer per action (10) | `:shared` commonMain |
| `ui/` | `PttUiStatus` (the shared state→presentation mapping), `MainScreen`, `SettingsScreen`, `App()` (iOS's UI root, `App.kt`), `components/`, `theme/`, `viewmodel/MainActivityViewModel` | `:shared` commonMain |
| `di/` | `SharedDi.kt` (platform-independent graph) — `:shared` commonMain; `SharedDiAndroid.kt`/`SharedDiDesktop.kt`/`SharedDiIos.kt` (platform providers), `KoinIos.kt` (`initKoinIos()`, iOS's Koin entry point) — `:shared` androidMain/desktopMain/iosMain; `AppDi.kt` (Android-only: `VoiceRecorder`, `VoicePlayer`, `OverlayController`, `ServicePttSessionLauncher`) — `:app` | split across `:shared` and `:app` |
| `MainViewController.kt` | `MainViewController()` — hosts `App()` via `ComposeUIViewController`, called from `iosApp/iosApp/ContentView.swift` | `:shared` iosMain |

## The MVI loop

```
Compose ──onAction(MainAction)──▶ MainActivityViewModel
                                      │
                                      ▼
                            MainActionProcessor
                       (finds the Reducer for action::class)
                                      │
                                      ▼
                    Reducer.reduce(action, state) ──▶ Result(state, nextAction?, event?)
                                      │
                     ┌────────────────┼────────────────┐
                     ▼                ▼                ▼
              _state update      re-dispatch      _event channel
                     │
                     ▼
              Compose recomposes
```

`ActionProcessor` (`mvi/ActionProcessor.kt`) takes `Set<Reducer<out ACTION, …>>`, validates that
action classes are unique **once at construction**, and indexes reducers by `KClass` so dispatch is
a map lookup.

Reducers no longer touch sockets or audio — they call `PttController` or `PttSessionLauncher`.
`ScreenState` embeds `PttState` rather than duplicating connection state.

| Action | Reducer | Effect |
|---|---|---|
| `InitConnection` | `InitConnectionReducer` | Starts the foreground service (or asks for the mic permission first) |
| `Disconnect` | `DisconnectReducer` | Stops the service |
| `Reconnect` | `ReconnectReducer` | `PttController.restart()` |
| `Speak` | `StartSpeakReducer` | `requestTalk()` — asks the server for the floor |
| `StopSpeak` | `StopSpeakReducer` | `releaseTalk()` |
| `SetChannel(n)` | `SetChannelReducer` | Clamps to 1..99, persists, reconnects |
| `OpenSettings` / `CloseSettings` | corresponding reducers | Switch the visible screen |
| `SaveSettings(s)` | `SaveSettingsReducer` | One atomic DataStore write, closes Settings, chains to `Reconnect`, emits `ShowMessage` |
| `DismissError` | `DismissErrorReducer` | `PttController.clearError()` |

## Talk-floor flow

The server grants a single talk floor per channel. The client **never opens the microphone on its
own** — pressing PTT only sends `talk_request`, and recording starts when the server confirms the
floor is ours:

```
press ──▶ requestTalk() ──▶ talk_request ──▶ server
                                              │ floor free?
                              ┌───────────────┴───────────────┐
                              ▼ yes                            ▼ no
                    floor{isSelf:true}                error{floor_busy}
                              │                                │
                     startTransmit()                  stay silent, clear
                     mic opens, audio flows            the pending request
```

Everyone else on the channel receives `floor{isSelf:false, holderName:…}`, which disables their PTT
control and drives the "someone is talking" indication. See `PttController.handleFloor`.

## Reconnection

`domain/ReconnectPolicy` — exponential backoff with full jitter, 500 ms base, 30 s cap. Observed
sequence on a dead server: 500, 758, 1867, 2491, 4041 ms… The previous implementation slept a flat
1000 ms and retried forever at a constant rate.

## Koin graph (`di/SharedDi.kt`, `SharedDiAndroid.kt`, `SharedDiDesktop.kt`, `SharedDiIos.kt`, `AppDi.kt`)

Split across four modules since Phase 5, all loaded together into one graph at `startKoin { }`
(Koin merges every loaded module regardless of which file registers a binding another module's
definitions depend on):

- **`:shared` commonMain (`SharedDi.kt`)** — everything platform-independent: `CoroutineContextProvider`,
  the named `sessionScope` (`SupervisorJob + Dispatchers.IO`, exposed as the public qualifier
  `SESSION_SCOPE` rather than a private string, since three different modules now need to `get()`
  against the exact same scope instance), `SettingsRepository`, `KtorPttConnection` (bound to
  `PttConnection`), `PttController`, all ten reducers, `MainActionProcessor`, and
  `MainActivityViewModel` (`viewModelOf`).
- **`:shared` androidMain / desktopMain (`SharedDiAndroid.kt` / `SharedDiDesktop.kt`)** — each
  platform's `DataStore<Preferences>` (via `createAndroidSettingsDataStore(androidContext())` /
  `createDesktopSettingsDataStore()`) and `InternalPttServer` (`jvmDi()` in `SharedDiJvm.kt`,
  `jvmCommonMain`, called from both). Desktop's module additionally binds the real
  `VoiceRecorderContract`/`VoicePlayerContract`/`PttSessionLauncher` implementations
  (`DesktopVoiceRecorder`/`DesktopVoicePlayer`/`DesktopPttSessionLauncher`), since there is
  no separate "desktop app" module to split those into the way Android has `:app`.
- **`:app` (`AppDi.kt`)** — Android-only classes that need a real `Context`, `AudioRecord`/
  `AudioTrack`, or `PttForegroundService`: `VoiceRecorder`, `VoicePlayer` (bound to their
  contracts), `ServicePttSessionLauncher`, `OverlayController`.

`PTTdroidApplication.onCreate()` calls `startKoin { modules(sharedModule, sharedAndroidModule,
appModule) }`; `:desktopApp`'s `main()` calls `startKoin { modules(sharedModule,
sharedDesktopModule) }` itself, since there is no `Application` class to do it for it. iOS's
`initKoinIos()` (`di/KoinIos.kt`, iosMain) does the same with `sharedModule` +
`sharedIosModule` (`di/SharedDiIos.kt`), called once from `iosApp/iosApp/iOSApp.swift`'s `init()`.

`SettingsRepository` takes a `DataStore<Preferences>` rather than a `Context`, resolved via plain
`get()` — the platform DI module above is what actually builds it. The Phase 3 stopgap this
replaced (`AndroidSettingsContext`, a process-wide static `Context` holder `PTTdroidApplication`
populated before `startKoin` ran) is gone: the `Context` now comes from Koin's own
`androidContext()`, the same as every other Android-scoped binding.

## Settings storage

`SettingsRepository` lives in `:shared` commonMain and reads/writes through one
`DataStore<Preferences>`, bound by each platform's own DI module (see above) rather than built from
a commonMain `expect fun` — the Android build needs a `Context` and the Koin wiring that supplies it
is itself platform-specific, so there is nothing left for a shared `expect`/`actual` pair to do.
The two platform functions, both in `data/settings/`:

- **`createAndroidSettingsDataStore(context: Context)`** (`SettingsDataStore.android.kt`):
  reproduces the old `Context.preferencesDataStore(name = "ptt_settings")` delegate's file exactly
  — `<context.filesDir>/datastore/ptt_settings.preferences_pb` — via
  `PreferenceDataStoreFactory.create { File(context.filesDir, "datastore/ptt_settings.preferences_pb") }`.
  Getting this wrong would silently reset every existing install's settings on upgrade, so it is
  pinned by an instrumented test, `SettingsDataStoreMigrationTest` (`:shared`'s
  `androidInstrumentedTest` — a JVM unit test has no real `filesDir` to check this against), which
  calls the same `androidx.datastore.dataStoreFile(name)` helper the old delegate itself called
  internally, rather than re-asserting a hardcoded path string.
- **`createDesktopSettingsDataStore()`** (`SettingsDataStore.desktop.kt`):
  `$XDG_CONFIG_HOME/ptt-client/settings.preferences_pb`, falling back to
  `~/.config/ptt-client/...`.

`LanguageMode` is stored the same way, under `AppSettings.languageMode`, but persisting it is the
easy half — nothing here reads `stringResource()`, so DataStore alone does not make a saved
language show up. Applying it is a genuinely per-platform seam, `applyLanguagePreference()`
(`data/settings/LanguageApplier.kt`, `expect`/`actual`), paired with `key(languageMode) { ... }`
around the affected content in `Main.kt` (desktop) and `ui/App.kt` (iOS); Android instead is a
no-op on that `expect` and forces the language through `Context.attachBaseContext()` +
`Activity.recreate()` (`LocaleApplier.android.kt`, called once more from
`PTTdroidApplication.onCreate()` so the notification, widget and overlay bubble get it even
without an Activity). Why this needs forcing into view at all, rather than just calling
`Locale.setDefault`/writing `NSUserDefaults` and being done — Compose Multiplatform exposes no
public API for pointing resource lookup at an arbitrary locale — is written up as a gotcha in
[`known-issues.md`](known-issues.md).

## The relay address

`AppSettings` keeps two pairs and they are not the same thing. `customHost`/`customPort` are what
the user typed; `serverHost`/`serverPort` are computed from `serverMode` and are what the transport
dials. Everything downstream — `webSocketUrl()`, the offline card, the embedded relay's port —
reads the computed pair and never has to know which mode is in force, and the typed pair survives a
spell on Default so that switching back does not lose it.

`ServerAddress.parse` turns one line of text into that pair. It takes a bare host, a `host:port`,
or a whole URL, because that is what people have on the clipboard: `ptt-server` prints
`ws://192.168.1.20:8000` at startup and a tunnel hands out `https://something.ngrok-free.app`. A
scheme that is spelled out keeps its own default port — 443 for `https`, 80 for `http`, and only a
bare host with no scheme gets this app's 8000 — and it decides encryption, so the Security switch
shows what the address implies rather than contradicting it. Flipping that switch first resolves
the address to `host:port`, which is what stops turning encryption off from silently taking 443
with it. Credentials in the address are refused rather than accepted: a URL reaches every proxy log
on the way, which is exactly why the token is a header.

The Default address is not a constant either: `relay.properties` is read at build time into
`BuildConfig.DEFAULT_RELAY_HOST` / `_PORT` / `_TLS`, which is what `AppSettings.DEFAULT_HOST` and
friends return. A fork with its own relay changes that one line and ships an APK that arrives
pointing at it. See [`build-and-run.md`](build-and-run.md#the-default-relay).

`ServerMode.restore` is the one piece with a compatibility duty. An install that predates the
Default/Custom choice has a stored address and no stored mode; reading that as Default would
quietly move it back to the built-in address, so a stored address that differs from the default is
taken as the Custom choice it was made under.

## Transport security

`AppSettings` carries three fields the transport needs, and `endpoint()` folds them into one
`PttEndpoint` — url, pin, token — so they can only change together. A url `String` alone invited
exactly the half-applied change this prevents: switching to `wss://` without the matching pin, or
moving to a different relay while keeping the old token.

Three states, and the middle one is the common misunderstanding:

| Setting | What the client does |
|---|---|
| Encryption off | `ws://`. Cleartext, permitted by `network_security_config.xml` |
| Encryption on, no fingerprint | `wss://` verified the normal way, against the device's certificate authorities. This is the ngrok / real-certificate path |
| Encryption on, fingerprint set | `wss://` trusting **only** that certificate, by SHA-256 of its DER encoding |

The pinned case installs `PinnedTrustManager` and skips hostname verification, because a
self-signed relay's address is not stable enough to name in a certificate and the pin already
identifies the peer more precisely than a name would. It still enforces the certificate's
validity window: a pin says *which* key, the window says *for how long*.

`PinnedTrustManager.getAcceptedIssuers()` returns nothing on purpose. Advertising issuers would
put it on OkHttp's chain-cleaning path, which wants to build a chain up to a known root — there
is no root, and the fingerprint has already settled the question. OkHttp only invokes the cleaner
when a `CertificatePinner` is configured, and none is.

`CertificatePin` normalizes what people actually paste: colons or not, upper or lower case, with
stray spaces or newlines from a terminal copy. Anything that is not a complete 64-hex-character
fingerprint normalizes to empty rather than to a pin that would match nothing.

The `HttpClient` is rebuilt only when `PttEndpoint.trustProfile` changes. The TLS stack is baked
into the OkHttp engine, so a pin change needs a new client — but rebuilding per connection
attempt would throw away the connection pool on every reconnect.

`KtorPttConnection` itself lives in `:shared` commonMain, but building that `HttpClient` is
inherently engine-specific — Ktor's `engine { }` configuration block is typed per engine — so it
sits behind `createPttHttpClient(endpoint)`, an `expect fun` (`network/PttHttpClient.kt`) with two
`actual`s: jvmCommonMain's `PttHttpClient.jvm.kt` (OkHttp + `pinnedTls()` +
`PinnedHostnameVerifier`, described above) and, as of Phase 7a, iosMain's `PttHttpClient.ios.kt`
(Ktor's Darwin engine). The same pattern covers describing a TLS failure: the cause-chain walk
that turns a wrapped exception into a user-readable string is commonMain, but recognizing
`java.security.cert.CertificateException` — what `PinnedTrustManager` actually throws — is
jvmCommonMain's `describePlatformCause`; iOS's own `describePlatformCause` returns `null` (see its
KDoc — the Darwin engine's wrapped-error shape was not verified), falling back to the OS-provided
`NSError` message.

**The iOS pinning path is deliberately not `CertificatePinner`.** Ktor's Darwin engine ships
`io.ktor.client.engine.darwin.certificates.CertificatePinner`, which pins a certificate's *SPKI*
hash — a different value from the SHA-256-of-whole-DER-certificate this app's
`certificateSha256` setting stores. `PttHttpClient.ios.kt` instead installs a
`handleChallenge { ... }` engine callback that extracts the presented leaf certificate
(`network/tls/PinnedTrust.ios.kt`'s `leafCertificateDer`, via `SecTrustGetCertificateAtIndex` —
deprecated since iOS 15 in favour of `SecTrustCopyCertificateChain`, kept anyway because it is the
simpler, single-certificate API and this file can only be frontend-compiled here, not linked or
run, so there is no way to try the more complex replacement against a real device), hashes it
(`sha256`, via `CommonCrypto`'s `CC_SHA256` — one of
Kotlin/Native's built-in Apple platform-library bindings, not a new dependency), and compares it
with the same commonMain `CertificatePin.matches` the JVM path uses — so one fingerprint works
identically on every platform. **Known accepted gap** (see `docs/known-issues.md`): this path
checks only the pin, not the certificate's validity window, unlike `PinnedTrustManager` on the
JVM side.

## Design decisions worth knowing

**The floating bubble is a plain `View`, not Compose.** Hosting a `ComposeView` in a
`WindowManager` window requires hand-rolled `LifecycleOwner`, `ViewModelStoreOwner` and
`SavedStateRegistryOwner` (or a custom `Recomposer`), all of which can leak or crash. This window
must never take the foreground service down with it, and the UI is one circle that changes colour.
See `overlay/OverlayBubbleView.kt`.

**The widget is a toggle, not hold-to-talk.** RemoteViews — and therefore Glance — only deliver
discrete click events; there is no touch-down/touch-up. Genuine press-and-hold exists in the app
and in the floating bubble, both of which receive real touch events.

**`WindowManager` calls must run on the main thread.** The session scope is on `Dispatchers.IO`, so
`OverlayController` keeps its own `Dispatchers.Main.immediate` scope for view work — otherwise
`addView` throws `Can't create handler inside thread … that has not called Looper.prepare()`.

**One enum owns state→presentation, for all four surfaces.** `ui/PttUiStatus` maps `PttState` to
a colour, a status label and the word on the button face. The app screen, the floating bubble (a
raw `Canvas`), the Glance widget and the notification all read it, so a colour cannot come to mean
one thing on the bubble and another on the button. It holds raw ARGB and no Compose or Android
types, which is what lets it be unit-tested. See [`ui-design.md`](ui-design.md).

**Saving settings is an action, not an Activity side effect.** `MainActivity` used to call six
suspending repository setters from a Compose callback, which put persistence outside the MVI loop
and emitted six separate `AppSettings` values — a reconnect racing that could read a new host with
the old port. `SaveSettingsReducer` + `SettingsRepository.save` make it one write and one emission.

**A newly added widget must be seeded.** The service pushes state on change, which a
just-placed widget never sees, so `PttWidget.provideGlance` reads the controller's current state
before rendering.

## Foreground service

`service/PttForegroundService`, declared `android:foregroundServiceType="microphone"` with the
`FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MICROPHONE` permissions (Android 14+ requires both,
plus a matching type, or `startForeground` throws).

Android 14+ also **forbids starting a microphone foreground service from the background**. The
service is therefore long-lived: it is started from a visible Activity, or by an exempt user
gesture (a notification action or a widget tap), and the widget/overlay then merely toggle transmit
on an already-running service rather than cold-starting one. `promoteToForeground()` catches the
failure and stops cleanly if it happens anyway.

## Related

- [`platform-support.md`](platform-support.md) — what each of the three platforms has and does not have
- [`audio-pipeline.md`](audio-pipeline.md) — formats, buffers, the capture/playback lifecycle
- [`features.md`](features.md) — what the app does from the user's side
- [`build-and-run.md`](build-and-run.md) — building, installing, emulator networking
- [`testing.md`](testing.md) — what is covered and how to run it
- [`ui-design.md`](ui-design.md) — what the interface is for and the rules it follows
- [`conventions.md`](conventions.md) — Kotlin style rules for this repo
- [`known-issues.md`](known-issues.md) — fixed defects and remaining gotchas
