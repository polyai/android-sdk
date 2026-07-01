// Copyright PolyAI Limited

//  DebugStrip.kt — Examples/compose/07-playground

package ai.poly.examples.playground.compose.components

import ai.poly.examples.playground.compose.DevDiagnostics
import ai.poly.examples.playground.compose.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

/**
 * Tiny always-on observability strip rendered at the top of the chat view
 * when `DevSettings.showDebugStrip` is enabled. Mirrors the most useful
 * SettingsSheet diagnostics into a 1-line, always-visible chip so you can
 * watch reconnects / streaming chunks happen in real-time without opening
 * the sheet.
 */
@Composable
fun DebugStrip(diagnostics: DevDiagnostics) {
    val connectionLabel by diagnostics.connectionLabel.collectAsStateWithLifecycle()
    val lastSequence by diagnostics.lastSequence.collectAsStateWithLifecycle()
    val framesIn by diagnostics.framesIn.collectAsStateWithLifecycle()
    val framesOut by diagnostics.framesOut.collectAsStateWithLifecycle()
    val chunksIn by diagnostics.chunksIn.collectAsStateWithLifecycle()
    val lastInboundAt by diagnostics.lastInboundAt.collectAsStateWithLifecycle()

    // 1s ticker so the "last frame" age keeps counting.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }

    val statusIcon = when {
        connectionLabel == "open" -> R.drawable.ic_radio_waves
        connectionLabel == "connecting" -> R.drawable.ic_more_horiz
        connectionLabel.startsWith("reconnecting") -> R.drawable.ic_autorenew
        connectionLabel == "closing" || connectionLabel == "closed" -> R.drawable.ic_close_circle
        connectionLabel == "failed" -> R.drawable.ic_error_triangle
        else -> R.drawable.ic_circle_outline
    }
    val statusColor = when {
        connectionLabel == "open" -> StripGreen
        connectionLabel == "connecting" || connectionLabel.startsWith("reconnecting") -> StripYellow
        connectionLabel == "failed" || connectionLabel == "closed" || connectionLabel == "closing" -> StripRed
        else -> Color.White.copy(alpha = 0.6f)
    }

    fun lastFrameLabel(): String {
        val at = lastInboundAt ?: return "—"
        val s = ((now - at) / 1_000L).toInt()
        return if (s < 1) "0s" else "${s}s"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.78f))
            .padding(horizontal = 10.dp, vertical = 5.dp)
            // One combined element replacing the chip texts.
            .clearAndSetSemantics {
                contentDescription =
                    "Status $connectionLabel. Sequence $lastSequence. Chunks $chunksIn. Last frame ${lastFrameLabel()}."
            },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chip(icon = statusIcon, text = connectionLabel, color = statusColor)
        Chip(icon = R.drawable.ic_tag, text = "seq $lastSequence")
        Chip(icon = R.drawable.ic_swap_vert, text = "$framesOut→ ←$framesIn")
        if (chunksIn > 0) {
            Chip(icon = R.drawable.ic_waveform, text = "${chunksIn}c", color = StripBlue)
        }
        Spacer(Modifier.weight(1f))
        Chip(icon = R.drawable.ic_clock, text = lastFrameLabel())
    }
}

@Composable
private fun Chip(icon: Int, text: String, color: Color = Color.White.copy(alpha = 0.85f)) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(9.dp))
        Text(text, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}

// Vivid green/yellow/red/blue on the dark strip.
private val StripGreen = Color(0xFF30D158)
private val StripYellow = Color(0xFFFFD60A)
private val StripRed = Color(0xFFFF453A)
private val StripBlue = Color(0xFF0A84FF)
