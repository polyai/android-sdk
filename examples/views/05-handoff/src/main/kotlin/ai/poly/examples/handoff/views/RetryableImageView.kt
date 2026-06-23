// Copyright PolyAI Limited

package ai.poly.examples.handoff.views

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.widget.ImageView
import coil.imageLoader
import coil.request.Disposable
import coil.request.ImageRequest
import coil.transform.CircleCropTransformation

/**
 * An ImageView that loads from a URL via Coil with tap-to-retry on failure and a 5-second auto-retry
 * that re-arms every cycle (a persistently-failing image keeps retrying). The SDK never fetches
 * bytes — you load the URL. (Coil owns memory/disk caching.)
 *
 * Used on recycled RecyclerView cells, so it cancels the prior request and guards every callback on
 * `currentUrl == url` — a slow response from a previous bind can never overwrite the recycled view.
 */
class RetryableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ImageView(context, attrs, defStyleAttr) {

    private var currentUrl: String? = null
    private var fallbackRes: Int = 0
    private var circle: Boolean = false
    private var failed: Boolean = false
    private var didAutoRetry: Boolean = false
    private var disposable: Disposable? = null
    private val handler = Handler(Looper.getMainLooper())

    init {
        scaleType = ScaleType.CENTER_CROP
        setOnClickListener { if (failed) currentUrl?.let { reload() } } // retry only from the failure state
    }

    fun load(url: String?, fallbackRes: Int = 0, circle: Boolean = false) {
        this.currentUrl = url
        this.fallbackRes = fallbackRes
        this.circle = circle
        handler.removeCallbacksAndMessages(null)
        disposable?.dispose()
        if (url == null) {
            showFallback()
            return
        }
        reload()
    }

    private fun reload() {
        val url = currentUrl ?: return
        didAutoRetry = false // re-arm a fresh 5s retry each cycle
        failed = false
        scaleType = ScaleType.CENTER_CROP
        if (!circle) setBackgroundColor(Palette.systemGray6) // loading backdrop
        disposable?.dispose()
        val request = ImageRequest.Builder(context)
            .data(url)
            .target(
                onSuccess = { drawable ->
                    if (currentUrl == url) { failed = false; scaleType = ScaleType.CENTER_CROP; setImageDrawable(drawable) }
                },
                onError = { if (currentUrl == url) { showFallback(); scheduleAutoRetry(url) } },
            )
            .apply { if (circle) transformations(CircleCropTransformation()) }
            .build()
        disposable = context.imageLoader.enqueue(request)
    }

    private fun showFallback() {
        failed = true
        scaleType = if (circle) ScaleType.CENTER_CROP else ScaleType.CENTER
        if (!circle) setBackgroundColor(Palette.systemGray4) // failed backdrop (darker than loading)
        if (fallbackRes != 0) setImageResource(fallbackRes) else setImageDrawable(null)
    }

    private fun scheduleAutoRetry(url: String) {
        if (didAutoRetry) return
        didAutoRetry = true
        handler.postDelayed({ if (currentUrl == url) reload() }, 5_000)
    }
}
