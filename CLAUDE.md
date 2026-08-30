# CLAUDE.md — ptt-client-android

Android push-to-talk client. Self-rolled MVI (`mvi/`) + Koin (`di/AppDi.kt`) + Jetpack Compose,
with a Glance widget and a floating overlay. Single module `:app`, namespace
`com.github.devapro.pttdroid`. Talks to the WebSocket relay in the sibling `../ptt-server` repo, or
to the relay this app can host itself (`internalserver/`).

**The one thing to know before changing anything:** `domain/PttController` is the sole owner of the
connection, microphone and speaker, and it is hosted by `service/PttForegroundService` — not by the
Activity. The UI, the floating bubble and the widget are all observers of its single
`StateFlow<PttState>`. Do not introduce a second source of truth for connection or floor state.

**The one thing to know before changing the UI:** `ui/PttUiStatus` is the single mapping from
`PttState` to colour and wording, and the app screen, the floating bubble, the Glance widget and
the notification all read it. Do not let a surface invent its own colours or labels — a colour has
to mean the same thing on all four. What the interface is for, and the rules it follows:
[`docs/ui-design.md`](docs/ui-design.md).

Package map and the MVI loop: [`docs/architecture.md`](docs/architecture.md). Docs index:
[`docs/`](docs).

## Build / test / run

```bash
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # 65 unit tests
./gradlew lintDebug              # must stay clean
ANDROID_SERIAL=<serial> ./gradlew connectedDebugAndroidTest   # 24 Compose UI tests
./gradlew build                  # full build
adb -s <serial> install -r -g app/build/outputs/apk/debug/app-debug.apk
```

Prerequisites: **JDK 21**, `ANDROID_HOME` set, SDK platform **android-36**. Exact dependency
versions live in `gradle/libs.versions.toml` — read it rather than assuming.
Details: [`docs/build-and-run.md`](docs/build-and-run.md).

## Hard rules

- Never `git commit` or `git push` unless the user explicitly asks.
- **Do not remove `android.builtInKotlin=false` or `android.newDsl=false` from
  `gradle.properties`.** AGP 9 rejects the `org.jetbrains.kotlin.android` plugin without both.
- **Do not raise `compileSdk` above 36, and do not bump AndroidX versions blindly.** API 37 is not
  installed here, and `core-ktx` > 1.18.0, `lifecycle` > 2.10.0 and Compose BOM > 2026.06.01 all
  require it. Check `minCompileSdk` in the AAR's `aar-metadata.properties` first — see
  [`docs/build-and-run.md`](docs/build-and-run.md).
- **Never hardcode a server host or port.** They are user settings in `data/settings/AppSettings.kt`.
- **Emulators reach the host machine at `10.0.2.2`, never `localhost`** (this is the default host).
- **No logging on a per-audio-frame path** — `VoiceRecorder`'s read loop and `VoicePlayer.play()`.
  See [`docs/known-issues.md`](docs/known-issues.md) #10.
- Reducers must not touch the socket or the audio devices; they delegate to `PttController` /
  `PttSessionLauncher`. Persistence goes through a reducer too, not from a Compose callback.
- **Never key the PTT gesture's `pointerInput` on anything that changes mid-press**, and keep the
  release in a `finally`. A disabled Compose button drops its gesture detector; losing the release
  strands the talk floor with the microphone open. See [`docs/known-issues.md`](docs/known-issues.md) #20.
- **No dynamic colour.** Colour is the readout here, and it has to match on three surfaces that
  cannot follow a wallpaper-derived scheme.
- `docs/index.html` is the product landing page served by GitHub Pages from `main` / `docs`. Its
  screenshots in `docs/img/` are real device captures — retake them rather than editing them if
  the UI changes.
- Keep `docs/` in sync when changing architecture, the action/reducer set, the audio pipeline, the
  wire protocol or DI wiring. `../ptt-server/docs/protocol.md` is the canonical protocol spec —
  change it first.
- Run the gates (`./gradlew assembleDebug testDebugUnitTest lintDebug`) before declaring work done.

## Conventions

Full Kotlin style rules are in [`docs/conventions.md`](docs/conventions.md) — read it before writing
Kotlin here.

## Before "fixing" something that looks broken

[`docs/known-issues.md`](docs/known-issues.md) lists 31 defects already fixed (with the mechanism
that replaced each), what remains open, and the non-obvious platform gotchas: cleartext being
blocked for `ws://`, microphone foreground services not being startable from the background,
`WindowManager` needing the main thread, the widget being unable to do hold-to-talk, and emulator
microphones capturing silence.
