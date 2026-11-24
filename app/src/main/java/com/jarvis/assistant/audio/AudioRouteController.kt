package com.jarvis.assistant.audio

import android.media.AudioDeviceInfo
import android.media.MediaRecorder

/** Describes why Ash is opening an audio path so a route controller can choose a matching device. */
enum class AudioRoutePurpose {
    WAKE_STANDBY,
    COMMAND_CAPTURE,
    SPEECH_PLAYBACK,
}

/** Input capture policy returned to [com.jarvis.assistant.voice.VoiceInput]. */
data class InputRoutePolicy(
    val audioSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
    val preferredDevice: AudioDeviceInfo? = null,
)

/** Output playback policy returned to [com.jarvis.assistant.voice.AudioOut]. */
data class OutputRoutePolicy(
    val preferredDevice: AudioDeviceInfo? = null,
)

/** External hardware/headset activation events forwarded into [com.jarvis.assistant.service.JarvisService]. */
enum class RouteTrigger {
    HEADSET_BUTTON,
    BLUETOOTH_VOICE_ACTIVATION,
}

/** Callbacks from an [AudioRouteController] into the active voice service. */
interface AudioRouteListener {
    /** Called when the active input/output route changes (e.g., Bluetooth headset connected or disconnected). */
    fun onRouteChanged(reason: String) {}

    /** Called when a connected peripheral requests a voice turn (e.g., headphone button press). */
    fun onRouteTrigger(trigger: RouteTrigger) {}
}

/**
 * Integration seam for audio routing and peripheral voice triggers.
 *
 * Phase 3 ships [BuiltInPhoneAudioRouteController], which verifies built-in phone microphone capture.
 * Phase 4 Bluetooth integration can supply an implementation managing Bluetooth Classic HFP/SCO,
 * BLE Audio communication devices, and MediaSession/headset button triggers without modifying
 * [com.jarvis.assistant.voice.VoiceInput] or [com.jarvis.assistant.service.JarvisService] internals.
 */
interface AudioRouteController {
    /** Begins observing audio route/device changes while the voice service is alive. */
    fun start(listener: AudioRouteListener) {}

    /** Stops observing route changes and tears down any route-owned resources. */
    fun stop() {}

    /** Returns the [InputRoutePolicy] to configure [android.media.AudioRecord]. */
    fun prepareInput(purpose: AudioRoutePurpose): InputRoutePolicy = InputRoutePolicy()

    /** Verifies whether [device] reported by [android.media.AudioRecord.getRoutedDevice] is valid for [purpose]. */
    fun acceptInputDevice(device: AudioDeviceInfo?, purpose: AudioRoutePurpose): Boolean

    /** Returns the [OutputRoutePolicy] to configure [android.media.AudioTrack]. */
    fun prepareOutput(): OutputRoutePolicy = OutputRoutePolicy()

    /** Releases any temporary communication/headset routing acquired for an active voice turn. */
    fun releaseSession() {}
}

/** Default Phase 3 route controller: uses the phone's built-in voice-recognition microphone. */
object BuiltInPhoneAudioRouteController : AudioRouteController {
    override fun acceptInputDevice(device: AudioDeviceInfo?, purpose: AudioRoutePurpose): Boolean =
        device != null && device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
}
