# Conventions

## Kotlin

- Explicit visibility on anything public; prefer `internal` for cross-package helpers that are not
  API.
- **No `!!`.** Use `?:`, `requireNotNull` with a message, or restructure.
- Immutable state: `data class` + `copy`. State holders expose `StateFlow`; one-shot effects use a
  `Channel`/`SharedFlow`, never `StateFlow`.
- Prefer `runCatching { … }.onFailure { … }` over bare `try/catch` when the failure is
  non-fatal and only needs logging.
- Never swallow `CancellationException` — rethrow it. Catch it explicitly before a broad
  `catch (e: Exception)` if that block exists.
- Constructor injection only. The platform-independent graph is wired in `:shared`'s
  `di/SharedDi.kt`; each platform adds its own module (`SharedDiAndroid.kt`/`SharedDiDesktop.kt`/
  `SharedDiIos.kt` in `:shared`, `AppDi.kt` in `:app` for Android-only classes that need a real
  `Context`/`AudioRecord`/`AudioTrack`/`PttForegroundService`) — see `docs/architecture.md`'s Koin
  graph section for the full split. No service locators, no `GlobalContext.get()` in application
  code — the one exception is `PttWidget.provideGlance`, where Glance gives no injection point (and
  it is wrapped in `runCatching`).

## Architecture

- **One reducer per action.** A reducer performs an effect and returns
  `Result(state, nextAction?, event?)`; it does not reach into the transport or the audio devices.
  Call `PttController` or `PttSessionLauncher` instead.
- **A reducer's returned state is merged, not assigned — and a suspending reducer must not expect
  to own the whole of `ScreenState`.** `reduce(action, state)` receives its snapshot **by value**,
  taken before it runs, and `MainActivityViewModel.onAction` launches one coroutine per action. Two
  fields are written from outside that pipeline and are not a reducer's to return: `ptt`, mirrored
  in from `PttController`, and `micPermissionGranted`, set by `onMicPermissionResult`. So `onAction`
  re-applies both from the live value (`_state.update { live -> result.state.copy(ptt = live.ptt,
  …) }`) rather than assigning `result.state`. Without that, a reducer which suspends resumes
  holding a pre-suspend copy and silently reverts whichever of the two changed meanwhile — a
  settings save is immediately followed by `Reconnect`, exactly when the controller is emitting, so
  the talk-floor readout went briefly stale. **Serialising action dispatch would not fix this**: the
  controller mirror is its own coroutine, not another action. Today only `SaveSettingsReducer`
  suspends, on the DataStore write; keep the merge whenever a reducer gains a suspending
  collaborator. Pinned by `MainActivityViewModelTest`.
- **No business logic in Composables.** They render state and emit actions.
- Connection/floor/channel state belongs to `PttController`. Do not add a second source of truth —
  the Activity, the overlay and the widget must not be able to disagree.
- Anything that must outlive an Activity belongs to the foreground service, not the Activity.

## UI

- **No business logic in Composables** — they render state and emit actions. That includes
  persistence: settings are saved by a reducer, not from an `onClick`.
- **State→presentation lives in `ui/PttUiStatus`, nowhere else.** Four surfaces render the same
  session; if any of them maps `PttState` to a colour or a label on its own they drift. The enum
  holds raw ARGB and no Compose or Android types so it stays unit-testable.
- **No dynamic colour, and no new palette entries outside `ui/theme/Color.kt`.**
- **Colour is never the only signal.** Every state also changes a word and a glyph.
- **Any gesture that grabs a resource releases it in a `finally`**, and its `pointerInput` key must
  not change mid-gesture. See [`known-issues.md`](known-issues.md) #20.
- Every interactive element needs a `contentDescription`, and anything driven by press-and-hold
  also needs a semantics `onClick` — TalkBack cannot express a hold.
- Sizes that depend on the space left over are computed from `BoxWithConstraints`, not fixed.

Rationale for all of the above: [`ui-design.md`](ui-design.md).

## Configuration

- **Never hardcode a host or port.** They are user settings (`data/settings/AppSettings.kt`). A
  hardcoded LAN IP is what made the previous build unusable on any other network.
- Magic numbers that describe the wire format or the audio format go in `AudioConfig` or the
  protocol types, not inline.

## Logging

- **Never on a per-audio-frame path** (`VoiceRecorder`'s read loop, `VoicePlayer.play`,
  `DesktopVoiceRecorder`/`DesktopVoicePlayer`, `IosAudio.kt`'s tap/render callbacks, the relay
  path). At 25 frames/second per direction this floods the log and costs battery.
- `:app` (Android-only code) uses Timber, gated by `Timber.plant` on `BuildConfig.DEBUG`. `:shared`
  commonMain/jvmCommonMain/iosMain code — which has no `Timber` — uses `PttLog`, a thin Kermit
  facade with the same per-frame rule (see its KDoc).
- Log lifecycle transitions (connect, disconnect, floor grant/release, device open/close) at info;
  recoverable faults at debug/warn with the exception attached.

## Android specifics

- Runtime permissions via the Activity Result API. Never `onRequestPermissionsResult`.
- `WindowManager` calls (`addView`, `updateViewLayout`, `removeView`) must run on the **main
  thread**.
- Guard `NotificationManagerCompat.notify` with a POST_NOTIFICATIONS check and
  `areNotificationsEnabled()` — the permission is revocable from API 33.
- targetSdk 36 enforces edge-to-edge: use `enableEdgeToEdge()` plus inset modifiers. Do not set
  `window.statusBarColor` (deprecated and a no-op).

## Tests

- Every new domain behaviour gets a JVM unit test. If something is untestable because it touches an
  Android class directly, extract an interface (see `audio/AudioContracts.kt`).
- Protocol changes must update `ProtocolSerializationTest` with the literal expected JSON, and the
  matching test in the server repo.

## Docs

Update `docs/` in the same change as the code. `ptt-server/docs/protocol.md` is the canonical wire
contract — change it first, then both implementations.
