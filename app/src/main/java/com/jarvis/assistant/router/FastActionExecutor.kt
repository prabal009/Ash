package com.jarvis.assistant.router

import android.app.KeyguardManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.MediaStore
import android.telecom.TelecomManager
import android.util.Log
import android.view.KeyEvent
import android.Manifest
import android.os.Bundle
import com.jarvis.assistant.Settings
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.agent.SensitiveActions
import com.jarvis.assistant.ai.AiMode
import com.jarvis.assistant.contacts.ContactEntry
import com.jarvis.assistant.contacts.ContactMatcher
import com.jarvis.assistant.contacts.ContactResolver
import com.jarvis.assistant.contacts.PermissionActivity
import com.jarvis.assistant.contacts.PhoneNumber
import com.jarvis.assistant.contacts.DirectCallPolicy
import com.jarvis.assistant.media.MediaNotificationListener
import com.jarvis.assistant.memory.MemoryStore
import com.jarvis.assistant.memory.SensitiveFilter
import com.jarvis.assistant.runtime.EmbeddedRuntime
import com.jarvis.assistant.service.LockedCommands
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import android.provider.Settings as AndroidSettings

/**
 * Runs [FastCommand]s with direct Android APIs: intents, AudioManager, the camera flash, media keys and the
 * accessibility service. No language model is involved. Each action reports what actually happened, and checks the
 * result where Android lets it. For request-only APIs, success means dispatch, not a verified effect.
 *
 * Call [execute] from a worker thread: some actions wait briefly to verify the outcome.
 */
class FastActionExecutor(
    context: Context,
    private val settings: Settings,
    private val memory: () -> MemoryStore?,
) {
    /**
     * [handled] false means "not something I can do directly": the caller should hand the request to the model.
     * [app] and [how] tell which app was opened and how it was matched.
     */
    data class Result(
        val handled: Boolean,
        val success: Boolean,
        val message: String,
        val app: AppEntry? = null,
        val how: String = "",
        /** True when the action cannot be taken back. Opening the dialer does not place a call. */
        val irreversible: Boolean = false,
    )

    private val app = context.applicationContext
    private val a11y get() = JarvisAccessibilityService.instance

    @Volatile private var appCache: Pair<Long, List<AppEntry>>? = null

    // A resource is reserved before dispatch, including when Android returns an uncertain error.
    private var lastDirectAttempt = Long.MIN_VALUE

    private val resolver by lazy { ContactResolver(app) }

    // A separate overload preserves calls that pass `asker` as a trailing lambda.
    fun execute(cmd: FastCommand, asker: ((String) -> String?)? = null): Result = execute(cmd, {}, asker)

    fun execute(cmd: FastCommand, recordStep: (String) -> Unit, asker: ((String) -> String?)?): Result =
        execute(cmd, recordStep, asker) { true }

    /**
     * [canAct] checks that this request is still current. It is rechecked after questions/waits and before effects.
     * [asker] returns the answer to a question, or null when no response is available.
     */
    fun execute(
        cmd: FastCommand,
        recordStep: (String) -> Unit,
        asker: ((String) -> String?)?,
        spokenRequest: String? = null,
        canAct: () -> Boolean,
    ): Result = try {
        val check = { checkAuthorized(canAct, LockedCommands.allows(cmd)) }
        check()
        run(cmd, recordStep, asker, check, spokenRequest)
    } catch (e: ActionBlocked) {
        Result(true, false, e.message.orEmpty())
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        Result(true, false, "The request was interrupted.")
    } catch (e: SecurityException) {
        Log.w(TAG, "${cmd.javaClass.simpleName} not permitted (${e.javaClass.simpleName})")
        Result(true, false, "Android didn't allow that.")
    } catch (e: Exception) {
        Log.w(TAG, "${cmd.javaClass.simpleName} failed (${e.javaClass.simpleName})")
        Result(true, false, "Sorry, that didn't work.")
    }

    private fun run(
        cmd: FastCommand,
        recordStep: (String) -> Unit,
        asker: ((String) -> String?)?,
        check: () -> Unit,
        spokenRequest: String?,
    ): Result = when (cmd) {
        is FastCommand.OpenApp -> openApp(cmd.query, asker, check)
        is FastCommand.Press -> press(cmd.key, check)
        is FastCommand.Scroll, FastCommand.ClearText, FastCommand.SubmitSearch, is FastCommand.TypeText,
        is FastCommand.TapText -> withPhoneControl(check) { svc ->
            val effect = svc.userControl(cmd, asker, check)
            Result(true, effect.verified, effect.summary)
        }
        is FastCommand.YouTubeSearch -> {
            val svc = a11y
            if (svc != null) {
                check()
                val effect = svc.userControl(cmd, asker, check)
                Result(true, effect.verified, effect.summary)
            } else {
                webSearch(FastCommand.WebSearch(cmd.query, "youtube"), check)
            }
        }
        is FastCommand.Volume -> volume(cmd, check)
        is FastCommand.Flashlight -> flashlight(cmd, check)
        is FastCommand.Media -> media(cmd, asker, check)
        is FastCommand.PlayMusic -> playMusic(cmd, asker, check)
        is FastCommand.WebSearch -> webSearch(cmd, check)
        is FastCommand.OpenWebsite -> {
            val intent = cmd.browserIntent()
            val sent = intent != null && startIntent(intent, check)
            Result(true, sent, if (sent) "I asked Android to open the website." else "I couldn't open that website.")
        }
        is FastCommand.OpenSettings -> openSettings(cmd, check)
        FastCommand.OpenCamera -> {
            val ok = startIntent(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), check)
            Result(true, ok, if (ok) "I asked Android to open the camera." else "I couldn't open the camera.")
        }
        is FastCommand.SetAlarm -> setAlarm(cmd, check)
        is FastCommand.SetTimer -> setTimer(cmd, check)
        is FastCommand.Dial -> {
            val dialable = ContactMatcher.dialableNumber(cmd.number)
            val now = SystemClock.elapsedRealtime()
            if (dialable != null && DirectCallPolicy.canPlaceAfterClarification(
                    directEnabled = settings.directContactCalls,
                    hasCallPermission = resolver.hasCallPermission(),
                    spokenRequest = spokenRequest,
                    number = dialable,
                    nowMs = now,
                    lastAttemptMs = lastDirectAttempt,
                    context = app,
                )
            ) {
                placeDirectCall(null, dialable, recordStep, check)
            } else {
                confirmDial(cmd.number, null, asker, recordStep, check)
            }
        }
        is FastCommand.CallContact -> callContact(cmd.name, spokenRequest, asker, recordStep, check)
        FastCommand.Screenshot -> withPhoneControl(check) { svc ->
            val msg = svc.pressKey("screenshot")
            Result(true, msg.startsWith("Pressed"), if (msg.startsWith("Pressed")) "Android accepted the screenshot request." else msg)
        }
        FastCommand.LockScreen -> withPhoneControl(check) { svc ->
            val msg = svc.pressKey("lock")
            Result(true, msg.startsWith("Pressed"), if (msg.startsWith("Pressed")) "Android accepted the screen lock request." else msg)
        }
        is FastCommand.Ask -> ask(cmd.kind)
        is FastCommand.SetMode -> setMode(cmd.mode, check)
        is FastCommand.SetLanguage -> setLanguage(cmd.lang, check)
        is FastCommand.RuntimeControl -> runtimeControl(cmd.action, check)
    }

    // ---- Apps ----

    /** Launchable apps, cached for a short time so repeated commands are instant. */
    fun installedApps(): List<AppEntry> {
        val now = SystemClock.elapsedRealtime()
        appCache?.let { if (now - it.first < APP_CACHE_MS) return it.second }
        val pm = app.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
            .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.activityInfo.name) }
            .distinctBy { it.packageName }
        appCache = now to list
        return list
    }

    private fun openApp(query: String, asker: ((String) -> String?)?, check: () -> Unit): Result {
        val m = AppMatcher.match(query, installedApps()) { phrase -> runCatching { memory()?.appAlias(phrase) }.getOrNull() }
        val entry = m.app
        if (entry == null) {
            if (m.ambiguous.isNotEmpty()) {
                return Result(true, false, "Which one: ${m.ambiguous.joinToString(" or ") { it.label }}? Please say the full name.")
            }
            m.notInstalled?.let { return Result(true, false, "${it.replaceFirstChar { c -> c.uppercase() }} isn't installed on this phone.") }
            return Result(false, false, "")
        }
        // A match that only "sounds like" the name is confirmed first. A yes teaches Jarvis the name for next time.
        if (m.how == "sound") {
            if (asker == null) return Result(true, false, "Please say the full app name.")
            check()
            val answer = asker("Did you mean ${entry.label}?")
            check()
            if (!SensitiveActions.isYes(answer)) return Result(true, false, "Okay, I won't open it.")
            learnAlias(query, entry)
        }
        val svc = a11y
        val started = if (svc != null) {
            check()
            svc.openPackage(entry.packageName, entry.activity)
        } else {
            val intent = app.packageManager.getLaunchIntentForPackage(entry.packageName)
            intent != null && startIntent(intent, check)
        }
        if (!started) return Result(true, false, "I couldn't open ${entry.label}.", entry, m.how)
        if (svc == null) {
            return Result(true, true, "I asked Android to open ${entry.label}.", entry, m.how)
        }
        val shown = svc.waitForForeground(entry.packageName, FOREGROUND_WAIT_MS)
        val msg = if (shown) "Opened ${entry.label}." else "I asked Android to open ${entry.label}, but I couldn't confirm it came to the front."
        return Result(true, true, msg, entry, m.how)
    }

    // ---- Navigation and screen ----

    private fun press(key: String, check: () -> Unit): Result = withPhoneControl(check) { svc ->
        val msg = svc.pressKey(key)
        val ok = msg.startsWith("Pressed")
        Result(true, ok, if (ok) "Android accepted the ${key.replace('_', ' ')} request." else msg)
    }

    private inline fun withPhoneControl(check: () -> Unit, block: (JarvisAccessibilityService) -> Result): Result {
        val svc = a11y ?: return Result(true, false, "Please turn on phone control in the Ash app first.")
        check()
        return block(svc)
    }

    // ---- Sound, flashlight, media ----

    private fun volume(cmd: FastCommand.Volume, check: () -> Unit): Result {
        val am = app.getSystemService(AudioManager::class.java)
        val stream = AudioManager.STREAM_MUSIC
        val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
        check()
        when (cmd.change) {
            VolumeChange.UP -> am.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            VolumeChange.DOWN -> am.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            VolumeChange.MUTE -> am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            VolumeChange.UNMUTE -> am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            VolumeChange.SET -> {
                val level = ((cmd.percent ?: 50) * max / 100f).roundToInt().coerceIn(0, max)
                am.setStreamVolume(stream, level, AudioManager.FLAG_SHOW_UI)
            }
        }
        val percent = (am.getStreamVolume(stream) * 100f / max).roundToInt()
        return Result(true, true, if (am.isStreamMute(stream)) "Muted." else "Volume is $percent percent.")
    }

    private fun flashlight(cmd: FastCommand.Flashlight, check: () -> Unit): Result {
        val cm = app.getSystemService(CameraManager::class.java)
        val cameraId = cm.cameraIdList.firstOrNull { id ->
            val c = cm.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return Result(true, false, "This phone has no flashlight I can use.")
        // Callbacks are per camera. An unknown initial state (or another camera's callback)
        // cannot confirm "off", and we must not guess the direction of a toggle.
        val state = java.util.concurrent.atomic.AtomicReference<Boolean?>()
        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(id: String, enabled: Boolean) {
                if (id == cameraId) state.set(enabled)
            }
            override fun onTorchModeUnavailable(id: String) {
                if (id == cameraId) state.set(null)
            }
        }
        cm.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
        try {
            waitUntil(800) { state.get() != null }
            val want = cmd.on ?: state.get()?.not() ?: return Result(true, false, "I couldn't determine the flashlight state.")
            check()
            if (state.get() != want) cm.setTorchMode(cameraId, want)
            return if (waitUntil(800) { state.get() == want }) Result(true, true, if (want) "Flashlight on." else "Flashlight off.")
                else Result(true, false, "I couldn't verify that the flashlight turned ${if (want) "on" else "off"}.")
        } finally {
            cm.unregisterTorchCallback(callback)
        }
    }

    private fun media(cmd: FastCommand.Media, asker: ((String) -> String?)?, check: () -> Unit): Result {
        val sessions = if (isUnlocked()) activeSessions() else emptyList()
        val target = cmd.app?.let {
            mediaApp(it, sessions, emptyList(), asker, check) ?: return unknownMediaApp()
        }
        val actionFlag = when (cmd.action) {
            MediaAction.PLAY -> PlaybackState.ACTION_PLAY
            MediaAction.PAUSE -> PlaybackState.ACTION_PAUSE
            MediaAction.NEXT -> PlaybackState.ACTION_SKIP_TO_NEXT
            MediaAction.PREVIOUS -> PlaybackState.ACTION_SKIP_TO_PREVIOUS
        }
        val players = sessions.filter { (target == null || it.packageName == target.packageName) && it.supports(actionFlag) }
        if (players.isEmpty()) {
            if (target == null) return globalMedia(cmd.action, check)
            return Result(true, false, "I couldn't find an active ${target.label} session that supports this. Open the player and check Ash's optional notification access.")
        }

        val checkSession = {
            check()
            if (!isUnlocked()) throw ActionBlocked("Unlock your phone and try again.")
        }
        val selected = choosePlayer(players, { appLabel(it.packageName) }, asker, checkSession)
            ?: return Result(true, false, "I didn't select a player. Name the app you want to control.")
        val label = appLabel(selected.packageName)
        val active = currentSession(selected, actionFlag, checkSession) ?: return unavailableSession()
        val expected = when (cmd.action) {
            MediaAction.PLAY -> PlaybackState.STATE_PLAYING
            MediaAction.PAUSE -> PlaybackState.STATE_PAUSED
            else -> null
        }
        val observed = if (cmd.action == MediaAction.PLAY) "playing" else "paused"
        if (expected != null && active.playbackState?.state == expected) {
            checkSession()
            return Result(true, true, "$label reports that it's already $observed.")
        }
        val controls = active.transportControls
        checkSession()
        when (cmd.action) {
            MediaAction.PLAY -> controls.play()
            MediaAction.PAUSE -> controls.pause()
            MediaAction.NEXT -> controls.skipToNext()
            MediaAction.PREVIOUS -> controls.skipToPrevious()
        }
        if (expected != null && waitUntil(900) { isUnlocked() && active.playbackState?.state == expected }) {
            checkSession()
            return Result(true, true, "$label reports that it's $observed.")
        }
        return if (isUnlocked()) mediaDispatched(cmd.action, label) else mediaDispatched(cmd.action)
    }

    private fun globalMedia(action: MediaAction, check: () -> Unit): Result {
        val am = app.getSystemService(AudioManager::class.java)
        val code = when (action) {
            MediaAction.PLAY -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaAction.PAUSE -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        check()
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return mediaDispatched(action)
    }

    private fun mediaDispatched(action: MediaAction, label: String = "Android's media controls"): Result {
        val request = when (action) {
            MediaAction.PLAY -> "play"
            MediaAction.PAUSE -> "pause"
            MediaAction.NEXT -> "next track"
            MediaAction.PREVIOUS -> "previous track"
        }
        return Result(true, true, "I sent the $request request to $label, but couldn't verify playback.")
    }

    private fun activeSessions(): List<MediaController> = try {
        val manager = app.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(app, MediaNotificationListener::class.java)
        // Android authorizes this only while the user has enabled this notification listener.
        manager.getActiveSessions(listener).filterNot { it.packageName == app.packageName }
    } catch (_: SecurityException) {
        emptyList()
    }

    private fun MediaController.supports(action: Long): Boolean =
        (playbackState?.actions ?: 0L) and action != 0L

    private fun currentSession(controller: MediaController, action: Long, check: () -> Unit): MediaController? {
        check()
        return activeSessions().firstOrNull { it.sessionToken == controller.sessionToken && it.supports(action) }
    }

    private fun unavailableSession() = Result(true, false, "That media session is no longer available.")

    private fun appLabel(packageName: String): String = try {
        val pm = app.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }

    private fun mediaApp(
        name: String,
        sessions: List<MediaController>,
        handlers: List<AppEntry>,
        asker: ((String) -> String?)?,
        check: () -> Unit,
    ): AppEntry? {
        val apps = (installedApps() + handlers + sessions.map { AppEntry(appLabel(it.packageName), it.packageName) })
            .distinctBy { it.packageName }
        val match = AppMatcher.match(name, apps) { runCatching { memory()?.appAlias(it) }.getOrNull() }
        if (match.ambiguous.isNotEmpty()) return choosePlayer(match.ambiguous, AppEntry::label, asker, check)
        val entry = match.app ?: return null
        if (match.how in setOf("exact", "learned", "builtin")) return entry
        check()
        val answer = asker?.invoke("Did you mean ${entry.label}?")
        check()
        return entry.takeIf { SensitiveActions.isYes(answer) }
    }

    private fun unknownMediaApp() = Result(true, false, "I couldn't identify that media app. Say its full name.")

    private fun <T> choosePlayer(
        players: List<T>,
        label: (T) -> String,
        asker: ((String) -> String?)?,
        check: () -> Unit,
    ): T? {
        if (players.size <= 1) return players.singleOrNull()
        if (asker == null || players.size > MAX_CHOICES) return null
        val labels = players.map(label)
        check()
        val answer = asker("Which player? ${numbered(labels)}")
        check()
        Lexicon.optionIndex(answer)?.let { return players.getOrNull(it) }
        val name = Lexicon.normalize(answer.orEmpty())
        val index = labels.indices.singleOrNull { Lexicon.normalize(labels[it]) == name } ?: return null
        return players[index]
    }

    private fun waitUntil(ms: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + ms
        while (true) {
            if (condition()) return true
            if (SystemClock.elapsedRealtime() >= end) return false
            Thread.sleep(60)
        }
    }

    // ---- Search, settings, clock ----

    private fun webSearch(cmd: FastCommand.WebSearch, check: () -> Unit): Result {
        val q = URLEncoder.encode(cmd.query, "UTF-8")
        val youtube = cmd.site == "youtube"
        val url = if (youtube) "https://www.youtube.com/results?search_query=$q" else "https://www.google.com/search?q=$q"
        val ok = startIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)), check)
        val where = if (youtube) "YouTube" else "Google"
        return Result(true, ok, if (ok) "I sent the search request to $where." else "I couldn't start the search.")
    }

    private fun openSettings(cmd: FastCommand.OpenSettings, check: () -> Unit): Result {
        val action = when (cmd.page) {
            "wifi" -> AndroidSettings.ACTION_WIFI_SETTINGS
            "bluetooth" -> AndroidSettings.ACTION_BLUETOOTH_SETTINGS
            "battery" -> AndroidSettings.ACTION_BATTERY_SAVER_SETTINGS
            "display" -> AndroidSettings.ACTION_DISPLAY_SETTINGS
            "sound" -> AndroidSettings.ACTION_SOUND_SETTINGS
            "location" -> AndroidSettings.ACTION_LOCATION_SOURCE_SETTINGS
            "airplane" -> AndroidSettings.ACTION_AIRPLANE_MODE_SETTINGS
            "hotspot", "data" -> AndroidSettings.ACTION_WIRELESS_SETTINGS
            "apps" -> AndroidSettings.ACTION_APPLICATION_SETTINGS
            "storage" -> AndroidSettings.ACTION_INTERNAL_STORAGE_SETTINGS
            "accessibility" -> AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS
            "developer" -> AndroidSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
            "date" -> AndroidSettings.ACTION_DATE_SETTINGS
            "language" -> AndroidSettings.ACTION_LOCALE_SETTINGS
            "notification" -> "android.settings.ALL_APPS_NOTIFICATION_SETTINGS"
            else -> AndroidSettings.ACTION_SETTINGS
        }
        val sent = startIntent(Intent(action), check)
        val general = !sent && action != AndroidSettings.ACTION_SETTINGS && startIntent(Intent(AndroidSettings.ACTION_SETTINGS), check)
        val name = cmd.page.replaceFirstChar { it.uppercase() }
        return when {
            general -> Result(true, true, "I requested general Android settings. I couldn't open the $name page.")
            !sent -> Result(true, false, "I couldn't open settings.")
            cmd.toggleRequested -> Result(true, true, "I requested $name settings. Change the setting there.")
            else -> Result(true, true, "I requested $name settings.")
        }
    }

    private fun setAlarm(cmd: FastCommand.SetAlarm, check: () -> Unit): Result {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, cmd.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, cmd.minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Ash")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val ok = startIntent(intent, check)
        val h12 = if (cmd.hour % 12 == 0) 12 else cmd.hour % 12
        val label = "%d:%02d %s".format(Locale.US, h12, cmd.minute, if (cmd.hour < 12) "AM" else "PM")
        return Result(true, ok, if (ok) "I've asked your clock app to set an alarm for $label." else "I couldn't find a clock app to set the alarm.")
    }

    private fun setTimer(cmd: FastCommand.SetTimer, check: () -> Unit): Result {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, cmd.seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Ash")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val ok = startIntent(intent, check)
        val minutes = cmd.seconds / 60
        val label = when {
            cmd.seconds % 3600 == 0 -> "${cmd.seconds / 3600} hour"
            minutes > 0 && cmd.seconds % 60 == 0 -> "$minutes minute"
            else -> "${cmd.seconds} second"
        }
        return Result(true, ok, if (ok) "I've asked your clock app to start a $label timer." else "I couldn't find a clock app to start the timer.")
    }

    // ---- Music search ----

    private fun playMusic(cmd: FastCommand.PlayMusic, asker: ((String) -> String?)?, check: () -> Unit): Result {
        val extras = android.os.Bundle().apply {
            putString(SearchManager.QUERY, cmd.query)
            putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        }
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).putExtras(extras)
        val pm = app.packageManager
        val handlers = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.exported && it.activityInfo.packageName != app.packageName }
            .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
        val sessions = activeSessions()
        val target = cmd.app?.let { mediaApp(it, sessions, handlers, asker, check) ?: return unknownMediaApp() }
        val action = PlaybackState.ACTION_PLAY_FROM_SEARCH
        val players = sessions.filter { (target == null || it.packageName == target.packageName) && it.supports(action) }
        if (players.isNotEmpty()) {
            val selected = choosePlayer(players, { appLabel(it.packageName) }, asker, check)
                ?: return Result(true, false, "I didn't select a player. Name the app you want to search.")
            val label = appLabel(selected.packageName)
            val active = currentSession(selected, action, check) ?: return unavailableSession()
            val controls = active.transportControls
            check()
            controls.playFromSearch(cmd.query, extras)
            return Result(true, true, "I sent a playback search to $label, but couldn't verify what played.")
        }

        val supported = if (target == null) handlers else handlers.filter { it.packageName == target.packageName }
        if (supported.isEmpty()) {
            return Result(true, false, if (target == null) "I couldn't find an app that supports playback search."
                else "${target.label} doesn't have an available playback search. Try another music app.")
        }
        val selected = choosePlayer(supported, AppEntry::label, asker, check)
            ?: return Result(true, false, "I didn't select a player. Name the app you want to search.")
        val ok = startIntent(intent.setPackage(selected.packageName), check, direct = true)
        return Result(true, ok, if (ok) "I requested a playback search in ${selected.label}, but couldn't verify what played."
            else "I couldn't send the playback search to ${selected.label}.")
    }

    // ---- Calling ----

    private fun callContact(
        name: String,
        spokenRequest: String?,
        asker: ((String) -> String?)?,
        recordStep: (String) -> Unit,
        check: () -> Unit,
    ): Result {
        if (!resolver.hasContactsPermission()) {
            val sent = startIntent(PermissionActivity.intent(app), check, direct = true)
            return Result(true, false, if (sent) "I requested contacts access. If you grant it, ask again."
                else "I need contacts access for names. Grant it in Ash's settings or dial in the Phone app.")
        }
        val match = ContactMatcher.find(name, resolver.contacts())
        check()
        var clarified = false
        val contact: ContactEntry = when {
            match.matches.isEmpty() -> return Result(true, false, "I couldn't find that contact.")
            match.ambiguous -> {
                if (asker == null || match.matches.size > MAX_CHOICES) {
                    return Result(true, false, "Several contacts match. Please say the full name or select the contact in the Phone app.")
                }
                val labels = match.matches.map { person ->
                    val duplicates = match.matches.any { it.id != person.id && it.name.equals(person.name, ignoreCase = true) }
                    if (!duplicates) person.name else person.numbers.joinToString(", ", "${person.name}, ") { describeNumber(it) }
                }
                check()
                val answer = asker("Which contact? ${numbered(labels)}")
                check()
                clarified = true
                ContactMatcher.pick(answer.orEmpty(), match.matches)
                    ?: return Result(true, false, "I couldn't identify the contact, so I didn't place a call.")
            }
            else -> match.matches.single()
        }
        val numbers = contact.numbers
        val number = when {
            numbers.isEmpty() -> return Result(true, false, "${contact.name} has no saved number.")
            numbers.size == 1 -> numbers.single()
            asker == null || numbers.size > MAX_CHOICES -> return Result(true, false, "${contact.name} has several numbers. Select one in the Phone app.")
            else -> {
                check()
                val answer = asker("Which number for ${contact.name}? ${numbered(numbers.map(::describeNumber))}")
                check()
                clarified = true
                ContactMatcher.pickNumber(answer, numbers)
                    ?: return Result(true, false, "I couldn't identify the number, so I didn't place a call.")
            }
        }
        val dialable = ContactMatcher.dialableNumber(number.number)
            ?: return Result(true, false, "I can't call that number. Enter it in the Phone app.")
        val now = SystemClock.elapsedRealtime()
        val allowDirect = if (clarified) {
            DirectCallPolicy.canPlaceAfterClarification(
                directEnabled = settings.directContactCalls,
                hasCallPermission = resolver.hasCallPermission(),
                spokenRequest = spokenRequest,
                number = dialable,
                nowMs = now,
                lastAttemptMs = lastDirectAttempt,
                context = app,
            )
        } else {
            DirectCallPolicy.canPlaceDirectly(
                directEnabled = settings.directContactCalls,
                hasCallPermission = resolver.hasCallPermission(),
                spokenRequest = spokenRequest,
                match = match,
                number = dialable,
                nowMs = now,
                lastAttemptMs = lastDirectAttempt,
                context = app,
            )
        }
        if (allowDirect) {
            return placeDirectCall(contact, dialable, recordStep, check)
        }
        val canDirectAfterConfirm = settings.directContactCalls &&
            resolver.hasCallPermission() &&
            !spokenRequest.isNullOrBlank() &&
            !DirectCallPolicy.isEmergencyOrSpecial(dialable, app) &&
            (lastDirectAttempt == Long.MIN_VALUE || now - lastDirectAttempt >= DirectCallPolicy.COOLDOWN_MS)
        if (canDirectAfterConfirm && asker != null) {
            check()
            val answer = asker("Call ${contact.name}, ${number.type} $dialable? Say yes to call.")
            check()
            return if (ContactMatcher.confirmsDial(answer)) {
                placeDirectCall(contact, dialable, recordStep, check)
            } else {
                Result(true, false, "I didn't place the call.")
            }
        }
        return confirmDial(dialable, contact, asker, recordStep, check, number.type)
    }

    private fun placeDirectCall(
        contact: ContactEntry?,
        number: String,
        recordStep: (String) -> Unit,
        check: () -> Unit,
    ): Result {
        if (contact != null && !resolver.stillHasNumber(contact, number)) {
            return Result(true, false, "The contact or number is no longer available. Please ask again.")
        }
        val label = contact?.name ?: number
        check()
        // Reserve cooldown BEFORE dispatch so an uncertain platform failure never redials.
        lastDirectAttempt = SystemClock.elapsedRealtime()
        recordStep("Requested direct call to $label.")
        val uri = Uri.fromParts("tel", number, null)
        // Prefer TelecomManager.placeCall (background-safe on Android 10–15 without BAL blocks);
        // fall back to ACTION_CALL (routed via AccessibilityService when bound) if TelecomManager is unavailable.
        val placed = try {
            val telecom = app.getSystemService(TelecomManager::class.java)
            if (telecom != null) {
                telecom.placeCall(uri, Bundle())
                true
            } else false
        } catch (e: SecurityException) {
            Log.w(TAG, "Direct call permission denied (${e.javaClass.simpleName})")
            false
        } catch (e: RuntimeException) {
            Log.w(TAG, "Direct call via TelecomManager failed (${e.javaClass.simpleName})")
            false
        } || startIntent(Intent(Intent.ACTION_CALL, uri), check, direct = false)
        return if (placed) {
            Result(true, true, "Calling $label.", irreversible = true)
        } else {
            Result(true, false, "Android didn't start the call to $label. Enable Direct contact calls and Phone permission in Ash Settings.")
        }
    }

    private fun describeNumber(number: PhoneNumber): String =
        "${number.type} ${ContactMatcher.dialableNumber(number.number) ?: "(unsupported number)"}"

    private fun confirmDial(
        raw: String,
        contact: ContactEntry?,
        asker: ((String) -> String?)?,
        recordStep: (String) -> Unit,
        check: () -> Unit,
        type: String? = null,
    ): Result {
        val number = ContactMatcher.dialableNumber(raw)
            ?: return Result(true, false, "I can't open the dialer with that number. Enter it in the Phone app.")
        if (asker == null) return Result(true, false, "I need confirmation before opening the dialer.")
        val target = if (contact == null) number else "${contact.name}, $type $number"
        check()
        val answer = asker("Open the dialer for $target? You will place the call yourself.")
        val confirmedAt = SystemClock.elapsedRealtime()
        check()
        if (!ContactMatcher.confirmsDial(answer)) return Result(true, false, "I didn't open the dialer.")
        if (contact != null && !resolver.stillHasNumber(contact, number)) {
            return Result(true, false, "The contact or number is no longer available. Please ask again.")
        }
        val dialer = app.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).setPackage(dialer)
        val ok = startIntent(intent, {
            check()
            if (SystemClock.elapsedRealtime() - confirmedAt >= DIAL_APPROVAL_MS) {
                throw ActionBlocked("The dialer confirmation expired. Please ask again.")
            }
            recordStep("Approved dispatch of the dialer request.")
            check()
        }, direct = true)
        return Result(true, ok, if (ok) "I asked Android to open the dialer. Tap Call there to place the call."
            else "I couldn't open the dialer. No call was placed.")
    }

    private fun numbered(labels: List<String>): String =
        labels.mapIndexed { index, label -> "${index + 1}, $label" }.joinToString("; ")

    // ---- Things Jarvis can answer itself ----

    private fun ask(kind: QueryKind): Result = when (kind) {
        QueryKind.TIME -> Result(true, true, "It's ${SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())}.")
        QueryKind.DATE -> Result(true, true, "Today is ${SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())}.")
        QueryKind.BATTERY -> {
            val bm = app.getSystemService(BatteryManager::class.java)
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (level !in 0..100) Result(true, false, "Android didn't report a battery level.")
            else Result(true, true, "Battery is at $level percent${if (bm.isCharging) " and charging" else ""}.")
        }
    }

    private fun setMode(mode: AiMode, check: () -> Unit): Result {
        check()
        settings.aiMode = mode
        val msg = when (mode) {
            AiMode.AUTO -> "Automatic mode. I'll pick the local or cloud model for each request."
            AiMode.ONLINE -> "Online mode. I'll prefer your cloud models."
            AiMode.OFFLINE -> "Offline mode. Only the local model will answer."
        }
        return Result(true, true, msg)
    }

    private fun runtimeControl(action: RuntimeAction, check: () -> Unit): Result {
        val rt = EmbeddedRuntime.get(app)
        if (!rt.isSupported()) return Result(true, false, "This build doesn't include the on-device model runtime.")
        val s = rt.status
        return when (action) {
            RuntimeAction.DOWNLOAD -> when {
                rt.hasModel() -> Result(true, true, "The on-device model is already installed.")
                s.phase == EmbeddedRuntime.Phase.DOWNLOADING -> Result(true, true, "It's already downloading${if (s.percent >= 0) ", ${s.percent} percent done" else ""}.")
                else -> {
                    check()
                    rt.startDownloadIfNeeded(allowMobileData = true) // the user asked for it explicitly
                    Result(true, true, "I requested the on-device model download. Check progress in Settings.")
                }
            }
            RuntimeAction.STATUS -> Result(true, true, when (s.phase) {
                EmbeddedRuntime.Phase.RUNNING -> "The on-device model is running."
                EmbeddedRuntime.Phase.STARTING -> "The on-device model is starting."
                EmbeddedRuntime.Phase.DOWNLOADING -> "The on-device model is downloading${if (s.percent >= 0) ", ${s.percent} percent" else ""}."
                EmbeddedRuntime.Phase.NO_MODEL -> "The on-device model isn't downloaded yet. Say download the offline model."
                EmbeddedRuntime.Phase.FAILED -> "The on-device model has a problem. ${s.detail}"
                EmbeddedRuntime.Phase.STOPPED -> "The on-device model is installed and will start when needed."
                EmbeddedRuntime.Phase.NO_BINARY -> "This build doesn't include the on-device model runtime."
            })
        }
    }

    private fun setLanguage(lang: String, check: () -> Unit): Result {
        check()
        settings.speechLanguage = lang
        return if (lang == "hi") {
            Result(true, true, "Okay. I'll listen for Hindi and Hinglish with Google speech recognition, and answer in English.")
        } else {
            Result(true, true, "Okay. English with offline recognition.")
        }
    }

    // ---- Memory ----

    /** Carries out a memory request. Only called for sentences the user spoke. */
    fun handleMemory(intent: MemoryIntent): Result {
        val store = memory() ?: return Result(true, false, "My memory isn't available right now.")
        return when (intent) {
            is MemoryIntent.TeachApp -> teach(store, intent)
            is MemoryIntent.TeachPhrase -> {
                if (SensitiveFilter.isSensitive(intent.phrase) || SensitiveFilter.isSensitive(intent.meaning)) Result(true, false, SensitiveFilter.REFUSAL)
                else {
                    store.addPhraseCorrection(intent.phrase, intent.meaning)
                    Result(true, true, "Got it. When I hear \"${intent.phrase}\", I'll understand \"${intent.meaning}\".")
                }
            }
            is MemoryIntent.Remember -> {
                if (SensitiveFilter.isSensitive(intent.text)) return Result(true, false, SensitiveFilter.REFUSAL)
                val id = store.addFact(intent.text, intent.style)
                if (id >= 0) Result(true, true, if (intent.style) "Okay, I'll keep that in mind." else "Okay, I'll remember that.")
                else Result(true, false, "I couldn't save that.")
            }
            is MemoryIntent.Forget -> {
                val n = store.forget(intent.what)
                if (n > 0) Result(true, true, "Okay, I forgot $n ${if (n == 1) "thing" else "things"}.")
                else Result(true, true, "I don't have anything saved about that.")
            }
            MemoryIntent.ListAll -> {
                val items = store.all()
                if (items.isEmpty()) Result(true, true, "I haven't saved anything yet.")
                else Result(true, true, "I remember ${items.size} ${if (items.size == 1) "thing" else "things"}. " +
                    "The latest: ${items.take(3).joinToString("; ") { it.title }}. Open Memories in Ash to see or change them.")
            }
        }
    }

    private fun teach(store: MemoryStore, t: MemoryIntent.TeachApp): Result {
        val apps = installedApps()
        val target = AppMatcher.match(t.target, apps)
        val entry = target.app
        if (entry == null) {
            return if (target.ambiguous.isNotEmpty()) {
                Result(true, false, "${t.target} matches ${target.ambiguous.joinToString(" and ") { it.label }}. Please say the full app name.")
            } else {
                Result(true, false, "I couldn't find an app called ${t.target}, so I didn't save that.")
            }
        }
        val already = AppMatcher.match(t.phrase, apps) { runCatching { store.appAlias(it) }.getOrNull() }.app
        if (already?.packageName == entry.packageName) {
            return Result(true, true, "I already understand \"${t.phrase}\" as ${entry.label}.")
        }
        store.addAlias(t.phrase, entry.packageName, entry.label)
        return Result(true, true, "Got it. When you say \"${t.phrase}\", I'll open ${entry.label}.")
    }

    /** Saves "[phrase] -> [entry]" after the user repeated a failed command with the right name. */
    fun learnAlias(phrase: String, entry: AppEntry) {
        runCatching { memory()?.addAlias(phrase, entry.packageName, entry.label) }
    }

    // ---- helpers ----

    private class ActionBlocked(message: String) : RuntimeException(message)

    private fun isUnlocked(): Boolean =
        app.getSystemService(KeyguardManager::class.java)?.let { !it.isDeviceLocked && !it.isKeyguardLocked } == true

    private fun checkAuthorized(canAct: () -> Boolean, allowLocked: Boolean = false) {
        if (!canAct()) throw ActionBlocked("The request is no longer active.")
        if (!allowLocked && !isUnlocked()) throw ActionBlocked("Unlock your phone and try again.")
    }

    private fun startIntent(intent: Intent, check: () -> Unit, direct: Boolean = false): Boolean {
        if (!direct) a11y?.let {
            check()
            return it.launch(intent)
        }
        check()
        return try {
            app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    companion object {
        private const val TAG = "FastAction"
        private const val APP_CACHE_MS = 30_000L
        private const val FOREGROUND_WAIT_MS = 3_000L
        private const val DIAL_APPROVAL_MS = 10_000L
        private const val MAX_CHOICES = 5
    }
}
