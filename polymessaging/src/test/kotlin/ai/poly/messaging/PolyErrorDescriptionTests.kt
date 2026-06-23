// Copyright PolyAI Limited

package ai.poly.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Pure-JVM tests asserting that every [PolyError] leaf carries a non-empty, user-facing
 * [Throwable.message] that never leaks internal structure. No Android framework, no
 * coroutines, no fakes — just the sealed type.
 */
class PolyErrorDescriptionTests {

    /**
     * Every case must produce a non-empty, user-facing string that does NOT leak the
     * internal/structural form. One per top-level case category — payload variants are
     * smoke-tested two-of-each-kind to catch obvious typos. Includes
     * [PolyError.Transport.NotConnected] so the iteration tests cover the full Kotlin
     * taxonomy.
     */
    private val allCases: List<PolyError> = listOf(
        PolyError.Auth.TokenAcquisitionFailed,
        PolyError.Auth.Unauthorized,
        PolyError.Session.SessionCreationFailed(SessionErrorCode.UNKNOWN),
        PolyError.Session.UnexpectedDisconnect(1006, "test"),
        PolyError.Session.UnexpectedDisconnect(1006, ""),
        PolyError.Session.MaxReconnectAttemptsExceeded,
        PolyError.Session.SessionExpired,
        PolyError.Session.SessionEnded("user_ended"),
        PolyError.Session.SessionEnded(null),
        PolyError.Message.DeliveryFailed("abc"),
        PolyError.Message.PayloadTooLarge(1_048_576),
        PolyError.Transport.NetworkError("offline"),
        PolyError.Transport.NetworkError(""),
        PolyError.Transport.ProtocolError("bad frame"),
        PolyError.Transport.NotConnected, // Android-only case.
        PolyError.Voice.NotImplemented,
        PolyError.Voice.SignalingFailed("ICE failed"),
        PolyError.Voice.MediaFailed("no microphone"),
        PolyError.Voice.TimedOut,
        PolyError.InvalidConfiguration("token empty"),
    )

    @Test
    fun polyError_message_isNonEmptyForAllCases() {
        for (error in allCases) {
            val message = assertNotNull(
                error.message,
                "message must not be null for ${error.debugDescription}",
            )
            assertFalse(
                message.isEmpty(),
                "message must not be empty for ${error.debugDescription}",
            )
        }
    }

    @Test
    fun polyError_message_doesNotLeakInternalForm() {
        for (error in allCases) {
            val message = assertNotNull(error.message)
            // Guard against leaking internal form into user-facing text: the
            // fully-qualified class name prefix ("ai.poly.messaging.PolyError")
            // and a "null" from interpolating a nullable payload.
            assertFalse(
                message.contains("ai.poly.messaging"),
                "user-facing message leaked internal type for ${error.debugDescription}: $message",
            )
            assertFalse(
                message.contains("null"),
                "user-facing message leaked null for ${error.debugDescription}: $message",
            )
        }
    }

    @Test
    fun polyError_localizedMessage_matchesMessage() {
        for (error in allCases) {
            // Throwable.localizedMessage is the platform's localized accessor — it must
            // never drift from message.
            assertEquals(
                error.message,
                error.localizedMessage,
                "localizedMessage drifted from message for ${error.debugDescription}",
            )
        }
    }

    @Test
    fun polyError_unexpectedDisconnect_omitsEmptyReason() {
        val message = assertNotNull(PolyError.Session.UnexpectedDisconnect(1006, "").message)
        assertEquals("Disconnected unexpectedly (code 1006).", message)
        assertFalse(message.endsWith(": "), "trailing colon when reason is empty: $message")
    }

    @Test
    fun polyError_sessionEnded_nullReason_doesNotLeakNull() {
        val message = assertNotNull(PolyError.Session.SessionEnded(null).message)
        assertEquals("Conversation ended.", message)
        // Guards against a null leaking into user-facing text.
        assertFalse(message.contains("null"), "null leaked into user-facing text: $message")
        assertFalse(message.endsWith(":"), "trailing colon when reason is null: $message")
        // Also covers the reason = "" arm of the same isNullOrEmpty() branch.
        assertEquals("Conversation ended.", PolyError.Session.SessionEnded("").message)
    }
}
