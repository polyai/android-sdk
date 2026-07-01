// Copyright PolyAI Limited

package ai.poly.examples.voice.compose

import ai.poly.messaging.Configuration
import ai.poly.messaging.voice.CallState
import ai.poly.voice.AudioDevice
import ai.poly.voice.PolyVoice
import ai.poly.voice.VoiceOptions
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * The smallest thing that makes a voice call: build a [ai.poly.voice.VoiceCall], request the mic
 * permission, start/end it, and render its [CallState]. One screen, one button.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { CallScreen() }
            }
        }
    }
}

@Composable
private fun CallScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    // One call object for this screen; closed when the screen leaves the composition.
    // Fill in your connector from Agent Studio › Connector Settings (see the README's "Use your own agent").
    val call = remember {
        PolyVoice.call(
            context = context,
            config = Configuration(
                apiKey = "YOUR_API_KEY", // connector token, sent as X-Token
                // environment defaults to Environment.US; hostIdentifier defaults to this app's package name
            ),
            options = VoiceOptions(webrtcToken = "YOUR_WEBRTC_TOKEN"), // the connector's WebRTC token (distinct from apiKey)
        )
    }
    DisposableEffect(Unit) { onDispose { CallForegroundService.stop(context); call.close() } }

    val state by call.state.collectAsStateWithLifecycle()
    val audio by call.audio.collectAsStateWithLifecycle()
    var muted by remember { mutableStateOf(false) }
    var autoOutput by remember { mutableStateOf(true) } // false once the user pins an output device

    // Stop the foreground service once the call leaves an active state (ended / failed).
    LaunchedEffect(state) {
        if (state is CallState.Ended || state is CallState.Failed) CallForegroundService.stop(context)
    }

    // Start the mic foreground service BEFORE the call so it survives the app being backgrounded.
    fun startCall() {
        autoOutput = true
        CallForegroundService.start(context)
        scope.launch { runCatching { call.start() } }
    }

    val requestMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCall()
    }

    fun toggleCall() {
        when (state) {
            is CallState.Connecting, is CallState.Connected -> scope.launch { call.end() }
            else -> {
                muted = false
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) startCall() else requestMic.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    val inCall = state is CallState.Connecting || state is CallState.Connected
    val (status, statusColor) = statusFor(state)

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("PolyAI Voice", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = status,
            color = statusColor,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(40.dp))

        Button(
            onClick = ::toggleCall,
            enabled = state !is CallState.Connecting,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = if (inCall) ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)) else ButtonDefaults.buttonColors(),
        ) {
            Text(
                when (state) {
                    is CallState.Connecting -> "Connecting…"
                    is CallState.Connected -> "End call"
                    else -> "Start call"
                },
            )
        }

        if (state is CallState.Connected) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { muted = !muted; scope.launch { call.setMuted(muted) } },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text(if (muted) "Unmute" else "Mute")
            }

            // Audio output picker — tap to route the live call. The selection confirms via call.audio
            // (Bluetooth can take a few seconds), so the highlight follows the flow, not the tap.
            if (audio.availableDevices.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text("Audio output", style = MaterialTheme.typography.labelLarge, color = Color.DarkGray)
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    // "Auto" follows the connected accessory / system default; tapping a device pins it.
                    FilterChip(
                        selected = autoOutput,
                        onClick = { autoOutput = true; scope.launch { call.setAudioDevice(null) } },
                        label = { Text("Auto") },
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    audio.availableDevices.forEach { device ->
                        FilterChip(
                            selected = !autoOutput && device == audio.selectedDevice,
                            onClick = { autoOutput = false; scope.launch { call.setAudioDevice(device) } },
                            label = { Text(labelFor(device)) },
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun labelFor(device: AudioDevice): String = when (device.type) {
    AudioDevice.Type.SPEAKER_PHONE -> "Speaker"
    AudioDevice.Type.EARPIECE -> "Phone"
    AudioDevice.Type.WIRED_HEADSET -> "Headset"
    AudioDevice.Type.BLUETOOTH -> device.name
    AudioDevice.Type.UNKNOWN -> device.name
}

private fun statusFor(state: CallState): Pair<String, Color> = when (state) {
    is CallState.Idle -> "Tap to call the agent" to Color.DarkGray
    is CallState.Connecting -> "Connecting…" to Color(0xFFEF6C00)
    is CallState.Connected -> "Connected — say hello 👋" to Color(0xFF2E7D32)
    is CallState.Ended -> "Call ended" to Color.DarkGray
    is CallState.Failed -> "Failed: ${state.error.message}" to Color(0xFFD32F2F)
}
