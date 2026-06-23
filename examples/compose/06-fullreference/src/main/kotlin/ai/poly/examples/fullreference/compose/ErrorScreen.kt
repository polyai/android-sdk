// Copyright PolyAI Limited

package ai.poly.examples.fullreference.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Connect/start failures with a single Go Back action. */
@Composable
fun ErrorScreen(message: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.weight(1f))
        Icon(
            painterResource(R.drawable.ic_error_triangle),
            contentDescription = null,
            tint = SystemOrange,
            modifier = Modifier.size(44.dp),
        )
        Text("Something went wrong", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(
            message,
            fontSize = 15.sp,
            color = SecondaryLabel,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onBack,
            colors = ButtonDefaults.buttonColors(containerColor = SystemBlue),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("Go Back", modifier = Modifier.padding(vertical = 6.dp))
        }
    }
}
