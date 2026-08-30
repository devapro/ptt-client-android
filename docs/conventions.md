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
- Constructor injection only, wired in `di/AppDi.kt`. No service locators, no `GlobalContext.get()`
  in application code — the one exception is `PttWidget.provideGlance`, where Glance gives no
  injection point (and it is wrapped in `runCatching`).

## Architecture

- **One reducer per action.** A reducer performs an effect and returns
  `Result(state, nextAction?, event?)`; it does not reach into the transport or the audio devices.
  Call `PttController` or `PttSessionLauncher` instead.
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

- Timber only, and **never on a per-audio-frame path** (`VoiceRecorder`'s read loop,
  `VoicePlayer.play`, the relay path). At 25 frames/second per direction this floods logcat and
  costs battery.
- `Timber.plant` is gated on `BuildConfig.DEBUG`.
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
