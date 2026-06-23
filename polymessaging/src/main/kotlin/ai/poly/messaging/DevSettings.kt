// Copyright PolyAI Limited

package ai.poly.messaging

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI

/**
 * QA-only runtime [Configuration] builder. Flip
 * environment / streaming / logging without rebuilding; persisted via `SharedPreferences`.
 * `open` for subclassing in playground apps.
 * Reads the API key from the global config — call [PolyMessaging.initialize] first.
 *
 * The default environment, cluster name and custom URLs are seeded from the
 * `initialize(...)` config so the picker reflects the active environment on first
 * launch (a persisted value always wins over the seed).
 */
public open class DevSettings @JvmOverloads constructor(
    context: Context,
    private val hostIdentifier: String? = null,
    defaultEnvironment: EnvironmentKind? = null,
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("ai.poly.messaging.devsettings", Context.MODE_PRIVATE),
) {
    public enum class EnvironmentKind(public val value: Int) {
        US(0), UK(1), EUW(2), CLUSTER(3), CUSTOM(4),
        ;

        public val displayName: String
            get() = when (this) {
                // Picker labels.
                US -> "Production US"; UK -> "Production UK"; EUW -> "Production EU West"
                CLUSTER -> "Cluster"; CUSTOM -> "Custom URLs"
            }

        public companion object {
            @JvmStatic public fun fromValue(value: Int): EnvironmentKind = entries.firstOrNull { it.value == value } ?: US
        }
    }

    // Seed the picker from the active initialize config: the seed kind is the
    // ctor override else the config's environment; cluster name / custom URLs seed from the config too.
    private val baseEnvironment: Environment = PolyMessaging.currentConfig().environment
    private val defaultEnvironmentKind: EnvironmentKind = defaultEnvironment ?: kindFor(baseEnvironment)
    private val seedCluster: String = (baseEnvironment as? Environment.Cluster)?.name ?: "us-1"
    private val seedRest: String = (baseEnvironment as? Environment.Custom)?.restBaseUrl?.toString() ?: ""
    private val seedWs: String = (baseEnvironment as? Environment.Custom)?.wsBaseUrl?.toString() ?: ""

    // A persisted value always wins over the seed. An out-of-range persisted int resolves to the
    // seed kind (NOT US); fromValue's `?: US` default would force US, so resolve against the seed
    // here to fall back to the seed kind for an unrecognized persisted value.
    private val _environmentKind = MutableStateFlow(
        EnvironmentKind.entries.firstOrNull { it.value == prefs.getInt(K_ENV, defaultEnvironmentKind.value) } ?: defaultEnvironmentKind,
    )
    public val environmentKind: StateFlow<EnvironmentKind> = _environmentKind.asStateFlow()

    private val _clusterName = MutableStateFlow(prefs.getString(K_CLUSTER, seedCluster).orEmpty())
    public val clusterName: StateFlow<String> = _clusterName.asStateFlow()

    private val _customRestUrl = MutableStateFlow(prefs.getString(K_REST, seedRest).orEmpty())
    public val customRestUrl: StateFlow<String> = _customRestUrl.asStateFlow()

    private val _customWsUrl = MutableStateFlow(prefs.getString(K_WS, seedWs).orEmpty())
    public val customWsUrl: StateFlow<String> = _customWsUrl.asStateFlow()

    private val _streamingEnabled = MutableStateFlow(prefs.getBoolean(K_STREAMING, true))
    public val streamingEnabled: StateFlow<Boolean> = _streamingEnabled.asStateFlow()

    private val _logLevel = MutableStateFlow(LogLevel.fromLevel(prefs.getInt(K_LOG, LogLevel.DEBUG.level)) ?: LogLevel.DEBUG)
    public val logLevel: StateFlow<LogLevel> = _logLevel.asStateFlow()

    /** 0 = use SDK default (600s). */
    private val _sessionTimeoutSeconds = MutableStateFlow(prefs.getInt(K_TIMEOUT, 0))
    public val sessionTimeoutSeconds: StateFlow<Int> = _sessionTimeoutSeconds.asStateFlow()

    /** 0 = use SDK default (30s). */
    private val _heartbeatIntervalSeconds = MutableStateFlow(prefs.getInt(K_HEARTBEAT, 0))
    public val heartbeatIntervalSeconds: StateFlow<Int> = _heartbeatIntervalSeconds.asStateFlow()

    /** 0 = use SDK default (10). */
    private val _maxReconnectAttempts = MutableStateFlow(prefs.getInt(K_MAXRECONNECT, 0))
    public val maxReconnectAttempts: StateFlow<Int> = _maxReconnectAttempts.asStateFlow()

    private val _showDebugStrip = MutableStateFlow(prefs.getBoolean(K_STRIP, false))
    public val showDebugStrip: StateFlow<Boolean> = _showDebugStrip.asStateFlow()

    private val _showMessageTimestamps = MutableStateFlow(prefs.getBoolean(K_TS, true))
    public val showMessageTimestamps: StateFlow<Boolean> = _showMessageTimestamps.asStateFlow()

    /** The `streamingEnabled` baked into the most recently created session — call [recordSessionApplied]. */
    private val _lastAppliedStreamingEnabled = MutableStateFlow(prefs.getBoolean(K_LAST_STREAMING, true))
    public val lastAppliedStreamingEnabled: StateFlow<Boolean> = _lastAppliedStreamingEnabled.asStateFlow()

    public open fun setEnvironmentKind(value: EnvironmentKind) { prefs.edit().putInt(K_ENV, value.value).apply(); _environmentKind.value = value }
    public open fun setClusterName(value: String) { prefs.edit().putString(K_CLUSTER, value).apply(); _clusterName.value = value }
    public open fun setCustomRestUrl(value: String) { prefs.edit().putString(K_REST, value).apply(); _customRestUrl.value = value }
    public open fun setCustomWsUrl(value: String) { prefs.edit().putString(K_WS, value).apply(); _customWsUrl.value = value }
    public open fun setStreamingEnabled(value: Boolean) { prefs.edit().putBoolean(K_STREAMING, value).apply(); _streamingEnabled.value = value }
    public open fun setLogLevel(value: LogLevel) { prefs.edit().putInt(K_LOG, value.level).apply(); _logLevel.value = value }
    public open fun setSessionTimeoutSeconds(value: Int) { prefs.edit().putInt(K_TIMEOUT, value).apply(); _sessionTimeoutSeconds.value = value }
    public open fun setHeartbeatIntervalSeconds(value: Int) { prefs.edit().putInt(K_HEARTBEAT, value).apply(); _heartbeatIntervalSeconds.value = value }
    public open fun setMaxReconnectAttempts(value: Int) { prefs.edit().putInt(K_MAXRECONNECT, value).apply(); _maxReconnectAttempts.value = value }
    public open fun setShowDebugStrip(value: Boolean) { prefs.edit().putBoolean(K_STRIP, value).apply(); _showDebugStrip.value = value }
    public open fun setShowMessageTimestamps(value: Boolean) { prefs.edit().putBoolean(K_TS, value).apply(); _showMessageTimestamps.value = value }

    private fun kindFor(env: Environment): EnvironmentKind = when (env) {
        Environment.US -> EnvironmentKind.US
        Environment.UK -> EnvironmentKind.UK
        Environment.EUW -> EnvironmentKind.EUW
        is Environment.Cluster -> EnvironmentKind.CLUSTER
        is Environment.Custom -> EnvironmentKind.CUSTOM
    }

    public open fun resolvedEnvironment(): Environment = when (_environmentKind.value) {
        EnvironmentKind.US -> Environment.US
        EnvironmentKind.UK -> Environment.UK
        EnvironmentKind.EUW -> Environment.EUW
        EnvironmentKind.CLUSTER -> {
            // Trim and fall back to US for an empty/whitespace cluster name (no crash).
            val name = _clusterName.value.trim()
            if (name.isEmpty()) Environment.US else Environment.Cluster(name)
        }
        EnvironmentKind.CUSTOM -> {
            // Empty/unparseable input falls back to US. java.net.URI throws on malformed input and
            // accepts "" (empty URI), so treat blank/throwing input as null.
            val rest = _customRestUrl.value.takeIf { it.isNotBlank() }?.let { runCatching { URI(it) }.getOrNull() }
            val ws = _customWsUrl.value.takeIf { it.isNotBlank() }?.let { runCatching { URI(it) }.getOrNull() }
            if (rest != null && ws != null) Environment.Custom(rest, ws) else Environment.US
        }
    }

    /** A short, human-readable label for the resolved environment host. */
    public open fun environmentDisplayName(): String = when (_environmentKind.value) {
        EnvironmentKind.US -> "messaging.us-1.poly.ai"
        EnvironmentKind.UK -> "messaging.uk-1.poly.ai"
        EnvironmentKind.EUW -> "messaging.euw-1.poly.ai"
        EnvironmentKind.CLUSTER -> {
            val name = _clusterName.value.trim()
            if (name.isEmpty()) "(missing cluster)" else "messaging.$name.poly.ai"
        }
        EnvironmentKind.CUSTOM ->
            _customRestUrl.value.takeIf { it.isNotBlank() }?.let { runCatching { URI(it).host }.getOrNull() } ?: "(custom)"
    }

    /** True when any knob differs from the seeded defaults (6 conditions). */
    public open val hasCustomization: Boolean
        get() = _environmentKind.value != defaultEnvironmentKind ||
            !_streamingEnabled.value ||
            _logLevel.value != LogLevel.DEBUG ||
            _sessionTimeoutSeconds.value > 0 ||
            _heartbeatIntervalSeconds.value > 0 ||
            _maxReconnectAttempts.value > 0

    /** Resets the env/streaming/log/override knobs only — it does NOT touch
     *  showDebugStrip / showMessageTimestamps / lastAppliedStreamingEnabled, and does not wipe other keys. */
    public open fun resetToDefaults() {
        setEnvironmentKind(defaultEnvironmentKind)
        setClusterName("us-1")
        setCustomRestUrl("")
        setCustomWsUrl("")
        setStreamingEnabled(true)
        setLogLevel(LogLevel.DEBUG) // dev-overlay seeds DEBUG
        setSessionTimeoutSeconds(0)
        setHeartbeatIntervalSeconds(0)
        setMaxReconnectAttempts(0)
    }

    /** Record the streamingEnabled baked into the most recent session. */
    public open fun recordSessionApplied() {
        prefs.edit().putBoolean(K_LAST_STREAMING, _streamingEnabled.value).apply()
        _lastAppliedStreamingEnabled.value = _streamingEnabled.value
    }

    /** Build a [Configuration] from the current knobs + the global API key. */
    public open fun buildConfiguration(): Configuration {
        val base = PolyMessaging.currentConfig()
        return Configuration(
            apiKey = base.apiKey,
            environment = resolvedEnvironment(),
            hostIdentifier = hostIdentifier ?: base.hostIdentifier,
            streamingEnabled = _streamingEnabled.value,
            logLevel = _logLevel.value,
            // Use the override knob (knob > 0) else null (SDK default) — does NOT inherit base here.
            heartbeatIntervalSeconds = _heartbeatIntervalSeconds.value.takeIf { it > 0 },
            sessionTimeoutSeconds = _sessionTimeoutSeconds.value.takeIf { it > 0 },
            maxReconnectAttempts = _maxReconnectAttempts.value.takeIf { it > 0 },
        )
    }

    private companion object {
        const val K_ENV = "environment_kind"
        const val K_CLUSTER = "cluster_name"
        const val K_REST = "custom_rest_url"
        const val K_WS = "custom_ws_url"
        const val K_STREAMING = "streaming_enabled"
        const val K_LOG = "log_level"
        const val K_TIMEOUT = "session_timeout_seconds"
        const val K_HEARTBEAT = "heartbeat_interval_seconds"
        const val K_MAXRECONNECT = "max_reconnect_attempts"
        const val K_STRIP = "show_debug_strip"
        const val K_TS = "show_message_timestamps"
        const val K_LAST_STREAMING = "last_applied_streaming_enabled"
    }
}
