// Copyright PolyAI Limited

//  LogsSheet.kt — Examples/compose/07-playground
//  The filterable, expandable debug event log, presented as a ModalBottomSheet.

package ai.poly.examples.playground.compose

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsSheet(logs: List<LogEntry>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf("") }
    val filteredLogs = remember(logs, filter) {
        if (filter.isEmpty()) logs
        else logs.filter {
            it.summary.contains(filter, ignoreCase = true) ||
                (it.detail?.contains(filter, ignoreCase = true) ?: false)
        }
    }
    val listState = rememberLazyListState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
    ) {
        Column(Modifier.fillMaxHeight(0.92f)) {
            // Title bar: copy-all on the left, Done on the right.
            Box(Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = {
                        val text = logs.joinToString("\n") { e ->
                            e.summary + (e.detail?.let { "\n$it" } ?: "")
                        }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Debug Logs", text))
                    },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .semantics { contentDescription = "Copy all logs" },
                ) {
                    Icon(
                        painterResource(R.drawable.ic_copy),
                        contentDescription = null,
                        tint = SystemBlue,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    "Debug Logs",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center),
                )
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Text("Done", color = SystemBlue, fontWeight = FontWeight.SemiBold)
                }
            }

            // Counters row (total + matches while filtering).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SystemGray6)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(R.drawable.ic_log_lines),
                        contentDescription = null,
                        tint = SecondaryLabel,
                        modifier = Modifier.size(14.dp),
                    )
                    Text("${logs.size}", fontSize = 12.sp, color = SecondaryLabel)
                }
                if (filter.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(R.drawable.ic_filter_lines),
                            contentDescription = null,
                            tint = SecondaryLabel,
                            modifier = Modifier.size(14.dp),
                        )
                        Text("${filteredLogs.size} match", fontSize = 12.sp, color = SecondaryLabel)
                    }
                }
                Spacer(Modifier.weight(1f))
            }

            // Filter field.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SystemGray6)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ic_search),
                    contentDescription = null,
                    tint = SecondaryLabel,
                    modifier = Modifier.size(14.dp),
                )
                Box(Modifier.weight(1f)) {
                    if (filter.isEmpty()) {
                        Text("Filter logs...", fontSize = 12.sp, color = SecondaryLabel)
                    }
                    BasicTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        textStyle = TextStyle(fontSize = 12.sp, color = Color.Black),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            HorizontalDivider(color = SystemGray5)

            LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
                items(filteredLogs, key = { it.id }) { entry ->
                    LogEntryRow(entry = entry, index = filteredLogs.indexOf(entry))
                }
            }
            // Open scrolled to the newest entry.
            LaunchedEffect(Unit) {
                if (filteredLogs.isNotEmpty()) listState.scrollToItem(filteredLogs.size - 1)
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: LogEntry, index: Int) {
    var isExpanded by remember { mutableStateOf(false) }

    // Keyword heuristics for the log level.
    val lower = entry.summary.lowercase()
    val (color, icon) = when {
        lower.contains("error") || lower.contains("failed") -> SystemRed to R.drawable.ic_close_circle
        lower.contains("warn") || lower.contains("timeout") -> SystemOrange to R.drawable.ic_error_triangle
        lower.contains("connected") || lower.contains("session started") || lower.contains("confirmed") ->
            SystemGreen to R.drawable.ic_check_circle
        lower.contains("chunk") || lower.contains("thinking") -> SecondaryLabel to R.drawable.ic_more_horiz
        else -> Color.Black to R.drawable.ic_info
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(if (index % 2 == 0) Color.White else SystemGray6)
            .animateContentSize(),
    ) {
        // Only the header row toggles, so a tap on the expanded detail block never collapses it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = entry.detail != null) { isExpanded = !isExpanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp),
            )
            Text(
                entry.summary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = Color.Black,
                maxLines = if (isExpanded) Int.MAX_VALUE else 1,
                modifier = Modifier.weight(1f),
            )
            if (entry.detail != null) {
                Icon(
                    painterResource(if (isExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                    contentDescription = null,
                    tint = SystemBlue,
                    modifier = Modifier.size(12.dp),
                )
            }
        }

        if (isExpanded && entry.detail != null) {
            Column(
                Modifier.padding(start = 36.dp, end = 12.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (line in entry.detail.split("\n")) {
                    val colonIdx = line.indexOf(':')
                    if (colonIdx >= 0) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Top) {
                            Text(
                                line.substring(0, colonIdx + 1),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = SystemBlue,
                            )
                            Text(
                                line.substring(colonIdx + 1).trim(),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color.Black,
                            )
                        }
                    } else {
                        Text(line, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color.Black)
                    }
                }
            }
        }
    }
}
