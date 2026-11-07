package com.privacy.app.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Redraws every home-screen widget from the current data: when the app opens, and every 15 minutes in the background. */
object WidgetSync {
    suspend fun all(ctx: Context) {
        runCatching { TaskWidget().updateAll(ctx) }
        runCatching { HealthWidget().updateAll(ctx) }
        runCatching { FocusWidget().updateAll(ctx) }
        runCatching { NoteViewWidget().updateAll(ctx) }
        runCatching { NoteWidget().updateAll(ctx) }
    }

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<WidgetSyncWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("widget_sync", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

class WidgetSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result { WidgetSync.all(applicationContext); return Result.success() }
}
