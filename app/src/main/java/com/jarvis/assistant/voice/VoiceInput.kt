package com.jarvis.assistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.assistant.audio.AudioRouteController
import com.jarvis.assistant.audio.AudioRoutePurpose
import com.jarvis.assistant.audio.BuiltInPhoneAudioRouteController
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.KeywordSpotterResult
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Pinned English keyword transducer (~5 MB installed). */
object WakeModel {
    const val URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2"
    const val ENCODER = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
    const val DECODER = "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
    const val JOINER = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
    val KEEP_FILES = setOf(ENCODER, DECODER, JOINER, "tokens.txt")
    /** SentencePiece rows from this transducer's GigaSpeech vocabulary. */
    const val KEYWORDS = "▁HE Y ▁A SH :2.9 #0.055 @HEY_ASH\n▁HE LL O ▁A SH :2.0 #0.1 @HELLO_ASH\n"
    /** An optional interruption while Ash is speaking. */
    const val STOP_KEYWORD = "▁ST O P :2.0 #0.15 @STOP"
    fun dir(context: Context) = File(context.filesDir, "kws")
    fun isInstalled(context: Context) = KEEP_FILES.all { File(dir(context), it).exists() }
}

/** Offline English speech recognition for commands (Kroko streaming zipformer, ~70 MB). */
object SpeechModel {
    const val URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06.tar.bz2"
    val KEEP_FILES = setOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")
    fun dir(context: Context) = File(context.filesDir, "asr")
    fun isInstalled(context: Context) = KEEP_FILES.all { File(dir(context), it).exists() }
}

/** Shared by the assistant, its local recognition provider, and the platform fallback client. */
internal object MicrophoneOwner {
    private val current = AtomicReference<Any?>()
    fun acquire(owner: Any) = current.compareAndSet(null, owner)
    fun release(owner: Any) { current.compareAndSet(owner, null) }
}

/**
 * Feeds a single microphone to local KWS and command ASR. Recorder and native streams
 * are used and closed only by the capture thread; callbacks are delivered on main.
 */
class VoiceInput(
    private val context: Context,
    private val routeController: AudioRouteController = BuiltInPhoneAudioRouteController,
    private val onWake: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    @Volatile private var spotter: KeywordSpotter? = null
    @Volatile private var recognizer: OnlineRecognizer? = null
    @Volatile private var released = false
    @Volatile private var generation = 0L
    @Volatile private var stopVersion = 0L
    @Volatile private var listening = false
    private var capture: Capture? = null // lock
    private var releaseFailed = false // lock; never acknowledge a failed AudioRecord release
    private var loading = false
    private val loadWaiters = ArrayList<Pair<Long, (String?) -> Unit>>()
    @Volatile private var request: CommandRequest? = null
    @Volatile private var commandId = 0L
    @Volatile private var cancelId = 0L
    @Volatile private var speechId = 0L
    @Volatile private var speaking: Speaking? = null
    @Volatile private var micGain = MicGain()

    @Volatile var earlyAccept: ((String) -> Long?)? = null

    /**
     * Main-thread status: ok (verified phone capture), busy, permission, unavailable (model or
     * route), interrupted (policy or route changed), silent (zero PCM), retrying, failed,
     * stop_timeout, or overrun (wake handoff discarded).
     */
    @Volatile var onMicProblem: ((problem: String, attempts: Int) -> Unit)? = null

    @Volatile var muted = false
    @Volatile var level = 0f
        private set
    @Volatile var customWakePhrase = ""
    @Volatile var wakeEnabled = true
    @Volatile var echoCancellation = true
    @Volatile var autoGain = true
    val gain get() = if (autoGain) micGain.gain else 1f
    val echoLog = mutableListOf<String>()
    @Volatile var lastEchoHeard = ""
        private set
    @Volatile var speechSinceWake = false
        private set

    val partialText: String get() = request?.text.orEmpty()
    val canTranscribe get() = !released && recognizer != null
    /** True only after the recorder produces live PCM from a verified phone microphone. */
    val isListening get() = listening
    val hasWakeWord get() = !released && spotter != null

    fun listenWhileSpeaking(spoken: String, bargeIn: (stop: Boolean) -> Unit) {
        val words = words(spoken)
        val transcript = " ${words.joinToString(" ")} "
        val custom = WakePhrase.optional(customWakePhrase)
        val saysName = " hey ash " in transcript || " hello ash " in transcript ||
            (custom.isNotEmpty() && " ${custom.lowercase()} " in transcript)
        synchronized(lock) { speaking = Speaking(++speechId, words.toSet(), saysName, soundsLikeStop(spoken), bargeIn) }
        muted = false
    }

    fun stopListeningWhileSpeaking() {
        synchronized(lock) {
            ++speechId
            speaking = null
        }
    }

    /** Loads installed models once per instance at a time; loading itself never opens the mic. */
    fun load(ready: (error: String?) -> Unit) {
        synchronized(lock) {
            if (released) return
            loadWaiters += stopVersion to ready
            if (loading) return
            loading = true
        }
        modelWorker.execute {
            var error: String? = null
            if (!released && spotter == null) {
                runCatching { if (WakeModel.isInstalled(context)) openSpotter() else null }
                    .onSuccess { spotter = it }
                    .onFailure {
                        Log.e(TAG, "wake model initialization failed", it)
                        error = "The wake model couldn't start. Try installing it again in Settings."
                    }
            }
            if (!released && recognizer == null) {
                runCatching { if (SpeechModel.isInstalled(context)) openRecognizer() else null }
                    .onSuccess { recognizer = it }
                    .onFailure {
                        Log.e(TAG, "speech model initialization failed", it)
                        if (error == null) error = "On-device speech recognition couldn't start. Try installing it again in Settings."
                    }
            }
            val waiters = synchronized(lock) {
                loading = false
                disposeModelsIfIdle()
                loadWaiters.toList().also { loadWaiters.clear() }
            }
            main.post {
                for ((version, callback) in waiters) {
                    if (!released && version == stopVersion) callback(error)
                }
            }
        }
    }

    private fun openSpotter(): KeywordSpotter {
        val dir = WakeModel.dir(context)
        val tokens = File(dir, "tokens.txt")
        val vocabulary = tokens.useLines { lines -> lines.map { it.substringBeforeLast(' ') }.toSet() }
        val required = (WakeModel.KEYWORDS + WakeModel.STOP_KEYWORD).lineSequence()
            .flatMap { it.splitToSequence(' ').takeWhile { field -> field.firstOrNull()?.let { c -> c !in ":#@" } == true } }
        check(required.all { it in vocabulary }) { "Wake keywords do not match installed vocabulary" }
        val keywords = File(dir, "ash_keywords.txt").apply { writeText(WakeModel.KEYWORDS) }
        return KeywordSpotter(config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(dir, WakeModel.ENCODER).absolutePath,
                    decoder = File(dir, WakeModel.DECODER).absolutePath,
                    joiner = File(dir, WakeModel.JOINER).absolutePath,
                ),
                tokens = tokens.absolutePath,
                numThreads = 1,
            ),
            keywordsFile = keywords.absolutePath,
            keywordsScore = 2.0f,
            keywordsThreshold = 0.1f,
        ))
    }

    private fun openRecognizer(): OnlineRecognizer {
        val dir = SpeechModel.dir(context)
        return OnlineRecognizer(config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(dir, "encoder.onnx").absolutePath,
                    decoder = File(dir, "decoder.onnx").absolutePath,
                    joiner = File(dir, "joiner.onnx").absolutePath,
                ),
                tokens = File(dir, "tokens.txt").absolutePath,
                numThreads = 2,
            ),
            enableEndpoint = true,
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0f),
                rule2 = EndpointRule(true, 1.2f, 0f),
                rule3 = EndpointRule(false, 0f, 20f),
            ),
        ))
    }

    /** Must hold [lock]. Only dispose once neither loader nor capture can touch the models. */
    private fun disposeModelsIfIdle() {
        if (!released || loading || capture != null) return
        val kws = spotter
        val asr = recognizer
        spotter = null
        recognizer = null
        if (kws == null && asr == null) return
        modelWorker.execute {
            closeResource { kws?.release() }
            closeResource { asr?.release() }
        }
    }

    fun start() {
        synchronized(lock) { startLocked() }
    }

    private fun startLocked(): Boolean {
        if (released) return false
        val active = capture
        if (active != null && !active.closing) return true
        val problem = when {
            releaseFailed -> "failed"
            active != null -> "busy"
            spotter == null && recognizer == null -> "unavailable"
            else -> null
        }
        if (problem != null) {
            report(generation, problem)
            return false
        }
        val next = Capture(generation + 1)
        if (!MicrophoneOwner.acquire(next)) {
            report(generation, "busy")
            return false
        }
        generation = next.generation
        capture = next
        Thread({ captureLoop(next) }, "ash-mic").start()
        return true
    }

    fun stop() = stopCapture(null)

    /** [onStopped] runs on main only after the recorder and its streams actually closed. */
    fun stop(onStopped: () -> Unit) = stopCapture(onStopped)

    private fun stopCapture(onStopped: (() -> Unit)?) {
        synchronized(lock) {
            ++stopVersion
            ++generation
            cancelCommandLocked()
            ++speechId
            speaking = null
            listening = false
            level = 0f
            val active = capture
            if (active == null) {
                if (onStopped != null && !releaseFailed) acknowledgeStop(listOf(onStopped))
                return
            }
            if (onStopped != null) active.stopWaiters += onStopped
            if (active.closing) return
            active.closing = true
            val timeout = Runnable {
                if (synchronized(lock) { capture === active }) onMicProblem?.invoke("stop_timeout", 0)
            }
            active.stopTimeout = timeout
            main.postDelayed(timeout, STOP_TIMEOUT_MS)
            active.stopSignal.countDown()
        }
    }

    fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            loadWaiters.clear()
            stopCapture(null)
            disposeModelsIfIdle()
        }
    }

    /** Transcribes the current local turn or starts a manual one. */
    fun listenForCommand(silenceTimeoutMs: Long = 7000, onResult: (String?) -> Unit) {
        synchronized(lock) {
            if (released) return
            val next = CommandRequest(++commandId, silenceTimeoutMs.coerceIn(1, MAX_COMMAND_MS), onResult)
            request = next
            if (recognizer == null || !startLocked()) completeCommand(next, generation, null)
        }
    }

    /** Explicit end of input, used by Android's external recognition client. */
    fun finishCommand() {
        request?.finish = true
    }

    fun cancelCommand() {
        synchronized(lock) { cancelCommandLocked() }
    }

    private fun cancelCommandLocked() {
        ++cancelId
        ++commandId
        request = null
        speechSinceWake = false
    }

    private fun completeCommand(command: CommandRequest, gen: Long, text: String?) {
        synchronized(lock) {
            if (request !== command) return
            request = null
        }
        main.post {
            if (!released && generation == gen && commandId == command.id && (text == null || listening)) {
                command.onResult(text)
            }
        }
    }

    private class Capture(val generation: Long) {
        @Volatile var closing = false
        val stopSignal = java.util.concurrent.CountDownLatch(1)
        val stopWaiters = ArrayList<() -> Unit>()
        var stopTimeout: Runnable? = null
        var closedSafely = true // capture worker only
    }

    private class CommandRequest(val id: Long, val silenceTimeoutMs: Long, val onResult: (String?) -> Unit) {
        val started = SystemClock.elapsedRealtime()
        @Volatile var text = ""
        @Volatile var finish = false
    }

    private class Speaking(
        val id: Long, val words: Set<String>, val saysName: Boolean, val saysStop: Boolean,
        val onBargeIn: (Boolean) -> Unit,
    )

    private enum class MicExit(val code: String) {
        STOPPED(""), PERMISSION("permission"), UNAVAILABLE("unavailable"), INTERRUPTED("interrupted"),
        SILENT("silent"), FAILED("failed")
    }

    private fun report(gen: Long, code: String, attempt: Int = 0) {
        main.post {
            if (!released && generation == gen && (code != "ok" || listening)) onMicProblem?.invoke(code, attempt)
        }
    }

    private fun acknowledgeStop(waiters: List<() -> Unit>) {
        main.post { waiters.forEach { it() } }
    }

    private fun captureLoop(owner: Capture) {
        var attempts = 0
        var exit = MicExit.STOPPED
        try {
            while (!owner.closing) {
                exit = runMic(owner)
                if (owner.closing || !owner.closedSafely || exit != MicExit.FAILED) break
                request?.let { completeCommand(it, owner.generation, null) }
                if (!wakeEnabled || attempts == RETRY_DELAYS.size) break
                report(owner.generation, "retrying", attempts + 1)
                owner.stopSignal.await(RETRY_DELAYS[attempts++], java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        } finally {
            synchronized(lock) {
                listening = false
                level = 0f
                if (owner.closedSafely) MicrophoneOwner.release(owner) else releaseFailed = true
                capture = null
                owner.stopTimeout?.let(main::removeCallbacks)
                if (!owner.closedSafely) {
                    report(generation, "failed")
                    request?.let { completeCommand(it, owner.generation, null) }
                } else if (owner.closing) {
                    if (owner.stopWaiters.isNotEmpty()) acknowledgeStop(owner.stopWaiters)
                } else {
                    if (exit != MicExit.STOPPED) report(owner.generation, exit.code, attempts)
                    request?.let { completeCommand(it, owner.generation, null) }
                }
                disposeModelsIfIdle()
            }
        }
    }

    private fun hasMicPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    internal fun micBlocked(audio: AudioManager): Boolean = audio.isMicrophoneMute ||
        audio.mode == AudioManager.MODE_IN_CALL ||
        audio.mode == AudioManager.MODE_RINGTONE ||
        (audio.mode == AudioManager.MODE_IN_COMMUNICATION && !routeController.ownsCommunicationRoute)

    private fun runMic(owner: Capture): MicExit {
        if (!hasMicPermission()) return MicExit.PERMISSION
        val audio = context.getSystemService(AudioManager::class.java) ?: return MicExit.UNAVAILABLE
        if (micBlocked(audio)) return MicExit.INTERRUPTED
        var record: AudioRecord? = null
        var echoCanceller: AcousticEchoCanceler? = null
        var recordingCallback: AudioManager.AudioRecordingCallback? = null
        val pipeline = Pipeline(owner)
        try {
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) return MicExit.UNAVAILABLE
            val initialPurpose = if (request != null) AudioRoutePurpose.COMMAND_CAPTURE else AudioRoutePurpose.WAKE_STANDBY
            val policy = routeController.prepareInput(initialPurpose)
            val input = AudioRecord.Builder()
                .setAudioSource(policy.audioSource)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minBuffer, CHUNK * 4))
                .apply { if (Build.VERSION.SDK_INT >= 31) setContext(context) }
                .build()
            policy.preferredDevice?.let { runCatching { input.setPreferredDevice(it) } }
            record = input
            val silenced = AtomicBoolean()
            if (Build.VERSION.SDK_INT >= 29) {
                val callback = object : AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                        if (configs.any { it.clientAudioSessionId == input.audioSessionId && it.isClientSilenced }) {
                            silenced.set(true)
                        }
                    }
                }
                input.registerAudioRecordingCallback(context.mainExecutor, callback)
                recordingCallback = callback
            }
            if (owner.closing) return MicExit.STOPPED
            input.startRecording()
            if (input.recordingState != AudioRecord.RECORDSTATE_RECORDING) return MicExit.FAILED
            val pcm = ShortArray(CHUNK)
            var buffered = 0
            var badReads = 0
            var silentBlocks = 0
            var noRouteBlocks = 0
            var route: Int? = null
            var aecEnabled = false
            micGain = MicGain()
            while (!owner.closing) {
                val n = input.read(pcm, buffered, CHUNK - buffered)
                if (owner.closing) return MicExit.STOPPED
                if (!hasMicPermission()) return MicExit.PERMISSION
                if (silenced.get() || micBlocked(audio)) return MicExit.INTERRUPTED
                if (n <= 0) {
                    if (n == AudioRecord.ERROR_DEAD_OBJECT || ++badReads >= MAX_READ_ERRORS) return MicExit.FAILED
                    Thread.sleep(20)
                    continue
                }
                badReads = 0
                buffered += n
                if (buffered < CHUNK) continue
                buffered = 0
                if (input.recordingState != AudioRecord.RECORDSTATE_RECORDING) return MicExit.FAILED
                val purpose = if (request != null) AudioRoutePurpose.COMMAND_CAPTURE else AudioRoutePurpose.WAKE_STANDBY
                val actual = routedDeviceOf(input)
                if (actual == null) {
                    if (route != null) return MicExit.INTERRUPTED
                    if (++noRouteBlocks >= MAX_NO_ROUTE_BLOCKS) return MicExit.UNAVAILABLE
                    continue
                }
                if (!routeController.acceptInputDevice(actual, purpose)) {
                    return if (route == null) MicExit.UNAVAILABLE else MicExit.INTERRUPTED
                }
                if (route != null && route != actual.id) return MicExit.INTERRUPTED
                route = actual.id
                if (isDigitalSilence(pcm)) {
                    if (++silentBlocks >= SILENT_BLOCKS) return MicExit.SILENT
                } else {
                    silentBlocks = 0
                    if (!listening) {
                        synchronized(lock) {
                            if (owner.closing) return MicExit.STOPPED
                            listening = true
                        }
                        report(owner.generation, "ok")
                    }
                }
                if (!listening) continue
                val useAec = echoCancellation && speaking != null
                if (useAec != aecEnabled) {
                    aecEnabled = useAec
                    runCatching {
                        if (useAec && echoCanceller == null && AcousticEchoCanceler.isAvailable()) {
                            echoCanceller = AcousticEchoCanceler.create(input.audioSessionId)
                        }
                        echoCanceller?.enabled = useAec
                    }
                }
                pipeline.consume(pcm)
            }
            return MicExit.STOPPED
        } catch (e: SecurityException) {
            Log.w(TAG, "microphone access denied", e)
            return if (hasMicPermission()) MicExit.INTERRUPTED else MicExit.PERMISSION
        } catch (e: Throwable) {
            Log.w(TAG, "microphone capture failed", e)
            return MicExit.FAILED
        } finally {
            listening = false
            val streamsClosed = pipeline.close()
            closeResource { echoCanceller?.release() }
            if (Build.VERSION.SDK_INT >= 29) {
                recordingCallback?.let { callback -> closeResource { record?.unregisterAudioRecordingCallback(callback) } }
            }
            closeResource { record?.stop() }
            val recorderClosed = closeResource { record?.release() }
            owner.closedSafely = streamsClosed && recorderClosed
        }
    }

    private fun isDigitalSilence(pcm: ShortArray): Boolean {
        for (sample in pcm) if (sample.toInt() != 0) return false
        return true
    }

    /** Reusable 100 ms slots; the slot being decoded becomes history by swapping references. */
    private class PcmHistory {
        private val blocks = Array(HANDOFF_BLOCKS) { FloatArray(CHUNK) }
        private var first = 0
        var size = 0
            private set
        var input = FloatArray(CHUNK)
            private set

        fun remember(limit: Int) {
            while (size >= limit) {
                first = (first + 1) % blocks.size
                --size
            }
            val index = (first + size) % blocks.size
            val next = blocks[index]
            blocks[index] = input
            input = next
            ++size
        }

        operator fun get(index: Int) = blocks[(first + index) % blocks.size]

        fun clear() {
            for (i in 0 until size) get(i).fill(0f)
            size = 0
        }

        fun wipe() {
            blocks.forEach { it.fill(0f) }
            input.fill(0f)
        }
    }

    private class Handoff(val phrase: String = "", val echo: Set<String>? = null, val wakeEnd: Float? = null) {
        @Volatile var accepted = false
        var lost = false
    }

    private class CommandSession(val request: CommandRequest, val stream: OnlineStream, handoff: Handoff?) {
        var prefix = prefixPattern(handoff?.phrase.orEmpty())
        var wakeEnd = handoff?.wakeEnd
        val echo = handoff?.echo
        var liveSamples = 0L
        var quietSamples = 0L
    }

    private inner class Pipeline(private val owner: Capture) {
        private val history = PcmHistory()
        private var session: CommandSession? = null
        private var wakeStream: OnlineStream? = null
        private var wakeSamples = 0L
        private var wakeFrames = 0L
        private var echoStream: OnlineStream? = null
        private var stopStream: OnlineStream? = null
        private var echoFor: Speaking? = null
        private var handoff: Handoff? = null
        private var phraseSetting: String? = null
        private var phrase = ""
        private var customRow = ""
        private var seenCancel = cancelId

        fun consume(pcm: ShortArray) {
            if (seenCancel != cancelId) {
                seenCancel = cancelId
                clearCommand()
                handoff = null
                history.clear()
                resetWake()
            }
            val command = request
            val played = speaking
            if (played !== echoFor) {
                resetEcho()
                echoFor = played
                if (handoff == null) history.clear()
                resetWake()
            }
            if (session?.request !== command) clearCommand()
            if (muted) {
                level = 0f
                history.clear()
                handoff?.lost = true
                resetWake()
                resetEcho()
                if (command != null && (command.finish || SystemClock.elapsedRealtime() - command.started >= MAX_COMMAND_MS)) {
                    clearCommand()
                    completeCommand(command, owner.generation, null)
                }
                return
            }

            val samples = history.input
            for (i in pcm.indices) samples[i] = pcm[i] / 32768f
            val gainControl = micGain
            val raw = if (autoGain) gainControl.process(samples, learn = command == null && handoff == null && played == null)
                else MicGain.rms(samples)
            val boosted = raw * gain
            level = (boosted * 8).coerceAtMost(1f)
            val voiced = boosted > maxOf(MIN_SPEECH_LEVEL, gainControl.noiseFloor * gain * 3)
            if (voiced && (command != null || handoff != null)) speechSinceWake = true
            if (command != null) {
                val asr = checkNotNull(recognizer)
                if (session == null) {
                    val pending = handoff?.takeIf { it.accepted }
                    handoff = null
                    if (pending?.lost == true) {
                        history.clear()
                        completeCommand(command, owner.generation, null)
                        return
                    }
                    val next = CommandSession(command, asr.createStream(""), pending)
                    session = next
                    resetWake()
                    if (pending != null) {
                        for (i in 0 until history.size) {
                            if (owner.closing || request !== command || feed(asr, next, history[i], replay = true)) {
                                clearCommand()
                                history.clear()
                                return
                            }
                        }
                    }
                    history.clear()
                }
                val active = checkNotNull(session)
                active.liveSamples += CHUNK
                active.quietSamples = if (voiced) 0 else active.quietSamples + CHUNK
                if (feed(asr, active, samples, replay = false)) clearCommand()
                return
            }

            val pending = handoff
            if (pending != null) {
                if (pending.lost || recognizer == null) return
                if (history.size == HANDOFF_BLOCKS) {
                    history.clear()
                    pending.lost = true
                    report(owner.generation, "overrun")
                } else history.remember(HANDOFF_BLOCKS)
                return
            }

            if (played != null && checkBargeIn(played, samples)) return
            val kws = spotter
            if (kws != null && wakeEnabled && played?.saysName != true) {
                val setting = customWakePhrase
                if (setting != phraseSetting) {
                    resetWake()
                    phraseSetting = setting
                    phrase = WakePhrase.optional(setting)
                    customRow = if (phrase.isEmpty()) "" else runCatching { WakePhrase.keyword(context, phrase) }
                        .onFailure { Log.w(TAG, "custom wake phrase couldn't be tokenized", it) }.getOrDefault("")
                }
                val stream = wakeStream ?: kws.createStream(customRow).also { wakeStream = it }
                wakeSamples += samples.size
                val result = keyword(kws, stream, samples, wake = true)
                val hit = WakePhrase.detectedPhrase(result?.keyword.orEmpty(), phrase)
                history.remember(if (played == null) PREROLL_BLOCKS else ECHO_BLOCKS)
                if (hit.isNotEmpty()) {
                    val wakeEnd = result?.timestamps?.lastOrNull()?.let {
                        // The native decoder can reset its timestamps after silence. Resets occur
                        // at chunk boundaries; recover the latest matching frame before hit blanks.
                        val localFrame = (it / KWS_FRAME_SECONDS + 0.5f).toInt()
                        val latest = wakeFrames - 1 - KWS_CONFIRMATION_BLANKS
                        val frame = latest - Math.floorMod(latest - localFrame, KWS_CHUNK_FRAMES)
                        frame * KWS_FRAME_SECONDS - (wakeSamples - history.size.toLong() * CHUNK) / SAMPLE_RATE.toFloat()
                    }
                    resetWake()
                    val hitCommand = commandId
                    val hitCancel = seenCancel
                    val episode = Handoff(phrase = hit, echo = played?.words, wakeEnd = wakeEnd)
                    handoff = episode
                    speechSinceWake = false
                    main.post {
                        if (!owner.closing && listening && owner.generation == generation &&
                            hitCommand == commandId && hitCancel == cancelId) {
                            episode.accepted = true
                            onWake()
                        }
                    }
                }
            } else {
                resetWake()
                if (played != null) history.remember(ECHO_BLOCKS) else history.clear()
            }
        }

        private fun checkBargeIn(played: Speaking, samples: FloatArray): Boolean {
            val kws = spotter
            val stop = !played.saysStop && kws != null && keyword(
                kws, stopStream ?: kws.createStream(WakeModel.STOP_KEYWORD).also { stopStream = it }, samples,
            )?.keyword == "STOP"
            if (!stop) {
                val asr = recognizer ?: return false
                val stream = echoStream ?: asr.createStream("").also { echoStream = it }
                stream.acceptWaveform(samples, SAMPLE_RATE)
                while (asr.isReady(stream)) asr.decode(stream)
                val heard = asr.getResult(stream).text
                lastEchoHeard = heard
                if (!isBargeIn(heard, played.words)) {
                    if (asr.isEndpoint(stream)) asr.reset(stream)
                    return false
                }
            }
            synchronized(lock) {
                if (speaking !== played || request != null || owner.closing || seenCancel != cancelId) return false
                speaking = null
            }
            synchronized(echoLog) {
                if (echoLog.size >= 20) echoLog.removeAt(0)
                echoLog += if (stop) "stop [keyword]" else "$lastEchoHeard [interrupt]"
            }
            val episode = if (stop) {
                history.clear()
                null
            } else {
                history.remember(ECHO_BLOCKS)
                Handoff(echo = played.words).also {
                    handoff = it
                    speechSinceWake = true
                }
            }
            resetWake()
            main.post {
                if (!owner.closing && listening && owner.generation == generation && speechId == played.id) {
                    episode?.accepted = true
                    played.onBargeIn(stop)
                }
            }
            return true
        }

        private fun keyword(kws: KeywordSpotter, stream: OnlineStream, samples: FloatArray, wake: Boolean = false): KeywordSpotterResult? {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            while (kws.isReady(stream)) {
                kws.decode(stream)
                if (wake) wakeFrames += KWS_CHUNK_FRAMES
                val result = kws.getResult(stream)
                if (result.keyword.isNotBlank()) {
                    kws.reset(stream)
                    return result
                }
            }
            return null
        }

        private fun expired(active: CommandSession) =
            active.liveSamples * 1000 / SAMPLE_RATE >= MAX_COMMAND_MS ||
                SystemClock.elapsedRealtime() - active.request.started >= MAX_COMMAND_MS

        /** True means the caller must not give this stream any more samples, including during replay. */
        private fun feed(asr: OnlineRecognizer, active: CommandSession, samples: FloatArray, replay: Boolean): Boolean {
            val turn = active.request
            val stream = active.stream
            if (owner.closing || request !== turn) return true
            stream.acceptWaveform(samples, SAMPLE_RATE)
            val finish = turn.finish && !replay
            if (finish) stream.inputFinished()
            while (asr.isReady(stream)) asr.decode(stream)
            val result = asr.getResult(stream)
            val rawText = stripEcho(result.text, active.echo)
            val cleaned = cleanPrefix(rawText, active.prefix)
            val wakeEnd = active.wakeEnd
            val command = if (wakeEnd != null && active.echo == null && rawText.isNotBlank() &&
                active.prefix?.containsMatchIn(rawText) != true
            ) dropTimedWake(rawText, result, wakeEnd) else cleaned
            // An isolated pre-roll tail is not a command, even when ASR detects an endpoint.
            val text = if (active.prefix != null && command.lowercase() in WAKE_TAIL_ARTIFACTS) "" else command
            if (!replay && !finish && rawText.isNotBlank() && text.isEmpty() && asr.isEndpoint(stream)) {
                // Discard the wake-only segment so it cannot become the prefix of the next utterance.
                asr.reset(stream)
                active.prefix = null
                active.wakeEnd = null
            }
            turn.text = text
            val quietFor = active.quietSamples * 1000 / SAMPLE_RATE
            val done = when {
                finish -> text.ifEmpty { null }
                expired(active) -> null // an incomplete turn must not dispatch on the hard deadline
                text.isNotEmpty() && asr.isEndpoint(stream) -> text
                replay -> return false
                text.isEmpty() && active.liveSamples * 1000 / SAMPLE_RATE >= turn.silenceTimeoutMs -> null
                text.isNotEmpty() && quietFor >= END_OF_SPEECH_MS -> text
                text.isNotEmpty() && earlyAccept?.invoke(text)?.let { quietFor >= it } == true -> text
                else -> return false
            }
            completeCommand(turn, owner.generation, done)
            return true
        }

        private fun clearCommand() {
            session?.stream?.release()
            session = null
        }

        private fun resetWake() {
            wakeStream?.release()
            wakeStream = null
            wakeSamples = 0
            wakeFrames = 0
        }

        private fun resetEcho() {
            echoStream?.release()
            echoStream = null
            stopStream?.release()
            stopStream = null
        }

        fun close(): Boolean {
            val commandClosed = closeResource { clearCommand() }
            val wakeClosed = closeResource { resetWake() }
            val echoClosed = closeResource { echoStream?.release() }
            val stopClosed = closeResource { stopStream?.release() }
            history.wipe()
            return commandClosed && wakeClosed && echoClosed && stopClosed
        }
    }

    companion object {
        private const val TAG = "VoiceInput"
        @Volatile internal var routedDeviceOf: (AudioRecord) -> AudioDeviceInfo? = AudioRecord::getRoutedDevice
        const val SAMPLE_RATE = 16000
        private const val END_OF_SPEECH_MS = 1300L
        private const val MAX_READ_ERRORS = 10
        private const val CHUNK = SAMPLE_RATE / 10
        // Pinned Zipformer KWS: 32 10-ms input frames per chunk, subsampled by four.
        private const val KWS_CHUNK_FRAMES = 8L
        private const val KWS_FRAME_SECONDS = 0.04f
        private const val KWS_CONFIRMATION_BLANKS = 3 // Decoder tests > numTrailingBlanks (2).
        private const val PREROLL_BLOCKS = 15
        private const val ECHO_BLOCKS = 30
        private const val HANDOFF_BLOCKS = 50
        private const val SILENT_BLOCKS = 50
        private const val MAX_NO_ROUTE_BLOCKS = 10
        private const val STOP_TIMEOUT_MS = 1500L
        private const val MAX_COMMAND_MS = 20_000L
        private const val MIN_SPEECH_LEVEL = 0.02f
        private val RETRY_DELAYS = longArrayOf(500, 2000)
        private val modelWorker = Executors.newSingleThreadExecutor { Thread(it, "ash-models").apply { isDaemon = true } }

        private inline fun closeResource(release: () -> Unit): Boolean = try {
            release()
            true
        } catch (e: Throwable) {
            Log.e(TAG, "failed to release audio resource", e)
            false
        }

        /** Words that mean "be quiet" when said over Jarvis. */
        val STOP_WORDS = setOf("stop", "wait", "cancel", "quiet", "enough", "pause", "hold", "shush")

        /** Lower-case words, letters and apostrophes only. */
        fun words(text: String): List<String> =
            text.lowercase().split(Regex("\\s+")).map { w -> w.filter { it.isLetter() || it == '\'' } }.filter { it.isNotEmpty() }

        /**
         * Whether a sentence Jarvis is about to say contains something the "stop" keyword could mistake
         * for "stop" ("top", "stock", "start", "story"…). For those sentences the keyword is switched off.
         */
        fun soundsLikeStop(spoken: String): Boolean {
            val ws = words(spoken)
            return ws.withIndex().any { (i, w) ->
                w.startsWith("st") && w.length >= 3 && w[2] in "aeiouy" ||               // stop, stock, start, story, step
                    w.startsWith("top") ||                                                  // top
                    (i > 0 && ws[i - 1].endsWith("s") && w.startsWith("to") && w.length <= 4) // "its top", "bus too"
            }
        }

        /** Whether [word] is something Jarvis said: the same word, nearly, or part of a longer one. */
        private fun isEcho(word: String, spoken: Set<String>) = spoken.any {
            it == word || (word.length >= 4 && editDistance(word, it) <= 1) || (word.length >= 3 && it.length > word.length && word in it)
        }

        /** Whether [heard] is someone talking over Jarvis, rather than Jarvis hearing itself say [spoken]. */
        fun isBargeIn(heard: String, spoken: Set<String>): Boolean {
            val foreign = words(heard).filter { w -> !isEcho(w, spoken) }
            if (foreign.any { it in STOP_WORDS }) return true
            return foreign.count { it.length >= 2 } >= 3
        }

        /** Drops Jarvis's own words (picked up as echo) from the start of [text]. */
        fun stripEcho(text: String, echo: Set<String>?): String {
            if (echo == null) return text
            val ws = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            var i = 0
            while (i < ws.size && words(ws[i]).firstOrNull()?.let { w -> isEcho(w, echo) } == true) i++
            return ws.drop(i).joinToString(" ")
        }

        /** "Stop", "never mind" and the like: the user wants Jarvis to stop, not to do something. */
        fun isStopCommand(text: String): Boolean {
            val t = words(text).joinToString(" ")
            return t in setOf("stop", "stop it", "stop that", "stop talking", "cancel", "cancel that", "never mind", "nevermind",
                "shut up", "be quiet", "quiet", "enough", "that's enough", "thats enough", "okay stop", "ok stop", "wait", "hold on", "no stop")
        }

        private const val PREFIX_SEPARATOR = "[\\s,.!?;:…—–]"
        private val WAKE_TAIL_ARTIFACTS = setOf("ash", "as", "asked")

        private fun prefixPattern(phrase: String): Regex? {
            if (phrase.isBlank()) return null
            val normalized = WakePhrase.normalize(phrase)
            if (normalized.equals(WakePhrase.DEFAULT, ignoreCase = true)) {
                return Regex("^hey$PREFIX_SEPARATOR+(?:ash|asked)(?:$PREFIX_SEPARATOR+|$)", RegexOption.IGNORE_CASE)
            }
            if (normalized.equals(WakePhrase.ALTERNATE, ignoreCase = true)) {
                return Regex("^hello$PREFIX_SEPARATOR+(?:ash|asked)(?:$PREFIX_SEPARATOR+|$)", RegexOption.IGNORE_CASE)
            }
            val prefix = normalized.split(' ')
                .joinToString("$PREFIX_SEPARATOR+") { Regex.escape(it) }
            return Regex("^$prefix(?:$PREFIX_SEPARATOR+|$)", RegexOption.IGNORE_CASE)
        }

        /** Removes this turn's detected wake phrase or a known ASR rendering at the start. */
        fun clean(raw: String, wakePhrase: String = ""): String = cleanPrefix(raw, prefixPattern(wakePhrase))

        private fun cleanPrefix(raw: String, prefix: Regex?): String {
            val text = raw.trim()
            val match = prefix?.find(text)
            val stripped = (if (match == null) text else text.substring(match.range.last + 1))
                .trimStart { !it.isLetterOrDigit() }
            return if (stripped.none { it.isLetterOrDigit() }) "" else stripped.replaceFirstChar { it.uppercase() }
        }

        /** Drops words that started in the wake span; never clips the subwords of a command. */
        private fun dropTimedWake(text: String, result: OnlineRecognizerResult, wakeEnd: Float): String {
            val tokens = result.tokens
            val times = result.timestamps
            val cutoff = wakeEnd + KWS_FRAME_SECONDS
            if (tokens.isEmpty() || tokens.size != times.size || times[0] > cutoff) return cleanPrefix(text, null)
            var length = tokens[0].trimStart().length
            for (i in 1 until tokens.size) {
                val token = tokens[i]
                if (token.startsWith(' ') && times[i] > cutoff) {
                    // The tokenizer text can differ from the displayed, normalized transcript.
                    // Do not remove a prefix unless the word boundary maps to the transcript.
                    return if (length < text.length && text[length].isWhitespace() &&
                        text.regionMatches(length + 1, token, 1, token.length - 1, ignoreCase = true)
                    ) cleanPrefix(text.substring(length + 1), null) else cleanPrefix(text, null)
                }
                length += token.length
            }
            return ""
        }

        fun editDistance(a: String, b: String): Int {
            val d = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = d[0]; d[0] = i
                for (j in 1..b.length) {
                    val tmp = d[j]
                    d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = tmp
                }
            }
            return d[b.length]
        }
    }
}
