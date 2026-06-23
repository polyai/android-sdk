// Copyright PolyAI Limited

package ai.poly.messaging

import java.net.URI

/**
 * Immutable SDK configuration. Kotlin callers use the constructor with named/default
 * args; Java callers use [Configuration.Builder]. Only [apiKey] is required.
 *
 * A `Configuration` value type. Not a `data class` (public API binary
 * stability), so [Builder] is the supported way to derive variants.
 */
public class Configuration @JvmOverloads constructor(
    /** Credential from Agent Studio. Required. */
    @JvmField public val apiKey: String,
    /** Region/cluster. Defaults to [Environment.US] (US production). */
    @JvmField public val environment: Environment = Environment.US,
    /** Host identifier sent as `X-Host`. When null, defaults to the app's package name. */
    @JvmField public val hostIdentifier: String? = null,
    /** Whether agent replies stream token-by-token (true) or arrive as complete bubbles. */
    @JvmField public val streamingEnabled: Boolean = true,
    /** Logging verbosity. */
    @JvmField public val logLevel: LogLevel = LogLevel.ERROR,
    /** Override the default heartbeat interval (30s). Server capabilities still win once connected. */
    @JvmField public val heartbeatIntervalSeconds: Int? = null,
    /** Override the default idle session timeout (600s). Matches the backend WS idle timeout. */
    @JvmField public val sessionTimeoutSeconds: Int? = null,
    /** Override the default max reconnect attempts (10). Server capabilities still win once connected. */
    @JvmField public val maxReconnectAttempts: Int? = null,
) {
    /** Java-friendly builder. Kotlin callers can use the primary constructor instead. */
    public class Builder(private val apiKey: String) {
        private var environment: Environment = Environment.US
        private var hostIdentifier: String? = null
        private var streamingEnabled: Boolean = true
        private var logLevel: LogLevel = LogLevel.ERROR
        private var heartbeatIntervalSeconds: Int? = null
        private var sessionTimeoutSeconds: Int? = null
        private var maxReconnectAttempts: Int? = null

        public fun environment(value: Environment): Builder = apply { environment = value }
        public fun hostIdentifier(value: String?): Builder = apply { hostIdentifier = value }
        public fun streamingEnabled(value: Boolean): Builder = apply { streamingEnabled = value }
        public fun logLevel(value: LogLevel): Builder = apply { logLevel = value }
        public fun heartbeatIntervalSeconds(value: Int?): Builder = apply { heartbeatIntervalSeconds = value }
        public fun sessionTimeoutSeconds(value: Int?): Builder = apply { sessionTimeoutSeconds = value }
        public fun maxReconnectAttempts(value: Int?): Builder = apply { maxReconnectAttempts = value }

        public fun build(): Configuration = Configuration(
            apiKey = apiKey,
            environment = environment,
            hostIdentifier = hostIdentifier,
            streamingEnabled = streamingEnabled,
            logLevel = logLevel,
            heartbeatIntervalSeconds = heartbeatIntervalSeconds,
            sessionTimeoutSeconds = sessionTimeoutSeconds,
            maxReconnectAttempts = maxReconnectAttempts,
        )
    }
}

/**
 * The backend region or a custom endpoint. Production regions are objects; the
 * escape hatches carry data. Java branches via `instanceof`; use the factories
 * ([cluster], [custom]) for the `cluster("dev")` / `custom(...)` style.
 */
public sealed class Environment {
    /** Production US region (`messaging.us-1.poly.ai`). The default. */
    public object US : Environment()

    /** Production UK region (`messaging.uk-1.poly.ai`). */
    public object UK : Environment()

    /** Production EU West region (`messaging.euw-1.poly.ai`). */
    public object EUW : Environment()

    /**
     * A named cluster not covered by the production regions, e.g. `cluster("dev")`
     * resolves to `messaging.dev.poly.ai`.
     */
    public class Cluster(@JvmField public val name: String) : Environment() {
        override fun equals(other: Any?): Boolean = other is Cluster && name == other.name
        override fun hashCode(): Int = name.hashCode()
        override fun toString(): String = "Environment.Cluster($name)"
    }

    /** Override both base URLs entirely — for local mocks, proxies, or one-off deployments. */
    public class Custom(
        @JvmField public val restBaseUrl: URI,
        @JvmField public val wsBaseUrl: URI,
    ) : Environment() {
        override fun equals(other: Any?): Boolean =
            other is Custom && restBaseUrl == other.restBaseUrl && wsBaseUrl == other.wsBaseUrl
        override fun hashCode(): Int = restBaseUrl.hashCode() * 31 + wsBaseUrl.hashCode()
        override fun toString(): String = "Environment.Custom(rest=$restBaseUrl, ws=$wsBaseUrl)"
    }

    public companion object {
        @JvmStatic public fun cluster(name: String): Environment = Cluster(name)
        @JvmStatic public fun custom(restBaseUrl: URI, wsBaseUrl: URI): Environment = Custom(restBaseUrl, wsBaseUrl)
    }
}
