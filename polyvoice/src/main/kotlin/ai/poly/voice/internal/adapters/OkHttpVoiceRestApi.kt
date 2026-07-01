// Copyright PolyAI Limited

package ai.poly.voice.internal.adapters

import ai.poly.messaging.PolyError
import ai.poly.messaging.PolyLogger
import ai.poly.voice.internal.IceServer
import ai.poly.voice.internal.log.d
import ai.poly.voice.internal.log.w
import ai.poly.voice.internal.ports.VoiceRestApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID

/**
 * Self-contained REST auth for a call: `POST /access-token` → `POST /sessions`, plus the gateway's
 * `GET /ice-servers`. The header conventions mirror the chat client's `OkHttpRestApi` so a call
 * authenticates exactly like a chat session. Kept intentionally lighter than the chat client (no
 * token cache / retry ladder) — a call is short-lived and fetches fresh credentials each time.
 *
 * @param restBaseUrl messaging REST base (ends in `/api/v1`).
 * @param iceServersUrl builds the gateway ICE-servers URL for a given access token.
 * @param deviceType the `device_type` reported on session create (`mobile`/`tablet`), matching chat.
 */
internal class OkHttpVoiceRestApi(
    private val restBaseUrl: String,
    private val iceServersUrl: (token: String) -> String,
    private val apiKey: String,
    private val hostIdentifier: String,
    private val deviceType: String,
    private val version: String,
    private val logger: PolyLogger,
    private val client: OkHttpClient = OkHttpClient(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : VoiceRestApi {

    override suspend fun obtainAccessToken(): String = withContext(io) {
        val req = Request.Builder()
            .url("$restBaseUrl/access-token")
            .header("Accept", "application/json")
            .header("X-Token", apiKey)
            .header("X-Host", hostIdentifier)
            .header("X-Polyai-Correlation-Id", UUID.randomUUID().toString())
            .post("{}".toRequestBody(JSON))
            .build()
        val (code, body) = execute(req)
        if (code == 401 || code == 403) throw PolyError.Auth.Unauthorized
        if (code !in 200..299) throw PolyError.Auth.TokenAcquisitionFailed
        val token = runCatching { JSONObject(body) }.getOrNull()
            ?.firstString("access_token", "accessToken")?.takeIf { it.isNotEmpty() }
            ?: throw PolyError.Auth.TokenAcquisitionFailed
        logger.d("[voice] access token obtained")
        token
    }

    override suspend fun createSession(token: String): String = withContext(io) {
        val payload = JSONObject()
            .put("streaming_enabled", true)
            .put("platform", "android")
            .put("device_type", deviceType)
        val req = Request.Builder()
            .url("$restBaseUrl/sessions")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .header("User-Agent", "PolyVoice-Android/$version (Android; ${android.os.Build.VERSION.RELEASE})")
            .header("X-Polyai-Correlation-Id", UUID.randomUUID().toString())
            .post(payload.toString().toRequestBody(JSON))
            .build()
        val (code, body) = execute(req)
        if (code == 401 || code == 403) throw PolyError.Auth.Unauthorized
        if (code !in 200..299) throw PolyError.Voice.SignalingFailed("session creation failed (HTTP $code)")
        val sessionId = runCatching { JSONObject(body) }.getOrNull()
            ?.firstString("session_id", "sessionId", "id")?.takeIf { it.isNotEmpty() }
            ?: throw PolyError.Voice.SignalingFailed("session creation returned no session id")
        logger.d("[voice] session created")
        sessionId
    }

    override suspend fun fetchIceServers(token: String): List<IceServer> = withContext(io) {
        // Best-effort: any failure falls back to public STUN so a call can still connect on open NATs.
        runCatching {
            val req = Request.Builder()
                .url(iceServersUrl(token))
                .header("Accept", "application/json")
                .get()
                .build()
            val (code, body) = execute(req)
            if (code !in 200..299) return@runCatching IceServer.DEFAULT
            parseIceServers(body).ifEmpty { IceServer.DEFAULT }
        }.getOrElse {
            logger.w("[voice] ice-servers fetch failed; falling back to STUN", mapOf("error" to (it.message ?: "")))
            IceServer.DEFAULT
        }
    }

    private fun parseIceServers(body: String): List<IceServer> {
        val arr = runCatching { JSONObject(body).optJSONArray("iceServers") }.getOrNull() ?: return emptyList()
        val out = ArrayList<IceServer>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val urls = when {
                obj.optJSONArray("urls") != null -> {
                    val u = obj.getJSONArray("urls")
                    (0 until u.length()).mapNotNull { u.optString(it).takeIf { s -> s.isNotEmpty() } }
                }
                obj.optString("urls").isNotEmpty() -> listOf(obj.getString("urls"))
                else -> emptyList()
            }
            if (urls.isEmpty()) continue
            out += IceServer(
                urls = urls,
                username = obj.optString("username").takeIf { it.isNotEmpty() },
                credential = obj.optString("credential").takeIf { it.isNotEmpty() },
            )
        }
        return out
    }

    private data class Http(val code: Int, val body: String)

    private fun execute(req: Request): Http {
        client.newCall(req).execute().use { resp ->
            return Http(resp.code, resp.body?.string().orEmpty())
        }
    }

    /** First non-empty string among candidate keys (tolerates field-name variants). */
    private fun JSONObject.firstString(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> if (has(k) && !isNull(k)) optString(k).takeIf { it.isNotEmpty() } else null }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
