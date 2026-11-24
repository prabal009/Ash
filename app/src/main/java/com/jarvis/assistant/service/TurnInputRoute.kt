package com.jarvis.assistant.service

import com.jarvis.assistant.audio.AudioRouteController
import com.jarvis.assistant.audio.InputRouteKind

/** Main-thread route handoff. Never start the answer timer while the headset is still negotiating. */
internal class TurnInputRoute(private val routes: AudioRouteController, private val stopRecorder: (() -> Unit) -> Unit) {
    enum class Result { LOCAL, HEADSET, PHONE_FALLBACK, HEADSET_LOST }

    fun prepare(
        preferHeadset: Boolean, requireHeadset: Boolean, bluetoothEnabled: Boolean,
        isCurrent: () -> Boolean, ready: (Result) -> Unit,
    ) {
        if (!isCurrent()) return
        if (!preferHeadset || !bluetoothEnabled || !routes.currentSnapshot().bluetoothMicAvailable) {
            ready(if (requireHeadset) Result.HEADSET_LOST else Result.LOCAL)
            return
        }
        stopRecorder {
            if (!isCurrent()) return@stopRecorder
            routes.prepareCommandInput(preferHeadset = true) { policy, fallback ->
                if (isCurrent()) {
                    val headset = !fallback && (policy.expectedKind == InputRouteKind.BLUETOOTH_SCO ||
                        policy.expectedKind == InputRouteKind.BLE_AUDIO)
                    ready(when {
                        headset -> Result.HEADSET
                        requireHeadset -> Result.HEADSET_LOST
                        else -> Result.PHONE_FALLBACK
                    })
                }
            }
        }
    }
}
