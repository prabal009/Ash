/*
 * Bluetooth HFP/SCO routing state machine and AudioDeviceCallback drop classification
 * adapted from lovisschmidt/yunto (MIT License, Copyright (c) 2026 Lovis Schmidt),
 * rewritten in pure native Kotlin for Project Ash with BLE Audio (API 31+),
 * API 26–30 SCO compatibility, BLUETOOTH_CONNECT permission guards, generation-scoped
 * timer cancellation, and zero Expo/React Native dependencies.
 */
package com.jarvis.assistant.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Production Phase 4 native audio & Bluetooth route controller.
 *
 * Route policy:
 * - Wake standby (`WAKE_STANDBY`): uses built-in phone mic (or wired/USB headset mic if plugged in).
 *   Never holds Classic Bluetooth SCO while idle so A2DP music stays hi-fi and earbuds do not drain.
 * - Command capture (`COMMAND_CAPTURE`):
 *   - Phone-mic wake hit (`preferHeadset = false`): keeps the running phone/wired mic so pre-roll
 *     and leading command words are never lost mid-sentence.
 *   - Headset / assistant / Talk turn (`preferHeadset = true`): negotiates BLE Audio (`TYPE_BLE_HEADSET`)
 *     first on API 31+, then Classic HFP/SCO (`TYPE_BLUETOOTH_SCO`), with bounded timeout and automatic
 *     teardown + verified fallback to wired or phone mic on refusal, timeout, A2DP-only headset, or
 *     missing `BLUETOOTH_CONNECT` permission.
 * - Speech playback (`SPEECH_PLAYBACK`): tears down turn-only SCO or BLE communication before reply output,
 *   routes to active Bluetooth A2DP / BLE Audio / wired / USB / speaker, and stops private output if
 *   the headset disappears mid-reply.
 */
class BluetoothAudioRouteController(
    context: Context,
    private val gateway: AudioSystemGateway = AndroidAudioSystemGateway(context),
    private val main: Handler = Handler(Looper.getMainLooper()),
    private val enableButtonBridge: Boolean = true,
    private val scoSettleMs: Long = SCO_SETTLE_MS,
    private val teardownSettleMs: Long = OUTPUT_SETTLE_MS,
    private val teardownGraceMs: Long = TEARDOWN_GRACE_MS,
) : AudioRouteController {

    private val app = context.applicationContext
    private var listener: AudioRouteListener? = null
    private var started = false
    private var bluetoothMicEnabled = true
    private var headsetButtonFallback = false
    private var ownedButtonBridge: HeadsetButtonBridge? = null

    private var routeGeneration = 0L
    @Volatile private var communicationOwned = false
    @Volatile private var expectingTeardown = false
    @Volatile private var activeInputPolicy = InputRoutePolicy()
    @Volatile private var activeOutputPolicy = OutputRoutePolicy()
    @Volatile private var activeCommunicationKind: InputRouteKind? = null

    private var pendingNegotiation: Negotiation? = null
    private var pendingOutputReady: Runnable? = null
    private var teardownGraceClear: Runnable? = null

    private class Negotiation(
        val generation: Long,
        val targetKind: InputRouteKind,
        val targetEndpoint: AudioEndpoint?,
        val onReady: (InputRoutePolicy, Boolean) -> Unit,
    ) {
        var settled = false
        var timeoutTask: Runnable? = null
        var settleTask: Runnable? = null
    }

    private val observer = object : AudioSystemObserver {
        override fun onDevicesAdded(devices: List<AudioEndpoint>) {
            val relevant = devices.any { isBluetoothType(it.type) || isWiredOrUsbType(it.type) }
            if (!relevant) return
            refreshButtonBridge()
            listener?.onRouteChanged("device_added")
        }

        override fun onDevicesRemoved(devices: List<AudioEndpoint>) {
            val btOutputGone = devices.any { isBluetoothOutputType(it.type) }
            val wiredGone = devices.any { isWiredOrUsbType(it.type) }
            val scoGone = devices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            val bleGone = devices.any { it.type == TYPE_BLE_HEADSET_COMPAT }

            // Full Bluetooth headset removal always bypasses expectingTeardown so a real disconnect
            // during our own SCO teardown is never swallowed.
            if (btOutputGone) {
                val hadPrivateOut = activeOutputPolicy.privateOutput &&
                    (activeOutputPolicy.expectedKind == OutputRouteKind.BLUETOOTH_A2DP ||
                        activeOutputPolicy.expectedKind == OutputRouteKind.BLE_AUDIO)
                val hadBtInput = communicationOwned ||
                    activeInputPolicy.expectedKind == InputRouteKind.BLUETOOTH_SCO ||
                    activeInputPolicy.expectedKind == InputRouteKind.BLE_AUDIO
                if (pendingNegotiation != null) {
                    failNegotiationAndFallback(RouteDisconnectReason.BLUETOOTH_DISCONNECTED)
                } else if (hadBtInput) {
                    releaseSessionInternal(clearMode = true)
                    listener?.onActiveInputLost(RouteDisconnectReason.BLUETOOTH_DISCONNECTED)
                }
                if (hadPrivateOut) {
                    activeOutputPolicy = selectFallbackOutputPolicy()
                    listener?.onPrivateOutputLost(RouteDisconnectReason.PRIVATE_OUTPUT_LOST)
                }
                refreshButtonBridge()
                listener?.onRouteChanged("bluetooth_disconnected")
                return
            }

            if (wiredGone) {
                val hadWiredInput = activeInputPolicy.expectedKind == InputRouteKind.WIRED_HEADSET ||
                    activeInputPolicy.expectedKind == InputRouteKind.USB_HEADSET
                val hadWiredOutput = activeOutputPolicy.privateOutput &&
                    (activeOutputPolicy.expectedKind == OutputRouteKind.WIRED_HEADSET ||
                        activeOutputPolicy.expectedKind == OutputRouteKind.USB_HEADSET)
                if (hadWiredInput) {
                    activeInputPolicy = selectNonBluetoothInputPolicy()
                    listener?.onActiveInputLost(RouteDisconnectReason.WIRED_UNPLUGGED)
                }
                if (hadWiredOutput) {
                    activeOutputPolicy = selectFallbackOutputPolicy()
                    listener?.onPrivateOutputLost(RouteDisconnectReason.PRIVATE_OUTPUT_LOST)
                }
                refreshButtonBridge()
                listener?.onRouteChanged("wired_unplugged")
                return
            }

            // SCO/BLE-only removal during intentional teardown is expected; ignore it.
            if (expectingTeardown) return

            if (scoGone || bleGone) {
                val outputStillPresent = gateway.getOutputEndpoints().any { isBluetoothOutputType(it.type) }
                val reason = if (outputStillPresent) RouteDisconnectReason.SCO_DROPPED else RouteDisconnectReason.BLUETOOTH_DISCONNECTED
                if (pendingNegotiation != null) {
                    failNegotiationAndFallback(reason)
                } else if (communicationOwned || activeInputPolicy.expectedKind == InputRouteKind.BLUETOOTH_SCO ||
                    activeInputPolicy.expectedKind == InputRouteKind.BLE_AUDIO
                ) {
                    releaseSessionInternal(clearMode = true)
                    listener?.onActiveInputLost(reason)
                }
                listener?.onRouteChanged(if (outputStillPresent) "sco_dropped" else "bluetooth_disconnected")
            }
        }

        override fun onCommunicationDeviceChanged(device: AudioEndpoint?) {
            val neg = pendingNegotiation ?: return
            if (neg.generation != routeGeneration || neg.settled) return
            if (device != null && matchesCommunicationKind(device.type, neg.targetKind)) {
                scheduleNegotiationSettle(neg)
            }
        }

        override fun onBluetoothAdapterDisabled() {
            val hadBtOutput = activeOutputPolicy.privateOutput &&
                (activeOutputPolicy.expectedKind == OutputRouteKind.BLUETOOTH_A2DP ||
                    activeOutputPolicy.expectedKind == OutputRouteKind.BLE_AUDIO)
            val hadBtInput = communicationOwned || pendingNegotiation != null ||
                activeInputPolicy.expectedKind == InputRouteKind.BLUETOOTH_SCO ||
                activeInputPolicy.expectedKind == InputRouteKind.BLE_AUDIO
            if (pendingNegotiation != null) {
                failNegotiationAndFallback(RouteDisconnectReason.BLUETOOTH_DISABLED)
            } else if (hadBtInput) {
                releaseSessionInternal(clearMode = true)
                listener?.onActiveInputLost(RouteDisconnectReason.BLUETOOTH_DISABLED)
            }
            if (hadBtOutput) {
                activeOutputPolicy = selectFallbackOutputPolicy()
                listener?.onPrivateOutputLost(RouteDisconnectReason.BLUETOOTH_DISABLED)
            }
            refreshButtonBridge()
            listener?.onRouteChanged("bluetooth_disabled")
        }
    }

    override val ownsCommunicationRoute: Boolean
        get() = communicationOwned

    override fun start(listener: AudioRouteListener) {
        this.listener = listener
        if (started) return
        started = true
        gateway.registerObserver(observer, main)
        ownedButtonBridge = if (!enableButtonBridge) null else HeadsetButtonBridge(
            context = app,
            isMusicActive = { gateway.isMusicActive },
            main = main,
            onTrigger = { trigger -> this.listener?.onRouteTrigger(trigger) },
        )
        ownedButtonBridge?.setEnabled(headsetButtonFallback)
        activeInputPolicy = selectNonBluetoothInputPolicy()
        activeOutputPolicy = selectCurrentOutputPolicy()
    }

    override fun stop() {
        started = false
        listener = null
        releaseSessionInternal(clearMode = true)
        cancelPendingOutput()
        cancelTeardownGrace()
        expectingTeardown = false
        gateway.unregisterObserver()
        ownedButtonBridge?.release()
        ownedButtonBridge = null
    }

    override fun updateSettings(bluetoothMicEnabled: Boolean, headsetButtonFallback: Boolean) {
        this.bluetoothMicEnabled = bluetoothMicEnabled
        this.headsetButtonFallback = headsetButtonFallback
        ownedButtonBridge?.setEnabled(headsetButtonFallback)
    }

    override fun currentSnapshot(): ActiveRouteSnapshot {
        val inputs = gateway.getInputEndpoints()
        val outputs = gateway.getOutputEndpoints()
        val hasPerm = gateway.hasBluetoothConnectPermission()
        val btConnected = hasPerm && outputs.any { isBluetoothType(it.type) }
        val btMicAvailable = hasPerm && bluetoothMicEnabled && findPreferredBluetoothCommEndpoint(inputs, outputs) != null
        val inputKind = activeInputPolicy.expectedKind
        val outputKind = selectCurrentOutputPolicy().expectedKind
        val label = when (inputKind) {
            InputRouteKind.BLE_AUDIO -> "Bluetooth LE mic"
            InputRouteKind.BLUETOOTH_SCO -> "Bluetooth mic"
            InputRouteKind.WIRED_HEADSET -> "wired mic"
            InputRouteKind.USB_HEADSET -> "USB mic"
            InputRouteKind.BUILT_IN_MIC -> "phone mic"
        }
        return ActiveRouteSnapshot(
            inputKind = inputKind,
            outputKind = outputKind,
            bluetoothConnected = btConnected,
            bluetoothMicAvailable = btMicAvailable,
            communicationOwned = communicationOwned,
            description = label,
        )
    }

    override fun prepareInput(purpose: AudioRoutePurpose): InputRoutePolicy {
        if (purpose == AudioRoutePurpose.WAKE_STANDBY) {
            // Standby wake detection never holds Classic SCO: preserves hi-fi A2DP music and battery.
            return selectNonBluetoothInputPolicy().also { activeInputPolicy = it }
        }
        if (communicationOwned && activeCommunicationKind != null) {
            return activeInputPolicy
        }
        return selectNonBluetoothInputPolicy().also { activeInputPolicy = it }
    }

    override fun prepareCommandInput(
        preferHeadset: Boolean,
        timeoutMs: Long,
        onReady: (policy: InputRoutePolicy, usedFallback: Boolean) -> Unit,
    ) {
        cancelPendingOutput()
        // A new request replaces any earlier negotiation, including when the new turn uses the phone.
        if (communicationOwned) releaseSessionInternal(clearMode = true)
        // If phone/VoIP call is active, never fight Android's audio mode.
        if (isExternalCallActive()) {
            val fallback = selectNonBluetoothInputPolicy().also { activeInputPolicy = it }
            onReady(fallback, true)
            return
        }
        // Phone-mic wake hit stays on the active non-SCO route so pre-roll syllables aren't clipped.
        if (!preferHeadset || !bluetoothMicEnabled) {
            val policy = selectNonBluetoothInputPolicy().also { activeInputPolicy = it }
            onReady(policy, false)
            return
        }
        if (!gateway.hasBluetoothConnectPermission()) {
            val fallback = selectNonBluetoothInputPolicy().also { activeInputPolicy = it }
            val hadBtOutput = gateway.getOutputEndpoints().any { isBluetoothOutputType(it.type) }
            onReady(fallback, hadBtOutput)
            return
        }

        val inputs = gateway.getInputEndpoints()
        val outputs = gateway.getOutputEndpoints()
        val btCandidate = findPreferredBluetoothCommEndpoint(inputs, outputs)
        if (btCandidate == null) {
            // Either no BT headset is connected, or it's A2DP-only without a microphone.
            val fallback = selectNonBluetoothInputPolicy().also { activeInputPolicy = it }
            val a2dpOnly = outputs.any { isBluetoothType(it.type) }
            onReady(fallback, a2dpOnly)
            return
        }

        val (kind, endpoint) = btCandidate
        startBluetoothNegotiation(kind, endpoint, timeoutMs.coerceIn(200L, MAX_ROUTE_TIMEOUT_MS), onReady)
    }

    override fun acceptInputDevice(device: AudioDeviceInfo?, purpose: AudioRoutePurpose): Boolean {
        if (device == null) return false
        val type = device.type
        if (purpose == AudioRoutePurpose.WAKE_STANDBY) {
            return when (activeInputPolicy.expectedKind) {
                InputRouteKind.WIRED_HEADSET -> type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_BUILTIN_MIC
                InputRouteKind.USB_HEADSET -> type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_BUILTIN_MIC
                else -> type == AudioDeviceInfo.TYPE_BUILTIN_MIC
            }
        }
        return when (activeInputPolicy.expectedKind) {
            InputRouteKind.BLE_AUDIO -> type == TYPE_BLE_HEADSET_COMPAT
            InputRouteKind.BLUETOOTH_SCO -> type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            InputRouteKind.WIRED_HEADSET -> type == AudioDeviceInfo.TYPE_WIRED_HEADSET
            InputRouteKind.USB_HEADSET -> type == AudioDeviceInfo.TYPE_USB_HEADSET
            InputRouteKind.BUILT_IN_MIC -> type == AudioDeviceInfo.TYPE_BUILTIN_MIC
        }
    }

    override fun prepareOutput(): OutputRoutePolicy =
        selectCurrentOutputPolicy().also { activeOutputPolicy = it }

    override fun prepareOutputForSpeech(onReady: (OutputRoutePolicy) -> Unit) {
        cancelPendingOutput()
        if (communicationOwned) {
            // Both routes are turn-scoped; only Classic needs to settle back to A2DP.
            val waitForA2dp = activeCommunicationKind == InputRouteKind.BLUETOOTH_SCO
            releaseSessionInternal(clearMode = true)
            val gen = routeGeneration
            val deliver = Runnable {
                pendingOutputReady = null
                if (gen != routeGeneration) return@Runnable
                val policy = selectCurrentOutputPolicy().also { activeOutputPolicy = it }
                onReady(policy)
            }
            if (!waitForA2dp || teardownSettleMs <= 0L) {
                deliver.run()
            } else {
                pendingOutputReady = deliver
                main.postDelayed(deliver, teardownSettleMs)
            }
            return
        }
        val policy = selectCurrentOutputPolicy().also { activeOutputPolicy = it }
        onReady(policy)
    }

    override fun releaseSession() {
        cancelPendingOutput()
        releaseSessionInternal(clearMode = true)
        refreshButtonBridge()
    }

    // ---- Internal negotiation & teardown ----

    private fun startBluetoothNegotiation(
        kind: InputRouteKind,
        commEndpoint: AudioEndpoint?,
        timeoutMs: Long,
        onReady: (InputRoutePolicy, Boolean) -> Unit,
    ) {
        cancelTeardownGrace()
        expectingTeardown = false
        val gen = ++routeGeneration
        val negotiation = Negotiation(gen, kind, commEndpoint, onReady)
        pendingNegotiation = negotiation

        negotiation.timeoutTask = Runnable {
            if (pendingNegotiation === negotiation && !negotiation.settled) {
                Log.w(TAG, "Bluetooth $kind negotiation timed out after ${timeoutMs}ms; falling back")
                failNegotiationAndFallback(RouteDisconnectReason.ROUTE_UNAVAILABLE)
            }
        }.also { main.postDelayed(it, timeoutMs) }

        communicationOwned = true
        activeCommunicationKind = kind
        gateway.setAudioMode(AudioManager.MODE_IN_COMMUNICATION)

        if (gateway.sdkInt >= Build.VERSION_CODES.S) {
            val target = commEndpoint
            if (target == null || !gateway.setCommunicationEndpoint(target)) {
                failNegotiationAndFallback(RouteDisconnectReason.ROUTE_UNAVAILABLE)
                return
            }
            // Check immediate snapshot too in case Android switched synchronously before listener fired.
            val current = gateway.getActiveCommunicationEndpoint()
            if (current != null && matchesCommunicationKind(current.type, kind)) {
                scheduleNegotiationSettle(negotiation)
            }
        } else {
            if (!gateway.isBluetoothScoAvailableOffCall()) {
                failNegotiationAndFallback(RouteDisconnectReason.ROUTE_UNAVAILABLE)
                return
            }
            gateway.registerLegacyScoReceiver(object : AudioSystemObserver {
                @Suppress("DEPRECATION")
                override fun onLegacyScoStateChanged(state: Int) {
                    if (pendingNegotiation !== negotiation) return
                    when (state) {
                        AudioManager.SCO_AUDIO_STATE_CONNECTED -> completeNegotiation(negotiation)
                        AudioManager.SCO_AUDIO_STATE_ERROR ->
                            failNegotiationAndFallback(RouteDisconnectReason.ROUTE_UNAVAILABLE)
                    }
                }
            })
            gateway.startLegacyBluetoothSco()
        }
    }

    private fun scheduleNegotiationSettle(neg: Negotiation) {
        if (neg.settled || neg.settleTask != null) return
        if (scoSettleMs <= 0L) {
            completeNegotiation(neg)
            return
        }
        neg.settleTask = Runnable {
            neg.settleTask = null
            if (pendingNegotiation === neg && !neg.settled) {
                completeNegotiation(neg)
            }
        }.also { main.postDelayed(it, scoSettleMs) }
    }

    private fun completeNegotiation(neg: Negotiation) {
        if (neg.settled || pendingNegotiation !== neg || neg.generation != routeGeneration) return
        if (gateway.sdkInt >= Build.VERSION_CODES.S &&
            gateway.getActiveCommunicationEndpoint()?.id != neg.targetEndpoint?.id
        ) return // Switched away during settle; keep waiting for the requested device or timeout.
        neg.settled = true
        neg.timeoutTask?.let(main::removeCallbacks)
        neg.settleTask?.let(main::removeCallbacks)
        pendingNegotiation = null
        gateway.unregisterLegacyScoReceiver()

        val preferredInput = findMatchingInputEndpoint(neg.targetKind)?.platformDevice
        val policy = InputRoutePolicy(
            audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            preferredDevice = preferredInput,
            expectedKind = neg.targetKind,
        )
        activeInputPolicy = policy
        neg.onReady(policy, false)
    }

    private fun failNegotiationAndFallback(@Suppress("UNUSED_PARAMETER") reason: RouteDisconnectReason) {
        val neg = pendingNegotiation ?: return
        if (neg.settled) return
        neg.settled = true
        neg.timeoutTask?.let(main::removeCallbacks)
        neg.settleTask?.let(main::removeCallbacks)
        gateway.unregisterLegacyScoReceiver()
        pendingNegotiation = null
        releaseSessionInternal(clearMode = true)
        val fallback = selectNonBluetoothInputPolicy().also { activeInputPolicy = it }
        neg.onReady(fallback, true)
    }

    private fun abortPendingNegotiation() {
        val neg = pendingNegotiation ?: return
        neg.settled = true
        neg.timeoutTask?.let(main::removeCallbacks)
        neg.settleTask?.let(main::removeCallbacks)
        pendingNegotiation = null
        gateway.unregisterLegacyScoReceiver()
    }

    private fun releaseSessionInternal(clearMode: Boolean) {
        val wasOwned = communicationOwned
        abortPendingNegotiation()
        ++routeGeneration
        communicationOwned = false
        activeCommunicationKind = null
        activeInputPolicy = selectNonBluetoothInputPolicy()
        if (!wasOwned) return

        expectingTeardown = true
        if (gateway.sdkInt >= Build.VERSION_CODES.S) {
            gateway.clearCommunicationEndpoint()
        } else {
            gateway.stopLegacyBluetoothSco()
        }
        val currentMode = gateway.audioMode
        val cellularCallActive = currentMode == AudioManager.MODE_IN_CALL ||
            currentMode == AudioManager.MODE_RINGTONE
        if (clearMode && !cellularCallActive) {
            gateway.setAudioMode(AudioManager.MODE_NORMAL)
        }
        cancelTeardownGrace()
        val graceGen = routeGeneration
        if (teardownGraceMs <= 0L) {
            expectingTeardown = false
        } else {
            teardownGraceClear = Runnable {
                teardownGraceClear = null
                if (graceGen == routeGeneration) expectingTeardown = false
            }.also { main.postDelayed(it, teardownGraceMs) }
        }
    }

    private fun cancelPendingOutput() {
        pendingOutputReady?.let(main::removeCallbacks)
        pendingOutputReady = null
    }

    private fun cancelTeardownGrace() {
        teardownGraceClear?.let(main::removeCallbacks)
        teardownGraceClear = null
    }

    private fun refreshButtonBridge() {
        ownedButtonBridge?.refreshPriority()
    }

    // ---- Device classification helpers ----

    private fun isExternalCallActive(): Boolean {
        val mode = gateway.audioMode
        return mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_RINGTONE ||
            (mode == AudioManager.MODE_IN_COMMUNICATION && !communicationOwned)
    }

    private fun findPreferredBluetoothCommEndpoint(
        inputs: List<AudioEndpoint>, outputs: List<AudioEndpoint>,
    ): Pair<InputRouteKind, AudioEndpoint?>? {
        if (gateway.sdkInt >= Build.VERSION_CODES.S) {
            val comm = gateway.getAvailableCommunicationEndpoints()
            comm.firstOrNull { it.type == TYPE_BLE_HEADSET_COMPAT }?.let {
                return InputRouteKind.BLE_AUDIO to it
            }
            comm.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }?.let {
                return InputRouteKind.BLUETOOTH_SCO to it
            }
            return null
        }
        // API 26–30: Classic SCO only, gated on off-call availability + visible SCO input or BT output.
        if (!gateway.isBluetoothScoAvailableOffCall()) return null
        // Some HFP inputs only materialize *after* startBluetoothSco. An output is a reason
        // to negotiate, never evidence of a working mic. Wait for CONNECTED or the timeout.
        val candidate = inputs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO } ||
            outputs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        return if (candidate) InputRouteKind.BLUETOOTH_SCO to null else null
    }

    private fun findMatchingInputEndpoint(kind: InputRouteKind): AudioEndpoint? {
        val inputs = gateway.getInputEndpoints()
        return when (kind) {
            InputRouteKind.BLE_AUDIO -> inputs.firstOrNull { it.type == TYPE_BLE_HEADSET_COMPAT }
            InputRouteKind.BLUETOOTH_SCO -> inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            InputRouteKind.WIRED_HEADSET -> inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
            InputRouteKind.USB_HEADSET -> inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            InputRouteKind.BUILT_IN_MIC -> inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        }
    }

    private fun selectNonBluetoothInputPolicy(): InputRoutePolicy {
        val inputs = gateway.getInputEndpoints()
        inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }?.let { wired ->
            return InputRoutePolicy(
                audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION,
                preferredDevice = wired.platformDevice,
                expectedKind = InputRouteKind.WIRED_HEADSET,
            )
        }
        inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }?.let { usb ->
            return InputRoutePolicy(
                audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION,
                preferredDevice = usb.platformDevice,
                expectedKind = InputRouteKind.USB_HEADSET,
            )
        }
        val builtIn = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        return InputRoutePolicy(
            audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION,
            preferredDevice = builtIn?.platformDevice,
            expectedKind = InputRouteKind.BUILT_IN_MIC,
        )
    }

    private fun selectCurrentOutputPolicy(): OutputRoutePolicy {
        val outputs = gateway.getOutputEndpoints()
        if (gateway.hasBluetoothConnectPermission()) {
            outputs.firstOrNull { it.type == TYPE_BLE_HEADSET_COMPAT }?.let { ble ->
                return OutputRoutePolicy(ble.platformDevice, privateOutput = true, expectedKind = OutputRouteKind.BLE_AUDIO)
            }
            outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }?.let { a2dp ->
                return OutputRoutePolicy(a2dp.platformDevice, privateOutput = true, expectedKind = OutputRouteKind.BLUETOOTH_A2DP)
            }
        }
        return selectFallbackOutputPolicy(outputs)
    }

    private fun selectFallbackOutputPolicy(outputs: List<AudioEndpoint> = gateway.getOutputEndpoints()): OutputRoutePolicy {
        outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        }?.let { wired ->
            return OutputRoutePolicy(wired.platformDevice, privateOutput = true, expectedKind = OutputRouteKind.WIRED_HEADSET)
        }
        outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }?.let { usb ->
            return OutputRoutePolicy(usb.platformDevice, privateOutput = true, expectedKind = OutputRouteKind.USB_HEADSET)
        }
        return OutputRoutePolicy(
            preferredDevice = null,
            privateOutput = false,
            expectedKind = OutputRouteKind.BUILT_IN_SPEAKER,
        )
    }

    private fun matchesCommunicationKind(type: Int, kind: InputRouteKind): Boolean = when (kind) {
        InputRouteKind.BLE_AUDIO -> type == TYPE_BLE_HEADSET_COMPAT
        InputRouteKind.BLUETOOTH_SCO -> type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        else -> false
    }

    companion object {
        private const val TAG = "BtAudioRoute"
        /** AudioDeviceInfo.TYPE_BLE_HEADSET (26, added in API 31). Safe constant on API 26+. */
        internal const val TYPE_BLE_HEADSET_COMPAT = 26
        /** AudioDeviceInfo.TYPE_BLE_SPEAKER (27, added in API 31). */
        private const val TYPE_BLE_SPEAKER_COMPAT = 27
        internal const val SCO_SETTLE_MS = 300L
        internal const val OUTPUT_SETTLE_MS = 250L
        internal const val TEARDOWN_GRACE_MS = 1_500L
        private const val MAX_ROUTE_TIMEOUT_MS = 5_000L

        internal fun isBluetoothOutputType(type: Int): Boolean =
            type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                type == TYPE_BLE_HEADSET_COMPAT ||
                type == TYPE_BLE_SPEAKER_COMPAT

        internal fun isBluetoothType(type: Int): Boolean =
            isBluetoothOutputType(type) || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO

        internal fun isWiredOrUsbType(type: Int): Boolean =
            type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                type == AudioDeviceInfo.TYPE_USB_DEVICE
    }
}
