// Copyright PolyAI Limited

package ai.poly.messaging

/**
 * An event the client can send over the raw transport (`getConnection().send(...)`).
 * Most apps never construct these — use [ChatSession]/[PolyMessagingClient] instead.
 * Only these encode to the wire.
 */
public sealed class OutgoingEvent {
    public class UserMessage @JvmOverloads constructor(
        @JvmField public val text: String,
        @JvmField public val metadata: Map<String, String>? = null,
    ) : OutgoingEvent()

    public class UserTyping(@JvmField public val state: TypingState) : OutgoingEvent()

    /** Encodes to `EVENT_TYPE_USER_END_SESSION` (same wire type as [UserLeft]). */
    public object UserEndConversation : OutgoingEvent()

    /** Encodes to `EVENT_TYPE_USER_END_SESSION` (same wire type as [UserEndConversation]). */
    public object UserLeft : OutgoingEvent()

    public object RequestPolyAgentJoin : OutgoingEvent()

    public object Heartbeat : OutgoingEvent()
}
