// Copyright PolyAI Limited

package ai.poly.messaging

import java.net.URI
import java.util.UUID

/**
 * Kind of attachment carried on an [AgentMessage]. Unknown wire values decode to
 * [UNKNOWN] (never crash).
 */
public enum class AttachmentContentType(public val raw: String) {
    IMAGE("ATTACHMENT_CONTENT_TYPE_IMAGE"),
    URL("ATTACHMENT_CONTENT_TYPE_URL"),
    UNKNOWN(""),
    ;

    public companion object {
        @JvmStatic
        public fun fromRaw(raw: String): AttachmentContentType =
            entries.firstOrNull { it.raw == raw } ?: UNKNOWN
    }
}

/**
 * An image or link-card attachment on an agent message. Filter by [contentType]
 * and drop [AttachmentContentType.UNKNOWN] before rendering.
 *
 * Arrives via the event stream; not consumer-constructible.
 */
public class Attachment internal constructor(
    @JvmField public val contentType: AttachmentContentType,
    @JvmField public val contentUrl: URI?,
    @JvmField public val title: String?,
    @JvmField public val previewImageUrl: URI?,
    @JvmField public val callToActionText: String?,
) {
    override fun equals(other: Any?): Boolean =
        other is Attachment &&
            contentType == other.contentType &&
            contentUrl == other.contentUrl &&
            title == other.title &&
            previewImageUrl == other.previewImageUrl &&
            callToActionText == other.callToActionText

    override fun hashCode(): Int {
        var r = contentType.hashCode()
        r = 31 * r + (contentUrl?.hashCode() ?: 0)
        r = 31 * r + (title?.hashCode() ?: 0)
        r = 31 * r + (previewImageUrl?.hashCode() ?: 0)
        r = 31 * r + (callToActionText?.hashCode() ?: 0)
        return r
    }

    override fun toString(): String = "Attachment(contentType=$contentType, contentUrl=$contentUrl)"
}

/**
 * A quick-reply suggestion rendered as a pill under the latest agent message.
 * Consumer-constructible (e.g. for previews/tests).
 *
 * Note: the `@JvmOverloads` ladder has defaults last (the defaulted `id` comes last).
 */
public class ResponseSuggestion @JvmOverloads constructor(
    @JvmField public val messageText: String,
    @JvmField public val payload: String? = null,
    @JvmField public val id: UUID = UUID.randomUUID(),
) {
    override fun equals(other: Any?): Boolean =
        other is ResponseSuggestion && id == other.id &&
            messageText == other.messageText && payload == other.payload

    override fun hashCode(): Int = (id.hashCode() * 31 + messageText.hashCode()) * 31 + (payload?.hashCode() ?: 0)
    override fun toString(): String = "ResponseSuggestion(messageText=$messageText)"
}

/**
 * A tap-to-dial call button on an agent message. [contactNumber] is dialed via a
 * `tel:` intent (digits and a leading `+` only).
 */
public class ChatCallAction @JvmOverloads constructor(
    @JvmField public val title: String,
    @JvmField public val contactNumber: String,
    @JvmField public val id: UUID = UUID.randomUUID(),
) {
    override fun equals(other: Any?): Boolean =
        other is ChatCallAction && id == other.id &&
            title == other.title && contactNumber == other.contactNumber

    override fun hashCode(): Int = (id.hashCode() * 31 + title.hashCode()) * 31 + contactNumber.hashCode()
    override fun toString(): String = "ChatCallAction(title=$title)"
}

/** Severity of a server-pushed system message. */
public enum class SystemMessageLevel(public val raw: String) {
    INFO("SYSTEM_MESSAGE_LEVEL_INFO"),
    WARNING("SYSTEM_MESSAGE_LEVEL_WARNING"),
    ERROR("SYSTEM_MESSAGE_LEVEL_ERROR"),
    ;

    public companion object {
        @JvmStatic
        public fun fromRaw(raw: String): SystemMessageLevel =
            entries.firstOrNull { it.raw == raw } ?: INFO
    }
}
