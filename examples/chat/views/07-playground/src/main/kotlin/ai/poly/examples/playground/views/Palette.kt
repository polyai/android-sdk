// Copyright PolyAI Limited

package ai.poly.examples.playground.views

import android.graphics.Color

/**
 * System-color palette (light appearance) for the bubbles, pills, banner and labels — system
 * grays, yellow, blue, red, plus secondary label and secondary background tones.
 */
internal object Palette {
    val systemGray2 = Color.parseColor("#AEAEB2")
    val systemGray3 = Color.parseColor("#C7C7CC")
    val systemGray4 = Color.parseColor("#D1D1D6")
    val systemGray5 = Color.parseColor("#E5E5EA")
    val systemGray6 = Color.parseColor("#F2F2F7")
    val systemYellow = Color.parseColor("#FFCC00")
    val systemYellow15 = Color.parseColor("#26FFCC00") // systemYellow @ 15% alpha
    val systemBlue = Color.parseColor("#007AFF")
    val systemBlue10 = Color.parseColor("#1A007AFF") // systemBlue @ 10% alpha
    val systemRed = Color.parseColor("#FF3B30")
    val systemGreen = Color.parseColor("#34C759")
    val systemOrange = Color.parseColor("#FF9500")
    val systemRed15 = Color.parseColor("#26FF3B30") // systemRed @ 15% alpha
    val systemTeal = Color.parseColor("#30B0C7")
    val systemOrange12 = Color.parseColor("#1FFF9500") // systemOrange @ 12% — warning pill fill
    val systemRed12 = Color.parseColor("#1FFF3B30") // systemRed @ 12% — error pill fill
    val systemTeal18 = Color.parseColor("#2E30B0C7") // systemTeal @ 18% alpha — live-agent bubble fill
    val secondaryLabel = Color.parseColor("#993C3C43") // secondaryLabel ≈ black @ 60%
    val label = Color.parseColor("#000000")
    val secondarySystemBackground = Color.parseColor("#F2F2F7")
    val white = Color.WHITE
}
