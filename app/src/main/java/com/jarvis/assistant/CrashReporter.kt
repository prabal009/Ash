package com.jarvis.assistant

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Keeps a report of the last crash so the user can share it, since we can't attach a debugger to their phone. */
object CrashReporter {
    private fun file(context: Context) = File(context.filesDir, "crash_locations.txt")
    private fun prefs(context: Context) = context.getSharedPreferences("crash", Context.MODE_PRIVATE)

    fun install(app: Application) {
        // Reports from older versions can contain raw exception messages; never offer to share them.
        File(app.filesDir, "last_crash.txt").delete()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                // Exception messages, thread names and ANR traces can contain request payloads.
                // Export diagnostic locations, never free-form application or provider data.
                file(app).writeText(buildString {
                    append(header(app))
                    var cause: Throwable? = error
                    repeat(4) {
                        val current = cause ?: return@buildString
                        appendLine(current.javaClass.name)
                        current.stackTrace.take(24).forEach { frame ->
                            append("  at ").append(frame.className).append('.').append(frame.methodName)
                                .append(':').appendLine(frame.lineNumber)
                        }
                        cause = current.cause
                    }
                })
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The last unreported crash, including native crashes Android recorded (Android 11+), or null. */
    fun pending(context: Context): String? {
        file(context).takeIf { it.exists() }?.readText()?.let { return it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val am = context.getSystemService(ActivityManager::class.java)
        val exit = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() }.getOrNull() ?: return null
        val crashed = exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
            exit.reason == ApplicationExitInfo.REASON_CRASH || exit.reason == ApplicationExitInfo.REASON_ANR
        if (!crashed || exit.timestamp <= prefs(context).getLong("seen", 0)) return null
        val kind = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
            ApplicationExitInfo.REASON_ANR -> "App not responding"
            else -> "Crash"
        }
        return header(context) + "$kind at ${fmt(exit.timestamp)}\nStatus: ${exit.status}\n"
    }

    fun dismiss(context: Context) {
        file(context).delete()
        prefs(context).edit().putLong("seen", System.currentTimeMillis()).apply()
    }

    private fun header(context: Context): String {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        return "Ash $version crash report\nPhone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "Time: ${fmt(System.currentTimeMillis())}\n"
    }

    private fun fmt(t: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(t))
}

class JarvisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
