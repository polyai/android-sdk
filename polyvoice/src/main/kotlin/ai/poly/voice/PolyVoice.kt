// Copyright PolyAI Limited

package ai.poly.voice

import ai.poly.messaging.Configuration
import ai.poly.messaging.PolyError
import ai.poly.voice.internal.VoiceHosts
import ai.poly.voice.internal.adapters.AndroidAudioControl
import ai.poly.voice.internal.adapters.AndroidLogLogger
import ai.poly.voice.internal.adapters.AndroidWebRtcPeer
import ai.poly.voice.internal.adapters.OkHttpSignalingTransport
import ai.poly.voice.internal.adapters.OkHttpVoiceRestApi
import ai.poly.voice.internal.adapters.OkHttpVoiceSessionLink
import ai.poly.voice.internal.services.CallCoordinator
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
 * val call = PolyVoice.call(
 *     context,
 *     Configuration(apiKey = "…", environment = Environment.cluster("dev")),
 *     VoiceOptions(webrtcToken = "…"), // the connector's WebRTC token (distinct from apiKey)
 * )
 * // observe call.state for Connected / Failed
 * call.start() // after the RECORD_AUDIO runtime permission is granted
 * ```
 *
 * Reuses the messaging `Configuration` (api key, environment, host identifier, log level). Each call
 * is self-contained — it creates its own session, independent of any active chat.
 */
public object PolyVoice {

    /**
     * Build a `VoiceCall` for the given `config`. Does not start it — observe `VoiceCall.state` and
     * call `VoiceCall.start`.
     *
     * @param options call options — `VoiceOptions.webrtcToken` (the connector's WebRTC token) is
     *   required. With `Environment.Custom`, also set `VoiceOptions.signalingHost` or this throws
     *   `PolyError.InvalidConfiguration`.
     */
    @JvmStatic
    public fun call(
        context: Context,
        config: Configuration,
        options: VoiceOptions,
    ): VoiceCall {
        if (config.apiKey.isBlank()) throw PolyError.InvalidConfiguration("apiKey must not be blank")
        if (options.webrtcToken.isBlank()) throw PolyError.InvalidConfiguration("VoiceOptions.webrtcToken must not be blank")

        val app = context.applicationContext
        val logger = AndroidLogLogger(config.logLevel)
        val hosts = VoiceHosts(config.environment, options.signalingHost)
        val hostId = config.hostIdentifier ?: app.packageName
        // The WebRTC gateway authenticates the offer + ICE-servers fetch with the connector's WebRTC
        // token — always a distinct value from the API key.
        val gatewayToken = options.webrtcToken
        // device_type mirrors the chat SDK's detection (smallestScreenWidthDp >= 600 ⇒ tablet) so voice
        // and chat report the same dimension on session create.
        val deviceType = if (app.resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "mobile"

        val restApi = OkHttpVoiceRestApi(
            restBaseUrl = hosts.restBaseUrl(),
            iceServersUrl = { token -> hosts.iceServersUrl(token) },
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
        val signaling = OkHttpSignalingTransport(logger)
        val webrtc = AndroidWebRtcPeer(app, logger)
        val audioControl = AndroidAudioControl(app, logger, useSpeakerphone = options.speakerphone)

        // Single-threaded confinement: the coordinator's collectors, timers, and pipeline all run on
        // this one thread, so its mutable state needs no locks.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

        val coordinator = CallCoordinator(
            gatewayToken = gatewayToken,
            restApi = restApi,
            sessionLink = sessionLink,
            signaling = signaling,
            webrtc = webrtc,
            signalingUrl = hosts.signalingUrl(),
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
}
