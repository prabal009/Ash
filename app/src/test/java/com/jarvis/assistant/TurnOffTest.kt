package com.jarvis.assistant

import android.Manifest
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.jarvis.assistant.service.JarvisService
import com.jarvis.assistant.service.BootReceiver
import android.content.Intent
import org.junit.Assert.assertEquals
import com.jarvis.assistant.voice.VoiceInput
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** "Turn off" must stop listening and stay off. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TurnOffTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val app get() = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())

    @Before
    fun setUp() {
        ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().clear().commit()
        app.grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        JarvisService.resetForTest()
    }

    @Test
    fun turningOffStopsTheServiceWithoutStartingItAgain() {
        assertTrue(JarvisService.start(ctx))
        assertEquals(JarvisService.ACTION_ARM, app.nextStartedService?.action)
        Settings(ctx).switchedOn = true // state set by the running, promoted service
        app.clearStartedServices()

        JarvisService.stop(ctx)
        assertFalse(Settings(ctx).switchedOn)
        assertNull("turning off must not start the service", app.nextStartedService)
    }

    @Test
    fun bootNeverStartsCaptureEvenWhenRemindersAreOn() {
        Settings(ctx).apply { switchedOn = true; startOnBoot = true }
        app.clearStartedServices()
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(app.nextStartedService)
    }

    @Test
    fun manualTalkDoesNotArmStandby() {
        assertTrue(JarvisService.start(ctx, JarvisService.ACTION_LISTEN))
        assertEquals(JarvisService.ACTION_LISTEN, app.nextStartedService?.action)
        assertFalse(Settings(ctx).switchedOn)
    }

    @Test
    fun aModelLoadFinishingAfterTurnOffDoesNotListenAgain() {
        val voice = VoiceInput(ctx) {}
        var readyCalled = false
        voice.load { readyCalled = true; voice.start() }
        voice.release() // turned off while the models were still loading
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertFalse("no callback after release", readyCalled)

        voice.load { readyCalled = true }
        Thread.sleep(300)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertFalse(readyCalled)
    }

    @Test
    fun destroyedServiceIsOff() {
        val controller = Robolectric.buildService(JarvisService::class.java).create()
        controller.destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertFalse(JarvisService.running)
    }
}
