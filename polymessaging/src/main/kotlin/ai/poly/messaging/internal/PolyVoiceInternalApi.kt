// Copyright PolyAI Limited

package ai.poly.messaging.internal

/**
 * Marks API that crosses the `:polymessaging` / `:polyvoice` module boundary without being part of
 * the supported public contract — the Kotlin equivalent of the iOS SDK's `@_spi(PolyVoice)`.
 *
 * Kotlin's `internal` is single-module visibility (a single Gradle compilation), so it can't reach
 * across separate Gradle modules the way Swift's `@_spi` reaches across a single package's targets.
 * The symbols this marks must therefore be `public`, but this annotation — plus living in
 * `internal/`, which CONTRIBUTING.md already carves out as outside the public contract — requires
 * an explicit [OptIn] at every call site, so a call site reads exactly as unsupported as it is.
 * `:polyvoice` opts in; nothing else should.
 */
@RequiresOptIn(
    message = "Crosses the :polymessaging/:polyvoice module boundary and is not part of the public " +
        "API — only :polyvoice may opt in.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
public annotation class PolyVoiceInternalApi
