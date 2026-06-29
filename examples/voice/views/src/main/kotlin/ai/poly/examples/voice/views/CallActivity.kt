// Copyright PolyAI Limited

package ai.poly.examples.voice.views

import ai.poly.examples.voice.views.databinding.ActivityCallBinding
import ai.poly.messaging.Configuration
import ai.poly.messaging.voice.CallState
import ai.poly.voice.AudioDevice
import ai.poly.voice.AudioState
import ai.poly.voice.PolyVoice
import ai.poly.voice.VoiceOptions
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The smallest voice call in classic Android Views: build a [ai.poly.voice.VoiceCall], request the
 * mic permission, start/end it, render its [CallState], and switch the audio output mid-call. The
 * Views counterpart of the Compose `MainActivity`.
 */
class CallActivity : ComponentActivity() {

    private lateinit var binding: ActivityCallBinding

    // One call object for this screen; closed in onDestroy.
    // Fill in your connector from Agent Studio › Connector Settings (see the README's "Use your own agent").
    private val call by lazy {
        PolyVoice.call(
            context = this,
            config = Configuration(
                apiKey = "YOUR_API_KEY", // connector token, sent as X-Token
                // environment defaults to Environment.US; hostIdentifier defaults to this app's package name
            ),
            options = VoiceOptions(webrtcToken = "YOUR_WEBRTC_TOKEN"), // the connector's WebRTC token (distinct from apiKey)
        )
    }

    private var muted = false
    private var autoOutput = true // false once the user pins an output device

    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCall()
    }

    // Start the mic foreground service BEFORE the call so it survives the app being backgrounded.
    private fun startCall() {
        autoOutput = true
        CallForegroundService.start(this)
        lifecycleScope.launch { runCatching { call.start() } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.callButton.setOnClickListener { toggleCall() }
        binding.muteButton.setOnClickListener {
            muted = !muted
            binding.muteButton.text = if (muted) "Unmute" else "Mute"
            lifecycleScope.launch { call.setMuted(muted) }
        }

        // Render the live call state + audio routing, lifecycle-aware. Both flows drive the UI together
        // (combined), so the output picker appears the moment the call is Connected even though the
        // device snapshot was published earlier, while Connecting — the Views analog of Compose
        // recomposing whenever either state or audio changes.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(call.state, call.audio) { state, audio -> state to audio }
                    .distinctUntilChanged()
                    .collect { (state, audio) ->
                        render(state)
                        renderDevices(state, audio)
                    }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        CallForegroundService.stop(this)
        call.close()
    }

    private fun toggleCall() {
        when (call.state.value) {
            is CallState.Connecting, is CallState.Connected -> lifecycleScope.launch { call.end() }
            else -> {
                muted = false
                binding.muteButton.text = "Mute"
                val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) startCall()
                else requestMic.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun render(state: CallState) {
        // Stop the foreground service once the call leaves an active state.
        if (state is CallState.Ended || state is CallState.Failed) CallForegroundService.stop(this)
        val connected = state is CallState.Connected
        binding.status.text = when (state) {
            is CallState.Idle -> "Tap to call the agent"
            is CallState.Connecting -> "Connecting…"
            is CallState.Connected -> "Connected — say hello 👋"
            is CallState.Ended -> "Call ended"
            is CallState.Failed -> "Failed: ${state.error.message}"
        }
        binding.callButton.text = when (state) {
            is CallState.Connecting -> "Connecting…"
            is CallState.Connected -> "End call"
            else -> "Start call"
        }
        binding.callButton.isEnabled = state !is CallState.Connecting
        binding.muteButton.visibility = if (connected) View.VISIBLE else View.GONE
    }

    /** Rebuild the audio-output picker from the current call state + [AudioState] snapshot. */
    private fun renderDevices(state: CallState, audio: AudioState) {
        val show = state is CallState.Connected && audio.availableDevices.isNotEmpty()
        binding.audioLabel.visibility = if (show) View.VISIBLE else View.GONE
        binding.deviceRow.visibility = if (show) View.VISIBLE else View.GONE
        binding.deviceRow.removeAllViews()
        if (!show) return
        // "Auto" follows the connected accessory / system default; tapping a device pins it.
        binding.deviceRow.addView(
            Button(this).apply {
                text = "Auto"
                isAllCaps = false
                alpha = if (autoOutput) 1f else 0.4f
                setOnClickListener { autoOutput = true; lifecycleScope.launch { call.setAudioDevice(null) } }
            },
        )
        audio.availableDevices.forEach { device ->
            binding.deviceRow.addView(
                Button(this).apply {
                    text = labelFor(device)
                    isAllCaps = false
                    // The selection confirms via call.audio (Bluetooth can take a few seconds), so the
                    // highlight follows the flow, not the tap.
                    alpha = if (!autoOutput && device == audio.selectedDevice) 1f else 0.4f
                    setOnClickListener { autoOutput = false; lifecycleScope.launch { call.setAudioDevice(device) } }
                },
            )
        }
    }

    private fun labelFor(device: AudioDevice): String = when (device.type) {
        AudioDevice.Type.SPEAKER_PHONE -> "Speaker"
        AudioDevice.Type.EARPIECE -> "Phone"
        AudioDevice.Type.WIRED_HEADSET -> "Headset"
        AudioDevice.Type.BLUETOOTH -> device.name
        AudioDevice.Type.UNKNOWN -> device.name
    }
}
