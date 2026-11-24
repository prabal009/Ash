package com.jarvis.assistant.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionService
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.jarvis.assistant.router.AlternativePicker
import com.jarvis.assistant.router.FastCommandRouter
import com.jarvis.assistant.router.MemoryIntentParser
import com.jarvis.assistant.tasks.TaskControl

/** One-shot transcription of what the user says after the wake word. Must be used on the main thread. */
class CommandListener(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var active: Listening? = null
    private var closing = false
    private var cleanupFailed = false
    private val afterClose = ArrayList<() -> Unit>()

    private class Listening(val recognizer: SpeechRecognizer, val lease: Any, val timeout: Runnable)

    fun listen(onResult: (String?) -> Unit) = listen(null, onResult)

    fun listen(language: String?, onResult: (String?) -> Unit) = listen(language, offline = false, onResult = onResult)

    /** For offline requests use Android's on-device recognizer or fail; a preference extra alone isn't a guarantee. */
    fun listen(language: String?, offline: Boolean, onReady: (() -> Unit)? = null, onResult: (String?) -> Unit) {
        val gen = ++generation
        closeActive()
        whenClosed {
            if (generation == gen) begin(gen, language, offline, onReady, onResult)
        }
    }

    private fun begin(
        gen: Long, language: String?, offline: Boolean, onReady: (() -> Unit)?, onResult: (String?) -> Unit,
    ) {
        val lease = Any()
        if (cleanupFailed || !MicrophoneOwner.acquire(lease)) {
            onResult(null)
            return
        }
        val recognizer = try {
            if (offline) {
                if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                    // This is the OS-configured on-device recognizer, not the role-selected default service.
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else null
            } else {
                externalComponent()?.let { SpeechRecognizer.createSpeechRecognizer(context, it) }
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "recognizer unavailable", e)
            null
        }
        if (recognizer == null) {
            MicrophoneOwner.release(lease)
            onResult(null)
            return
        }

        fun isCurrent() = generation == gen && active?.lease === lease
        fun deliver(text: String?) {
            if (!isCurrent()) return
            closeActive()
            whenClosed { if (generation == gen) onResult(text.takeUnless { cleanupFailed }) }
        }

        val timeout = Runnable { deliver(null) }
        active = Listening(recognizer, lease, timeout)
        var ready = false
        try {
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    if (!isCurrent()) return
                    val alternatives = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    deliver(AlternativePicker.pick(alternatives) { text ->
                        FastCommandRouter.parse(text) != null || MemoryIntentParser.parse(text) != null || TaskControl.parse(text) != null
                    })
                }

                override fun onError(error: Int) = deliver(null)
                override fun onReadyForSpeech(params: Bundle?) {
                    if (isCurrent() && !ready) {
                        ready = true
                        onReady?.invoke()
                    }
                }

                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            if (language != null) {
                intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            }
            main.postDelayed(timeout, TIMEOUT_MS)
            recognizer.startListening(intent)
        } catch (e: RuntimeException) {
            Log.w(TAG, "recognizer couldn't start", e)
            deliver(null)
        }
    }

    /** Never ask SpeechRecognizer for an implicit default; that default can be Ash itself. */
    @Suppress("DEPRECATION")
    private fun externalComponent(): ComponentName? {
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        val pm = context.packageManager
        val external = pm.queryIntentServices(intent, 0).mapNotNull { it.serviceInfo }
            .filter { it.packageName != context.packageName && it.exported && it.enabled }
            .map { ComponentName(it.packageName, it.name) }.distinct()
        val resolved = pm.resolveService(intent, 0)?.serviceInfo?.let { ComponentName(it.packageName, it.name) }
        return resolved?.takeIf { it in external } ?: external.singleOrNull()
    }

    fun cancel() {
        ++generation
        closeActive()
    }

    /** Runs after the platform client has dispatched its teardown and relinquished the local lease. */
    fun cancel(onStopped: () -> Unit) {
        cancel()
        whenClosed { if (!cleanupFailed) onStopped() }
    }

    private fun whenClosed(action: () -> Unit) {
        if (closing) afterClose += action else action()
    }

    private fun closeActive() {
        val old = active ?: return
        active = null // destroy/cancel can still trigger late callbacks
        main.removeCallbacks(old.timeout)
        closing = true
        try {
            old.recognizer.destroy()
        } catch (e: RuntimeException) {
            Log.e(TAG, "recognizer teardown failed", e)
            cleanupFailed = true
        }
        // Android queues destroy on main. Dispatch before handing the process lease to a new client.
        // The public API does not provide an acknowledgement from the provider's actual AudioRecord.
        main.post {
            if (!cleanupFailed) MicrophoneOwner.release(old.lease)
            val waiters = afterClose.toList()
            afterClose.clear()
            closing = false
            waiters.forEach { it() }
        }
    }

    private companion object {
        const val TAG = "CommandListener"
        const val TIMEOUT_MS = 20_000L
    }
}
