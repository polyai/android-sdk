// Copyright PolyAI Limited

package ai.poly.messaging.internal.services

import ai.poly.messaging.AgentMessagePayload
import ai.poly.messaging.Envelope
import ai.poly.messaging.MessagingEvent
import ai.poly.messaging.OutgoingEvent
import ai.poly.messaging.SessionEndPayload
import ai.poly.messaging.internal.helpers.Clock
import java.util.UUID

/**
 * The optimistic-send + delivery-tracking + dedup + streaming-reassembly state machine.
 * Pure (no coroutines/IO) so it is deterministically unit-testable. The [Coordinator]
 * drives it and performs the actual transport IO.
 */
internal class ChatService(private val clock: Clock = Clock.SYSTEM) {

    /** Result of preparing a user send: the optimistic events to emit + the frame to send. */
    internal data class Prepared(
        val draftId: String,
        val events: List<MessagingEvent>,
        val outgoing: OutgoingEvent?,
    )

    private data class Pending(val draftId: String, val clientEventId: String, val text: String)

    private val pending = LinkedHashMap<String, Pending>() // draftId -> pending
    private val seenEventIds = HashSet<String>()
    private val streamBuffers = HashMap<String, StreamingBuffer>()

    var maxMessageSize: Int = 1 shl 20 // 1 MiB; overridden by SESSION_START capabilities
    var chatEnded: Boolean = false

    /** Trim, size-check, mint a draft, and produce the optimistic [Prepared]. */
    fun prepareUserMessage(text: String): Prepared {
        val trimmed = text.trim()
        val draftId = UUID.randomUUID().toString()
        // Empty / after-end: silently drop with no event (UIs gate the send button).
        if (trimmed.isEmpty() || chatEnded) {
            return Prepared(draftId, emptyList(), outgoing = null)
        }
        // Oversized: fail with an event (payloadTooLarge → messageFailed).
        // The size check is guarded on maxMessageSize > 0, so a server cap of 0 disables enforcement.
        if (maxMessageSize > 0 && trimmed.toByteArray(Charsets.UTF_8).size > maxMessageSize) {
            return Prepared(draftId, listOf(MessagingEvent.MessageFailed(draftId)), outgoing = null)
        }
        val clientEventId = UUID.randomUUID().toString()
        pending[draftId] = Pending(draftId, clientEventId, trimmed)
        return Prepared(
            draftId = draftId,
            events = listOf(MessagingEvent.MessagePending(draftId, trimmed)),
            outgoing = OutgoingEvent.UserMessage(trimmed, mapOf("local_id" to clientEventId)),
        )
    }

    /** Outgoing frames for any still-pending messages (re-sent on reconnect/open). */
    fun pendingResends(): List<OutgoingEvent> =
        pending.values.map { OutgoingEvent.UserMessage(it.text, mapOf("local_id" to it.clientEventId)) }

    /** The outgoing frame for one still-pending draft (the per-message retry ladder), or null if confirmed. */
    fun pendingResend(draftId: String): OutgoingEvent? =
        pending[draftId]?.let { OutgoingEvent.UserMessage(it.text, mapOf("local_id" to it.clientEventId)) }

    fun isPending(draftId: String): Boolean = pending.containsKey(draftId)

    /** Fail a stuck pending message (retry ladder exhausted) → MessageFailed, or null if already confirmed. */
    fun failPending(draftId: String): MessagingEvent? =
        if (pending.remove(draftId) != null) MessagingEvent.MessageFailed(draftId) else null

    /** Finalize any open streaming buffers as assembled agent messages (graceful 1000-close flush). */
    fun flushStreams(): List<MessagingEvent> {
        val out = streamBuffers.values.mapNotNull { buffer ->
            val env = buffer.lastEnv ?: return@mapNotNull null
            val payload = buffer.assemble()
            if (payload.text.isNotEmpty() || payload.attachments.isNotEmpty() || payload.responseSuggestions.isNotEmpty()) {
                MessagingEvent.AgentMessage(env, payload)
            } else {
                null
            }
        }
        streamBuffers.clear()
        return out
    }

    /**
     * Process one inbound decoded event; returns the (possibly transformed) events to emit
     * downstream. Handles global dedup, echo→confirm, and streaming chunk reassembly.
     */
    fun handleInbound(event: MessagingEvent): List<MessagingEvent> {
        // Global dedup: any event with a non-empty id + non-null sequence is deduped.
        event.envelope?.let { env ->
            if (env.id.isNotEmpty() && env.sequence != null && !seenEventIds.add(env.id)) {
                return emptyList()
            }
        }
        // Clear a latched chatEnded on ANY inbound except heartbeat / sessionEnd / systemMessage /
        // liveAgentLeft — so an incoming agent/live-agent message un-latches a prior end.
        when (event) {
            is MessagingEvent.Heartbeat, is MessagingEvent.SessionEnd,
            is MessagingEvent.SystemMessage, is MessagingEvent.LiveAgentLeft,
            -> Unit
            else -> chatEnded = false
        }
        return when (event) {
            is MessagingEvent.UserMessage -> handleEcho(event)
            is MessagingEvent.AgentMessageChunk -> handleChunk(event)
            is MessagingEvent.AgentMessage -> handleAgentMessage(event)
            // Emit the SessionEnd event FIRST (so the end UI renders), THEN flush any
            // in-flight streaming buffer as a bubble — and only if not already ended.
            is MessagingEvent.SessionEnd -> {
                if (chatEnded) {
                    listOf(event)
                } else {
                    val out = mutableListOf<MessagingEvent>(event)
                    out += flushStreams()
                    chatEnded = true
                    out
                }
            }
            // A live agent leaving is NOT a chat-ended signal — just pass it through.
            is MessagingEvent.LiveAgentLeft -> listOf(event)
            is MessagingEvent.SessionStart -> { chatEnded = false; applyCapabilities(event); listOf(event) }
            else -> listOf(event)
        }
    }

    private fun handleAgentMessage(event: MessagingEvent.AgentMessage): List<MessagingEvent> {
        val p = event.payload
        val out = mutableListOf<MessagingEvent>()
        // hasContent gate: a fully-empty agent message is dropped (no bubble).
        val hasContent = p.text.isNotEmpty() || p.attachments.isNotEmpty() ||
            p.responseSuggestions.isNotEmpty() || p.chatCallActions.isNotEmpty()
        if (hasContent) out += event
        // end_conversation: synthesize handleSessionEnd → emit SESSION_END FIRST, then flush.
        if (p.endConversation && !chatEnded) {
            out += MessagingEvent.SessionEnd(event.env, SessionEndPayload("agent_ended"))
            out += flushStreams()
            chatEnded = true
        }
        return out
    }

    fun resetChat(isResume: Boolean) {
        pending.clear()
        streamBuffers.clear()
        chatEnded = false
        if (!isResume) seenEventIds.clear() // preserve seen ids on resume so replays aren't re-shown
    }

    private fun applyCapabilities(event: MessagingEvent.SessionStart) {
        // Apply the cap unconditionally (a 0/negative value disables the size guard at send time).
        maxMessageSize = event.payload.capabilities.maxMessageSize
    }

    private fun handleEcho(event: MessagingEvent.UserMessage): List<MessagingEvent> {
        val localId = event.env.metadata?.custom?.get("local_id")
        // When a local_id is present, match ONLY by clientEventId (no text fallback);
        // otherwise (a genuine replayed message) match by text.
        val match = if (localId != null) {
            pending.values.firstOrNull { it.clientEventId == localId }
        } else {
            pending.values.firstOrNull { it.text == event.payload.text }
        }
        return if (match != null) {
            pending.remove(match.draftId)
            listOf(MessagingEvent.MessageConfirmed(match.draftId, event.payload.messageId))
        } else {
            listOf(event) // genuine replayed user message (session resume)
        }
    }

    private fun handleChunk(event: MessagingEvent.AgentMessageChunk): List<MessagingEvent> {
        val msgId = event.payload.messageId
        // A chunk for a new messageId finalizes any open buffer first (abandoned-stream recovery).
        val emitted = mutableListOf<MessagingEvent>()
        streamBuffers.keys.filter { it != msgId }.toList().forEach { other ->
            streamBuffers.remove(other)?.let { buf ->
                val p = buf.assemble()
                // The abandoned-stream finalize is gated on hasContent — don't emit an empty bubble.
                if (p.text.isNotEmpty() || p.attachments.isNotEmpty() || p.responseSuggestions.isNotEmpty()) {
                    emitted += MessagingEvent.AgentMessage(buf.lastEnv ?: event.env, p)
                }
            }
        }
        val buffer = streamBuffers.getOrPut(msgId) { StreamingBuffer(msgId) }
        buffer.add(event.env, event.payload)
        emitted += event // pass the chunk through (keeps the typing indicator alive)
        if (event.payload.isComplete) {
            streamBuffers.remove(msgId)
            val payload = buffer.assemble()
            // Emit if it carries ANY content — text, attachments, OR suggestions (a suggestions-only
            // final chunk still produces a bubble, matching the hasContent gate).
            if (payload.text.isNotEmpty() || payload.attachments.isNotEmpty() || payload.responseSuggestions.isNotEmpty()) {
                emitted += MessagingEvent.AgentMessage(buffer.lastEnv ?: event.env, payload)
            }
        }
        return emitted
    }

    private class StreamingBuffer(val messageId: String) {
        private data class ChunkEntry(
            val chunkIndex: Int,
            val text: String?,
            val attachments: List<ai.poly.messaging.Attachment>,
            val responseSuggestions: List<ai.poly.messaging.ResponseSuggestion>,
        )

        private val chunks = ArrayList<ChunkEntry>()
        // Attachments are deduped at APPEND time (arrival order) against a PERSISTENT set, keeping the
        // first-ARRIVING attachment per content URL (not the lowest-chunkIndex one); a null URL is always
        // kept (fresh UUID key). finalize() then only orders by chunkIndex.
        private val seenAttachmentUrls = HashSet<String>()
        var lastEnv: Envelope? = null

        fun add(env: Envelope, p: ai.poly.messaging.AgentMessageChunkPayload) {
            lastEnv = env
            val deduped = ArrayList<ai.poly.messaging.Attachment>(p.attachments.size)
            for (a in p.attachments) {
                val key = a.contentUrl?.toString() ?: UUID.randomUUID().toString()
                if (seenAttachmentUrls.add(key)) deduped += a
            }
            chunks += ChunkEntry(p.chunkIndex, p.text, deduped, p.responseSuggestions)
        }

        fun assemble(): AgentMessagePayload {
            val ordered = chunks.sortedBy { it.chunkIndex }
            val text = ordered.mapNotNull { it.text }.filter { it.isNotEmpty() }.joinToString(" ")
            // Attachments were already deduped at arrival; finalize only orders them by chunkIndex.
            val attachments = ordered.flatMap { it.attachments }
            // Suggestions from the LAST chunk that actually carried any (an empty final chunk must not
            // wipe suggestions delivered earlier in the stream).
            val suggestions = ordered.lastOrNull { it.responseSuggestions.isNotEmpty() }?.responseSuggestions ?: emptyList()
            return AgentMessagePayload(
                messageId = messageId,
                text = text,
                agentName = null,
                avatarUrl = null,
                attachments = attachments,
                responseSuggestions = suggestions,
                chatCallActions = emptyList(),
            )
        }
    }
}
