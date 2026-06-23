// Copyright PolyAI Limited

package ai.poly.messaging

/**
 * Metadata attached to a wire event (`metadata.custom`). */
public class EventMetadata internal constructor(
    @JvmField public val custom: Map<String, String>?,
) {
    // Value equality over `custom`. Explicit overrides (not a
    // data class) keep the `internal constructor` from leaking a public copy()/componentN().
    override fun equals(other: Any?): Boolean =
        this === other || (other is EventMetadata && custom == other.custom)
    override fun hashCode(): Int = custom?.hashCode() ?: 0
}

/**
 * The wire envelope every server event carries: a stable [id], an optional monotonic
 * [sequence] (used for cursor replay + dedup), an epoch-millis [timestamp], and
 * optional [metadata]. */
public class Envelope internal constructor(
    @JvmField public val id: String,
    @JvmField public val sequence: Int?,
    @JvmField public val timestamp: Long,
    @JvmField public val metadata: EventMetadata?,
) {
    // Structural value equality over all stored fields.
    override fun equals(other: Any?): Boolean =
        this === other || (other is Envelope && id == other.id && sequence == other.sequence &&
            timestamp == other.timestamp && metadata == other.metadata)
    override fun hashCode(): Int {
        var r = id.hashCode()
        r = 31 * r + (sequence ?: 0)
        r = 31 * r + timestamp.hashCode()
        r = 31 * r + (metadata?.hashCode() ?: 0)
        return r
    }
}

/**
 * The low-level typed event stream (`client.events`). Most apps observe [ChatSession]
 * instead; drop here for imperative side effects (navigation, analytics, haptics).
 *
 * Java callers classify via [envelope] / [isAgentEvent] / [isHandoffEvent] / … on the
 * base instead of a 30-arm `instanceof` chain (~30 cases).
 */
public sealed class MessagingEvent {

    // ---- Session lifecycle ----
    public class SessionStart(@JvmField public val env: Envelope, @JvmField public val payload: SessionStartPayload) : MessagingEvent()
    public class SessionEnd(@JvmField public val env: Envelope, @JvmField public val payload: SessionEndPayload) : MessagingEvent()
    public class SessionIdleWarning(@JvmField public val env: Envelope) : MessagingEvent()

    // ---- User (echoed back by the server) ----
    public class UserMessage(@JvmField public val env: Envelope, @JvmField public val payload: UserMessageEchoPayload) : MessagingEvent()
    public class UserTyping(@JvmField public val env: Envelope) : MessagingEvent()
    public class UserEndSession(@JvmField public val env: Envelope) : MessagingEvent()
    public class RequestPolyAgentJoin(@JvmField public val env: Envelope) : MessagingEvent()
    public class MessagePending(@JvmField public val draftId: String, @JvmField public val text: String) : MessagingEvent()
    public class MessageConfirmed(@JvmField public val draftId: String, @JvmField public val messageId: String) : MessagingEvent()
    public class MessageFailed(@JvmField public val draftId: String) : MessagingEvent()

    // ---- Poly agent ----
    public class AgentJoined(@JvmField public val env: Envelope, @JvmField public val payload: AgentJoinedPayload) : MessagingEvent()
    public class AgentThinking(@JvmField public val env: Envelope) : MessagingEvent()
    public class AgentMessage(@JvmField public val env: Envelope, @JvmField public val payload: AgentMessagePayload) : MessagingEvent()
    public class AgentMessageChunk(@JvmField public val env: Envelope, @JvmField public val payload: AgentMessageChunkPayload) : MessagingEvent()
    public class AgentLeft(@JvmField public val env: Envelope, @JvmField public val payload: AgentLeftPayload) : MessagingEvent()
    public class AgentTriggeredHandoff(@JvmField public val env: Envelope) : MessagingEvent()

    // ---- Live agent ----
    public class LiveAgentJoined(@JvmField public val env: Envelope, @JvmField public val payload: LiveAgentJoinedPayload) : MessagingEvent()
    public class LiveAgentTyping(@JvmField public val env: Envelope, @JvmField public val payload: LiveAgentTypingPayload) : MessagingEvent()
    public class LiveAgentMessage(@JvmField public val env: Envelope, @JvmField public val payload: LiveAgentMessagePayload) : MessagingEvent()
    public class LiveAgentLeft(@JvmField public val env: Envelope, @JvmField public val payload: LiveAgentLeftPayload) : MessagingEvent()

    // ---- System ----
    public class SystemMessage(@JvmField public val env: Envelope, @JvmField public val payload: SystemMessagePayload) : MessagingEvent()
    public class Heartbeat(@JvmField public val env: Envelope) : MessagingEvent()

    // ---- Handoff ----
    public class ClientHandoffRequired(@JvmField public val env: Envelope, @JvmField public val payload: ClientHandoffRequiredPayload) : MessagingEvent()
    public class HandoffQueueStatus(@JvmField public val env: Envelope, @JvmField public val payload: HandoffQueueStatusPayload) : MessagingEvent()
    public class HandoffAccepted(@JvmField public val env: Envelope, @JvmField public val payload: HandoffAcceptedPayload) : MessagingEvent()
    public class HandoffFailed(@JvmField public val env: Envelope, @JvmField public val payload: HandoffFailedPayload) : MessagingEvent()
    public class HandoffTimeout(@JvmField public val env: Envelope, @JvmField public val payload: HandoffTimeoutPayload) : MessagingEvent()

    // ---- Connection lifecycle (no envelope) ----
    public object Connected : MessagingEvent()
    public class Reconnecting(@JvmField public val attempt: Int) : MessagingEvent()
    public class Disconnected(@JvmField public val error: PolyError?) : MessagingEvent()

    /** The wire envelope, or null for connection/delivery events that carry none. */
    public val envelope: Envelope?
        get() = when (this) {
            is SessionStart -> env
            is SessionEnd -> env
            is SessionIdleWarning -> env
            is UserMessage -> env
            is UserTyping -> env
            is UserEndSession -> env
            is RequestPolyAgentJoin -> env
            is AgentJoined -> env
            is AgentThinking -> env
            is AgentMessage -> env
            is AgentMessageChunk -> env
            is AgentLeft -> env
            is AgentTriggeredHandoff -> env
            is LiveAgentJoined -> env
            is LiveAgentTyping -> env
            is LiveAgentMessage -> env
            is LiveAgentLeft -> env
            is SystemMessage -> env
            is Heartbeat -> env
            is ClientHandoffRequired -> env
            is HandoffQueueStatus -> env
            is HandoffAccepted -> env
            is HandoffFailed -> env
            is HandoffTimeout -> env
            is Connected, is Reconnecting, is Disconnected,
            is MessagePending, is MessageConfirmed, is MessageFailed,
            -> null
        }

    public val isConnectionEvent: Boolean
        get() = this is Connected || this is Reconnecting || this is Disconnected

    public val isHandoffEvent: Boolean
        get() = when (this) {
            is ClientHandoffRequired, is HandoffQueueStatus, is HandoffAccepted,
            is HandoffFailed, is HandoffTimeout, is AgentTriggeredHandoff,
            -> true
            else -> false
        }

    public val isAgentEvent: Boolean
        get() = when (this) {
            is AgentJoined, is AgentThinking, is AgentMessage, is AgentMessageChunk, is AgentLeft -> true
            else -> false
        }

    public val isLiveAgentEvent: Boolean
        get() = when (this) {
            is LiveAgentJoined, is LiveAgentTyping, is LiveAgentMessage, is LiveAgentLeft -> true
            else -> false
        }

    public val isDeliveryEvent: Boolean
        get() = this is MessagePending || this is MessageConfirmed || this is MessageFailed
}
