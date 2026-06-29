// Copyright PolyAI Limited

//  TimestampSeparator.kt — Examples/compose/07-playground

package ai.poly.examples.playground.compose.components

import ai.poly.examples.playground.compose.MessageTimestamp
import ai.poly.examples.playground.compose.SecondaryLabel
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

/**
 * Centered timestamp pill rendered between message groups.
 * Inserted by ChatView whenever the time gap between two consecutive
 * messages exceeds `MessageTimestamp.GROUP_GAP_MILLIS`, plus above the very
 * first message so the top of the list always anchors a time.
 */
@Composable
fun TimestampSeparator(epochMillis: Long) {
    val header = MessageTimestamp.groupHeader(epochMillis)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .semantics { contentDescription = header },
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            header,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = SecondaryLabel,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Spacer(Modifier.weight(1f))
    }
}
