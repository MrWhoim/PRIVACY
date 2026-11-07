package com.privacy.app

import android.content.Context
import java.io.File

/** Saves the stack trace of a crash, so the next start can show it (with a Copy button) instead of failing silently. */
object CrashLog {
    private fun file(c: Context) = File(c.filesDir, "last_crash.txt")

    fun install(c: Context) {
        val app = c.applicationContext
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                file(app).writeText("Thread: ${t.name}\nTime: ${java.util.Date()}\n\n" + android.util.Log.getStackTraceString(e))
            }
            old?.uncaughtException(t, e)
        }
    }

    /** Returns the saved crash (once) and clears it. */
    fun take(c: Context): String? = runCatching {
        val f = file(c)
        if (!f.exists()) null else f.readText().also { f.delete() }
    }.getOrNull()
}
