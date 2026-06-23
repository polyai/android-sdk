// Copyright PolyAI Limited

package ai.poly.examples.playground.compose

import ai.poly.messaging.POLY_MESSAGING_VERSION
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The production-style entry screen: branding, a read-only connection summary card, and
 * Resume / Start New actions (the primary button reads "Start Chat" when there's nothing to
 * resume).
 */
@Composable
fun ConnectView(
    hasActiveSession: Boolean,
    canResume: Boolean,
    hasCustomSettings: Boolean = false,
    environmentLabel: String? = null,
    onResume: () -> Unit,
    onStartNew: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color.White, SystemBlue.copy(alpha = 0.05f), SystemBlue.copy(alpha = 0.1f)),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(60.dp))

            // Branding: concentric blue circles around the chat-bubbles glyph, which carries a
            // diagonal blue→blue-70% gradient (masked onto the glyph with SrcIn).
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(90.dp).clip(CircleShape).background(SystemBlue.copy(alpha = 0.1f)))
                Box(Modifier.size(70.dp).clip(CircleShape).background(SystemBlue.copy(alpha = 0.08f)))
                Icon(
                    painterResource(R.drawable.ic_chat_bubbles),
                    contentDescription = null,
                    tint = SystemBlue,
                    modifier = Modifier
                        .size(32.dp)
                        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                Brush.linearGradient(
                                    listOf(SystemBlue, SystemBlue.copy(alpha = 0.7f)),
                                    start = Offset.Zero,
                                    end = Offset(size.width, size.height),
                                ),
                                blendMode = BlendMode.SrcIn,
                            )
                        },
                )
            }
            Text(
                "PolyMessaging",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                "AI-powered conversations",
                fontSize = 15.sp,
                color = SecondaryLabel,
                modifier = Modifier.padding(top = 12.dp),
            )

            Spacer(Modifier.height(40.dp))

            // Read-only connection summary card.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .shadow(4.dp, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ConfigRow(
                    icon = R.drawable.ic_dns,
                    label = "ENVIRONMENT",
                    value = environmentLabel ?: "messaging.us-1.poly.ai",
                    // Truncate this row in the MIDDLE so the host's tail survives.
                    overflow = TextOverflow.MiddleEllipsis,
                    trailing = { Box(Modifier.size(8.dp).clip(CircleShape).background(SystemGreen)) },
                )
                HorizontalDivider(color = SystemGray5)
                ConfigRow(
                    icon = R.drawable.ic_key,
                    label = "CONNECTOR",
                    value = "your connector",
                    monospace = true,
                    trailing = if (hasCustomSettings) {
                        {
                            Text(
                                "Custom",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = SystemOrange,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(SystemOrange.copy(alpha = 0.18f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                                    .semantics { contentDescription = "Custom dev settings applied" },
                            )
                        }
                    } else {
                        null
                    },
                )
            }

            Spacer(Modifier.height(40.dp))

            val primaryShowsResume = hasActiveSession || canResume
            Button(
                onClick = onResume,
                colors = ButtonDefaults.buttonColors(containerColor = SystemBlue),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 8.dp),
                ) {
                    Icon(
                        painterResource(if (primaryShowsResume) R.drawable.ic_resume else R.drawable.ic_bolt),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        if (primaryShowsResume) "Resume Chat" else "Start Chat",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (primaryShowsResume) {
                OutlinedButton(
                    onClick = onStartNew,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SystemBlue),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(top = 8.dp),
                ) {
                    Text("Start New Chat", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }

            if (hasActiveSession) {
                Text(
                    "Your conversation is still active",
                    fontSize = 12.sp,
                    color = SystemBlue.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else if (canResume) {
                Text(
                    "A previous conversation is available to resume",
                    fontSize = 12.sp,
                    color = SystemBlue.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Text(
                "PolyMessaging Android SDK v$POLY_MESSAGING_VERSION",
                fontSize = 11.sp,
                color = SecondaryLabel.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 12.dp, bottom = 20.dp),
            )
        }
    }
}

@Composable
private fun ConfigRow(
    icon: Int,
    label: String,
    value: String,
    monospace: Boolean = false,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = SystemBlue,
            modifier = Modifier.width(20.dp).size(20.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontSize = 11.sp, color = SecondaryLabel)
            Text(
                value,
                fontSize = 15.sp,
                fontWeight = if (monospace) FontWeight.Normal else FontWeight.Medium,
                fontFamily = if (monospace) FontFamily.Monospace else null,
                color = if (monospace) Color.Black.copy(alpha = 0.7f) else Color.Black,
                maxLines = 1,
                overflow = overflow,
            )
        }
        trailing?.invoke()
    }
}
