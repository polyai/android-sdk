// Copyright PolyAI Limited

package ai.poly.examples.resilience.compose.components

import ai.poly.examples.resilience.compose.R
import ai.poly.examples.resilience.compose.SystemBlue
import ai.poly.examples.resilience.compose.SystemGray
import ai.poly.examples.resilience.compose.SystemGray4
import ai.poly.examples.resilience.compose.SystemGray6
import ai.poly.messaging.Attachment
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.URI

/**
 * A horizontally scrolling row of image attachment cards. Each card loads
 * `previewImageUrl ?? contentUrl` (retryable) and opens `contentUrl` on tap.
 */
@Composable
fun AttachmentCarousel(attachments: List<Attachment>) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        attachments.forEach { AttachmentCard(it) }
    }
}

@Composable
private fun AttachmentCard(attachment: Attachment) {
    val context = LocalContext.current
    val imageUrl = (attachment.previewImageUrl ?: attachment.contentUrl)?.toString()
    Column(
        modifier = Modifier
            .width(220.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SystemGray6)
            .clickable(enabled = attachment.contentUrl != null) { openUri(context, attachment.contentUrl) },
    ) {
        if (imageUrl != null) {
            RetryableAsyncImage(
                url = imageUrl,
                modifier = Modifier.width(220.dp).height(140.dp),
                placeholder = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                },
                fallback = { ImagePlaceholder() },
            )
        }
        val title = attachment.title
        if (!title.isNullOrEmpty()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val cta = attachment.callToActionText
                if (!cta.isNullOrEmpty()) {
                    Text(cta, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SystemBlue)
                }
            }
        }
    }
}

@Composable
internal fun ImagePlaceholder() {
    Box(Modifier.fillMaxSize().background(SystemGray4), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_image), contentDescription = null, tint = SystemGray, modifier = Modifier.size(28.dp))
    }
}

/** Open a decoded attachment/link URL with the system handler (the SDK never opens links itself). */
internal fun openUri(context: android.content.Context, uri: URI?) {
    if (uri == null) return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri.toString()))) }
}
