package com.jarvis.assistant

import android.Manifest
import android.app.Application
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.MediaRecorder
import android.service.notification.NotificationListenerService
import android.service.voice.VoiceInteractionService
import android.speech.RecognitionService
import androidx.test.core.app.ApplicationProvider
import com.jarvis.assistant.agent.OneUseApproval
import com.jarvis.assistant.agent.OpenAiCompatibleSession
import com.jarvis.assistant.agent.PhoneAgent
import com.jarvis.assistant.agent.ProviderFailure
import com.jarvis.assistant.agent.SensitiveActions
import com.jarvis.assistant.agent.ToolCall
import com.jarvis.assistant.ai.Endpoints
import com.jarvis.assistant.ai.InvalidProviderEndpoint
import com.jarvis.assistant.assistant.AshRecognitionService
import com.jarvis.assistant.assistant.AshVoiceInteractionService
import com.jarvis.assistant.audio.AudioRouteController
import com.jarvis.assistant.audio.AudioRoutePurpose
import com.jarvis.assistant.audio.BuiltInPhoneAudioRouteController
import com.jarvis.assistant.audio.InputRoutePolicy
import com.jarvis.assistant.media.MediaNotificationListener
import com.jarvis.assistant.router.FastActionExecutor
import com.jarvis.assistant.router.FastCommand
import com.jarvis.assistant.router.MediaAction
import com.jarvis.assistant.router.QueryKind
import com.jarvis.assistant.service.JarvisService
import com.jarvis.assistant.ui.MainActivity
import com.jarvis.assistant.voice.AudioOut
import com.jarvis.assistant.voice.WakeModel
import com.jarvis.assistant.voice.WakePhrase
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AshPhase3Test {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        JarvisService.audioRouteControllerFactory = { BuiltInPhoneAudioRouteController }
        AudioOut.routeController = BuiltInPhoneAudioRouteController
    }

    @Test
    fun dualWakePhrasesAndHitMapping() {
        assertTrue(WakeModel.KEYWORDS.contains("@HEY_ASH"))
        assertTrue(WakeModel.KEYWORDS.contains("@HELLO_ASH"))
        assertEquals("Hey Ash", WakePhrase.detectedPhrase("HEY_ASH", ""))
        assertEquals("Hello Ash", WakePhrase.detectedPhrase("HELLO_ASH", ""))
        assertEquals("Hey Computer", WakePhrase.detectedPhrase("CUSTOM", "Hey Computer"))
        assertEquals("bare Ash must be rejected as custom phrase", "", WakePhrase.optional("Ash"))
        assertEquals("built-in Hey Ash is not duplicated as custom", "", WakePhrase.optional("Hey Ash"))
        assertEquals("built-in Hello Ash is not duplicated as custom", "", WakePhrase.optional("Hello Ash"))
        assertEquals("Hey Computer", WakePhrase.optional("  Hey   Computer  "))
        assertFalse(WakePhrase.isValid("Ash 123"))
        assertTrue(WakePhrase.isValid("Hello Computer"))
        // Verify WakePhrase.keyword() segments both built-in phrases cleanly using assets/wake-pieces.txt.
        assertTrue(WakePhrase.keyword(ctx, "Hey Ash").contains("▁HE Y ▁A SH"))
        assertTrue(WakePhrase.keyword(ctx, "Hello Ash").contains("▁HE LL O ▁A SH"))
    }

    @Test
    fun endpointsEnforceHttpsForRemoteAndAllowLocal() {
        assertEquals("https://api.openai.com/v1", Endpoints.requireHttpUrl("https://api.openai.com/v1").toString())
        assertEquals("http://127.0.0.1:8080/v1", Endpoints.requireHttpUrl("http://127.0.0.1:8080/v1").toString())
        assertEquals("http://192.168.1.50:1234/v1", Endpoints.requireHttpUrl("http://192.168.1.50:1234/v1").toString())
        assertEquals("http://box.local:11434/v1", Endpoints.requireHttpUrl("http://box.local:11434/v1").toString())

        for (bad in listOf(
            "http://api.openai.com/v1",
            "http://example.com/v1",
            "https://user:pass@api.openai.com/v1",
            "https://api.openai.com/v1?x=1",
            "https://api.openai.com/v1#frag",
            "",
        )) {
            val threw = runCatching { Endpoints.requireHttpUrl(bad) }.exceptionOrNull()
            assertTrue("expected InvalidProviderEndpoint for $bad, got $threw", threw is InvalidProviderEndpoint)
        }
    }

    @Test
    fun oneUseApprovalEnforcesBindingExpiryAndSingleUse() {
        val approval = OneUseApproval("action-A", askedAt = 1_000L)
        assertFalse(approval.consume("action-B", "yes", now = 2_000L, cancelled = false))
        assertFalse(approval.consume("action-A", "no", now = 2_000L, cancelled = false))
        assertFalse(approval.consume("action-A", "yes", now = 2_000L, cancelled = true))
        assertFalse(approval.consume("action-A", "yes", now = 12_000L, cancelled = false))

        val fresh = OneUseApproval("action-A", askedAt = 1_000L)
        assertTrue(fresh.consume("action-A", "yes", now = 5_000L, cancelled = false))
        assertFalse("approval cannot be consumed twice", fresh.consume("action-A", "yes", now = 5_500L, cancelled = false))
    }

    @Test
    fun sensitiveActionsBlockProtectedPackagesAndLabels() {
        assertTrue(SensitiveActions.isProtectedPackage("com.android.permissioncontroller"))
        assertTrue(SensitiveActions.isProtectedPackage("com.google.android.packageinstaller"))
        assertTrue(SensitiveActions.isProtectedPackage("com.example.wallet"))
        assertFalse(SensitiveActions.isProtectedPackage("com.spotify.music"))
        assertTrue(SensitiveActions.isProtectedLabel("Enter OTP code"))
        assertTrue(SensitiveActions.isProtectedLabel("Confirm payment"))
        assertTrue(SensitiveActions.isSearchLabel("Search"))
        assertTrue(SensitiveActions.isEditableLabel("Message"))
        assertFalse(SensitiveActions.canQuote("Line 1\nLine 2", 50))
        assertTrue(SensitiveActions.canQuote("Play jazz music", 50))
    }

    @Test
    fun manifestRegistersAssistantRecognitionAndMediaListenerComponents() {
        val pm = ctx.packageManager
        val vis = pm.getServiceInfo(ComponentName(ctx, AshVoiceInteractionService::class.java), 0)
        assertEquals(android.Manifest.permission.BIND_VOICE_INTERACTION, vis.permission)

        val rec = pm.getServiceInfo(ComponentName(ctx, AshRecognitionService::class.java), 0)
        assertTrue(rec.exported)
        assertTrue(pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .any { it.serviceInfo.name == AshRecognitionService::class.java.name })

        val media = pm.getServiceInfo(ComponentName(ctx, MediaNotificationListener::class.java), 0)
        assertEquals(android.Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE, media.permission)
        assertTrue(pm.queryIntentServices(Intent(NotificationListenerService.SERVICE_INTERFACE), 0)
            .any { it.serviceInfo.name == MediaNotificationListener::class.java.name })
    }

    @Test
    fun audioRouteControllerSeamAcceptsCustomPhase4Implementation() {
        assertFalse(BuiltInPhoneAudioRouteController.acceptInputDevice(null, AudioRoutePurpose.WAKE_STANDBY))
        assertEquals(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            BuiltInPhoneAudioRouteController.prepareInput(AudioRoutePurpose.WAKE_STANDBY).audioSource,
        )

        val stub = object : AudioRouteController {
            override fun prepareInput(purpose: AudioRoutePurpose) =
                InputRoutePolicy(audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION)

            override fun acceptInputDevice(device: AudioDeviceInfo?, purpose: AudioRoutePurpose): Boolean = true
        }
        JarvisService.audioRouteControllerFactory = { stub }
        AudioOut.routeController = stub
        assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, stub.prepareInput(AudioRoutePurpose.COMMAND_CAPTURE).audioSource)
        assertTrue(stub.acceptInputDevice(null, AudioRoutePurpose.COMMAND_CAPTURE))
        assertNotNull(JarvisService.audioRouteControllerFactory(ctx))
    }

    @Test
    fun dialRefusesMalformedServiceCodesWithoutOpeningDialer() {
        val executor = FastActionExecutor(ctx, Settings(ctx)) { null }
        val result = executor.execute(FastCommand.Dial("call *#06#"))
        assertTrue(result.handled)
        assertFalse(result.success)
        assertTrue(result.message, result.message.contains("can't open the dialer"))
    }

    @Test
    fun dialRequiresSpokenConfirmationAndOpensSystemDialerOnlyOnYes() {
        val app = shadowOf(ApplicationProvider.getApplicationContext<Application>())
        app.clearNextStartedActivities()
        val executor = FastActionExecutor(ctx, Settings(ctx)) { null }

        val denied = executor.execute(FastCommand.Dial("9876543210")) { "no" }
        assertTrue(denied.handled)
        assertFalse(denied.success)
        assertNull("must not open dialer on no", app.nextStartedActivity)

        val unconfirmed = executor.execute(FastCommand.Dial("9876543210"), asker = null)
        assertTrue(unconfirmed.handled)
        assertFalse(unconfirmed.success)
        assertNull("must not open dialer without confirmation", app.nextStartedActivity)

        val confirmed = executor.execute(FastCommand.Dial("9876543210")) { "yes" }
        assertTrue(confirmed.handled)
        assertTrue(confirmed.message, confirmed.success)
        val launched = app.nextStartedActivity
        assertNotNull("should open dialer on yes", launched)
        assertEquals(Intent.ACTION_DIAL, launched.action)
        assertEquals("tel:9876543210", launched.dataString)
    }

    @Test
    fun lockScreenAllowsOnlyTimeAndUntargetedMediaTransport() {
        val keyguard = shadowOf(ctx.getSystemService(KeyguardManager::class.java))
        keyguard.setKeyguardLocked(true)
        try {
            val executor = FastActionExecutor(ctx, Settings(ctx)) { null }
            val time = executor.execute(FastCommand.Ask(QueryKind.TIME))
            assertTrue(time.message, time.success)

            val media = executor.execute(FastCommand.Media(MediaAction.PAUSE))
            assertTrue(media.message, media.success)

            val date = executor.execute(FastCommand.Ask(QueryKind.DATE))
            assertFalse(date.success)
            assertTrue(date.message, date.message.contains("Unlock"))

            val battery = executor.execute(FastCommand.Ask(QueryKind.BATTERY))
            assertFalse(battery.success)
            assertTrue(battery.message, battery.message.contains("Unlock"))

            val targetedMedia = executor.execute(FastCommand.Media(MediaAction.PAUSE, app = "spotify"))
            assertFalse(targetedMedia.success)
            assertTrue(targetedMedia.message, targetedMedia.message.contains("Unlock"))
        } finally {
            keyguard.setKeyguardLocked(false)
        }
    }

    @Test
    fun phoneAgentParserRejectsStaleOrUnsafeToolCalls() {
        assertNotNull(PhoneAgent.parse(ToolCall("1", "tap", JSONObject().put("observation_id", 2).put("element_id", 3)), 2))
        assertNotNull(PhoneAgent.parse(ToolCall("2", "finish", JSONObject()), 2))
        assertNull("stale observation must be rejected",
            PhoneAgent.parse(ToolCall("3", "tap", JSONObject().put("observation_id", 1).put("element_id", 3)), 2))
        assertNull("raw coordinate tap_point must be rejected",
            PhoneAgent.parse(ToolCall("4", "tap_point", JSONObject().put("observation_id", 2).put("x", 10).put("y", 20)), 2))
        assertNull("extra keys must be rejected",
            PhoneAgent.parse(ToolCall("5", "tap", JSONObject().put("observation_id", 2).put("element_id", 3).put("userConfirmed", true)), 2))
        assertNull("press_key enter must be rejected",
            PhoneAgent.parse(ToolCall("6", "press_key", JSONObject().put("observation_id", 2).put("key", "enter")), 2))
    }

    @Test
    fun openAiCompatibleSessionSanitizesErrorsAndSupportsCancellation() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"sk-super-secret-leak"}}"""))
            val preset = PROVIDERS.first { it.id == "custom" }
            val cfg = ProviderConfig(preset, server.url("/v1").toString(), "sk-test", "demo", "")
            val session = OpenAiCompatibleSession(cfg, "System", emptyList())
            val error = runCatching { session.send("Hi", emptyList()) }.exceptionOrNull()
            assertTrue("expected ProviderFailure, got $error", error is ProviderFailure)
            assertFalse("must never leak raw provider response body", error?.message.orEmpty().contains("sk-super-secret-leak"))
            assertTrue(error?.message.orEmpty().contains("401"))

            val cancelSession = OpenAiCompatibleSession(cfg, "System", emptyList())
            cancelSession.cancel()
            val cancelled = runCatching { cancelSession.send("Hi", emptyList()) }.exceptionOrNull()
            assertTrue("expected cancelled ProviderFailure, got $cancelled", cancelled is ProviderFailure)
            assertTrue(cancelled?.message.orEmpty().contains("cancelled", ignoreCase = true))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun mainActivitySwitchesToHomeOnAssistantIntent() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        Settings(ctx).onboarded = true
        val start = Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, R.id.nav_models)
        val controller = Robolectric.buildActivity(MainActivity::class.java, start).setup()
        try {
            val activity = controller.get()
            val nav = activity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.nav)
            assertEquals(R.id.nav_models, nav.selectedItemId)
            controller.newIntent(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_ASSIST))
            ShadowLooper.idleMainLooper()
            assertEquals(R.id.nav_home, nav.selectedItemId)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
