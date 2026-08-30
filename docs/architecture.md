# Architecture — ptt-client-android

Single Gradle module `:app`, namespace `com.github.devapro.pttdroid`. Self-rolled MVI for the UI,
with all session state owned by one application-scoped controller.

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

| Package | Contents |
|---|---|
| `domain/` | `PttController`, `PttState` + `ConnectionStatus`, `ReconnectPolicy`, `PttSessionLauncher` |
| `network/` | `PttConnection` (interface), `KtorPttConnection`, `PttEndpoint`, `protocol/Messages.kt`, `tls/PinnedTrust.kt` |
| `audio/` | `AudioConfig`, `AudioContracts`, `VoiceRecorder`, `VoicePlayer` |
| `service/` | `PttForegroundService`, `PttNotifications`, `PttServiceCommands` |
| `overlay/` | `OverlayController`, `OverlayBubbleView` |
| `widget/` | `PttWidget`, `PttWidgetAction`, `PttWidgetReceiver`, `PttWidgetUpdater` |
| `internalserver/` | `InternalPttServer` — optional on-device relay |
| `data/settings/` | `AppSettings`, `ThemeMode`, `CertificatePin`, `SettingsRepository` (DataStore) |
| `mvi/` | `ActionProcessor`, `Reducer`, `MviViewModel` |
| `model/` | `MainAction`, `ScreenState`, `MainEvent` |
| `reducer/` | one reducer per action (10) |
| `ui/` | `PttUiStatus` (the shared state→presentation mapping), `MainScreen`, `SettingsScreen`, `components/`, `theme/` |
| `di/` | `AppDi.kt` — the whole Koin graph |

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

## Koin graph (`di/AppDi.kt`)

Application-scoped singletons: `SettingsRepository`, `PttSessionLauncher`, `VoiceRecorder`,
`VoicePlayer`, `KtorPttConnection` (bound to `PttConnection`), `PttController`,
`OverlayController`, `InternalPttServer`, plus a named `sessionScope`
(`SupervisorJob + Dispatchers.IO`) that outlives every Activity. Reducers are factories; the
ViewModel uses `viewModelOf`.

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

- [`audio-pipeline.md`](audio-pipeline.md) — formats, buffers, the capture/playback lifecycle
- [`features.md`](features.md) — what the app does from the user's side
- [`build-and-run.md`](build-and-run.md) — building, installing, emulator networking
- [`testing.md`](testing.md) — what is covered and how to run it
- [`ui-design.md`](ui-design.md) — what the interface is for and the rules it follows
- [`conventions.md`](conventions.md) — Kotlin style rules for this repo
- [`known-issues.md`](known-issues.md) — fixed defects and remaining gotchas
