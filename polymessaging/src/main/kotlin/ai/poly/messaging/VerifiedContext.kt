// Copyright PolyAI Limited

package ai.poly.messaging

import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Passed to `Configuration.onContextRequired`. Sign a JWT with `sub = contextId`. */
public class VerifiedContextRequest(
    @JvmField public val sessionId: String,
    @JvmField public val contextId: String,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is VerifiedContextRequest && sessionId == other.sessionId && contextId == other.contextId)
    override fun hashCode(): Int = 31 * sessionId.hashCode() + contextId.hashCode()
}

/** Returns a JWT signed by your backend, or `null` to skip. Capped at 4s. Java callers use [VerifiedContextCallback]. */
public typealias VerifiedContextHandler = suspend (VerifiedContextRequest) -> String?

/** Outcome of the verified-context step. Never blocks the chat: the agent always joins. */
public sealed class VerifiedContextStatus {
    public object Idle : VerifiedContextStatus()
    public object Pending : VerifiedContextStatus()
    public object Set : VerifiedContextStatus()

    /** The handler returned `null` or an empty string. */
    public object Declined : VerifiedContextStatus()

    public class Failed(@JvmField public val reason: PolyError.VerifiedContext) : VerifiedContextStatus() {
        override fun equals(other: Any?): Boolean = this === other || (other is Failed && reason == other.reason)
        override fun hashCode(): Int = reason.hashCode()
    }
}

internal val VerifiedContextStatus.debugLabel: String
    get() = when (this) {
        is VerifiedContextStatus.Idle -> "idle"
        is VerifiedContextStatus.Pending -> "pending"
        is VerifiedContextStatus.Set -> "set"
        is VerifiedContextStatus.Declined -> "declined"
        is VerifiedContextStatus.Failed -> "failed (${reason.debugDescription})"
    }

/** Late or repeated completions are ignored. */
internal fun VerifiedContextCallback.asHandler(): VerifiedContextHandler = { request ->
    suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean(false)
        onContextRequired(
            request,
            object : Callback<String?> {
                override fun onSuccess(value: String?) {
                    if (completed.compareAndSet(false, true)) continuation.resume(value)
                }

                override fun onError(error: Throwable) {
                    if (completed.compareAndSet(false, true)) continuation.resumeWithException(error)
                }
            },
        )
    }
}
