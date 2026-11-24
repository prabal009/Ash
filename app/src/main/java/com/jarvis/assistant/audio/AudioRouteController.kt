package com.jarvis.assistant.audio

import android.media.AudioDeviceInfo
import android.media.MediaRecorder

/** Describes why Ash is opening an audio path so a route controller can choose a matching device. */
enum class AudioRoutePurpose {
    WAKE_STANDBY,
    COMMAND_CAPTURE,
    SPEECH_PLAYBACK,
}

/** Observed or requested microphone route category. */
enum class InputRouteKind {
    BUILT_IN_MIC,
    WIRED_HEADSET,
    USB_HEADSET,
    BLUETOOTH_SCO,
    BLE_AUDIO,
}

/** Observed or requested playback route category. */
enum class OutputRouteKind {
    BUILT_IN_SPEAKER,
    WIRED_HEADSET,
    USB_HEADSET,
    BLUETOOTH_A2DP,
    BLE_AUDIO,
}

/** Why an active audio input or private output route stopped working. */
enum class RouteDisconnectReason {
    BLUETOOTH_DISCONNECTED,
    SCO_DROPPED,
    WIRED_UNPLUGGED,
    BLUETOOTH_DISABLED,
    ROUTE_UNAVAILABLE,
    PRIVATE_OUTPUT_LOST,
}

/** Input capture policy returned to [com.jarvis.assistant.voice.VoiceInput]. */
data class InputRoutePolicy(
    val audioSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
    val preferredDevice: AudioDeviceInfo? = null,
    val expectedKind: InputRouteKind = InputRouteKind.BUILT_IN_MIC,
)

/** Output playback policy returned to [com.jarvis.assistant.voice.AudioOut]. */
data class OutputRoutePolicy(
    val preferredDevice: AudioDeviceInfo? = null,
    val privateOutput: Boolean = false,
    val expectedKind: OutputRouteKind = OutputRouteKind.BUILT_IN_SPEAKER,
)

/** Current verified routing snapshot for UI status, diagnostics, and tests. */
data class ActiveRouteSnapshot(
    val inputKind: InputRouteKind = InputRouteKind.BUILT_IN_MIC,
    val outputKind: OutputRouteKind = OutputRouteKind.BUILT_IN_SPEAKER,
    val bluetoothConnected: Boolean = false,
    val bluetoothMicAvailable: Boolean = false,
    val communicationOwned: Boolean = false,
    val description: String = "phone mic",
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

    /** Called when the selected microphone input drops mid-turn (e.g., earbud disconnect or SCO drop). */
    fun onActiveInputLost(reason: RouteDisconnectReason) {}

    /** Called when private headset playback output disappears mid-reply so speech stops instead of spilling to speaker. */
    fun onPrivateOutputLost(reason: RouteDisconnectReason) {}
}

/**
 * Integration seam for audio routing and peripheral voice triggers.
 *
 * Phase 3 shipped [BuiltInPhoneAudioRouteController]. Phase 4 adds [BluetoothAudioRouteController]
 * managing Bluetooth Classic HFP/SCO, BLE Audio communication devices, wired/USB headsets,
 * output route cleanup, and opt-in headset button activation without modifying the wake-word engine.
 */
interface AudioRouteController {
    /** True while this controller intentionally holds Android's communication audio mode for a voice turn. */
    val ownsCommunicationRoute: Boolean
        get() = false

    /** Begins observing audio route/device changes while the voice service is alive. */
    fun start(listener: AudioRouteListener) {}

    /** Stops observing route changes and tears down any route-owned resources. */
    fun stop() {}

    /** Snapshot of the currently detected and active input/output route. */
    fun currentSnapshot(): ActiveRouteSnapshot = ActiveRouteSnapshot()

    /** Returns the synchronous [InputRoutePolicy] to configure [android.media.AudioRecord]. */
    fun prepareInput(purpose: AudioRoutePurpose): InputRoutePolicy = InputRoutePolicy()

    /**
     * Prepares the microphone route for a command turn asynchronously.
     *
     * When [preferHeadset] is false (for example, a phone-mic wake-word hit), keeps the already active
     * non-SCO route so pre-roll and leading words are not dropped. When [preferHeadset] is true,
     * negotiates a supported Bluetooth mic if available and permitted, falling back cleanly on timeout/refusal.
     */
    fun prepareCommandInput(
        preferHeadset: Boolean,
        timeoutMs: Long = DEFAULT_ROUTE_TIMEOUT_MS,
        onReady: (policy: InputRoutePolicy, usedFallback: Boolean) -> Unit,
    ) {
        onReady(prepareInput(AudioRoutePurpose.COMMAND_CAPTURE), false)
    }

    /** Verifies whether [device] reported by [android.media.AudioRecord.getRoutedDevice] is valid for [purpose]. */
    fun acceptInputDevice(device: AudioDeviceInfo?, purpose: AudioRoutePurpose): Boolean

    /** Returns the synchronous [OutputRoutePolicy] to configure [android.media.AudioTrack]. */
    fun prepareOutput(): OutputRoutePolicy = OutputRoutePolicy()

    /**
     * Releases any turn-only Classic SCO communication link before reply playback so hi-fi A2DP/BLE/wired
     * output is restored, then invokes [onReady] on the main thread with the verified output policy.
     */
    fun prepareOutputForSpeech(onReady: (OutputRoutePolicy) -> Unit) {
        onReady(prepareOutput())
    }

    /** Updates runtime preferences (such as Bluetooth mic preference or opt-in headset button fallback). */
    fun updateSettings(bluetoothMicEnabled: Boolean, headsetButtonFallback: Boolean) {}

    /** Releases any temporary communication/headset routing acquired for an active voice turn. */
    fun releaseSession() {}

    companion object {
        const val DEFAULT_ROUTE_TIMEOUT_MS = 3_000L
    }
}

/** Default Phase 3 route controller: uses the phone's built-in voice-recognition microphone. */
object BuiltInPhoneAudioRouteController : AudioRouteController {
    override fun acceptInputDevice(device: AudioDeviceInfo?, purpose: AudioRoutePurpose): Boolean =
        device != null && device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
}
