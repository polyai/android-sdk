// Copyright PolyAI Limited

package ai.poly.voice

import ai.poly.messaging.Configuration
import ai.poly.messaging.PolyError
import ai.poly.messaging.PolyMessaging
import ai.poly.messaging.internal.PolyVoiceInternalApi
import ai.poly.voice.internal.VoiceHosts
import ai.poly.voice.internal.adapters.AndroidAudioControl
import ai.poly.voice.internal.adapters.AndroidLogLogger
import ai.poly.voice.internal.adapters.AndroidWebRtcPeer
import ai.poly.voice.internal.adapters.OkHttpBridgeApi
import ai.poly.voice.internal.adapters.OkHttpEventsTransport
import ai.poly.voice.internal.adapters.OkHttpVoiceRestApi
import ai.poly.voice.internal.adapters.OkHttpVoiceSessionLink
import ai.poly.voice.internal.services.BridgeCallCoordinator
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Entry point for WebRTC voice calling — the `ai.poly:voice` companion to `ai.poly:messaging`.
 *
 * ```kotlin
 * // At launch — sets both tokens once (webrtcToken is only needed for voice):
 * PolyMessaging.initialize(context, Configuration(apiKey = "…", webrtcToken = "…"))
 *
 * // Elsewhere — no config to pass, same pattern as PolyMessaging.chat()/voice():
 * val call = PolyVoice.call(context)
 * // observe call.state for Connected / Failed
 * call.start() // after the RECORD_AUDIO runtime permission is granted
 * ```
 *
 * Need a different connector than the one `initialize(...)` set? Pass a `Configuration` explicitly
 * instead: `call(context, config, options)`.
 *
 * Reuses the messaging `Configuration` (api key, environment, host identifier, log level). Each call
 * is self-contained — it creates its own session, independent of any active chat.
 */
public object PolyVoice {

    /**
     * Build a `VoiceCall` for the given `config`. Does not start it — observe `VoiceCall.state` and
     * call `VoiceCall.start`.
     *
     * @param options call options. The web calling token comes from `config.webrtcToken`.
     *   Defaults to `VoiceOptions()`, so `call(context, config)` alone works when the token is set.
     *   With `Environment.Custom`, also set
     *   `VoiceOptions.signalingHost` or this throws `PolyError.InvalidConfiguration`.
     * @throws PolyError.InvalidConfiguration if `apiKey` or `webrtcToken` is blank, or the
     *   environment is `Environment.Custom` without
     *   `VoiceOptions.signalingHost`.
     */
    @JvmStatic
    @JvmOverloads
    public fun call(
        context: Context,
        config: Configuration,
        options: VoiceOptions = VoiceOptions(),
    ): VoiceCall {
        if (config.apiKey.isBlank()) throw PolyError.InvalidConfiguration("apiKey must not be blank")
        val bridgeToken = config.webrtcToken
        if (bridgeToken.isNullOrBlank()) {
            throw PolyError.InvalidConfiguration("Configuration.webrtcToken must not be blank")
        }

        val app = context.applicationContext
        val logger = AndroidLogLogger(config.logLevel)
        val hosts = VoiceHosts(config.environment, options.signalingHost)
        val hostId = config.hostIdentifier ?: app.packageName
        // device_type mirrors the chat SDK's detection (smallestScreenWidthDp >= 600 ⇒ tablet) so voice
        // and chat report the same dimension on session create.
        val deviceType = if (app.resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "mobile"

        val restApi = OkHttpVoiceRestApi(
            restBaseUrl = hosts.restBaseUrl(),
            apiKey = config.apiKey,
            hostIdentifier = hostId,
            deviceType = deviceType,
            version = BuildConfig.VERSION_NAME,
            logger = logger,
        )
        val sessionLink = OkHttpVoiceSessionLink(
            wsUrl = { sessionId, token -> hosts.voiceSessionWsUrl(sessionId, token) },
            logger = logger,
        )
        val events = OkHttpEventsTransport(logger)
        val webrtc = AndroidWebRtcPeer(app, logger)
        val audioControl = AndroidAudioControl(app, logger, useSpeakerphone = options.speakerphone)

        // Single-threaded confinement: the coordinator's collectors, timers, and pipeline all run on
        // this one thread, so its mutable state needs no locks.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

        val coordinator = BridgeCallCoordinator(
            bridgeToken = bridgeToken,
            restApi = restApi,
            bridge = OkHttpBridgeApi(
                baseUrl = hosts.bridgeBaseUrl(),
                authToken = bridgeToken,
                logger = logger,
            ),
            sessionLink = sessionLink,
            events = events,
            webrtc = webrtc,
            scope = scope,
            logger = logger,
            audioControl = audioControl,
        )

        return VoiceCall(
            coordinator = coordinator,
            scope = scope,
            permissionGranted = {
                app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            },
        )
    }

    /**
     * Same as `call(context, config, options)`, but reads the `Configuration` from
     * `PolyMessaging.initialize(context, config)` instead of taking one — the
     * `PolyMessaging.chat()` / `PolyMessaging.voice()` pattern. Requires `initialize` to have been
     * called first (crashes otherwise, same contract as those two), with
     * `Configuration.webrtcToken` set.
     */
    @OptIn(PolyVoiceInternalApi::class)
    @JvmStatic
    @JvmOverloads
    public fun call(context: Context, options: VoiceOptions = VoiceOptions()): VoiceCall =
        call(context, PolyMessaging.currentConfig(), options)
}
