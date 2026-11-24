package com.jarvis.assistant.assistant

import android.content.ContextParams
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.jarvis.assistant.service.JarvisService
import com.jarvis.assistant.voice.SpeechModel
import com.jarvis.assistant.voice.VoiceInput
import java.util.Locale

/** Legacy assistant recognition endpoint. It returns text, and never invokes Ash commands. */
class AshRecognitionService : RecognitionService() {
    private val main = Handler(Looper.getMainLooper())
    private var request: Request? = null

    private class Request(val listener: Callback, val voice: VoiceInput) {
        var finished = false
        var ready = false
    }

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        if (request != null || ownsCapture || JarvisService.running) {
            sendError(listener, SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return
        }
        val tag = recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE) ?: Locale.getDefault().toLanguageTag()
        if (Locale.forLanguageTag(tag).language != "en" || !SpeechModel.isInstalled(this)) {
            sendError(listener, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
            return
        }
        val inputContext = if (Build.VERSION.SDK_INT >= 31) {
            createContext(ContextParams.Builder().setNextAttributionSource(listener.callingAttributionSource).build())
        } else this
        val voice = VoiceInput(inputContext) {}.apply { wakeEnabled = false; echoCancellation = false }
        val active = Request(listener, voice)
        ownsCapture = true
        request = active
        voice.onMicProblem = { problem, _ ->
            if (request === active && !active.finished) {
                if (problem == "ok") {
                    if (!active.ready) {
                        active.ready = true
                        runCatching { listener.readyForSpeech(Bundle()) }.onFailure { close(active) }
                    }
                } else close(active, error = if (problem == "permission") SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS else SpeechRecognizer.ERROR_AUDIO)
            }
        }
        voice.load { _ ->
            if (request === active && !active.finished) {
                if (!voice.canTranscribe) close(active, error = SpeechRecognizer.ERROR_AUDIO)
                else voice.listenForCommand(7_000) { text ->
                    if (text.isNullOrBlank()) close(active, error = SpeechRecognizer.ERROR_NO_MATCH)
                    else close(active, text = text)
                }
            }
        }
        main.postDelayed({ close(active, error = SpeechRecognizer.ERROR_SPEECH_TIMEOUT) }, 25_000)
    }

    override fun onStopListening(listener: Callback) {
        request?.takeIf { it.listener === listener && !it.finished }?.let {
            if (it.ready) it.voice.finishCommand() else close(it, error = SpeechRecognizer.ERROR_NO_MATCH)
        }
    }

    override fun onCancel(listener: Callback) {
        request?.takeIf { it.listener === listener }?.let { close(it) }
    }

    private fun close(active: Request, text: String? = null, error: Int? = null) {
        if (request !== active || active.finished) return
        active.finished = true
        main.removeCallbacksAndMessages(null)
        active.voice.stop {
            active.voice.release()
            request = null
            ownsCapture = false
            if (text != null) runCatching {
                active.listener.results(Bundle().apply {
                    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                })
            } else if (error != null) sendError(active.listener, error)
        }
    }

    private fun sendError(listener: Callback, error: Int) {
        runCatching { listener.error(error) }
    }

    override fun onDestroy() {
        request?.let { close(it) }
        super.onDestroy()
    }

    companion object {
        internal var ownsCapture = false // both services' callbacks run on the main looper
            private set
    }
}
