// Copyright PolyAI Limited

package ai.poly.examples.handoff.compose.components

import ai.poly.examples.handoff.compose.R
import ai.poly.examples.handoff.compose.SystemRed
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shown ABOVE the SDK's ConnectionBanner when the OS reports no network path. Distinct from the SDK's
 * reconnect banner: this means the *device* is offline, not that the WebSocket is reconnecting — both
 * can be visible at once.
 */
@Composable
fun OfflineBanner(isOnline: Boolean) {
    if (!isOnline) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SystemRed.copy(alpha = 0.18f))
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.ic_wifi_off),
                contentDescription = null,
                tint = SystemRed,
                modifier = Modifier.size(15.dp),
            )
            Text("You're offline", color = SystemRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
    }
}
