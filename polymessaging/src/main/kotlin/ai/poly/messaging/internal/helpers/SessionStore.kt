// Copyright PolyAI Limited

package ai.poly.messaging.internal.helpers

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest

/**
 * Persists the (sessionId, accessToken, activity) tuple for cross-launch resume. Everything goes
 * through Jetpack Security `EncryptedSharedPreferences` (AES-256, backed by the Android
 * Keystore). Keys are namespaced per-API-key (`sha256(apiKey)[0:4]`) so multiple connectors
 * on one device don't collide.
 */
internal class SessionStore(context: Context, apiKey: String) {

    internal data class Stored(
        val sessionId: String,
        val accessToken: String?,
        val lastActivityMillis: Long,
        val tokenExpiresAtMillis: Long,
    )

    private val prefs: SharedPreferences = run {
        val suffix = sha256Hex(apiKey).take(8)
        val name = "$PREFIX$suffix"
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            name,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun save(sessionId: String, accessToken: String, lastActivityMillis: Long, tokenExpiresAtMillis: Long) {
        prefs.edit()
            .putString(KEY_SESSION_ID, sessionId)
            .putString(KEY_TOKEN, accessToken)
            .putLong(KEY_LAST_ACTIVITY, lastActivityMillis)
            .putLong(KEY_TOKEN_EXP, tokenExpiresAtMillis)
            .apply()
    }

    fun load(): Stored? {
        val sessionId = prefs.getString(KEY_SESSION_ID, null) ?: return null
        return Stored(
            sessionId = sessionId,
            accessToken = prefs.getString(KEY_TOKEN, null),
            lastActivityMillis = prefs.getLong(KEY_LAST_ACTIVITY, 0L),
            tokenExpiresAtMillis = prefs.getLong(KEY_TOKEN_EXP, 0L),
        )
    }

    fun touch(nowMillis: Long) {
        prefs.edit().putLong(KEY_LAST_ACTIVITY, nowMillis).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFIX = "ai.poly.messaging."
        const val KEY_SESSION_ID = "session_id"
        const val KEY_TOKEN = "access_token"
        const val KEY_LAST_ACTIVITY = "last_activity"
        const val KEY_TOKEN_EXP = "token_expires_at"

        fun sha256Hex(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
