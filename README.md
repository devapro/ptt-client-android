# PTTdroid

A push-to-talk radio for a channel you host yourself — Android, desktop and iOS, built on one
Compose Multiplatform codebase. Hold the button, talk, everyone else on your channel hears you —
over your own [PTT server](https://github.com/devapro/ptt-server), or over the relay the
Android/desktop builds can host on the machine itself. See
[`docs/platform-support.md`](docs/platform-support.md) for exactly what each platform has.

On Android, the point is that you can talk **without opening the app**: a microphone foreground
service keeps the channel connected, and you can transmit from a floating button over other apps, a
home-screen widget, or the ongoing notification.

The interface is available in English, Russian and Serbian, with an in-app switcher under
**Settings → Language** — useful when a channel gets handed to someone who does not read whatever
language the device owner set up.

## What it looks like

The interface is built for one-handed use while looking at something else. Three questions have to
be answerable at a glance — can I talk, is anyone hearing me, which channel — and everything you
touch sits in the bottom half of the screen where a thumb lands.

| State | The button says | Colour |
|---|---|---|
| Connected, floor free | `HOLD` | green |
| Waiting for the server's answer | `WAIT` | amber |
| You hold the floor | `ON AIR` | red |
| Someone else holds it | `BUSY` | blue |
| Connecting / offline | `LINKING` / `OFFLINE` | amber / slate |

Colour follows radio convention rather than traffic lights: red is *on air*, and an incoming
transmission is blue, because green here means "the channel is yours" — the opposite of somebody
else holding the floor. Nothing depends on colour alone; every state also changes the word on the
button and the glyph above it. The reasoning is written down in [`docs/ui-design.md`](docs/ui-design.md).

## Install

From this project's own F-Droid repository — add it in the F-Droid app under
**Settings → Repositories → +**:

```
https://devapro.github.io/ptt-client-android/fdroid/repo
```

Every release is signed and published there by CI, alongside a signed APK on the
[GitHub release](https://github.com/devapro/ptt-client-android/releases). How that works, and how
to submit to the official F-Droid catalogue: [`docs/fdroid.md`](docs/fdroid.md).

## Quick start

```bash
./gradlew assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

The other two targets run from the same checkout, with the same interface:

```bash
./gradlew :desktopApp:run          # desktop — Linux, macOS, Windows
open iosApp/iosApp.xcodeproj       # iOS — needs a Mac; Gradle builds :shared as a framework
```

Then open **Settings → Relay**. **Default** dials the address the build ships with — for this
repository that is `ws://10.0.2.2:8000`, the emulator's route to your development machine, since
`localhost` inside an emulator is the emulator itself. **Custom** takes one address: a host, a
`host:port`, or a whole URL pasted from the server log or a tunnel. Either way the screen shows
the exact URL it will dial, which is usually enough to spot a typo.

Don't have a relay yet? Two ways out:

- **Run one** — start to finish, from a spare machine to two phones talking:
  [`ptt-server/docs/running-your-own.md`](https://github.com/devapro/ptt-server/blob/main/docs/running-your-own.md).
- **Host one on a phone** — turn on **Host a relay on this device**, set that phone's **Relay** to
  Custom `127.0.0.1`, and point the other phones at its LAN address.

Building for a group that already has a relay? Set it once in `relay.properties` and the APK
arrives already pointing at it — [`docs/build-and-run.md`](docs/build-and-run.md#the-default-relay).

### Reaching a relay that is not on your Wi-Fi

**Settings → Security** has three settings, matching whatever the relay was started with:

| | |
|---|---|
| **Encrypted connection** | `wss://` instead of `ws://` |
| **Certificate fingerprint** | For a relay serving its own self-signed certificate — paste the SHA-256 `ptt-server` prints on startup. Colons and case do not matter. Leave empty when the relay has a publicly trusted certificate, such as through a tunnel |
| **Access token** | The relay's shared secret, sent as a header |

A pinned fingerprint is a stricter guarantee than a certificate authority gives: it admits one
key and nothing else. What it costs is rotation — replacing the relay's keypair means re-pairing
every handset.

Toolchain and the two build constraints that are easy to break:
[`docs/build-and-run.md`](docs/build-and-run.md).

## Architecture

- **MVI** — `mvi/` (`ActionProcessor` + one `Reducer` per action). This repo is intentionally an
  MVI example.
- **Ktor** — `ktor-client-websockets` over OkHttp for the socket; `ktor-server-cio` for the
  optional on-device relay.
- **Koin** — DI, split across `:shared`'s `di/SharedDi.kt` (platform-independent) plus a small
  platform module per target, and `:app`'s `di/AppDi.kt` (Android-only classes).
- **Compose Multiplatform** + Material 3 for the interface on all three platforms, **Glance** for
  the Android widget, **DataStore** for settings.

### One codebase, three platforms

| Module | What it is |
|---|---|
| `:shared` | A Kotlin Multiplatform module — `androidTarget` + `jvm("desktop")` + `iosArm64`/`iosSimulatorArm64` (the last two behind a build guard). Everything platform-independent: `domain/`, `mvi/`, `model/`, `data/settings/`, `network/`, the ten reducers and the whole Compose UI |
| `:app` | The Android launcher — `MainActivity`, the foreground service, the Glance widget, the overlay bubble, and the Android-only half of DI. Thirteen Kotlin files |
| `:desktopApp` | Hosts the shared UI in a `Window {}` and starts Koin itself |
| `iosApp/` | An Xcode project; a `ComposeUIViewController` hosts `:shared`'s `App()` |

Below `commonMain` there is one intermediate source set worth knowing about: **`jvmCommonMain`**,
which `androidMain` and `desktopMain` both `dependsOn`. The OkHttp-backed HTTP client, the pinned
trust manager (`javax.net.ssl`) and the on-device relay (a Ktor CIO server) are all JVM-only APIs,
so `jvmCommonMain` is what lets Android and desktop share them without iOS having to stub any of
it — new JVM-only code belongs there rather than duplicated into both. iOS supplies its own three
seams: Ktor's Darwin engine, a hand-rolled DER certificate-pin check, and `AVAudioEngine` for
capture and playback.

The same 136 unit tests are compiled and run twice, once per JVM target. iOS Kotlin can be
frontend-compiled on Linux — `-PenableIosTargets=true :shared:compileKotlinIosSimulatorArm64`, part
of the normal gate — but only a Mac can link or run it, so `.github/workflows/ios.yml` is where iOS
correctness is actually established. Per-platform detail:
[`docs/platform-support.md`](docs/platform-support.md).

Two things to know before changing anything:

**`domain/PttController` owns the session** — the connection, the microphone and the speaker — and
it is hosted by `service/PttForegroundService`, not by the Activity. The UI, the floating bubble
and the widget are all observers of one `StateFlow<PttState>`. Backgrounding the app does not tear
down a transmission in flight.

**`ui/PttUiStatus` owns state→presentation.** One enum maps `PttState` to a colour, a status label
and the word on the button, and all four surfaces read it. A colour cannot come to mean one thing
on the floating bubble and another on the big button.

## Talking without opening the app (Android)

These three surfaces are the one part of the app that is not shared. Neither desktop nor iOS gives
a third-party app an always-on-top window or a home screen to put a widget on, and iOS cannot open
the microphone from a background notification handler — so the desktop build talks for as long as
its window is open, and the iOS build for as long as its audio session survives being backgrounded.

| Surface | Gesture | Notes |
|---|---|---|
| **Floating bubble** | Hold to talk, drag to move | Real press-and-hold. Shows the channel number and a microphone, struck through when a press would do nothing. Hidden while the app itself is on screen |
| **Notification** | Tap **Talk** / **Stop** | Present whenever the session is running |
| **Home-screen widget** | Tap to toggle, −/+ for channel | Toggle only — RemoteViews deliver clicks, never touch-down/up, so hold-to-talk is not expressible in a widget |

## Tests

```bash
./gradlew :shared:testDebugUnitTest :shared:desktopTest       # 136 JVM tests, on both targets
ANDROID_SERIAL=<serial> ./gradlew :shared:connectedDebugAndroidTest   # 41 Compose UI tests (of 45 total; 3 opt-in TLS tests need a live relay)
./gradlew lintDebug                                          # 12 pre-existing findings, no more
./gradlew -PenableIosTargets=true \
  :shared:compileKotlinIosSimulatorArm64 :shared:compileKotlinIosArm64   # iOS, on Linux too
```

The unit tests are mostly about the talk floor — above all that **pressing PTT must not open the
microphone**, only a server grant may. The UI tests are about the gesture: the request leaves on
touch-down, and the release fires even when state changes under a held finger, because losing that
release strands the floor with the microphone open. The pinned-TLS trust manager is tested
against real generated certificates, and there is an opt-in instrumented test that connects to an
actual `wss://` relay — Android's TLS stack is Conscrypt, not the JDK's, so that path cannot be
settled on the JVM alone. Coverage map: [`docs/testing.md`](docs/testing.md).

## Project site

[`docs/index.html`](docs/index.html) is a landing page for the whole product, with screenshots in
[`docs/img/`](docs/img). It is published by `.github/workflows/pages.yml` on every push to `main`
that touches `docs/`, which assembles the page *and* the F-Droid repository into one deployment —
so **Settings → Pages → Source** has to be **GitHub Actions**, not "Deploy from a branch". A
branch deployment would publish the landing page alone and delete the F-Droid repository out from
under everyone who had added it. A `.nojekyll` file is present, so the folder is served as-is and
the Markdown docs below stay readable on github.com rather than being rendered into the site.

Its platform matrix duplicates [`docs/platform-support.md`](docs/platform-support.md) for a reader
who will not open a Markdown file; change them together.

To look at it locally:

```bash
python3 -m http.server -d docs 8080   # then open http://localhost:8080
```

## Docs

| | |
|---|---|
| [`docs/ui-design.md`](docs/ui-design.md) | What the interface is for, and the rules it follows |
| [`docs/architecture.md`](docs/architecture.md) | Package map, the MVI loop, the service/overlay/widget |
| [`docs/platform-support.md`](docs/platform-support.md) | What Android, desktop and iOS each have and do not have |
| [`docs/features.md`](docs/features.md) | What the app does, from the user's side |
| [`docs/audio-pipeline.md`](docs/audio-pipeline.md) | Capture → wire → playback |
| [`docs/build-and-run.md`](docs/build-and-run.md) | Toolchain and build constraints |
| [`docs/testing.md`](docs/testing.md) | Test coverage and the manual device checklist |
| [`docs/conventions.md`](docs/conventions.md) | Kotlin and UI style rules |
| [`docs/known-issues.md`](docs/known-issues.md) | 33 fixed defects, open gaps, platform gotchas |
| [`docs/fdroid.md`](docs/fdroid.md) | Releasing: signing keys, the F-Droid repository, the official catalogue |

The wire protocol is specified in the server repo, at `ptt-server/docs/protocol.md`.

## Not included

- No accounts — the access token is one shared secret for the whole channel, with no per-handset
  revocation.
- The on-device relay serves plaintext only. Encryption means pointing at `ptt-server`.
- No audio compression; raw 16 kHz mono PCM, roughly 32 kB/s while transmitting.
- No message history, no text chat, no per-user mute.

## Licence

[GNU General Public License v3.0](LICENSE) — GPL-3.0-only. If you distribute a modified build,
publish the source for it.
