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

## Testing note

`audio/AudioContracts.kt` defines `VoiceRecorderContract` and `VoicePlayerContract` so the domain
layer can be unit-tested on the JVM — `AudioRecord` and `AudioTrack` are unavailable outside an
instrumented test. `PttControllerTest` drives fakes for both.

On an emulator the microphone usually captures silence. Verification therefore asserts on frame
flow and floor state rather than on audibility — see [`testing.md`](testing.md).
