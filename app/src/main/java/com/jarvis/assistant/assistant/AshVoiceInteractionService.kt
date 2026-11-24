package com.jarvis.assistant.assistant

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.jarvis.assistant.R
import com.jarvis.assistant.service.JarvisService
import com.jarvis.assistant.ui.MainActivity

/** The system owns this binding. Binding it neither arms nor starts the microphone. */
class AshVoiceInteractionService : VoiceInteractionService() {
    override fun onShutdown() {
        JarvisService.cancelAssistantTurn()
        super.onShutdown()
    }
}

class AshSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AshSession(this)
}

private class AshSession(private val host: Context) : VoiceInteractionSession(host) {
    private var invocation: Int? = null
    private var showing = false
    private var status: TextView? = null

    override fun onCreateContentView(): View = LinearLayout(host).apply {
        val padding = (24 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        orientation = LinearLayout.VERTICAL
        addView(TextView(host).apply { status = this; textSize = 18f })
        addView(Button(host).apply {
            setText(R.string.open_jarvis)
            setOnClickListener {
                val intent = Intent(host, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { startAssistantActivity(intent) }.onSuccess { hide() }
            }
        })
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (showing) return
        showing = true
        val keyguard = host.getSystemService(KeyguardManager::class.java)
        invocation = if (keyguard.isDeviceLocked || keyguard.isKeyguardLocked) null
        else JarvisService.startAssistantTurn(host)
        status?.setText(if (invocation == null) R.string.ash_assistant_open else R.string.ash_assistant_starting)
    }

    private fun finishTurn() {
        showing = false
        invocation?.let { JarvisService.cancelAssistantTurn(it) }
        invocation = null
    }

    override fun onHide() {
        finishTurn()
        super.onHide()
    }

    override fun onDestroy() {
        finishTurn()
        status = null
        super.onDestroy()
    }
}
