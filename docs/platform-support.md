# Platform support

Three platforms ship from this repository, from three different degrees of a single `:shared`
Compose Multiplatform module. This is the honest state of each, not the aspiration.

| | Android (`:app`) | Desktop (`:desktopApp`) | iOS (`iosApp/`) |
|---|---|---|---|
| Talk floor, channels, reconnect, settings | Yes | Yes | Yes |
| Audio capture/playback | Yes — `AudioRecord`/`AudioTrack` | Yes — `javax.sound.sampled` (Phase 6) | Yes — `AVAudioEngine` (Phase 7b) |
| Pinned TLS (`wss://`) | Yes — OkHttp + `PinnedTrustManager` | Yes — same jvmCommonMain code as Android | Yes — Darwin engine + a hand-rolled DER pin check; does **not** check the certificate's validity window (see `known-issues.md`) |
| On-device relay (**Host a relay on this device**) | Yes — `internalserver/InternalPttServer`, jvmCommonMain | Yes — same code | No — `domain/canHostRelay` is `false` on iOS; the relay is JVM-only (Ktor CIO server) |
| Persistent settings | DataStore, `<filesDir>/datastore/` | DataStore, `$XDG_CONFIG_HOME/ptt-client/` | DataStore, `<Documents>/settings.preferences_pb` |
| Runs without the app open | Yes — microphone foreground service (`PttForegroundService`) keeps the session alive indefinitely | No — the process simply runs until the window is closed; no background concept to speak of on desktop | Partial — `UIBackgroundModes: audio` plus an active `AVAudioSession` keeps *audio* alive backgrounded, but there is no persistent always-on session the way a foreground service is |
| Cross-app overlay / floating button | Yes — `overlay/OverlayBubbleView`, a `WindowManager` window | No — no cross-app window concept on desktop | No — iOS gives third-party apps no always-on-top window API |
| Home-screen widget | Yes — `widget/PttWidget` (Glance), toggle-only (RemoteViews cannot express hold) | No | No — an equivalent would need a separate WidgetKit extension target (its own process, no direct calls into the running app) and could not do hold-to-talk either, for the same discrete-tap reason |
| Notification with a transmit action | Yes — `service/PttNotifications` | No — no notification concept wired up | No — iOS cannot open the microphone from a background notification handler |
| Unit tests | `:shared:testDebugUnitTest` (136) | `:shared:desktopTest` (136, same source) | Frontend-compiled only — Kotlin/Native tests need a Mac to execute |
| UI tests | `:shared:connectedDebugAndroidTest` (43: 40 pass, 3 skip without a live relay) | None | None |
| CI coverage | `ci.yml`, `ubuntu-latest` | `ci.yml`, `ubuntu-latest` | `ios.yml`, `macos-latest` — the only place that actually links/runs iOS code; `ci.yml` only frontend-compiles it (see below) |
| Packaging | Signed APK + F-Droid repo (`release.yml`) | `:desktopApp:packageDeb`/`packageMsi`/`packageDmg` (unsigned, not part of the release pipeline) | Xcode archive, not automated here |

## Why iOS is verified less than the other two

Compose Multiplatform's iOS targets (`iosArm64`, `iosSimulatorArm64`) can only be **linked or run**
on a real Apple toolchain. This repository's regular development machine (and `ci.yml`) is Linux,
which can frontend-compile iOS Kotlin (catching type errors, bad `expect`/`actual` pairs, and most
cinterop mistakes) but cannot produce or execute a real binary. `.github/workflows/ios.yml`
(`macos-latest`) is the only place iOS code is actually linked and its Xcode project actually
built — treat anything under `shared/src/iosMain`/`iosApp/` as compile-verified but link/runtime-
verified only when that job is green.

## Where each gap is explained in depth

- The overlay, widget and notification gaps, and why none of the three ports around them: `known-issues.md`, "iOS has no cross-app overlay window…".
- The TLS pin's validity-window gap: `known-issues.md`, "iOS's certificate pin does not check the certificate's validity window".
- Backgrounded operation on iOS vs. Android's foreground service: `architecture.md`'s iOS paragraph and `IosPttSessionLauncher`'s KDoc.
- Desktop and iOS audio implementations, including what's real hardware-verified vs. compile-verified only: `audio-pipeline.md`.

## Related

- [`architecture.md`](architecture.md) — the module layout each of these lives in
- [`known-issues.md`](known-issues.md) — the same gaps, with the full reasoning
- [`testing.md`](testing.md) — how each number in the table above is produced
