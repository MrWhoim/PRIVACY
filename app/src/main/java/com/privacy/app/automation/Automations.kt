package com.privacy.app.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.privacy.app.AppGraph
import com.privacy.app.ai.NoteRules
import com.privacy.app.PrivacyApp
import com.privacy.app.data.AutoAction
import com.privacy.app.data.AutomationEntity
import com.privacy.app.data.NoteEntity
import com.privacy.app.data.TaskStatus
import com.privacy.app.work.Jobs
import com.privacy.app.work.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Works out when automations fire and registers exact alarms with Android. */
object AutomationScheduler {
    const val EXTRA_ID = "automation_id"
    /** An AI task whose message starts with this also turns its result into an audio file. */
    const val AUDIO_TAG = "[[audio]]"

    // ---------- time maths ----------
    fun nextTrigger(a: AutomationEntity, from: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime? {
        val time = LocalTime.of((a.timeMinutes / 60).coerceIn(0, 23), (a.timeMinutes % 60).coerceIn(0, 59))
        val zone = ZoneId.systemDefault()
        if (a.dateDay != null) {
            val t = ZonedDateTime.of(LocalDate.ofEpochDay(a.dateDay), time, zone)
            return if (t.isAfter(from)) t else null
        }
        if ((a.daysMask and 127) == 0) return null
        for (d in 0..7) {
            val date = from.toLocalDate().plusDays(d.toLong())
            val bit = 1 shl (date.dayOfWeek.value - 1) // Monday = bit 0
            if ((a.daysMask and bit) == 0) continue
            val t = ZonedDateTime.of(date, time, zone)
            if (t.isAfter(from)) return t
        }
        return null
    }

    fun describe(a: AutomationEntity): String {
        val time = LocalTime.of(a.timeMinutes / 60, a.timeMinutes % 60).format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
        val kind = when (a.action) {
            AutoAction.NOTIFY_TASKS.name -> "task list"
            AutoAction.AI_PROMPT.name -> if (a.message.startsWith(AUDIO_TAG)) "AI task + audio" else "AI task"
            AutoAction.NOTES_AUDIO.name -> "notes audio"
            else -> "reminder"
        }
        val whenText = when {
            a.dateDay != null -> {
                val today = LocalDate.now().toEpochDay()
                when (a.dateDay) {
                    today -> "today"
                    today + 1 -> "tomorrow"
                    else -> LocalDate.ofEpochDay(a.dateDay).format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
                }
            }
            (a.daysMask and 127) == 127 -> "every day"
            (a.daysMask and 127) == 31 -> "weekdays"
            (a.daysMask and 127) == 96 -> "weekends"
            else -> (0..6).filter { (a.daysMask and (1 shl it)) != 0 }
                .joinToString(", ") { DayOfWeek.of(it + 1).getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
        }
        return "$kind · $time · $whenText"
    }

    fun parseDays(s: String?): Int {
        val v = s?.trim()?.lowercase().orEmpty()
        if (v.isEmpty()) return 0
        if (v in setOf("daily", "everyday", "every day", "all")) return 127
        if (v == "weekdays") return 31
        if (v == "weekends") return 96
        var mask = 0
        val names = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
        v.split(',', ' ', '/').map { it.trim() }.filter { it.isNotEmpty() }.forEach { token ->
            names.forEachIndexed { i, n -> if (token.startsWith(n)) mask = mask or (1 shl i) }
        }
        return mask
    }

    /** Accepts "19:00", "7:30 pm", "7pm", "07.15". Returns minutes after midnight or null. */
    fun parseTime(s: String?): Int? {
        val m = Regex("""^\s*(\d{1,2})(?:[:.](\d{2}))?\s*([ap])?\.?m?\.?\s*$""", RegexOption.IGNORE_CASE).find(s.orEmpty()) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val ap = m.groupValues[3].lowercase()
        if (ap == "p" && h < 12) h += 12
        if (ap == "a" && h == 12) h = 0
        if (h !in 0..23 || min !in 0..59) return null
        return h * 60 + min
    }

    // ---------- alarms ----------
    fun canExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    private fun pending(ctx: Context, id: Long): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, id.toInt(),
            Intent(ctx, AutomationReceiver::class.java).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    /** Registers the next run. Returns when it will happen, or null if there is none. */
    fun schedule(ctx: Context, a: AutomationEntity): ZonedDateTime? {
        cancel(ctx, a.id)
        if (!a.enabled) return null
        val next = nextTrigger(a) ?: return null
        val am = ctx.getSystemService(AlarmManager::class.java)
        val at = next.toInstant().toEpochMilli()
        val pi = pending(ctx, a.id)
        try {
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        return next
    }

    fun cancel(ctx: Context, id: Long) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, id))
    }

    suspend fun rescheduleAll(g: AppGraph) {
        g.db.automationDao().all().forEach { schedule(g.context, it) }
    }

    /** Runs the automation once the internet is back and then rewrites the placeholder note [noteId] with the real result. */
    private fun retryPrefs(ctx: Context) = ctx.getSharedPreferences("auto_retry", Context.MODE_PRIVATE)
    /** Retries that are still waiting (automation id : placeholder note id). Started again whenever the app opens. */
    fun resumePending(ctx: Context) {
        val p = retryPrefs(ctx)
        p.all.keys.toList().forEach { k ->
            val id = k.substringBefore(':').toLongOrNull() ?: return@forEach; val note = k.substringAfter(':').toLongOrNull() ?: return@forEach
            enqueueRetry(ctx, id, note)
        }
    }
    fun retryDone(ctx: Context, noteId: Long) { retryPrefs(ctx).edit().apply { retryPrefs(ctx).all.keys.filter { it.endsWith(":$noteId") }.forEach { remove(it) } }.apply() }
    fun retryWhenOnline(ctx: Context, id: Long, noteId: Long) {
        retryPrefs(ctx).edit().putBoolean("$id:$noteId", true).apply()
        enqueueRetry(ctx, id, noteId)
    }
    private fun enqueueRetry(ctx: Context, id: Long, noteId: Long) {
        val req = androidx.work.OneTimeWorkRequestBuilder<AutomationWorker>()
            .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
            .setInputData(Data.Builder().putLong(EXTRA_ID, id).putLong("note_id", noteId).build())
            .build()
        androidx.work.WorkManager.getInstance(ctx).enqueueUniqueWork("retry_$noteId", androidx.work.ExistingWorkPolicy.KEEP, req)
    }

    fun enqueueRun(ctx: Context, id: Long) {
        Jobs.expedited<AutomationWorker>(ctx, Data.Builder().putLong(EXTRA_ID, id).build())
    }

    // ---------- the work itself ----------
    /** Runs one automation now. Returns a short human-readable result. */
    private val running = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /** [placeholder] = the "waiting for internet" note to fill in; the result then goes into it instead of a second note. */
    suspend fun execute(g: AppGraph, a: AutomationEntity, placeholder: Long = -1L): String {
        // one run per automation at a time: a late alarm, a retry and "Run now" can never produce two notes together
        if (a.action == AutoAction.AI_PROMPT.name && !running.add(a.id)) return "Already running."
        try { return executeOnce(g, a, placeholder) } finally { if (a.action == AutoAction.AI_PROMPT.name) running.remove(a.id) }
    }

    /** Saves the result: into the placeholder note when this is a retry, otherwise as a new note. */
    private suspend fun saveResult(g: AppGraph, placeholder: Long, title: String, body: String) {
        val old = if (placeholder > 0) g.db.noteDao().get(placeholder) else null
        if (old != null) g.db.noteDao().update(old.copy(title = title, body = body, updatedAt = System.currentTimeMillis()))
        else if (placeholder <= 0) g.db.noteDao().insert(NoteEntity(title = title, body = body))
        // placeholder > 0 but the user deleted it: they don't want it, so no note is made
    }

    private suspend fun executeOnce(g: AppGraph, a: AutomationEntity, placeholder: Long): String {
        val ctx = g.context
        val nid = 3000 + (a.id % 5000).toInt()
        return when (a.action) {
            AutoAction.NOTIFY_TASKS.name -> {
                val open = g.db.taskDao().open()
                val lead = a.message.trim()
                val text = when {
                    open.isEmpty() -> lead.ifBlank { "No open tasks. Nice." }
                    else -> (if (lead.isNotBlank()) "$lead · " else "") + "${open.size} open task" + if (open.size == 1) "" else "s"
                }
                val big = open.take(15).joinToString("\n") { (if (it.status == TaskStatus.IN_PROGRESS) "◐ " else "☐ ") + it.title }
                Notifier.post(ctx, nid, Notifier.CH_AUTOMATION, a.name.ifBlank { "Your tasks" }, text, if (open.isEmpty()) null else big, target = "tasks")
                "Sent the task list."
            }
            AutoAction.NOTES_AUDIO.name -> NotesAudio.run(g, a)
            AutoAction.AI_PROMPT.name -> {
                if (!g.settings.aiReady()) {
                    Notifier.post(ctx, nid, Notifier.CH_AUTOMATION, a.name, "Couldn't run: no AI key is set up.")
                    return "No AI key."
                }
                val prompt = a.message.removePrefix(AUDIO_TAG).trim()
                // the user simply says it in the task: "…and save only the audio", "also make an audio file of it"
                // sound is made ONLY when the "Also create audio" switch of this automation is on
                val wantAudio = a.message.startsWith(AUDIO_TAG)
                val audioOnly = wantAudio && Regex("(?i)(only|just)\\s+(the\\s+)?(audio|voice|mp3)|audio\\s+only").containsMatchIn(prompt)
                val stamp = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault()))
                if (!com.privacy.app.ai.Net.online()) {
                    if (placeholder > 0 || retryPrefs(ctx).all.keys.any { it.startsWith("${a.id}:") }) return "Waiting for internet."
                    // no internet: tell the user simply, keep a placeholder note, and finish it as soon as the internet is back
                    val title = NoteRules.tagged("Automation", "${a.name} · $stamp")
                    val nid2 = g.db.noteDao().insert(NoteEntity(title = title, body = "Internet was turned off, so the automation couldn't start. It will run again automatically when the internet is back and this note will be replaced with the result."))
                    Notifier.post(ctx, nid, Notifier.CH_AUTOMATION, a.name, "Internet was turned off, automation couldn't start. Retry again when internet switches on.", target = "notes")
                    AutomationScheduler.retryWhenOnline(ctx, a.id, nid2)
                    return "Waiting for internet."
                }
                val res = g.agent.runTask(prompt)
                if (res.error != null) {
                    // keep a record in Notes too, so a failed run is not silent
                    saveResult(g, placeholder, NoteRules.tagged("Automation", "${a.name} · $stamp · failed"), "The automation could not finish.\n\n> ${res.error}\n\nPrompt: $prompt")
                    Notifier.post(ctx, nid, Notifier.CH_AUTOMATION, a.name, "Couldn't finish: ${res.error.take(140)}", target = "notes")
                    "Failed: ${res.error}"
                } else {
                    val noteTitle = NoteRules.tagged("Automation", "${a.name} · $stamp")
                    saveResult(g, placeholder, noteTitle, res.text)
                    if (wantAudio) {
                        // a hidden one-time "notes audio" job reads exactly this note into an audio file (it keeps going by itself if it is rate-limited)
                        val id = g.db.automationDao().insert(AutomationEntity(name = (if (audioOnly) "AudioOnly · " else "Audio · ") + a.name, action = AutoAction.NOTES_AUDIO.name, message = "${a.name} · $stamp",
                            timeMinutes = 0, daysMask = 0, dateDay = LocalDate.now().toEpochDay(), enabled = false))
                        AutomationScheduler.enqueueRun(ctx, id)
                    }
                    Notifier.post(ctx, nid, Notifier.CH_AUTOMATION, a.name, res.text.replace(Regex("[*#`]"), "").take(140), res.text.replace(Regex("[*#`]"), "").take(900), target = "notes")
                    "Done – saved to Notes."
                }
            }
            else -> {
                Notifier.post(ctx, nid, Notifier.CH_AUTOMATION, a.name.ifBlank { "Reminder" }, a.message.ifBlank { a.name })
                "Reminder sent."
            }
        }
    }
}

/** Alarm → starts the worker, and immediately books the next repeat so it can never be lost. */
class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(AutomationScheduler.EXTRA_ID, -1L)
        if (id < 0) return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val g = (app as PrivacyApp).graph
                val a = g.db.automationDao().get(id)
                if (a != null && a.enabled) {
                    AutomationScheduler.enqueueRun(app, id)
                    if (a.dateDay == null) AutomationScheduler.schedule(app, a)
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/** After a reboot, app update or time-zone/clock change, alarms are gone – book them again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val g = (app as PrivacyApp).graph
                AutomationScheduler.rescheduleAll(g)
                com.privacy.app.work.Scheduler.scheduleMorningAlarm(app, g.settings.morningEnabled.value, g.settings.morningMinutes.value)
            } finally {
                pending.finish()
            }
        }
    }
}

class AutomationWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(7001, Notifier.workingNotification(applicationContext))

    override suspend fun doWork(): Result {
        val g = (applicationContext as PrivacyApp).graph
        val id = inputData.getLong(AutomationScheduler.EXTRA_ID, -1L)
        val a = g.db.automationDao().get(id) ?: return Result.success()
        val placeholder = inputData.getLong("note_id", -1L)
        try {
            if (placeholder > 0 && !com.privacy.app.ai.Net.online()) return Result.retry()
            val r = AutomationScheduler.execute(g, a, placeholder)
            if (placeholder > 0 && r != "Waiting for internet." && r != "Already running.") AutomationScheduler.retryDone(applicationContext, placeholder)
            if (placeholder > 0 && r == "Waiting for internet.") return Result.retry()
        } catch (e: Exception) {
            Notifier.post(applicationContext, 3000 + (a.id % 5000).toInt(), Notifier.CH_AUTOMATION, a.name, "Couldn't run: ${e.message ?: "unknown error"}")
        }
        val now = System.currentTimeMillis()
        // One-time automations switch themselves off after running.
        g.db.automationDao().update(a.copy(lastRunAt = now, enabled = if (a.dateDay != null) false else a.enabled))
        return Result.success()
    }
}

fun AutomationEntity.nextRunText(): String? =
    AutomationScheduler.nextTrigger(this)?.format(DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.getDefault()))
