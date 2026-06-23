// Copyright PolyAI Limited

package ai.poly.examples.resilience.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout

/**
 * Three leading, pulsing rounded rows that preview where
 * the first agent messages will land — shown while the session is opening and no messages exist yet
 * (the caller gates this on `!isReady && messages.isEmpty()`). Decorative — hidden from accessibility.
 * Self-manages its pulse: animates while attached + visible, stops otherwise.
 */
class LoadingSkeletonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val bars: List<View>
    private var animator: ValueAnimator? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.WHITE)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val d = resources.displayMetrics.density
        setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
        bars = listOf(220, 260, 190).mapIndexed { i, w ->
            View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 16 * d
                    setColor(Palette.systemGray5)
                }
                layoutParams = LayoutParams((w * d).toInt(), (42 * d).toInt()).apply {
                    if (i > 0) topMargin = (10 * d).toInt()
                }
            }.also { addView(it) }
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && isAttachedToWindow) start() else stop()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun start() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(1f, 0.43f).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator() // ease-in-out
            addUpdateListener { a -> val v = a.animatedValue as Float; bars.forEach { it.alpha = v } }
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
        bars.forEach { it.alpha = 1f }
    }
}
