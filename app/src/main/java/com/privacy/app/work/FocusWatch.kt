package com.privacy.app.work

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.privacy.app.PrivacyApp
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Watches (every ~15 minutes, the shortest period Android allows) whether a distracting app was used in the first two hours after waking up. */
object FocusWatch {
    fun schedule(ctx: Context, on: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        if (!on) { wm.cancelUniqueWork("focus_watch"); return }
        wm.enqueueUniquePeriodicWork("focus_watch", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<FocusWatchWorker>(15, TimeUnit.MINUTES).build())
    }
}

class FocusWatchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val g = (applicationContext as PrivacyApp).graph
        val f = g.focus
        // keeps the Dopamine vs Detox widget fresh (app usage changes without any tap in the app)
        runCatching { com.privacy.app.widget.FocusWidget().updateAll(applicationContext) }
        if (!f.guardOn || !f.hasUsageAccess()) return Result.success()
        val today = LocalDate.now().toEpochDay()
        val now = System.currentTimeMillis()
        val wake = f.wakeMs(today)
        if (now < wake || now > wake + 2 * 3_600_000L) return Result.success()
        val recent = f.sessions(today).filter { it.endAt >= now - 16 * 60_000L }
        if (recent.isEmpty()) return Result.success()
        val names = recent.map { f.appLabel(it.pkg) }.distinct()
        val minutes = recent.sumOf { (it.endAt - maxOf(it.startAt, now - 16 * 60_000L)) / 60_000.0 }.toInt().coerceAtLeast(1)
        val idx = ((now / 900_000L) % 5).toInt()
        val raw = listOf(
            "The first hours decide the day. Put the phone down and start with one real task.",
            "Morning scrolling gives your brain a cheap dopamine hit – focus is harder for the rest of the day.",
            "Still on %s? Close it and drink a glass of water first.",
            "Your best focus is right now. Spend it on the one thing that matters today.",
            "Detox first, feeds later: 20 minutes of real work before %s."
        )[idx]
        val text = raw.replace("%s", names.first())
        Notifier.post(applicationContext, 7300, Notifier.CH_AUTOMATION, "${names.joinToString()} · $minutes min this morning", text, target = "focus")
        return Result.success()
    }
}
