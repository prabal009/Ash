/*
 * Debounced headset-button handling concepts adapted from lovisschmidt/yunto
 * (MIT License, Copyright (c) 2026 Lovis Schmidt), redesigned for Project Ash
 * so Ash never holds idle audio focus or hijacks active music players.
 */
package com.jarvis.assistant.audio

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

/**
 * Optional, OFF-by-default headset-button trigger bridge.
 *
 * Design rules:
 * - Primary headset activation is Android's Default Digital Assistant (`AshVoiceInteractionService` /
 *   `ACTION_VOICE_COMMAND`), which preserves normal media buttons for Spotify/YouTube Music/etc.
 * - When a headset lacks an OS voice-assistant gesture and the user explicitly opts in, this bridge
 *   creates an idle `STATE_PAUSED` [MediaSession] with ZERO audio focus claim.
 * - Whenever external media is playing (`isMusicActive()` is true), play/pause/headset-hook events
 *   are NOT intercepted so normal music playback controls remain undisturbed.
 * - `KEYCODE_VOICE_ASSIST` is accepted directly; `KEYCODE_HEADSETHOOK` / `KEYCODE_MEDIA_PLAY_PAUSE`
 *   require opt-in AND idle music state, debounced to coalesce rapid multi-taps into one trigger.
 */
class HeadsetButtonBridge(
    context: Context,
    private val isMusicActive: () -> Boolean,
    private val main: Handler = Handler(Looper.getMainLooper()),
    private val onTrigger: (RouteTrigger) -> Unit,
) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
            refreshPriority()
        }
    }
    private var session: MediaSession? = null
    private var enabled = false
    private var pendingPresses = 0
    private val debounce = Runnable {
        val count = pendingPresses
        pendingPresses = 0
        if (enabled && count > 0 && !isMusicActive()) {
            onTrigger(RouteTrigger.HEADSET_BUTTON)
        }
    }

    val isSessionActive: Boolean
        get() = session?.isActive == true

    /** Enables or disables the opt-in MediaSession fallback. Calling with false tears it down completely. */
    fun setEnabled(enable: Boolean) {
        if (enabled == enable) {
            refreshPriority()
            return
        }
        enabled = enable
        if (!enable) {
            release()
            return
        }
        ensureSession()
        refreshPriority()
    }

    /** Yields MediaSession priority when another player is playing music, and restores passive readiness when idle. */
    fun refreshPriority() {
        val current = session ?: return
        if (!enabled) {
            release()
            return
        }
        val shouldBeActive = !isMusicActive()
        if (!shouldBeActive && pendingPresses != 0) {
            main.removeCallbacks(debounce)
            pendingPresses = 0
        }
        if (current.isActive != shouldBeActive) {
            runCatching { current.isActive = shouldBeActive }
        }
    }

    /** Releases the MediaSession, its playback observer, and any pending debounce timer. */
    fun release() {
        enabled = false
        pendingPresses = 0
        main.removeCallbacks(debounce)
        session?.let { s ->
            runCatching { audio?.unregisterAudioPlaybackCallback(playbackCallback) }
            runCatching { s.isActive = false }
            runCatching { s.release() }
        }
        session = null
    }

    /**
     * Evaluates a media-button [KeyEvent]. Returns true only when Ash consumes the event.
     * Exposed internally so unit tests can verify exact music-coexistence behavior deterministically.
     */
    internal fun handleKeyEvent(event: KeyEvent?): Boolean {
        if (!enabled || event == null) return false
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOICE_ASSIST -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    main.removeCallbacks(debounce)
                    pendingPresses = 0
                    onTrigger(RouteTrigger.BLUETOOTH_VOICE_ACTIVATION)
                }
                return true
            }
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            -> {
                // Never hijack Play/Pause while another music app is actively playing.
                if (isMusicActive()) {
                    refreshPriority()
                    return false
                }
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    pendingPresses++
                    main.removeCallbacks(debounce)
                    main.postDelayed(debounce, DEBOUNCE_MS)
                }
                return true
            }
            else -> return false
        }
    }

    private fun ensureSession() {
        if (session != null) return
        val manager = audio ?: return
        val created = runCatching { MediaSession(app, SESSION_TAG) }.getOrNull() ?: return
        @Suppress("DEPRECATION")
        created.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS)
        created.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                .setState(PlaybackState.STATE_PAUSED, 0L, 1.0f)
                .build(),
        )
        created.setCallback(
            object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                    val keyEvent: KeyEvent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    }
                    return if (handleKeyEvent(keyEvent)) true else super.onMediaButtonEvent(mediaButtonIntent)
                }
            },
            main,
        )
        // If playback cannot be observed, don't claim a button that may belong to a music player.
        try {
            manager.registerAudioPlaybackCallback(playbackCallback, main)
        } catch (_: RuntimeException) {
            created.release()
            return
        }
        session = created
    }

    companion object {
        private const val SESSION_TAG = "AshHeadsetTrigger"
        internal const val DEBOUNCE_MS = 300L
    }
}
