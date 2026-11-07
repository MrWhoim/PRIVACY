package com.privacy.app.ai

import org.json.JSONObject
import com.privacy.app.AppGraph
import com.privacy.app.data.MemoryEntity
import com.privacy.app.data.NoteEntity
import com.privacy.app.data.TaskEntity
import com.privacy.app.data.TaskStatus
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Works with no internet and no API key. Anything starting with "/" lands here. */
object LocalCommands {

    /** Every command with a one-line description – shown when you type "/" in chat and on the About page. */
    val catalog: List<Pair<String, String>> = listOf(
        "/add" to "Add a task. Extras: daily, !high, !low, @tomorrow, @2026-12-31",
        "/daily" to "Add a daily task",
        "/tasks" to "Open tasks · /tasks done · /tasks all · /tasks archive",
        "/done" to "Complete a task by number or part of its name",
        "/doing" to "Mark a task in progress",
        "/undo" to "Put a task back to not started",
        "/del" to "Cancel (archive) a task",
        "/note" to "Save a note: /note Title | text",
        "/notes" to "List or search notes",
        "/remember" to "Save something for the assistant to remember",
        "/memories" to "List what the assistant remembers",
        "/forget" to "Forget a memory by number",
        "/calc" to "Calculator, e.g. /calc (1200*0.18)+45",
        "/brief" to "Today at a glance",
        "/water" to "Log water: /water 500 (ml) · /water 1.5l · /water 2 glasses",
        "/sleep" to "Log last night's sleep: /sleep 7.5",
        "/ate" to "Log a meal: /ate 2 rotis, dal and paneer",
        "/did" to "Log exercise: /did ran 2 km and 30 squats",
        "/health" to "Today's health log and scores",
        "/time" to "Current date and time",
        "/distracted" to "I'm Feeling Distracted mode: /distracted on · /distracted off",
        "/music" to "Music player: /music (open) · /music <song name> (find it on the phone)",
        "/call" to "Phone call: /call <name or number>",
        "/telegram" to "Telegram: /telegram <message> sends it to your bot",
        "/new" to "Start a new chat",
        "/clear" to "Clear this chat",
        "/help" to "Show the command list"
    )

    val HELP = """
**Offline commands** (no internet needed)
- `/add Buy milk` – add a task. Extras: `daily`, `!high`, `!low`, `@tomorrow`, `@2026-12-31`
- `/daily Drink water` – add a daily task
- `/tasks` – open tasks · `/tasks done` · `/tasks all`
- `/done 3` or `/done milk` – complete a task
- `/doing 3` – mark in progress · `/undo 3` – back to not started
- `/del 3` – archive a task
- `/note Title | text` – save a note · `/notes` or `/notes word` – list / search
- `/remember I prefer short answers` – save a memory · `/memories` · `/forget 4`
- `/calc (1200*0.18)+45` – calculator
- `/brief` – today at a glance · `/time` – date and time
- `/clear` – clear this chat

Anything else is sent to your AI (needs internet and an API key).
""".trimIndent()

    private data class Parsed(val title: String, val daily: Boolean, val priority: Int, val due: Long?)

    private fun parseTask(input: String, forceDaily: Boolean = false): Parsed {
        var daily = forceDaily
        var priority = 1
        var due: Long? = null
        val kept = mutableListOf<String>()
        for (tok in input.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            when {
                tok.equals("daily", true) || tok.equals("#daily", true) -> daily = true
                tok.equals("!high", true) -> priority = 2
                tok.equals("!low", true) -> priority = 0
                tok.startsWith("@") && parseDay(tok.drop(1)) != null -> due = parseDay(tok.drop(1))
                else -> kept += tok
            }
        }
        return Parsed(kept.joinToString(" ").trim(), daily, priority, due)
    }

    private suspend fun findTask(ref: String, g: AppGraph): Pair<TaskEntity?, String?> {
        ref.toLongOrNull()?.let { id ->
            val t = g.db.taskDao().get(id)
            return if (t != null) t to null else null to "No task #$id."
        }
        if (ref.isBlank()) return null to "Tell me which task: a number or part of its name."
        val matches = g.db.taskDao().all().filter { it.status != TaskStatus.DELETED && it.title.contains(ref, true) }
        return when {
            matches.size == 1 -> matches[0] to null
            matches.isEmpty() -> null to "No task matches “$ref”."
            else -> null to "Several tasks match: " + matches.take(5).joinToString("; ") { "#${it.id} ${it.title}" } + ". Use the number."
        }
    }

    suspend fun handle(raw: String, g: AppGraph): String? {
        val text = raw.trim()
        if (!text.startsWith("/")) return null
        val cmd = text.substringBefore(' ').lowercase().removePrefix("/")
        val arg = text.substringAfter(' ', "").trim()
        val dao = g.db.taskDao()

        return when (cmd) {
            "help", "start", "commands" -> HELP

            "water", "sleep", "ate", "did", "log" -> {
                if (arg.isBlank()) "Usage: `/water 500` · `/sleep 7.5` · `/ate 2 rotis and dal` · `/did ran 2 km`" else {
                    val sentence = when (cmd) {
                        "water" -> if (arg.any { it.isLetter() }) "drank $arg water" else "drank $arg ml water"
                        "sleep" -> if (arg.any { it.isLetter() }) "slept $arg" else "slept $arg hours"
                        "ate" -> "ate $arg"
                        else -> arg
                    }
                    val parsed = com.privacy.app.data.HealthParser.parse(sentence, java.time.LocalTime.now().hour)
                    val results = g.health.logDrafts(parsed, arg, "manual")
                    val lines = results.map {
                        when (it) {
                            is com.privacy.app.data.HealthRepo.LogResult.Added -> "Saved: ${it.entry.category.lowercase()} – ${it.entry.label}" + (it.entry.kcal?.let { k -> " (~${k.toInt()} kcal, estimate)" } ?: "")
                            is com.privacy.app.data.HealthRepo.LogResult.Updated -> it.note
                            is com.privacy.app.data.HealthRepo.LogResult.Duplicate -> "Already logged just now – skipped the duplicate."
                            is com.privacy.app.data.HealthRepo.LogResult.Rejected -> it.reason
                        }
                    } + parsed.questions
                    if (lines.isEmpty()) "I couldn't read that. Try `/water 500`, `/sleep 7`, `/ate 2 rotis and dal`, `/did ran 2 km`." else lines.joinToString("\n")
                }
            }
            "health" -> "```\n" + g.health.summary() + "```"

            "add", "a", "task" -> {
                val p = parseTask(arg)
                if (p.title.isEmpty()) "Usage: `/add Buy milk`" else {
                    val id = g.taskRepo.add(p.title, p.daily, p.priority, p.due)
                    "Added task #$id: **${p.title}**" + if (p.daily) " (daily)" else ""
                }
            }
            "daily" -> {
                val p = parseTask(arg, forceDaily = true)
                if (p.title.isEmpty()) "Usage: `/daily Drink water`" else {
                    val id = g.taskRepo.add(p.title, true, p.priority, p.due)
                    "Added daily task #$id: **${p.title}**"
                }
            }
            "tasks", "t", "list" -> {
                val all = dao.all()
                val (title, list) = when (arg.lowercase()) {
                    "done" -> "Completed" to all.filter { it.status == TaskStatus.COMPLETED }
                    "all" -> "All tasks" to all.filter { it.status != TaskStatus.DELETED }
                    "archive" -> "Archive" to all.filter { it.status == TaskStatus.DELETED }.takeLast(15)
                    else -> "Open tasks" to all.filter { it.status == TaskStatus.NOT_STARTED || it.status == TaskStatus.IN_PROGRESS }
                }
                if (list.isEmpty()) "**$title:** nothing here." else "**$title:**\n" + list.joinToString("\n") { "- ${it.describe()}" }
            }
            "done", "d", "complete" -> findTask(arg, g).let { (t, err) ->
                if (t == null) err else { g.taskRepo.setStatus(t.id, TaskStatus.COMPLETED); "Completed: **${t.title}** ☑" }
            }
            "doing", "start-task", "progress" -> findTask(arg, g).let { (t, err) ->
                if (t == null) err else { g.taskRepo.setStatus(t.id, TaskStatus.IN_PROGRESS); "In progress: **${t.title}** ◐" }
            }
            "undo", "reopen" -> findTask(arg, g).let { (t, err) ->
                if (t == null) err else { g.taskRepo.setStatus(t.id, TaskStatus.NOT_STARTED); "Reopened: **${t.title}**" }
            }
            "del", "delete", "rm" -> findTask(arg, g).let { (t, err) ->
                if (t == null) err else { g.taskRepo.delete(t.id); "Archived: **${t.title}** (restore it from Tasks → Archive)" }
            }

            "note", "n" -> {
                val title = arg.substringBefore('|').trim()
                val body = if (arg.contains('|')) arg.substringAfter('|').trim() else ""
                if (title.isEmpty()) "Usage: `/note Title | text`" else {
                    val id = g.db.noteDao().insert(NoteEntity(title = title, body = body))
                    "Saved note #$id: **$title**"
                }
            }
            "notes" -> {
                val list = if (arg.isBlank()) g.db.noteDao().recent(10) else g.db.noteDao().search(arg)
                if (list.isEmpty()) "No notes found." else list.joinToString("\n") { "- #${it.id} **${it.title}** ${it.body.take(60).replace('\n', ' ')}" }
            }

            "remember", "mem" -> if (arg.isEmpty()) "Usage: `/remember I prefer short answers`" else {
                val id = g.db.memoryDao().insert(MemoryEntity(content = arg))
                "Remembered #$id: $arg"
            }
            "memories" -> {
                val l = g.db.memoryDao().all()
                if (l.isEmpty()) "Nothing remembered yet." else l.joinToString("\n") { "- #${it.id} ${it.content}" }
            }
            "forget" -> arg.toLongOrNull()?.let { g.db.memoryDao().delete(it); "Forgotten #$it." } ?: "Usage: `/forget 4`"

            "calc", "=" -> runCatching { Calculator.eval(arg) }.fold(
                { v -> "= **${if (v % 1.0 == 0.0 && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()}**" },
                { e -> "Couldn't calculate that: ${e.message}" }
            )
            "time", "date" -> ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy – HH:mm (z)"))
            "brief", "today" -> {
                val all = dao.all().filter { it.status != TaskStatus.DELETED }
                val open = all.filter { it.status != TaskStatus.COMPLETED }
                val done = all.count { it.status == TaskStatus.COMPLETED }
                val today = LocalDate.now().toEpochDay()
                val due = open.filter { it.dueDay != null && it.dueDay <= today }
                buildString {
                    appendLine("**${LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM"))}**")
                    appendLine("${open.size} open · $done done")
                    if (due.isNotEmpty()) { appendLine("\n**Due or overdue:**"); due.forEach { appendLine("- ${it.describe()}") } }
                    if (open.isNotEmpty()) { appendLine("\n**Next up:**"); open.take(8).forEach { appendLine("- ${it.describe()}") } }
                }.trim()
            }
            "music" -> {
                val ctx = g.context
                com.privacy.app.media.MusicPlayer.init(ctx)
                com.privacy.app.media.MusicUi.focus.value = true
                if (arg.isBlank()) "Music player opened at the top of the chat – swipe the stats bar." else MediaTools.run(g, "music", JSONObject().put("action", "play").put("query", arg))?.result ?: "Done."
            }
            "call" -> if (arg.isBlank()) "Usage: `/call Mom` or `/call 9876543210`" else DeviceTools.call(g.context, arg)
            "telegram" -> if (arg.isBlank()) "Usage: `/telegram hello` – sends it to your bot. Set the bot up in Account → Telegram." else TelegramHub.send(g, arg, null)
            else -> "Unknown command `/$cmd`. Type `/help` to see what works offline."
        }
    }
}
