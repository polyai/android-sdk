// Copyright PolyAI Limited

package ai.poly.messaging.internal.helpers

import ai.poly.messaging.Environment
import kotlin.math.min
import kotlin.math.pow

/**
 * An UNRECOVERABLE programmer-error abort. Throws a [PolyFatalError] (a JVM `Error`, not an
 * `Exception`) so it propagates past ordinary `catch (e: Exception)` and crashes the process,
 * rather than being a routinely-catchable exception.
 */
internal fun polyFatalError(message: String): Nothing = throw PolyFatalError(message)

internal class PolyFatalError(message: String) : Error(message)

/** Injectable clock so timeout/expiry logic is testable. */
internal fun interface Clock {
    fun nowMillis(): Long
    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}

/**
 * Reconnect backoff policy: `min(maxSeconds, 2^attempt)` with ±jitter.
 * Injectable so tests can force a fixed/near-zero delay via the
 * `reconnectBackoffOverrideSeconds` test seam.
 */
internal class Backoff(
    private val maxSeconds: Double = 30.0,
    private val jitterFraction: Double = 0.20,
    private val networkPollSeconds: Double = 10.0,
    private val overrideSeconds: Double? = null,
    private val random: () -> Double = { Math.random() },
) {
    /** Delay in millis for [attempt] (1-based). [networkClose] uses a fixed poll interval. */
    fun delayMillis(attempt: Int, networkClose: Boolean): Long {
        overrideSeconds?.let { return (it * 1000).toLong() }
        if (networkClose) return (networkPollSeconds * 1000).toLong()
        val base = min(maxSeconds, 2.0.pow(attempt.coerceAtLeast(0)))
        val jitter = 1.0 + (random() * 2 - 1) * jitterFraction // [1-j, 1+j]
        return (base * jitter * 1000).toLong()
    }
}

/** Resolves a backend [Environment] to its REST + WS base URLs. */
internal object EnvironmentUrls {
    // Verified against the live V2 messaging backend (webchat env): REST base ends in
    // `/api/v1` (paths /access-token, /sessions); WS endpoint is `<host>/ws`. Cluster
    // names accept [a-z0-9-].
    private const val REST_PATH = "/api/v1"
    private const val WS_PATH = "/ws"
    // Require a leading alphanumeric (reject leading/all-hyphen names → invalid hostnames).
    private val clusterPattern = Regex("^[a-z0-9][a-z0-9-]*$")

    fun restBaseUrl(env: Environment): String = when (env) {
        is Environment.US -> "https://messaging.us-1.poly.ai$REST_PATH"
        is Environment.UK -> "https://messaging.uk-1.poly.ai$REST_PATH"
        is Environment.EUW -> "https://messaging.euw-1.poly.ai$REST_PATH"
        is Environment.Cluster -> "https://messaging.${cluster(env.name)}.poly.ai$REST_PATH"
        is Environment.Custom -> env.restBaseUrl.toString().trimEnd('/')
    }

    /** WS endpoint, already including the `/ws` path (e.g. wss://messaging.dev.poly.ai/ws). */
    fun wsBaseUrl(env: Environment): String = when (env) {
        is Environment.US -> "wss://messaging.us-1.poly.ai$WS_PATH"
        is Environment.UK -> "wss://messaging.uk-1.poly.ai$WS_PATH"
        is Environment.EUW -> "wss://messaging.euw-1.poly.ai$WS_PATH"
        is Environment.Cluster -> "wss://messaging.${cluster(env.name)}.poly.ai$WS_PATH"
        is Environment.Custom -> env.wsBaseUrl.toString().trimEnd('/')
    }

    private fun cluster(name: String): String {
        if (!clusterPattern.matches(name)) polyFatalError("PolyMessaging: invalid cluster name '$name' — use lowercase alphanumeric with hyphens (e.g. \"us-1\")")
        return name
    }
}

/**
 * Structural JWT validation (3 parts, base64url payload, `exp` check with skew). No
 * signature verification — the SDK doesn't hold the signing key.
 * A missing `exp` is treated as non-expiring (matches web).
 */
internal object JwtValidator {
    private const val CLOCK_SKEW_SECONDS = 5L

    fun isStructurallyValid(token: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val parts = token.split(".")
        if (parts.size != 3) return false
        val payloadJson = runCatching { base64UrlDecodeToString(parts[1]) }.getOrNull() ?: return false
        val obj = runCatching { org.json.JSONObject(payloadJson) }.getOrNull() ?: return false
        if (!obj.has("exp")) return true // non-expiring
        // Treat a non-numeric exp (e.g. a string) as malformed → invalid.
        val expVal = obj.opt("exp") as? Number ?: return false
        // Keep FRACTIONAL precision on both exp and now — don't truncate to Long.
        val expSeconds = expVal.toDouble()
        if (expSeconds <= 0) return false
        return expSeconds + CLOCK_SKEW_SECONDS > nowMillis / 1000.0
    }

    // Pure-JVM base64url decode (no android.util.Base64 / java.util.Base64), so it works at
    // minSdk 24 AND in plain JVM unit tests.
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private fun base64UrlDecodeToString(s: String): String {
        val cleaned = s.replace('-', '+').replace('_', '/').filter { it != '=' && it != '\n' && it != '\r' }
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in cleaned) {
            val v = ALPHABET.indexOf(c)
            // Use non-ignoring decoding: ANY invalid char fails the whole decode
            // (→ isStructurallyValid false), rather than silently skipping it.
            if (v < 0) throw IllegalArgumentException("invalid base64url char")
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
