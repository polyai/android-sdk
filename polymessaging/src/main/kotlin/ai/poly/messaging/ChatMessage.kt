// Copyright PolyAI Limited

package ai.poly.messaging

import java.net.URI
import java.util.UUID

/** Whether an agent message came from the Poly AI agent or a human live agent. */
public enum class AgentKind { POLY, LIVE }

/** Delivery state of an outgoing user message. */
public enum class Delivery { PENDING, SENT, FAILED }

/**
 * A single row in the conversation. Render with an exhaustive `when` over the three
 * cases (`is ChatMessage.User` / `Agent` / `System`); Java callers can use the base
 * helpers ([isUser], [isAgent], [isSystem], [text], …) to avoid `instanceof`.
 *
 * Timestamps are epoch milliseconds (UTC) for zero-friction Java + minSdk-24 use.
 */
public sealed class ChatMessage {

    public class User(@JvmField public val message: UserMessage) : ChatMessage()
    public class Agent(@JvmField public val message: AgentMessage) : ChatMessage()
    public class System(@JvmField public val message: SystemMessage) : ChatMessage()

    public val id: UUID
        get() = when (this) {
            is User -> message.id
            is Agent -> message.id
            is System -> message.id
        }

    public val timestamp: Long
        get() = when (this) {
            is User -> message.timestamp
            is Agent -> message.timestamp
            is System -> message.timestamp
        }

    public val text: String?
        get() = when (this) {
            is User -> message.text
            is Agent -> message.text
            is System -> null
        }

    public val isUser: Boolean get() = this is User
    public val isAgent: Boolean get() = this is Agent
    public val isSystem: Boolean get() = this is System

    public val delivery: Delivery? get() = (this as? User)?.message?.delivery
    public val suggestions: List<ResponseSuggestion> get() = (this as? Agent)?.message?.suggestions ?: emptyList()
    public val attachments: List<Attachment> get() = (this as? Agent)?.message?.attachments ?: emptyList()

    override fun equals(other: Any?): Boolean = when {
        this is User && other is User -> message == other.message
        this is Agent && other is Agent -> message == other.message
        this is System && other is System -> message == other.message
        else -> false
    }

    override fun hashCode(): Int = when (this) {
        is User -> message.hashCode()
        is Agent -> message.hashCode()
        is System -> message.hashCode()
    }
}

/** A message the user sent (or had echoed back on resume). */
public class UserMessage @JvmOverloads constructor(
    @JvmField public val text: String,
    @JvmField public val delivery: Delivery,
    @JvmField public val draftId: String,
    @JvmField public val id: UUID = UUID.randomUUID(),
    @JvmField public val timestamp: Long = System.currentTimeMillis(),
) {
    override fun equals(other: Any?): Boolean =
        other is UserMessage && id == other.id && text == other.text &&
            delivery == other.delivery && draftId == other.draftId && timestamp == other.timestamp

    override fun hashCode(): Int {
        var r = id.hashCode()
        r = 31 * r + text.hashCode(); r = 31 * r + delivery.hashCode()
        r = 31 * r + draftId.hashCode(); r = 31 * r + timestamp.hashCode()
        return r
    }
}

/** A message from the Poly agent or a live agent. */
public class AgentMessage @JvmOverloads constructor(
    @JvmField public val messageId: String,
    @JvmField public val agentKind: AgentKind,
    @JvmField public val text: String,
    @JvmField public val agentName: String? = null,
    @JvmField public val avatarUrl: URI? = null,
    @JvmField public val attachments: List<Attachment> = emptyList(),
    @JvmField public val suggestions: List<ResponseSuggestion> = emptyList(),
    @JvmField public val callActions: List<ChatCallAction> = emptyList(),
    @JvmField public val id: UUID = UUID.randomUUID(),
    @JvmField public val timestamp: Long = System.currentTimeMillis(),
) {
    override fun equals(other: Any?): Boolean =
        other is AgentMessage && id == other.id && messageId == other.messageId &&
            agentKind == other.agentKind && text == other.text && agentName == other.agentName &&
            avatarUrl == other.avatarUrl && attachments == other.attachments &&
            suggestions == other.suggestions && callActions == other.callActions && timestamp == other.timestamp

    override fun hashCode(): Int {
        var r = id.hashCode()
        r = 31 * r + messageId.hashCode(); r = 31 * r + text.hashCode()
        r = 31 * r + agentKind.hashCode(); r = 31 * r + (agentName?.hashCode() ?: 0)
        r = 31 * r + suggestions.hashCode(); r = 31 * r + attachments.hashCode()
        return r
    }
}

/** A non-user, non-agent event rendered as a pill/separator (handoff, queue, etc.). */
public class SystemMessage @JvmOverloads constructor(
    @JvmField public val event: SystemEvent,
    @JvmField public val id: UUID = UUID.randomUUID(),
    @JvmField public val timestamp: Long = System.currentTimeMillis(),
) {
    override fun equals(other: Any?): Boolean =
        other is SystemMessage && id == other.id && event == other.event && timestamp == other.timestamp
    override fun hashCode(): Int = (id.hashCode() * 31 + event.hashCode()) * 31 + timestamp.hashCode()
}

/**
 * What a [SystemMessage] represents. Java callers can read [reason]/[isHandoff]/
 * [isTerminal] on the base without branching.
 */
public sealed class SystemEvent {
    public class ConversationEnded(@JvmField public val reasonText: String?) : SystemEvent()
    public class AgentLeft(@JvmField public val reasonText: String?) : SystemEvent()
    public class LiveAgentJoined(@JvmField public val name: String?) : SystemEvent()
    public class LiveAgentLeft(@JvmField public val reasonText: String?) : SystemEvent()
    public class QueueStatus(
        @JvmField public val position: Int?,
        @JvmField public val displayMessage: String?,
    ) : SystemEvent()
    public object HandoffStarted : SystemEvent()
    public class HandoffRequired(@JvmField public val reasonText: String) : SystemEvent()
    public object HandoffAccepted : SystemEvent()
    public class HandoffFailed(@JvmField public val reasonText: String?) : SystemEvent()
    public object HandoffTimeout : SystemEvent()
    public object IdleWarning : SystemEvent()
    public class ServerMessage(
        @JvmField public val text: String,
        @JvmField public val level: SystemMessageLevel,
    ) : SystemEvent()

    public val isHandoff: Boolean
        get() = when (this) {
            is HandoffStarted, is HandoffRequired, is HandoffAccepted, is HandoffFailed, is HandoffTimeout -> true
            else -> false
        }

    public val isTerminal: Boolean
        get() = when (this) {
            is ConversationEnded, is LiveAgentLeft, is HandoffFailed, is HandoffTimeout -> true
            else -> false
        }

    /** A human-readable reason/name where one applies, else null. */
    public val reason: String?
        get() = when (this) {
            is ConversationEnded -> reasonText
            is AgentLeft -> reasonText
            is LiveAgentLeft -> reasonText
            is HandoffFailed -> reasonText
            is LiveAgentJoined -> name
            is HandoffRequired -> reasonText
            else -> null
        }

    override fun equals(other: Any?): Boolean = this::class == other?.let { it::class } && when (this) {
        is ConversationEnded -> reasonText == (other as ConversationEnded).reasonText
        is AgentLeft -> reasonText == (other as AgentLeft).reasonText
        is LiveAgentJoined -> name == (other as LiveAgentJoined).name
        is LiveAgentLeft -> reasonText == (other as LiveAgentLeft).reasonText
        is QueueStatus -> position == (other as QueueStatus).position && displayMessage == other.displayMessage
        is HandoffRequired -> reasonText == (other as HandoffRequired).reasonText
        is HandoffFailed -> reasonText == (other as HandoffFailed).reasonText
        is ServerMessage -> text == (other as ServerMessage).text && level == other.level
        else -> true // payload-less objects
    }

    override fun hashCode(): Int = this::class.hashCode() * 31 + (reason?.hashCode() ?: 0)
}
