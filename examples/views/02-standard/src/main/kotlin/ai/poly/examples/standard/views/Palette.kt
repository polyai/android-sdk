// Copyright PolyAI Limited

package ai.poly.examples.standard.views

import android.graphics.Color

/**
 * System-color palette (light appearance). The 02-Standard rung styles its bubbles, pills,
 * banner and labels with these system gray / yellow / blue / red / secondary-label /
 * secondary-background values.
 */
internal object Palette {
    val systemGray2 = Color.parseColor("#AEAEB2")
    val systemGray3 = Color.parseColor("#C7C7CC")
    val systemGray5 = Color.parseColor("#E5E5EA")
    val systemGray6 = Color.parseColor("#F2F2F7")
    val systemYellow15 = Color.parseColor("#26FFCC00") // systemYellow @ 15% alpha
    val systemBlue = Color.parseColor("#007AFF")
    val systemBlue10 = Color.parseColor("#1A007AFF") // systemBlue @ 10% alpha
    val systemRed = Color.parseColor("#FF3B30")
    val systemRed15 = Color.parseColor("#26FF3B30") // systemRed @ 15% alpha
    val secondaryLabel = Color.parseColor("#993C3C43") // secondaryLabel ≈ black @ 60%
    val label = Color.parseColor("#000000")
    val secondarySystemBackground = Color.parseColor("#F2F2F7")
    val white = Color.WHITE
}
