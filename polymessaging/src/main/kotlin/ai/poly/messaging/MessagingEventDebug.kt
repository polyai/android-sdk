// Copyright PolyAI Limited

package ai.poly.messaging

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One-line and multi-line debug renderings of a [MessagingEvent]
 * (`debugSummary` / `debugDetail`). Diagnostic only —
 * not part of the wire protocol.
 */
public val MessagingEvent.debugSummary: String
    get() = when (this) {
        is MessagingEvent.Connected -> "Connected"
        is MessagingEvent.Disconnected -> "Disconnected: " + (error?.message ?: "clean")
        is MessagingEvent.Reconnecting -> "Reconnecting (attempt $attempt)"
        is MessagingEvent.SessionStart -> "Session started"
        is MessagingEvent.SessionEnd -> "Session ended"
        is MessagingEvent.SessionIdleWarning -> "Session idle warning"
        is MessagingEvent.UserMessage -> "User: " + payload.text.take(40)
        is MessagingEvent.UserTyping -> "User typing"
        is MessagingEvent.UserEndSession -> "User ended session"
        is MessagingEvent.RequestPolyAgentJoin -> "Request agent join"
        is MessagingEvent.MessagePending -> "Message pending [" + draftId.take(8) + "]"
        is MessagingEvent.MessageConfirmed -> "Message confirmed [" + draftId.take(8) + "]"
        is MessagingEvent.MessageFailed -> "Message failed [" + draftId.take(8) + "]"
        is MessagingEvent.AgentThinking -> "Agent thinking"
        is MessagingEvent.AgentMessage -> "Agent: " + payload.text.take(40)
        is MessagingEvent.AgentMessageChunk -> "Chunk [" + payload.messageId.take(8) + "]"
        is MessagingEvent.AgentJoined -> "Agent joined: " + (payload.agentName ?: "")
        is MessagingEvent.AgentLeft -> "Agent left"
        is MessagingEvent.AgentTriggeredHandoff -> "Agent triggered handoff"
        is MessagingEvent.LiveAgentJoined -> "Live agent joined: " + (payload.agentName ?: "")
        is MessagingEvent.LiveAgentTyping -> "Live agent typing"
        is MessagingEvent.LiveAgentMessage -> "Live agent: " + payload.text.take(40)
        is MessagingEvent.LiveAgentLeft -> "Live agent left"
        is MessagingEvent.SystemMessage -> "System: " + payload.message.take(40)
        is MessagingEvent.Heartbeat -> "Heartbeat"
        is MessagingEvent.ClientHandoffRequired -> "Handoff required: " + payload.reason
        is MessagingEvent.HandoffQueueStatus -> "Queue position: " + (payload.position ?: 0)
        is MessagingEvent.HandoffAccepted -> "Handoff accepted"
        is MessagingEvent.HandoffFailed -> "Handoff failed: " + (payload.reason ?: "unknown")
        is MessagingEvent.HandoffTimeout -> "Handoff timeout"
    }

public val MessagingEvent.debugDetail: String
    get() {
        val lines = mutableListOf<String>()
        envelope?.let { env ->
            if (env.id.isNotEmpty()) lines += "id: ${env.id}"
            env.sequence?.let { lines += "sequence: $it" }
            lines += "timestamp: ${formatDebugTime(env.timestamp)}"
            env.metadata?.custom?.takeIf { it.isNotEmpty() }?.forEach { (k, v) -> lines += "meta.$k: $v" }
        }
        when (this) {
            is MessagingEvent.SessionStart -> {
                lines += "streaming: ${payload.capabilities.streaming}"
                lines += "maxMessageSize: ${payload.capabilities.maxMessageSize}"
                payload.capabilities.heartbeatIntervalSeconds?.let { lines += "heartbeatInterval: ${it}s" }
                payload.capabilities.maxReconnectAttempts?.let { lines += "maxReconnects: $it" }
            }
            is MessagingEvent.SessionEnd -> lines += "reason: ${payload.reason ?: "none"}"
            is MessagingEvent.AgentMessage -> {
                lines += "messageId: ${payload.messageId}"
                lines += "text: ${payload.text.take(100)}"
                payload.agentName?.let { lines += "agentName: $it" }
                if (payload.attachments.isNotEmpty()) lines += "attachments: ${payload.attachments.size}"
                if (payload.responseSuggestions.isNotEmpty()) {
                    lines += "suggestions: ${payload.responseSuggestions.joinToString(", ") { it.messageText }}"
                }
                lines += "endConversation: ${payload.endConversation}"
            }
            is MessagingEvent.AgentMessageChunk -> {
                lines += "messageId: ${payload.messageId}"
                lines += "chunkIndex: ${payload.chunkIndex}"
                lines += "isComplete: ${payload.isComplete}"
                payload.text?.let { lines += "text: ${it.take(80)}" }
            }
            is MessagingEvent.LiveAgentMessage -> {
                lines += "messageId: ${payload.messageId}"
                lines += "text: ${payload.text.take(100)}"
            }
            is MessagingEvent.SystemMessage -> {
                lines += "level: ${payload.level}"
                lines += "message: ${payload.message}"
            }
            is MessagingEvent.Connected -> lines += "status: connected"
            is MessagingEvent.Disconnected -> lines += "error: ${error?.message ?: "none"}"
            is MessagingEvent.Reconnecting -> lines += "attempt: $attempt"
            else -> {}
        }
        return lines.joinToString("\n")
    }

// Renders the timestamp as local "HH:mm:ss.SSS" (default time zone).
private fun formatDebugTime(epochMillis: Long): String =
    SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(epochMillis))
