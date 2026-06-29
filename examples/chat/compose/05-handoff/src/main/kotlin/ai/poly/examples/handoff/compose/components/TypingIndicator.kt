// Copyright PolyAI Limited

package ai.poly.examples.handoff.compose.components

import ai.poly.examples.handoff.compose.SystemGray2
import ai.poly.examples.handoff.compose.SystemGray5
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import java.net.URI

/**
 * The agent avatar next to three dots that bob up-and-down in a staggered loop inside a gray
 * rounded bubble.
 */
@Composable
fun TypingIndicator(avatarUrl: URI?) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AgentAvatar(url = avatarUrl)

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(SystemGray5)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(3) { i -> TypingDot(index = i) }
        }
    }
}

@Composable
private fun TypingDot(index: Int) {
    val transition = rememberInfiniteTransition(label = "typing")
    // easeInOut 0.5s, repeatForever autoreverse, staggered by 0.2s * index — y: 0 -> -6.
    val offsetY by transition.animateFloat(
        initialValue = 0f,
        targetValue = -6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
            initialStartOffset = StartOffset(index * 200),
        ),
        label = "dot$index",
    )
    androidx.compose.foundation.layout.Box(
        Modifier
            .offset(y = offsetY.dp)
            .size(8.dp)
            .clip(CircleShape)
            .background(SystemGray2),
    )
}
