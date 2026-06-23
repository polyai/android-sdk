// Copyright PolyAI Limited

package ai.poly.examples.playground.compose.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import kotlinx.coroutines.delay

/**
 * The SDK never fetches bytes — you load the URL.
 * This wraps Coil and adds tap-to-retry on failure plus a one-shot 5-second auto-retry. The request
 * is keyed on a `loadId` so each retry forces a fresh fetch past the (negative) cache.
 */
@Composable
fun RetryableAsyncImage(
    url: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: @Composable () -> Unit,
    fallback: @Composable () -> Unit,
) {
    var loadId by remember(url) { mutableIntStateOf(0) }
    val context = LocalContext.current
    val request = remember(url, loadId) {
        ImageRequest.Builder(context).data(url).setParameter("retry", loadId.toString()).build()
    }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier,
    ) {
        when (painter.state) {
            is AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
            is AsyncImagePainter.State.Error -> {
                Box(Modifier.fillMaxSize().clickable { loadId++ }) { fallback() }
                // One-shot auto-retry 5s later; re-keying on loadId means a persistent failure keeps retrying.
                LaunchedEffect(loadId) {
                    delay(5_000)
                    loadId++
                }
            }
            else -> placeholder()
        }
    }
}
