// Copyright PolyAI Limited

package ai.poly.messaging

import ai.poly.messaging.internal.services.VerifiedContextService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VerifiedContextServiceTests {

    private val request = VerifiedContextRequest("session_123", "ctx_abc")

    private fun failed(reason: PolyError.VerifiedContext) = VerifiedContextStatus.Failed(reason)

    private suspend fun TestScope.runService(
        api: FakeRestApi = FakeRestApi(),
        notifyAgent: Boolean = false,
        handler: VerifiedContextHandler,
    ): VerifiedContextStatus {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val service = VerifiedContextService(handler, api, NoopLogger, scope)
        return service.run(request, "access-token", notifyAgent).also { scope.cancel() }
    }

    @Test
    fun token_isSubmitted_andStatusIsSet() = runTest {
        val api = FakeRestApi()
        val seen = mutableListOf<VerifiedContextRequest>()

        val status = runService(api) { seen += it; "signed.jwt.token" }

        assertEquals(VerifiedContextStatus.Set, status)
        assertEquals(listOf(request), seen)
        val submitted = api.submittedContexts.single()
        assertEquals("access-token", submitted.accessToken)
        assertEquals("session_123", submitted.sessionId)
        assertEquals("signed.jwt.token", submitted.token)
        assertEquals(false, submitted.notifyAgent)
    }

    @Test
    fun notifyAgent_isPassedToSubmit() = runTest {
        val api = FakeRestApi()
        runService(api, notifyAgent = true) { "signed.jwt.token" }
        assertTrue(api.submittedContexts.single().notifyAgent)
    }

    @Test
    fun nullToken_declinesWithoutSubmitting() = runTest {
        val api = FakeRestApi()
        assertEquals(VerifiedContextStatus.Declined, runService(api) { null })
        assertTrue(api.submittedContexts.isEmpty())
    }

    @Test
    fun emptyToken_declinesWithoutSubmitting() = runTest {
        val api = FakeRestApi()
        assertEquals(VerifiedContextStatus.Declined, runService(api) { "" })
        assertTrue(api.submittedContexts.isEmpty())
    }

    @Test
    fun throwingHandler_fails() = runTest {
        val api = FakeRestApi()
        val status = runService(api) { throw IllegalStateException("signing down") }
        val reason = assertIs<PolyError.VerifiedContext.CallbackFailed>(assertIs<VerifiedContextStatus.Failed>(status).reason)
        assertTrue(reason.reason.contains("signing down"))
        assertTrue(api.submittedContexts.isEmpty())
    }

    @Test
    fun handlersOwnTimeout_isACallbackFailure_notACancelledGate() = runTest {
        val status = runService { withTimeout(100) { delay(1_000); "too.late" } }
        assertIs<PolyError.VerifiedContext.CallbackFailed>(assertIs<VerifiedContextStatus.Failed>(status).reason)
    }

    @Test
    fun slowHandler_timesOutAtFourSeconds() = runTest {
        val api = FakeRestApi()
        val status = runService(api) { delay(60_000); "too.late" }
        assertEquals(failed(PolyError.VerifiedContext.CallbackTimedOut), status)
        assertEquals(4_000, currentTime)
        assertTrue(api.submittedContexts.isEmpty())
    }

    @Test
    fun handlerThatIgnoresCancellation_stillTimesOut() = runTest {
        val status = runService { withContext(NonCancellable) { delay(60_000); "too.late" } }
        assertEquals(failed(PolyError.VerifiedContext.CallbackTimedOut), status)
        assertEquals(4_000, currentTime)
    }

    @Test
    fun verificationFailure_isSurfaced() = runTest {
        val api = FakeRestApi().apply {
            submitContextError = PolyError.VerifiedContext.VerificationFailed("invalid token signature")
        }
        val status = runService(api) { "bad.sig.token" }
        assertEquals(failed(PolyError.VerifiedContext.VerificationFailed("invalid token signature")), status)
    }

    @Test
    fun otherSubmitError_isWrappedAsSubmitFailed() = runTest {
        val api = FakeRestApi().apply { submitContextError = IllegalStateException("boom") }
        val status = runService(api) { "a.b.c" }
        assertIs<PolyError.VerifiedContext.SubmitFailed>(assertIs<VerifiedContextStatus.Failed>(status).reason)
    }

    @Test
    fun slowSubmit_isCappedByTotalBudget() = runTest {
        val api = FakeRestApi().apply { submitContextDelayMs = 60_000 }
        val status = runService(api) { "a.b.c" }
        assertEquals(failed(PolyError.VerifiedContext.SubmitFailed("Timed out")), status)
        assertEquals(5_000, currentTime)
    }

    @Test
    fun handlerAndSubmit_shareTheBudget() = runTest {
        val api = FakeRestApi().apply { submitContextDelayMs = 60_000 }
        val status = runService(api) { delay(3_500); "a.b.c" }
        assertEquals(failed(PolyError.VerifiedContext.SubmitFailed("Timed out")), status)
        assertEquals(5_000, currentTime)
    }

    // ---- Java callback bridge ----

    private fun bridged(callback: VerifiedContextCallback): VerifiedContextHandler =
        Configuration.Builder("key").onContextRequired(callback).build().onContextRequired!!

    @Test
    fun javaCallback_deliversTokenAndNull() = runTest {
        assertEquals("a.b.c", bridged { _, result -> result.onSuccess("a.b.c") }(request))
        assertNull(bridged { _, result -> result.onSuccess(null) }(request))
    }

    @Test
    fun javaCallback_onErrorThrows() = runTest {
        assertFailsWith<IllegalStateException> {
            bridged { _, result -> result.onError(IllegalStateException("nope")) }(request)
        }
    }

    @Test
    fun javaCallback_secondCompletionIsIgnored() = runTest {
        val token = bridged { _, result ->
            result.onSuccess("first")
            result.onSuccess("second")
            result.onError(IllegalStateException("late"))
        }(request)
        assertEquals("first", token)
    }
}
