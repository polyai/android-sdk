// Copyright PolyAI Limited

package ai.poly.voice.internal.log

import ai.poly.messaging.LogLevel
import ai.poly.messaging.PolyLogger

/**
 * Internal level-shortcut helpers over the messaging SDK's `PolyLogger` (reused so voice logs flow
 * through the host's existing logger). Keep messages PII-free — NEVER log the API key, access
 * token, or session identifiers.
 */
internal fun PolyLogger.d(message: String, metadata: Map<String, Any?>? = null): Unit = log(LogLevel.DEBUG, message, metadata)
internal fun PolyLogger.i(message: String, metadata: Map<String, Any?>? = null): Unit = log(LogLevel.INFO, message, metadata)
internal fun PolyLogger.w(message: String, metadata: Map<String, Any?>? = null): Unit = log(LogLevel.WARN, message, metadata)
internal fun PolyLogger.e(message: String, metadata: Map<String, Any?>? = null): Unit = log(LogLevel.ERROR, message, metadata)
