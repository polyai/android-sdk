// Copyright PolyAI Limited

package ai.poly.examples.standard.compose.components

import ai.poly.examples.standard.compose.SecondaryLabel
import ai.poly.examples.standard.compose.SystemYellow
import ai.poly.messaging.ConnectionStatus
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shown only during a transient reconnect
 * (`ConnectionStatus.Reconnecting`); other states resolve silently. `.failed` is terminal and is
 * handled by the failure overlay, not here.
 */
@Composable
fun ConnectionBanner(status: ConnectionStatus) {
    if (status is ConnectionStatus.Reconnecting) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(SystemYellow.copy(alpha = 0.15f))
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A small spinner.
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = SecondaryLabel,
            )
            Text(
                "Reconnecting...",
                modifier = Modifier.padding(start = 8.dp),
                fontSize = 12.sp,
                color = SecondaryLabel,
            )
        }
    }
}
