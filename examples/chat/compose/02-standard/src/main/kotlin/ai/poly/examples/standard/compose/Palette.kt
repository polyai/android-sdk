// Copyright PolyAI Limited

package ai.poly.examples.standard.compose

import androidx.compose.ui.graphics.Color

/**
 * System-color constants (light appearance). The 02-Standard rung styles its bubbles, pills and
 * banner with system grays, yellow, blue, red and a secondary label; these constants define that
 * palette for Compose.
 */
internal val SystemGray = Color(0xFF8E8E93)
internal val SystemGray2 = Color(0xFFAEAEB2)
internal val SystemGray3 = Color(0xFFC7C7CC)
internal val SystemGray5 = Color(0xFFE5E5EA)
internal val SystemGray6 = Color(0xFFF2F2F7)
internal val SystemYellow = Color(0xFFFFCC00)
internal val SystemBlue = Color(0xFF007AFF)
internal val SystemRed = Color(0xFFFF3B30)

/** Secondary label — black at 60% in light mode. */
internal val SecondaryLabel = Color(0xFF3C3C43).copy(alpha = 0.6f)

/** Bar material backing the composer / footer — a near-opaque system chrome surface. */
internal val BarBackground = Color(0xFFF9F9F9)
