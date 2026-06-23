// Copyright PolyAI Limited

package ai.poly.messaging.internal.wire

import ai.poly.messaging.OutgoingEvent
import org.json.JSONObject

/**
 * Encodes the (small, fixed) set of outgoing events to wire frames.
 * Frame shape: `{"type": <EVENT_TYPE_*>, "payload": {...}, "metadata": {...}}`.
 * Only these five types are encodable; `UserEndConversation` and `UserLeft` both map to
 * `EVENT_TYPE_USER_END_SESSION`.
 */
internal object WireEncoder {

    fun encode(event: OutgoingEvent): String {
        val type: WireEventType
        val payload = JSONObject()
        val metadata = JSONObject()
        var hasMetadata = false // the metadata frame is emitted when metadata is non-null (even if empty)

        when (event) {
            is OutgoingEvent.UserMessage -> {
                type = WireEventType.USER_MESSAGE
                payload.put("text", event.text)
                event.metadata?.let { m -> hasMetadata = true; m.forEach { (k, v) -> metadata.put(k, v) } }
            }
            is OutgoingEvent.UserTyping -> {
                type = WireEventType.USER_TYPING
                payload.put("state", event.state.raw)
            }
            is OutgoingEvent.RequestPolyAgentJoin -> type = WireEventType.REQUEST_POLY_AGENT_JOIN
            is OutgoingEvent.Heartbeat -> type = WireEventType.HEARTBEAT
            is OutgoingEvent.UserEndConversation -> type = WireEventType.USER_END_SESSION
            is OutgoingEvent.UserLeft -> type = WireEventType.USER_END_SESSION
        }

        val frame = JSONObject()
        frame.put("type", type.raw)
        frame.put("payload", payload) // always a (possibly empty) object per the server schema
        if (hasMetadata) frame.put("metadata", JSONObject().put("custom", metadata))
        return frame.toString()
    }
}
