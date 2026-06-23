// Copyright PolyAI Limited

package ai.poly.examples.playground.compose.components

import ai.poly.examples.playground.compose.SystemBlue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A horizontally scrolling row of quick-reply pills (capsule, blue-tinted) shown under the last
 * agent message.
 */
@Composable
fun SuggestionRow(
    suggestions: List<String>,
    onTap: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        suggestions.forEach { s ->
            Text(
                text = s,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 15.sp, // subheadline
                color = SystemBlue,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(SystemBlue.copy(alpha = 0.1f))
                    .clickable { onTap(s) }
                    .semantics { contentDescription = "Suggested reply: $s" }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
