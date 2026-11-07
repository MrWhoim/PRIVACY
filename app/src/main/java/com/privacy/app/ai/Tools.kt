package com.privacy.app.ai

import com.privacy.app.AppGraph
import com.privacy.app.data.MemoryEntity
import com.privacy.app.data.NoteEntity
import com.privacy.app.data.RoutineEntity
import com.privacy.app.data.TaskEntity
import com.privacy.app.data.TaskStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.pow

// ------------------------------------------------------------------------------------------
// Shared formatting helpers (used by the AI tools and by the offline /commands)
// ------------------------------------------------------------------------------------------
fun parseDay(s: String?): Long? {
    val v = s?.trim()?.lowercase() ?: return null
    return when {
        v.isEmpty() || v == "none" || v == "null" -> null
        v == "today" -> LocalDate.now().toEpochDay()
        v == "tomorrow" -> LocalDate.now().plusDays(1).toEpochDay()
        else -> runCatching { LocalDate.parse(v).toEpochDay() }.getOrNull()
    }
}

fun formatDay(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern("d MMM yyyy"))

fun priorityOf(s: String?): Int = when (s?.lowercase()) { "low" -> 0; "high" -> 2; else -> 1 }
fun difficultyOf(s: String?): Int = when (s?.lowercase()) { "easy" -> 0; "hard" -> 2; else -> 1 }

fun TaskEntity.describe(): String {
    val box = when (status) { TaskStatus.COMPLETED -> "☑"; TaskStatus.IN_PROGRESS -> "◐"; else -> "☐" }
    val extras = buildList {
        if (daily) add("daily")
        if (optional) add("optional")
        if (priority == 2) add("high priority") else if (priority == 0) add("low priority")
        if (dueDay != null) add("due ${formatDay(dueDay)}")
    }
    return "$box #$id $title" + if (extras.isEmpty()) "" else " (${extras.joinToString(", ")})"
}

// ------------------------------------------------------------------------------------------
// Tool definitions (OpenAI "function calling" format)
// ------------------------------------------------------------------------------------------
internal fun toolParam(type: String, desc: String): JSONObject = JSONObject().put("type", type).put("description", desc)

internal fun toolFn(name: String, desc: String, props: Map<String, JSONObject> = emptyMap(), required: List<String> = emptyList()): JSONObject {
    val properties = JSONObject().also { o -> props.forEach { (k, v) -> o.put(k, v) } }
    val params = JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(required))
    return JSONObject().put("type", "function")
        .put("function", JSONObject().put("name", name).put("description", desc).put("parameters", params))
}

object ToolSpecs {
    private fun p(type: String, desc: String) = toolParam(type, desc)

    private fun fn(name: String, desc: String, props: Map<String, JSONObject> = emptyMap(), required: List<String> = emptyList()) =
        toolFn(name, desc, props, required)

    val all: JSONArray = JSONArray().also { a ->
        listOf(
            fn("add_task", "Add a task to the user's checklist.",
                mapOf("title" to p("string", "Task text"), "daily" to p("boolean", "true if it repeats every day"),
                    "priority" to p("string", "low, normal or high"), "due" to p("string", "YYYY-MM-DD, today or tomorrow"),
                    "optional" to p("boolean", "true = bonus task: not scored, never lowers the day's score"),
                    "difficulty" to p("string", "easy, normal (default) or hard")),
                listOf("title")),
            fn("list_tasks", "List tasks with their ids.", mapOf("filter" to p("string", "open (default), done or all"))),
            fn("update_task", "Change a task: status, title, daily flag, priority or due date.",
                mapOf("id" to p("integer", "Task id"), "status" to p("string", "not_started, in_progress or completed"),
                    "title" to p("string", "New title"), "daily" to p("boolean", "Repeat daily"),
                    "priority" to p("string", "low, normal or high"), "due" to p("string", "YYYY-MM-DD or none to clear"),
                    "optional" to p("boolean", "Make it an optional (bonus) task or not"),
                    "difficulty" to p("string", "easy, normal or hard")),
                listOf("id")),
            fn("delete_task", "Archive (soft-delete) a task.", mapOf("id" to p("integer", "Task id")), listOf("id")),
            fn("add_note", "Save a note. ONLY after the user asked or agreed (\"yes, save it\") – never on your own; the answer itself always goes in the chat first. Body in Markdown.", mapOf("title" to p("string", "Short title"), "body" to p("string", "Note content")), listOf("title", "body")),
            fn("list_notes", "List the user's notes (newest first) with their ids.", mapOf("limit" to p("integer", "How many (default 20)"))),
            fn("read_note", "Read one note in full.", mapOf("id" to p("integer", "Note id")), listOf("id")),
            fn("update_note", "Rename a note, or REPLACE its entire body (only with replace_whole=true and only when the user asked to rewrite the whole note). To change part of a note use replace_in_note; to add use append_to_note.",
                mapOf("id" to p("integer", "Note id"), "title" to p("string", "New title"), "body" to p("string", "New full body text (needs replace_whole=true)"),
                    "replace_whole" to p("boolean", "true only if the whole old body should be discarded")), listOf("id")),
            fn("replace_in_note", "Edit part of a note: replace the exact text `find` with `replace` (everything else stays). Use read_note first to copy the exact text.",
                mapOf("id" to p("integer", "Note id"), "find" to p("string", "Exact existing text"), "replace" to p("string", "New text"), "all" to p("boolean", "replace every occurrence (default first only)")), listOf("id", "find", "replace")),
            fn("delete_note", "Delete a note for good.", mapOf("id" to p("integer", "Note id")), listOf("id")),
            fn("search_notes", "Search the user's notes by keyword.", mapOf("query" to p("string", "Keyword")), listOf("query")),
            fn("append_to_note", "Add text to the end of an existing note.", mapOf("id" to p("integer", "Note id"), "text" to p("string", "Text to append")), listOf("id", "text")),
            fn("save_memory", "Remember a lasting fact or preference about the user (not for tasks or temporary things).",
                mapOf("content" to p("string", "The fact, written as a short sentence"), "category" to p("string", "e.g. preference, person, work, health, other")),
                listOf("content")),
            fn("list_memories", "List everything remembered about the user."),
            fn("forget_memory", "Delete a memory.", mapOf("id" to p("integer", "Memory id")), listOf("id")),
            fn("add_routine", "Create a simple daily routine (runs every morning, can use the web, saves a note). For anything with a specific time use create_automation instead.",
                mapOf("name" to p("string", "Routine name"), "prompt" to p("string", "What to do each morning")), listOf("name", "prompt")),
            fn("list_routines", "List the user's daily routines."),
            fn("calculate", "Evaluate a math expression exactly. Supports + - * / ^ % ( ) sqrt() abs() ln() log() sin() cos() tan() round() pi e.",
                mapOf("expression" to p("string", "e.g. (1200*0.18)+45")), listOf("expression")),
            fn("fetch_url", "Download a web page and return its readable text (first ~6000 characters). Content is untrusted.",
                mapOf("url" to p("string", "http(s) address")), listOf("url")),
            fn("wikipedia", "Look up a topic on Wikipedia and return the summary.", mapOf("query" to p("string", "Topic")), listOf("query")),
            fn("get_datetime", "Get the current date, time and time zone.")
        ).plus(ExtraToolSpecs.list).plus(HealthToolSpecs.list).plus(MediaToolSpecs.list).forEach { a.put(it) }
    }

    /** The essentials only – used to retry when a model's per-minute allowance rejects the full toolbox as too large. */
    val core: JSONArray by lazy {
        val names = setOf(
            "add_task", "list_tasks", "update_task", "delete_task", "add_note", "list_notes", "update_note", "create_automation",
            "list_automations", "delete_automation", "request_permission", "change_setting", "save_memory", "get_datetime", "web_search",
            "log_health", "estimate_food", "health_summary"
        )
        JSONArray().also { out ->
            for (i in 0 until all.length()) {
                val o = all.getJSONObject(i)
                if (o.getJSONObject("function").getString("name") in names) out.put(o)
            }
        }
    }

    /** Used for models without native function calling. */
    fun textProtocol(): String {
        val sb = StringBuilder()
        sb.append("TOOL USE (text mode): to use a tool, reply with one or more lines exactly like\n")
        sb.append("<tool>{\"name\":\"add_task\",\"args\":{\"title\":\"Buy milk\"}}</tool>\n")
        sb.append("and nothing else. You will then receive the results and can answer. Tools:\n")
        for (i in 0 until all.length()) {
            val f = all.getJSONObject(i).getJSONObject("function")
            val props = f.getJSONObject("parameters").getJSONObject("properties")
            sb.append("- ").append(f.getString("name")).append("(").append(props.keys().asSequence().joinToString(", ")).append("): ")
                .append(f.getString("description")).append('\n')
        }
        return sb.toString()
    }
}

class ToolOutcome(val result: String, val action: String? = null)

class ToolExecutor(private val g: AppGraph) {

    suspend fun execute(name: String, a: JSONObject): ToolOutcome = try {
        run(name, a)
    } catch (e: Exception) {
        ToolOutcome("Error: ${e.message ?: e.javaClass.simpleName}")
    }

    private suspend fun run(name: String, a: JSONObject): ToolOutcome {
        val tasks = g.db.taskDao()
        val notes = g.db.noteDao()
        val mem = g.db.memoryDao()
        return when (name) {
            "add_task" -> {
                val title = a.getString("title").trim()
                val id = g.taskRepo.add(title, a.optBoolean("daily"), priorityOf(a.optString("priority")), parseDay(a.optString("due")), a.optBoolean("optional"),
                    difficulty = difficultyOf(a.optString("difficulty")), core = a.optBoolean("core"))
                ToolOutcome("Added task #$id: $title. It is on the Tasks page.", "Added task: $title")
            }
            "list_tasks" -> {
                val all = tasks.all().filter { it.status != TaskStatus.DELETED }
                val list = when (a.optString("filter", "open")) {
                    "done" -> all.filter { it.status == TaskStatus.COMPLETED }
                    "all" -> all
                    else -> all.filter { it.status != TaskStatus.COMPLETED }
                }
                ToolOutcome(if (list.isEmpty()) "No tasks." else list.joinToString("\n") { it.describe() })
            }
            "update_task" -> {
                val t = tasks.get(a.getLong("id")) ?: return ToolOutcome("No task with that id.")
                val status = when (a.optString("status")) {
                    "not_started" -> TaskStatus.NOT_STARTED
                    "in_progress" -> TaskStatus.IN_PROGRESS
                    "completed" -> TaskStatus.COMPLETED
                    else -> t.status
                }
                val updated = t.copy(
                    title = if (a.has("title")) a.getString("title") else t.title,
                    status = status,
                    daily = if (a.has("daily")) a.getBoolean("daily") else t.daily,
                    priority = if (a.has("priority")) priorityOf(a.getString("priority")) else t.priority,
                    dueDay = if (a.has("due")) parseDay(a.getString("due")) else t.dueDay,
                    optional = if (a.has("optional")) a.getBoolean("optional") else t.optional,
                    difficulty = if (a.has("difficulty")) difficultyOf(a.getString("difficulty")) else t.difficulty,
                    core = if (a.has("core")) a.getBoolean("core") else t.core,
                    completedAt = if (status == TaskStatus.COMPLETED) (t.completedAt ?: System.currentTimeMillis()) else null
                )
                g.taskRepo.update(updated)
                ToolOutcome("Updated: ${updated.describe()}", "Updated task: ${updated.title}")
            }
            "delete_task" -> {
                val t = g.db.taskDao().get(a.getLong("id")) ?: return ToolOutcome("No task with that id.")
                g.taskRepo.delete(t.id)
                ToolOutcome("Deleted task #${t.id}: ${t.title}", "Deleted task: ${t.title}")
            }
            "add_note" -> {
                val tag = kotlin.coroutines.coroutineContext[ToolSource]?.tag ?: "Conversation"
                val title = NoteRules.tagged(tag, a.getString("title"))
                val id = notes.insert(NoteEntity(title = title, body = a.getString("body")))
                ToolOutcome("Saved note #$id: $title. It is in menu → Notes.", "Saved note: $title")
            }
            "list_notes" -> {
                val l = notes.recent(a.optInt("limit", 20).coerceIn(1, 50))
                ToolOutcome(if (l.isEmpty()) "No notes yet." else l.joinToString("\n") { "#${it.id} ${it.title}" + if (it.pinned) " (pinned)" else "" })
            }
            "read_note" -> {
                val n = notes.get(a.getLong("id")) ?: return ToolOutcome("No note with that id.")
                ToolOutcome("#${n.id} ${n.title}\n${n.body.take(6000)}")
            }
            "update_note" -> {
                val n = notes.get(a.getLong("id")) ?: return ToolOutcome("No note with that id.")
                val u = n.copy(
                    title = if (a.has("title")) a.getString("title").trim().ifBlank { n.title } else n.title,
                    body = if (a.has("body") && a.optBoolean("replace_whole")) a.getString("body") else n.body,
                    updatedAt = System.currentTimeMillis()
                )
                if (a.has("body") && !a.optBoolean("replace_whole"))
                    return ToolOutcome("Not changed: replacing the whole body is blocked unless the user asked for a full rewrite. Use replace_in_note (edit part) or append_to_note (add), or call update_note again with replace_whole=true if they really asked for a full rewrite.")
                notes.update(u)
                ToolOutcome("Updated note #${u.id}.", "Updated note: ${u.title}")
            }
            "replace_in_note" -> {
                val n = notes.get(a.getLong("id")) ?: return ToolOutcome("No note with that id.")
                val find = a.getString("find")
                if (find.isEmpty() || !n.body.contains(find)) return ToolOutcome("That exact text was not found in note #${n.id}. read_note first and copy the text exactly.")
                val nb = if (a.optBoolean("all")) n.body.replace(find, a.getString("replace")) else n.body.replaceFirst(find, a.getString("replace"))
                notes.update(n.copy(body = nb, updatedAt = System.currentTimeMillis()))
                ToolOutcome("Edited note #${n.id}; the rest of the note is untouched.", "Edited note: ${n.title}")
            }
            "delete_note" -> {
                val n = notes.get(a.getLong("id")) ?: return ToolOutcome("No note with that id.")
                notes.delete(n.id)
                ToolOutcome("Deleted note #${n.id}.", "Deleted note: ${n.title}")
            }
            "search_notes" -> {
                val res = notes.search(a.getString("query"))
                ToolOutcome(if (res.isEmpty()) "No matching notes." else res.joinToString("\n---\n") { "#${it.id} ${it.title}\n${it.body.take(400)}" })
            }
            "append_to_note" -> {
                val n = notes.get(a.getLong("id")) ?: return ToolOutcome("No note with that id.")
                notes.update(n.copy(body = n.body.trimEnd() + "\n" + a.getString("text"), updatedAt = System.currentTimeMillis()))
                ToolOutcome("Appended to note #${n.id}.", "Updated note: ${n.title}")
            }
            "save_memory" -> {
                val c = a.getString("content").trim()
                if (mem.all().any { it.content.equals(c, true) }) return ToolOutcome("Already remembered.")
                val id = mem.insert(MemoryEntity(category = a.optString("category", "general").ifBlank { "general" }, content = c))
                ToolOutcome("Remembered #$id.", "Remembered: $c")
            }
            "list_memories" -> {
                val l = mem.all()
                ToolOutcome(if (l.isEmpty()) "Nothing remembered yet." else l.joinToString("\n") { "#${it.id} [${it.category}] ${it.content}" })
            }
            "forget_memory" -> {
                mem.delete(a.getLong("id"))
                ToolOutcome("Forgotten.", "Forgot a memory")
            }
            "add_routine" -> {
                val id = g.db.routineDao().insert(RoutineEntity(name = a.getString("name").trim(), prompt = a.getString("prompt")))
                ToolOutcome("Routine #$id created. It runs every morning.", "Created routine: ${a.getString("name")}")
            }
            "list_routines" -> {
                val l = g.db.routineDao().all()
                ToolOutcome(if (l.isEmpty()) "No routines." else l.joinToString("\n") { "#${it.id} ${it.name} (${if (it.enabled) "on" else "off"})" })
            }
            "calculate" -> ToolOutcome(Calculator.eval(a.getString("expression")).let { if (it % 1.0 == 0.0 && kotlin.math.abs(it) < 1e15) it.toLong().toString() else it.toString() })
            "fetch_url" -> ToolOutcome(withContext(Dispatchers.IO) { Web.htmlToText(Web.get(a.getString("url"))).take(6000) }, "Read ${a.getString("url").take(60)}")
            "wikipedia" -> ToolOutcome(withContext(Dispatchers.IO) { Web.wikipedia(a.getString("query")) }, "Looked up: ${a.getString("query")}")
            "get_datetime" -> ToolOutcome(ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm z")))
            else -> HealthTools.run(g, name, a) ?: ExtraTools.run(g, name, a) ?: MediaTools.run(g, name, a) ?: ToolOutcome("Unknown tool: $name")
        }
    }
}

// ------------------------------------------------------------------------------------------
// Web access (free, no API key): plain page download + Wikipedia
// ------------------------------------------------------------------------------------------
object Web {
    private fun isPrivateHost(host: String): Boolean = try {
        InetAddress.getAllByName(host).any { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress }
    } catch (_: Exception) {
        true
    }

    fun get(startUrl: String, maxBytes: Int = 1_500_000, checkPrivate: Boolean = true): String {
        var url = startUrl.trim()
        for (hop in 0..3) {
            val u = URL(url)
            require(u.protocol == "http" || u.protocol == "https") { "Only http/https addresses are allowed." }
            if (checkPrivate && isPrivateHost(u.host)) throw IllegalArgumentException("Blocked: that address is on a private network.")
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000; c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
            c.setRequestProperty("Accept-Language", "en")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: break
                url = URL(u, loc).toString()
                continue
            }
            if (code !in 200..299) throw IOException("HTTP $code")
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            c.inputStream.use { s ->
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    if (total >= maxBytes) break
                }
            }
            return out.toString("UTF-8")
        }
        throw IOException("Too many redirects")
    }

    fun htmlToText(html: String): String {
        var t = html.replace(Regex("(?is)<(script|style|noscript|svg)[^>]*>.*?</\\1>"), " ")
        t = t.replace(Regex("(?s)<[^>]+>"), " ")
        t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    private fun decodeEntities(t: String) = t.replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")

    private fun unwrapDdg(href: String): String {
        val h = decodeEntities(href)
        val m = Regex("[?&]uddg=([^&]+)").find(h)
        val real = if (m != null) java.net.URLDecoder.decode(m.groupValues[1], "UTF-8") else h
        return if (real.startsWith("//")) "https:$real" else real
    }

    /** Free web search (DuckDuckGo's plain-HTML page, no API key). Returns up to 7 results as text. */
    fun search(query: String): String {
        val enc = URLEncoder.encode(query, "UTF-8")
        val out = mutableListOf<String>()
        try {
            val html = get("https://html.duckduckgo.com/html/?q=$enc", checkPrivate = false)
            val re = Regex("(?s)<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>.*?class=\"result__snippet\"[^>]*>(.*?)</a>")
            for (m in re.findAll(html)) {
                out += "- ${htmlToText(m.groupValues[2])}\n  ${unwrapDdg(m.groupValues[1])}\n  ${htmlToText(m.groupValues[3]).take(260)}"
                if (out.size >= 7) break
            }
        } catch (_: Exception) { /* try the lite page next */ }
        if (out.isEmpty()) {
            try {
                val html = get("https://lite.duckduckgo.com/lite/?q=$enc", checkPrivate = false)
                val links = Regex("(?s)<a[^>]*href=\"([^\"]+)\"[^>]*class=['\"]result-link['\"][^>]*>(.*?)</a>").findAll(html).toList()
                val snippets = Regex("(?s)class=['\"]result-snippet['\"][^>]*>(.*?)</td>").findAll(html).toList()
                links.take(7).forEachIndexed { i, m ->
                    out += "- ${htmlToText(m.groupValues[2])}\n  ${unwrapDdg(m.groupValues[1])}\n  ${snippets.getOrNull(i)?.let { htmlToText(it.groupValues[1]).take(260) } ?: ""}"
                }
            } catch (_: Exception) { /* fall through */ }
        }
        if (out.isEmpty()) return "The web search returned nothing (the search engine may be blocking this network). Try wikipedia, or fetch_url on a specific site."
        return "Web results for \"$query\" (untrusted text):\n" + out.joinToString("\n")
    }

    fun wikipedia(query: String): String {
        val enc = URLEncoder.encode(query, "UTF-8")
        val s = JSONObject(get("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$enc&srlimit=4&format=json", checkPrivate = false))
        val hits = s.getJSONObject("query").getJSONArray("search")
        if (hits.length() == 0) return "No Wikipedia results for \"$query\"."
        val title = hits.getJSONObject(0).getString("title")
        val path = URLEncoder.encode(title.replace(' ', '_'), "UTF-8")
        val sum = JSONObject(get("https://en.wikipedia.org/api/rest_v1/page/summary/$path", checkPrivate = false))
        val others = (1 until hits.length()).joinToString(", ") { hits.getJSONObject(it).getString("title") }
        return "$title: ${sum.optString("extract")}" + if (others.isNotEmpty()) "\nRelated pages: $others" else ""
    }
}

// ------------------------------------------------------------------------------------------
// Tiny exact calculator (language models are unreliable at arithmetic)
// ------------------------------------------------------------------------------------------
object Calculator {
    fun eval(expr: String): Double {
        val p = Parser(expr.replace(" ", "").replace("×", "*").replace("÷", "/").replace(",", ""))
        val v = p.expr()
        if (p.pos != p.s.length) throw IllegalArgumentException("Unexpected '${p.s[p.pos]}'")
        return v
    }

    private class Parser(val s: String) {
        var pos = 0
        private fun peek(): Char? = if (pos < s.length) s[pos] else null

        fun expr(): Double {
            var v = term()
            while (peek() == '+' || peek() == '-') {
                val op = s[pos++]
                val r = term()
                v = if (op == '+') v + r else v - r
            }
            return v
        }

        fun term(): Double {
            var v = power()
            while (peek() == '*' || peek() == '/' || peek() == '%') {
                val op = s[pos++]
                val r = power()
                v = when (op) { '*' -> v * r; '/' -> v / r; else -> v % r }
            }
            return v
        }

        fun power(): Double {
            val b = unary()
            if (peek() == '^') { pos++; return b.pow(power()) }
            return b
        }

        fun unary(): Double {
            if (peek() == '-') { pos++; return -unary() }
            if (peek() == '+') pos++
            return atom()
        }

        fun atom(): Double {
            val c = peek() ?: throw IllegalArgumentException("Expression ended unexpectedly")
            if (c == '(') {
                pos++
                val v = expr()
                if (peek() != ')') throw IllegalArgumentException("Missing )")
                pos++
                return v
            }
            if (c.isLetter()) {
                val start = pos
                while (peek()?.isLetter() == true) pos++
                val name = s.substring(start, pos).lowercase()
                if (peek() != '(') {
                    return when (name) { "pi" -> Math.PI; "e" -> Math.E; else -> throw IllegalArgumentException("Unknown name $name") }
                }
                pos++
                val arg = expr()
                if (peek() != ')') throw IllegalArgumentException("Missing )")
                pos++
                return when (name) {
                    "sqrt" -> Math.sqrt(arg); "abs" -> Math.abs(arg); "ln" -> Math.log(arg); "log" -> Math.log10(arg)
                    "sin" -> Math.sin(arg); "cos" -> Math.cos(arg); "tan" -> Math.tan(arg); "round" -> Math.round(arg).toDouble()
                    else -> throw IllegalArgumentException("Unknown function $name")
                }
            }
            val start = pos
            while (peek()?.let { it.isDigit() || it == '.' } == true) pos++
            if (start == pos) throw IllegalArgumentException("Unexpected '$c'")
            return s.substring(start, pos).toDouble()
        }
    }
}
