// Copyright PolyAI Limited

package ai.poly.messaging

/**
 * The live connection state. Rarely matched directly — prefer the helpers
 * ([isConnected], [isReconnecting], [isFailed], …).
 */
public sealed class ConnectionStatus {
    public object Idle : ConnectionStatus()
    public object Connecting : ConnectionStatus()
    public object Open : ConnectionStatus()
    public object Closing : ConnectionStatus()

    /** Transient disconnect — the SDK may still auto-reconnect via the reconnect ladder. */
    public class Closed(@JvmField public val event: ConnectionCloseEvent?) : ConnectionStatus() {
        override fun equals(other: Any?): Boolean = other is Closed && event == other.event
        override fun hashCode(): Int = event?.hashCode() ?: 0
    }

    public class Reconnecting(@JvmField public val attempt: Int) : ConnectionStatus() {
        override fun equals(other: Any?): Boolean = other is Reconnecting && attempt == other.attempt
        override fun hashCode(): Int = attempt
    }

    /**
     * Terminal: the SDK has exhausted its reconnect/invalid-session budgets and will
     * not retry on its own. Surface a manual "Reconnect" affordance (call `client.resume()`).
     */
    public class Failed(@JvmField public val reason: PolyError?) : ConnectionStatus() {
        override fun equals(other: Any?): Boolean = other is Failed && reason == other.reason
        override fun hashCode(): Int = reason?.hashCode() ?: 0
    }

    public val isConnected: Boolean get() = this is Open
    public val isReconnecting: Boolean get() = this is Reconnecting
    public val isFailed: Boolean get() = this is Failed
    public val isTerminal: Boolean get() = this is Failed
    public val isActive: Boolean get() = this is Connecting || this is Open || this is Reconnecting
    public val reconnectAttempt: Int? get() = (this as? Reconnecting)?.attempt
}

/** Why and how a socket closed. Arrives via the stream; not consumer-constructible. */
public class ConnectionCloseEvent internal constructor(
    @JvmField public val code: Int,
    @JvmField public val reason: String,
    @JvmField public val wasClean: Boolean,
) {
    override fun equals(other: Any?): Boolean =
        other is ConnectionCloseEvent && code == other.code && reason == other.reason && wasClean == other.wasClean
    override fun hashCode(): Int = (code * 31 + reason.hashCode()) * 31 + wasClean.hashCode()
    override fun toString(): String = "ConnectionCloseEvent(code=$code, reason=$reason, wasClean=$wasClean)"
}

/** WebSocket close codes the SDK distinguishes. */
public enum class CloseCode(public val code: Int) {
    NORMAL(1000),
    NO_STATUS(1005),
    ABNORMAL(1006),
    CLIENT_REPLACED(4000),
    SESSION_UNKNOWN(4001),
    APP_ERROR(4002),
    ;

    public companion object {
        @JvmStatic
        public fun fromCode(code: Int): CloseCode? = entries.firstOrNull { it.code == code }
    }
}
