package com.jarvis.assistant

import android.Manifest
import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.jarvis.assistant.accessibility.WindowContext
import com.jarvis.assistant.agent.PhoneAgent
import com.jarvis.assistant.agent.ToolCall
import com.jarvis.assistant.audio.AudioEndpoint
import com.jarvis.assistant.audio.AudioRouteListener
import com.jarvis.assistant.audio.AudioSystemGateway
import com.jarvis.assistant.audio.AudioSystemObserver
import com.jarvis.assistant.audio.BluetoothAudioRouteController
import com.jarvis.assistant.audio.BluetoothAudioRouteController.Companion.TYPE_BLE_HEADSET_COMPAT
import com.jarvis.assistant.audio.InputRouteKind
import com.jarvis.assistant.contacts.ContactEntry
import com.jarvis.assistant.contacts.ContactMatch
import com.jarvis.assistant.contacts.DirectCallPolicy
import com.jarvis.assistant.contacts.PhoneNumber
import com.jarvis.assistant.router.FastActionExecutor
import com.jarvis.assistant.router.FastCommand
import com.jarvis.assistant.router.MediaAction
import com.jarvis.assistant.router.QueryKind
import com.jarvis.assistant.router.VolumeChange
import com.jarvis.assistant.service.LockedCommands
import com.jarvis.assistant.service.PendingUnlock
import com.jarvis.assistant.service.TurnInputRoute
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class EndToEndPhase7Test {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val mainLooper = shadowOf(Looper.getMainLooper())
    private val mainHandler = Handler(Looper.getMainLooper())

    @Test
    fun directCallPolicyAllowsOnlyOptInUnambiguousOrdinaryNumbersAndEnforcesCooldown() {
        val rahul = ContactEntry(1L, "Rahul", listOf(PhoneNumber("+919876543210", "mobile")))
        val exact = ContactMatch(listOf(rahul), "exact")
        val fuzzy = ContactMatch(listOf(rahul), "fuzzy")
        val ambiguous = ContactMatch(listOf(rahul, ContactEntry(2L, "Rahul Sharma", emptyList())), "words")

        assertTrue(
            DirectCallPolicy.canPlaceDirectly(
                directEnabled = true,
                hasCallPermission = true,
                spokenRequest = "call rahul",
                match = exact,
                number = "+919876543210",
                nowMs = 20_000L,
                lastAttemptMs = Long.MIN_VALUE,
            ),
        )
        assertFalse("off by default", DirectCallPolicy.canPlaceDirectly(false, true, "call rahul", exact, "+919876543210", 20_000L, Long.MIN_VALUE))
        assertFalse("requires CALL_PHONE", DirectCallPolicy.canPlaceDirectly(true, false, "call rahul", exact, "+919876543210", 20_000L, Long.MIN_VALUE))
        assertFalse("requires live spoken command", DirectCallPolicy.canPlaceDirectly(true, true, null, exact, "+919876543210", 20_000L, Long.MIN_VALUE))
        assertFalse("fuzzy match must confirm", DirectCallPolicy.canPlaceDirectly(true, true, "call rahul", fuzzy, "+919876543210", 20_000L, Long.MIN_VALUE))
        assertFalse("ambiguous match must clarify", DirectCallPolicy.canPlaceDirectly(true, true, "call rahul", ambiguous, "+919876543210", 20_000L, Long.MIN_VALUE))
        assertFalse("emergency number must never auto-call", DirectCallPolicy.canPlaceDirectly(true, true, "call 112", exact, "112", 20_000L, Long.MIN_VALUE))
        assertFalse("duplicate cooldown blocks redial", DirectCallPolicy.canPlaceDirectly(true, true, "call rahul", exact, "+919876543210", 25_000L, 20_000L))
        assertTrue("allowed after cooldown expires", DirectCallPolicy.canPlaceDirectly(true, true, "call rahul", exact, "+919876543210", 31_000L, 20_000L))
        val prefix = ContactMatch(listOf(rahul), "prefix")
        assertTrue("unambiguous prefix match allowed in direct calling mode", DirectCallPolicy.canPlaceDirectly(true, true, "call rah", prefix, "+919876543210", 20_000L, Long.MIN_VALUE))
    }

    @Test
    fun directDialPlacesCallOnSingleVoiceCommandWhenOptedInAndBlocksEmergency() {
        val app = shadowOf(ApplicationProvider.getApplicationContext<Application>())
        app.grantPermissions(Manifest.permission.CALL_PHONE)
        val settings = Settings(ctx)
        settings.directContactCalls = true
        try {
            val executor = FastActionExecutor(ctx, settings) { null }
            var asked = false
            val direct = executor.execute(
                FastCommand.Dial("9876543210"),
                recordStep = {},
                asker = { asked = true; "yes" },
                spokenRequest = "call 9876543210",
                canAct = { true },
            )
            assertTrue(direct.message, direct.success)
            assertTrue("direct call must be marked irreversible", direct.irreversible)
            assertFalse("direct call must not prompt when opted in", asked)

            val emergency = executor.execute(
                FastCommand.Dial("112"),
                recordStep = {},
                asker = { asked = true; "no" },
                spokenRequest = "call 112",
                canAct = { true },
            )
            assertFalse("emergency number must never auto-call", emergency.success)
            assertTrue("emergency number must still require explicit confirmation", asked)
        } finally {
            settings.directContactCalls = false
        }
    }

    @Test
    fun windowContextAndPhoneAgentSupportGestureSwipeWithoutWeakeningGuards() {
        val base = WindowContext("com.example.app", 10, "0 0 1080 2400", "Home", epoch = 1L)
        assertTrue("epoch ticks alone must not invalidate foreground window", base.sameWindow(base.copy(epoch = 99L)))
        assertFalse("package change must invalidate foreground window", base.sameWindow(base.copy(packageName = "com.other.app")))
        assertFalse("windowId change must invalidate foreground window", base.sameWindow(base.copy(windowId = 11)))
        assertFalse("title change must invalidate foreground window", base.sameWindow(base.copy(title = "Sign in")))

        assertNotNull(
            "swipe without element_id allowed for gesture fallback",
            PhoneAgent.parse(ToolCall("1", "swipe", JSONObject().put("observation_id", 2).put("direction", "down")), 2),
        )
        assertNull(
            "raw coordinate tap_point remains rejected",
            PhoneAgent.parse(ToolCall("2", "tap_point", JSONObject().put("observation_id", 2).put("x", 100).put("y", 200)), 2),
        )
    }

    @Test
    fun lockedCommandsAndPendingUnlockEnforceSafeLockedPolicy() {
        assertTrue(LockedCommands.allows(FastCommand.Ask(QueryKind.TIME)))
        assertTrue(LockedCommands.allows(FastCommand.Volume(VolumeChange.UP)))
        assertTrue(LockedCommands.allows(FastCommand.Media(MediaAction.NEXT)))
        assertFalse(LockedCommands.allows(FastCommand.Media(MediaAction.PAUSE, app = "spotify")))
        assertFalse(LockedCommands.allows(FastCommand.CallContact("rahul")))
        assertFalse(LockedCommands.allows(FastCommand.SetTimer(300)))

        var now = 1_000L
        val pending = PendingUnlock { now }
        assertTrue(pending.offer(FastCommand.SetTimer(600)))
        assertNull("cannot take while still locked", pending.take(unlocked = false))

        assertTrue(pending.offer(FastCommand.SetTimer(600)))
        now = 10_000L
        assertEquals(FastCommand.SetTimer(600), pending.take(unlocked = true))
        assertNull("one-shot take clears pending command", pending.take(unlocked = true))

        assertTrue(pending.offer(FastCommand.Ask(QueryKind.BATTERY)))
        now = 50_000L
        assertNull("expired pending command must be dropped", pending.take(unlocked = true))
        assertFalse("sensitive call is never queued across unlock", pending.offer(FastCommand.CallContact("rahul")))
    }

    @Test
    fun openWebsiteRejectsUnsafeSchemesAndAllowsValidHttps() {
        val ok = FastCommand.OpenWebsite("example.com/docs").browserIntent()
        assertNotNull(ok)
        assertEquals("https://example.com/docs", ok?.dataString)
        assertNull(FastCommand.OpenWebsite("javascript:alert(1)").browserIntent())
        assertNull(FastCommand.OpenWebsite("file:///etc/passwd").browserIntent())
        assertNull(FastCommand.OpenWebsite("content://contacts").browserIntent())
        assertNull(FastCommand.OpenWebsite("not a url").browserIntent())
    }

    @Test
    fun auditFixesHigh01High02AndMed01VerifiedInAudioRouting() {
        // HIGH-02: API 29 headset exposing only A2DP output before startBluetoothSco still negotiates SCO
        val legacyGw = object : AudioSystemGateway {
            override val sdkInt = 29
            private var mode = AudioManager.MODE_NORMAL
            override val audioMode get() = mode
            override val isMusicActive = false
            override val isMicrophoneMute = false
            var scoObserver: AudioSystemObserver? = null
            var startedSco = 0
            override fun hasBluetoothConnectPermission() = true
            override fun getInputEndpoints() = listOf(AudioEndpoint(1, AudioDeviceInfo.TYPE_BUILTIN_MIC, "Phone"))
            override fun getOutputEndpoints() = listOf(AudioEndpoint(2, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Buds"))
            override fun getAvailableCommunicationEndpoints() = emptyList<AudioEndpoint>()
            override fun getActiveCommunicationEndpoint() = null
            override fun setAudioMode(mode: Int) { this.mode = mode }
            override fun setCommunicationEndpoint(endpoint: AudioEndpoint) = false
            override fun clearCommunicationEndpoint() {}
            override fun isBluetoothScoAvailableOffCall() = true
            override fun startLegacyBluetoothSco() { startedSco++ }
            override fun stopLegacyBluetoothSco() {}
            override fun registerObserver(observer: AudioSystemObserver, handler: Handler) {}
            override fun unregisterObserver() {}
            override fun registerLegacyScoReceiver(observer: AudioSystemObserver) { scoObserver = observer }
            override fun unregisterLegacyScoReceiver() { scoObserver = null }
        }
        val legacyCtrl = BluetoothAudioRouteController(ctx, legacyGw, mainHandler, enableButtonBridge = false)
        legacyCtrl.start(object : AudioRouteListener {})
        assertTrue("API 29 A2DP-connected headset should allow SCO negotiation", legacyCtrl.currentSnapshot().bluetoothMicAvailable)
        var legacyKind: InputRouteKind? = null
        legacyCtrl.prepareCommandInput(preferHeadset = true) { p, _ -> legacyKind = p.expectedKind }
        assertEquals(1, legacyGw.startedSco)
        @Suppress("DEPRECATION")
        legacyGw.scoObserver?.onLegacyScoStateChanged(AudioManager.SCO_AUDIO_STATE_CONNECTED)
        assertEquals(InputRouteKind.BLUETOOTH_SCO, legacyKind)
        legacyCtrl.stop()

        // MED-01 & HIGH-01: BLE Audio communication mode releases on prepareOutputForSpeech AND TurnInputRoute re-acquires for follow-up/ask
        val bleComm = AudioEndpoint(30, TYPE_BLE_HEADSET_COMPAT, "LE Buds")
        val bleGw = object : AudioSystemGateway {
            override val sdkInt = 35
            private var mode = AudioManager.MODE_NORMAL
            override val audioMode get() = mode
            override val isMusicActive = false
            override val isMicrophoneMute = false
            var active: AudioEndpoint? = null
            var obs: AudioSystemObserver? = null
            override fun hasBluetoothConnectPermission() = true
            override fun getInputEndpoints() = listOf(AudioEndpoint(1, AudioDeviceInfo.TYPE_BUILTIN_MIC), AudioEndpoint(31, TYPE_BLE_HEADSET_COMPAT))
            override fun getOutputEndpoints() = listOf(AudioEndpoint(2, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), AudioEndpoint(32, TYPE_BLE_HEADSET_COMPAT))
            override fun getAvailableCommunicationEndpoints() = listOf(bleComm)
            override fun getActiveCommunicationEndpoint() = active
            override fun setAudioMode(mode: Int) { this.mode = mode }
            override fun setCommunicationEndpoint(endpoint: AudioEndpoint): Boolean {
                active = endpoint
                obs?.onCommunicationDeviceChanged(endpoint)
                return true
            }
            override fun clearCommunicationEndpoint() { active = null }
            override fun isBluetoothScoAvailableOffCall() = true
            override fun startLegacyBluetoothSco() {}
            override fun stopLegacyBluetoothSco() {}
            override fun registerObserver(observer: AudioSystemObserver, handler: Handler) { obs = observer }
            override fun unregisterObserver() { obs = null }
            override fun registerLegacyScoReceiver(observer: AudioSystemObserver) {}
            override fun unregisterLegacyScoReceiver() {}
        }
        val bleCtrl = BluetoothAudioRouteController(ctx, bleGw, mainHandler, enableButtonBridge = false)
        bleCtrl.start(object : AudioRouteListener {})
        val turnRoute = TurnInputRoute(bleCtrl) { done -> done() }

        var r1: TurnInputRoute.Result? = null
        turnRoute.prepare(preferHeadset = true, requireHeadset = false, bluetoothEnabled = true, isCurrent = { true }) { r1 = it }
        mainLooper.idleFor(Duration.ofMillis(350))
        assertEquals(TurnInputRoute.Result.HEADSET, r1)
        assertTrue(bleCtrl.ownsCommunicationRoute)

        // MED-01: prepareOutputForSpeech releases BLE Audio communication route cleanly
        var outDelivered = false
        bleCtrl.prepareOutputForSpeech { outDelivered = true }
        assertTrue(outDelivered)
        assertFalse("BLE communication route must be released before speech", bleCtrl.ownsCommunicationRoute)
        assertEquals(AudioManager.MODE_NORMAL, bleGw.audioMode)

        // HIGH-01: TurnInputRoute re-negotiates headset mic cleanly after prompt playback
        var r2: TurnInputRoute.Result? = null
        turnRoute.prepare(preferHeadset = true, requireHeadset = false, bluetoothEnabled = true, isCurrent = { true }) { r2 = it }
        mainLooper.idleFor(Duration.ofMillis(350))
        assertEquals(TurnInputRoute.Result.HEADSET, r2)
        assertTrue(bleCtrl.ownsCommunicationRoute)
        bleCtrl.stop()
    }
}
