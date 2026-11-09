// Copyright PolyAI Limited

package ai.poly.messaging.internal.services

import ai.poly.messaging.PolyError
import ai.poly.messaging.PolyLogger
import ai.poly.messaging.VerifiedContextHandler
import ai.poly.messaging.VerifiedContextRequest
import ai.poly.messaging.VerifiedContextStatus
import ai.poly.messaging.debugLabel
import ai.poly.messaging.internal.helpers.Clock
import ai.poly.messaging.internal.helpers.i
import ai.poly.messaging.internal.helpers.w
import ai.poly.messaging.internal.ports.RestApiPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Asks the handler for a JWT and submits it. Resolves within [totalBudgetMillis] so the agent join always goes out. */
internal class VerifiedContextService(
    private val handler: VerifiedContextHandler,
    private val api: RestApiPort,
    private val logger: PolyLogger,
    private val scope: CoroutineScope,
    private val handlerTimeoutMillis: Long = DEFAULT_HANDLER_TIMEOUT_MS,
    private val totalBudgetMillis: Long = DEFAULT_TOTAL_BUDGET_MS,
    private val clock: Clock = Clock.SYSTEM,
) {
    suspend fun run(
        request: VerifiedContextRequest,
        accessToken: String,
        notifyAgent: Boolean = false,
    ): VerifiedContextStatus {
        val started = clock.nowMillis()
        val status = withTimeoutOrNull(totalBudgetMillis) { resolve(request, accessToken, notifyAgent) } ?: TIMED_OUT
        val metadata = mapOf("status" to status.debugLabel, "ms" to clock.nowMillis() - started)
        if (status is VerifiedContextStatus.Failed) {
            logger.w("Verified context failed", metadata)
        } else {
            logger.i("Verified context resolved", metadata)
        }
        return status
    }

    private suspend fun resolve(
        request: VerifiedContextRequest,
        accessToken: String,
        notifyAgent: Boolean,
    ): VerifiedContextStatus {
        val token = when (val outcome = awaitWithin(handlerTimeoutMillis) { callHandler(request) }) {
            null -> return VerifiedContextStatus.Failed(PolyError.VerifiedContext.CallbackTimedOut)
            is HandlerOutcome.NoToken -> return VerifiedContextStatus.Declined
            is HandlerOutcome.Threw -> return VerifiedContextStatus.Failed(PolyError.VerifiedContext.CallbackFailed(outcome.reason))
            is HandlerOutcome.Token -> outcome.value
        }
        return awaitWithin(totalBudgetMillis) { submit(request.sessionId, accessToken, token, notifyAgent) } ?: TIMED_OUT
    }

    private suspend fun callHandler(request: VerifiedContextRequest): HandlerOutcome = try {
        handler(request)?.takeIf { it.isNotEmpty() }?.let { HandlerOutcome.Token(it) } ?: HandlerOutcome.NoToken
    } catch (c: CancellationException) {
        // Still active means the handler's own withTimeout fired, not our deadline.
        currentCoroutineContext().ensureActive()
        HandlerOutcome.Threw(c.toString())
    } catch (t: Throwable) {
        HandlerOutcome.Threw(t.toString())
    }

    private suspend fun submit(
        sessionId: String,
        accessToken: String,
        token: String,
        notifyAgent: Boolean,
    ): VerifiedContextStatus = try {
        api.submitVerifiedContext(accessToken, sessionId, token, notifyAgent)
        VerifiedContextStatus.Set
    } catch (c: CancellationException) {
        throw c
    } catch (e: PolyError.VerifiedContext) {
        VerifiedContextStatus.Failed(e)
    } catch (t: Throwable) {
        VerifiedContextStatus.Failed(PolyError.VerifiedContext.SubmitFailed(t.toString()))
    }

    /** Detached: `withTimeoutOrNull` alone would wait for work that ignores cancellation. */
    private suspend fun <T> awaitWithin(timeoutMillis: Long, block: suspend () -> T): T? {
        val work = scope.async { block() }
        try {
            return withTimeoutOrNull(timeoutMillis) { work.await() }
        } finally {
            work.cancel()
        }
    }

    private sealed class HandlerOutcome {
        class Token(val value: String) : HandlerOutcome()
        object NoToken : HandlerOutcome()
        class Threw(val reason: String) : HandlerOutcome()
    }

    private companion object {
        // Same limits as the web and iOS SDKs.
        const val DEFAULT_HANDLER_TIMEOUT_MS = 4_000L
        const val DEFAULT_TOTAL_BUDGET_MS = 5_000L
        val TIMED_OUT = VerifiedContextStatus.Failed(PolyError.VerifiedContext.SubmitFailed("Timed out"))
    }
}
