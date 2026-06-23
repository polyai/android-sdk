// Copyright PolyAI Limited

package ai.poly.messaging

import java.net.URI

/**
 * Strongly-typed payloads carried by [MessagingEvent]. They arrive via the event
 * stream and are not consumer-constructible (`internal constructor`).
 */

/** Server capabilities announced on `SESSION_START` (override the client defaults). */
public class SessionCapabilities internal constructor(
    @JvmField public val streaming: Boolean,
    @JvmField public val maxMessageSize: Int,
    @JvmField public val heartbeatIntervalSeconds: Int?,
    @JvmField public val maxReconnectAttempts: Int?,
)

public class SessionStartPayload internal constructor(
    @JvmField public val capabilities: SessionCapabilities,
)

public class SessionEndPayload internal constructor(
    @JvmField public val reason: String?,
)

public class UserMessageEchoPayload internal constructor(
    @JvmField public val messageId: String,
    @JvmField public val text: String,
)

public class AgentJoinedPayload internal constructor(
    @JvmField public val agentName: String?,
    @JvmField public val avatarUrl: URI?,
)

public class AgentMessagePayload internal constructor(
    @JvmField public val messageId: String,
    @JvmField public val text: String,
    @JvmField public val agentName: String?,
    @JvmField public val avatarUrl: URI?,
    @JvmField public val attachments: List<Attachment>,
    @JvmField public val responseSuggestions: List<ResponseSuggestion>,
    @JvmField public val chatCallActions: List<ChatCallAction>,
    @JvmField public val endConversation: Boolean = false,
)

public class AgentMessageChunkPayload internal constructor(
    @JvmField public val messageId: String,
    @JvmField public val text: String?,
    @JvmField public val chunkIndex: Int,
    @JvmField public val isComplete: Boolean,
    @JvmField public val attachments: List<Attachment>,
    @JvmField public val responseSuggestions: List<ResponseSuggestion>,
)

public class AgentLeftPayload internal constructor(
    @JvmField public val reason: String?,
)

public class LiveAgentJoinedPayload internal constructor(
    @JvmField public val agentId: String?,
    @JvmField public val agentName: String?,
    @JvmField public val avatarUrl: URI?,
)

public class LiveAgentTypingPayload internal constructor(
    @JvmField public val state: TypingState,
    @JvmField public val agentId: String? = null,
    @JvmField public val agentName: String? = null,
)

public class LiveAgentMessagePayload internal constructor(
    @JvmField public val messageId: String,
    @JvmField public val text: String,
    @JvmField public val agentId: String?,
    @JvmField public val agentName: String?,
    @JvmField public val avatarUrl: URI?,
    @JvmField public val attachments: List<Attachment>,
    @JvmField public val responseSuggestions: List<ResponseSuggestion>,
    @JvmField public val chatCallActions: List<ChatCallAction>,
)

public class LiveAgentLeftPayload internal constructor(
    @JvmField public val agentId: String?,
    @JvmField public val agentName: String?,
    @JvmField public val reason: String?,
)

public class SystemMessagePayload internal constructor(
    @JvmField public val message: String,
    @JvmField public val level: SystemMessageLevel,
)

public class ClientHandoffRequiredPayload internal constructor(
    @JvmField public val route: String?,
    @JvmField public val reason: String?,
    @JvmField public val queueName: String?,
)

public class HandoffQueueStatusPayload internal constructor(
    @JvmField public val position: Int?,
    @JvmField public val estimatedWaitSeconds: Int?,
    @JvmField public val queueName: String?,
    @JvmField public val displayMessage: String?,
)

public class HandoffAcceptedPayload internal constructor(
    @JvmField public val queueName: String?,
)

public class HandoffFailedPayload internal constructor(
    @JvmField public val reason: String?,
)

public class HandoffTimeoutPayload internal constructor(
    @JvmField public val reason: String?,
)
