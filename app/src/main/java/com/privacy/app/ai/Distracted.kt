package com.privacy.app.ai

import android.content.Context
import com.privacy.app.AppGraph
import com.privacy.app.data.ChatEntity
import com.privacy.app.work.Notifier
import kotlinx.coroutines.delay

/**
 * "I'm Feeling Distracted" mode. The assistant stops being a tool and becomes a person who talks about the user's own big
 * projects (or, failing that, a valuable problem worth solving) in short, human messages – and starts topics by itself.
 */
object Distracted {
    private val on = Regex("(?i)(i'?m|i am|im|feeling|feel)\\s+(so\\s+|very\\s+|really\\s+)?distracted|distracted mode|/distracted(\\s+on)?\\s*$")
    private val off = Regex("(?i)(stop|exit|end|leave|turn off|disable|quit)\\s+(the\\s+)?distracted( mode)?|distracted mode off|i'?m (focused|back|good now)|/distracted\\s+off")

    fun wantsOn(t: String) = on.containsMatchIn(t.trim()) && !off.containsMatchIn(t)
    fun wantsOff(t: String) = off.containsMatchIn(t.trim())

    const val PROMPT = """MODE: "I'M FEELING DISTRACTED" is ON. The user wants to be pulled into something big, like a friend who is genuinely excited about their future.
- First choose ONE real project, idea or problem of this user from everything you know (memories, notes, tasks, goals, past chats). If you know none, ask them what they are building or dreaming about – one question at a time – and wait for the answer.
- Then talk about it the way a real person does: how to make it bigger, what the real problems are, the next concrete steps, what could go right and what could fail (be honest about both). Show the possible outcomes of the different scenarios. Challenge weak spots in their ideas, don't just praise them.
- NOT a summary and NOT an essay. Write 1 to 3 SHORT chat messages (one or two sentences each), separated by the exact marker ||| so they arrive one after another like texts from a person. No headings, no bullet lists, no markdown. End with a question or a hook that pulls them in.
- You may start a topic yourself, change topic, bring a new angle, or throw a surprising idea. Keep momentum.
- If they seem bored or not interested in their own project, switch to a hard, valuable industry problem that could make serious money or fame if solved (name the problem, who pays, why it is still unsolved, a first move) and invite them to attack it with you.
- Do not use tools in this mode unless the user asks for an action. Stay in this mode until the user says to stop."""

    /** The hidden turn used when the assistant speaks first. */
    private const val NUDGE = "(Hidden instruction, do not mention it: the user has gone quiet. Send your NEXT message to pull them back in – continue the thread, or open a new angle/topic. Short, human, 1–3 parts separated by |||.)"

    /** Saves a multi-part reply as several chat bubbles that arrive a moment apart. */
    suspend fun deliver(g: AppGraph, cid: Long, text: String, gapMs: Long = 1400L): String {
        val parts = text.split("|||").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(text.trim()) }
        val dao = g.db.chatDao()
        parts.forEachIndexed { i, p ->
            if (i > 0) delay(gapMs)
            dao.insert(ChatEntity(role = "assistant", content = p, conversationId = cid))
        }
        return parts.joinToString("\n\n")
    }

    /** The assistant speaks first (in-app when the user is idle, or in the background with a notification). Returns true when something was sent. */
    suspend fun maybeNudge(g: AppGraph, ctx: Context, minGapMs: Long, notify: Boolean): Boolean {
        val s = g.settings
        if (!s.distractedMode.value || !s.aiReady() || !Net.isOnline(ctx)) return false
        val now = System.currentTimeMillis()
        if (now - s.lastDistractedAt < minGapMs) return false
        val hour = java.time.LocalTime.now().hour
        if (hour < 8 || hour >= 23) return false
        val cid = g.chatService.ensureConversation()
        val recent = g.db.chatDao().recent(cid, 8)
        // never pile up: after 3 unanswered messages wait for the user
        val unanswered = recent.takeLastWhile { it.role != "user" }.count { it.role == "assistant" }
        if (unanswered >= 3) return false
        val last = recent.lastOrNull()
        if (last != null && now - last.createdAt < 90_000L) return false
        s.lastDistractedAt = now
        val r = g.agent.chat(recent + ChatEntity(role = "user", content = NUDGE, conversationId = cid))
        if (r.error != null || r.text.isBlank()) return false
        val shown = deliver(g, cid, r.text)
        if (notify) Notifier.post(ctx, 1600, Notifier.CH_COMPANION, "Distracted mode", shown.take(160), shown, target = "chat")
        return true
    }
}
