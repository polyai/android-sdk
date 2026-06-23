// Copyright PolyAI Limited

package ai.poly.messaging

/** Live-agent typing state on the wire. */
public enum class TypingState(public val raw: String) {
    STARTED("TYPING_STATE_STARTED"),
    STOPPED("TYPING_STATE_STOPPED"),
    ;

    public companion object {
        @JvmStatic
        public fun fromRaw(raw: String): TypingState? = entries.firstOrNull { it.raw == raw }
    }
}
