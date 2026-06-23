// Copyright PolyAI Limited

package ai.poly.messaging.internal.adapters

import ai.poly.messaging.PolyError
import ai.poly.messaging.PolyLogger
import ai.poly.messaging.SessionErrorCode
import ai.poly.messaging.internal.ports.AccessToken
import ai.poly.messaging.internal.ports.CreatedSession
import ai.poly.messaging.internal.ports.RestApiPort
import ai.poly.messaging.internal.ports.SessionCreateContext
import ai.poly.messaging.internal.helpers.JwtValidator
import ai.poly.messaging.internal.helpers.d
import ai.poly.messaging.internal.helpers.e
import ai.poly.messaging.internal.helpers.w
import ai.poly.messaging.internal.wire.firstInt
import ai.poly.messaging.internal.wire.firstString
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/**
 * Two-step REST auth (`POST /api/v1/access-token` → `POST /api/v1/sessions`) over OkHttp,
 * including debug logging: every request is logged with method/url, ALL request headers, and
 * the request/response bodies (the curl-equivalent), with the error body surfaced at warn.
 * Application-level retry with 1s backoff, `Retry-After`-aware 429, 4xx-terminal vs 5xx-retry,
 * error-body → [SessionErrorCode], a token cache with a 300s expiring-soon refresh, and
 * per-request timing.
 */
internal class OkHttpRestApi(
    private val baseUrl: String,
    private val apiKey: String,
    private val hostIdentifier: String,
    private val version: String,
    private val logger: PolyLogger,
    private val client: OkHttpClient = OkHttpClient(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : RestApiPort {

    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedExpiresAtMillis: Long = 0L

    override suspend fun obtainAccessToken(): AccessToken = withContext(io) {
        // Clear the cached token FIRST so a failed in-flight fetch can't leave a stale token cached.
        cachedToken = null
        cachedExpiresAtMillis = 0L
        val req = Request.Builder()
            .url("$baseUrl/access-token")
            .header("Accept", "application/json")
            .header("X-Token", apiKey)
            .header("X-Host", hostIdentifier)
            .header("X-Polyai-Correlation-Id", UUID.randomUUID().toString())
            .post("{}".toRequestBody(JSON))
            .build()
        val resp = execute(req, "access-token")
        if (resp.code == 429) throw PolyError.Transport.NetworkError("HTTP 429 rate limited") // exhausted
        if (resp.code !in 200..299) {
            // Client-error mapping: a raw 401/403 → unauthorized; a parsed connector-validation code
            // → sessionCreationFailed(code) (carrying the SPECIFIC code, not a boolean); anything else →
            // tokenAcquisitionFailed. SessionService derives hasInvalidApiKey from the code's set membership.
            if (resp.code == 401 || resp.code == 403) throw PolyError.Auth.Unauthorized
            val message = runCatching { JSONObject(resp.body) }.getOrNull()?.firstString("message", "error")
            val code = message?.let { m -> SessionErrorCode.entries.firstOrNull { it.raw == m } }
            throw if (code != null) PolyError.Session.SessionCreationFailed(code) else PolyError.Auth.TokenAcquisitionFailed
        }
        // The token is validated structurally only in ensureAccessToken, not at acquisition.
        // An unparseable 2xx body is a TRANSPORT/protocol error, not auth.
        val json = runCatching { JSONObject(resp.body) }.getOrNull() ?: throw PolyError.Transport.ProtocolError("Invalid JSON response")
        // Reject a present-but-empty access_token with tokenAcquisitionFailed.
        val token = json.firstString("access_token", "accessToken")?.takeIf { it.isNotEmpty() }
            ?: throw PolyError.Auth.TokenAcquisitionFailed
        val expiresIn = json.firstInt("expires_in", "expiresIn") ?: 3600
        val expiresAt = System.currentTimeMillis() + expiresIn * 1000L
        cachedToken = token
        cachedExpiresAtMillis = expiresAt
        logger.d("Access token obtained")
        AccessToken(token, expiresAt)
    }

    override suspend fun ensureAccessToken(): String {
        val token = cachedToken
        if (token != null &&
            JwtValidator.isStructurallyValid(token, System.currentTimeMillis()) &&
            System.currentTimeMillis() < cachedExpiresAtMillis - REFRESH_THRESHOLD_MS
        ) {
            return token
        }
        val refreshed = obtainAccessToken().token
        // Re-validate the freshly obtained token: a malformed JWT is logged at
        // ERROR and rejected (tokenAcquisitionFailed), not silently accepted.
        if (!JwtValidator.isStructurallyValid(refreshed, System.currentTimeMillis())) {
            logger.e("obtainAccessToken returned malformed JWT")
            throw PolyError.Auth.TokenAcquisitionFailed
        }
        return refreshed
    }

    override suspend fun createSession(token: String, context: SessionCreateContext): CreatedSession = withContext(io) {
        val payload = JSONObject()
            .put("streaming_enabled", context.streamingEnabled)
            .put("platform", context.platform)
            .put("device_type", context.deviceType)
        // Authenticate /sessions with the Bearer token only — no X-Host header here.
        val req = Request.Builder()
            .url("$baseUrl/sessions")
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .header("User-Agent", "PolyMessaging-Android/$version (Android; ${android.os.Build.VERSION.RELEASE})")
            .header("X-Polyai-Correlation-Id", UUID.randomUUID().toString())
            .post(payload.toString().toRequestBody(JSON))
            .build()
        val resp = execute(req, "sessions")
        if (resp.code in 200..299) {
            // An unparseable 2xx body is a transport protocol error, thrown BEFORE the session_id
            // check; only a parseable-but-missing/empty session_id is sessionCreationFailed.
            val json = runCatching { JSONObject(resp.body) }.getOrNull()
                ?: throw PolyError.Transport.ProtocolError("Invalid JSON response")
            val sessionId = json.firstString("session_id", "sessionId", "id")?.takeIf { it.isNotEmpty() }
                ?: throw PolyError.Session.SessionCreationFailed(SessionErrorCode.SESSION_CREATION_FAILED)
            logger.d("Session created", mapOf("sessionId" to sessionId))
            return@withContext CreatedSession(sessionId = sessionId, accessToken = token)
        }
        if (resp.code == 429) throw PolyError.Transport.NetworkError("HTTP 429 rate limited") // exhausted
        // Client-error mapping for /sessions: a raw 401/403 → unauthorized; otherwise
        // sessionCreationFailed carrying the parsed connector code (or unknown). SessionService maps the
        // connector codes to hasInvalidApiKey — the adapter no longer collapses them to a boolean here.
        if (resp.code == 401 || resp.code == 403) throw PolyError.Auth.Unauthorized
        val message = runCatching { JSONObject(resp.body) }.getOrNull()?.firstString("message", "error")
        val code = message?.let { m -> SessionErrorCode.entries.firstOrNull { it.raw == m } } ?: SessionErrorCode.UNKNOWN
        throw PolyError.Session.SessionCreationFailed(code)
    }

    // ─── retrying executor with request/response logging ──────────

    private data class Http(val code: Int, val body: String)

    /** Executes with retry: network IOException + 5xx + 429 (Retry-After) retried up to [MAX_RETRIES];
     *  4xx is terminal. Logs each request/response (curl-equivalent). */
    private suspend fun execute(req: Request, endpoint: String): Http {
        var attempt = 0
        while (true) {
            logRequest(req, endpoint, attempt)
            val started = System.nanoTime()
            val response = try {
                client.newCall(req).execute()
            } catch (io: IOException) {
                if (attempt >= MAX_RETRIES) throw networkError(io)
                logger.w("Network error, attempt ${attempt + 1}/${MAX_RETRIES + 1}", mapOf("endpoint" to endpoint, "error" to (io.message ?: "")))
                delay(RETRY_BASE_MS)
                attempt++
                continue
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            val code = response.code
            val retryAfter = response.header("Retry-After")
            val body = response.body?.string().orEmpty()
            response.close()
            logResponse(code, body, endpoint, elapsedMs)

            when {
                code in 200..299 -> return Http(code, body)
                code == 429 -> {
                    // Log the 429 warn on EVERY attempt incl. the final exhausting one.
                    val wait = retryAfterMillis(retryAfter)
                    logger.w("Rate limited (429), retrying after ${wait / 1000}s, attempt ${attempt + 1}/${MAX_RETRIES + 1}", mapOf("endpoint" to endpoint))
                    if (attempt < MAX_RETRIES) { delay(wait); attempt++ } else return Http(code, body)
                }
                code in 500..599 && attempt < MAX_RETRIES -> {
                    logger.w("Server error $code, attempt ${attempt + 1}/${MAX_RETRIES + 1}", mapOf("endpoint" to endpoint))
                    delay(RETRY_BASE_MS)
                    attempt++
                }
                else -> return Http(code, body) // 4xx terminal, or retries exhausted
            }
        }
    }

    // ─── debug logging (logRequest / logResponse / previewBody) ──

    private fun logRequest(req: Request, endpoint: String, attempt: Int) {
        val attemptLabel = if (attempt == 0) "" else " attempt=${attempt + 1}/${MAX_RETRIES + 1}"
        logger.d("→ ${req.method} ${req.url}$attemptLabel", mapOf("endpoint" to endpoint))
        req.headers.toList().sortedBy { it.first }.forEach { (name, value) -> logger.d("    $name: $value") }
        val body = requestBodyString(req)
        if (body.isNotEmpty()) logger.d("    body: ${previewBody(body)}")
    }

    private fun logResponse(code: Int, body: String, endpoint: String, elapsedMs: Long) {
        logger.d("← $code (${elapsedMs}ms)", mapOf("endpoint" to endpoint))
        if (code !in 200..299 && body.isNotEmpty()) {
            // Surface the server's error body at warn so failures are diagnosable without .debug.
            logger.w("HTTP $code body: ${previewBody(body)}", mapOf("endpoint" to endpoint))
        } else if (body.isNotEmpty()) {
            logger.d("    body: ${previewBody(body)}")
        }
    }

    private fun requestBodyString(req: Request): String {
        val body = req.body ?: return ""
        return runCatching { Buffer().also { body.writeTo(it) }.readUtf8() }.getOrDefault("")
    }

    private fun previewBody(s: String): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        if (bytes.size <= LOG_BODY_MAX_BYTES) return s
        return String(bytes, 0, LOG_BODY_MAX_BYTES, Charsets.UTF_8) + "…(${bytes.size} bytes total)"
    }

    private fun retryAfterMillis(header: String?): Long {
        // Clamp ONLY the upper bound — a fractional or 0 Retry-After is honored verbatim;
        // the 5s default applies only when the header is missing/unparseable.
        val seconds = header?.trim()?.toDoubleOrNull() ?: DEFAULT_RETRY_AFTER_SEC.toDouble()
        return (seconds.coerceAtMost(MAX_RETRY_AFTER_SEC.toDouble()) * 1000).toLong()
    }

    private fun networkError(io: IOException): PolyError =
        PolyError.Transport.NetworkError(io.message ?: "network error")

    private companion object {
        val JSON = "application/json".toMediaType() // Content-Type: application/json (no charset)
        const val MAX_RETRIES = 3
        const val RETRY_BASE_MS = 1000L
        const val DEFAULT_RETRY_AFTER_SEC = 5L
        const val MAX_RETRY_AFTER_SEC = 30L
        const val REFRESH_THRESHOLD_MS = 300_000L // refresh a token expiring within 5 min
        const val LOG_BODY_MAX_BYTES = 1024
    }
}
