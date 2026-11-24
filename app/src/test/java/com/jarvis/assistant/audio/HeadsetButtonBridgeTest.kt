package com.jarvis.assistant.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/**
 * Verifies Phase 4 opt-in headset-button behavior:
 * - Off by default (no MediaSession claimed)
 * - Yields to active music playback (no Play/Pause hijacking)
 * - Debounces headset button when idle & opted in
 * - Supports KEYCODE_VOICE_ASSIST directly
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class HeadsetButtonBridgeTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val mainLooper = shadowOf(Looper.getMainLooper())
    private val mainHandler = Handler(Looper.getMainLooper())

    // Scenario 7: Music playing — never hijacks Play/Pause or HeadsetHook
    @Test
    fun musicPlayingPreventsHeadsetButtonHijackAndYieldsSessionPriority() {
        var musicPlaying = true
        val triggers = mutableListOf<RouteTrigger>()
        val bridge = HeadsetButtonBridge(ctx, isMusicActive = { musicPlaying }, main = mainHandler) {
            triggers += it
        }
        try {
            // Off by default
            assertFalse(bridge.isSessionActive)
            assertFalse(bridge.handleKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))

            // Even when opted in, active music playback yields session and lets player keep Play/Pause
            bridge.setEnabled(true)
            assertFalse("session must yield while music is playing", bridge.isSessionActive)
            assertFalse(bridge.handleKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))
            assertFalse(bridge.handleKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK)))
            mainLooper.idleFor(Duration.ofMillis(400))
            assertTrue("no Ash trigger while music owns buttons", triggers.isEmpty())
        } finally {
            bridge.release()
        }
    }

    // Scenario 8: Music paused — opt-in headset button triggers once after debounce, and voice-assist fires immediately
    @Test
    fun musicPausedAllowsOptInHeadsetButtonTriggerAndVoiceAssist() {
        var musicPlaying = false
        val triggers = mutableListOf<RouteTrigger>()
        val bridge = HeadsetButtonBridge(ctx, isMusicActive = { musicPlaying }, main = mainHandler) {
            triggers += it
        }
        try {
            bridge.setEnabled(true)
            assertTrue(bridge.isSessionActive)

            // Rapid double press coalesces into a single trigger after 300ms debounce
            assertTrue(bridge.handleKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK)))
            mainLooper.idleFor(Duration.ofMillis(100))
            assertTrue(bridge.handleKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK)))
            mainLooper.idleFor(Duration.ofMillis(350))
            assertEquals(listOf(RouteTrigger.HEADSET_BUTTON), triggers)

            // Dedicated voice-assist button fires immediately
            assertTrue(bridge.handleKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOICE_ASSIST)))
            assertEquals(listOf(RouteTrigger.HEADSET_BUTTON, RouteTrigger.BLUETOOTH_VOICE_ACTIVATION), triggers)

            // Music resumes -> session yields cleanly
            musicPlaying = true
            bridge.refreshPriority()
            assertFalse(bridge.isSessionActive)

            // Turning opt-in off tears down session completely
            bridge.setEnabled(false)
            assertFalse(bridge.isSessionActive)
        } finally {
            bridge.release()
        }
    }
}
