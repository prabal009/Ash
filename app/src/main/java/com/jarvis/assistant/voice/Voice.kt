package com.jarvis.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import com.jarvis.assistant.CLOUD_VOICES
import com.jarvis.assistant.Settings

interface VoiceEngine {
    /** Speak [text], replacing anything currently being said. [onDone] runs on the main thread. */
    fun speak(text: String, onDone: () -> Unit)
    fun shutdown()
}

internal val assistantSpeechAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ASSISTANT)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .build()

/**
 * Ash's speech output. A cloud or local voice has to own audio focus before it can speak.
 * A cloud failure can fall back to a local voice under the same focus lease.
 * Call the playback controls on the main thread.
 */
class Speaker(context: Context) {
    private val app = context.applicationContext
    private val settings = Settings(app)
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(AudioManager::class.java)
    private val system by lazy { SystemVoice(app) }
    private val cloud by lazy { CloudVoice() }
    private var usedSystem = false
    private var closed = false
    /** Whether the current speech can report its loudness (everything except Android's voice). */
    @Volatile private var measurable = false

    /** Called after a focus loss stops output. The interrupted utterance doesn't complete. */
    var onFocusLost: (() -> Unit)? = null

    val usingKokoro get() = settings.voiceEngine == "kokoro" && kokoroReady

    private val kokoroReady get() = Kokoro.isInstalled(app) && !Kokoro.failed

    /** The cloud voice's settings, or null if a cloud voice isn't selected or has no key yet. */
    private fun cloudConfig() = CLOUD_VOICES.firstOrNull { it.id == settings.voiceEngine }?.let { preset ->
        settings.cloudVoice(preset.id).takeIf { !preset.needsKey || it.apiKey.isNotBlank() }
    }

    /** The last problem with the chosen voice, for Settings. */
    val lastError: String?
        get() = if (cloudConfig() != null || settings.voiceEngine in CLOUD_VOICES.map { it.id }) cloud.lastError else Kokoro.lastError

    @MainThread
    fun speak(text: String, onDone: () -> Unit = {}) = speak(text, onSkipped = onDone, onDone = onDone)

    /**
     * Replaces this speaker's utterance. [onSkipped] runs if playback can't start because
     * focus was denied or another Ash speaker (for example, a Settings preview) owns output.
     */
    @MainThread
    fun speak(text: String, onSkipped: () -> Unit, onDone: () -> Unit) {
        val previous = active
        if (closed || (previous != null && previous.speaker !== this)) {
            onSkipped()
            return
        }
        previous?.cancel()
        val utterance = Utterance(onDone)
        if (audio?.requestAudioFocus(utterance.request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            onSkipped()
            return
        }
        active = utterance
        try {
            utterance.speak(text)
        } catch (e: RuntimeException) {
            if (active === utterance) utterance.cancel()
            throw e
        }
    }

    private enum class Backend { KOKORO, CLOUD, SYSTEM }

    private inner class Utterance(private val onDone: () -> Unit) {
        val speaker get() = this@Speaker
        private var backend: Backend? = null
        val request: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(assistantSpeechAttributes)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                if (change < 0 && active === this) {
                    cancel()
                    onFocusLost?.invoke()
                }
            }, main)
            .build()

        fun speak(text: String) {
            val config = cloudConfig()
            if (config == null) {
                speakLocally(text)
            } else {
                backend = Backend.CLOUD
                measurable = true
                cloud.speak(config, text, settings.voiceSpeed, ::complete) {
                    if (active === this) {
                        cloud.stop()
                        speakLocally(text)
                    }
                }
            }
        }

        private fun speakLocally(text: String) {
            // Kokoro when chosen or as the cloud fallback; otherwise use Android's voice.
            if (kokoroReady && settings.voiceEngine != "system") {
                backend = Backend.KOKORO
                measurable = true
                Kokoro.speak(app, text, settings.kokoroSpeaker, settings.voiceSpeed, ::complete)
            } else {
                backend = Backend.SYSTEM
                usedSystem = true
                measurable = false
                system.speak(text, ::complete)
            }
        }

        private fun complete() {
            if (active !== this) return
            active = null
            measurable = false
            audio?.abandonAudioFocusRequest(request)
            onDone()
        }

        fun cancel() {
            active = null
            measurable = false
            try {
                when (backend) {
                    Backend.KOKORO -> Kokoro.stop()
                    Backend.CLOUD -> cloud.stop()
                    Backend.SYSTEM -> system.stop()
                    null -> Unit
                }
            } finally {
                audio?.abandonAudioFocusRequest(request)
            }
        }
    }

    /**
     * Says that [text] will be spoken straight after the current speech, so the voice can make it
     * meanwhile. Only Kokoro uses this; the other voices start quickly enough without it.
     */
    @MainThread
    fun prepare(text: String) {
        val current = active
        if (closed || (current != null && current.speaker !== this)) return
        if (cloudConfig() == null && kokoroReady && settings.voiceEngine != "system") {
            Kokoro.prepare(app, text, settings.kokoroSpeaker, settings.voiceSpeed)
        }
    }

    /** Load the voice now so the next reply starts without delay. */
    fun warmUp() { if (usingKokoro) Kokoro.warmUp(app) }

    @MainThread
    fun stop() {
        val current = active
        if (current != null && current.speaker === this) current.cancel()
        else if (current == null) Kokoro.stop() // drops pre-rendered audio when ending a reply
    }

    /** Loudness of the speech playing now (0..1), or null if this voice can't tell. */
    fun level(): Float? = if (measurable) AudioOut.level() else null

    @MainThread
    fun shutdown() {
        if (closed) return
        closed = true
        stop()
        if (usedSystem) system.shutdown()
    }

    private companion object {
        /** The preview and service share the same PCM output. Neither can seize it from the other. */
        var active: Utterance? = null
    }
}
