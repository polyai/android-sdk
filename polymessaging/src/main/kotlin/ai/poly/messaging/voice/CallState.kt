// Copyright PolyAI Limited

package ai.poly.messaging.voice

import ai.poly.messaging.PolyError

/**
 * State of a [PolyCall]. Voice calling is not yet implemented in this SDK build —
 * starting a call surfaces [PolyError.Voice.NotImplemented].
 */
public sealed class CallState {
    public object Idle : CallState()
    public object Connecting : CallState()
    public object Connected : CallState()
    public object Ended : CallState()
    public class Failed(@JvmField public val error: PolyError) : CallState() {
        override fun equals(other: Any?): Boolean = other is Failed && error == other.error
        override fun hashCode(): Int = error.hashCode()
    }

    public val isActive: Boolean get() = this is Connecting || this is Connected
}
