package com.jarvis.assistant.audio

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.jarvis.assistant.audio.BluetoothAudioRouteController.Companion.TYPE_BLE_HEADSET_COMPAT
import com.jarvis.assistant.service.JarvisService
import com.jarvis.assistant.voice.AudioOut
import com.jarvis.assistant.voice.VoiceInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/**
 * Deterministic host tests for Phase 4 Bluetooth, wired headset, BLE Audio, screen-off,
 * incoming call, permission denial, output privacy, and recovery scenarios.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class BluetoothAudioRouteTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val mainLooper = shadowOf(Looper.getMainLooper())
    private val mainHandler = Handler(Looper.getMainLooper())

    private class FakeGateway(
        override var sdkInt: Int = 35,
        var currentAudioMode: Int = AudioManager.MODE_NORMAL,
        override var isMusicActive: Boolean = false,
        override var isMicrophoneMute: Boolean = false,
        var btConnectGranted: Boolean = true,
        var scoAvailableOffCall: Boolean = true,
        var setCommSucceeds: Boolean = true,
        var autoConfirmCommSwitch: Boolean = true,
    ) : AudioSystemGateway {
        override val audioMode: Int get() = currentAudioMode
        val inputs = mutableListOf(AudioEndpoint(1, AudioDeviceInfo.TYPE_BUILTIN_MIC, "Phone Mic"))
        val outputs = mutableListOf(AudioEndpoint(2, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Speaker"))
        val availableComm = mutableListOf<AudioEndpoint>()
        var activeComm: AudioEndpoint? = null
        var observer: AudioSystemObserver? = null
        var legacyScoObserver: AudioSystemObserver? = null
        val setModeCalls = mutableListOf<Int>()
        var clearCommCalls = 0
        var startLegacyScoCalls = 0
        var stopLegacyScoCalls = 0

        override fun hasBluetoothConnectPermission(): Boolean = sdkInt < 31 || btConnectGranted
        override fun getInputEndpoints(): List<AudioEndpoint> = inputs.toList()
        override fun getOutputEndpoints(): List<AudioEndpoint> = outputs.toList()
        override fun getAvailableCommunicationEndpoints(): List<AudioEndpoint> = availableComm.toList()
        override fun getActiveCommunicationEndpoint(): AudioEndpoint? = activeComm

        override fun setAudioMode(mode: Int) {
            currentAudioMode = mode
            setModeCalls += mode
        }

        override fun setCommunicationEndpoint(endpoint: AudioEndpoint): Boolean {
            if (!setCommSucceeds) return false
            if (autoConfirmCommSwitch) {
                activeComm = endpoint
                observer?.onCommunicationDeviceChanged(endpoint)
            }
            return true
        }

        override fun clearCommunicationEndpoint() {
            clearCommCalls++
            activeComm = null
        }

        override fun isBluetoothScoAvailableOffCall(): Boolean = scoAvailableOffCall

        override fun startLegacyBluetoothSco() {
            startLegacyScoCalls++
        }

        override fun stopLegacyBluetoothSco() {
            stopLegacyScoCalls++
        }

        override fun registerObserver(observer: AudioSystemObserver, handler: Handler) {
            this.observer = observer
        }

        override fun unregisterObserver() {
            observer = null
            legacyScoObserver = null
        }

        override fun registerLegacyScoReceiver(observer: AudioSystemObserver) {
            legacyScoObserver = observer
        }

        override fun unregisterLegacyScoReceiver() {
            legacyScoObserver = null
        }
    }

    private fun controller(gateway: FakeGateway): BluetoothAudioRouteController =
        BluetoothAudioRouteController(
            context = ctx,
            gateway = gateway,
            main = mainHandler,
            enableButtonBridge = false,
            scoSettleMs = 300L,
            teardownSettleMs = 250L,
            teardownGraceMs = 1_500L,
        )

    // Scenario 1: No headset
    @Test
    fun noHeadsetUsesBuiltInPhoneMicAndSpeaker() {
        val gw = FakeGateway()
        val ctrl = controller(gw)
        ctrl.start(object : AudioRouteListener {})

        val standby = ctrl.prepareInput(AudioRoutePurpose.WAKE_STANDBY)
        assertEquals(InputRouteKind.BUILT_IN_MIC, standby.expectedKind)
        assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, standby.audioSource)

        var cmdPolicy: InputRoutePolicy? = null
        var fallback = true
        ctrl.prepareCommandInput(preferHeadset = true) { p, fb -> cmdPolicy = p; fallback = fb }
        assertEquals(InputRouteKind.BUILT_IN_MIC, cmdPolicy?.expectedKind)
        assertFalse(fallback)
        assertFalse(ctrl.ownsCommunicationRoute)

        var outPolicy: OutputRoutePolicy? = null
        ctrl.prepareOutputForSpeech { outPolicy = it }
        assertEquals(OutputRouteKind.BUILT_IN_SPEAKER, outPolicy?.expectedKind)
        assertFalse(outPolicy?.privateOutput == true)
        ctrl.stop()
    }

    // Scenario 2: Wired headset connect, command, playback, and unplug recovery
    @Test
    fun wiredHeadsetDetectedPreferredAndRecoversOnUnplug() {
        val gw = FakeGateway()
        val wiredIn = AudioEndpoint(10, AudioDeviceInfo.TYPE_WIRED_HEADSET, "Wired Mic")
        val wiredOut = AudioEndpoint(11, AudioDeviceInfo.TYPE_WIRED_HEADSET, "Wired Out")
        gw.inputs += wiredIn
        gw.outputs += wiredOut

        var inputLost: RouteDisconnectReason? = null
        var outputLost: RouteDisconnectReason? = null
        val ctrl = controller(gw)
        ctrl.start(object : AudioRouteListener {
            override fun onActiveInputLost(reason: RouteDisconnectReason) { inputLost = reason }
            override fun onPrivateOutputLost(reason: RouteDisconnectReason) { outputLost = reason }
        })

        assertEquals(InputRouteKind.WIRED_HEADSET, ctrl.prepareInput(AudioRoutePurpose.WAKE_STANDBY).expectedKind)
        assertTrue(ctrl.prepareOutput().privateOutput)
        assertEquals(OutputRouteKind.WIRED_HEADSET, ctrl.prepareOutput().expectedKind)

        // Simulate physical unplug
        gw.inputs.remove(wiredIn)
        gw.outputs.remove(wiredOut)
        gw.observer?.onDevicesRemoved(listOf(wiredIn, wiredOut))
        assertEquals(RouteDisconnectReason.WIRED_UNPLUGGED, inputLost)
        assertEquals(RouteDisconnectReason.PRIVATE_OUTPUT_LOST, outputLost)
        assertEquals(InputRouteKind.BUILT_IN_MIC, ctrl.prepareInput(AudioRoutePurpose.WAKE_STANDBY).expectedKind)
        ctrl.stop()
    }

    // Scenario 3a: Bluetooth Classic headset (API 31+ setCommunicationDevice -> SCO -> release -> hi-fi A2DP)
    @Test
    fun bluetoothClassicApi31NegotiatesScoAndReleasesForHiFiA2dp() {
        val gw = FakeGateway(sdkInt = 35)
        val scoIn = AudioEndpoint(20, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Earbuds HFP")
        val scoComm = AudioEndpoint(21, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Earbuds HFP")
        val a2dpOut = AudioEndpoint(22, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Earbuds A2DP")
        gw.inputs += scoIn
        gw.outputs += a2dpOut
        gw.availableComm += scoComm

        val ctrl = controller(gw)
        ctrl.start(object : AudioRouteListener {})

        // Standby MUST stay on phone mic (no idle SCO drain or A2DP degradation)
        assertEquals(InputRouteKind.BUILT_IN_MIC, ctrl.prepareInput(AudioRoutePurpose.WAKE_STANDBY).expectedKind)
        assertFalse(ctrl.ownsCommunicationRoute)
        assertTrue(ctrl.currentSnapshot().bluetoothConnected)
        assertTrue(ctrl.currentSnapshot().bluetoothMicAvailable)

        // Active headset-triggered command negotiates SCO
        var policy: InputRoutePolicy? = null
        var fallback = true
        ctrl.prepareCommandInput(preferHeadset = true) { p, fb -> policy = p; fallback = fb }
        assertTrue(ctrl.ownsCommunicationRoute)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, gw.audioMode)

        // Settles after SCO_SETTLE_MS (300ms)
        mainLooper.idleFor(Duration.ofMillis(350))
        assertEquals(InputRouteKind.BLUETOOTH_SCO, policy?.expectedKind)
        assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, policy?.audioSource)
        assertFalse(fallback)

        // Reply speech MUST release SCO first and settle into hi-fi A2DP
        var outPolicy: OutputRoutePolicy? = null
        ctrl.prepareOutputForSpeech { outPolicy = it }
        assertFalse(ctrl.ownsCommunicationRoute)
        assertEquals(AudioManager.MODE_NORMAL, gw.audioMode)
        assertEquals(1, gw.clearCommCalls)
        mainLooper.idleFor(Duration.ofMillis(300))
        assertEquals(OutputRouteKind.BLUETOOTH_A2DP, outPolicy?.expectedKind)
        assertTrue(outPolicy?.privateOutput == true)
        ctrl.stop()
    }

    // Scenario 3b: Bluetooth Classic legacy API 26–30 path
    @Test
    fun bluetoothClassicLegacyApi29UsesStartBluetoothScoAndBroadcast() {
        val gw = FakeGateway(sdkInt = 29)
        gw.inputs += AudioEndpoint(20, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Legacy BT")
        gw.outputs += AudioEndpoint(22, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Legacy A2DP")

        val ctrl = controller(gw)
        ctrl.start(object : AudioRouteListener {})

        var policy: InputRoutePolicy? = null
        var fallback = true
        ctrl.prepareCommandInput(preferHeadset = true) { p, fb -> policy = p; fallback = fb }
        assertEquals(1, gw.startLegacyScoCalls)
        assertNotNull(gw.legacyScoObserver)

        @Suppress("DEPRECATION")
        gw.legacyScoObserver?.onLegacyScoStateChanged(AudioManager.SCO_AUDIO_STATE_CONNECTED)
        assertEquals(InputRouteKind.BLUETOOTH_SCO, policy?.expectedKind)
        assertFalse(fallback)

        ctrl.releaseSession()
        assertEquals(1, gw.stopLegacyScoCalls)
        assertEquals(AudioManager.MODE_NORMAL, gw.audioMode)
        ctrl.stop()
    }

    // Scenario 3c: SCO negotiation timeout tears down cleanly and falls back to phone mic
    @Test
    fun bluetoothScoTimeoutTearsDownAndFallsBackToPhoneMic() {
        val gw = FakeGateway(sdkInt = 35, autoConfirmCommSwitch = false)
        gw.inputs += AudioEndpoint(20, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Slow Earbuds")
        gw.outputs += AudioEndpoint(22, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Slow A2DP")
        gw.availableComm += AudioEndpoint(21, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Slow Earbuds")

        val ctrl = controller(gw)
        ctrl.start(object : AudioRouteListener {})

        var policy: InputRoutePolicy? = null
        var fallback = false
        ctrl.prepareCommandInput(preferHeadset = true, timeoutMs = 1_000L) { p, fb -> policy = p; fallback = fb }
        mainLooper.idleFor(Duration.ofMillis(1_100))

        assertTrue("must report fallback on timeout", fallback)
        assertEquals(InputRouteKind.BUILT_IN_MIC, policy?.expectedKind)
        assertFalse(ctrl.ownsCommunicationRoute)
        assertEquals(AudioManager.MODE_NORMAL, gw.audioMode)
        assertEquals(1, gw.clearCommCalls)
        ctrl.stop()
    }

    // Scenario 3d: A2DP-only headphones (no HFP mic) fall back to phone mic while keeping A2DP playback
    @Test
    fun a2dpOnlyHeadsetFallsBackToPhoneMicForInputAndPlaysOverA2dp() {
        val gw = FakeGateway(sdkInt = 35)
        gw.outputs += AudioEndpoint(22, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "A2DP Headphones")

        val ctrl = controller(gw)
        ctrl.start(object : AudioRouteListener {})

        var policy: InputRoutePolicy? = null
        var fallback = false
        ctrl.prepareCommandInput(preferHeadset = true) { p, fb -> policy = p; fallback = fb }
        assertTrue(fallback)
        assertEquals(InputRouteKind.BUILT_IN_MIC, policy?.expectedKind)
        assertEquals(OutputRouteKind.BLUETOOTH_A2DP, ctrl.prepareOutput().expectedKind)
        ctrl.stop()
    }

    // Scenario 4: BLE Audio preferred over Classic SCO when both are exposed on API 31+
    @Test
    fun bleAudioPreferredOverClassicScoOnApi31() {
        val gw = FakeGateway(sdkInt = 35)
        val bleComm = AudioEndpoint(30, TYPE_BLE_HEADSET_COMPAT, "LE Audio Buds")
        val scoComm = AudioEndpoint(31, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Classic HFP")
        gw.inputs += AudioEndpoint(32, TYPE_BLE_HEADSET_COMPAT, "LE Mic")
        gw.outputs += AudioEndpoint(33, TYPE_BLE_HEADSET_COMPAT, "LE Output")
        gw.availableComm += listOf(scoComm, bleComm)

        val ctrl = controller(gw)
        ctrl.start(object : AudioRouteListener {})

        var policy: InputRoutePolicy? = null
        var fallback = true
        ctrl.prepareCommandInput(preferHeadset = true) { p, fb -> policy = p; fallback = fb }
        mainLooper.idleFor(Duration.ofMillis(350))

        assertEquals(InputRouteKind.BLE_AUDIO, policy?.expectedKind)
        assertFalse(fallback)
        assertEquals(bleComm, gw.activeComm)
        assertEquals(OutputRouteKind.BLE_AUDIO, ctrl.prepareOutput().expectedKind)
        assertTrue(ctrl.prepareOutput().privateOutput)
        ctrl.stop()
    }

    // Scenario 5 & 6: Screen-on headset turn vs Screen-off phone-mic standby wake hit (zero mid-sentence route switch)
    @Test
    fun phoneMicWakeHitKeepsPhoneRouteWithoutDroppingPreRollEvenWithEarbudsConnected() {
        val gw = FakeGateway(sdkInt = 35)
        gw.inputs += AudioEndpoint(20, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Earbuds")
        gw.outputs += AudioEndpoint(22, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Earbuds A2DP")
        gw.availableComm += AudioEndpoint(21, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Earbuds")

        val ctrl = controller(gw)
        ctrl.start(object : AudioRouteListener {})

        // Phone-mic wake word detected (preferHeadset = false): MUST NOT negotiate SCO mid-sentence
        var policy: InputRoutePolicy? = null
        var fallback = true
        ctrl.prepareCommandInput(preferHeadset = false) { p, fb -> policy = p; fallback = fb }
        assertEquals(InputRouteKind.BUILT_IN_MIC, policy?.expectedKind)
        assertFalse(fallback)
        assertFalse(ctrl.ownsCommunicationRoute)
        assertEquals(AudioManager.MODE_NORMAL, gw.audioMode)
        ctrl.stop()
    }

    // Scenario 9: Bluetooth reconnection during standby and after disconnect
    @Test
    fun bluetoothReconnectDuringStandbyAndAfterDisconnectRecoversCleanly() {
        val gw = FakeGateway(sdkInt = 35)
        val reasons = mutableListOf<String>()
        val ctrl = controller(gw)
        ctrl.start(object : AudioRouteListener {
            override fun onRouteChanged(reason: String) { reasons += reason }
        })
        assertFalse(ctrl.currentSnapshot().bluetoothConnected)

        // Earbuds connect while in standby
        val scoIn = AudioEndpoint(20, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Buds")
        val scoComm = AudioEndpoint(21, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Buds")
        val a2dp = AudioEndpoint(22, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Buds")
        gw.inputs += scoIn
        gw.outputs += a2dp
        gw.availableComm += scoComm
        gw.observer?.onDevicesAdded(listOf(scoIn, a2dp))
        assertTrue(ctrl.currentSnapshot().bluetoothConnected)
        assertTrue("device_added" in reasons)

        // Disconnect, then reconnect again
        gw.inputs.remove(scoIn)
        gw.outputs.remove(a2dp)
        gw.availableComm.remove(scoComm)
        gw.observer?.onDevicesRemoved(listOf(a2dp))
        assertFalse(ctrl.currentSnapshot().bluetoothConnected)

        gw.inputs += scoIn
        gw.outputs += a2dp
        gw.availableComm += scoComm
        gw.observer?.onDevicesAdded(listOf(scoIn, a2dp))
        assertTrue(ctrl.currentSnapshot().bluetoothConnected)

        var policy: InputRoutePolicy? = null
        ctrl.prepareCommandInput(preferHeadset = true) { p, _ -> policy = p }
        mainLooper.idleFor(Duration.ofMillis(350))
        assertEquals(InputRouteKind.BLUETOOTH_SCO, policy?.expectedKind)
        ctrl.stop()
    }

    // Scenario 10: Incoming call prevents negotiation and never fights MODE_IN_CALL / MODE_RINGTONE
    @Test
    fun incomingCallBlocksNegotiationAndNeverResetsCallAudioMode() {
        val gw = FakeGateway(sdkInt = 35, currentAudioMode = AudioManager.MODE_RINGTONE)
        gw.inputs += AudioEndpoint(20, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Buds")
        gw.outputs += AudioEndpoint(22, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Buds")
        gw.availableComm += AudioEndpoint(21, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Buds")

        val ctrl = controller(gw)
        ctrl.start(object : AudioRouteListener {})

        var fallback = false
        ctrl.prepareCommandInput(preferHeadset = true) { _, fb -> fallback = fb }
        assertTrue(fallback)
        assertFalse(ctrl.ownsCommunicationRoute)
        assertEquals(AudioManager.MODE_RINGTONE, gw.audioMode)
        assertTrue("must never change mode during incoming call", gw.setModeCalls.isEmpty())
        ctrl.stop()
    }

    // Scenario 11: BLUETOOTH_CONNECT denied falls back gracefully; RECORD_AUDIO denied refuses service start
    @Test
    fun permissionDenialsDegradeSafelyWithoutCrashing() {
        val gw = FakeGateway(sdkInt = 35, btConnectGranted = false)
        gw.inputs += AudioEndpoint(20, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Buds")
        gw.outputs += AudioEndpoint(22, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Buds")
        gw.availableComm += AudioEndpoint(21, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Buds")

        val ctrl = controller(gw)
        ctrl.start(object : AudioRouteListener {})
        assertFalse(ctrl.currentSnapshot().bluetoothMicAvailable)

        var policy: InputRoutePolicy? = null
        var fallback = false
        ctrl.prepareCommandInput(preferHeadset = true) { p, fb -> policy = p; fallback = fb }
        assertTrue(fallback)
        assertEquals(InputRouteKind.BUILT_IN_MIC, policy?.expectedKind)
        assertFalse(ctrl.ownsCommunicationRoute)

        // Verify RECORD_AUDIO denied blocks JarvisService.start cleanly
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertFalse(JarvisService.start(ctx, JarvisService.ACTION_LISTEN))
        ctrl.stop()
    }

    // Scenario 12: Private headset output removed mid-speech stops reply immediately instead of spilling to speaker
    @Test
    fun privateOutputRemovedMidSpeechSignalsStopInsteadOfSpeakerSpill() {
        val gw = FakeGateway(sdkInt = 35)
        val a2dp = AudioEndpoint(22, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Buds A2DP")
        gw.outputs += a2dp

        var privateLost: RouteDisconnectReason? = null
        val ctrl = controller(gw)
        ctrl.start(object : AudioRouteListener {
            override fun onPrivateOutputLost(reason: RouteDisconnectReason) { privateLost = reason }
        })

        var outPolicy: OutputRoutePolicy? = null
        ctrl.prepareOutputForSpeech { outPolicy = it }
        assertTrue(outPolicy?.privateOutput == true)

        // Earbuds disconnect during TTS playback
        gw.outputs.remove(a2dp)
        gw.observer?.onDevicesRemoved(listOf(a2dp))
        assertEquals(RouteDisconnectReason.PRIVATE_OUTPUT_LOST, privateLost)
        assertEquals(OutputRouteKind.BUILT_IN_SPEAKER, ctrl.prepareOutput().expectedKind)
        ctrl.stop()
    }

    // Scenario 13: Repeated rapid activations invalidate stale timers & callbacks
    @Test
    fun rapidRepeatedNegotiationsInvalidateStaleTimersAndCallbacks() {
        val gw = FakeGateway(sdkInt = 35, autoConfirmCommSwitch = false)
        val scoComm = AudioEndpoint(21, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Buds")
        gw.inputs += AudioEndpoint(20, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Buds")
        gw.outputs += AudioEndpoint(22, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Buds")
        gw.availableComm += scoComm

        val ctrl = controller(gw)
        ctrl.start(object : AudioRouteListener {})

        var firstCalls = 0
        var secondCalls = 0
        ctrl.prepareCommandInput(preferHeadset = true, timeoutMs = 500L) { _, _ -> firstCalls++ }
        // Immediately cancel/start a second turn before first resolves
        ctrl.releaseSession()
        gw.autoConfirmCommSwitch = true
        ctrl.prepareCommandInput(preferHeadset = true, timeoutMs = 2_000L) { _, _ -> secondCalls++ }
        mainLooper.idleFor(Duration.ofMillis(1_000))

        assertEquals("stale first turn callback must not fire", 0, firstCalls)
        assertEquals("only current turn callback should fire once", 1, secondCalls)
        ctrl.stop()
    }

    // Scenario 14: SCO-only drop vs full BT disconnect, teardown grace suppression, and BT adapter OFF
    @Test
    fun scoDropVsFullDisconnectAndBluetoothAdapterOffRecovery() {
        val gw = FakeGateway(sdkInt = 35)
        val scoIn = AudioEndpoint(20, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Buds HFP")
        val scoComm = AudioEndpoint(21, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Buds HFP")
        val a2dp = AudioEndpoint(22, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Buds A2DP")
        gw.inputs += scoIn
        gw.outputs += a2dp
        gw.availableComm += scoComm

        val lostReasons = mutableListOf<RouteDisconnectReason>()
        val ctrl = controller(gw)
        ctrl.start(object : AudioRouteListener {
            override fun onActiveInputLost(reason: RouteDisconnectReason) { lostReasons += reason }
        })

        // 1. Intentional teardown suppresses SCO-only removal glitch
        ctrl.prepareCommandInput(preferHeadset = true) { _, _ -> }
        mainLooper.idleFor(Duration.ofMillis(350))
        ctrl.releaseSession()
        gw.observer?.onDevicesRemoved(listOf(scoIn))
        assertTrue("intentional teardown SCO drop must be suppressed", lostReasons.isEmpty())

        // 2. Unexpected SCO drop while A2DP output stays connected reports SCO_DROPPED
        mainLooper.idleFor(Duration.ofMillis(1_600)) // grace period elapsed
        ctrl.prepareCommandInput(preferHeadset = true) { _, _ -> }
        mainLooper.idleFor(Duration.ofMillis(350))
        gw.observer?.onDevicesRemoved(listOf(scoIn))
        assertEquals(listOf(RouteDisconnectReason.SCO_DROPPED), lostReasons)
        assertFalse(ctrl.ownsCommunicationRoute)
        assertEquals(AudioManager.MODE_NORMAL, gw.audioMode)

        // 3. Bluetooth adapter disabled mid-turn reports BLUETOOTH_DISABLED and cleans up
        lostReasons.clear()
        ctrl.prepareCommandInput(preferHeadset = true) { _, _ -> }
        mainLooper.idleFor(Duration.ofMillis(350))
        gw.observer?.onBluetoothAdapterDisabled()
        assertEquals(listOf(RouteDisconnectReason.BLUETOOTH_DISABLED), lostReasons)
        assertFalse(ctrl.ownsCommunicationRoute)
        assertEquals(AudioManager.MODE_NORMAL, gw.audioMode)
        ctrl.stop()
    }

    @Test
    fun manifestDeclaresMinimalBluetoothPermissionsWithoutScanOrLocation() {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
        val perms = info.requestedPermissions?.toSet().orEmpty()
        assertTrue(Manifest.permission.MODIFY_AUDIO_SETTINGS in perms)
        assertTrue(Manifest.permission.BLUETOOTH_CONNECT in perms)
        assertFalse("must not request BLUETOOTH_SCAN", Manifest.permission.BLUETOOTH_SCAN in perms)
        assertFalse("must not request fine location", Manifest.permission.ACCESS_FINE_LOCATION in perms)
        assertFalse("must not request coarse location", Manifest.permission.ACCESS_COARSE_LOCATION in perms)
    }

    @Test
    fun voiceInputAllowsAshOwnedCommunicationModeAndBlocksExternalCalls() {
        val gw = FakeGateway(sdkInt = 35)
        gw.inputs += AudioEndpoint(20, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Buds HFP")
        gw.outputs += AudioEndpoint(22, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Buds A2DP")
        gw.availableComm += AudioEndpoint(21, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Buds HFP")

        val ctrl = controller(gw)
        ctrl.start(object : AudioRouteListener {})
        val voice = VoiceInput(ctx, ctrl) {}
        val audio = ctx.getSystemService(AudioManager::class.java)

        try {
            // External VoIP (MODE_IN_COMMUNICATION while Ash does NOT own route) must block capture
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            assertTrue("external VoIP must block mic", voice.micBlocked(audio))

            // Ash-owned communication route must NOT self-block VoiceInput
            ctrl.prepareCommandInput(preferHeadset = true) { _, _ -> }
            assertTrue(ctrl.ownsCommunicationRoute)
            assertFalse("Ash-owned SCO/BLE route must be permitted", voice.micBlocked(audio))

            // Cellular ringtone / active call ALWAYS blocks capture even if Ash owned a route
            audio.mode = AudioManager.MODE_RINGTONE
            assertTrue("incoming ringtone must always block mic", voice.micBlocked(audio))
            audio.mode = AudioManager.MODE_IN_CALL
            assertTrue("cellular call must always block mic", voice.micBlocked(audio))
        } finally {
            audio.mode = AudioManager.MODE_NORMAL
            voice.release()
            ctrl.stop()
        }
    }

    @Test
    fun jarvisServiceReleasesBluetoothCommunicationRouteOnDestroy() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        val gw = FakeGateway(sdkInt = 35)
        gw.inputs += AudioEndpoint(20, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Buds HFP")
        gw.outputs += AudioEndpoint(22, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Buds A2DP")
        gw.availableComm += AudioEndpoint(21, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Buds HFP")

        val ctrl = controller(gw)
        val previousFactory = JarvisService.audioRouteControllerFactory
        JarvisService.audioRouteControllerFactory = { ctrl }
        val serviceCtrl = Robolectric.buildService(JarvisService::class.java)
        try {
            serviceCtrl.create()
            assertEquals(ctrl, AudioOut.routeController)

            ctrl.prepareCommandInput(preferHeadset = true) { _, _ -> }
            mainLooper.idleFor(Duration.ofMillis(350))
            assertTrue(ctrl.ownsCommunicationRoute)
            assertEquals(AudioManager.MODE_IN_COMMUNICATION, gw.audioMode)

            serviceCtrl.destroy()
            assertFalse("service destroy must release Bluetooth communication route", ctrl.ownsCommunicationRoute)
            assertEquals(AudioManager.MODE_NORMAL, gw.audioMode)
            assertEquals(BuiltInPhoneAudioRouteController, AudioOut.routeController)
        } finally {
            JarvisService.audioRouteControllerFactory = previousFactory
        }
    }
}
