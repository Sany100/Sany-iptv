package com.sany.iptv

import android.app.Application
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Captures fatal startup errors before MainActivity and CloudStream initialize. */
class SanyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = StringWriter()
                error.printStackTrace(PrintWriter(trace))
                File(filesDir, "sany_last_crash.txt").writeText(
                    "SANY IPTV crash report" +
                        "\nThread: ${thread.name}" +
                        "\nAndroid: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})" +
                        "\nDevice: ${Build.MANUFACTURER} ${Build.MODEL}" +
                        "\nTime: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}" +
                        "\n\n" + trace.toString()
                )
            } catch (writeError: Throwable) {
                Log.e("SANY-CRASH", "Could not save startup crash report", writeError)
            }
            previous?.uncaughtException(thread, error)
        }
        Log.i("SANY-STARTUP", "Application crash logger installed")
    }
}