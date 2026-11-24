package com.jarvis.assistant.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.annotation.MainThread

/** Android's built-in text-to-speech. Completion callbacks run on the main thread. */
class SystemVoice(context: Context) : VoiceEngine {
    private val main = Handler(Looper.getMainLooper())
    private var pending: Pair<String, () -> Unit>? = null
    private var queued: Pair<String, () -> Unit>? = null
    private var nextId = 0L
    private var initDone = false
    private var ready = false
    private var closed = false

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        main.post { onInitialized(status) }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finish(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
        })
    }

    private fun onInitialized(status: Int) {
        if (closed) return
        initDone = true
        ready = status == TextToSpeech.SUCCESS
        if (ready) tts.setAudioAttributes(assistantSpeechAttributes)
        val next = queued
        queued = null
        if (next != null) speak(next.first, next.second)
    }

    private fun finish(id: String?) {
        if (id != null) main.post { complete(id) }
    }

    private fun complete(id: String) {
        val current = pending ?: return
        if (current.first != id) return
        pending = null
        current.second()
    }

    @MainThread
    override fun speak(text: String, onDone: () -> Unit) {
        if (closed) return
        if (!initDone) {
            queued = text to onDone
            return
        }
        if (!ready) {
            onDone()
            return
        }
        val id = (++nextId).toString()
        pending = id to onDone
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.ERROR) complete(id)
    }

    /** Stops speaking. Callbacks for the interrupted speech are dropped. */
    @MainThread
    fun stop() {
        pending = null
        queued = null
        tts.stop()
    }

    @MainThread
    override fun shutdown() {
        if (closed) return
        closed = true
        stop()
        tts.shutdown()
    }
}
