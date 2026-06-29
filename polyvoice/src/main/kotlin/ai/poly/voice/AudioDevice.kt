// Copyright PolyAI Limited

package ai.poly.voice

/**
 * An audio output the call can be routed to (earpiece, loudspeaker, wired headset, Bluetooth).
 *
 * Obtain instances from [VoiceCall.getAudio] / the `audio` flow — never construct one. Pass an
 * instance back to [VoiceCall.setAudioDevice] to switch the live call's output, or `null` to revert
 * to automatic routing.
 */
public class AudioDevice internal constructor(
    /** The kind of output, for picking an icon/label without string-matching [name]. */
    @JvmField public val type: Type,
    /** Human-readable label suitable for a device picker (e.g. `"Pixel Buds"`, `"Speaker"`). */
    @JvmField public val name: String,
    // Round-trips a selection back to the platform: AudioDeviceInfo.id on API 31+, a synthetic stable
    // id on legacy. Opaque to consumers; part of identity so two Bluetooth headsets stay distinct.
    internal val nativeId: Int,
) {
    /** The category of an [AudioDevice]. */
    public enum class Type { EARPIECE, SPEAKER_PHONE, WIRED_HEADSET, BLUETOOTH, UNKNOWN }

    override fun equals(other: Any?): Boolean =
        this === other || (other is AudioDevice && other.type == type && other.nativeId == nativeId)

    override fun hashCode(): Int = 31 * type.hashCode() + nativeId

    override fun toString(): String = "AudioDevice($type, \"$name\")"
}

/**
 * A consistent snapshot of audio routing: the outputs available right now and which one is active.
 *
 * Delivered as a single value (rather than two separate flows) so `availableDevices` and
 * `selectedDevice` can never momentarily disagree. Observe it via [VoiceCall.audio] /
 * [VoiceCall.addAudioListener] to drive a device picker.
 */
public class AudioState internal constructor(
    /** Every output the call can currently be routed to. Empty before the call's audio is engaged. */
    @JvmField public val availableDevices: List<AudioDevice>,
    /** The active output, or `null` before the call's audio is engaged. */
    @JvmField public val selectedDevice: AudioDevice?,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is AudioState && other.availableDevices == availableDevices && other.selectedDevice == selectedDevice)

    override fun hashCode(): Int = 31 * availableDevices.hashCode() + (selectedDevice?.hashCode() ?: 0)

    override fun toString(): String = "AudioState(available=$availableDevices, selected=$selectedDevice)"

    internal companion object {
        val EMPTY = AudioState(emptyList(), null)
    }
}
