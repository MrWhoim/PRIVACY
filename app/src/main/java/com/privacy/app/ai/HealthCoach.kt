package com.privacy.app.ai

import android.content.Context
import com.privacy.app.AppGraph
import com.privacy.app.work.Notifier
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.LocalTime

/**
 * Health nudges for nutrition, movement, sleep and water – written by the AI from today's real numbers (offline: a plain line),
 * delivered into the latest chat and as a notification. Each kind is sent at most once a day.
 */
object HealthCoach {
    class Alert(val kind: String, val title: String, val facts: String, val fallback: String)

    /** What is worth saying right now (null = nothing). Pure data – no AI call. */
    suspend fun pick(g: AppGraph): Alert? {
        val s = g.settings
        if (!s.healthAlertsOn.value || !Engagement.ok(g)) return null
        val h = LocalTime.now().hour
        val ev = g.health.evaluate(java.time.LocalDate.now().toEpochDay())
        val t = ev.targets
        val waterT = (t.waterMl ?: 2000).toDouble()
        val water = ev.waterMl ?: 0.0
        val kcal = ev.kcal ?: 0.0
        val kcalT = (t.kcal ?: 0).toDouble()
        fun fresh(k: String) = !s.healthAlertSentToday(k)
        return when {
            h in 8..11 && ev.sleepH == null && fresh("sleep_log") ->
                Alert("sleep_log", "Sleep", "Last night's sleep is not logged yet.", "Good morning! How many hours did you sleep last night? Tell me and I'll log it.")
            h in 12..21 && water < waterT * (0.15 + 0.04 * (h - 12)) && fresh("water") ->
                Alert("water", "Water", "Water so far: ${water.toInt()} ml of about ${waterT.toInt()} ml. One glass = 250 ml. Time: $h:00.", "Water check: you're at ${water.toInt()} ml of about ${waterT.toInt()} ml. Have a glass (250 ml) now?")
            h in 14..21 && kcal < 1.0 && fresh("meal") ->
                Alert("meal", "Nutrition", "Nothing eaten is logged today. Time: $h:00.", "I don't see any food logged today. Did you eat? Tell me what and I'll log it – estimates are fine.")
            h in 14..21 && kcalT > 0 && kcal > 0 && kcal < kcalT * (0.18 + 0.045 * (h - 12)) && fresh("meal") ->
                Alert("meal", "Nutrition", "Eaten ${kcal.toInt()} kcal of about ${kcalT.toInt()} kcal, protein ${(ev.protein ?: 0.0).toInt()} g. Time: $h:00.", "You're a bit behind on food today (${kcal.toInt()} of about ${kcalT.toInt()} kcal). Time for a proper meal?")
            h in 17..21 && ev.activeMinutes < 1.0 && fresh("activity") ->
                Alert("activity", "Activity", "No exercise logged today. Even 10 push-ups count. Time: $h:00.", "No movement logged today. Want to do 10 push-ups or a short walk now? Tell me and I'll log it.")
            h in 21..22 && ev.sleepH == null && fresh("bedtime") ->
                Alert("bedtime", "Sleep", "It is late evening; the target sleep is about ${t.sleepH ?: 7.5} h.", "It's getting late – wind down soon. A steady bedtime helps tomorrow's score.")
            else -> null
        }
    }

    private suspend fun write(g: AppGraph, a: Alert): String {
        if (!g.settings.aiReady() || !Net.isOnline(g.context)) return a.fallback
        val name = g.settings.userCall.value
        val msgs = listOf(
            JSONObject().put("role", "system").put("content", "You are the user's personal health coach inside their assistant app. Write ONE short, warm, specific nudge (max 2 sentences, no markdown, at most one emoji) addressed to \"$name\". Use only the facts given, never diagnose, never shame. End with a tiny easy action or question."),
            JSONObject().put("role", "user").put("content", "Topic: ${a.title}\nFacts: ${a.facts}")
        )
        val r = withTimeoutOrNull(15_000L) { runCatching { g.llm.chat(msgs, null, maxTokens = 120, temperature = 0.8, tier = com.privacy.app.settings.ModelTier.FAST, patient = false) }.getOrNull() }
        val text = (r as? LlmClient.Result.Ok)?.message?.contentOrEmpty().orEmpty().replace(Regex("(?s)<think>.*?</think>"), "").replace(Regex("[*#`_]"), "").trim()
        return text.ifBlank { a.fallback }.take(300)
    }

    /** Sends the alert if the quiet-hours / "app in use" rules allow. Returns true when something was sent. */
    suspend fun maybeAlert(g: AppGraph, ctx: Context, notify: Boolean): Boolean {
        val a = pick(g) ?: return false
        val text = write(g, a)
        // the latest chat continues – a health nudge never opens a new chat if one is open
        g.chatService.addAssistant(text)
        g.settings.markHealthAlert(a.kind)
        if (notify) Notifier.post(ctx, 1700 + a.kind.hashCode().mod(50), Notifier.CH_COMPANION, "${a.title} · health check", text, text, target = "tasks")
        return true
    }
}
