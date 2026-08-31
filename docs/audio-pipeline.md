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

`AudioTrack` is built with `AudioTrack.Builder`, `USAGE_VOICE_COMMUNICATION` and
`CONTENT_TYPE_SPEECH` — this is a comms app, so routing and volume should follow the voice-call
stream rather than media. It is prepared on `welcome` and released on disconnect.

There is no jitter buffer beyond `AudioTrack`'s own; on a LAN this is adequate.

## Lifecycle

Both classes are owned by `PttController`, and both `release()` methods are idempotent:

| | `VoiceRecorder` | `VoicePlayer` |
|---|---|---|
| Create | lazily in `ensureRecord()`, reused | `prepare()`, no-op if already built |
| Start | `start()` — `startRecording()` + one read coroutine | implicit, `AudioTrack.play()` in `prepare()` |
| Stop | `stop()` — cancels the read job, `AudioRecord.stop()` | — |
| Release | `release()` — stops, releases, **nulls the field** | `release()` — stops, releases, **nulls the field** |

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
AVAudioSession (PlayAndRecord / VoiceChat, active)
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

Both classes' `AVAudioSession` category/activation happens here, not in `IosPttSessionLauncher` —
see that class's KDoc for how that interacts with `UIBackgroundModes: audio` for backgrounded
operation. `prepare()`/`release()` are idempotent on both sides, and `PttController` always
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
