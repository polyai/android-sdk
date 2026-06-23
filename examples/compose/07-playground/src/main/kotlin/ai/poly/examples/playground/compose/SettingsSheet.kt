// Copyright PolyAI Limited

//  SettingsSheet.kt — Examples/compose/07-playground
//  Live diagnostics, wire-level send/disconnect actions, display toggles,
//  environment/session/advanced Configuration knobs, and reset, presented as a
//  ModalBottomSheet with grouped sections.
//
//  Close-code note: OkHttp refuses to SEND the reserved code 1006 for "force reconnect" /
//  "network drop", so both rows send 4002 — the SDK's sendable app code that routes through
//  the same exponential-backoff reconnect path. 4001 / 1000 are sendable.

package ai.poly.examples.playground.compose

import ai.poly.messaging.DevSettings
import ai.poly.messaging.LogLevel
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: DevSettings,
    diagnostics: DevDiagnostics,
    hasLiveSession: Boolean,
    hasResumableSession: Boolean,
    onApplyAndRestart: () -> Unit,
    onForceReconnect: () -> Unit,
    onSimulateDrop: () -> Unit,
    onDisconnectClean: () -> Unit,
    onSimulateServerReject: () -> Unit,
    onSimulateIdleTimeout: () -> Unit,
    onSendHeartbeat: () -> Unit,
    onSendTypingStart: () -> Unit,
    onSendTypingStop: () -> Unit,
    onSendUserEndSession: () -> Unit,
    onSendUserLeft: () -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var toastMessage by remember { mutableStateOf<String?>(null) }
    var lastToast by remember { mutableStateOf("") }
    var toastNonce by remember { mutableStateOf(0) }
    val hasAnySession = hasLiveSession || hasResumableSession

    // Auto-dismiss the toast ~1.6s after the latest action.
    LaunchedEffect(toastNonce) {
        if (toastMessage != null) {
            delay(1_600)
            toastMessage = null
        }
    }

    fun fireAction(message: String, action: () -> Unit) {
        action()
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        toastMessage = message
        lastToast = message
        toastNonce += 1
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SystemGray6,
    ) {
        Box(Modifier.fillMaxHeight(0.92f)) {
            Column(Modifier.fillMaxWidth()) {
                // Title bar (title + Done).
                Box(Modifier.fillMaxWidth()) {
                    Text(
                        "Dev Settings",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterEnd)) {
                        Text("Done", color = SystemBlue, fontWeight = FontWeight.SemiBold)
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    if (hasAnySession) {
                        item {
                            SectionCard {
                                SessionMismatchBanner(
                                    hasLiveSession = hasLiveSession,
                                    onApplyAndRestart = {
                                        onApplyAndRestart()
                                        onDismiss()
                                    },
                                )
                            }
                        }
                    }
                    item { DiagnosticsSection(diagnostics) }
                    if (hasLiveSession) {
                        item {
                            Section(
                                header = "Send frames",
                                footer = "Wire-level events sent directly to the server via the raw connection.",
                            ) {
                                ActionRow("Send HEARTBEAT", R.drawable.ic_ecg, enabled = hasLiveSession) {
                                    fireAction("Heartbeat sent", onSendHeartbeat)
                                }
                                RowDivider()
                                ActionRow("Send USER_TYPING (started)", R.drawable.ic_chat_typing, enabled = hasLiveSession) {
                                    fireAction("Typing started", onSendTypingStart)
                                }
                                RowDivider()
                                ActionRow("Send USER_TYPING (stopped)", R.drawable.ic_chat_typing_filled, enabled = hasLiveSession) {
                                    fireAction("Typing stopped", onSendTypingStop)
                                }
                                RowDivider()
                                ActionRow("Send USER_END_SESSION", R.drawable.ic_close_circle, enabled = hasLiveSession, isDestructive = true) {
                                    fireAction("End session sent", onSendUserEndSession)
                                }
                                RowDivider()
                                ActionRow("Send USER_LEFT", R.drawable.ic_logout, enabled = hasLiveSession, isDestructive = true) {
                                    fireAction("User left sent", onSendUserLeft)
                                }
                            }
                        }
                        item {
                            Section(
                                header = "Disconnect / reconnect",
                                footer = "Exercises each ConnectionService close-code path. 4002 reconnects, " +
                                    "4001 refetches, 1000 is terminal. (OkHttp can't send reserved codes, so " +
                                    "the first two use 4002 — same reconnect path.)",
                            ) {
                                ActionRow("Force reconnect (4002)", R.drawable.ic_autorenew, enabled = hasLiveSession) {
                                    fireAction("Reconnect triggered", onForceReconnect)
                                }
                                RowDivider()
                                ActionRow("Simulate network drop (4002)", R.drawable.ic_wifi_off, enabled = hasLiveSession) {
                                    fireAction("Simulated network drop", onSimulateDrop)
                                }
                                RowDivider()
                                ActionRow("Simulate idle timeout (4002)", R.drawable.ic_clock_alert, enabled = hasLiveSession) {
                                    fireAction("Idle-timeout close sent", onSimulateIdleTimeout)
                                }
                                RowDivider()
                                ActionRow("Simulate server reject (4001)", R.drawable.ic_shield_alert, enabled = hasLiveSession, isDestructive = true) {
                                    fireAction("Server-reject close sent", onSimulateServerReject)
                                }
                                RowDivider()
                                ActionRow("Clean disconnect (1000)", R.drawable.ic_block, enabled = hasLiveSession, isDestructive = true) {
                                    fireAction("Clean disconnect sent", onDisconnectClean)
                                }
                            }
                        }
                    }
                    item { DisplaySection(settings) }
                    item { EnvironmentSection(settings) }
                    item { SessionSection(settings, hasAnySession) }
                    item { AdvancedSection(settings) }
                    item { CursorSection() }
                    item {
                        SectionCard {
                            ActionRow("Reset to defaults", R.drawable.ic_restore, enabled = true, isDestructive = true) {
                                settings.resetToDefaults()
                            }
                        }
                    }
                    item { Spacer(Modifier.size(8.dp)) }
                }
            }

            // Action toast (spring slide+fade overlay). Qualified call:
            // the enclosing ColumnScope's AnimatedVisibility extension would shadow it.
            androidx.compose.animation.AnimatedVisibility(
                visible = toastMessage != null,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
                enter = slideInVertically(spring(dampingRatio = 0.8f)) { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                Row(
                    modifier = Modifier
                        .shadow(8.dp, CircleShape)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.82f))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .semantics { contentDescription = lastToast },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_check_circle),
                        contentDescription = null,
                        tint = SystemGreen,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(lastToast, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

// MARK: - Banner

@Composable
private fun SessionMismatchBanner(hasLiveSession: Boolean, onApplyAndRestart: () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painterResource(R.drawable.ic_error_triangle),
                contentDescription = null,
                tint = SystemOrange,
                modifier = Modifier.size(18.dp),
            )
            Text(
                if (hasLiveSession) "Live session active" else "Resumable session exists",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            if (hasLiveSession) {
                "Streaming, greeting and environment changes only apply to NEW sessions."
            } else {
                "A previous session is stored on disk. Streaming and other session-creation settings " +
                    "here only apply once you start fresh — they don't change a resumed session."
            },
            fontSize = 12.sp,
            color = SecondaryLabel,
        )
        Button(
            onClick = onApplyAndRestart,
            colors = ButtonDefaults.buttonColors(containerColor = SystemOrange),
            shape = RoundedCornerShape(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.ic_autorenew),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                Text("Apply & Start New Session", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// MARK: - Diagnostics (read-only)

@Composable
private fun DiagnosticsSection(diagnostics: DevDiagnostics) {
    val connectionLabel by diagnostics.connectionLabel.collectAsStateWithLifecycle()
    val sessionId by diagnostics.sessionId.collectAsStateWithLifecycle()
    val isReady by diagnostics.isReady.collectAsStateWithLifecycle()
    val lastSequence by diagnostics.lastSequence.collectAsStateWithLifecycle()
    val framesIn by diagnostics.framesIn.collectAsStateWithLifecycle()
    val framesOut by diagnostics.framesOut.collectAsStateWithLifecycle()
    val chunksIn by diagnostics.chunksIn.collectAsStateWithLifecycle()
    val heartbeatsIn by diagnostics.heartbeatsIn.collectAsStateWithLifecycle()
    val reconnectCount by diagnostics.reconnectCount.collectAsStateWithLifecycle()
    val lastInboundAt by diagnostics.lastInboundAt.collectAsStateWithLifecycle()
    val streamingCapability by diagnostics.streamingCapability.collectAsStateWithLifecycle()
    val maxMessageSize by diagnostics.maxMessageSize.collectAsStateWithLifecycle()
    val serverHeartbeatSeconds by diagnostics.serverHeartbeatSeconds.collectAsStateWithLifecycle()
    val serverMaxReconnectAttempts by diagnostics.serverMaxReconnectAttempts.collectAsStateWithLifecycle()

    Section(
        header = "Diagnostics",
        footer = "Live values from the SDK. Counters reset when a fresh client is created.",
    ) {
        LabeledValue("Status", connectionLabel)
        RowDivider()
        LabeledValue("Session ID", shortened(sessionId))
        RowDivider()
        LabeledValue("Ready", if (isReady) "yes" else "no")
        RowDivider()
        LabeledValue("Last sequence", "$lastSequence")
        RowDivider()
        LabeledValue("Frames in / out", "$framesIn / $framesOut")
        RowDivider()
        LabeledValue("Chunks received", "$chunksIn")
        RowDivider()
        LabeledValue("Heartbeats in", "$heartbeatsIn")
        RowDivider()
        LabeledValue("Reconnects", "$reconnectCount")
        RowDivider()
        LabeledValue("Last frame", lastFrameLabel(lastInboundAt))
        streamingCapability?.let {
            RowDivider()
            LabeledValue("Server streaming", if (it) "yes" else "no")
        }
        maxMessageSize?.let {
            RowDivider()
            LabeledValue("Max message size", if (it == 0) "unlimited" else "$it bytes")
        }
        serverHeartbeatSeconds?.let {
            RowDivider()
            LabeledValue("Server heartbeat", if (it == 0) "disabled" else "${it}s")
        }
        serverMaxReconnectAttempts?.let {
            RowDivider()
            LabeledValue("Server max reconnect", if (it == 0) "unlimited" else "$it")
        }
    }
}

private fun lastFrameLabel(lastInboundAt: Long?): String {
    val at = lastInboundAt ?: return "—"
    val s = ((System.currentTimeMillis() - at) / 1_000L).toInt()
    if (s < 1) return "just now"
    if (s < 60) return "${s}s ago"
    return "${s / 60}m ago"
}

private fun shortened(s: String?): String {
    if (s.isNullOrEmpty()) return "—"
    if (s.length <= 12) return s
    return s.take(4) + "…" + s.takeLast(8)
}

// MARK: - Display toggles

@Composable
private fun DisplaySection(settings: DevSettings) {
    val showDebugStrip by settings.showDebugStrip.collectAsStateWithLifecycle()
    val showTimestamps by settings.showMessageTimestamps.collectAsStateWithLifecycle()

    Section(header = "Display", footer = "Visual debug aids inside the chat screen.") {
        ToggleRow("Show debug strip in chat", showDebugStrip) { settings.setShowDebugStrip(it) }
        RowDivider()
        ToggleRow("Message timestamps", showTimestamps) { settings.setShowMessageTimestamps(it) }
    }
}

// MARK: - Environment

@Composable
private fun EnvironmentSection(settings: DevSettings) {
    val kind by settings.environmentKind.collectAsStateWithLifecycle()
    val clusterName by settings.clusterName.collectAsStateWithLifecycle()
    val customRest by settings.customRestUrl.collectAsStateWithLifecycle()
    val customWs by settings.customWsUrl.collectAsStateWithLifecycle()
    var pickerOpen by remember { mutableStateOf(false) }

    Section(
        header = "Environment",
        footer = "Switches the REST + WebSocket base URLs. Applies on next session creation.",
    ) {
        // Target picker (dropdown row).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { pickerOpen = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Target", fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(kind.displayName, fontSize = 15.sp, color = SecondaryLabel)
            Box {
                DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                    DevSettings.EnvironmentKind.entries.forEach { k ->
                        DropdownMenuItem(
                            text = { Text(k.displayName) },
                            onClick = {
                                settings.setEnvironmentKind(k)
                                pickerOpen = false
                            },
                        )
                    }
                }
            }
        }

        when (kind) {
            DevSettings.EnvironmentKind.CLUSTER -> {
                RowDivider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Cluster name", fontSize = 12.sp, color = SecondaryLabel)
                    SettingsTextField(value = clusterName, placeholder = "us-1") { settings.setClusterName(it) }
                    Text("Resolves to messaging.<name>.poly.ai", fontSize = 11.sp, color = SecondaryLabel)
                }
            }
            DevSettings.EnvironmentKind.CUSTOM -> {
                RowDivider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("REST base URL", fontSize = 12.sp, color = SecondaryLabel)
                    SettingsTextField(
                        value = customRest,
                        placeholder = "https://...",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    ) { settings.setCustomRestUrl(it) }
                    Text("WS base URL", fontSize = 12.sp, color = SecondaryLabel)
                    SettingsTextField(
                        value = customWs,
                        placeholder = "wss://.../ws",
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    ) { settings.setCustomWsUrl(it) }
                }
            }
            else -> Unit
        }

        RowDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("Resolved", fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(
                settings.environmentDisplayName(),
                fontSize = 12.sp,
                color = SecondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// MARK: - Session creation params

@Composable
private fun SessionSection(settings: DevSettings, hasAnySession: Boolean) {
    val streamingEnabled by settings.streamingEnabled.collectAsStateWithLifecycle()
    val lastApplied by settings.lastAppliedStreamingEnabled.collectAsStateWithLifecycle()
    val logLevel by settings.logLevel.collectAsStateWithLifecycle()
    var logPickerOpen by remember { mutableStateOf(false) }

    Section(header = "Session", footer = "Sent in the /sessions request body.") {
        Column {
            ToggleRow("Streaming enabled", streamingEnabled) { settings.setStreamingEnabled(it) }
            if (hasAnySession && streamingEnabled != lastApplied) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_error_circle),
                        contentDescription = null,
                        tint = SystemOrange,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        "Won't apply to current session — start fresh to take effect.",
                        fontSize = 11.sp,
                        color = SystemOrange,
                    )
                }
            }
        }
        RowDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { logPickerOpen = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Log level", fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(logLevelName(logLevel), fontSize = 15.sp, color = SecondaryLabel)
            Box {
                DropdownMenu(expanded = logPickerOpen, onDismissRequest = { logPickerOpen = false }) {
                    listOf(LogLevel.NONE, LogLevel.ERROR, LogLevel.WARN, LogLevel.INFO, LogLevel.DEBUG).forEach { l ->
                        DropdownMenuItem(
                            text = { Text(logLevelName(l)) },
                            onClick = {
                                settings.setLogLevel(l)
                                logPickerOpen = false
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun logLevelName(l: LogLevel): String = when (l) {
    LogLevel.NONE -> "None"; LogLevel.ERROR -> "Error"; LogLevel.WARN -> "Warn"
    LogLevel.INFO -> "Info"; LogLevel.DEBUG -> "Debug"
}

// MARK: - Advanced timing

@Composable
private fun AdvancedSection(settings: DevSettings) {
    val heartbeat by settings.heartbeatIntervalSeconds.collectAsStateWithLifecycle()
    val timeout by settings.sessionTimeoutSeconds.collectAsStateWithLifecycle()
    val maxReconnects by settings.maxReconnectAttempts.collectAsStateWithLifecycle()

    Section(
        header = "Advanced",
        footer = "0 = use SDK default. Server SessionCapabilities still wins after SESSION_START.",
    ) {
        StepperRow(
            label = "Heartbeat",
            value = secondsLabel(heartbeat, defaultLabel = "30s"),
            onDecrement = { settings.setHeartbeatIntervalSeconds((heartbeat - 5).coerceAtLeast(0)) },
            onIncrement = { settings.setHeartbeatIntervalSeconds((heartbeat + 5).coerceAtMost(300)) },
        )
        RowDivider()
        StepperRow(
            label = "Session timeout",
            value = secondsLabel(timeout, defaultLabel = "10m"),
            onDecrement = { settings.setSessionTimeoutSeconds((timeout - 60).coerceAtLeast(0)) },
            onIncrement = { settings.setSessionTimeoutSeconds((timeout + 60).coerceAtMost(86_400)) },
        )
        RowDivider()
        StepperRow(
            label = "Max reconnects",
            value = if (maxReconnects == 0) "10" else "$maxReconnects",
            onDecrement = { settings.setMaxReconnectAttempts((maxReconnects - 1).coerceAtLeast(0)) },
            onIncrement = { settings.setMaxReconnectAttempts((maxReconnects + 1).coerceAtMost(50)) },
        )
    }
}

private fun secondsLabel(secs: Int, defaultLabel: String): String {
    if (secs == 0) return "$defaultLabel (default)"
    if (secs >= 3_600) return "${secs / 3_600}h" + if (secs % 3_600 == 0) "" else " ${secs % 3_600 / 60}m"
    if (secs >= 60) return "${secs / 60}m" + if (secs % 60 == 0) "" else " ${secs % 60}s"
    return "${secs}s"
}

// MARK: - Cursor (debug only — read-only for now)

@Composable
private fun CursorSection() {
    Section(
        header = "Reconnect Cursor",
        footer = "Debug only. Overriding the cursor requires an SDK debug hook that isn't exposed yet — " +
            "ask the SDK author to add a public setCursorOverride() if you need to test replay from an " +
            "arbitrary sequence.",
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Override", fontSize = 15.sp, color = SecondaryLabel, modifier = Modifier.weight(1f))
            Text("Not supported", fontSize = 15.sp, color = SecondaryLabel)
        }
    }
}

// MARK: - Building blocks (section / rows)

@Composable
private fun Section(header: String, footer: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            header.uppercase(),
            fontSize = 12.sp,
            color = SecondaryLabel,
            modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White),
        ) { content() }
        footer?.let {
            Text(
                it,
                fontSize = 11.sp,
                color = SecondaryLabel,
                modifier = Modifier.padding(start = 16.dp, top = 6.dp),
            )
        }
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White),
        ) { content() }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(color = SystemGray5, modifier = Modifier.padding(start = 16.dp))
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 15.sp, color = SecondaryLabel)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = SystemGreen),
        )
    }
}

@Composable
private fun ActionRow(
    title: String,
    icon: Int,
    enabled: Boolean,
    isDestructive: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> SecondaryLabel.copy(alpha = 0.4f)
        isDestructive -> SystemRed
        else -> Color.Black
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Text(title, fontSize = 15.sp, color = tint)
    }
}

@Composable
private fun StepperRow(label: String, value: String, onDecrement: () -> Unit, onIncrement: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 15.sp, color = SecondaryLabel)
        StepperButton("−", onDecrement)
        StepperButton("+", onIncrement)
    }
}

@Composable
private fun StepperButton(symbol: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(SystemGray5)
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (symbol == "+") "Increment" else "Decrement" },
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SettingsTextField(
    value: String,
    placeholder: String,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onValueChange: (String) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(SystemGray6)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        if (value.isEmpty()) {
            Text(placeholder, fontSize = 14.sp, color = SecondaryLabel, fontFamily = FontFamily.Monospace)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            keyboardOptions = keyboardOptions,
            textStyle = TextStyle(fontSize = 14.sp, color = Color.Black, fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
