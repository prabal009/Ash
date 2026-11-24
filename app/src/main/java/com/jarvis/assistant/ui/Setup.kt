package com.jarvis.assistant.ui

import androidx.annotation.StringRes
import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
import android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
import android.provider.Settings.Secure
import android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS
import android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
import androidx.appcompat.app.AlertDialog
import com.jarvis.assistant.assistant.AshVoiceInteractionService
import com.jarvis.assistant.service.JarvisService
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.databinding.ItemCheckBinding
import com.jarvis.assistant.voice.Kokoro
import com.jarvis.assistant.voice.ModelDownloader
import com.jarvis.assistant.voice.SpeechModel
import com.jarvis.assistant.voice.WakeModel

/** The things Jarvis needs before it can work, shared by onboarding, home and settings. */
object Setup {

    enum class Item(
        @DrawableRes val icon: Int,
        @StringRes val title: Int,
        @StringRes val subtitle: Int,
        @StringRes val action: Int,
        val required: Boolean,
    ) {
        MICROPHONE(R.drawable.ic_mic, R.string.item_mic_title, R.string.item_mic_sub, R.string.allow, true),
        PHONE_CONTROL(R.drawable.ic_touch, R.string.item_control_title, R.string.item_control_sub, R.string.enable, false),
        BACKGROUND(R.drawable.ic_mic, R.string.ash_default_app, R.string.ash_default_app_manual, R.string.open_settings, false),
        WAKE_WORD(R.drawable.ic_wave, R.string.item_wake_title, R.string.item_wake_sub, R.string.download, false),
        SPEECH(R.drawable.ic_mic, R.string.item_speech_title, R.string.item_speech_sub, R.string.download, false),
        VOICE(R.drawable.ic_voice, R.string.item_voice_title, R.string.item_voice_sub, R.string.download, false),
    }

    val PERMISSIONS: Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    fun isDone(context: Context, item: Item): Boolean = when (item) {
        Item.MICROPHONE -> ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        Item.PHONE_CONTROL -> isAccessibilityEnabled(context)
        Item.BACKGROUND -> VoiceInteractionService.isActiveService(context, ComponentName(context, AshVoiceInteractionService::class.java))
        Item.WAKE_WORD -> WakeModel.isInstalled(context)
        Item.SPEECH -> SpeechModel.isInstalled(context)
        Item.VOICE -> Kokoro.isInstalled(context)
    }

    fun remaining(context: Context) = Item.entries.filter { !isDone(context, it) }

    fun act(activity: Activity, item: Item) {
        when (item) {
            Item.MICROPHONE -> activity.requestPermissions(PERMISSIONS, 1)
            Item.PHONE_CONTROL -> {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.toast_a11y),
                    Toast.LENGTH_LONG,
                ).show()
                activity.startActivity(Intent(ACTION_ACCESSIBILITY_SETTINGS))
            }
            Item.BACKGROUND -> if (!runCatching { activity.startActivity(Intent(ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }.isSuccess) {
                Toast.makeText(activity, R.string.ash_default_app_manual, Toast.LENGTH_LONG).show()
            }
            Item.WAKE_WORD, Item.SPEECH, Item.VOICE -> Downloads.start(activity.applicationContext, item)
        }
    }

    fun arm(activity: Activity, explain: Boolean) {
        if (!isDone(activity, Item.MICROPHONE)) {
            act(activity, Item.MICROPHONE)
            return
        }
        if (!JarvisService.notificationsAvailable(activity)) {
            Toast.makeText(activity, R.string.ash_arm_notifications, Toast.LENGTH_LONG).show()
            runCatching {
                activity.startActivity(Intent(ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, activity.packageName))
            }
            return
        }
        if (!isDone(activity, Item.WAKE_WORD) || !isDone(activity, Item.SPEECH)) {
            Toast.makeText(activity, R.string.ash_arm_models, Toast.LENGTH_LONG).show()
            (activity as? MainActivity)?.goTo(R.id.nav_settings)
            return
        }
        fun start() {
            if (!JarvisService.start(activity)) {
                Toast.makeText(activity, R.string.ash_start_refused, Toast.LENGTH_LONG).show()
            }
        }
        if (explain) AlertDialog.Builder(activity)
            .setTitle(R.string.ash_arm)
            .setMessage(R.string.ash_arm_explanation)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.allow) { _, _ -> start() }
            .show()
        else start()
    }

    private fun isAccessibilityEnabled(context: Context): Boolean {
        if (JarvisAccessibilityService.instance != null) return true
        val enabled = Secure.getString(context.contentResolver, Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(context, JarvisAccessibilityService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    /** Adds one row per item to [container]; call the returned function to refresh them. */
    fun bindRows(activity: Activity, container: ViewGroup, items: List<Item>): () -> Unit {
        val rows = items.map { item ->
            val row = ItemCheckBinding.inflate(LayoutInflater.from(activity), container, true)
            row.icon.setImageResource(item.icon)
            row.title.setText(item.title)
            row.subtitle.setText(item.subtitle)
            row.action.setText(item.action)
            row.action.setOnClickListener { act(activity, item) }
            item to row
        }
        return {
            for ((item, row) in rows) {
                val progress = Downloads.progress[item]
                val done = isDone(activity, item) && progress == null
                row.done.visibility = if (done) View.VISIBLE else View.GONE
                row.action.visibility = if (done || progress != null) View.GONE else View.VISIBLE
                row.progress.visibility = if (progress != null) View.VISIBLE else View.GONE
                if (progress != null) {
                    row.progress.setProgressCompat(progress, true)
                    row.subtitle.text = if (progress >= 90) activity.getString(R.string.unpacking) else activity.getString(R.string.downloading_percent, progress)
                } else {
                    row.subtitle.text = Downloads.errors[item] ?: activity.getString(item.subtitle)
                }
                row.icon.alpha = if (done) 1f else 0.6f
            }
        }
    }
}

/** Model downloads keep running (and reporting) when the user moves between pages. */
object Downloads {
    val progress = mutableMapOf<Setup.Item, Int>()
    val errors = mutableMapOf<Setup.Item, String>()
    private val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }
    private fun notifyListeners() = listeners.toList().forEach { it() }

    fun start(context: Context, item: Setup.Item) {
        if (progress.containsKey(item)) return
        progress[item] = 0
        errors.remove(item)
        notifyListeners()
        Thread {
            val report: (Int) -> Unit = { p -> main.post { if (progress.containsKey(item)) { progress[item] = p; notifyListeners() } } }
            val error = try {
                when (item) {
                    Setup.Item.VOICE -> ModelDownloader.downloadKokoro(context, report)
                    Setup.Item.SPEECH -> ModelDownloader.downloadSpeech(context, report)
                    else -> ModelDownloader.downloadWakeWord(context, report)
                }
                null
            } catch (e: Throwable) {
                context.getString(R.string.download_failed, e.message ?: e.javaClass.simpleName)
            }
            if (error == null && item == Setup.Item.VOICE) {
                val settings = com.jarvis.assistant.Settings(context)
                Kokoro.prerender(context, settings.kokoroSpeaker, settings.voiceSpeed)
            }
            main.post {
                progress.remove(item)
                if (error != null) errors[item] = error
                notifyListeners()
                Toast.makeText(context, error ?: context.getString(R.string.item_installed, context.getString(item.title)), Toast.LENGTH_SHORT).show()
            }
        }.start()
    }
}
