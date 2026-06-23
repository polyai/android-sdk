// Copyright PolyAI Limited

package ai.poly.messaging.internal.wire

import ai.poly.messaging.Attachment
import ai.poly.messaging.AttachmentContentType
import ai.poly.messaging.ChatCallAction
import ai.poly.messaging.Envelope
import ai.poly.messaging.EventMetadata
import ai.poly.messaging.MessagingEvent
import ai.poly.messaging.PolyLogger
import ai.poly.messaging.ResponseSuggestion
import ai.poly.messaging.SessionCapabilities
import ai.poly.messaging.SessionStartPayload
import ai.poly.messaging.SessionEndPayload
import ai.poly.messaging.SystemMessageLevel
import ai.poly.messaging.TypingState
import ai.poly.messaging.AgentJoinedPayload
import ai.poly.messaging.AgentLeftPayload
import ai.poly.messaging.AgentMessageChunkPayload
import ai.poly.messaging.AgentMessagePayload
import ai.poly.messaging.ClientHandoffRequiredPayload
import ai.poly.messaging.HandoffAcceptedPayload
import ai.poly.messaging.HandoffFailedPayload
import ai.poly.messaging.HandoffQueueStatusPayload
import ai.poly.messaging.HandoffTimeoutPayload
import ai.poly.messaging.LiveAgentJoinedPayload
import ai.poly.messaging.LiveAgentLeftPayload
import ai.poly.messaging.LiveAgentMessagePayload
import ai.poly.messaging.LiveAgentTypingPayload
import ai.poly.messaging.SystemMessagePayload
import ai.poly.messaging.UserMessageEchoPayload
import ai.poly.messaging.internal.helpers.d
import ai.poly.messaging.internal.helpers.i
import ai.poly.messaging.internal.helpers.w
import org.json.JSONArray
import org.json.JSONObject

/**
 * Decodes inbound wire frames into [MessagingEvent]s:
 * single frames, `EVENT_TYPE_EVENT_BATCH` (reshape oneof payloads, sorted by sequence nulls-last),
 * the canonical server field names only, and the non-heartbeat id+timestamp guard. Spec-defined-
 * but-unsent events (read receipts, CSAT) and unknown types are dropped, never crashed.
 */
internal class WireDecoder(
    private val now: () -> Long = System::currentTimeMillis,
    private val logger: PolyLogger? = null,
) {

    /** True if the raw frame is an EVENT_BATCH (so the transport can route it to batchEvents). */
    fun isBatchFrame(text: String): Boolean {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return false
        return WireEventType.fromRaw(obj.stringOrNull("type")) == WireEventType.EVENT_BATCH
    }

    fun decode(text: String): List<MessagingEvent> {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: run {
            logger?.w("Failed to parse JSON frame")
            return emptyList()
        }
        if (WireEventType.fromRaw(obj.stringOrNull("type")) == WireEventType.EVENT_BATCH) {
            return decodeBatch(obj)
        }
        return listOfNotNull(decodeSingle(obj))
    }

    private fun decodeBatch(obj: JSONObject): List<MessagingEvent> {
        // `array("events")` is all-or-nothing: a missing array OR any non-object element drops the
        // WHOLE batch with this exact log (it does NOT salvage the valid events).
        val events = obj.objOrNull("payload")?.arrayOrNull("events")?.objectsStrictOrNull() ?: run {
            logger?.w("Malformed event batch — dropped")
            return emptyList()
        }
        // Log an INFO batch summary (count + comma-joined types) before decoding.
        logger?.i("Batch contents", mapOf("count" to events.size.toString(), "types" to events.mapNotNull { it.stringOrNull("type") }.joinToString(",")))
        // Log a WARN per event that decodeSingle drops (when its type is a present string).
        val decoded = buildList {
            for (raw in events) {
                val ev = decodeSingle(reshapeBatchEvent(raw))
                if (ev != null) add(ev) else raw.stringOrNull("type")?.let { logger?.w("Batch event dropped during decode", mapOf("wireType" to it)) }
            }
        }
        // Stable sort by sequence, nulls-last.
        return decoded.sortedWith(compareBy(nullsLast()) { it.envelope?.sequence })
    }

    /** oneof wire form: an event without an object `payload` carries it under a type-derived key
     *  (EVENT_TYPE_POLY_AGENT_MESSAGE → "poly_agent_message"). */
    private fun reshapeBatchEvent(obj: JSONObject): JSONObject {
        if (obj.objOrNull("payload") != null) return obj
        val type = obj.stringOrNull("type") ?: return obj
        val key = type.removePrefix("EVENT_TYPE_").lowercase()
        // Log a WARN when neither a direct nor a oneof object payload is present (then emit empty).
        val nested = obj.objOrNull(key) ?: run {
            logger?.w("Batch event payload not found — emitting empty", mapOf("wireType" to type))
            return obj
        }
        return JSONObject(obj.toString()).put("payload", nested)
    }

    private fun decodeSingle(obj: JSONObject): MessagingEvent? {
        val type = WireEventType.fromRaw(obj.stringOrNull("type")) ?: run {
            // Log only when a non-empty type string is present; a missing/empty type drops silently.
            obj.stringOrNull("type")?.let { logger?.w("Unknown event type — dropped", mapOf("wireType" to it)) }
            return null
        }
        // A non-heartbeat event missing id or timestamp is malformed and dropped.
        if (type != WireEventType.HEARTBEAT && (obj.stringOrNull("id") == null || obj.stringOrNull("timestamp") == null)) {
            return null
        }
        val env = envelope(obj)
        val p = obj.objOrNull("payload") ?: JSONObject()

        return when (type) {
            WireEventType.HEARTBEAT -> MessagingEvent.Heartbeat(env)
            WireEventType.SESSION_START -> MessagingEvent.SessionStart(env, sessionStart(p))
            WireEventType.SESSION_END -> MessagingEvent.SessionEnd(env, SessionEndPayload(p.firstString("reason")))
            WireEventType.SESSION_IDLE_WARNING -> MessagingEvent.SessionIdleWarning(env)
            WireEventType.USER_MESSAGE -> MessagingEvent.UserMessage(
                env, UserMessageEchoPayload(p.firstString("message_id") ?: "", p.firstString("text") ?: ""),
            )
            WireEventType.USER_TYPING -> MessagingEvent.UserTyping(env)
            WireEventType.USER_END_SESSION -> MessagingEvent.UserEndSession(env)
            WireEventType.REQUEST_POLY_AGENT_JOIN -> MessagingEvent.RequestPolyAgentJoin(env)
            WireEventType.POLY_AGENT_JOINED -> MessagingEvent.AgentJoined(env, AgentJoinedPayload(polyAgentName(p), polyJoinedAvatar(p)))
            WireEventType.POLY_AGENT_THINKING -> MessagingEvent.AgentThinking(env)
            WireEventType.POLY_AGENT_MESSAGE -> MessagingEvent.AgentMessage(env, agentMessage(p))
            WireEventType.POLY_AGENT_MESSAGE_CHUNK -> MessagingEvent.AgentMessageChunk(env, agentChunk(p))
            WireEventType.POLY_AGENT_LEFT -> MessagingEvent.AgentLeft(env, AgentLeftPayload(p.firstString("reason")))
            WireEventType.POLY_AGENT_TRIGGERED_HANDOFF -> MessagingEvent.AgentTriggeredHandoff(env)
            WireEventType.LIVE_AGENT_JOINED -> MessagingEvent.LiveAgentJoined(
                env, LiveAgentJoinedPayload(liveAgentId(p), liveAgentName(p), liveAvatarUrl(p)),
            )
            WireEventType.LIVE_AGENT_TYPING -> MessagingEvent.LiveAgentTyping(
                // State defaults to STARTED when absent; only the 'state' key is read.
                env, LiveAgentTypingPayload(
                    TypingState.fromRaw(p.firstString("state") ?: "") ?: TypingState.STARTED,
                    agentId = p.firstString("agent_id"),
                    agentName = p.firstString("agent_name"),
                ),
            )
            WireEventType.LIVE_AGENT_MESSAGE -> MessagingEvent.LiveAgentMessage(env, liveAgentMessage(p))
            WireEventType.LIVE_AGENT_LEFT -> MessagingEvent.LiveAgentLeft(
                env, LiveAgentLeftPayload(p.firstString("agent_id"), p.firstString("agent_name"), p.firstString("reason")),
            )
            WireEventType.SYSTEM_MESSAGE -> MessagingEvent.SystemMessage(
                env, SystemMessagePayload(p.firstString("message") ?: "", SystemMessageLevel.fromRaw(p.firstString("level") ?: "")),
            )
            WireEventType.CLIENT_HANDOFF_REQUIRED -> MessagingEvent.ClientHandoffRequired(
                env, ClientHandoffRequiredPayload(p.firstString("route"), p.firstString("reason"), p.firstString("queue_name")),
            )
            WireEventType.HANDOFF_QUEUE_STATUS -> MessagingEvent.HandoffQueueStatus(
                env, HandoffQueueStatusPayload(
                    p.firstInt("position_in_queue", "position"),
                    p.firstInt("estimated_wait_seconds"),
                    p.firstString("queue_name"),
                    p.firstString("display_message"),
                ),
            )
            WireEventType.HANDOFF_ACCEPTED -> MessagingEvent.HandoffAccepted(env, HandoffAcceptedPayload(p.firstString("queue_name")))
            WireEventType.HANDOFF_FAILED -> MessagingEvent.HandoffFailed(env, HandoffFailedPayload(p.firstString("reason")))
            WireEventType.HANDOFF_TIMEOUT -> MessagingEvent.HandoffTimeout(env, HandoffTimeoutPayload(p.firstString("reason")))
            WireEventType.EVENT_BATCH -> null // handled by decodeBatch
            // Spec-defined-but-unsent: decoded-and-dropped. Logged at DEBUG ('TBD event received').
            WireEventType.USER_RECEIVED_MESSAGE, WireEventType.USER_READ_MESSAGE,
            WireEventType.POLY_AGENT_RECEIVED_MESSAGE, WireEventType.POLY_AGENT_READ_MESSAGE,
            WireEventType.LIVE_AGENT_RECEIVED_MESSAGE, WireEventType.LIVE_AGENT_READ_MESSAGE,
            WireEventType.SHOW_CSAT_REQUEST, WireEventType.CSAT_RESPONSE,
            -> { logger?.d("TBD event received", mapOf("type" to type.raw)); null }
        }
    }

    private fun envelope(obj: JSONObject): Envelope {
        // Metadata is null ONLY when the `metadata` dict is absent; when the dict IS present it ALWAYS
        // yields EventMetadata(custom:) with custom possibly null. So the metadata object's presence
        // tracks the `metadata` dict, NOT whether `custom` parsed.
        val metaObj = obj.objOrNull("metadata")
        val custom: Map<String, String>? = metaObj?.objOrNull("custom")?.let { c ->
            // `custom` is all-or-nothing: if ANY value is not a String, the whole map becomes null —
            // it does NOT yield a partial map.
            val keys = c.keys().asSequence().toList()
            if (keys.all { c.stringOrNull(it) != null }) {
                buildMap<String, String> { keys.forEach { k -> put(k, c.getString(k)) } }
            } else {
                null
            }
        }
        return Envelope(
            id = obj.firstString("id") ?: "",
            sequence = obj.firstInt("sequence"),
            timestamp = parseIso8601(obj.stringOrNull("timestamp"), now()),
            metadata = metaObj?.let { EventMetadata(custom) },
        )
    }

    private fun sessionStart(p: JSONObject): SessionStartPayload {
        // Reads ONLY the nested `capabilities` object (empty if absent) and the canonical keys.
        val caps = p.objOrNull("capabilities") ?: JSONObject()
        return SessionStartPayload(
            SessionCapabilities(
                streaming = caps.boolOr("streaming", false),
                maxMessageSize = caps.firstInt("max_message_size_bytes") ?: (1 shl 20),
                heartbeatIntervalSeconds = caps.firstInt("heartbeat_interval_seconds"),
                maxReconnectAttempts = caps.firstInt("max_reconnect_attempts"),
            ),
        )
    }

    private fun agentMessage(p: JSONObject) = AgentMessagePayload(
        messageId = p.firstString("message_id") ?: "",
        text = p.firstString("text") ?: "",
        agentName = polyAgentName(p),
        avatarUrl = polyMessageAvatar(p),
        attachments = attachments(p),
        responseSuggestions = suggestions(p),
        chatCallActions = callActions(p),
        endConversation = p.boolOr("end_conversation", false),
    )

    private fun liveAgentMessage(p: JSONObject) = LiveAgentMessagePayload(
        messageId = p.firstString("message_id") ?: "",
        text = p.firstString("text") ?: "",
        // A live-agent message reads ONLY the top-level keys — the nested-`agent` object fallback
        // (liveAgentId/liveAgentName/liveAvatarUrl) exists only for LIVE_AGENT_JOINED, not for a message.
        agentId = p.firstString("agent_id"),
        agentName = p.firstString("agent_name"),
        avatarUrl = p.uriOrNull("avatar_url"),
        attachments = attachments(p),
        responseSuggestions = suggestions(p),
        chatCallActions = callActions(p),
    )

    private fun agentChunk(p: JSONObject) = AgentMessageChunkPayload(
        messageId = p.firstString("message_id") ?: "",
        text = p.stringOrNull("text"),
        chunkIndex = p.firstInt("chunk_index") ?: 0,
        isComplete = p.boolOr("is_complete", false),
        attachments = attachments(p),
        responseSuggestions = suggestions(p),
    )

    private fun polyAgentName(p: JSONObject): String? = p.firstString("agent_name")
    // POLY_AGENT_JOINED reads agent_avatar_url ?: avatar_url; POLY_AGENT_MESSAGE reads avatar_url ONLY.
    private fun polyJoinedAvatar(p: JSONObject) = p.uriOrNull("agent_avatar_url") ?: p.uriOrNull("avatar_url")
    private fun polyMessageAvatar(p: JSONObject) = p.uriOrNull("avatar_url")

    // Live-agent name/avatar/id: avatar_url (NOT agent_avatar_url) with a nested `agent` object fallback.
    private fun liveAgentId(p: JSONObject): String? = p.firstString("agent_id") ?: p.objOrNull("agent")?.firstString("id")
    private fun liveAgentName(p: JSONObject): String? = p.firstString("agent_name") ?: p.objOrNull("agent")?.firstString("name")
    private fun liveAvatarUrl(p: JSONObject) =
        p.uriOrNull("avatar_url") ?: p.objOrNull("agent")?.uriOrNull("avatar_url")

    // All-or-nothing: a mixed array (any non-object element) decodes to null → [].
    // objectsStrictOrNull() returns null in that case, so a single bad element drops the whole list.
    private fun attachments(p: JSONObject): List<Attachment> =
        p.arrayOrNull("attachments")?.objectsStrictOrNull()?.map { a ->
            Attachment(
                contentType = AttachmentContentType.fromRaw(a.firstString("content_type") ?: ""),
                contentUrl = a.uriOrNull("content_url"),
                title = a.stringOrNull("title"),
                previewImageUrl = a.uriOrNull("preview_image_url"),
                callToActionText = a.firstString("call_to_action_text"),
            )
        } ?: emptyList()

    private fun suggestions(p: JSONObject): List<ResponseSuggestion> =
        p.arrayOrNull("response_suggestions")?.objectsStrictOrNull()?.map { s ->
            ResponseSuggestion(messageText = s.firstString("message_text") ?: "", payload = s.stringOrNull("payload"))
        } ?: emptyList()

    private fun callActions(p: JSONObject): List<ChatCallAction> =
        p.arrayOrNull("chat_call_actions")?.objectsStrictOrNull()?.map { c ->
            // Keep every action with empty-string defaults — do NOT drop on a missing number.
            ChatCallAction(title = c.firstString("title") ?: "", contactNumber = c.firstString("contact_number") ?: "")
        } ?: emptyList()
}
