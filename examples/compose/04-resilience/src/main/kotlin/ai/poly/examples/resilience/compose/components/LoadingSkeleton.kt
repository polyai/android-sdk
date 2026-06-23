// Copyright PolyAI Limited

package ai.poly.examples.resilience.compose.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Pulsing placeholder rows shown while the WebSocket is opening for the first time (the caller gates
 * this on `!isReady && messages.isEmpty()`); warm resumes already have messages in memory, so they
 * skip the skeleton. Decorative — hidden from accessibility.
 */
@Composable
fun LoadingSkeleton() {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.12f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clearAndSetSemantics {}, // decorative — hide from TalkBack
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SkeletonBubble(220.dp, alpha)
        SkeletonBubble(260.dp, alpha)
        SkeletonBubble(190.dp, alpha)
    }
}

@Composable
private fun SkeletonBubble(width: Dp, alpha: Float) {
    Box(
        Modifier
            .width(width)
            .height(42.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Gray.copy(alpha = alpha)),
    )
}
