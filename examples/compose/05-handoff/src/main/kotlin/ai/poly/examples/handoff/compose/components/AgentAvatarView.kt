// Copyright PolyAI Limited

package ai.poly.examples.handoff.compose.components

import ai.poly.examples.handoff.compose.R
import ai.poly.examples.handoff.compose.SystemGray3
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.net.URI

/**
 * The plain circular agent avatar (used by the typing indicator): a retryable network image
 * falling back to a gray person-in-circle glyph. The live-aware avatar with the teal ring lives
 * in [MessageBubbleView].
 */
@Composable
fun AgentAvatar(url: URI?) {
    val size = 28.dp
    if (url != null) {
        RetryableAsyncImage(
            url = url.toString(),
            modifier = Modifier.size(size).clip(CircleShape),
            placeholder = { AvatarFallback(size) },
            fallback = { AvatarFallback(size) },
        )
    } else {
        AvatarFallback(size)
    }
}

/** A person-in-circle glyph tinted gray. */
@Composable
private fun AvatarFallback(size: Dp) {
    Icon(
        painter = painterResource(R.drawable.ic_account_circle),
        contentDescription = null,
        tint = SystemGray3,
        modifier = Modifier.size(size),
    )
}
