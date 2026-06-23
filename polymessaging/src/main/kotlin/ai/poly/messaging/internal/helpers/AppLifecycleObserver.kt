// Copyright PolyAI Limited

package ai.poly.messaging.internal.helpers

import ai.poly.messaging.internal.ports.ForegroundPort
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Emits when the app returns to the foreground. The [Coordinator]
 * uses it to touch the session, run the idle-timeout check, and reconnect a stale socket.
 * Observes the process lifecycle, so it works regardless of which Activity is on screen.
 */
internal class AppLifecycleObserver : ForegroundPort {

    private val _foreground = MutableSharedFlow<Unit>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val foreground: SharedFlow<Unit> = _foreground.asSharedFlow()

    private val mainHandler = Handler(Looper.getMainLooper())

    private val observer = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            _foreground.tryEmit(Unit)
        }
    }

    override fun start() {
        // ProcessLifecycleOwner must be touched on the main thread.
        mainHandler.post {
            runCatching { ProcessLifecycleOwner.get().lifecycle.addObserver(observer) }
        }
    }

    override fun stop() {
        mainHandler.post {
            runCatching { ProcessLifecycleOwner.get().lifecycle.removeObserver(observer) }
        }
    }
}
