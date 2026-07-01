// Copyright PolyAI Limited

package ai.poly.messaging

/**
 * The unified SDK error type. Extends [Exception] so it flows through both Kotlin
 * `suspend` throws and the Java `onError(Throwable)` callback path.
 *
 * - [message] / `toString()` — a short, user-facing string safe to show in UI.
 * - [debugDescription] — the structural form for logs and crash reports.
 *
 * Classify without `instanceof` via the
 * `isXxx` helpers, or pattern-match the nested types.
 */
public sealed class PolyError(message: String) : Exception(message) {

    /** Authentication failures. */
    public sealed class Auth(message: String) : PolyError(message) {
        public object TokenAcquisitionFailed :
            Auth("Couldn't get an access token. Check your connection and try again.")

        public object Unauthorized :
            Auth("Your API key was rejected. Please contact support.")
    }

    /** Session lifecycle failures. */
    public sealed class Session(message: String) : PolyError(message) {
        public class SessionCreationFailed(@JvmField public val code: SessionErrorCode) :
            Session("Couldn't start a session: ${code.raw}.") {
            // Value equality over associated values for payload cases.
            override fun equals(other: Any?): Boolean = this === other || (other is SessionCreationFailed && code == other.code)
            override fun hashCode(): Int = code.hashCode()
        }

        public class UnexpectedDisconnect(
            @JvmField public val code: Int,
            @JvmField public val reason: String,
        ) : Session(
            if (reason.isEmpty()) "Disconnected unexpectedly (code $code)."
            else "Disconnected unexpectedly (code $code): $reason",
        ) {
            override fun equals(other: Any?): Boolean = this === other || (other is UnexpectedDisconnect && code == other.code && reason == other.reason)
            override fun hashCode(): Int = 31 * code + reason.hashCode()
        }

        public object MaxReconnectAttemptsExceeded :
            Session("Connection lost — please try reconnecting.")

        public object SessionExpired :
            Session("Your session timed out. Start a new chat to continue.")

        public class SessionEnded(@JvmField public val reason: String?) : Session(
            if (!reason.isNullOrEmpty()) "Conversation ended: $reason" else "Conversation ended.",
        ) {
            override fun equals(other: Any?): Boolean = this === other || (other is SessionEnded && reason == other.reason)
            override fun hashCode(): Int = reason?.hashCode() ?: 0
        }
    }

    /** Outbound message failures. */
    public sealed class Message(message: String) : PolyError(message) {
        public class DeliveryFailed(@JvmField public val draftId: String) :
            Message("Couldn't deliver your message. Please try again.") {
            override fun equals(other: Any?): Boolean = this === other || (other is DeliveryFailed && draftId == other.draftId)
            override fun hashCode(): Int = draftId.hashCode()
        }

        public class PayloadTooLarge(@JvmField public val maxBytes: Int) :
            Message("Message is too large (max ${maxBytes / 1024} KB).") {
            override fun equals(other: Any?): Boolean = this === other || (other is PayloadTooLarge && maxBytes == other.maxBytes)
            override fun hashCode(): Int = maxBytes
        }
    }

    /** Transport (socket/network) failures. */
    public sealed class Transport(message: String) : PolyError(message) {
        public class NetworkError(@JvmField public val detail: String) :
            Transport(if (detail.isEmpty()) "Network problem." else "Network problem: $detail") {
            override fun equals(other: Any?): Boolean = this === other || (other is NetworkError && detail == other.detail)
            override fun hashCode(): Int = detail.hashCode()
        }

        public class ProtocolError(@JvmField public val reason: String) :
            Transport(if (reason.isEmpty()) "Connection problem." else "Connection problem: $reason") {
            override fun equals(other: Any?): Boolean = this === other || (other is ProtocolError && reason == other.reason)
            override fun hashCode(): Int = reason.hashCode()
        }

        public object NotConnected :
            Transport("Not connected — please wait for the connection to recover and try again.")
    }

    /** Voice-call failures. Live voice calling ships in the separate `ai.poly:voice` artifact. */
    public sealed class Voice(message: String) : PolyError(message) {
        public object NotImplemented :
            Voice("Voice calling needs the ai.poly:voice add-on — add the dependency and use ai.poly.voice.PolyVoice.")

        public class SignalingFailed(@JvmField public val detail: String) :
            Voice(if (detail.isEmpty()) "Voice call setup failed." else "Voice call setup failed: $detail") {
            override fun equals(other: Any?): Boolean = this === other || (other is SignalingFailed && detail == other.detail)
            override fun hashCode(): Int = detail.hashCode()
        }

        public class MediaFailed(@JvmField public val detail: String) :
            Voice(if (detail.isEmpty()) "Voice call audio failed." else "Voice call audio failed: $detail") {
            override fun equals(other: Any?): Boolean = this === other || (other is MediaFailed && detail == other.detail)
            override fun hashCode(): Int = detail.hashCode()
        }

        /**
         * The media connection dropped after the call was established (network change / lost
         * connectivity) and did not recover within the grace window. Distinct from [MediaFailed]
         * (a hard negotiation/engine failure) so callers can treat a transient drop as retryable.
         */
        public object Disconnected : Voice("The call was disconnected. Please try again.")

        /**
         * The call was interrupted by the system taking audio focus away for good — e.g. an incoming
         * phone call the user answered, or another app starting an exclusive audio session. The call
         * was torn down and the microphone released; the consumer can start a new call afterwards.
         */
        public object Interrupted : Voice("The call was interrupted. Please try again.")

        public object TimedOut : Voice("Voice call timed out.")
    }

    /** Bad configuration (e.g. empty API key). */
    public class InvalidConfiguration(@JvmField public val detail: String) :
        PolyError(if (detail.isEmpty()) "Invalid configuration." else "Invalid configuration: $detail") {
        override fun equals(other: Any?): Boolean = this === other || (other is InvalidConfiguration && detail == other.detail)
        override fun hashCode(): Int = detail.hashCode()
    }

    // ---- Java-friendly classification (no instanceof needed) ----

    public val isAuthError: Boolean get() = this is Auth
    public val isSessionError: Boolean get() = this is Session
    public val isTransportError: Boolean get() = this is Transport
    public val isSessionExpired: Boolean get() = this is Session.SessionExpired
    public val isRetryable: Boolean
        get() = when (this) {
            is Transport -> true
            is Session.UnexpectedDisconnect, is Session.MaxReconnectAttemptsExceeded -> true
            is Voice.Disconnected, is Voice.Interrupted -> true
            else -> false
        }

    /**
     * Structural form for logs/crash reports — keep separate from the user-facing [message].
     *
     * This hand-written form renders the backend raw string for sessionCreationFailed,
     * `code=`/`reason=` and `null` for absent values. It is a debug-only string (never UI).
     */
    public val debugDescription: String
        get() = when (this) {
            is Auth.TokenAcquisitionFailed -> "auth(tokenAcquisitionFailed)"
            is Auth.Unauthorized -> "auth(unauthorized)"
            is Session.SessionCreationFailed -> "session(sessionCreationFailed(${code.raw}))"
            is Session.UnexpectedDisconnect -> "session(unexpectedDisconnect(code=$code, reason=$reason))"
            is Session.MaxReconnectAttemptsExceeded -> "session(maxReconnectAttemptsExceeded)"
            is Session.SessionExpired -> "session(sessionExpired)"
            is Session.SessionEnded -> "session(sessionEnded(reason=$reason))"
            is Message.DeliveryFailed -> "message(deliveryFailed(draftId=$draftId))"
            is Message.PayloadTooLarge -> "message(payloadTooLarge(maxBytes=$maxBytes))"
            is Transport.NetworkError -> "transport(networkError($detail))"
            is Transport.ProtocolError -> "transport(protocolError($reason))"
            is Transport.NotConnected -> "transport(notConnected)"
            is Voice.NotImplemented -> "voice(notImplemented)"
            is Voice.SignalingFailed -> "voice(signalingFailed($detail))"
            is Voice.MediaFailed -> "voice(mediaFailed($detail))"
            is Voice.Disconnected -> "voice(disconnected)"
            is Voice.Interrupted -> "voice(interrupted)"
            is Voice.TimedOut -> "voice(timedOut)"
            is InvalidConfiguration -> "invalidConfiguration($detail)"
        }
}

/**
 * Human-readable session-creation error code. The raw strings are the backend's own
 * messages, preserved verbatim for end-user display. Unknown values decode to [UNKNOWN].
 */
public enum class SessionErrorCode(public val raw: String) {
    ERROR_PARSING_REQUEST("Error parsing request"),
    MISSING_AUTH_HEADERS("Missing authentication headers"),
    CONNECTOR_LOOKUP_FAILED("Unable to get agent details from connector service"),
    CONNECTOR_VALIDATION_FAILED("Failed to validate connector"),
    SESSION_CREATION_FAILED("Error creating session"),
    CONNECTION_CLOSED_ABNORMALLY("Connection closed abnormally"),
    MESSAGE_TOO_LARGE("Message too large"),
    UNKNOWN("UNKNOWN_ERROR"),
    ;

    public companion object {
        // Exact (case-sensitive) match.
        @JvmStatic
        public fun fromRaw(raw: String): SessionErrorCode =
            entries.firstOrNull { it.raw == raw } ?: UNKNOWN
    }
}
