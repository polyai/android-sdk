// Copyright PolyAI Limited

package ai.poly.messaging

import ai.poly.messaging.internal.adapters.AndroidLogLogger
import ai.poly.messaging.internal.adapters.OkHttpRestApi
import ai.poly.messaging.internal.adapters.OkHttpWebSocketTransport
import ai.poly.messaging.internal.helpers.AppLifecycleObserver
import ai.poly.messaging.internal.helpers.Backoff
import ai.poly.messaging.internal.helpers.Clock
import ai.poly.messaging.internal.helpers.DeviceTypeDetector
import ai.poly.messaging.internal.helpers.EnvironmentUrls
import ai.poly.messaging.internal.helpers.NetworkMonitor
import ai.poly.messaging.internal.helpers.SessionStore
import ai.poly.messaging.internal.ports.RestApiPort
import ai.poly.messaging.internal.ports.Transport
import ai.poly.messaging.internal.services.ChatService
import ai.poly.messaging.internal.services.ConnectionService
import ai.poly.messaging.internal.services.Coordinator
import ai.poly.messaging.internal.services.HeartbeatService
import ai.poly.messaging.internal.services.SessionService
import ai.poly.messaging.voice.PolyCall
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The long-lived connector handle — the lower-level imperative API beneath [ChatSession].
 * Obtain it via `PolyMessaging.configure(...)`,
 * or reach it from `session.client`. Exposes the raw streams and an escape-hatch API.
 */
public class PolyMessagingClient internal constructor(
    public val config: Configuration,
    private val coordinator: Coordinator,
    private val transport: Transport,
    private val scope: CoroutineScope,
    autoStart: Boolean,
) {
    /** Decoded inbound events (the stream [ChatSession] is built on). */
    public val events: SharedFlow<MessagingEvent> get() = coordinator.events

    /** Live connection status. */
    public val connectionStatus: StateFlow<ConnectionStatus> get() = coordinator.connectionStatus

    /** Session lifecycle state; late subscribers receive the current value. */
    public val sessionState: SharedFlow<SessionState> get() = coordinator.sessionState

    private val shutdownDone = AtomicBoolean(false)

    // The initial start (session create + connect). send/sendTyping/startNewSession await it before
    // delegating — so no optimistic bubble / outgoing frame is
    // produced until the session-create flow has completed. runCatching keeps a start failure from
    // crashing the host (the failure is surfaced via sessionState).
    private val startJob: Job? = if (autoStart) scope.launch { runCatching { coordinator.start() } } else null

    private suspend fun ensureStarted() {
        startJob?.join()
        // Rethrow a failed start through send/sendTyping/resume/startNewSession.
        coordinator.startFailure?.let { throw it }
    }

    public suspend fun send(text: String) {
        ensureStarted()
        coordinator.send(text)
    }

    public suspend fun sendTyping() {
        ensureStarted()
        coordinator.sendTyping()
    }

    public suspend fun end(reason: String? = "user_ended"): Unit = coordinator.end(reason)

    /**
     * Await the initial start, then re-establish the
     * socket if it has terminally failed (reconnect ladder exhausted). `ensureStarted()` only joins
     * the FIRST start — it does not revive a dead connection — so a terminal-error "Try Again" that
     * calls resume() needs the [Coordinator.resume] step to actually reconnect. Idempotent: a no-op
     * when the connection is already healthy.
     */
    public suspend fun resume() {
        ensureStarted()
        coordinator.resume()
    }

    public suspend fun startNewSession() {
        ensureStarted()
        coordinator.startNewSession()
    }

    public suspend fun shutdown() {
        if (!shutdownDone.compareAndSet(false, true)) return
        startJob?.cancel() // cancel the initial start
        coordinator.destroy()
    }

    public fun getConnection(): Connection = transport

    public fun voice(): PolyCall = PolyCall(config)

    // ---- Java callback overloads ----

    public fun send(text: String, executor: Executor, callback: Callback<Unit>): Cancellable =
        runAsync(executor, callback) { send(text) }

    public fun sendTyping(executor: Executor, callback: Callback<Unit>): Cancellable =
        runAsync(executor, callback) { sendTyping() }

    public fun end(executor: Executor, callback: Callback<Unit>): Cancellable =
        runAsync(executor, callback) { end() }

    public fun resume(executor: Executor, callback: Callback<Unit>): Cancellable =
        runAsync(executor, callback) { resume() }

    public fun startNewSession(executor: Executor, callback: Callback<Unit>): Cancellable =
        runAsync(executor, callback) { startNewSession() }

    public fun shutdown(executor: Executor, callback: Callback<Unit>): Cancellable =
        runAsync(executor, callback) { shutdown() }

    /**
     * Register a Java listener for [events]. The subscription is live when this returns
     * ([CoroutineStart.UNDISPATCHED] runs the collector to its first suspension — the
     * SharedFlow subscribe — before returning): [events] has replay = 0, so without that
     * guarantee an event emitted right after registration would be silently lost, and
     * Java callers have no `onSubscription` to handshake with.
     */
    public fun addEventListener(executor: Executor, listener: EventListener): Cancellable {
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            events.collect { e -> executor.execute { listener.onEvent(e) } }
        }
        return Cancellable { job.cancel() }
    }

    /** Register a Java listener for [connectionStatus] (fires with current value, then on change). */
    public fun addConnectionStatusListener(executor: Executor, listener: ValueListener<ConnectionStatus>): Cancellable {
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            connectionStatus.collect { s -> executor.execute { listener.onChanged(s) } }
        }
        return Cancellable { job.cancel() }
    }

    /** Register a Java listener for [sessionState]. Live on return (see [addEventListener]). */
    public fun addSessionStateListener(executor: Executor, listener: ValueListener<SessionState>): Cancellable {
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            sessionState.collect { s -> executor.execute { listener.onChanged(s) } }
        }
        return Cancellable { job.cancel() }
    }

    internal fun <T> runAsync(executor: Executor, callback: Callback<T>, block: suspend () -> T): Cancellable {
        val job = scope.launch {
            try {
                val result = block()
                executor.execute { callback.onSuccess(result) }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                executor.execute { callback.onError(t) }
            }
        }
        return Cancellable { job.cancel() }
    }

    internal companion object {
        fun create(config: Configuration, context: Context): PolyMessagingClient {
            // An empty key on the chat/start path is a crash, not a throw.
            if (config.apiKey.isEmpty()) {
                ai.poly.messaging.internal.helpers.polyFatalError("PolyMessaging: apiKey must not be empty. Get your key from Agent Studio.")
            }
            val appContext = context.applicationContext
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val logger = AndroidLogLogger(config.logLevel)
            // Log this insecure-scheme warning UNCONDITIONALLY (bypasses the log-level threshold),
            // so use android.util.Log.w directly rather than the threshold-gated logger.
            (config.environment as? Environment.Custom)?.let { env ->
                if (env.restBaseUrl.scheme != "https") {
                    android.util.Log.w("PolyMessaging", "REST URL uses ${env.restBaseUrl.scheme} instead of https — traffic will not be encrypted")
                }
                if (env.wsBaseUrl.scheme != "wss") {
                    android.util.Log.w("PolyMessaging", "WebSocket URL uses ${env.wsBaseUrl.scheme} instead of wss — traffic will not be encrypted")
                }
            }
            val hostId = config.hostIdentifier ?: appContext.packageName
            val rest = OkHttpRestApi(
                baseUrl = EnvironmentUrls.restBaseUrl(config.environment),
                apiKey = config.apiKey,
                hostIdentifier = hostId,
                version = POLY_MESSAGING_VERSION,
                logger = logger,
            )
            val transport = OkHttpWebSocketTransport(logger)
            return assemble(
                config = config,
                transport = transport,
                rest = rest,
                store = SessionStore(appContext, config.apiKey),
                deviceType = DeviceTypeDetector.detect(appContext),
                wsBaseUrl = EnvironmentUrls.wsBaseUrl(config.environment),
                logger = logger,
                scope = scope,
                autoStart = true,
                networkMonitor = NetworkMonitor(appContext),
                appLifecycle = AppLifecycleObserver(),
            )
        }

        /** Test seam: assemble over a fake transport/rest without a Context. */
        fun forTest(
            config: Configuration,
            transport: Transport,
            rest: RestApiPort,
            scope: CoroutineScope,
            backoff: Backoff = Backoff(overrideSeconds = 0.0),
            autoStart: Boolean = true,
            networkMonitor: ai.poly.messaging.internal.ports.NetworkStatePort? = null,
            appLifecycle: ai.poly.messaging.internal.ports.ForegroundPort? = null,
            clock: Clock = Clock.SYSTEM,
        ): PolyMessagingClient = assemble(
            config = config, transport = transport, rest = rest, store = null,
            deviceType = DeviceTypeDetector.MOBILE, wsBaseUrl = "wss://test.local",
            logger = NoopLogger, scope = scope, autoStart = autoStart, backoff = backoff,
            networkMonitor = networkMonitor, appLifecycle = appLifecycle, clock = clock,
        )

        private fun assemble(
            config: Configuration,
            transport: Transport,
            rest: RestApiPort,
            store: SessionStore?,
            deviceType: String,
            wsBaseUrl: String,
            logger: PolyLogger,
            scope: CoroutineScope,
            autoStart: Boolean,
            backoff: Backoff = Backoff(),
            networkMonitor: ai.poly.messaging.internal.ports.NetworkStatePort? = null,
            appLifecycle: ai.poly.messaging.internal.ports.ForegroundPort? = null,
            clock: Clock = Clock.SYSTEM,
        ): PolyMessagingClient {
            val sessionService = SessionService(
                api = rest, store = store, streamingEnabled = config.streamingEnabled,
                platform = Platform.ANDROID.raw, deviceType = deviceType,
                // Production hardcodes 600s and does NOT wire config.sessionTimeoutSeconds.
                // (The config knob is currently ignored — flagged for a future fix.)
                sessionTimeoutSeconds = 600, logger = logger, scope = scope, clock = clock,
            )
            val connectionService = ConnectionService(transport, wsBaseUrl, scope, backoff, logger, clock).apply {
                config.maxReconnectAttempts?.let { if (it > 0) maxReconnectAttempts = it }
            }
            val chatService = ChatService()
            val heartbeatService = HeartbeatService(scope, config.heartbeatIntervalSeconds ?: 30)
            val coordinator = Coordinator(
                transport, sessionService, connectionService, chatService, heartbeatService,
                logger, scope, networkMonitor, appLifecycle, clock,
            )
            return PolyMessagingClient(config, coordinator, transport, scope, autoStart)
        }
    }
}

/** A logger that discards everything — used in tests. */
internal object NoopLogger : PolyLogger {
    override fun log(level: LogLevel, message: String, metadata: Map<String, Any?>?) {}
}
