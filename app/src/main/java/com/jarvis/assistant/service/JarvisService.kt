package com.jarvis.assistant.service

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.assistant.AshRecognitionService
import com.jarvis.assistant.audio.AudioRouteController
import com.jarvis.assistant.audio.AudioRouteListener
import com.jarvis.assistant.audio.BluetoothAudioRouteController
import com.jarvis.assistant.audio.BuiltInPhoneAudioRouteController
import com.jarvis.assistant.audio.OutputRouteKind
import com.jarvis.assistant.audio.RouteDisconnectReason
import com.jarvis.assistant.audio.RouteTrigger
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.Settings
import com.jarvis.assistant.agent.AgentHost
import com.jarvis.assistant.agent.PhoneAgent
import com.jarvis.assistant.agent.Llm
import com.jarvis.assistant.agent.AgentResult
import com.jarvis.assistant.agent.ChatSession
import com.jarvis.assistant.ai.AiMode
import com.jarvis.assistant.ai.AiRouter
import com.jarvis.assistant.ai.Endpoints
import com.jarvis.assistant.ai.LocalServerManager
import com.jarvis.assistant.ai.TaskKind
import com.jarvis.assistant.memory.MemoryStore
import com.jarvis.assistant.persona.DailyGreeting
import com.jarvis.assistant.persona.GreetingStore
import com.jarvis.assistant.persona.Persona
import com.jarvis.assistant.runtime.EmbeddedRuntime
import com.jarvis.assistant.tasks.Interrupt
import com.jarvis.assistant.tasks.TaskHistory
import com.jarvis.assistant.tasks.TaskBoard
import com.jarvis.assistant.tasks.TaskControl
import com.jarvis.assistant.tasks.TaskType
import com.jarvis.assistant.router.AppMatcher
import com.jarvis.assistant.router.FastActionExecutor
import com.jarvis.assistant.router.FastCommand
import com.jarvis.assistant.router.FastCommandRouter
import com.jarvis.assistant.router.MemoryIntent
import com.jarvis.assistant.router.MemoryIntentParser
import com.jarvis.assistant.router.PhraseCorrector
import com.jarvis.assistant.router.QueryKind
import com.jarvis.assistant.util.LatencyTrace
import com.jarvis.assistant.voice.AudioOut
import com.jarvis.assistant.voice.ReplyStreamer
import com.jarvis.assistant.voice.Kokoro
import com.jarvis.assistant.ui.MainActivity
import com.jarvis.assistant.voice.CommandListener
import com.jarvis.assistant.voice.Speaker
import com.jarvis.assistant.voice.VoiceInput
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.TimeUnit

/** Foreground voice owner. System assistant sessions and app controls send events here. */
class JarvisService : Service() {

    private enum class State { IDLE, LISTENING_COMMAND, WORKING }

    private val main = Handler(Looper.getMainLooper())
    // A pool, not a single thread: a new command must never wait behind the task it is replacing, and a background
    // answer must keep running while the next command starts.
    private val worker = Executors.newCachedThreadPool { r -> Thread(r, "jarvis-task").apply { isDaemon = true } }
    private lateinit var settings: Settings
    private lateinit var audioRoutes: AudioRouteController
    private lateinit var speaker: Speaker
    private lateinit var voice: VoiceInput
    /** Fallback when the offline speech model isn't downloaded. */
    private lateinit var google: CommandListener

    // The assistant's brain. Created on first use so starting the service stays cheap.
    private val memoryStore by lazy { MemoryStore.get(this) }
    private val localServer by lazy { LocalServerManager(this, settings) }
    private val runtimeOwner = lazy { EmbeddedRuntime.get(this) }
    private val runtime by runtimeOwner
    private val aiRouter by lazy { AiRouter(this, settings, localServer, { runCatching { memoryStore }.getOrNull() }, runtime) }

    // ---- Tasks: what is running, what was cancelled, what must not run twice ----
    private val board = TaskBoard(now = { SystemClock.elapsedRealtime() })
    private val history by lazy { TaskHistory(this) }
    private val idCounter = AtomicInteger(0)

    /** Only one task at a time may drive the screen. A task that replaces another waits briefly for it to let go. */
    private val uiLock = ReentrantLock(true)

    /** The user spoke to Jarvis while a task was running. The task goes on unless a new command is actually heard. */
    @Volatile private var interruptedWork = false

    /** A finished task's reply that arrived while the user was talking. Spoken afterwards, or dropped if they gave a new command. */
    private var heldDelivery: (() -> Unit)? = null

    /** Something honest to say with the next reply ("part of the earlier task was already done"). */
    private var pendingNote: String? = null

    /** The streamed spoken reply of the chat task [first], if one is in progress. Touched only on the main thread, except when set. */
    @Volatile private var streamerFor: Pair<Int, ReplyStreamer>? = null

    /** What the user taught about how they sound ("you too" means "youtube"). Loaded once, off the main thread. */
    @Volatile private var phraseMap: Map<String, String> = emptyMap()
    @Volatile private var phrasesLoaded = false

    /** Replies of background tasks that finished while Jarvis was busy. */
    private val backgroundDone = ArrayDeque<String>()

    private val greeting by lazy {
        DailyGreeting(object : GreetingStore {
            override var lastDate: String?
                get() = settings.lastGreetingDate
                set(v) { settings.lastGreetingDate = v }
        })
    }
    private val acks = Persona.AckPicker()
    private var pendingAck: Runnable? = null
    private val fastActions by lazy { FastActionExecutor(this, settings) { runCatching { memoryStore }.getOrNull() } }
    private val trace = LatencyTrace({ SystemClock.elapsedRealtime() }) { Log.i("JarvisLatency", it) }

    /** An app name that could not be opened, and when. If the user repeats the command with the right name soon after, the old phrase is learned. */
    @Volatile private var failedApp: Pair<String, Long>? = null
    @Volatile private var state = State.IDLE
    private var startFailed = false
    /** Set in onDestroy; stops late callbacks (speech finishing, models loading) from listening again. */
    @Volatile private var destroyed = false
    private var standby = false
    private var invocation = 0
    private var captureRequest = 0
    private val inputRoute by lazy { TurnInputRoute(audioRoutes, voice::stop) }
    private var headsetTurn = false
    private val pendingUnlock = PendingUnlock(SystemClock::elapsedRealtime)
    private var pendingAnswer: (() -> Unit)? = null
    private var micStop: Runnable? = null
    private var captureState = VoiceState.DISABLED
    private var audioModeListener: AudioManager.OnModeChangedListener? = null
    private var audioRecordingCallback: AudioManager.AudioRecordingCallback? = null
    private var screenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    pendingUnlock.clear()
                    if (state != State.IDLE || captureState == VoiceState.SPEAKING) {
                        cancelVoiceTurn("Screen locked. Repeat the request if needed.")
                    }
                }
                Intent.ACTION_USER_PRESENT -> resumeAfterUnlock()
                AudioManager.ACTION_MICROPHONE_MUTE_CHANGED -> onAudioPolicyChanged()
            }
        }
    }

    private enum class VoiceState {
        DISABLED, WAKE_LISTENING, COMMAND_LISTENING, PROCESSING, SPEAKING, INTERRUPTED, ERROR_RECOVERY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Promote before opening audio or loading any native models.
        settings = Settings(this)
        audioRoutes = audioRouteControllerFactory(applicationContext)
        audioRoutes.updateSettings(settings.bluetoothMicEnabled, settings.headsetButtonFallback)
        AudioOut.routeController = audioRoutes
        speaker = Speaker(this)
        voice = VoiceInput(this, audioRoutes) { onWakeWord() }
        voice.customWakePhrase = settings.customWakePhrase
        voice.earlyAccept = { text -> FastCommandRouter.earlyAcceptDelayMs(text) }
        voice.onMicProblem = { problem, _ -> onMicProblem(problem) }
        speaker.onFocusLost = { cancelVoiceTurn("Audio interrupted") }
        google = CommandListener(this)
        createChannel()
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification("Starting…"),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            )
        } catch (e: Exception) {
            // Android refuses microphone services without the permission, or when started from the background.
            Log.w(TAG, "could not start in the foreground", e)
            failStart("Android refused microphone access. Open Ash and try again.")
            return
        }
        if (AshRecognitionService.ownsCapture) {
            failStart("Microphone in use by a speech recognition request.")
            return
        }
        instance = this
        running = true
        audioRoutes.start(object : AudioRouteListener {
            override fun onRouteChanged(reason: String) {
                main.post {
                    if (!destroyed && standby && state == State.IDLE && captureState == VoiceState.WAKE_LISTENING) {
                        voice.stop { resumeListening() }
                    }
                }
            }

            override fun onRouteTrigger(trigger: RouteTrigger) {
                main.post {
                    if (!destroyed && state == State.IDLE && pendingAnswer == null) {
                        onWakeWord(fromKeyword = false, preferHeadset = true)
                    }
                }
            }

            override fun onActiveInputLost(reason: RouteDisconnectReason) {
                main.post {
                    if (!destroyed && (state == State.LISTENING_COMMAND || pendingAnswer != null)) {
                        val message = when (reason) {
                            RouteDisconnectReason.BLUETOOTH_DISCONNECTED -> "Bluetooth headset disconnected."
                            RouteDisconnectReason.BLUETOOTH_DISABLED -> "Bluetooth turned off."
                            RouteDisconnectReason.SCO_DROPPED -> "Bluetooth microphone closed."
                            RouteDisconnectReason.WIRED_UNPLUGGED -> "Headset unplugged."
                            else -> "Microphone route interrupted."
                        }
                        cancelVoiceTurn(message)
                    }
                }
            }

            override fun onPrivateOutputLost(reason: RouteDisconnectReason) {
                main.post {
                    if (!destroyed && captureState == VoiceState.SPEAKING) {
                        cancelVoiceTurn("Headset disconnected. Reply paused.")
                    }
                }
            }
        })
        val systemFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(AudioManager.ACTION_MICROPHONE_MUTE_CHANGED)
        }
        ContextCompat.registerReceiver(this, screenReceiver, systemFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiverRegistered = true
        val am = getSystemService(AudioManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val listener = AudioManager.OnModeChangedListener { mode ->
                if (mode == AudioManager.MODE_NORMAL) onAudioPolicyChanged()
            }
            runCatching {
                am?.addOnModeChangedListener(mainExecutor, listener)
                audioModeListener = listener
            }
        }
        val recCb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                if (!destroyed && standby && state == State.IDLE && captureState == VoiceState.INTERRUPTED && configs.isEmpty()) {
                    main.postDelayed({ onAudioPolicyChanged() }, 400L)
                }
            }
        }
        runCatching {
            am?.registerAudioRecordingCallback(recCb, main)
            audioRecordingCallback = recCb
        }
        update("Starting…")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (startFailed) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_ARM -> {
                if (!notificationsAvailable(this)) {
                    failStart("Enable Ash notifications before turning on screen-off listening.")
                } else {
                    standby = true
                    settings.switchedOn = true
                    settings.wakeWordEnabled = true
                    resumeListening()
                }
            }
            ACTION_STOP_TASK -> cancelVoiceTurn("Cancelled")
            ACTION_SHUTDOWN -> {
                settings.switchedOn = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RUN -> intent.getStringExtra(EXTRA_TASK)?.let { runTask(it, fromVoice = false) }
                ?: resumeListening()
            ACTION_WAKE_SETTINGS -> {
                voice.customWakePhrase = settings.customWakePhrase
                voice.wakeEnabled = standby && settings.wakeWordEnabled
                audioRoutes.updateSettings(settings.bluetoothMicEnabled, settings.headsetButtonFallback)
                if (!settings.wakeWordEnabled) {
                    standby = false
                    settings.switchedOn = false
                }
                resumeListening()
            }
            ACTION_LISTEN -> {
                val token = intent.getIntExtra(EXTRA_INVOCATION, 0)
                if (token != 0) {
                    if (token != pendingInvocation) {
                        resumeListening()
                        return START_NOT_STICKY
                    }
                    pendingInvocation = 0
                }
                if (state == State.IDLE && pendingAnswer == null) {
                    invocation = token
                    val request = ++captureRequest
                    voice.load {
                        if (!destroyed && request == captureRequest && state == State.IDLE) {
                            onWakeWord(fromKeyword = false)
                        }
                    }
                }
            }
            else -> stopSelf() // never restore capture or a command from a null/redelivered launch
        }
        return START_NOT_STICKY
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Android is short of memory: give back the voice model (about 200 MB). It reloads on the next reply.
        if (level >= TRIM_RUNNING_CRITICAL) {
            Kokoro.unload()
            // The on-device model needs about 1 GB; it starts again by itself when it is next needed.
            runCatching { runtime.stop("low memory") }
        }
    }

    private fun failStart(message: String) {
        startFailed = true
        status = message
        statusListener?.invoke(message)
        stopSelf()
    }

    private fun onAudioPolicyChanged() {
        if (destroyed || !standby || state != State.IDLE || captureState != VoiceState.INTERRUPTED) return
        val am = getSystemService(AudioManager::class.java) ?: return
        if (am.mode == AudioManager.MODE_NORMAL && !am.isMicrophoneMute) {
            micStop?.let(main::removeCallbacks)
            micStop = null
            resumeListening()
        }
    }

    private fun resumeAfterUnlock() {
        if (destroyed || state != State.IDLE || deviceLocked()) return
        val pending = pendingUnlock.take(unlocked = true) ?: return
        executeFastCommand(pending, task = pending.toString(), fromVoice = false)
    }

    private fun onMicProblem(problem: String) {
        if (destroyed) return
        when (problem) {
            "ok" -> {
                micStop?.let(main::removeCallbacks)
                micStop = null
                if (standby && state == State.IDLE && captureState != VoiceState.SPEAKING) {
                    captureState = VoiceState.WAKE_LISTENING
                    update("Listening for ${settings.wakePromptPhrase}")
                }
            }
            "interrupted", "silent" -> {
                captureState = VoiceState.INTERRUPTED
                cancelVoiceTurn("Microphone paused by Android", resume = false)
                micStop?.let(main::removeCallbacks)
                micStop = if (!standby) {
                    Runnable {
                        if (!destroyed && captureState == VoiceState.INTERRUPTED) stopSelf()
                    }.also { main.postDelayed(it, 30_000) }
                } else if (problem == "silent") {
                    // Single bounded recovery attempt for transient digital silence in armed standby;
                    // call/recording interruptions recover event-driven via OnModeChangedListener / AudioRecordingCallback.
                    Runnable { onAudioPolicyChanged() }.also { main.postDelayed(it, 3_000) }
                } else {
                    null
                }
            }
            "retrying" -> {
                captureState = VoiceState.ERROR_RECOVERY
                cancelVoiceTurn("Microphone disconnected. Retrying…", resume = false)
            }
            else -> {
                captureState = VoiceState.ERROR_RECOVERY
                cancelVoiceTurn("Microphone unavailable. Open Ash to retry.", resume = false)
                stopSelf()
            }
        }
    }

    private fun cancelVoiceTurn(message: String, resume: Boolean = true) {
        if (destroyed) return
        invalidateInput()
        cancelForeground()
        board.running().filter { !it.background }.forEach { board.cancel(it.id) }
        interruptedWork = false
        heldDelivery = null
        invocation = 0
        headsetTurn = false
        interruptSpeech()
        state = State.IDLE
        island { it.hide() }
        update(message)
        if (::audioRoutes.isInitialized && audioRoutes.ownsCommunicationRoute) {
            // Stop the SCO/BLE AudioRecord before releasing the communication route so VoiceInput
            // does not see a spurious route-drop interruption.
            voice.stop {
                audioRoutes.releaseSession()
                if (!destroyed && resume) resumeListening()
            }
            return
        }
        if (::audioRoutes.isInitialized) audioRoutes.releaseSession()
        if (resume) resumeListening()
    }

    private fun invalidateInput() {
        captureRequest++
        pendingAnswer?.invoke()
        pendingAnswer = null
        google.cancel()
        voice.cancelCommand()
    }

    override fun onDestroy() {
        destroyed = true
        if (instance === this) {
            instance = null
            running = false
            phase = Phase.OFF
        }
        if (!startFailed && !status.startsWith("Microphone")) status = "Off"
        statusListener?.invoke(status)
        main.removeCallbacksAndMessages(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioModeListener?.let { listener ->
                runCatching { getSystemService(AudioManager::class.java)?.removeOnModeChangedListener(listener) }
            }
            audioModeListener = null
        }
        audioRecordingCallback?.let { cb ->
            runCatching { getSystemService(AudioManager::class.java)?.unregisterAudioRecordingCallback(cb) }
        }
        audioRecordingCallback = null
        pendingUnlock.clear()
        if (screenReceiverRegistered) unregisterReceiver(screenReceiver)
        if (runtimeOwner.isInitialized()) runtime.stop("service destroyed")
        taskId++
        activeSession?.cancel()
        activeSession = null
        island { it.hide() }
        invalidateInput()
        voice.release()
        speaker.shutdown()
        if (::audioRoutes.isInitialized) {
            runCatching { audioRoutes.releaseSession() }
            runCatching { audioRoutes.stop() }
        }
        AudioOut.routeController = BuiltInPhoneAudioRouteController
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (board.recent(1).isNotEmpty()) history.interruptAll()
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---- Voice flow ----
    //
    // A conversation: "Hey Jarvis" (or a follow-up straight after a reply) → listen → work → reply.
    // The microphone stays open while Jarvis works and while it replies, so the user can interrupt
    // at any point: by saying "Hey Jarvis" or, during a reply, simply by talking over it.

    /** What to do when the current speech ends naturally (also run if it's stopped from the island). */
    private var afterSpeech: (() -> Unit)? = null
    private var speechId = 0
    /** Increases with every task; a result from a task that was interrupted is never spoken. */
    @Volatile private var taskId = 0
    @Volatile private var activeSession: ChatSession? = null
    /** Recent requests and replies, so follow-ups like "and tell her I'll be late" make sense. */
    private val conversation = ArrayDeque<Pair<String, String>>()
    private var lastExchangeAt = 0L

    /**
     * Speaks [text]. If [interruptible] (and the user allows it), the microphone stays open and
     * talking over Jarvis stops it and becomes the next command; otherwise the microphone is muted
     * so Jarvis never hears itself.
     */
    private fun say(
        text: String, interruptible: Boolean = false, onSkipped: (() -> Unit)? = null, then: () -> Unit = {},
    ) {
        val id = ++speechId
        captureState = VoiceState.SPEAKING
        fun prepareAndSpeak() {
            if (destroyed || id != speechId) return
            audioRoutes.prepareOutputForSpeech { policy ->
                if (destroyed || id != speechId) return@prepareOutputForSpeech
                AudioOut.applyOutputPolicy(policy)
                val btOutput = policy.expectedKind == OutputRouteKind.BLUETOOTH_A2DP ||
                    policy.expectedKind == OutputRouteKind.BLE_AUDIO
                val bargeIn = interruptible && settings.interruptByTalking && !btOutput &&
                    !audioRoutes.ownsCommunicationRoute && voice.canTranscribe && voice.isListening
                if (bargeIn) voice.listenWhileSpeaking(text) { stop -> onBargeIn(stop) } else voice.muted = true
                afterSpeech = {
                    voice.stopListeningWhileSpeaking()
                    // After muted speech, let the speaker's echo die away before listening again.
                    main.postDelayed({
                        if (!destroyed && id == speechId) {
                            voice.muted = false
                            then()
                        }
                    }, if (bargeIn) 0L else 150L)
                }
                island { it.speaking(text) { speaker.level() } }
                speaker.speak(text, onSkipped = {
                    if (id == speechId && !destroyed) {
                        if (onSkipped == null) finishSpeech() else {
                            interruptSpeech()
                            onSkipped()
                        }
                    }
                }) { if (id == speechId) finishSpeech() }
            }
        }
        if (audioRoutes.ownsCommunicationRoute && voice.isListening) {
            // Close the active SCO/BLE AudioRecord before prepareOutputForSpeech tears down SCO.
            voice.stop { prepareAndSpeak() }
        } else {
            prepareAndSpeak()
        }
    }

    private fun finishSpeech() {
        val then = afterSpeech ?: return
        afterSpeech = null
        island { if (it.isShowing) it.speakingDone() }
        then()
    }


    /** Stops talking now and forgets what was going to happen next (the user has taken over). */
    private fun interruptSpeech() {
        streamerFor?.second?.cancel()
        speechId++
        afterSpeech = null
        voice.stopListeningWhileSpeaking()
        voice.muted = false
        speaker.stop()
        island { if (it.isShowing) it.speakingDone() }
    }

    private fun onWakeWord(fromKeyword: Boolean = true, preferHeadset: Boolean = !fromKeyword) {
        if (destroyed || (fromKeyword && !standby) || pendingAnswer != null) return
        if (state != State.LISTENING_COMMAND) trace.begin("wake")
        when (state) {
            State.LISTENING_COMMAND -> return
            State.WORKING -> {
                // "Hey Ash" during a task: listen for the next command, but let the task go on. It is replaced only
                // when a new command is actually heard, so a stray detection no longer kills a slow request.
                interruptedWork = board.foreground() != null
                interruptSpeech()
                listen(promptIfQuiet = fromKeyword, preferHeadset = preferHeadset)
            }
            State.IDLE -> {
                interruptSpeech()
                listen(promptIfQuiet = fromKeyword, preferHeadset = preferHeadset)
            }
        }
    }

    /** The user talked over Jarvis: what they're saying is the next command, or they said "stop". */
    private fun onBargeIn(stop: Boolean) {
        if (destroyed) return
        interruptSpeech()
        if (stop) {
            val said = if (state == State.WORKING) cancelForeground() else null
            interruptedWork = false
            heldDelivery = null
            state = State.IDLE
            voice.cancelCommand()
            island { it.hide() }
            update(said ?: "Okay")
            resumeListening()
        } else {
            // Talking over Jarvis: stop the speech and listen. A running task is only replaced if a command follows.
            interruptedWork = state == State.WORKING && board.foreground() != null
            listen(promptIfQuiet = false, interrupted = true)
        }
    }

    /**
     * Transcribes the next thing the user says. After "Hey Jarvis" ([promptIfQuiet]) Jarvis says
     * "Yes?" if they pause; for a [followUp] it waits quietly for a few seconds and gives up silently.
     */
    private fun listen(
        promptIfQuiet: Boolean,
        followUp: Boolean = false,
        interrupted: Boolean = false,
        preferHeadset: Boolean = false,
    ) {
        state = State.LISTENING_COMMAND
        captureState = VoiceState.COMMAND_LISTENING
        val request = ++captureRequest
        update("Listening…")
        island { it.listening(if (useOfflineAsr) ({ voice.partialText }) else null, { voice.level }) }
        val heard: (String?) -> Unit = { text ->
            if (!destroyed && request == captureRequest && state == State.LISTENING_COMMAND) {
                captureRequest++
                onHeard(text, followUp, interrupted)
            }
        }
        fun startCapture() {
            if (destroyed || request != captureRequest || state != State.LISTENING_COMMAND) return
            if (useOfflineAsr) {
                voice.listenForCommand(if (followUp) FOLLOW_UP_MS else 7000, heard)
            } else {
                voice.stop {
                    if (destroyed || request != captureRequest) return@stop
                    if (promptIfQuiet) update("Speak again when ready. The system recognizer starts a new recording.")
                    google.listen(googleLanguage(), offline = settings.aiMode == AiMode.OFFLINE || googleLanguage() == null,
                        onReady = { if (request == captureRequest) update("Speak now") }, onResult = heard)
                }
            }
        }
        val tryHeadset = preferHeadset && !interrupted
        inputRoute.prepare(
            preferHeadset = tryHeadset,
            requireHeadset = false,
            bluetoothEnabled = settings.bluetoothMicEnabled,
            isCurrent = { !destroyed && request == captureRequest && state == State.LISTENING_COMMAND },
        ) { routeResult ->
            headsetTurn = routeResult == TurnInputRoute.Result.HEADSET
            if (routeResult == TurnInputRoute.Result.PHONE_FALLBACK) update("Listening (phone mic)…")
            startCapture()
        }
    }

    /** Offline English recognition, unless the user chose Hindi/Hinglish (then Google's recognizer is used). */
    private val useOfflineAsr get() = voice.canTranscribe && settings.speechLanguage == "en"

    private fun googleLanguage(): String? = if (settings.speechLanguage == "hi") "hi-IN" else null

    private fun onHeard(text: String?, followUp: Boolean = false, interrupted: Boolean = false) {
        if (state != State.LISTENING_COMMAND) return
        when {
            // A one-word interruption is almost always "stop", "wait" or "no", often half drowned out
            // by Jarvis's voice. Don't act on it; just listen for what comes next.
            interrupted && text != null && VoiceInput.words(text).size <= 1 && !VoiceInput.isStopCommand(text) ->
                listen(promptIfQuiet = false, followUp = true)
            // Spoken to while a task runs, but nothing was said: carry on with the task (and its held reply, if any).
            text.isNullOrBlank() && interruptedWork -> resumeWork()
            text.isNullOrBlank() && followUp -> {
                // Nothing more was said after a reply: go back to waiting, quietly.
                state = State.IDLE
                island { it.hide() }
                resumeListening()
            }
            text.isNullOrBlank() -> {
                state = State.IDLE
                update("Didn't catch that")
                say("Sorry, I didn't catch that.", interruptible = true) { island { it.hide() }; resumeListening() }
            }
            VoiceInput.isStopCommand(text) && FastCommandRouter.parse(text) == null -> {
                val said = cancelForeground()
                interruptedWork = false
                heldDelivery = null
                state = State.IDLE
                island { it.hide() }
                update(said ?: "Okay")
                if (said != null && said != "Cancelled.") say(said, interruptible = true) { resumeListening() } else resumeListening()
            }
            else -> runTask(text)
        }
    }


    /** Route clear phone actions to the phone agent; ordinary conversation goes straight to the LLM. */
    private fun isPhoneAction(task: String): Boolean {
        val t = task.lowercase()
        val patterns = listOf(
            Regex("""\b(open|launch|close|exit|switch to|go to|tap|click|press|touch|select|choose|toggle|scroll|swipe|type|enter|send|text|message|call|dial|hang up|answer|reject|play|pause|skip|next|previous|turn on|turn off|enable|disable|increase|decrease|raise|lower|volume|brightness|screenshot|lock|unlock|navigate|share|copy|paste|set alarm|set a reminder|set reminder|set timer|change wallpaper|read screen|what(?:'s| is) on (?:my |the )?screen)\b"""),
            Regex("""(kholo|khol do|band karo|bhejo|bhej do|call karo|phone karo|lagao|laga do|bajao|chalao|chala do|on karo|off karo|badha do|kam karo|ghata do|screenshot lo|photo lo|record karo|mute karo|unmute karo|silent kar do|alarm laga|reminder laga|dabao|click karo|tap karo)""")
        )
        return patterns.any { it.containsMatchIn(t) }
    }

    /** Lightweight conversational path: no accessibility, screen observation or phone tools. */
    private fun runChatTask(
        task: String,
        earlier: List<Pair<String, String>>,
        id: Int
    ): AgentResult {
        // Which models, in which order, depends on the mode (auto / online / offline) and on what is reachable right now.
        val models = aiRouter.plan(task, TaskKind.CHAT)
        if (models.isEmpty()) {
            return AgentResult(
                false,
                if (settings.modelsInOrder().isEmpty()) "No AI model is set up yet."
                else "None of your models can be used in ${settings.aiMode.name.lowercase()} mode.",
            )
        }

        var lastError = ""
        for ((index, profile) in models.withIndex()) {
            if (isStale(id)) {
                return AgentResult(false, "Cancelled.")
            }

            val config = profile.toConfig()
            if (config.preset.needsKey && config.apiKey.isBlank()) {
                lastError = "${profile.name} has no API key"
                continue
            }

            // For the on-phone model this checks that the server answers and starts it through Termux if it does not.
            val usable = aiRouter.ready(profile, hasAlternative = index < models.lastIndex) { text ->
                main.post { if (!isStale(id) && !destroyed) update(text) }
            }
            if (!usable) {
                lastError = if (Endpoints.isLoopback(profile.baseUrl)) aiRouter.localFailureHint() else "${profile.name} isn't reachable"
                continue
            }

            try {
                main.post {
                    if (!isStale(id) && !destroyed) update("Thinking with ${profile.name}…")
                }

                // Notes the user asked Jarvis to keep go in the message, not the system prompt, so the system prompt stays cacheable.
                val memory = aiRouter.memoryContext(task, profile)
                val message = if (memory.isEmpty()) task else "$memory\n\n$task"

                val session = Llm.newSession(config, CHAT_PROMPT, emptyList())

                session.seedHistory(earlier)
                activeSession = session
                if (isStale(id)) session.cancel()
                // Speak the answer sentence by sentence while it is still being written.
                val streamer = if (settings.streamReplies) {
                    ReplyStreamer({ run -> main.post { run() } }, prepare = { speaker.prepare(it) }) { text, done ->
                        if (destroyed || isStale(id) || deviceLocked()) {
                            done()
                            return@ReplyStreamer
                        }
                        cancelAck() // the answer has started: no acknowledgement over it
                        trace.mark("firstSpeech")
                        say(text, interruptible = true, then = done)
                    }
                } else null
                streamerFor = streamer?.let { id to it }
                history.step(id, "Requested an answer from ${profile.name}.")
                val turn = try {
                    session.send(message, emptyList()) { delta -> streamer?.onDelta(delta) }
                } finally {
                    if (activeSession === session) activeSession = null
                }
                if (turn.toolCalls.isNotEmpty()) {
                    lastError = "${profile.name} unexpectedly returned a tool call"
                    continue
                }

                val reply = turn.text.trim()
                if (reply.isNotBlank()) {
                    history.step(id, "Received the answer.")
                    aiRouter.report(profile, true)
                    streamer?.finish()
                    return AgentResult(true, reply)
                }
                dropStreamer()

                lastError = turn.stopNote ?: "${profile.name} returned an empty reply"
            } catch (e: Throwable) {
                history.step(id, "The model request failed.")
                if (isStale(id)) {
                    return AgentResult(false, "Okay, I stopped.")
                }
                dropStreamer()
                aiRouter.report(profile, false)
                Log.w(TAG, "chat request failed (${e::class.java.simpleName})")
                lastError = "Check the provider's endpoint, API key and network connection."
            }
        }

        return AgentResult(
            false, "I couldn't reach any of your AI models. $lastError"
        )
    }

    private fun deviceLocked(): Boolean =
        getSystemService(KeyguardManager::class.java).let { it.isDeviceLocked || it.isKeyguardLocked }
    private fun isStale(id: Int) = board.isStale(id, taskId)

    /** Stops a half-spoken streamed answer (the task was replaced, cancelled, or failed part way). Any thread. */
    private fun dropStreamer() {
        val s = streamerFor?.second ?: return
        streamerFor = null
        main.post { s.cancel() }
    }

    /**
     * Stops the task the user is waiting on. Returns what can honestly be said: "Cancelled.", or that part of it was
     * already done and cannot be undone. Instant commands and background answers are not touched.
     */
    private fun cancelForeground(): String? {
        pendingAnswer?.invoke()
        val outcome = board.foreground()?.let { board.cancel(it.id) }
        outcome?.let { history.finish(it.task.id, "Cancelled") }
        taskId++ // anything still working for the old foreground id is now stale
        activeSession?.cancel()
        activeSession = null
        cancelAck()
        return outcome?.message()
    }

    private fun typeOf(c: FastCommand) = when (c) {
        is FastCommand.Volume, is FastCommand.Flashlight, is FastCommand.Media, is FastCommand.Ask,
        is FastCommand.SetMode, is FastCommand.SetLanguage, is FastCommand.SetAlarm, is FastCommand.SetTimer,
        FastCommand.Screenshot, FastCommand.LockScreen, is FastCommand.RuntimeControl,
        -> TaskType.INSTANT
        else -> TaskType.PHONE
    }

    /** Runs [block] holding the screen lock (when [needed]); gives [busy] if the previous screen task does not let go in time. */
    private fun <T> withUi(needed: Boolean, busy: T, block: () -> T): T {
        if (!needed) return block()
        var got = false
        try {
            got = uiLock.tryLock(UI_LOCK_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (!got) return busy
        try {
            return block()
        } finally {
            uiLock.unlock()
        }
    }

    /** "Cancel", or "stop that, open YouTube". */
    private fun handleCancel(then: String?) {
        val said = cancelForeground()
        interruptedWork = false
        heldDelivery = null
        if (then != null) {
            if (said != null && said != "Cancelled.") pendingNote = said
            runTask(then)
            return
        }
        state = State.IDLE
        island { it.hide() }
        update(said ?: "Nothing to cancel")
        say(said ?: "There's nothing running.", interruptible = true) { resumeListening() }
    }

    /** "Put that in the background (and open WhatsApp)". Only a spoken-answer task can; a screen task needs the screen. */
    private fun handleBackground(then: String?) {
        val fg = board.foreground()
        val moved = fg != null && board.moveToBackground(fg.id)
        if (moved) {
            history.step(fg.id, "Moved to the background.")
            cancelAck()
        }
        interruptedWork = false
        heldDelivery = null
        when {
            moved && then != null -> runTask(then)
            !moved && fg != null && then != null -> handleCancel(then) // a screen task cannot wait in the background: the new command replaces it
            else -> {
                val msg = when {
                    fg == null -> "Nothing is running."
                    moved -> "Okay, I'll keep working on it in the background."
                    else -> "That task is using the screen, so it can't run in the background. Say cancel to stop it."
                }
                if (moved || fg == null) { state = State.IDLE; island { it.hide() } }
                else { state = State.WORKING; island { it.working(fg?.text ?: "") } }
                say(msg, interruptible = true) { if (state == State.IDLE) resumeListening() }
            }
        }
    }

    /** Back to the running task after the user spoke but said nothing. A reply that arrived meanwhile is spoken now. */
    private fun resumeWork() {
        interruptedWork = false
        val held = heldDelivery
        heldDelivery = null
        val fg = board.foreground()
        when {
            held != null -> held()
            fg != null -> {
                state = State.WORKING
                island { it.working(fg.text) }
                update("Working on: ${fg.text}")
            }
            else -> {
                state = State.IDLE
                island { it.hide() }
                resumeListening()
            }
        }
    }

    private fun runTask(spoken: String, fromVoice: Boolean = true) {
        if (destroyed) return
        invalidateInput()
        if (!phrasesLoaded) {
            phrasesLoaded = true
            worker.execute { runCatching { phraseMap = memoryStore.phraseCorrections() } }
        }
        val task = PhraseCorrector.apply(spoken, phraseMap)
        val memoryIntent = MemoryIntentParser.parse(task)
        val fast = if (memoryIntent == null) FastCommandRouter.parse(task) else null
        if (deviceLocked() && (fast == null || !LockedCommands.allows(fast))) {
            cancelForeground()
            pendingUnlock.offer(fast)
            state = State.IDLE
            val msg = "I can do that, but please unlock your phone first."
            update(msg)
            say(msg) { resumeListening() }
            return
        }
        // Phrases about the tasks themselves are never tasks: "cancel", "stop that, open YouTube", "what are you doing".
        when (val control = if (fast == null) TaskControl.parse(task) else null) {
            is TaskControl.Cancel -> { handleCancel(control.then); return }
            is TaskControl.Background -> { handleBackground(control.then); return }
            TaskControl.Status -> {
                val running = board.foreground() != null
                state = if (running) State.WORKING else State.IDLE
                interruptedWork = false
                say(board.describeRunning(), interruptible = true) { if (!running) { island { it.hide() }; resumeListening() } }
                return
            }
            null -> Unit
        }
        // The same sentence heard twice (a double wake-up, an echo): do it once.
        board.duplicateOf(task, DUPLICATE_WINDOW_MS)?.let { dup ->
            Log.i(TAG, "ignoring a repeat of task ${dup.id}")
            resumeWork()
            return
        }

        trace.mark("heard")
        val type = when {
            memoryIntent != null -> TaskType.INSTANT
            fast != null -> typeOf(fast)
            isPhoneAction(task) -> TaskType.PHONE
            else -> TaskType.CHAT
        }

        // A new command replaces the task in front, except instant ones (volume, flashlight, time) that run alongside it.
        val decision = Interrupt.forNewTask(type, board.foreground())
        decision.cancel?.let { replaced ->
            board.cancel(replaced.id)
            history.finish(replaced.id, "Cancelled")
            activeSession?.cancel()
            activeSession = null
            cancelAck()
            if (decision.note != null) pendingNote = decision.note
        }
        val id = idCounter.incrementAndGet()
        board.start(id, task, type)
        val keep = memoryIntent == null && fast !is FastCommand.TypeText && fast !is FastCommand.Dial && fast !is FastCommand.CallContact
        if (keep) history.start(id, task, forContext = type != TaskType.INSTANT)
        if (type != TaskType.INSTANT) {
            taskId = id
            activeSession = null
        }
        interruptedWork = false
        heldDelivery = null
        state = State.WORKING
        captureState = VoiceState.PROCESSING
        voice.wakeEnabled = standby && settings.wakeWordEnabled
        lastTask = task
        island { it.working(board.foreground()?.text ?: task) }
        update("Working on: ${board.foreground()?.text ?: task}")

        // Instant paths first: what the user is teaching Jarvis, then simple phone commands. No language model involved,
        // so they work offline and finish in milliseconds.
        if (memoryIntent != null || fast != null) {
            trace.mark("routed")
            worker.execute {
                val busy = FastActionExecutor.Result(true, false, "I'm still finishing the previous task. Please try again in a moment.")
                val outcome = try {
                    if (memoryIntent != null) {
                        fastActions.handleMemory(memoryIntent).also {
                            if (memoryIntent is MemoryIntent.TeachPhrase) runCatching { phraseMap = memoryStore.phraseCorrections() }
                        }
                    } else {
                        withUi(type == TaskType.PHONE, busy) {
                            val command = checkNotNull(fast)
                            fastActions.execute(
                                command, recordStep = { if (keep) history.step(id, it) },
                                asker = { question -> hostFor(id).ask(question) },
                                spokenRequest = if (fromVoice) task else null,
                                canAct = { !destroyed && !isStale(id) && (LockedCommands.allows(command) || !deviceLocked()) },
                            )
                        }
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "instant action failed", e)
                    FastActionExecutor.Result(true, false, "Something went wrong.")
                }
                trace.mark("acted")
                if (outcome.irreversible) board.markIrreversible(id)
                if (!outcome.handled && fast is FastCommand.OpenApp) failedApp = fast.query to SystemClock.elapsedRealtime()
                if (keep) history.step(id, outcome.message)
                val message = learnFromRetry(outcome)
                main.post { deliver(task, id, AgentResult(outcome.success, message), keepInHistory = keep, via = "instant") }
            }
            return
        }
        runModelTask(task, id, type)
    }

    private fun executeFastCommand(command: FastCommand, task: String, fromVoice: Boolean) {
        if (destroyed || state != State.IDLE || (!LockedCommands.allows(command) && deviceLocked())) return
        val type = typeOf(command)
        val id = idCounter.incrementAndGet()
        board.start(id, task, type)
        if (type != TaskType.INSTANT) {
            taskId = id
            activeSession = null
        }
        state = State.WORKING
        captureState = VoiceState.PROCESSING
        update("Working…")
        worker.execute {
            val outcome = try {
                fastActions.execute(
                    command, recordStep = {},
                    asker = { question -> hostFor(id).ask(question) },
                    spokenRequest = if (fromVoice) task else null,
                    canAct = { !destroyed && !isStale(id) && (LockedCommands.allows(command) || !deviceLocked()) },
                )
            } catch (e: Throwable) {
                Log.e(TAG, "pending action failed", e)
                FastActionExecutor.Result(true, false, "Something went wrong.")
            }
            main.post { deliver(task, id, AgentResult(outcome.success, outcome.message), keepInHistory = false, via = "instant") }
        }
    }

    private fun needsUnlock(c: FastCommand): Boolean = !LockedCommands.allows(c)

    /**
     * If the user repeats a failed "open <name>" with the correct app name, the first name was probably a mis-hearing.
     * It is saved so it works next time, but only when the two names plausibly sound alike (so "open maps" after a
     * failed "open whtsup" never teaches that whtsup means Maps).
     */
    private fun learnFromRetry(outcome: FastActionExecutor.Result): String {
        val failed = failedApp
        val entry = outcome.app
        if (failed == null || entry == null || !outcome.success) return outcome.message
        failedApp = null
        if (SystemClock.elapsedRealtime() - failed.second > RETRY_LEARN_MS) return outcome.message
        if (outcome.how !in setOf("exact", "builtin", "learned", "prefix")) return outcome.message
        if (!AppMatcher.similar(failed.first, entry.label)) return outcome.message
        fastActions.learnAlias(failed.first, entry)
        return "${outcome.message} I'll remember that \"${failed.first}\" means ${entry.label}."
    }

    /** The model path: acknowledge, then let the phone agent or the chat model answer on the worker thread. */
    private fun runModelTask(task: String, id: Int, type: TaskType) {
        scheduleAck(id)
        val earlier = recentConversation()
        val phoneAction = type == TaskType.PHONE

        worker.execute {
            val result = try {
                if (phoneAction) {
                    withUi(true, AgentResult(false, "I'm still finishing the previous task. Please try again in a moment.")) {
                        if (deviceLocked() || isStale(id)) AgentResult(false, "Unlock your phone and repeat the request.")
                        else PhoneAgent(settings, hostFor(id), aiRouter).run(task, earlier)
                    }
                } else {
                    runChatTask(task, earlier, id)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "task failed (${e::class.java.simpleName})")
                AgentResult(false, "The request failed. Check your connection and model settings.")
            }
            trace.mark("model")
            main.post { deliver(task, id, result, keepInHistory = true, via = "model") }
        }
    }

    /**
     * No "On it." before every request. If the answer takes more than a moment, one short, varied acknowledgement is
     * spoken; fast answers get none. Instant commands never get one.
     */
    private fun scheduleAck(id: Int) {
        cancelAck()
        val r = Runnable {
            pendingAck = null
            if (!destroyed && !isStale(id) && state == State.WORKING) say(acks.next())
        }
        pendingAck = r
        main.postDelayed(r, ACK_DELAY_MS)
    }

    private fun cancelAck() {
        pendingAck?.let { main.removeCallbacks(it) }
        pendingAck = null
    }

    /** Shows and speaks the result of task [id], unless the user interrupted it meanwhile. */
    private fun deliver(task: String, id: Int, result0: AgentResult, keepInHistory: Boolean, via: String) {
        if (destroyed || isStale(id)) return
        if (deviceLocked() && FastCommandRouter.parse(task)?.let { !needsUnlock(it) } != true) {
            board.finish(id, false, null)
            cancelVoiceTurn("Unlock your phone to use this command.")
            return
        }
        cancelAck()
        val tracked = board.get(id)
        board.finish(id, result0.success, result0.summary)
        if (!result0.success && via == "model") history.step(id, result0.summary)
        history.finish(id, if (result0.success) "Completed" else "Failed")
        when {
            // Finished while the user moved on: say so when Jarvis is free, without taking over the conversation.
            tracked?.background == true -> { announceBackground(tracked.text, result0); return }
            // An instant command that ran next to a longer task: answer it briefly; the longer task is still in front.
            tracked?.type == TaskType.INSTANT && board.foreground() != null -> { say(result0.summary, interruptible = true); return }
            // The user is talking to Jarvis right now: do not talk over them. Spoken afterwards, or dropped if they say something new.
            state == State.LISTENING_COMMAND -> { heldDelivery = { deliverNow(task, id, result0, keepInHistory, via) }; return }
        }
        deliverNow(task, id, result0, keepInHistory, via)
    }

    private fun announceBackground(text: String, result: AgentResult) {
        val line = "Finished: ${result.summary}"
        if (state == State.IDLE) say(line, interruptible = true) { resumeListening() } else backgroundDone.addLast(line)
        Log.i(TAG, "background task finished: ${text.length} chars")
    }

    private fun deliverNow(task: String, id: Int, result0: AgentResult, keepInHistory: Boolean, via: String) {
        if (destroyed || isStale(id)) return
        // The first interaction of the day carries a greeting: "Good morning, sir. Opened YouTube."
        val streamer = streamerFor?.takeIf { it.first == id }?.second
        streamerFor = null
        val streamed = streamer != null && streamer.spoken > 0
        // A streamed answer was already spoken piece by piece, so the greeting and notes are kept for the next reply.
        val prefix = if (streamed) null else greeting.takePrefix()
        val note = if (streamed) null else pendingNote.also { pendingNote = null }
        val result = AgentResult(result0.success, listOfNotNull(prefix, note, result0.summary).joinToString(" "))
        lastResult = result.summary
        if (keepInHistory) remember(task, result.summary)
        if (streamed) {
            // Each sentence is shown as it is spoken; the final text appears when the last one is done.
            streamer!!.whenDone {
                island { it.finish(result.success, result.summary) }
                update(result.summary)
                state = State.IDLE
                afterReply()
            }
        } else {
            island { it.finish(result.success, result.summary) }
            update(result.summary)
            say(result.summary, interruptible = true) {
                state = State.IDLE
                afterReply()
            }
        }
        trace.finish(via)
    }

    /** After a reply: listen for a follow-up without the wake word, or go back to waiting. */
    private fun afterReply() {
        if (destroyed) return
        // Background tasks that finished meanwhile are announced first, one at a time.
        backgroundDone.removeFirstOrNull()?.let { line ->
            say(line, interruptible = true) { afterReply() }
            return
        }
        if (!deviceLocked() && settings.followUp && useOfflineAsr) {
            listen(promptIfQuiet = false, followUp = true, preferHeadset = headsetTurn)
        } else {
            resumeListening()
        }
    }

    private fun remember(task: String, reply: String) {
        conversation.addLast(task to reply)
        while (conversation.size > 3) conversation.removeFirst()
        lastExchangeAt = System.currentTimeMillis()
    }

    /** The last few exchanges, if the conversation is still going (within 10 minutes). */
    private fun recentConversation(): List<Pair<String, String>> {
        if (System.currentTimeMillis() - lastExchangeAt > 10 * 60_000) conversation.clear()
        return conversation.toList()
    }

    /** Go back to the armed local recorder, or finish a one-shot session without keeping an FGS. */
    private fun resumeListening() {
        if (destroyed || state != State.IDLE) return
        invocation = 0
        headsetTurn = false
        if (audioRoutes.ownsCommunicationRoute) {
            // Stop the headset AudioRecord first, then release SCO/BLE before returning to standby.
            voice.stop {
                audioRoutes.releaseSession()
                resumeListening()
            }
            return
        }
        audioRoutes.releaseSession()
        if (!standby || !settings.wakeWordEnabled || !notificationsAvailable(this)) {
            captureState = VoiceState.DISABLED
            voice.wakeEnabled = false
            voice.stop()
            if (board.running().isEmpty()) stopSelf()
            return
        }
        voice.echoCancellation = settings.interruptByTalking
        val request = ++captureRequest
        voice.load { error ->
            if (destroyed || request != captureRequest || state != State.IDLE) return@load
            if (error != null || !voice.hasWakeWord || !voice.canTranscribe) {
                failStart(error ?: "Download the wake and speech models before enabling screen-off listening.")
                return@load
            }
            voice.wakeEnabled = true
            if (voice.isListening) {
                captureState = VoiceState.WAKE_LISTENING
                update("Listening for ${settings.wakePromptPhrase}")
            } else {
                captureState = VoiceState.ERROR_RECOVERY
                update("Opening microphone…")
                voice.start()
            }
        }
    }

    /** Bridges the agent (worker thread) to the voice UI (main thread) for task [id]. */
    private fun hostFor(id: Int) = object : AgentHost {
        override val isCancelled get() = isStale(id)
        override fun recordStep(label: String) = history.step(id, label)
        override fun onIrreversible() {
            board.markIrreversible(id)
        }
        override fun attachSession(session: ChatSession) {
            activeSession = session
            if (isStale(id)) session.cancel()
        }
        override fun status(text: String) {
            main.post {
                if (!destroyed && !isStale(id)) update(text)
            }
        }
        override fun ask(question: String): String? {
            val result = java.util.concurrent.CompletableFuture<String?>()
            main.post {
                if (destroyed || isStale(id) || deviceLocked() || pendingAnswer != null || result.isDone) {
                    result.complete(null)
                    return@post
                }
                val request = ++captureRequest
                fun finishAnswer(text: String?) {
                    if (request == captureRequest) pendingAnswer = null
                    result.complete(text?.takeIf { !destroyed && !isStale(id) && !deviceLocked() && request == captureRequest })
                }
                pendingAnswer = { finishAnswer(null) }
                update("Asking: $question")
                say(question, onSkipped = { finishAnswer(null) }) {
                    val stillValid = { !result.isDone && !destroyed && !isStale(id) && !deviceLocked() && request == captureRequest }
                    if (!stillValid()) {
                        finishAnswer(null)
                        return@say
                    }
                    inputRoute.prepare(
                        preferHeadset = headsetTurn,
                        requireHeadset = false,
                        bluetoothEnabled = settings.bluetoothMicEnabled,
                        isCurrent = stillValid,
                    ) { routeResult ->
                        if (!stillValid()) {
                            finishAnswer(null)
                            return@prepare
                        }
                        if (routeResult == TurnInputRoute.Result.PHONE_FALLBACK) update("Listening (phone mic)…")
                        captureState = VoiceState.COMMAND_LISTENING
                        island { it.listening(if (useOfflineAsr) ({ voice.partialText }) else null, { voice.level }) }
                        if (useOfflineAsr) {
                            voice.listenForCommand(10_000, ::finishAnswer)
                        } else voice.stop {
                            if (stillValid()) {
                                google.listen(googleLanguage(), offline = settings.aiMode == AiMode.OFFLINE || googleLanguage() == null,
                                    onResult = ::finishAnswer)
                            }
                        }
                    }
                }
                main.postDelayed({
                    if (!result.isDone && request == captureRequest) {
                        invalidateInput()
                        interruptSpeech()
                    }
                }, 25_000)
            }
            return try {
                result.get(26, TimeUnit.SECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
                result.complete(null)
                null
            } catch (_: InterruptedException) {
                result.complete(null)
                Thread.currentThread().interrupt()
                null
            }
        }
    }

    // ---- Notification ----

    /** Runs [block] on the task island, if phone control is on and the island is enabled. */
    private fun island(block: (com.jarvis.assistant.ui.TaskIsland) -> Unit) {
        if (!settings.showIsland) return
        JarvisAccessibilityService.instance?.island?.let {
            it.onStop = { start(this, ACTION_STOP_TASK) }
            runCatching { block(it) }
        }
    }

    private fun update(text: String) {
        status = text
        if (state == State.WORKING && !text.startsWith("Working on:")) island { it.status(text) }
        phase = when (state) {
            State.IDLE -> Phase.IDLE
            State.LISTENING_COMMAND -> Phase.LISTENING
            State.WORKING -> Phase.WORKING
        }
        val summary = when (captureState) {
            VoiceState.WAKE_LISTENING -> getString(R.string.ash_notification_wake)
            VoiceState.INTERRUPTED, VoiceState.ERROR_RECOVERY -> getString(R.string.ash_notification_paused)
            else -> getString(R.string.ash_notification_active)
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(summary))
        statusListener?.invoke(text)
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, getString(R.string.ash_app_name), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun action(action: String, code: Int) =
            PendingIntent.getService(this, code, Intent(this, JarvisService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle(getString(R.string.ash_app_name))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.talk), action(ACTION_LISTEN, 1))
            .addAction(0, getString(R.string.notif_stop_task), action(ACTION_STOP_TASK, 2))
            .addAction(0, getString(R.string.turn_off), action(ACTION_SHUTDOWN, 3))
            .build()
    }

    companion object {
        private const val TAG = "JarvisService"
        private const val CHANNEL = "jarvis"
        private const val NOTIFICATION_ID = 1
        const val ACTION_ARM = "com.jarvis.assistant.ARM"
        private const val EXTRA_INVOCATION = "assistant_invocation"
        private var instance: JarvisService? = null // main-thread only
        private val invocationIds = AtomicInteger()
        private var pendingInvocation = 0
        const val ACTION_RUN = "com.jarvis.assistant.RUN"
        const val ACTION_WAKE_SETTINGS = "com.jarvis.assistant.WAKE_SETTINGS"
        const val ACTION_LISTEN = "com.jarvis.assistant.LISTEN"
        const val ACTION_STOP_TASK = "com.jarvis.assistant.STOP_TASK"
        const val ACTION_SHUTDOWN = "com.jarvis.assistant.SHUTDOWN"
        const val EXTRA_TASK = "task"
        /** How long to wait for a follow-up after a reply. */
        private const val FOLLOW_UP_MS = 6000L
        /** The same sentence starting again within this time of the first is one request heard twice (a double wake-up or an echo). */
        private const val DUPLICATE_WINDOW_MS = 1200L
        /** A new screen task waits this long for the one it replaces to let go of the screen. */
        private const val UI_LOCK_WAIT_MS = 5000L
        /** An answer that takes longer than this gets one short acknowledgement. */
        private const val ACK_DELAY_MS = 1500L
        /** A repeated command within this time after a failed one can teach Jarvis the first (mis-heard) name. */
        private const val RETRY_LEARN_MS = 20_000L
        private const val TRIM_RUNNING_CRITICAL = 15 // ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL

        private const val CHAT_PROMPT = "You are Ash, a helpful Android phone assistant. " +
            "Reply naturally and concisely in the user's language, including Hindi or Hinglish. " +
            "You are in conversation mode and cannot operate the phone in this mode. " +
            "Never claim you performed a phone action."

        enum class Phase { OFF, IDLE, LISTENING, WORKING }

        @Volatile var running = false
            private set
        @Volatile var phase = Phase.OFF
            private set
        @Volatile var status = "Off"
            private set
        @Volatile var lastTask: String? = null
            private set
        @Volatile var lastResult: String? = null
            private set
        @androidx.annotation.VisibleForTesting
        fun resetForTest() { lastTask = null; lastResult = null; status = "Off"; phase = Phase.OFF }

        /**
         * Phase 4 Bluetooth integration seam: supplies the [AudioRouteController] used by
         * [JarvisService], [VoiceInput], and [AudioOut]. Defaults to [BluetoothAudioRouteController].
         */
        @Volatile var audioRouteControllerFactory: (Context) -> AudioRouteController = { ctx ->
            BluetoothAudioRouteController(ctx)
        }

        /** Set by the visible activity to mirror status changes. Called on the main thread. */
        var statusListener: ((String) -> Unit)? = null

        fun notificationsAvailable(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            return (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
                manager.areNotificationsEnabled() &&
                manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        }

        /** Call from a visible user interaction. Permission granted does not imply FGS eligibility. */
        fun start(context: Context, action: String? = null, task: String? = null): Boolean =
            launch(context, action ?: ACTION_ARM, task)

        internal fun startAssistantTurn(context: Context): Int? {
            if (pendingInvocation != 0) return null
            val active = instance
            if (active != null && (active.state != State.IDLE || active.pendingAnswer != null || active.invocation != 0)) return null
            val token = invocationIds.incrementAndGet()
            pendingInvocation = token
            if (launch(context, ACTION_LISTEN, token = token)) return token
            pendingInvocation = 0
            return null
        }

        internal fun cancelAssistantTurn(token: Int? = null) {
            if (token == null || token == pendingInvocation) pendingInvocation = 0
            instance?.let { service ->
                if (service.invocation != 0 && (token == null || service.invocation == token)) {
                    service.cancelVoiceTurn("Assistant session closed")
                }
            }
        }

        private fun launch(context: Context, action: String, task: String? = null, token: Int = 0): Boolean {
            if (AshRecognitionService.ownsCapture ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
                (action == ACTION_ARM && !notificationsAvailable(context))) return false
            if (!running && (action == ACTION_STOP_TASK || action == ACTION_WAKE_SETTINGS)) return false
            val intent = Intent(context, JarvisService::class.java).setAction(action)
                .putExtra(EXTRA_TASK, task).putExtra(EXTRA_INVOCATION, token)
            return try {
                if (running) context.startService(intent) else ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: IllegalStateException) {
                Log.w(TAG, "microphone service start refused (${e::class.java.simpleName})")
                false
            } catch (_: SecurityException) {
                Log.w(TAG, "microphone service start denied")
                false
            }
        }

        /** Turns Jarvis off and keeps it off until the user turns it on again. */
        fun stop(context: Context) {
            Settings(context).switchedOn = false
            // stopService never creates the service, unlike sending it a "shut down" intent.
            context.stopService(Intent(context, JarvisService::class.java))
        }

    }
}
