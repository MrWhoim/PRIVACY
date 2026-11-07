package com.privacy.app.work

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import kotlinx.coroutines.sync.withLock
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.OutOfQuotaPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.privacy.app.AppGraph
import com.privacy.app.PrivacyApp
import com.privacy.app.MainActivity
import com.privacy.app.R
import com.privacy.app.data.NoteEntity
import com.privacy.app.data.RoutineEntity
import com.privacy.app.data.TaskStatus
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

object Notifier {
    const val CH_MORNING = "morning"
    const val CH_ROUTINE = "routines"
    const val CH_AUTOMATION = "automations"
    const val CH_WORK = "working"
    const val CH_COMPANION = "companion"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        fun ch(id: String, name: String, imp: Int) = nm.createNotificationChannel(NotificationChannel(id, name, imp).apply {
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        })
        ch(CH_MORNING, "Morning briefing", NotificationManager.IMPORTANCE_DEFAULT)
        ch(CH_ROUTINE, "Daily routines", NotificationManager.IMPORTANCE_DEFAULT)
        ch(CH_AUTOMATION, "Reminders & automations", NotificationManager.IMPORTANCE_HIGH)
        ch(CH_COMPANION, "Assistant check-ins", NotificationManager.IMPORTANCE_DEFAULT)
        nm.createNotificationChannel(NotificationChannel(CH_WORK, "Background work", NotificationManager.IMPORTANCE_LOW))
    }

    /** Shown only while an expedited job (voice question, automation) is running on older Android versions. */
    fun workingNotification(ctx: Context): android.app.Notification =
        NotificationCompat.Builder(ctx, CH_WORK)
            .setSmallIcon(R.drawable.ic_stat_privacy)
            .setContentTitle("${com.privacy.app.AppInfo.NAME} is working…")
            .setOngoing(true)
            .build()

    @SuppressLint("MissingPermission")
    fun post(ctx: Context, id: Int, channel: String, title: String, text: String, big: String? = null, target: String = "chat") {
        val manager = NotificationManagerCompat.from(ctx)
        if (!manager.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            ctx, id,
            Intent(ctx, MainActivity::class.java).putExtra("open_screen", target).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_privacy)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
        if (big != null) b.setStyle(NotificationCompat.BigTextStyle().bigText(big))
        manager.notify(id, b.build())
    }
}

/** Mirrors your Make "morning" scenario: completed daily tasks → Not started, other completed tasks → archived. */
object DailyReset {
    suspend fun runIfNeeded(g: AppGraph) {
        val today = LocalDate.now().toEpochDay()
        val last = g.settings.lastResetDay
        if (last == today) return
        if (last != -1L) {
            g.db.taskDao().resetDaily()
            g.db.taskDao().resetMultiDay()
            g.db.taskDao().archiveCompleted()
        }
        g.settings.lastResetDay = today
        g.taskRepo.refreshWidget()
    }
}

object RoutineRunner {
    /** Returns null on success, or an error text. The answer is saved as a note. */
    suspend fun run(g: AppGraph, r: RoutineEntity): String? {
        // Routines are now run by the full agent, so they can search the web and read pages.
        val res = g.agent.runTask(r.prompt)
        if (res.error != null) return res.error
        val today = LocalDate.now()
        g.db.noteDao().insert(NoteEntity(title = com.privacy.app.ai.NoteRules.tagged("Routine", "${r.name} · ${today.format(DateTimeFormatter.ofPattern("d MMM"))}"), body = res.text))
        g.db.routineDao().update(r.copy(lastRunDay = today.toEpochDay()))
        return null
    }
}

class MorningWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun getForegroundInfo(): androidx.work.ForegroundInfo = androidx.work.ForegroundInfo(7002, Notifier.workingNotification(applicationContext))

    companion object { private val lock = kotlinx.coroutines.sync.Mutex() }

    /** The alarm, the daily job and the app-open catch-up can all start this at once: only one may run, and the notification goes out once a day. */
    override suspend fun doWork(): Result = lock.withLock { run() }

    private suspend fun run(): Result {
        val g = (applicationContext as PrivacyApp).graph
        if (!com.privacy.app.ai.Engagement.ok(g)) return Result.success()   // brand-new user: nothing to say yet
        // already sent today (the alarm and the app-open catch-up may both fire)
        if (g.settings.lastBriefDay == LocalDate.now().toEpochDay()) return Result.success()
        // Look at yesterday BEFORE the daily reset clears it.
        val yesterday = LocalDate.now().minusDays(1).toEpochDay()
        val hist = runCatching { g.db.historyDao().forDay(yesterday) }.getOrDefault(emptyList())
        val leftovers = g.db.taskDao().open()
        DailyReset.runIfNeeded(g)
        val open = g.db.taskDao().open()
        val name = g.settings.userCall.value
        val fallbackText = if (open.isEmpty()) "Nothing pending. Enjoy the day." else "${open.size} things on your list today"
        val big = open.take(12).joinToString("\n") { (if (it.status == TaskStatus.IN_PROGRESS) "◐ " else "☐ ") + it.title }
        var text = fallbackText
        var bigText: String? = if (open.isEmpty()) null else big
        if (g.settings.aiReady()) {
            val doneY = hist.filter { it.status == TaskStatus.COMPLETED.name }.joinToString("; ") { it.title }.ifBlank { "nothing recorded" }
            val missedY = hist.filter { it.status != TaskStatus.COMPLETED.name }.joinToString("; ") { it.title }.ifBlank { "nothing" }
            val todayList = open.joinToString("; ") { it.title + if (it.status == TaskStatus.IN_PROGRESS) " (in progress)" else "" }.ifBlank { "nothing yet" }
            val left = leftovers.filter { it.dueDay != null && it.dueDay < LocalDate.now().toEpochDay() }.joinToString("; ") { it.title }.ifBlank { "none" }
            val t0 = LocalDate.now().toEpochDay()
            val week = runCatching { g.reports.facts(t0 - 7, t0 - 1) }.getOrDefault("")
            val insights = runCatching { g.reports.insights(t0 - 1).joinToString("\n") { "- ${it.observed} (${it.evidence})" } }.getOrDefault("")
            val prompt = "Write my morning briefing as a Markdown note. Sections: ## Yesterday (one or two lines on what I finished and what I missed), ## Today (a realistic plan in order, using my open tasks), ## Leftovers (what carried over and what to do about it), " +
                "## What is going well (specific things the last 7 days show I do well, and how to make them even better), ## What keeps going wrong (the pattern I repeat – tasks, health, distracting apps – with evidence from the data, and a concrete fix for each), ## Tip (one practical suggestion for today). " +
                "Be warm, honest and concrete, no filler, no medical diagnosis, say plainly if the data is too thin. Under 320 words.\n" +
                "Finished yesterday: $doneY\nNot finished yesterday: $missedY\nOpen tasks today: $todayList\nOverdue: $left\n" +
                "LAST 7 DAYS (efficiency / health / focus):\n$week\nRULE-BASED PATTERNS:\n$insights\nReply with the note only. Do not end with a question or an offer (no 'would you like me to…'): this is saved automatically and nobody replies."
            val r = runCatching { kotlinx.coroutines.withContext(com.privacy.app.credits.CreditLabel("Morning brief")) { com.privacy.app.ai.Agent(g).oneShot(prompt) } }.getOrNull()
            if (r != null && r.error == null && r.text.isNotBlank()) {
                val stamp = LocalDate.now().format(DateTimeFormatter.ofPattern("d MMM"))
                g.db.noteDao().insert(NoteEntity(title = com.privacy.app.ai.NoteRules.tagged("Morning Briefing", stamp), body = r.text))
                g.settings.lastBriefDay = LocalDate.now().toEpochDay()
                val plain = r.text.replace(Regex("[*#`>]"), "").replace(Regex("\n+"), " · ").trim()
                text = plain.take(110)
                bigText = plain.take(900)
            }
        }
        val ms = applicationContext.getSharedPreferences("morning_state", Context.MODE_PRIVATE)
        val todayKey = LocalDate.now().toEpochDay()
        // a second run the same day (e.g. the real brief written after an offline first try) saves the note but never rings again
        if (ms.getLong("notified_day", -1L) != todayKey) {
            Notifier.post(applicationContext, 1001, Notifier.CH_MORNING, "Good morning, $name", text, bigText, target = if (bigText != null && bigText != big) "notes" else "tasks")
            ms.edit().putLong("notified_day", todayKey).apply()
        }
        // no key → nothing more to try. With a key but no internet the short list above was sent; the app-open catch-up writes the real brief later.
        if (!g.settings.aiReady()) g.settings.lastBriefDay = LocalDate.now().toEpochDay()
        Scheduler.enqueueRoutines(applicationContext)
        return Result.success()
    }
}

class RoutinesWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val g = (applicationContext as PrivacyApp).graph
        val today = LocalDate.now().toEpochDay()
        val due = g.db.routineDao().enabled().filter { it.lastRunDay != today }
        if (due.isEmpty() || !g.settings.aiReady()) return Result.success()
        var failed = false
        for (r in due) {
            val err = RoutineRunner.run(g, r)
            if (err == null) {
                Notifier.post(applicationContext, 2000 + r.id.toInt(), Notifier.CH_ROUTINE, "Routine ready: ${r.name}", "Saved to your Notes.", target = "notes")
            } else failed = true
        }
        return if (failed && runAttemptCount < 4) Result.retry() else Result.success()
    }
}

/** Starts a worker right away. "Expedited" = runs promptly even in the background; falls back to normal if the quota is used up. */
object Jobs {
    inline fun <reified W : ListenableWorker> expedited(ctx: Context, data: Data, uniqueName: String? = null) {
        val req = OneTimeWorkRequestBuilder<W>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(data)
            .build()
        val wm = WorkManager.getInstance(ctx)
        if (uniqueName == null) wm.enqueue(req) else wm.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }
}

object Scheduler {
    private fun morningPi(ctx: Context) = PendingIntent.getBroadcast(ctx, 7003, Intent(ctx, MorningAlarmReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** An exact alarm fires the brief at its time even when the app is closed; the periodic job below stays as a back-up. */
    fun scheduleMorningAlarm(ctx: Context, enabled: Boolean, minutes: Int) {
        val am = ctx.getSystemService(android.app.AlarmManager::class.java)
        am.cancel(morningPi(ctx))
        if (!enabled) return
        val now = LocalDateTime.now()
        var target = now.toLocalDate().atTime(minutes / 60, minutes % 60)
        if (!target.isAfter(now)) target = target.plusDays(1)
        val at = target.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        runCatching {
            if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, morningPi(ctx))
            else am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, morningPi(ctx))
        }.onFailure { runCatching { am.set(android.app.AlarmManager.RTC_WAKEUP, at, morningPi(ctx)) } }
    }

    /** App opened: if today's brief is due (its time has passed) and was not sent yet – e.g. there was no internet – send it now. */
    suspend fun catchUpMorning(g: AppGraph) {
        val s = g.settings
        if (!s.morningEnabled.value || !com.privacy.app.ai.Engagement.ok(g)) return
        val nowMin = LocalDateTime.now().let { it.hour * 60 + it.minute }
        if (nowMin < s.morningMinutes.value || s.lastBriefDay == LocalDate.now().toEpochDay()) return
        if (!com.privacy.app.ai.Net.online()) return
        Jobs.expedited<MorningWorker>(g.context, Data.EMPTY, uniqueName = "morning_now")
    }

    fun scheduleMorning(ctx: Context, enabled: Boolean, minutes: Int, replace: Boolean) {
        val wm = WorkManager.getInstance(ctx)
        scheduleMorningAlarm(ctx, enabled, minutes)
        if (!enabled) {
            wm.cancelUniqueWork("morning")
            return
        }
        val now = LocalDateTime.now()
        var target = now.toLocalDate().atTime(minutes / 60, minutes % 60)
        if (!target.isAfter(now)) target = target.plusDays(1)
        val delay = Duration.between(now, target).toMillis()
        val req = PeriodicWorkRequestBuilder<MorningWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        wm.enqueueUniquePeriodicWork(
            "morning",
            if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP,
            req
        )
    }

    /** Runs the daily routines as soon as the phone has internet. */
    fun enqueueRoutines(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<RoutinesWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("routines", ExistingWorkPolicy.REPLACE, req)
    }
}

/** Exact morning alarm → runs the brief now and books tomorrow's alarm. */
class MorningAlarmReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val g = (context.applicationContext as PrivacyApp).graph
        Jobs.expedited<MorningWorker>(context, Data.EMPTY, uniqueName = "morning_now")
        Scheduler.scheduleMorningAlarm(context, g.settings.morningEnabled.value, g.settings.morningMinutes.value)
    }
}
