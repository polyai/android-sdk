// Copyright PolyAI Limited

package ai.poly.examples.richcontent.compose.components

import ai.poly.examples.richcontent.compose.R
import ai.poly.examples.richcontent.compose.SecondaryLabel
import ai.poly.examples.richcontent.compose.SystemBlue
import ai.poly.examples.richcontent.compose.SystemGray5
import ai.poly.examples.richcontent.compose.SystemGray6
import ai.poly.messaging.Attachment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A `.url` attachment card: optional preview image, title, and call-to-action label.
 * Tap opens `contentUrl` with the system handler.
 */
@Composable
fun UrlCard(attachment: Attachment) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .width(260.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SystemGray6)
            .clickable(enabled = attachment.contentUrl != null) { openUri(context, attachment.contentUrl) },
    ) {
        attachment.previewImageUrl?.let { preview ->
            RetryableAsyncImage(
                url = preview.toString(),
                modifier = Modifier.width(260.dp).height(140.dp),
                placeholder = {
                    Box(Modifier.fillMaxSize().background(SystemGray5), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                },
                fallback = {
                    Box(Modifier.fillMaxSize().background(SystemGray5), contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(R.drawable.ic_image),
                            contentDescription = null,
                            tint = SecondaryLabel,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                },
            )
        }
        Column(
            modifier = Modifier.padding(10.dp).fillMaxWidth(),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        ) {
            attachment.title?.takeIf { it.isNotEmpty() }?.let { title ->
                Text(
                    title,
                    color = Color.Black,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            attachment.callToActionText?.takeIf { it.isNotEmpty() }?.let { cta ->
                Text(cta, color = SystemBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
