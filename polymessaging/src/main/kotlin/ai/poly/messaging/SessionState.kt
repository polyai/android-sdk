// Copyright PolyAI Limited

package ai.poly.messaging

/** High-level session lifecycle status. */
public enum class SessionStatus(public val raw: String) {
    UNKNOWN("unknown"),
    ACTIVE("active"),
    ENDED("ended"),
    EXPIRED("expired"),
    RESTORED("restored"),
    ;

    public companion object {
        // Exact (case-sensitive) match.
        @JvmStatic
        public fun fromRaw(raw: String): SessionStatus =
            entries.firstOrNull { it.raw == raw } ?: UNKNOWN
    }
}

/**
 * Snapshot of the session's lifecycle. Surfaced as a `StateFlow` on the client.
 * Use [canSendMessages] / [isError] rather than matching fields.
 */
public class SessionState @JvmOverloads constructor(
    @JvmField public val status: SessionStatus,
    @JvmField public val isReady: Boolean,
    @JvmField public val isLoading: Boolean,
    @JvmField public val sessionId: String? = null,
    @JvmField public val error: SessionErrorCode? = null,
    @JvmField public val hasInvalidApiKey: Boolean = false,
) {
    public val isError: Boolean get() = error != null || hasInvalidApiKey
    // Invalid key → "Invalid API key"; otherwise the raw backend error string.
    public val errorMessage: String? get() = if (hasInvalidApiKey) "Invalid API key" else error?.raw
    public val isTerminal: Boolean get() = status == SessionStatus.ENDED || status == SessionStatus.EXPIRED
    // Exactly `isReady && !isError` — no status gate.
    public val canSendMessages: Boolean
        get() = isReady && !isError

    override fun equals(other: Any?): Boolean =
        other is SessionState && status == other.status && isReady == other.isReady &&
            isLoading == other.isLoading && sessionId == other.sessionId &&
            error == other.error && hasInvalidApiKey == other.hasInvalidApiKey

    override fun hashCode(): Int {
        var r = status.hashCode()
        r = 31 * r + isReady.hashCode(); r = 31 * r + isLoading.hashCode()
        r = 31 * r + (sessionId?.hashCode() ?: 0); r = 31 * r + (error?.hashCode() ?: 0)
        r = 31 * r + hasInvalidApiKey.hashCode()
        return r
    }
}
