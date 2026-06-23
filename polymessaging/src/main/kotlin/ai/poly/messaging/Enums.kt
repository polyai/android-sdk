// Copyright PolyAI Limited

package ai.poly.messaging

/**
 * The client platform reported on session create. Always [ANDROID] for this SDK
 * (orthogonal to device type — see the `device_type` dimension).
 */
public enum class Platform(public val raw: String) {
    ANDROID("android"),
}

/**
 * Logging verbosity. Ordered so `WARN > ERROR > NONE` etc. (a comparable
 * Int enum). Fully Java-ergonomic (`values()`, `switch`).
 */
public enum class LogLevel(public val level: Int) {
    NONE(0),
    ERROR(1),
    WARN(2),
    INFO(3),
    DEBUG(4),
    ;

    public companion object {
        /** Lookup by raw level; returns null if out of range. */
        @JvmStatic
        public fun fromLevel(level: Int): LogLevel? = entries.firstOrNull { it.level == level }
    }
}
