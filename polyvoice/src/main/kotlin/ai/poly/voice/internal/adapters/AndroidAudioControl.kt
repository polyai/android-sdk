// Copyright PolyAI Limited

package ai.poly.voice.internal.adapters

import ai.poly.messaging.PolyLogger
import ai.poly.voice.AudioDevice
import ai.poly.voice.AudioState
import ai.poly.voice.internal.log.d
import ai.poly.voice.internal.log.w
import ai.poly.voice.internal.ports.AudioControl
import ai.poly.voice.internal.ports.AudioInterruption
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.annotation.TargetApi
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

/**
 * Routes platform audio for a live call: switches `AudioManager` into `MODE_IN_COMMUNICATION`, holds
 * audio focus, applies an output route, and keeps a live [AudioState] of the outputs available and
 * the active one — so the SDK consumer can build a device picker and switch mid-call.
 *
 * Two implementations behind one surface: API 31+ uses the modern communication-device model
 * (`setCommunicationDevice` / `availableCommunicationDevices` / `addOnCommunicationDeviceChangedListener`);
 * API 24–30 uses `isSpeakerphoneOn` + the Bluetooth SCO dance + `AudioDeviceCallback`. Platform
 * device-change callbacks fire on a Handler thread and re-route (in auto mode) + publish; all route
 * mutation + flag access is serialised by `routeLock` against the coordinator-thread public API.
 *
 * Default routing is **accessory-aware**: on [activate] — and whenever devices change while no manual
 * selection is active — it follows a connected wired/Bluetooth headset, falling back to [useSpeakerphone]
 * (loudspeaker, or the earpiece) when nothing is plugged in. An explicit [selectAudioDevice] pins the
 * route until the consumer passes `null` to return to automatic.
 *
 * @param useSpeakerphone the fallback route when NO headset/Bluetooth is connected: loudspeaker
 *   (hands-free, the natural mode for a voice agent) when true, else the earpiece. Not JVM-tested
 *   (needs the platform `AudioManager` + a real device).
 */
internal class AndroidAudioControl(
    context: Context,
    private val logger: PolyLogger,
    private val useSpeakerphone: Boolean = true,
) : AudioControl {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { command -> handler.post(command) }

    private val _audio = MutableStateFlow(AudioState.EMPTY)
    override val audio: StateFlow<AudioState> = _audio.asStateFlow()

    private val _interruptions = MutableSharedFlow<AudioInterruption>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val interruptions: Flow<AudioInterruption> = _interruptions.asSharedFlow()

    private var previousMode: Int = AudioManager.MODE_NORMAL
    private var focusRequest: AudioFocusRequest? = null

    // The platform delivers focus changes here (on a Handler thread). Map them to interruptions the
    // coordinator acts on — transient losses mute, permanent losses (incoming call) end the call.
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> _interruptions.tryEmit(AudioInterruption.PERMANENT_LOSS)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> _interruptions.tryEmit(AudioInterruption.TRANSIENT_LOSS)
            AudioManager.AUDIOFOCUS_GAIN -> _interruptions.tryEmit(AudioInterruption.GAINED)
        }
    }

    @Suppress("DEPRECATION")
    private var previousSpeakerphoneOn: Boolean = false

    // Tracks whether activate() ran, so deactivate() only restores state we changed and never clobbers
    // process-global audio mode/route on a built-but-never-started call.
    private var activated = false

    // true = follow the system / connected accessory automatically; false once the consumer pins a
    // device via selectAudioDevice (until they pass null to return to automatic).
    private var autoRoute = true

    // The id of a device the consumer pinned (manual mode), so we can fall back to auto if it disappears.
    private var pinnedDeviceId: Int? = null

    // Serialises all AudioManager route mutation + autoRoute/activated/pinnedDeviceId access. The public
    // API (activate/deactivate/selectAudioDevice) runs on the coordinator's confined dispatcher; the
    // platform device-change callbacks fire on a Handler thread — both now re-route, so they must not race.
    private val routeLock = Any()

    // ── platform observers (registered in activate, removed in deactivate) ─────────────────────────
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { onDevicesChanged() }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { onDevicesChanged() }
    }

    // A headset/Bluetooth connecting or disconnecting. In auto mode, re-pick the best route — follow a
    // newly-connected accessory, fall back when it's unplugged. A manual selection is left pinned, unless
    // the pinned device itself disappeared (then fall back to automatic). Serialised vs the public API.
    private fun onDevicesChanged(): Unit = synchronized(routeLock) {
        // Runs on a platform callback thread — swallow+log any AudioManager throw (as the siblings do)
        // so it can't crash the binder thread, and still publish the fresh snapshot afterwards.
        runCatching {
            if (activated) {
                if (autoRoute) {
                    applyAutoRoute()
                } else {
                    val pinned = pinnedDeviceId
                    if (pinned != null && availableDeviceInfos().none { it.id == pinned }) {
                        autoRoute = true
                        pinnedDeviceId = null
                        applyAutoRoute()
                    }
                }
            }
        }.onFailure { logger.w("[voice] onDevicesChanged route failed", mapOf("error" to (it.message ?: ""))) }
        publishSnapshot()
    }

    private val commDeviceChangedListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        AudioManager.OnCommunicationDeviceChangedListener { publishSnapshot() }
    } else {
        null
    }

    // Headset yank → in auto mode, re-route (falls back to speaker/earpiece) and re-publish.
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                logger.d("[voice] audio becoming noisy (headset unplugged) — re-routing")
                onDevicesChanged()
            }
        }
    }

    // Bluetooth SCO connect/disconnect (legacy) → re-publish the active route.
    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { publishSnapshot() }
    }

    // ── lifecycle ──────────────────────────────────────────────────────────────────────────────────

    override fun activate() = synchronized(routeLock) {
        runCatching {
            previousMode = audioManager.mode
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            // Mark activated as soon as global state is mutated so a throw in a later step still gets
            // torn down by deactivate() — otherwise the process is stranded in MODE_IN_COMMUNICATION.
            activated = true
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                @Suppress("DEPRECATION")
                run { previousSpeakerphoneOn = audioManager.isSpeakerphoneOn } // save before we touch it
            }
            requestFocus()
            registerObservers()
            autoRoute = true
            pinnedDeviceId = null
            applyAutoRoute()
            publishSnapshot()
        }.onFailure { logger.w("[voice] audio activate failed", mapOf("error" to (it.message ?: ""))) }
        Unit
    }

    override fun deactivate(): Unit = synchronized(routeLock) {
        if (!activated) return@synchronized // nothing was acquired — don't touch the system audio state
        runCatching {
            unregisterObservers()
            restoreAudioRoute()
            abandonFocus()
            audioManager.mode = previousMode
            activated = false
            autoRoute = true
            pinnedDeviceId = null
            _audio.value = AudioState.EMPTY
        }.onFailure { logger.w("[voice] audio deactivate failed", mapOf("error" to (it.message ?: ""))) }
        Unit
    }

    // ── routing ──────────────────────────────────────────────────────────────────────────────────

    override fun selectAudioDevice(device: AudioDevice?): Unit = synchronized(routeLock) {
        if (!activated) return@synchronized
        runCatching {
            if (device == null) {
                autoRoute = true
                pinnedDeviceId = null
                applyAutoRoute() // back to following the system / connected accessory
            } else {
                autoRoute = false // pin the consumer's explicit choice
                pinnedDeviceId = device.nativeId
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) selectCommDevice(device) else selectLegacy(device.type)
            }
            publishSnapshot()
        }.onFailure { logger.w("[voice] selectAudioDevice failed", mapOf("error" to (it.message ?: ""))) }
        Unit
    }

    /** Accessory-aware default route: a connected wired/Bluetooth headset wins; otherwise speaker/earpiece. */
    private fun applyAutoRoute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = availableDeviceInfos()
            val accessory = devices.firstOrNull { typeOf(it) == AudioDevice.Type.WIRED_HEADSET || typeOf(it) == AudioDevice.Type.BLUETOOTH }
            val fallback = if (useSpeakerphone) AudioDevice.Type.SPEAKER_PHONE else AudioDevice.Type.EARPIECE
            val target = accessory ?: devices.firstOrNull { typeOf(it) == fallback }
            if (target != null) {
                if (!audioManager.setCommunicationDevice(target)) {
                    logger.w("[voice] auto-route setCommunicationDevice rejected", mapOf("type" to typeOf(target).name))
                }
            } else {
                audioManager.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            run {
                val types = availableDeviceInfos().map { typeOf(it) }
                when {
                    AudioDevice.Type.BLUETOOTH in types -> selectLegacy(AudioDevice.Type.BLUETOOTH)
                    AudioDevice.Type.WIRED_HEADSET in types -> selectLegacy(AudioDevice.Type.WIRED_HEADSET)
                    else -> selectLegacy(if (useSpeakerphone) AudioDevice.Type.SPEAKER_PHONE else AudioDevice.Type.EARPIECE)
                }
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.S) // only called from selectAudioDevice's SDK_INT >= S branch
    private fun selectCommDevice(device: AudioDevice?) {
        if (device == null) {
            audioManager.clearCommunicationDevice() // revert to automatic selection
            return
        }
        val target = availableDeviceInfos().firstOrNull { it.id == device.nativeId }
        if (target == null) {
            logger.w("[voice] requested audio device no longer available", mapOf("name" to device.name))
            return
        }
        if (!audioManager.setCommunicationDevice(target)) {
            logger.w("[voice] setCommunicationDevice rejected — using default route", mapOf("name" to device.name))
        }
    }

    @Suppress("DEPRECATION")
    private fun selectLegacy(type: AudioDevice.Type?) {
        when (type) {
            AudioDevice.Type.BLUETOOTH -> {
                audioManager.isSpeakerphoneOn = false
                if (!audioManager.isBluetoothScoOn) {
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                }
            }
            AudioDevice.Type.SPEAKER_PHONE -> { stopScoIfOn(); audioManager.isSpeakerphoneOn = true }
            AudioDevice.Type.EARPIECE -> { stopScoIfOn(); audioManager.isSpeakerphoneOn = false }
            AudioDevice.Type.WIRED_HEADSET -> { stopScoIfOn(); audioManager.isSpeakerphoneOn = false }
            // null/UNKNOWN → automatic: drop SCO + speaker and let the system pick by priority.
            else -> { stopScoIfOn(); audioManager.isSpeakerphoneOn = false }
        }
    }

    @Suppress("DEPRECATION")
    private fun stopScoIfOn() {
        if (audioManager.isBluetoothScoOn) {
            audioManager.isBluetoothScoOn = false
            audioManager.stopBluetoothSco()
        }
    }

    private fun restoreAudioRoute() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        } else {
            @Suppress("DEPRECATION")
            runCatching { stopScoIfOn(); audioManager.isSpeakerphoneOn = previousSpeakerphoneOn }
        }
    }

    // ── enumeration / snapshot ─────────────────────────────────────────────────────────────────────

    private fun publishSnapshot(): Unit = synchronized(routeLock) {
        if (activated) runCatching { _audio.value = AudioState(enumerateDevices(), currentSelectedDevice()) }
        Unit
    }

    private fun availableDeviceInfos(): List<AudioDeviceInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices
        } else {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }

    private fun enumerateDevices(): List<AudioDevice> =
        availableDeviceInfos()
            .map { it to typeOf(it) }
            .filter { it.second != AudioDevice.Type.UNKNOWN }
            .distinctBy { it.first.id }
            .map { (info, type) -> AudioDevice(type, displayName(info, type), info.id) }

    private fun currentSelectedDevice(): AudioDevice? {
        val devices = enumerateDevices()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val current = audioManager.communicationDevice ?: return null
            return devices.firstOrNull { it.nativeId == current.id }
        }
        // Legacy: infer the active route from AudioManager flags + present outputs.
        @Suppress("DEPRECATION")
        val inferred = when {
            audioManager.isBluetoothScoOn -> AudioDevice.Type.BLUETOOTH
            devices.any { it.type == AudioDevice.Type.WIRED_HEADSET } -> AudioDevice.Type.WIRED_HEADSET
            audioManager.isSpeakerphoneOn -> AudioDevice.Type.SPEAKER_PHONE
            else -> AudioDevice.Type.EARPIECE
        }
        return devices.firstOrNull { it.type == inferred }
    }

    private fun typeOf(info: AudioDeviceInfo): AudioDevice.Type = when (info.type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioDevice.Type.EARPIECE
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioDevice.Type.SPEAKER_PHONE
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        -> AudioDevice.Type.WIRED_HEADSET
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioDevice.Type.BLUETOOTH
        else ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.type == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                AudioDevice.Type.BLUETOOTH
            } else {
                AudioDevice.Type.UNKNOWN
            }
    }

    /** A friendly label: a fixed name for the built-ins, else the device's product name. */
    private fun displayName(info: AudioDeviceInfo, type: AudioDevice.Type): String = when (type) {
        AudioDevice.Type.EARPIECE -> "Earpiece"
        AudioDevice.Type.SPEAKER_PHONE -> "Speaker"
        else -> info.productName?.toString()?.takeIf { it.isNotBlank() } ?: type.name.lowercase().replaceFirstChar { it.uppercase() }
    }

    // ── observer registration ──────────────────────────────────────────────────────────────────────

    private fun registerObservers() {
        runCatching { audioManager.registerAudioDeviceCallback(deviceCallback, handler) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            commDeviceChangedListener?.let {
                runCatching { audioManager.addOnCommunicationDeviceChangedListener(mainExecutor, it) }
            }
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        }
        registerReceiver(becomingNoisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    private fun unregisterObservers() {
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            commDeviceChangedListener?.let {
                runCatching { audioManager.removeOnCommunicationDeviceChangedListener(it) }
            }
        } else {
            runCatching { appContext.unregisterReceiver(scoReceiver) }
        }
        runCatching { appContext.unregisterReceiver(becomingNoisyReceiver) }
    }

    private fun registerReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(receiver, filter)
            }
        }
    }

    // ── audio focus ────────────────────────────────────────────────────────────────────────────────

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN,
            )
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
    }
}
