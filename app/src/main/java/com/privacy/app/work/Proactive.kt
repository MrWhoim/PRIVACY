package com.privacy.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.privacy.app.AppGraph
import com.privacy.app.AppState
import com.privacy.app.PrivacyApp
import com.privacy.app.data.TaskStatus
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Lets the assistant speak first. Runs every ~30 minutes in the background, costs no API calls (the lines come from the user's own tasks)
 * and stays quiet when: the setting is off, the app is open or was used in the last 15 minutes (an active conversation), it is night,
 * a message was sent in the last 4 hours, or 3 were already sent today. The message lands in the chat – continuing the latest chat
 * when it is recent, otherwise in a new one – and optionally as a notification.
 */
class ProactiveWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val g = (applicationContext as PrivacyApp).graph
        runCatching { Proactive.maybeSpeak(g, applicationContext) }
        return Result.success()
    }
}

object Proactive {
    private const val MIN_GAP_MS = 4 * 3600_000L
    private const val ACTIVE_MS = 15 * 60_000L
    private const val MAX_PER_DAY = 3

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<ProactiveWorker>(30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("proactive", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    suspend fun maybeSpeak(g: AppGraph, ctx: Context) {
        val s = g.settings
        if (!com.privacy.app.ai.Engagement.ok(g)) return
        // "I'm Feeling Distracted" mode talks every ~30 minutes, even while the proactive check-ins are off
        if (s.distractedMode.value && !AppState.foreground) { if (com.privacy.app.ai.Distracted.maybeNudge(g, ctx, 25 * 60_000L, notify = true)) return }
        if (!s.proactiveOn.value || AppState.foreground) return
        val now = System.currentTimeMillis()
        if (now - s.lastActiveAt < ACTIVE_MS) return
        if (now - s.lastProactiveAt < MIN_GAP_MS) return
        val ldt = LocalDateTime.now()
        if (ldt.hour >= 22 || ldt.hour < 8) return
        if (s.proactiveCountToday() >= MAX_PER_DAY) return
        // someone just chatted from a widget or the face screen
        val cur = s.currentConversation.value
        val convo = if (cur > 0) g.db.conversationDao().get(cur) else null
        if (convo != null && now - convo.updatedAt < ACTIVE_MS) return

        // health nudges (water, meals, movement, sleep) come first – written by the AI from today's real numbers
        if (s.healthAlertsOn.value && now - s.lastHealthAlertAt > 90 * 60_000L) {
            if (com.privacy.app.ai.HealthCoach.maybeAlert(g, ctx, s.proactiveNotify.value)) { s.lastHealthAlertAt = now; return }
        }
        val call = s.userCall.value
        val first = s.userName.value.trim().split(" ").firstOrNull().orEmpty()
        val who = if (first.isBlank()) call else "$first ${call.lowercase()}"
        val tasks = g.db.taskDao().activeNow()
        val open = tasks.filter { it.status != TaskStatus.COMPLETED }
        val startOfToday = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val doneToday = tasks.count { it.status == TaskStatus.COMPLETED && (it.completedAt ?: 0L) >= startOfToday }
        val today = LocalDate.now().toEpochDay()
        val overdue = open.count { it.dueDay != null && it.dueDay < today }
        val awayH = if (s.lastActiveAt <= 0) 0 else (now - s.lastActiveAt) / 3600_000L

        // pick one thing worth saying (each kind at most once a day)
        val (kind, title, text) = when {
            doneToday > 0 && open.isEmpty() && s.proactiveKindDone("done") ->
                Triple("done", "All done today", "$who, you finished everything on today's list. Well done. Want to plan tomorrow, or take the evening off?")
            overdue > 0 && s.proactiveKindDone("overdue") ->
                Triple("overdue", "Overdue tasks", "$who, $overdue task${if (overdue > 1) "s are" else " is"} overdue. Shall we move ${if (overdue > 1) "them" else "it"} or finish ${if (overdue > 1) "them" else "it"} now?")
            open.isNotEmpty() && ldt.hour >= 16 && s.proactiveKindDone("unfinished") ->
                Triple("unfinished", "Unfinished work", "$who, you left some work unfinished: ${open.take(3).joinToString(", ") { "“${it.title.take(40)}”" }}${if (open.size > 3) " and ${open.size - 3} more" else ""}. Want to tackle the smallest one together?")
            awayH >= s.proactiveIdleHours.value && s.proactiveKindDone("away") ->
                Triple("away", "Checking in", "It's been a while, $who. Anything I can take off your plate – a plan, a reminder, a quick question?")
            else -> return
        }

        // a recent chat continues; otherwise open a fresh one
        if (convo == null || now - convo.updatedAt > 24 * 3600_000L || g.db.chatDao().count(convo.id) == 0) {
            g.chatService.startNewChat()
            val id = g.chatService.ensureConversation()
            g.db.conversationDao().get(id)?.let { g.db.conversationDao().update(it.copy(title = "Check-in · $title")) }
        }
        g.chatService.addAssistant(text)
        s.markProactive(kind)
        if (s.proactiveNotify.value) Notifier.post(ctx, 1500 + kind.hashCode().mod(50), Notifier.CH_COMPANION, title, text, text, target = "chat")
    }
}
