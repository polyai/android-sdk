// Copyright PolyAI Limited

package ai.poly.messaging

import ai.poly.messaging.internal.helpers.JwtValidator
import ai.poly.messaging.internal.helpers.SessionStore
import ai.poly.messaging.internal.helpers.polyFatalError
import ai.poly.messaging.voice.PolyCall
import android.content.Context

/**
 * The SDK entry point — a static namespace.
 * Call [initialize] once in your `Application.onCreate()`, then [chat]/[start] to get a
 * [ChatSession]. From Java these are plain static calls (`PolyMessaging.chat(...)`).
 */
public object PolyMessaging {

    /** SDK version (matches the published coordinate). */
    @JvmField
    public val VERSION: String = POLY_MESSAGING_VERSION

    private val lock = Any()
    private var appContext: Context? = null
    private var storedConfig: Configuration? = null

    private const val RESUME_WINDOW_MILLIS = 600_000L

    /** Store the global configuration. Must be called before any other SDK use. */
    @JvmStatic
    public fun initialize(context: Context, config: Configuration) {
        // An empty key is a crash (unrecoverable) rather than a throw.
        if (config.apiKey.isEmpty()) polyFatalError("PolyMessaging: apiKey must not be empty. Get your key from Agent Studio.")
        synchronized(lock) {
            appContext = context.applicationContext
            storedConfig = config
        }
    }

    /** Advanced: build a lower-level [PolyMessagingClient] directly. */
    @JvmStatic
    @Throws(PolyError::class)
    public fun configure(context: Context, config: Configuration): PolyMessagingClient {
        if (config.apiKey.isEmpty()) throw PolyError.InvalidConfiguration("apiKey must not be empty")
        return PolyMessagingClient.create(config, context.applicationContext)
    }

    /** Resume the prior session if still valid (within ~10 min idle), else create fresh. */
    @JvmStatic
    @JvmOverloads
    public fun chat(streamingEnabled: Boolean? = null): ChatSession = chat(currentConfig(), streamingEnabled)

    @JvmStatic
    @JvmOverloads
    public fun chat(config: Configuration, streamingEnabled: Boolean? = null): ChatSession {
        val client = PolyMessagingClient.create(config, currentContext())
        return ChatSession(client, streamingOverride = streamingEnabled)
    }

    /** Always start a brand-new session, discarding any persisted state. */
    @JvmStatic
    @JvmOverloads
    public fun start(streamingEnabled: Boolean? = null): ChatSession = start(currentConfig(), streamingEnabled)

    @JvmStatic
    @JvmOverloads
    public fun start(config: Configuration, streamingEnabled: Boolean? = null): ChatSession {
        clearResumableSession(config.apiKey)
        val client = PolyMessagingClient.create(config, currentContext())
        return ChatSession(client, streamingOverride = streamingEnabled)
    }

    @Deprecated("Use chat", ReplaceWith("chat(config, streamingEnabled)"))
    @JvmStatic
    @JvmOverloads
    public fun resume(config: Configuration, streamingEnabled: Boolean? = null): ChatSession =
        chat(config, streamingEnabled)

    @JvmStatic
    public fun hasResumableSession(apiKey: String): Boolean {
        if (apiKey.isEmpty()) return false
        val ctx = appContext ?: return false
        val stored = SessionStore(ctx, apiKey).load() ?: return false
        if (System.currentTimeMillis() - stored.lastActivityMillis >= RESUME_WINDOW_MILLIS) return false
        val token = stored.accessToken ?: return false
        return JwtValidator.isStructurallyValid(token)
    }

    @JvmStatic
    public fun hasResumableSession(): Boolean = hasResumableSession(currentConfig().apiKey)

    @JvmStatic
    public fun clearResumableSession(apiKey: String) {
        if (apiKey.isEmpty()) return
        appContext?.let { SessionStore(it, apiKey).clear() }
    }

    @JvmStatic
    public fun clearResumableSession(): Unit = clearResumableSession(currentConfig().apiKey)

    @JvmStatic
    public fun voice(): PolyCall = PolyCall(currentConfig())

    @JvmStatic
    public fun voice(config: Configuration): PolyCall = PolyCall(config)

    // Snapshot the field inside the lock (the same lock initialize() writes under), then
    // null-check/fatal outside the lock.
    internal fun currentContext(): Context =
        synchronized(lock) { appContext } ?: polyFatalError("PolyMessaging: call initialize(context, config) before using the SDK")

    internal fun currentConfig(): Configuration =
        synchronized(lock) { storedConfig } ?: polyFatalError("PolyMessaging: call initialize(context, config) before using the SDK")
}
