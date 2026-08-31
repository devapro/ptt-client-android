package com.github.devapro.pttdroid.audio

import com.github.devapro.pttdroid.PttLog
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioConverter
import platform.AVFAudio.AVAudioConverterInputStatus_HaveData
import platform.AVFAudio.AVAudioConverterInputStatus_NoDataNow
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPCMFormatFloat32
import platform.AVFAudio.AVAudioPCMFormatInt16
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeVoiceChat
import platform.AVFAudio.AVAudioSourceNode
import platform.AVFAudio.setActive
import platform.Foundation.NSError
import platform.posix.memcpy

/**
 * Phase 7b: real `AVAudioEngine` capture, converted to [AudioConfig]'s wire format (16 kHz mono
 * PCM16LE) with an explicit [AVAudioConverter] — mirroring `:app`'s `VoiceRecorder`
 * (`AudioRecord`) and `:shared` desktopMain's `DesktopVoiceRecorder` (`javax.sound.sampled`), the
 * other two real implementations of [VoiceRecorderContract]. See
 * `docs/audio-pipeline.md#ios-capture--playback` for the full picture.
 *
 * **Confidence, by API — every cinterop signature below was checked against `klib dump-metadata`
 * on this project's actual Kotlin/Native 2.4.10 iosSimulatorArm64 platform klibs (not guessed),
 * and this file compiles for real
 * (`./gradlew -PenableIosTargets=true :shared:compileKotlinIosSimulatorArm64`/`compileKotlinIosArm64`,
 * Phase 7b) — but a Kotlin/Native *frontend* compile on Linux cannot catch a wrong runtime
 * *behaviour* (a format AVAudioConverter refuses at runtime, a render-thread priority quirk, a
 * real device's mic route). Treat everything here as compile-verified, behaviour-unverified until
 * `.github/workflows/ios.yml`'s link/Xcode steps and a real device run:**
 * - [AVAudioSession.setCategory]/`setActive` (the latter a **category-based extension** — see the
 *   `import platform.AVFAudio.setActive` above; unlike `setCategory`/`setMode`, which are genuine
 *   `AVAudioSession` class members, `setActive` is declared outside the class body in the klib
 *   dump exactly the way `NSURLProtectionSpace.serverTrust` was in `PttHttpClient.ios.kt` — see
 *   that file's KDoc for the general shape of this gotcha), `AVAudioEngine.inputNode`/`prepare`/
 *   `startAndReturnError`, `AVAudioNode.inputFormatForBus`/`installTapOnBus`/`removeTapOnBus`,
 *   `AVAudioIONode.setVoiceProcessingEnabled` — all genuine class members, all confirmed present
 *   with these exact parameter/return types.
 * - `AVAudioConverter(fromFormat, toFormat)`/`AVAudioPCMBuffer(format, frameCapacity)` — real
 *   (non-`Deprecated`) constructors, confirmed non-nullable-returning per the klib (unlike the
 *   deprecated `initXxx()` factory methods they replace, which return `T?`).
 * - `AVAudioConverter.convertToBuffer(outputBuffer:error:withInputFromBlock:)` (the block-based
 *   overload, not `convertToBuffer(outputBuffer:fromBuffer:error:)`) — chosen deliberately: Apple's
 *   own header documents the non-block overload as unsupported for sample-rate conversion, and a
 *   phone's hardware input is essentially never natively 16 kHz, so this path performs a real rate
 *   conversion on every call. That specific piece of documentation could not be re-verified here
 *   (klib metadata carries signatures, not header prose), so this is inference from a
 *   well-known/widely-documented Core Audio constraint, not a klib-verified fact — flagged
 *   accordingly.
 * - `AVAudioPCMBuffer.int16ChannelData`/`frameLength` and `AVAudioFormat`'s
 *   `AVAudioPCMFormatInt16`/`AVAudioPCMFormatFloat32` constants — genuine members/top-level
 *   `const val`s respectively, confirmed.
 *
 * **A second import gotcha, same shape as the `serverTrust` one**: `kotlinx.cinterop`'s indexed
 * `CPointer<T>.get`/`.set`/`.plus` operators (used in [IosVoicePlayer]'s render block to write
 * samples through a raw `CPointer<FloatVar>`) are themselves ordinary top-level extensions in the
 * `kotlinx.cinterop` package, not auto-imported by `import kotlinx.cinterop.ExperimentalForeignApi`
 * or any of the other specific imports above — omitting `import kotlinx.cinterop.set`/`.plus`
 * produced "Unresolved reference" pointing at unrelated `StringBuilder.set`/`Map.plus` overloads
 * instead of a clear "did you mean to import this" message, confirmed by actually hitting and
 * fixing it while compiling this file for iosSimulatorArm64.
 *
 * The one thing that is genuinely **behaviour-unverified, not just unlinked**: whether
 * `AVAudioConverter`'s block-based conversion, fed exactly one buffer per call and told
 * "no more data" on the second invocation of the input block (see [start]), actually drains that
 * single buffer completely in one [AVAudioConverter.convertToBuffer] call rather than needing to
 * be called again for the same input — Apple's documented contract for this API assumes a
 * streaming caller that keeps calling until the input block reports `EndOfStream`, and a
 * one-shot-per-tap-callback caller is the same shape every third-party AVAudioConverter tutorial
 * assumes, but "the same shape everyone uses" is not "confirmed against this exact klib." If
 * captured audio comes out truncated on a real device, this is the first thing to look at.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosVoiceRecorder(private val scope: CoroutineScope) : VoiceRecorderContract {

    private val engine = AVAudioEngine()
    private var running = false
    private var accumulatorJob: Job? = null

    /**
     * Raw PCM16LE mono chunks straight off the tap + converter, one per hardware tap callback
     * (arbitrary-sized — whatever [TAP_BUFFER_FRAMES] converts down to at the device's native
     * rate) — never AudioConfig.FRAME_BYTES-exact on its own. [frames] is what's exact: a
     * `Dispatchers.Default` coroutine drains this channel through the shared, already-tested
     * [FrameAccumulator] (Phase 6) to reshape it into wire-exact frames, off the realtime audio
     * thread the tap callback itself must not block.
     */
    private val rawChunks: Channel<ByteArray> = Channel(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Same realtime/lossy semantics as every other platform's frames channel: drop the oldest
     *  frame rather than let a slow consumer build an unbounded backlog of stale audio. */
    override val frames: Channel<ByteArray> = Channel(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override fun start() {
        if (running) return

        runCatching {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(AVAudioSessionCategoryPlayAndRecord, AVAudioSessionModeVoiceChat, 0u, null)
            session.setActive(true, null)
        }.onFailure { PttLog.w(it) { "AVAudioSession configuration failed; recording may not work" } }

        val inputNode = engine.inputNode
        // iOS 13+; can fail on some hardware (per the task brief) and must not be fatal to
        // capture starting at all — echo cancellation is a nice-to-have, not a requirement.
        runCatching { inputNode.setVoiceProcessingEnabled(true, null) }
            .onFailure { PttLog.w(it) { "setVoiceProcessingEnabled failed; continuing without echo cancellation" } }

        // Tap in the hardware's own format and convert explicitly — the tap is not guaranteed to
        // accept a different sample rate directly (device-dependent), so this is the safe path.
        val hwFormat = inputNode.inputFormatForBus(0u)
        val targetFormat = AVAudioFormat(
            AVAudioPCMFormatInt16,
            AudioConfig.SAMPLE_RATE_HZ.toDouble(),
            AudioConfig.CHANNEL_COUNT.toUInt(),
            false,
        )

        val setup = runCatching {
            val converter = AVAudioConverter(hwFormat, targetFormat)
            // Sized against the worst case this class ever asks for (TAP_BUFFER_FRAMES hardware
            // frames converted at up to that same ratio), then reused for every tap callback —
            // allocating a fresh AVAudioPCMBuffer per callback is exactly the "heavy allocation"
            // the realtime tap thread below must not do.
            val ratio = targetFormat.sampleRate / hwFormat.sampleRate.coerceAtLeast(1.0)
            val outputCapacity = (TAP_BUFFER_FRAMES.toDouble() * ratio).toUInt() + 64u
            converter to AVAudioPCMBuffer(targetFormat, outputCapacity)
        }.onFailure {
            PttLog.e(it) { "Could not set up the capture format converter; running listen-only" }
        }.getOrNull() ?: return
        val (converter, outputBuffer) = setup

        accumulatorJob = scope.launch(Dispatchers.Default) {
            val accumulator = FrameAccumulator()
            for (chunk in rawChunks) {
                for (frame in accumulator.accumulate(chunk)) {
                    frames.trySend(frame)
                }
            }
        }

        // REALTIME AUDIO THREAD. Convert, one memcpy into a ByteArray, one trySend — nothing
        // else. No logging, no per-call AVAudioPCMBuffer allocation (outputBuffer/converter are
        // captured, allocated once above), no blocking. Re-chunking to exact frame sizes happens
        // off this thread, in the accumulatorJob coroutine started just above.
        inputNode.installTapOnBus(0u, TAP_BUFFER_FRAMES, hwFormat) { buffer, _ ->
            if (buffer == null) return@installTapOnBus
            var provided = false
            converter.convertToBuffer(outputBuffer, null) { _, outStatus ->
                if (provided) {
                    outStatus?.pointed?.value = AVAudioConverterInputStatus_NoDataNow
                    null
                } else {
                    provided = true
                    outStatus?.pointed?.value = AVAudioConverterInputStatus_HaveData
                    buffer
                }
            }
            val frameLen = outputBuffer.frameLength.toInt()
            if (frameLen <= 0) return@installTapOnBus
            val channelPtr = outputBuffer.int16ChannelData?.get(0) ?: return@installTapOnBus
            val byteCount = frameLen * AudioConfig.BYTES_PER_SAMPLE
            val bytes = ByteArray(byteCount)
            bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), channelPtr, byteCount.convert()) }
            rawChunks.trySend(bytes)
        }

        engine.prepare()
        val started = memScoped {
            val errorVar = alloc<ObjCObjectVar<NSError?>>()
            val ok = engine.startAndReturnError(errorVar.ptr)
            if (!ok) {
                PttLog.e { "AVAudioEngine failed to start: ${errorVar.value?.localizedDescription ?: "unknown error"}" }
            }
            ok
        }
        if (!started) {
            runCatching { inputNode.removeTapOnBus(0u) }
            accumulatorJob?.cancel()
            accumulatorJob = null
            return
        }
        running = true
    }

    override fun stop() {
        if (!running) return
        running = false
        runCatching { engine.inputNode.removeTapOnBus(0u) }
        runCatching { engine.stop() }
        accumulatorJob?.cancel()
        accumulatorJob = null
    }

    /** Idempotent; safe to call more than once. */
    override fun release() {
        stop()
        runCatching { AVAudioSession.sharedInstance().setActive(false, null) }
            .onFailure { PttLog.w(it) { "AVAudioSession deactivation failed" } }
    }

    private companion object {
        /** Hardware tap buffer size in *hardware-rate* frames. Not load-bearing for correctness —
         *  [FrameAccumulator] reshapes whatever arrives into exact [AudioConfig.FRAME_BYTES]
         *  pieces regardless of this value — chosen close to one wire frame's duration (40 ms) so
         *  the tap doesn't accumulate many callbacks' worth of latency before anything is sent. */
        const val TAP_BUFFER_FRAMES: UInt = 2048u
    }
}

/**
 * Phase 7b: real playback via an [AVAudioSourceNode] *pull* render node — chosen over
 * `AVAudioPlayerNode.scheduleBuffer`'s push model because a pull render block maps directly onto
 * "network delivers asynchronously, hardware consumes synchronously" (see the task brief); its
 * cinterop shape (a plain function type of primitives and `CPointer`s — no Objective-C category
 * lookups involved) proved entirely workable once checked against klib metadata, so the
 * `AVAudioPlayerNode` fallback was not needed.
 *
 * **Int16 vs Float32**: [AVAudioSourceNode]'s render block hands back an `AudioBufferList` to fill
 * — verified against klib metadata (`AVAudioPCMFormatFloat32`/`Int16` constants, the struct layout
 * of `AudioBufferList`/`AudioBuffer` in `platform.CoreAudioTypes`) that both PCM formats are
 * available. Float32 was chosen because every third-party (and Apple sample code) use of
 * `AVAudioSourceNode` renders Float32 into the buffer directly with no converter node in between —
 * unlike the recorder side, there is no *documented* Int16 restriction to reproduce, and skipping
 * a second `AVAudioConverter` on the playback path is one fewer realtime-thread risk. Int16→Float32
 * conversion (`sample / 32768f`) therefore happens once in [play], off the realtime thread, at the
 * ring-buffer boundary — exactly where the task brief calls for it.
 *
 * [prepare]/[release] are idempotent — `docs/known-issues.md` records a previous double-release
 * bug (`VoicePlayer`/`AudioTrack`, defect #6) this mirrors the fix for.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosVoicePlayer : VoicePlayerContract {

    private val engine = AVAudioEngine()
    private var node: AVAudioSourceNode? = null

    /**
     * ~4 network frames of slack (Float32 mono, one array per received wire frame), matching
     * Android's `AudioTrack` buffer depth (`AudioConfig.FRAME_BYTES * 4` — see
     * `docs/audio-pipeline.md`). [play] (called off the realtime thread, from
     * `PttController`'s event-observing coroutine) is the only sender; the render block installed
     * in [prepare] is the only receiver, via `kotlinx.coroutines.Channel`'s non-suspending
     * `trySend`/`tryReceive` — the same primitive `DesktopVoicePlayer`'s `pending` channel already
     * relies on for exactly this producer/realtime-consumer split, and safe to call from any
     * thread without blocking. Drop-oldest on overflow; the render block treats "nothing queued"
     * as silence rather than blocking to wait for one.
     */
    private val queue: Channel<FloatArray> = Channel(capacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Idempotent; safe to call more than once. */
    override fun prepare() {
        if (node != null) return

        runCatching {
            val session = AVAudioSession.sharedInstance()
            session.setCategory(AVAudioSessionCategoryPlayAndRecord, AVAudioSessionModeVoiceChat, 0u, null)
            session.setActive(true, null)
        }.onFailure { PttLog.w(it) { "AVAudioSession configuration failed; playback may not work" } }

        val format = AVAudioFormat(
            AVAudioPCMFormatFloat32,
            AudioConfig.SAMPLE_RATE_HZ.toDouble(),
            AudioConfig.CHANNEL_COUNT.toUInt(),
            false,
        )

        // Render-block-local playback cursor: which queued frame is currently draining, and how
        // far into it. Only this render block ever reads or writes these two captured vars, so no
        // synchronization beyond `queue`'s own tryReceive is needed.
        var current: FloatArray? = null
        var offset = 0

        // REALTIME AUDIO THREAD. No allocation beyond what `queue.tryReceive()` itself does
        // (returning a reference to a FloatArray `play()` already allocated) — never blocks: an
        // empty queue just means silence for the rest of this call.
        val sourceNode = AVAudioSourceNode(format) { isSilence, _, frameCount, outputData ->
            val dest = outputData?.pointed?.mBuffers?.get(0)?.mData?.reinterpret<FloatVar>()
            val frameCountInt = frameCount.toInt()
            if (dest == null) {
                isSilence?.pointed?.value = true
                return@AVAudioSourceNode 0
            }
            var written = 0
            while (written < frameCountInt) {
                val active = current
                if (active == null || offset >= active.size) {
                    val next = queue.tryReceive().getOrNull()
                    if (next == null) break
                    current = next
                    offset = 0
                    continue
                }
                (dest + written)!!.pointed.value = active[offset]
                offset++
                written++
            }
            for (i in written until frameCountInt) {
                (dest + i)!!.pointed.value = 0f
            }
            isSilence?.pointed?.value = written == 0
            0
        }

        runCatching {
            engine.attachNode(sourceNode)
            engine.connect(sourceNode, engine.mainMixerNode, format)
            engine.prepare()
        }.onFailure {
            PttLog.e(it) { "Could not configure the iOS playback engine; audio will not play" }
            return
        }

        val started = memScoped {
            val errorVar = alloc<ObjCObjectVar<NSError?>>()
            val ok = engine.startAndReturnError(errorVar.ptr)
            if (!ok) {
                PttLog.e { "AVAudioEngine failed to start for playback: ${errorVar.value?.localizedDescription ?: "unknown error"}" }
            }
            ok
        }
        if (!started) {
            runCatching { engine.detachNode(sourceNode) }
            return
        }

        node = sourceNode
    }

    /** Writes one received frame. No logging here — this runs per audio frame. */
    override fun play(pcm: ByteArray) {
        if (pcm.isEmpty() || node == null) return
        val sampleCount = pcm.size / AudioConfig.BYTES_PER_SAMPLE
        val floats = FloatArray(sampleCount)
        for (i in 0 until sampleCount) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt() and 0xFF
            floats[i] = ((hi shl 8) or lo).toShort() / 32768f
        }
        queue.trySend(floats)
    }

    /**
     * Idempotent; safe to call more than once and from either side of a reconnect.
     *
     * Also deactivates the shared `AVAudioSession` — safe because `PttController` always releases
     * the recorder and the player together at teardown (`domain/PttController.kt`), never one
     * without the other, so there is no live capture left depending on the session staying active
     * by the time this runs.
     */
    override fun release() {
        val current = node ?: return
        node = null
        runCatching { engine.stop() }
        runCatching { engine.detachNode(current) }
        runCatching { AVAudioSession.sharedInstance().setActive(false, null) }
            .onFailure { PttLog.w(it) { "AVAudioSession deactivation failed" } }
    }
}
