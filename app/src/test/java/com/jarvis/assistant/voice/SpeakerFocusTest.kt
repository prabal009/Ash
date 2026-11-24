package com.jarvis.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import com.jarvis.assistant.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowTextToSpeech

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class SpeakerFocusTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val audio = shadowOf(context.getSystemService(AudioManager::class.java))
    private val main = shadowOf(Looper.getMainLooper())
    private val speaker = Speaker(context)

    @Before fun setUp() {
        Settings(context).voiceEngine = "system"
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
    }

    @After fun tearDown() = speaker.shutdown()

    private fun request(): AudioFocusRequest = audio.lastAudioFocusRequest.audioFocusRequest

    private fun startSystemVoice(): ShadowTextToSpeech {
        val tts = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        tts.onInitListener.onInit(TextToSpeech.SUCCESS)
        return tts
    }

    @Test fun denialSkipsWithoutStartingTheEngine() {
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        var outcome = ""
        speaker.onFocusLost = { outcome += "lost" }
        speaker.speak("No sound", onSkipped = { outcome += "skipped" }) { outcome += "completed" }
        main.idle()
        assertEquals("skipped", outcome)
        assertNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
        assertNull(audio.lastAbandonedAudioFocusRequest)
    }

    @Test fun naturalCompletionAbandonsTheMatchingRequestBeforeNotifying() {
        var abandoned: AudioFocusRequest? = null
        speaker.speak("First") { abandoned = audio.lastAbandonedAudioFocusRequest }
        val first = request()
        assertEquals(AudioAttributes.USAGE_ASSISTANT, first.audioAttributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, first.audioAttributes.contentType)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, first.focusGain)
        assertNull(audio.lastAbandonedAudioFocusRequest)
        startSystemVoice()
        main.idle()
        assertSame(first, abandoned)
    }

    @Test fun focusLossStopsPlaybackAndIgnoresTheLateTtsCompletion() {
        speaker.speak("Initialize")
        val tts = startSystemVoice()
        main.idle()
        var outcome = ""
        speaker.speak("Interrupted", onSkipped = { outcome += "skipped" }) { outcome += "completed" }
        val interrupted = request()
        speaker.onFocusLost = {
            assertSame(interrupted, audio.lastAbandonedAudioFocusRequest)
            outcome += "lost"
        }
        audio.lastAudioFocusRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        main.idle()
        assertEquals("lost", outcome)
        assertTrue(tts.isStopped)
    }

    @Test fun replacementAbandonsOldFocusAndIgnoresOldLossAndCompletion() {
        speaker.speak("Initialize")
        val tts = startSystemVoice()
        main.idle()
        var outcome = ""
        speaker.speak("Old") { outcome += "old" }
        val old = request()
        val oldListener = audio.lastAudioFocusRequest.listener
        speaker.speak("Replacement") { outcome += "replacement" }
        val replacement = request()
        assertNotSame(old, replacement)
        assertSame(old, audio.lastAbandonedAudioFocusRequest)
        speaker.onFocusLost = { outcome += "lost" }
        oldListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        main.idle()
        assertEquals("replacement", outcome)
        assertEquals("Replacement", tts.lastSpokenText)
        assertSame(replacement, audio.lastAbandonedAudioFocusRequest)
    }

    @Test fun stopReleasesFocusAndDropsSpeechWaitingForPlatformInitialization() {
        var outcome = ""
        speaker.speak("Queued") { outcome += "completed" }
        val queued = request()
        speaker.onFocusLost = { outcome += "lost" }
        speaker.stop()
        val tts = startSystemVoice()
        main.idle()
        assertEquals("", outcome)
        assertNull(tts.lastSpokenText)
        assertSame(queued, audio.lastAbandonedAudioFocusRequest)
    }
}
