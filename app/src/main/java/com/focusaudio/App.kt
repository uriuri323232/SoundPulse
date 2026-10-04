package com.focusaudio

import android.app.Application
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures any uncaught crash to filesDir/last_crash.txt so the next launch can
 * offer to copy or share it. The previous handler still runs, so the app crashes
 * as before — it just leaves a readable report behind.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val when_ = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                File(filesDir, "last_crash.txt").writeText(
                    "SoundPulse ${BuildTag.VERSION}\n" +
                    "זמן: $when_\n" +
                    "מכשיר: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
                    "חוט: ${thread.name}\n\n" + sw.toString()
                )
            }
            prev?.uncaughtException(thread, e)
        }
    }
}

object BuildTag { const val VERSION = "2.0" }
