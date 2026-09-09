// Copyright PolyAI Limited

package ai.poly.voice.internal.adapters

import ai.poly.messaging.PolyError
import ai.poly.messaging.PolyLogger
import ai.poly.voice.internal.log.d
import ai.poly.voice.internal.log.w
import ai.poly.voice.internal.ports.BridgeApi
import ai.poly.voice.internal.protocol.BridgeProtocol
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OkHttp-backed [BridgeApi].
 *
 * The bridge returns credential **paths**, not absolute URLs, so every one is resolved against
 * [baseUrl] — never against anything else.
 *
 * @param baseUrl `https://webrtc-bridge.<cluster>/`.
 * @param authToken the connector's WebRTC token: the Bearer credential on provision. The bridge
 *   resolves account, project and client environment from it, so nothing else identifies the caller.
 */
internal class OkHttpBridgeApi(
    private val baseUrl: String,
    private val authToken: String,
    private val logger: PolyLogger,
    private val client: OkHttpClient = OkHttpClient(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : BridgeApi {

    override suspend fun provision(): BridgeProtocol.Provision = withContext(io) {
        val url = resolve("/api/v1/call")
            ?: throw PolyError.Voice.SignalingFailed("invalid bridge base URL: $baseUrl")
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $authToken")
            .post(BridgeProtocol.provisionBody().toRequestBody(JSON))
            .build()
        val body = execute(req, "provision")
        val provision = BridgeProtocol.parseProvision(body)
            ?: throw PolyError.Voice.SignalingFailed("bridge returned an unreadable provision response")
        logger.d(
            "[voice] bridge call provisioned",
            mapOf("callId" to provision.callId, "provider" to provision.credentials.provider),
        )
        provision
    }

    override suspend fun sendOffer(
        provision: BridgeProtocol.Provision,
        sdp: String,
        mid: String,
    ): String = withContext(io) {
        val req = callRequest(provision, provision.credentials.connectPath, "sdp")
            .post(BridgeProtocol.offerBody(sdp, mid).toRequestBody(JSON))
            .build()
        BridgeProtocol.parseSdp(execute(req, "sdp"))
            ?: throw PolyError.Voice.SignalingFailed("bridge returned no answer SDP")
    }

    override suspend fun pullAgentTrack(provision: BridgeProtocol.Provision): String = withContext(io) {
        val path = provision.credentials.pullPath
            ?: throw PolyError.Voice.SignalingFailed("bridge offered no agent-track pull URL")
        val req = callRequest(provision, path, "pull")
            .post("{}".toRequestBody(JSON))
            .build()
        BridgeProtocol.parseSdp(execute(req, "pull"))
            ?: throw PolyError.Voice.SignalingFailed("bridge returned no renegotiation offer")
    }

    override suspend fun renegotiate(provision: BridgeProtocol.Provision, answerSdp: String) {
        withContext(io) {
            val path = provision.credentials.renegotiatePath
                ?: throw PolyError.Voice.SignalingFailed("bridge offered no renegotiate URL")
            val req = callRequest(provision, path, "renegotiate")
                .post(BridgeProtocol.sdpBody(answerSdp).toRequestBody(JSON))
                .build()
            execute(req, "renegotiate")
        }
    }

    override suspend fun deleteCall(provision: BridgeProtocol.Provision) {
        withContext(io) {
            val url = resolve("/api/v1/call/${provision.callId}") ?: return@withContext
            val builder = Request.Builder().url(url).delete()
            provision.credentials.token?.let { builder.header(BridgeProtocol.CALL_TOKEN_HEADER, it) }
            // Best-effort: the bridge also reaps a session when its events socket drops, so a lost
            // DELETE is not a leak.
            runCatching { execute(builder.build(), "delete") }
                .onFailure { logger.w("[voice] bridge delete failed", mapOf("error" to (it.message ?: ""))) }
        }
    }

    override fun eventsUrl(provision: BridgeProtocol.Provision): String? {
        val path = provision.credentials.eventsPath ?: return null
        val url = resolve(path) ?: return null
        return url.newBuilder().scheme(if (url.scheme == "http") "http" else "https").build()
            .toString()
            .replaceFirst(Regex("^http"), "ws")
    }

    // ── internals ─────────────────────────────────────────────────

    private fun resolve(path: String): HttpUrl? =
        baseUrl.toHttpUrlOrNull()?.resolve(path)

    private fun callRequest(
        provision: BridgeProtocol.Provision,
        path: String,
        route: String,
    ): Request.Builder {
        val url = resolve(path)
            ?: throw PolyError.Voice.SignalingFailed("bridge returned an unusable $route URL")
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
        provision.credentials.token?.let { builder.header(BridgeProtocol.CALL_TOKEN_HEADER, it) }
        return builder
    }

    private fun execute(request: Request, route: String): String {
        val response = runCatching { client.newCall(request).execute() }
            .getOrElse { throw PolyError.Voice.SignalingFailed("bridge $route request failed: ${it.message}") }
        response.use {
            val body = it.body?.string().orEmpty()
            // 401 is the one status worth naming: the token was rejected, the network was fine.
            if (it.code == 401) {
                throw PolyError.Voice.SignalingFailed("bridge $route rejected the call credentials (401)")
            }
            if (it.code !in 200..299) {
                throw PolyError.Voice.SignalingFailed("bridge $route failed (${it.code})")
            }
            return body
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
