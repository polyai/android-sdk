// Copyright PolyAI Limited

package ai.poly.messaging.internal.wire

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Safe, null-tolerant accessors over `org.json` — the dependency-free wrapper through which
 * all wire JSON goes.
 */
// `self[key] as? String`: null only when absent/JSON-null or a non-string value;
// a present empty string "" is preserved (so the id/timestamp drop-guard and optional fields work).
internal fun JSONObject.stringOrNull(key: String): String? =
    if (isNull(key) || !has(key)) null else opt(key) as? String

// No string→number / string→bool coercion (types are kept strict):
// a numeric/boolean STRING value yields null/default rather than being parsed.
internal fun JSONObject.intOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) (opt(key) as? Number)?.toInt() else null

internal fun JSONObject.boolOr(key: String, default: Boolean): Boolean =
    if (has(key) && !isNull(key)) (opt(key) as? Boolean) ?: default else default

internal fun JSONObject.objOrNull(key: String): JSONObject? =
    if (has(key) && !isNull(key)) optJSONObject(key) else null

internal fun JSONObject.arrayOrNull(key: String): JSONArray? =
    if (has(key) && !isNull(key)) optJSONArray(key) else null

// An empty string is not a valid URL; filter blanks before parsing.
internal fun JSONObject.uriOrNull(key: String): URI? =
    stringOrNull(key)?.takeIf { it.isNotBlank() }?.let { runCatching { URI(it) }.getOrNull() }

/** First non-null string among the candidate keys (tolerates legacy field-name variants). */
internal fun JSONObject.firstString(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { stringOrNull(it) }

internal fun JSONObject.firstInt(vararg keys: String): Int? =
    keys.firstNotNullOfOrNull { intOrNull(it) }

// All-or-nothing: if ANY element is not a JSON object (a string/number/null), the whole array is
// treated as absent (null). Returns null if any element is non-object so callers can drop the entire list.
internal fun JSONArray.objectsStrictOrNull(): List<JSONObject>? {
    val out = ArrayList<JSONObject>(length())
    for (i in 0 until length()) out += (optJSONObject(i) ?: return null)
    return out
}

private val isoWithMillis = ThreadLocal.withInitial {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
}
private val isoPlain = ThreadLocal.withInitial {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
}

private val fractionalSeconds = Regex("""\.(\d+)""")

/** Parse an ISO-8601 timestamp to epoch millis (UTC), tolerating fractional seconds; falls back to now. */
internal fun parseIso8601(value: String?, nowMillis: Long): Long {
    if (value.isNullOrEmpty()) return nowMillis
    // An ISO-8601 fractional second is a TRUE fraction (".5" → 500 ms, ".123456" → ~123 ms), but
    // SimpleDateFormat's "SSS" reads it as a literal millisecond integer (".5" → 5 ms, ".123456" →
    // 123456 ms ≈ +2 min). Normalize the fractional component to exactly 3 digits (right-pad short,
    // truncate long) so parsing is correct everywhere.
    val normalized = fractionalSeconds.replace(value.replace("Z", "+00:00")) { m ->
        "." + (m.groupValues[1] + "000").substring(0, 3)
    }
    isoWithMillis.get()?.let { fmt -> runCatching { return fmt.parse(normalized)!!.time }.getOrNull() }
    isoPlain.get()?.let { fmt -> runCatching { return fmt.parse(normalized)!!.time }.getOrNull() }
    return nowMillis
}
