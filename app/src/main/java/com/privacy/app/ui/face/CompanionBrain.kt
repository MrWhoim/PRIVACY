package com.privacy.app.ui.face

import com.privacy.app.AppGraph
import com.privacy.app.ai.LlmClient
import com.privacy.app.ai.contentOrEmpty
import com.privacy.app.data.ChatEntity
import com.privacy.app.data.ConversationEntity
import com.privacy.app.data.TaskEntity
import com.privacy.app.data.TaskStatus
import com.privacy.app.settings.ModelTier
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The little assistant's personality: cute and innocent but a tiny bit disturbing – it pokes you, asks small questions, gets sulky or proud,
 * and has a smart opinion drawn from your tasks, notes, memories and past chats. Its talks are kept in their own chat ("Assistant chat · date").
 *
 * It must never go quiet with "can't connect" and never nag about the same remaining tasks: when the AI is busy or offline it falls back to
 * offline lines built from your own data, it remembers what it just said (no repeats), and it rotates the topic.
 */
object CompanionBrain {
    const val TITLE_PREFIX = "Assistant chat"

    private const val PERSONA =
        "You are the user's tiny pixel assistant who lives on their phone screen. Personality: cute, innocent, a little clingy and mischievous – " +
            "you disturb the user in an adorable way, like a small creature tugging at their sleeve. Sometimes sulky, sometimes proud, sometimes curious. " +
            "Always have a SMART, specific opinion that shows you know their notes, memories, habits or tasks, but tease gently – never appearance, health, family or money. " +
            "Reply in at most 2 short sentences, plain English, at most one emoji, no markdown, no lists. Speak directly to the user. Often end with a tiny question. " +
            "IMPORTANT: do NOT list or count remaining tasks, and do not mention tasks at all unless the topic you were given is a task. Never repeat an earlier line."

    private fun msg(role: String, text: String) = JSONObject().put("role", role).put("content", text)

    private fun clean(raw: String): String =
        raw.replace(Regex("(?s)<think>.*?</think>"), "").replace(Regex("[*#`_]"), "").trim().take(260)

    // ---- remembers what it said so it never repeats itself, and backs off when the AI is unavailable
    private val recent = ArrayDeque<String>()
    private var failures = 0
    private var quietUntil = 0L
    private var lastKind = ""

    private fun remember(line: String) { recent.addLast(line.take(80)); while (recent.size > 12) recent.removeFirst() }
    private fun fresh(line: String) = recent.none { it.equals(line.take(80), true) }

    /** True when it is worth trying the AI right now. */
    fun aiAvailable(g: AppGraph): Boolean = g.settings.aiReady() && System.currentTimeMillis() >= quietUntil

    private suspend fun ask(g: AppGraph, system: String, history: List<Pair<String, String>>, prompt: String?): String? {
        if (!aiAvailable(g)) return null
        val list = mutableListOf(msg("system", system + if (recent.isNotEmpty()) "\nLines you already said recently (do not repeat or paraphrase them): " + recent.joinToString(" | ") else ""))
        history.forEach { list.add(msg(it.first, it.second)) }
        if (prompt != null) list.add(msg("user", prompt))
        // One quick pass on the fast models, never waits in the chat's queue, gives up after 12 s.
        val r = withTimeoutOrNull(12_000L) { runCatching { kotlinx.coroutines.withContext(com.privacy.app.credits.CreditLabel("Assistant bubble")) { g.llm.chat(list, null, maxTokens = 160, temperature = 0.95, tier = ModelTier.FAST, patient = false) } }.getOrNull() }
        val text = (r as? LlmClient.Result.Ok)?.message?.contentOrEmpty().orEmpty()
        val out = clean(text).takeIf { it.isNotBlank() && fresh(it) }
        if (out == null && r !is LlmClient.Result.Ok) {
            failures++
            quietUntil = System.currentTimeMillis() + minOf(10L, 1L shl minOf(failures, 4)) * 60_000L // 2, 4, 8, 10 minutes
        } else if (out != null) { failures = 0; remember(out) }
        return out
    }

    private suspend fun brief(g: AppGraph, name: String): String {
        val notes = runCatching { g.db.noteDao().recent(6) }.getOrDefault(emptyList())
        val mems = runCatching { g.db.memoryDao().all() }.getOrDefault(emptyList()).takeLast(8)
        val chats = runCatching { g.db.conversationDao().recent(8) }.getOrDefault(emptyList()).filter { !it.title.startsWith(TITLE_PREFIX) }
        return buildString {
            appendLine("User's name/how to call them: $name")
            appendLine("Recent notes: " + notes.joinToString("; ") { it.title.take(40) }.ifBlank { "none" })
            appendLine("Things known about the user: " + mems.joinToString("; ") { it.content.take(100) }.ifBlank { "nothing yet" })
            appendLine("Past chat titles: " + chats.joinToString("; ") { it.title.take(40) }.ifBlank { "none" })
        }
    }

    // ------------------------------------------------------------------------------------------ offline lines (no AI, no internet)
    private fun hourWord(): String = when (LocalTime.now().hour) { in 5..11 -> "morning"; in 12..16 -> "afternoon"; in 17..21 -> "evening"; else -> "night" }

    private fun taskLine(t: TaskEntity, name: String): String {
        val title = "“${t.title.take(34)}”"
        return if (t.status == TaskStatus.IN_PROGRESS) listOf(
            "Psst $name… $title is half done. It looks so lonely like that 🥺",
            "I saw you start $title. Finish it? I'll clap. Quietly.",
            "$title is sitting there in progress, giving me puppy eyes."
        ).random() else listOf(
            "Hehe, $name… I poked $title. It didn't move. Can you wake it up?",
            "Tiny question: what's the smallest first step for $title?",
            "$title keeps looking at me. I think it likes you. Say hi?",
            "I won't say anything about $title. …I'm only thinking it very loudly."
        ).random()
    }

    private fun generic(name: String): String = when (hourWord()) {
        "morning" -> listOf("Good morning, $name! Did you drink water, or only dreams?", "I woke up before you. Don't tell anyone. Breakfast?")
        "afternoon" -> listOf("Hi $name! Quick stretch? I'll stretch too. I'm only pixels, but still.", "Is it snack o'clock? Asking for a friend. The friend is me.")
        "evening" -> listOf("The evening is soft, $name. What was the best tiny thing today?", "I'm proud of you for showing up today. Mostly.")
        else -> listOf("It's late, $name… are we sleeping soon or just thinking too much?", "Shh. Phone down soon? I'll guard your screen.")
    }.random()

    /** An offline comment built from the user's own data. Rotates topics so it does not nag. */
    suspend fun chirp(g: AppGraph, name: String): String {
        val tasks = runCatching { g.db.taskDao().open() }.getOrDefault(emptyList())
        val notes = runCatching { g.db.noteDao().recent(5) }.getOrDefault(emptyList())
        val mems = runCatching { g.db.memoryDao().all() }.getOrDefault(emptyList())
        val kinds = buildList {
            if (tasks.isNotEmpty() && lastKind != "task" && kotlin.random.Random.nextInt(100) < 20) add("task")
            if (notes.isNotEmpty() && lastKind != "note") add("note")
            if (mems.isNotEmpty() && lastKind != "mem") add("mem")
            if (lastKind != "gen") add("gen"); add("gen")
        }
        // late in the day: ask to confirm today's tasks (only once in a while)
        if (LocalTime.now().hour in 20..22 && tasks.any { !it.optional } && lastKind != "confirm" &&
            runCatching { g.db.scoreDao().get(LocalDate.now().toEpochDay())?.isFinal != true }.getOrDefault(false)) {
            lastKind = "confirm"
            val l = "It's getting late, $name. Shall we confirm today's tasks so your score becomes final? (Tasks → Finish & confirm)"
            if (fresh(l)) { remember(l); return l }
        }
        var kind = kinds.random()
        var line = ""
        for (i in 0 until 6) {
            kind = kinds.random()
            line = when (kind) {
                "task" -> taskLine(tasks.random(), name)
                "note" -> notes.random().let { listOf("I peeked at your note “${it.title.take(30)}”. Sorry. Not sorry. Continue it?", "Your note “${it.title.take(30)}” looks lonely. Add one line?").random() }
                "mem" -> mems.random().content.take(60).let { listOf("I remember: $it. Still true, $name?", "You once told me: $it. See, I listen!").random() }
                else -> generic(name)
            }
            if (fresh(line)) break
        }
        lastKind = kind
        remember(line)
        return line
    }

    // ------------------------------------------------------------------------------------------ jokes, hype, opinions
    private val jokes = listOf(
        "Why do programmers prefer dark mode? Because light attracts bugs.",
        "I told my phone I needed space. It opened a new tab.",
        "Why was the notebook nervous? It had too many loose ends.",
        "I'd tell you a UDP joke, but you might not get it.",
        "My battery and I have something in common: both drop fast after 20%.",
        "Why did the pixel go to therapy? Too many unresolved issues… and dead cells.",
        "I'm reading a book about anti-gravity. It's impossible to put down.",
        "Parallel lines have so much in common. It's a shame they'll never meet.",
        "Why don't skeletons fight each other? They don't have the guts.",
        "What do you call a sleeping bull? A bulldozer.",
        "I asked the fridge for advice. It said 'chill'.",
        "Why did the scarecrow win an award? Outstanding in his field."
    )
    private val hypes = listOf(
        "Alright, let's make something happen!", "Energy check: I'm at 110%. You?", "Plot twist: today could actually be good.",
        "Beep boop. Motivation module loaded.", "Ready, steady… you!", "Fun fact: you're doing better than you think.",
        "Today's weather: 100% chance of getting things done.", "I stretched my pixels. Your turn!", "Sparks are flying. Use them!"
    )
    fun joke(): String = jokes.filter { fresh(it) }.ifEmpty { jokes }.random().also { remember(it) }
    fun hype(): String = hypes.filter { fresh(it) }.ifEmpty { hypes }.random().also { remember(it) }

    private val moods = listOf("angry and impatient", "funny and sarcastic", "very dark (dark humour, never cruel or harmful)", "extremely serious, like a philosopher", "dramatic like a movie narrator", "suspicious and paranoid", "smug and superior", "oddly wise")

    /** Tap on the assistant: a genuinely useful insight about the user, the chat in progress, or the screen/setting they are on. */
    suspend fun opinion(g: AppGraph, name: String, screenKey: String, screenName: String): String {
        val facts = screenFacts(g, screenKey)
        val ai = ask(
            g, PERSONA + "\n\nThe user just tapped you on the $screenName screen. Give ONE genuinely helpful insight, based ONLY on the facts below: " +
                "(a) on a chat screen – a sharp observation about the conversation so far or the most useful next step in it; " +
                "(b) on tasks/health/reports – a specific pattern or number you notice and one concrete action for today; " +
                "(c) on a settings or keys screen – what this setting does and what is worth changing or checking, given how they use the app. " +
                "No jokes, no mood acting, no greeting, no filler, never invent facts. Under 40 words. If the facts are too thin, say the one most useful thing you can about this screen.",
            emptyList(), "Facts:\n$facts"
        )
        return ai ?: offlineOpinion(screenKey, name)
    }

    private suspend fun screenFacts(g: AppGraph, screenKey: String): String = runCatching {
        when (screenKey) {
            "chat" -> {
                val cid = g.db.conversationDao().recent(6).firstOrNull { !it.title.startsWith(TITLE_PREFIX) }?.id
                val msgs = if (cid != null) g.db.chatDao().recent(cid, 6) else emptyList()
                "The user is in the main chat. Last messages: " + msgs.joinToString(" | ") { it.role + ": " + it.content.take(110) }.ifBlank { "none yet" }
            }
            "tasks" -> "Task & Health screen. Today: " + g.db.taskDao().activeNow().let { l -> "${l.count { it.status == TaskStatus.COMPLETED }} of ${l.size} tasks done" } + ". " + g.health.summary().lineSequence().firstOrNull().orEmpty()
            "notes" -> "Notes screen. Recent notes: " + g.db.noteDao().recent(4).joinToString("; ") { it.title.take(40) }
            "tools" -> "Tools screen (timer, alarm, calendar, currency, calculator)."
            "apis" -> "API & Subscription screen: credits balance, plans, top-ups, AI connections (own keys cost 0 credits)."
            "reports" -> "Reports screen: " + g.health.summary().lineSequence().firstOrNull().orEmpty()
            else -> "Settings or account screen."
        }
    }.getOrDefault("Unknown screen.")

    private fun offlineOpinion(screenKey: String, name: String): String = when (screenKey) {
        "chat" -> "Tip: ask me for one thing at a time with the outcome you want (\"plan my day around these 3 tasks\") – the answers get much sharper."
        "tasks" -> "Pick the one task that would make today a win and do it first, $name – small tasks can wait for the gaps."
        "notes" -> "Long notes get lost: give each one a clear title, and ask me to summarise any note you haven't opened in a week."
        "tools" -> "Timers work best with a task attached: start a 25-minute timer for your top task right now."
        "apis" -> "Your credits, plans and AI connections live here. Own keys cost 0 credits – credits are only used when the app's built-in AI answers."
        "reports" -> "Look at the weakest of Tasks, Health and Focus this week – improving that one lifts your overall score fastest."
        else -> "This is the settings area: Account holds backup, Telegram and your name; Mind sets which apps count as distracting or useful."
    }

    class Starter(val reference: String, val line: String)

    /** The assistant opens a conversation. Tries the AI once; falls back to an offline line; never returns "can't connect". */
    suspend fun starter(g: AppGraph, name: String, workingOn: String): Starter? {
        val tasks = runCatching { g.db.taskDao().open() }.getOrDefault(emptyList())
        val notes = runCatching { g.db.noteDao().recent(8) }.getOrDefault(emptyList())
        val mems = runCatching { g.db.memoryDao().all() }.getOrDefault(emptyList())
        val chats = runCatching { g.db.conversationDao().recent(10) }.getOrDefault(emptyList()).filter { !it.title.startsWith(TITLE_PREFIX) }
        val options = buildList<Pair<String, String>> {
            // tasks are only one of several topics, and never twice in a row
            if (lastKind != "task" && kotlin.random.Random.nextInt(100) < 20) tasks.randomOrNull()?.let { add("task" to "Task: ${it.title}") }
            notes.randomOrNull()?.let { add("note" to "Note: ${it.title.take(60)}") }
            mems.randomOrNull()?.let { add("mem" to "Memory: ${it.content.take(100)}") }
            chats.randomOrNull()?.let { add("chat" to "Past chat: ${it.title.take(60)}") }
            if (workingOn.isNotBlank()) add("work" to "Current work: $workingOn")
            add("gen" to "Nothing specific – just a cute, curious check-in for the ${hourWord()}")
            add("joke" to "Tell me one short, clean, original joke")
        }
        val (kind, ref) = options.random()
        val ai = ask(g, PERSONA + "\n\nWhat you know:\n" + brief(g, name), emptyList(), "Start a little conversation with me about this: $ref. Give your own cute, smart opinion and, if natural, a tiny question.")
        lastKind = kind
        if (ai != null) return Starter(ref, ai)
        val local = chirp(g, name)
        return Starter("a quick thought", local)
    }

    /** A precise suggestion when the user taps the assistant. Always answers (offline fallback names the real top task). */
    suspend fun suggest(g: AppGraph, name: String, screen: String): String {
        val tasks = runCatching { g.db.taskDao().open() }.getOrDefault(emptyList())
        val focus = tasks.firstOrNull { it.core } ?: tasks.firstOrNull { it.priority == 2 } ?: tasks.firstOrNull { it.status == TaskStatus.IN_PROGRESS } ?: tasks.firstOrNull()
        val ai = ask(
            g, PERSONA + "\n\nWhat you know:\n" + brief(g, name), emptyList(),
            "I tapped you while on my $screen screen. Give me ONE precise, practical suggestion for what to do right now" +
                (focus?.let { " (my most important open task is “${it.title}” – you may use it)" } ?: "") + ", with a little cute attitude."
        )
        if (ai != null) return ai
        return focus?.let { "Start with “${it.title.take(40)}”, $name – just ten minutes. I'll hover here and cheer 🌟" } ?: chirp(g, name)
    }

    /** Continues the conversation stored in [cid]. Null only when the AI is genuinely unreachable (the caller then says something kind). */
    suspend fun reply(g: AppGraph, cid: Long, name: String, userText: String): String? {
        val hist = runCatching { g.db.chatDao().recent(cid, 12) }.getOrDefault(emptyList())
            .filter { it.role == "user" || it.role == "assistant" }
            .map { it.role to it.content.take(500) }
        return ask(g, PERSONA + "\n\nWhat you know:\n" + brief(g, name), hist, userText)
    }

    // ---------- the assistant's own conversation ----------
    /** One conversation per day, separate from the chat the user is in. */
    suspend fun ensureConvo(g: AppGraph): Long {
        val prefs = g.context.getSharedPreferences("companion_convo", 0)
        val today = LocalDate.now().toEpochDay()
        val id = prefs.getLong("id", 0L)
        if (id > 0 && prefs.getLong("day", -1L) == today && g.db.conversationDao().get(id) != null) return id
        val title = "$TITLE_PREFIX · " + LocalDate.now().format(DateTimeFormatter.ofPattern("d MMM"))
        val nid = g.db.conversationDao().insert(ConversationEntity(title = title))
        prefs.edit().putLong("id", nid).putLong("day", today).apply()
        return nid
    }

    suspend fun record(g: AppGraph, cid: Long, role: String, text: String) {
        runCatching {
            g.db.chatDao().insert(ChatEntity(role = role, content = text, conversationId = cid))
            g.db.conversationDao().get(cid)?.let { g.db.conversationDao().update(it.copy(updatedAt = System.currentTimeMillis())) }
        }
    }
}
