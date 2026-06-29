// Copyright PolyAI Limited

package ai.poly.voice

import ai.poly.messaging.Callback
import ai.poly.messaging.Cancellable
import ai.poly.messaging.PolyError
import ai.poly.messaging.ValueListener
import ai.poly.messaging.voice.CallState
import ai.poly.voice.internal.services.CallCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor

/**
 * A WebRTC voice call to a PolyAI agent. Obtain one from `PolyVoice.call`, observe `state`, then
 * `start` it. The microphone (RECORD_AUDIO) runtime permission must be granted before `start` —
 * otherwise it fails fast with `PolyError.Voice.MediaFailed`.
 *
 * Mirrors the gated `ai.poly.messaging.voice.PolyCall` surface, but backed by a real media engine.
 */
public class VoiceCall internal constructor(
    private val coordinator: CallCoordinator,
    private val scope: CoroutineScope,
    private val permissionGranted: () -> Boolean,
) {
    /** The live call state. Observe for the `CallState.Connected` / `CallState.Failed` transitions. */
    // "...Flow" JVM getter so getState() stays unambiguous from Java (same pattern as ChatSession).
    @get:JvmName("getStateFlow")
    public val state: StateFlow<CallState> = coordinator.state

    /** Current call state snapshot (Java-friendly). */
    public fun getState(): CallState = coordinator.state.value

    /** Whether the local microphone is currently muted. */
    public fun isMuted(): Boolean = coordinator.isMuted()

    /**
     * Audio routing: the outputs available right now and the active one, as one consistent snapshot.
     * Updates live as devices connect/disconnect or you call [setAudioDevice]. Empty until the call's
     * audio is engaged (`start`).
     */
    @get:JvmName("getAudioFlow")
    public val audio: StateFlow<AudioState> = coordinator.audio

    /** Current audio-routing snapshot (Java-friendly). */
    public fun getAudio(): AudioState = coordinator.audio.value

    /**
     * Place the call: authenticate, link the session, exchange SDP/ICE, and open the audio stream.
     * Suspends until the offer is sent (the call is then `CallState.Connecting`); watch `state` for
     * `CallState.Connected`. Throws `PolyError.Voice` if the microphone permission is missing or the
     * setup fails — the same failure is reflected in `state`.
     */
    public suspend fun start() {
        if (!permissionGranted()) {
            val error = PolyError.Voice.MediaFailed("microphone permission (RECORD_AUDIO) not granted")
            coordinator.failPreflight(error)
            throw error
        }
        coordinator.start()
    }

    /** End the call and release the microphone. Safe to call at any time. */
    public suspend fun end(): Unit = withContext(scope.coroutineContext) { coordinator.endCall() }

    /** Mute or unmute the local microphone. */
    public suspend fun setMuted(muted: Boolean): Unit = withContext(scope.coroutineContext) { coordinator.setMuted(muted) }

    /**
     * Route call audio to [device] — pass an instance from [audio]'s `availableDevices`, or `null` to
     * revert to automatic routing (wired > Bluetooth > earpiece/speaker). The switch applies to the
     * live call; the result confirms asynchronously via [audio] (Bluetooth can take a few seconds), so
     * drive UI off the flow rather than this call returning. A no-op if [device] isn't available.
     */
    public suspend fun setAudioDevice(device: AudioDevice?): Unit =
        withContext(scope.coroutineContext) { coordinator.selectAudioDevice(device) }

    /**
     * Release this call permanently: ends it (if still active) and tears down its coroutine scope.
     * Call this when you're done with the object — after it, the `VoiceCall` can't be reused; obtain a
     * fresh one from `PolyVoice.call`. Mirrors `ChatSession.close()`.
     */
    public fun close(): Unit = coordinator.dispose()

    // ---- Java callback overloads (mirror PolyCall / ChatSession) ----

    public fun start(executor: Executor, callback: Callback<Unit>): Cancellable = runAsync(executor, callback) { start() }
    public fun end(executor: Executor, callback: Callback<Unit>): Cancellable = runAsync(executor, callback) { end() }
    public fun setMuted(muted: Boolean, executor: Executor, callback: Callback<Unit>): Cancellable =
        runAsync(executor, callback) { setMuted(muted) }
    public fun setAudioDevice(device: AudioDevice?, executor: Executor, callback: Callback<Unit>): Cancellable =
        runAsync(executor, callback) { setAudioDevice(device) }

    /**
     * Observe audio routing (Java-friendly). The listener fires immediately with the current snapshot,
     * then on every change, delivered on [executor]. Cancel the returned handle to stop observing.
     */
    public fun addAudioListener(executor: Executor, listener: ValueListener<AudioState>): Cancellable {
        val job = scope.launch { audio.collect { snapshot -> executor.execute { listener.onChanged(snapshot) } } }
        return Cancellable { job.cancel() }
    }

    private fun runAsync(executor: Executor, callback: Callback<Unit>, block: suspend () -> Unit): Cancellable {
        val job = scope.launch {
            try {
                block()
                executor.execute { callback.onSuccess(Unit) }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                executor.execute { callback.onError(t) }
            }
        }
        return Cancellable { job.cancel() }
    }
}
