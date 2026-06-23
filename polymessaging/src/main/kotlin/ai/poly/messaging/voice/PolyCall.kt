// Copyright PolyAI Limited

package ai.poly.messaging.voice

import ai.poly.messaging.Callback
import ai.poly.messaging.Cancellable
import ai.poly.messaging.Configuration
import ai.poly.messaging.PolyError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

/**
 * A voice call. **Voice is not yet implemented in this SDK build** — there is no bundled
 * on-device media (WebRTC audio) engine, so [start] surfaces [PolyError.Voice.NotImplemented].
 */
public class PolyCall internal constructor(
    @Suppress("UNUSED_PARAMETER") private val config: Configuration,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    // "...Flow" JVM getter so getState() stays unambiguous from Java (same pattern as ChatSession).
    @get:JvmName("getStateFlow")
    public val state: StateFlow<CallState> = _state.asStateFlow()

    public fun getState(): CallState = _state.value

    public suspend fun start() {
        _state.value = CallState.Failed(PolyError.Voice.NotImplemented)
        throw PolyError.Voice.NotImplemented
    }

    public suspend fun end() {
        _state.value = CallState.Ended
    }

    public suspend fun setMuted(@Suppress("UNUSED_PARAMETER") muted: Boolean) {
        // no-op until the media engine lands
    }

    // ---- Java callback overloads ----

    public fun start(executor: Executor, callback: Callback<Unit>): Cancellable = runAsync(executor, callback) { start() }
    public fun end(executor: Executor, callback: Callback<Unit>): Cancellable = runAsync(executor, callback) { end() }
    public fun setMuted(muted: Boolean, executor: Executor, callback: Callback<Unit>): Cancellable =
        runAsync(executor, callback) { setMuted(muted) }

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
