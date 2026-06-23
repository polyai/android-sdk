// Copyright PolyAI Limited

package ai.poly.messaging

/**
 * The PolyMessaging Android SDK version, kept in lockstep with the published Maven
 * coordinate (`ai.poly:messaging:<version>`) and `CHANGELOG.md`.
 *
 * Surfaced as the `User-Agent` (`PolyMessaging-Android/<version>`) on REST calls.
 *
 * The literal is wired from the Gradle `VERSION_NAME` property via `BuildConfig`,
 * so there is exactly one place to bump.
 */
public val POLY_MESSAGING_VERSION: String = BuildConfig.VERSION_NAME
