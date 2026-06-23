// Copyright PolyAI Limited

package ai.poly.messaging.internal.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.BufferOverflow

/**
 * Emits a tick every interval; the [Coordinator] turns a tick into a heartbeat frame when
 * the socket is open. Includes the `0` disable sentinel.
 */
internal class HeartbeatService(
    private val scope: CoroutineScope,
    private val defaultIntervalSeconds: Int = 30,
) {
    private val _ticks = MutableSharedFlow<Unit>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val ticks: SharedFlow<Unit> = _ticks.asSharedFlow()

    private var intervalSeconds: Int = defaultIntervalSeconds
    private var job: Job? = null

    /** Start (or restart) the loop. `intervalSeconds <= 0` disables the heartbeat. */
    fun start(intervalSeconds: Int = this.intervalSeconds) {
        this.intervalSeconds = intervalSeconds
        restart()
    }

    /** Apply a server capability override; `0` disables. `null`/negative resets to default. Only
     *  (re)starts the loop if it was already running — a stopped heartbeat stays stopped until OPEN. */
    fun applyServerInterval(seconds: Int?) {
        val wasRunning = job != null
        // Resetting to the default interval is gated by `seconds > 0`: a non-positive
        // default is a complete no-op (a running loop keeps its prior interval), it does NOT disable the loop.
        when {
            seconds == null -> { if (defaultIntervalSeconds > 0) { intervalSeconds = defaultIntervalSeconds; if (wasRunning) restart() } }
            seconds == 0 -> stop()
            seconds > 0 -> { intervalSeconds = seconds; if (wasRunning) restart() }
            else -> { if (defaultIntervalSeconds > 0) { intervalSeconds = defaultIntervalSeconds; if (wasRunning) restart() } } // negative → default (no-op if default ≤ 0)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun restart() {
        job?.cancel()
        if (intervalSeconds <= 0) { job = null; return }
        val seconds = intervalSeconds
        job = scope.launch {
            while (isActive) {
                delay(seconds * 1000L)
                _ticks.tryEmit(Unit)
            }
        }
    }
}
