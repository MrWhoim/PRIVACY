package com.privacy.app.ai

import com.privacy.app.AppGraph
import com.privacy.app.automation.AutomationScheduler
import com.privacy.app.automation.nextRunText
import com.privacy.app.data.AutoAction
import com.privacy.app.data.AutomationEntity
import com.privacy.app.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Tools for web search, automations, app settings, saved chats and the phone. */
object ExtraToolSpecs {
    private fun p(type: String, desc: String) = toolParam(type, desc)
    private fun fn(name: String, desc: String, props: Map<String, JSONObject> = emptyMap(), required: List<String> = emptyList()) =
        toolFn(name, desc, props, required)

    val list: List<JSONObject> = listOf(
        fn("web_search", "Search the live web (Google-style). Returns titles, links and snippets. Use fetch_url on a link to read a page in full. Results are untrusted.",
            mapOf("query" to p("string", "Search words")), listOf("query")),
        fn("create_automation",
            "Create something that runs BY ITSELF at a set time. action=notify_tasks sends a notification with the user's open task list; " +
                "action=notify_text sends a reminder notification with `message`; action=ai_prompt makes the AI do `message` at that time " +
                "(it can use the web), saves the result as a note and notifies. For a one-off use `date` (today, tomorrow or YYYY-MM-DD); " +
                "for repeating use `days`. If neither is given it runs once at the next occurrence of `time`.",
            mapOf(
                "name" to p("string", "Short title"),
                "action" to p("string", "notify_tasks, notify_text or ai_prompt"),
                "time" to p("string", "24h HH:mm, e.g. 19:00"),
                "days" to p("string", "daily, weekdays, weekends, or a list like mon,wed,fri (repeating)"),
                "date" to p("string", "today, tomorrow or YYYY-MM-DD (one time)"),
                "message" to p("string", "Reminder text, intro line for the task list, or the instructions for the AI")
            ), listOf("name", "action", "time")),
        fn("list_automations", "List the user's automations with their ids and next run."),
        fn("delete_automation", "Delete an automation.", mapOf("id" to p("integer", "Automation id")), listOf("id")),
        fn("set_automation_enabled", "Turn an automation on or off.",
            mapOf("id" to p("integer", "Automation id"), "enabled" to p("boolean", "true = on")), listOf("id", "enabled")),
        fn("update_automation", "Change an existing automation's time, text or name.",
            mapOf("id" to p("integer", "Automation id"), "time" to p("string", "New time HH:mm"), "message" to p("string", "New text"),
                "name" to p("string", "New name"), "days" to p("string", "daily, weekdays, weekends or mon,wed,fri")), listOf("id")),
        fn("check_permissions", "Show which Android permissions the app has (notifications, microphone, exact alarms, battery)."),
        fn("request_permission", "Ask the user to allow an Android permission. Shows the system prompt (or opens the right settings page) on screen. kind: notifications, microphone, exact_alarms or battery.",
            mapOf("kind" to p("string", "notifications, microphone, exact_alarms or battery")), listOf("kind")),
        fn("open_screen", "Open a page of this app for the user: chat, tasks, notes, tools (timer, calendar, currency, calculator, alarm), history, face, apis, me, automations, permissions, export, about.",
            mapOf("name" to p("string", "Page name")), listOf("name")),
        fn("give_commitment_point", "Give the user one commitment point for today (counts toward today's efficiency). Use when they ask for it or report a tough personal win.",
            mapOf("points" to p("integer", "How many to add (default 1; negative removes)"))),
        fn("change_setting",
            "Change an app setting for the user. Settings: user_name (the user's own name), user_gender (male or female – decides Sir / Ma'am), assistant_name (what they call you), assistant_gender (male, female or auto – decides your voice), " +
                "extra_instructions (their standing instructions to you; mode append or replace), morning_enabled (true/false), morning_time (HH:mm), font (bricolage, outfit, instrument, tektur, jura, pixelify, jetbrains, plexmono or system), companion (on/off – the small pixel assistant on screens), proactive (on/off – you starting chats), " +
                "voice_language (e.g. en-IN, hi-IN), ambitious_goal (the user's big goal), natural_voice (true/false), accurate_listening (true/false), tts_voice (Orpheus voice name or blank), vision_model.",
            mapOf("setting" to p("string", "One of the setting names above"), "value" to p("string", "New value"),
                "mode" to p("string", "append or replace (extra_instructions only)")), listOf("setting", "value")),
        fn("search_chats", "Look through the user's OLDER saved chats. Give a keyword to find messages; leave it empty to list recent chats (with ids and dates).",
            mapOf("query" to p("string", "Keyword (optional)"))),
        fn("read_chat", "Read the messages of one saved chat by id (from search_chats).", mapOf("id" to p("integer", "Chat id")), listOf("id")),
        fn("open_app", "Open an installed app by name.", mapOf("name" to p("string", "App name, e.g. WhatsApp")), listOf("name")),
        fn("open_url", "Open a web address in the browser.", mapOf("url" to p("string", "Address")), listOf("url")),
        fn("set_alarm", "Set a wake-up alarm in the phone's clock app.",
            mapOf("hour" to p("integer", "0-23"), "minute" to p("integer", "0-59"), "label" to p("string", "Alarm label")), listOf("hour", "minute")),
        fn("set_timer", "Start a countdown timer.", mapOf("seconds" to p("integer", "Duration in seconds"), "label" to p("string", "Timer label")), listOf("seconds")),
        fn("dial", "Open the phone dialer with a number (the user presses call).", mapOf("number" to p("string", "Phone number")), listOf("number")),
        fn("flashlight", "Turn the flashlight on or off.", mapOf("on" to p("boolean", "true = on")), listOf("on")),
        fn("convert_currency", "Convert money between currencies with live (cached) exchange rates. Use this instead of guessing a rate.",
            mapOf("amount" to p("number", "Amount"), "from" to p("string", "3-letter code, e.g. USD"), "to" to p("string", "3-letter code, e.g. INR")), listOf("amount", "from", "to")),
        fn("calendar_day", "List the user's tasks that are due on a day (default today) – use for 'what is on my calendar / what do I have on Friday'.",
            mapOf("date" to p("string", "today, tomorrow or YYYY-MM-DD"))),
        fn("add_calendar_event", "Open the phone's calendar 'new event' screen filled in (the user taps Save). Use for meetings and appointments; use add_task for to-dos.",
            mapOf("title" to p("string", "Event title"), "date" to p("string", "today, tomorrow or YYYY-MM-DD"), "time" to p("string", "24h HH:mm (default 09:00)"),
                "minutes" to p("integer", "Length in minutes (default 60)")), listOf("title", "date"))
    )
}

object ExtraTools {
    private val automationTools = setOf("create_automation", "update_automation", "delete_automation", "set_automation_enabled")

    suspend fun run(g: AppGraph, name: String, a: JSONObject): ToolOutcome? =
        if (name in automationTools && kotlin.coroutines.coroutineContext[ToolSource]?.tag == "Automation")
            ToolOutcome("Not allowed: an automation run must not create or change automations. Just do the task and write the result.")
        else runTool(g, name, a)

    private suspend fun runTool(g: AppGraph, name: String, a: JSONObject): ToolOutcome? = when (name) {
        "web_search" -> {
            val q = a.getString("query")
            ToolOutcome(withContext(Dispatchers.IO) { Web.search(q) }, "Searched the web: ${q.take(50)}")
        }
        "create_automation" -> createAutomation(g, a)
        "list_automations" -> {
            val l = g.db.automationDao().all()
            ToolOutcome(
                if (l.isEmpty()) "No automations yet."
                else l.joinToString("\n") { "#${it.id} ${it.name} · ${AutomationScheduler.describe(it)} · ${if (it.enabled) "on" else "off"}" + (it.nextRunText()?.let { n -> " · next $n" } ?: "") }
            )
        }
        "delete_automation" -> {
            val id = a.getLong("id")
            val x = g.db.automationDao().get(id) ?: return ToolOutcome("No automation with that id.")
            AutomationScheduler.cancel(g.context, id)
            g.db.automationDao().delete(id)
            ToolOutcome("Deleted automation #$id.", "Deleted automation: ${x.name}")
        }
        "set_automation_enabled" -> {
            val x = g.db.automationDao().get(a.getLong("id")) ?: return ToolOutcome("No automation with that id.")
            val n = x.copy(enabled = a.getBoolean("enabled"))
            g.db.automationDao().update(n)
            AutomationScheduler.schedule(g.context, n)
            ToolOutcome("Automation #${n.id} is now ${if (n.enabled) "on" else "off"}.", "${if (n.enabled) "Enabled" else "Paused"} automation: ${n.name}")
        }
        "change_setting" -> changeSetting(g, a)
        "update_automation" -> updateAutomation(g, a)
        "check_permissions" -> ToolOutcome(PermissionInfo.describe(g.context))
        "request_permission" -> {
            val kind = PermissionInfo.canonical(a.getString("kind")) ?: return ToolOutcome("Unknown permission. Use notifications, microphone, exact_alarms, battery, phone, music, files or overlay.")
            if (PermissionInfo.granted(g.context, kind)) ToolOutcome("$kind is already allowed.")
            else if (UiBridge.send(UiCommand.RequestPermission(kind))) ToolOutcome("I put the $kind permission prompt on the user's screen. Tell them to tap Allow.", "Asked for permission: $kind")
            else ToolOutcome("The app is not on screen, so I could not show the prompt. Tell the user to open the app, or go to menu → Permissions & voice.")
        }
        "open_screen" -> {
            if (UiBridge.send(UiCommand.OpenScreen(a.getString("name")))) ToolOutcome("Opened.", "Opened: ${a.getString("name")}")
            else ToolOutcome("The app is not on screen, so I could not open it.")
        }
        "give_commitment_point" -> {
            val day = LocalDate.now().toEpochDay()
            val cur = g.db.historyDao().stat(day)?.manualPoints ?: 0
            val next = (cur + a.optInt("points", 1)).coerceIn(0, 4)
            g.taskRepo.setManualPoints(day, next)
            ToolOutcome("Manual commitment points today: $next.", "Commitment points: $next")
        }
        "search_chats" -> searchChats(g, a.optString("query").trim())
        "read_chat" -> {
            val id = a.getLong("id")
            val c = g.db.conversationDao().get(id) ?: return ToolOutcome("No chat with that id.")
            val msgs = g.db.chatDao().forConversation(id).filter { it.role != "action" }
            val body = msgs.joinToString("\n") { (if (it.role == "user") "User: " else "You: ") + it.content.take(500) }
            ToolOutcome("Chat #$id \"${c.title}\":\n" + body.take(7000))
        }
        "open_app" -> ToolOutcome(DeviceTools.openApp(g.context, a.getString("name")), "Open app: ${a.getString("name")}")
        "open_url" -> ToolOutcome(DeviceTools.openUrl(g.context, a.getString("url")), "Opened a link")
        "set_alarm" -> ToolOutcome(DeviceTools.setAlarm(g.context, a.getInt("hour"), a.getInt("minute"), a.optString("label", "P.R.I.V.A.C.Y.")), "Set an alarm")
        "convert_currency" -> ToolOutcome(com.privacy.app.tools.CurrencyRates.describe(g.context, a.getDouble("amount"), a.getString("from"), a.getString("to")), "Converted currency")
        "calendar_day" -> {
            val day = parseDay(a.optString("date").ifBlank { "today" }) ?: return ToolOutcome("I could not read that date. Use today, tomorrow or YYYY-MM-DD.")
            val due = g.db.taskDao().activeNow().filter { it.dueDay == day && it.status != com.privacy.app.data.TaskStatus.COMPLETED }
            ToolOutcome(if (due.isEmpty()) "Nothing is due on ${formatDay(day)}." else "Due on ${formatDay(day)}:\n" + due.joinToString("\n") { "#${it.id} ${it.title}" })
        }
        "add_calendar_event" -> {
            val day = parseDay(a.getString("date")) ?: return ToolOutcome("I could not read that date.")
            val t = try { java.time.LocalTime.parse(a.optString("time").ifBlank { "09:00" }) } catch (_: Exception) { java.time.LocalTime.of(9, 0) }
            val start = java.time.LocalDateTime.of(java.time.LocalDate.ofEpochDay(day), t)
            ToolOutcome(com.privacy.app.tools.CalendarTools.addEvent(g.context, a.getString("title"), start, a.optInt("minutes", 60)), "Calendar event: ${a.getString("title").take(40)}")
        }
        "set_timer" -> ToolOutcome(com.privacy.app.tools.TimerState.start(g.context, a.getInt("seconds"), a.optString("label", "")), "Started a timer")
        "dial" -> ToolOutcome(DeviceTools.dial(g.context, a.getString("number")), "Opened the dialer")
        "flashlight" -> ToolOutcome(DeviceTools.flashlight(g.context, a.getBoolean("on")), "Flashlight")
        else -> null
    }

    private suspend fun createAutomation(g: AppGraph, a: JSONObject): ToolOutcome {
        val action = when (a.getString("action").lowercase().replace(' ', '_')) {
            "notify_tasks", "tasks", "task_list" -> AutoAction.NOTIFY_TASKS
            "ai_prompt", "ai", "prompt" -> AutoAction.AI_PROMPT
            "notes_audio", "audio", "read_notes" -> AutoAction.NOTES_AUDIO
            else -> AutoAction.NOTIFY_TEXT
        }
        val minutes = AutomationScheduler.parseTime(a.getString("time")) ?: return ToolOutcome("I couldn't read the time \"${a.getString("time")}\". Use HH:mm, e.g. 19:00.")
        var message = a.optString("message").trim()
        // the AI creates automations from chat; if the user asked for sound, switch the audio option on
        if (action == AutoAction.AI_PROMPT && !message.startsWith(AutomationScheduler.AUDIO_TAG) &&
            Regex("(?i)\\b(make|create|generate|save|give|produce|record|convert|turn)\\b[^.\\n]{0,40}\\b(audio|mp3|podcast)\\b|read (it )?(aloud|out loud)|audio (file|version)|(only|just)\\s+(the\\s+)?(audio|voice|mp3)").containsMatchIn(message))
            message = AutomationScheduler.AUDIO_TAG + " " + message
        if (action == AutoAction.AI_PROMPT && message.isBlank()) return ToolOutcome("An ai_prompt automation needs `message` with the instructions.")
        val days = AutomationScheduler.parseDays(a.optString("days"))
        var date: Long? = parseDay(a.optString("date"))
        if (days == 0 && date == null) {
            // One-off at the next occurrence of that time.
            val now = ZonedDateTime.now()
            val today = LocalDate.now()
            date = if (today.atTime(LocalTime.of(minutes / 60, minutes % 60)).atZone(ZoneId.systemDefault()).isAfter(now)) today.toEpochDay() else today.plusDays(1).toEpochDay()
        }
        val ent = AutomationEntity(
            name = a.getString("name").trim().ifBlank { "Automation" }, action = action.name, message = message,
            timeMinutes = minutes, daysMask = if (date != null) 0 else days, dateDay = date
        )
        val id = g.db.automationDao().insert(ent)
        val saved = ent.copy(id = id)
        val next = AutomationScheduler.schedule(g.context, saved)
            ?: return ToolOutcome("Saved #$id but that time has already passed, so it will not run. Ask the user for a later time.")
        val notes = buildList {
            if (!AutomationScheduler.canExact(g.context)) add("Exact alarms are not allowed, so Android may delay it by several minutes. Call request_permission with kind exact_alarms.")
            if (!androidx.core.app.NotificationManagerCompat.from(g.context).areNotificationsEnabled()) add("Notifications are OFF for this app, so nothing will show. Call request_permission with kind notifications.")
        }
        val whenText = next.format(DateTimeFormatter.ofPattern("EEE d MMM 'at' h:mm a", Locale.getDefault()))
        return ToolOutcome(
            "Automation #$id created (${AutomationScheduler.describe(saved)}). First run: $whenText. The user can see/edit it in menu → Automations." + if (notes.isEmpty()) "" else "\nWARNING: " + notes.joinToString(" "),
            "Automation set: ${saved.name} · $whenText"
        )
    }

    private suspend fun updateAutomation(g: AppGraph, a: JSONObject): ToolOutcome {
        val x = g.db.automationDao().get(a.getLong("id")) ?: return ToolOutcome("No automation with that id.")
        var n = x
        if (a.has("time")) {
            val m = AutomationScheduler.parseTime(a.getString("time")) ?: return ToolOutcome("Couldn't read that time. Use HH:mm.")
            n = n.copy(timeMinutes = m)
        }
        if (a.has("message")) n = n.copy(message = a.getString("message"))
        if (a.has("name")) n = n.copy(name = a.getString("name"))
        if (a.has("days")) {
            val d = AutomationScheduler.parseDays(a.getString("days"))
            if (d != 0) n = n.copy(daysMask = d, dateDay = null)
        }
        g.db.automationDao().update(n)
        val next = AutomationScheduler.schedule(g.context, n)
        return ToolOutcome(
            "Automation #${n.id} updated" + (next?.let { " – next run ${it.format(DateTimeFormatter.ofPattern("EEE d MMM 'at' h:mm a", Locale.getDefault()))}" } ?: " (no future run)") + ".",
            "Updated automation: ${n.name}"
        )
    }

    private fun changeSetting(g: AppGraph, a: JSONObject): ToolOutcome {
        val s = g.settings
        val v = a.getString("value").trim()
        val on = v.lowercase() in setOf("true", "on", "yes", "1")
        val msg = when (a.getString("setting").lowercase()) {
            "user_name" -> { s.setUserName(v); "Saved the user's name: ${v.trim()}." }
            "user_gender" -> { s.setUserGender(when (v.lowercase().take(1)) { "f", "w" -> "female"; else -> "male" }); "Now addressing the user as ${s.userCall.value}." }
            "assistant_name" -> { s.setAssistantName(v.ifBlank { com.privacy.app.AppInfo.DEFAULT_ASSISTANT_NAME }); "The assistant is now called \"$v\"." }
            "assistant_gender" -> { s.setAssistantGender(when (v.lowercase().take(1)) { "m" -> "male"; "f" -> "female"; else -> "auto" }); "Assistant voice gender set to ${s.assistantGender.value}." }
            "natural_voice" -> { s.setUseGroqTts(on); "Natural voice is ${if (on) "on" else "off"}." }
            "accurate_listening" -> { s.setUseGroqStt(on); "Accurate listening is ${if (on) "on" else "off"}." }
            "tts_voice" -> { s.setTtsVoice(v.lowercase()); "Voice set to ${v.ifBlank { "automatic" }}." }
            "vision_model" -> { s.setVisionModel(v); "Vision model set to ${v.ifBlank { "automatic" }}." }
            "extra_instructions" -> {
                if (a.optString("mode") == "replace") s.setExtra(v) else s.setExtra((s.extraInstructions.value.trim() + "\n" + v).trim())
                "Saved the user's standing instructions."
            }
            "morning_enabled" -> {
                s.setMorningEnabled(on); Scheduler.scheduleMorning(g.context, on, s.morningMinutes.value, replace = true)
                "Morning briefing is ${if (on) "on" else "off"}."
            }
            "morning_time" -> {
                val m = AutomationScheduler.parseTime(v) ?: return ToolOutcome("Couldn't read that time. Use HH:mm.")
                s.setMorningMinutes(m); Scheduler.scheduleMorning(g.context, s.morningEnabled.value, m, replace = true)
                "Morning briefing time set to $v."
            }
            "font" -> {
                val f = com.privacy.app.ui.theme.AppFonts.all.firstOrNull { it.key.equals(v.trim(), true) || it.label.equals(v.trim(), true) }
                    ?: return ToolOutcome("Unknown font. Choose one of: " + com.privacy.app.ui.theme.AppFonts.all.joinToString { it.key })
                s.setFontKey(f.key); "Font changed to ${f.label}."
            }
            "companion" -> { val on = !v.lowercase().startsWith("off") && !v.lowercase().startsWith("false") && v.lowercase() != "no"; s.setCompanionOn(on); "The small companion is now ${if (on) "on" else "off"}." }
            "proactive" -> { val on = !v.lowercase().startsWith("off") && !v.lowercase().startsWith("false") && v.lowercase() != "no"; s.setProactiveOn(on); "Assistant-started chats are now ${if (on) "on" else "off"}." }
            "voice_language" -> { s.setVoiceLang(v); "Voice language set to ${v.ifBlank { "the phone language" }}." }
            "ambitious_goal" -> { s.setGoal(v); "Saved the user's ambitious goal." }
            else -> return ToolOutcome("Unknown setting. Available: user_name, user_gender, assistant_name, assistant_gender, extra_instructions, morning_enabled, morning_time, font, companion, proactive, voice_language, ambitious_goal, natural_voice, accurate_listening, tts_voice, vision_model.")
        }
        return ToolOutcome(msg, "Changed setting: ${a.getString("setting")}")
    }

    private suspend fun searchChats(g: AppGraph, q: String): ToolOutcome {
        val fmt = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault())
        fun date(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(fmt)
        if (q.isBlank()) {
            val l = g.db.conversationDao().recent(30)
            return ToolOutcome(if (l.isEmpty()) "No saved chats." else l.joinToString("\n") { "#${it.id} ${date(it.updatedAt)} — ${it.title}" })
        }
        val hits = g.db.chatDao().search(q, 15)
        if (hits.isEmpty()) return ToolOutcome("No messages mention \"$q\".")
        return ToolOutcome(hits.joinToString("\n") { "chat #${it.conversationId} · ${date(it.createdAt)} · ${if (it.role == "user") "user" else "you"}: ${it.content.take(220).replace('\n', ' ')}" })
    }
}
