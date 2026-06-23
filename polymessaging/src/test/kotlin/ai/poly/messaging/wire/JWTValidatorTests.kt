// Copyright PolyAI Limited

package ai.poly.messaging

import ai.poly.messaging.internal.helpers.JwtValidator
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Structural JWT validation tests not already covered by `WireTest.jwt_structuralValidity`.
 * Pure functions: no fakes, no virtual time; expiry is exercised via the injectable `nowMillis`
 * parameter (deterministic).
 */
class JWTValidatorTests {

    // Token expiring 2s ago is within the 5s CLOCK_SKEW_SECONDS window.
    @Test
    fun jwt_clockSkewTolerance_expiryWithinFiveSecondsStillValid() {
        val nowMillis = 1_700_000_000_000L
        val nowSeconds = nowMillis / 1000L
        // exp = now - 2s → within the 5s skew window → still valid.
        val nearPast = "eyJhbGciOiJub25lIn0." + base64Url("""{"exp":${nowSeconds - 2}}""") + ".sig"
        assertTrue(JwtValidator.isStructurallyValid(nearPast, nowMillis = nowMillis))
        // exp = now - 6s → outside the 5s skew window → invalid.
        val pastSkew = "eyJhbGciOiJub25lIn0." + base64Url("""{"exp":${nowSeconds - 6}}""") + ".sig"
        assertFalse(JwtValidator.isStructurallyValid(pastSkew, nowMillis = nowMillis))
    }

    // Non-base64url payload chars fail the whole decode (the decoder throws on any invalid
    // char), exercising the runCatching base64UrlDecodeToString → false path.
    @Test
    fun jwt_nonBase64UrlPayloadRejected() {
        assertFalse(JwtValidator.isStructurallyValid("header.!!!.signature"))
    }
}

// Same encoding as WireTest's private base64Url helper
// (private top-level = file-scoped, so no cross-file collision with WireTest's member helper).
private fun base64Url(s: String): String {
    val std = java.util.Base64.getEncoder().encodeToString(s.toByteArray())
    return std.replace('+', '-').replace('/', '_').trimEnd('=')
}
