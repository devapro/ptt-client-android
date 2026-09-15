# Audio pipeline

## Format

Fixed by protocol v1 — there is **no negotiation**, so both peers must use exactly these values.
All of them live in one place, `audio/AudioConfig.kt`:

| Property | Value |
|---|---|
| Sample rate | 16 000 Hz |
| Channels | 1 (mono) |
| Encoding | PCM signed 16-bit **little-endian** (`pcm16le`) |
| Frame size | 1 280 bytes = 640 samples = **40 ms** |
| Max frame accepted by the server | 8 192 bytes, and must be an even length |
| Bitrate on the wire | 32 kB/s ≈ 256 kbit/s (uncompressed) |

Previously `VoiceRecorder` and `VoicePlayer` each probed independently for a working rate from
`{8000, 11025, 16000, 22050, 44100}`, with nothing guaranteeing the two agreed and no way to tell a
peer which had been chosen. `VoicePlayer` also probed using `AudioRecord.getMinBufferSize`, which is
the wrong class entirely.

## Capture → wire

```
AudioRecord (VOICE_COMMUNICATION source, 16 kHz mono PCM16)
    │  read(buffer, 0, 1280)
    ▼
buffer.copyOf(read)                    ← exactly the bytes captured
    │
    ▼
Channel<ByteArray>(64, DROP_OLDEST)    ← VoiceRecorder.frames
    │
    ▼
PttController.audioPumpJob  ──▶ connection.sendAudio(chunk)  ──▶ Frame.Binary
```

On an accepted local floor grant, `PttController` first sends the enabled broadcast-start bip:
two 40 ms, 1 kHz PCM16LE frames generated once in common `AudioConfig` code. Only after both
binary frames complete does it start the recorder and forward `VoiceRecorder.frames`, so receivers
hear the bip through the unchanged wire-to-playback path before microphone audio. Disabled bip
preferences skip those generated frames and start capture directly. Repeated or late floor grants
are ignored unless a local floor request is still pending, preventing a release or cancellation
from reopening capture or injecting another bip.

`DROP_OLDEST` is deliberate: capture is realtime, so if the network cannot keep up, discarding the
oldest frame beats accumulating a backlog of stale audio.

The recorder only runs while we hold the talk floor — `startTransmit()` is called from
`handleFloor` when the server confirms `isSelf`, never on the button press itself.

## Wire → playback

```
Frame.Binary ──▶ ConnectionEvent.Audio ──▶ PttController.observeEvents
                                                │
                                                ▼
                                    VoicePlayer.play(pcm)
                                    AudioTrack.write(WRITE_NON_BLOCKING)
```

`AudioTrack` is built with `AudioTrack.Builder` and `CONTENT_TYPE_SPEECH`. It is prepared on
`welcome` and released on disconnect.

There is no jitter buffer beyond `AudioTrack`'s own; on a LAN this is adequate.

## Output routing and volume

The track used to be built unconditionally with `USAGE_VOICE_COMMUNICATION` — the honest
description of the stream, and the wrong default. That usage puts playback on
`STREAM_VOICE_CALL`, which on a good number of devices routes to the **handset receiver** rather
than the loudspeaker, at the call volume rather than the media volume. The app was loud on some
phones and a whisper held to the ear on others, with no control anywhere to change it. A
walkie-talkie is a loudspeaker.

So the route is a user choice — `data/settings/AudioOutput`, defaulting to `SPEAKER` — and each
side of it is a different stream, not just a different device:

| `AudioOutput` | Android | iOS | Desktop |
|---|---|---|---|
| `SPEAKER` (default) | `USAGE_MEDIA` + `CONTENT_TYPE_SPEECH` (`STREAM_MUSIC`), `MODE_NORMAL`, no communication device | `AVAudioSessionCategoryOptionDefaultToSpeaker` + `overrideOutputAudioPort(.speaker)` | n/a — one output device, chosen in the OS |
| `EARPIECE` | `USAGE_VOICE_COMMUNICATION` (`STREAM_VOICE_CALL`), `MODE_IN_COMMUNICATION`, `setCommunicationDevice(TYPE_BUILTIN_EARPIECE)` (API 31+) or `isSpeakerphoneOn = false` below it | no category option + `overrideOutputAudioPort(.none)` | n/a |

`USAGE_MEDIA` on the speaker side is deliberate and not merely "louder": it is what makes the
volume rocker adjust *this* app while it plays, keeps the system out of call mode (which ducks
other apps and relabels the volume UI), and reaches a Bluetooth headset over A2DP rather than
8 kHz HFP. Echo cancellation is what `USAGE_VOICE_COMMUNICATION` buys and it is not needed here:
PTT is half-duplex, so the microphone is never open while this is playing.
`MODE_IN_COMMUNICATION` on the earpiece side *is* a process-wide side effect, so `VoicePlayer`
tracks whether it was the one that set it and restores `MODE_NORMAL` on `release()`.

**iOS has the same problem by default, not by device:** an
`AVAudioSessionCategoryPlayAndRecord` session's documented default output is the receiver. Both
levers are used — the category option as the standing preference (survives a route change), and
`overrideOutputAudioPort` as the imperative one (takes effect mid-session, and is what actually
works under `AVAudioSessionModeVoiceChat`, which the option alone is documented not to cover).
`IosAudioSession` is the single owner of that configuration, because the recorder and the player
both set the category and the recorder starts *later* (on the floor grant, not on `welcome`) —
two copies of the arguments meant the recorder silently put the route back.

**Volume** is a linear 0..1 factor, `AppSettings.playbackVolume`, applied where each platform is
cheapest:

| Platform | Where |
|---|---|
| Android | `AudioTrack.setVolume` — a real mixer gain, nothing per frame |
| iOS | folded into the Int16 → Float32 conversion `IosVoicePlayer.play` already performs (`sample * (volume / 32768f)`), off the realtime render thread |
| Desktop | `audio/PcmGain.kt`, on the drain coroutine. `javax.sound.sampled` only offers `MASTER_GAIN`/`VOLUME` when the mixer implements them, decibel-scaled with a device-dependent floor, and has no fallback when a line reports neither. Returns the input array untouched at full gain, so the default costs nothing |

Both settings are owned by the main screen, not the settings form — they are changed
mid-conversation — so `SettingsRepository.save()` deliberately does not write them, exactly as it
does not write the floating button's position. `PttController` re-applies both to the player at
the start of every session, before `welcome` can prepare the track: the player remembers them
across its own `prepare()`/`release()` but is constructed with the defaults and never sees
DataStore.

## Lifecycle

Both classes are owned by `PttController`, and both `release()` methods are idempotent:

| | `VoiceRecorder` | `VoicePlayer` |
|---|---|---|
| Create | lazily in `ensureRecord()`, reused | `prepare()`, no-op if already built |
| Start | `start()` — `startRecording()` + one read coroutine | implicit, `AudioTrack.play()` in `prepare()` |
| Stop | `stop()` — cancels the read job, `AudioRecord.stop()` | — |
| Release | `release()` — stops, releases, **nulls the field** | `release()` — stops, releases, **nulls the field**, restores `MODE_NORMAL` if it took it |

`setOutput` on a live `VoicePlayer` rebuilds the track — `AudioAttributes` are immutable once
built — which costs a frame or two of silence at the moment the user taps, and that is the moment
they are least likely to hear one. `setVolume` does not: it is a live call on the existing track.

Three defects this replaced:

1. **Short reads transmitted stale data.** The old loop sent the whole 8 192-byte buffer regardless
   of what `read()` returned, so a partial read shipped whatever was left in the tail.
2. **A released `AudioTrack` stayed reachable.** `stopPlay()` released the track but left the field
   non-null, so the next incoming frame wrote to a released object.
3. **`destroy()` was never called** from anywhere, and `create()` allocated a new `AudioRecord`
   without releasing the previous one — so every Activity restart leaked one.

The read loop also used to launch into a brand-new, unmanaged `CoroutineScope` on **every PTT
press**. It now uses one scope owned by the recorder and cancels properly.

## Logging

There is **no logging on the per-frame path**. The previous code logged once per captured buffer and
once per played buffer (`Timber.i("read $readCount")`, `Timber.i("play …")`, `Timber.i("write …")`)
— roughly 25 lines per second per direction — and `Timber.plant` ran unconditionally, so this
happened in release builds too. Timber is now planted only when `BuildConfig.DEBUG`.

Coarse lifecycle events (device opened, format, failures) are still logged.

## Desktop capture / playback

`:desktopApp` has no Android classes to lean on, so `DesktopVoiceRecorder`/`DesktopVoicePlayer`
(`:shared` desktopMain, `audio/DesktopAudio.kt`, Phase 6) implement the same two contracts with
`javax.sound.sampled`, built to the same format (`AudioFormat(16000f, 16, 1, signed=true,
bigEndian=false)`) and the same buffer depth (`AudioConfig.FRAME_BYTES * 4`).

Two differences from the Android classes, both forced by the `javax.sound.sampled` API rather than
a change in intent:

- **`TargetDataLine.read` can short-read**, where `AudioRecord.read` on Android is just trimmed to
  the bytes actually captured. A short, odd-length frame on the wire would violate the protocol's
  fixed `AudioConfig.FRAME_BYTES` contract, so the read loop assembles frames through
  `audio/FrameAccumulator.kt` instead of emitting one `read()` call's result directly — it carries
  any partial frame to the next read and only emits once a full frame's worth of bytes has
  accumulated. `FrameAccumulatorTest` (`commonTest`) covers exact frames, short reads spread over
  several calls, an oversized chunk spanning more than one frame, and a remainder carried across
  calls — all hardware-independent.
- **`SourceDataLine.write` has no non-blocking mode.** Android's `AudioTrack.write` is called with
  `WRITE_NON_BLOCKING` so the network receive path never stalls; `DesktopVoicePlayer.play` instead
  hands each frame to a small `Channel(4, DROP_OLDEST)` drained by its own IO coroutine, so a slow
  or blocked line drops late audio rather than stalling the caller.

No capture/playback device, or one that refuses to open (`AudioSystem.isLineSupported` false, or a
`LineUnavailableException`/`IllegalArgumentException` from `open()`), is not fatal: the failure is
logged once at acquisition (never per frame) and the affected side just stays inert — no capture
means `frames` never emits, no playback means `play()` is a no-op — so the app still runs as a
listen-only (or transmit-only) client instead of crashing.

## iOS capture / playback

`:shared` iosMain (`audio/IosAudio.kt`, Phase 7b) implements the same two contracts with
`AVAudioEngine`, converting to and from [the fixed wire format](#format) explicitly rather than
assuming the hardware or the render callback will hand it over in that shape directly.

**Capture — tap, convert, re-chunk:**

```
IosAudioSession (PlayAndRecord / VoiceChat, active, route per AudioOutput)
    │
    ▼
engine.inputNode.setVoiceProcessingEnabled(true)      ← AEC, iOS 13+; failure is not fatal
    │
    ▼
installTapOnBus(0, …, hwFormat) { buffer, _ -> … }    ← REALTIME AUDIO THREAD
    │  AVAudioConverter(hwFormat → 16 kHz mono Int16), one reused output AVAudioPCMBuffer
    │  one memcpy of int16ChannelData into a ByteArray
    ▼
rawChunks: Channel<ByteArray>(64, DROP_OLDEST)  ── trySend, nothing else on this thread
    │
    ▼
Dispatchers.Default coroutine ──▶ FrameAccumulator ──▶ frames: Channel<ByteArray>(64, DROP_OLDEST)
```

The tap runs on a realtime audio thread with the same "no logging, no heavy allocation, no
blocking" constraint as Android's `VoiceRecorder` read loop and desktop's line-read loop (see
Logging, below) — the `AVAudioConverter`/`AVAudioPCMBuffer` it uses are constructed once in
`start()` and reused every callback, not allocated per frame. Re-chunking arbitrary hardware-buffer
sizes into exact `AudioConfig.FRAME_BYTES` pieces is the same [`FrameAccumulator`](#lifecycle) desktop
uses for the same reason (a capture API that doesn't guarantee frame-sized reads), just fed from a
channel instead of a blocking line read.

Tapping in the hardware's own format (queried via `inputFormatForBus`, never assumed) and
converting explicitly mirrors the same lesson desktop's `AudioSystem.getLine` code learned: don't
assume a capture API will hand over 16 kHz mono PCM16 directly. The conversion always goes through
`AVAudioConverter`'s block-based `convertToBuffer(outputBuffer:error:withInputFromBlock:)`, not the
plain `convertToBuffer(outputBuffer:fromBuffer:error:)` overload — Apple documents the latter as
unsupported for sample-rate conversion, and a phone's hardware input rate is essentially never
natively 16 kHz.

**Playback — a pull render node, not a push scheduler:**

```
Frame.Binary ──▶ ConnectionEvent.Audio ──▶ IosVoicePlayer.play(pcm)
                                                │  Int16LE → Float32 (sample / 32768f)
                                                ▼
                                    queue: Channel<FloatArray>(4, DROP_OLDEST)
                                                │
                                                ▼ tryReceive() — never blocks
                        AVAudioSourceNode render block  ← REALTIME AUDIO THREAD
                          drains queued Float32 frames into the AudioBufferList
                          CoreAudio actually asks for; silence on underrun
```

`AVAudioSourceNode` was chosen over `AVAudioPlayerNode.scheduleBuffer` because a *pull* render
block matches "network delivers asynchronously, hardware consumes synchronously" more directly
than a *push* API with completion-handler bookkeeping — see the class KDoc in `IosAudio.kt` for
the full reasoning. The render block's playback cursor (which queued `FloatArray` is draining, and
how far into it) is local, captured state; the only cross-thread handoff is `queue`'s
`trySend`/`tryReceive`, the same non-suspending pair `DesktopVoicePlayer`'s `pending` channel
already relies on for an analogous producer/realtime-consumer split. The ring depth (4 frames)
matches Android's `AudioTrack` buffer depth (`AudioConfig.FRAME_BYTES * 4`).

Both classes' `AVAudioSession` category/activation goes through `IosAudioSession` (same file),
not through `IosPttSessionLauncher` — see that launcher's KDoc for how this interacts with
`UIBackgroundModes: audio` for backgrounded operation, and [Output routing and
volume](#output-routing-and-volume) for why the two classes no longer configure the session
individually. `prepare()`/`release()` are idempotent on both sides, and `PttController` always
releases the recorder and the player together, so `IosVoicePlayer.release()` deactivating the
shared `AVAudioSession` never cuts off capture still in progress on the other object.

**What is and isn't verified.** Every cinterop signature in `IosAudio.kt` was checked against
`klib dump-metadata` on this project's own Kotlin/Native platform klibs, and the file compiles for
real (`-PenableIosTargets=true :shared:compileKotlinIosSimulatorArm64`/`compileKotlinIosArm64`) —
but a Linux frontend compile cannot exercise real hardware, so behaviour (whether the one-shot
`AVAudioConverter` call actually drains a full tap buffer, render-thread timing, a real device's
mic route, `AVAudioSession` interruption handling) is unverified until `.github/workflows/ios.yml`
and a real device run. See the Phase 7b report for the full, prioritised list.

## Testing note

`audio/AudioContracts.kt` defines `VoiceRecorderContract` and `VoicePlayerContract` so the domain
layer can be unit-tested on the JVM — `AudioRecord` and `AudioTrack` are unavailable outside an
instrumented test. `PttControllerTest` drives fakes for both. `audio/FrameAccumulator.kt` is the
one piece of the desktop implementation that is hardware-independent and gets its own unit test
(`FrameAccumulatorTest`, `commonTest`); the rest of `DesktopVoiceRecorder`/`DesktopVoicePlayer` can
only be smoke-tested against real hardware.

On an emulator the microphone usually captures silence. Verification therefore asserts on frame
flow and floor state rather than on audibility — see [`testing.md`](testing.md). The same caution
applies to a desktop box whose default audio device is misrouted (see `known-issues.md`).
