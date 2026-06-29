// Copyright PolyAI Limited

package ai.poly.examples.playground.compose

import androidx.compose.ui.graphics.Color

/**
 * System color constants (light appearance) used to style the bubbles, pills and banner, plus the
 * secondary label color.
 */
internal val SystemGray = Color(0xFF8E8E93)
internal val SystemGray2 = Color(0xFFAEAEB2)
internal val SystemGray3 = Color(0xFFC7C7CC)
internal val SystemGray4 = Color(0xFFD1D1D6)
internal val SystemGray5 = Color(0xFFE5E5EA)
internal val SystemGray6 = Color(0xFFF2F2F7)
internal val SystemYellow = Color(0xFFFFCC00)
internal val SystemBlue = Color(0xFF007AFF)
internal val SystemRed = Color(0xFFFF3B30)
internal val SystemOrange = Color(0xFFFF9500)
internal val SystemGreen = Color(0xFF34C759)

/** Secondary label — black at 60% in light mode. */
internal val SecondaryLabel = Color(0xFF3C3C43).copy(alpha = 0.6f)

/** Material backing the composer / footer — a near-opaque system chrome surface. */
internal val BarBackground = Color(0xFFF9F9F9)
