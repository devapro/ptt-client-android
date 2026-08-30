# PTTdroid

An Android push-to-talk radio for a channel you host yourself. Hold the button, talk, everyone
else on your channel hears you — over your own [PTT server](https://github.com/devapro/ptt-server),
or over the relay this app can host on the phone itself.

The point is that you can talk **without opening the app**: a microphone foreground service keeps
the channel connected, and you can transmit from a floating button over other apps, a home-screen
widget, or the ongoing notification.

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

## Quick start

```bash
./gradlew assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

Then open **Settings** and point **Relay** at your server. On an emulator, a server running on your
development machine is at **`10.0.2.2`** — inside an emulator, `localhost` is the emulator itself.
The screen shows the exact `ws://` URL it will dial, which is usually enough to spot a typo.

No server? Turn on **Host a relay on this device**, point that phone's host at `127.0.0.1`, and
point the other phones at its LAN address.

Toolchain and the two build constraints that are easy to break:
[`docs/build-and-run.md`](docs/build-and-run.md).

## Architecture

- **MVI** — `mvi/` (`ActionProcessor` + one `Reducer` per action). This repo is intentionally an
  MVI example.
- **Ktor** — `ktor-client-websockets` over OkHttp for the socket; `ktor-server-cio` for the
  optional on-device relay.
- **Koin** — DI, `di/AppDi.kt`.
- **Jetpack Compose** + Material 3, **Glance** for the widget, **DataStore** for settings.

Two things to know before changing anything:

**`domain/PttController` owns the session** — the connection, the microphone and the speaker — and
it is hosted by `service/PttForegroundService`, not by the Activity. The UI, the floating bubble
and the widget are all observers of one `StateFlow<PttState>`. Backgrounding the app does not tear
down a transmission in flight.

**`ui/PttUiStatus` owns state→presentation.** One enum maps `PttState` to a colour, a status label
and the word on the button, and all four surfaces read it. A colour cannot come to mean one thing
on the floating bubble and another on the big button.

## Talking without opening the app

| Surface | Gesture | Notes |
|---|---|---|
| **Floating bubble** | Hold to talk, drag to move | Real press-and-hold. Shows the channel number and a microphone, struck through when a press would do nothing. Hidden while the app itself is on screen |
| **Notification** | Tap **Talk** / **Stop** | Present whenever the session is running |
| **Home-screen widget** | Tap to toggle, −/+ for channel | Toggle only — RemoteViews deliver clicks, never touch-down/up, so hold-to-talk is not expressible in a widget |

## Tests

```bash
./gradlew testDebugUnitTest                                  # 65 JVM tests
ANDROID_SERIAL=<serial> ./gradlew connectedDebugAndroidTest   # 24 Compose UI tests
./gradlew lintDebug                                          # must stay clean
```

The unit tests are mostly about the talk floor — above all that **pressing PTT must not open the
microphone**, only a server grant may. The UI tests are about the gesture: the request leaves on
touch-down, and the release fires even when state changes under a held finger, because losing that
release strands the floor with the microphone open. Coverage map:
[`docs/testing.md`](docs/testing.md).

## Project site

[`docs/index.html`](docs/index.html) is a landing page for the whole product, with screenshots in
[`docs/img/`](docs/img). To publish it: **Settings → Pages → Deploy from a branch → `main` /
`docs`**. A `.nojekyll` file is present, so GitHub serves the folder as-is and the Markdown docs
below stay readable on github.com rather than being rendered into the site.

To look at it locally:

```bash
python3 -m http.server -d docs 8080   # then open http://localhost:8080
```

## Docs

| | |
|---|---|
| [`docs/ui-design.md`](docs/ui-design.md) | What the interface is for, and the rules it follows |
| [`docs/architecture.md`](docs/architecture.md) | Package map, the MVI loop, the service/overlay/widget |
| [`docs/features.md`](docs/features.md) | What the app does, from the user's side |
| [`docs/audio-pipeline.md`](docs/audio-pipeline.md) | Capture → wire → playback |
| [`docs/build-and-run.md`](docs/build-and-run.md) | Toolchain and build constraints |
| [`docs/testing.md`](docs/testing.md) | Test coverage and the manual device checklist |
| [`docs/conventions.md`](docs/conventions.md) | Kotlin and UI style rules |
| [`docs/known-issues.md`](docs/known-issues.md) | 31 fixed defects, open gaps, platform gotchas |

The wire protocol is specified in the server repo, at `ptt-server/docs/protocol.md`.

## Not included

- No authentication and no TLS — the transport is plain `ws://`. Suitable for a LAN, not for the
  open internet.
- No audio compression; raw 16 kHz mono PCM, roughly 32 kB/s while transmitting.
- No message history, no text chat, no per-user mute.
