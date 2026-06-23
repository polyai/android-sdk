// Copyright PolyAI Limited

//  DebugStripView.kt — Examples/views/07-playground
//  Status / sequence / frame counts / chunks / last-frame age in a black monospaced
//  bar, updated off DevDiagnostics StateFlows + a 1s ticker.

package ai.poly.examples.playground.views

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import android.content.res.ColorStateList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DebugStripView(context: Context) : LinearLayout(context) {

    private val statusChip = Chip(context)
    private val seqChip = Chip(context)
    private val framesChip = Chip(context)
    private val chunksChip = Chip(context)
    private val ageChip = Chip(context)
    private var jobs = mutableListOf<Job>()

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.argb((0.78f * 255).toInt(), 0, 0, 0))
        setPadding(dp(10), dp(5), dp(10), dp(5))

        addChip(statusChip)
        addChip(seqChip, marginStart = 10)
        addChip(framesChip, marginStart = 10)
        addChip(chunksChip, marginStart = 10)
        addView(Space(context), LayoutParams(0, 0, 1f))
        addChip(ageChip)
    }

    private fun addChip(chip: Chip, marginStart: Int = 0) {
        addView(
            chip,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                this.marginStart = dp(marginStart)
            },
        )
    }

    fun start(scope: CoroutineScope, diagnostics: DevDiagnostics) {
        stop()
        jobs += scope.launch {
            diagnostics.connectionLabel.collect { refresh(diagnostics) }
        }
        jobs += scope.launch {
            diagnostics.framesIn.collect { refresh(diagnostics) }
        }
        jobs += scope.launch {
            diagnostics.framesOut.collect { refresh(diagnostics) }
        }
        jobs += scope.launch {
            diagnostics.lastSequence.collect { refresh(diagnostics) }
        }
        jobs += scope.launch {
            diagnostics.chunksIn.collect { refresh(diagnostics) }
        }
        // 1s ticker so the last-frame age keeps counting.
        jobs += scope.launch {
            while (true) {
                delay(1_000)
                refresh(diagnostics)
            }
        }
        refresh(diagnostics)
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    private fun refresh(d: DevDiagnostics) {
        val label = d.connectionLabel.value
        statusChip.set(statusIcon(label), label, statusColor(label))
        seqChip.set(R.drawable.ic_tag, "seq ${d.lastSequence.value}", DIM_WHITE)
        framesChip.set(R.drawable.ic_swap_vert, "${d.framesOut.value}→ ←${d.framesIn.value}", DIM_WHITE)
        if (d.chunksIn.value > 0) {
            chunksChip.visibility = VISIBLE
            chunksChip.set(R.drawable.ic_waveform, "${d.chunksIn.value}c", Palette.systemBlue)
        } else {
            chunksChip.visibility = GONE
        }
        ageChip.set(R.drawable.ic_clock, lastFrameLabel(d.lastInboundAt.value), DIM_WHITE)
        contentDescription =
            "Status $label. Sequence ${d.lastSequence.value}. Chunks ${d.chunksIn.value}. " +
            "Last frame ${lastFrameLabel(d.lastInboundAt.value)}."
    }

    private fun lastFrameLabel(at: Long?): String {
        if (at == null) return "—"
        val s = ((System.currentTimeMillis() - at) / 1_000L).toInt()
        return if (s < 1) "0s" else "${s}s"
    }

    private fun statusIcon(label: String): Int = when {
        label == "open" -> R.drawable.ic_radio_waves
        label == "connecting" -> R.drawable.ic_more_horiz
        label.startsWith("reconnecting") -> R.drawable.ic_autorenew
        label == "closing" || label == "closed" -> R.drawable.ic_close_circle
        label == "failed" -> R.drawable.ic_error_triangle
        else -> R.drawable.ic_circle_outline
    }

    private fun statusColor(label: String): Int = when {
        label == "open" -> Palette.systemGreen
        label == "connecting" || label.startsWith("reconnecting") -> Palette.systemYellow
        label == "failed" || label == "closed" || label == "closing" -> Palette.systemRed
        else -> Color.argb((0.6f * 255).toInt(), 255, 255, 255)
    }

    private class Chip(context: Context) : LinearLayout(context) {
        private val icon = ImageView(context)
        private val label = TextView(context)
        private val density = resources.displayMetrics.density

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(icon, LayoutParams((11 * density).toInt(), (11 * density).toInt()))
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            label.typeface = Typeface.MONOSPACE
            addView(
                label,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    marginStart = (3 * density).toInt()
                },
            )
        }

        fun set(iconRes: Int, text: String, color: Int) {
            icon.setImageDrawable(ContextCompat.getDrawable(context, iconRes))
            ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(color))
            label.text = text
            label.setTextColor(color)
        }
    }

    private companion object {
        val DIM_WHITE = Color.argb((0.85f * 255).toInt(), 255, 255, 255)
    }
}
