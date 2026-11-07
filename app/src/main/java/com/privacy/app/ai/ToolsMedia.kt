package com.privacy.app.ai

import com.privacy.app.AppGraph
import com.privacy.app.media.FileFinder
import com.privacy.app.media.MusicPlayer
import com.privacy.app.media.MusicUi
import org.json.JSONObject

/** Tools for files, music, phone calls and Telegram. */
object MediaToolSpecs {
    private fun p(type: String, desc: String) = toolParam(type, desc)
    private fun fn(name: String, desc: String, props: Map<String, JSONObject> = emptyMap(), required: List<String> = emptyList()) = toolFn(name, desc, props, required)

    val list: List<JSONObject> = listOf(
        fn("find_file", "Search the phone's storage for a file by (part of) its name. Returns a numbered list. Tell the user what you found and ask which one to open, then call open_file with its number.",
            mapOf("query" to p("string", "Words from the file name, e.g. 'resume pdf' or 'kya yehi pyar hai'")), listOf("query")),
        fn("open_file", "Open a file from the LAST find_file list by its number. mp3/audio opens in the in-app music player, pictures and Markdown/text open inside the app, other types open in another app: pass `app` (an installed app's name) when the user picked one; with `make_default` true that app becomes the default for this file type.",
            mapOf("number" to p("integer", "Number from the list"), "app" to p("string", "App to open it with (optional)"), "make_default" to p("boolean", "Remember this app for this file type")), listOf("number")),
        fn("music", "Control the in-app music player. action: play (optionally with `query` = song name to find on the phone), pause, resume, next, previous, stop, open (show the player bar).",
            mapOf("action" to p("string", "play, pause, resume, next, previous, stop or open"), "query" to p("string", "Song name for action=play")), listOf("action")),
        fn("call", "Phone call: a contact name (looked up in the phone book) or a number. Calls directly when the Phone permission is allowed, otherwise opens the dialer.",
            mapOf("who" to p("string", "Contact name or phone number")), listOf("who")),
        fn("telegram_send", "Share something to Telegram. Use it when the user says \"share this to my Telegram\" / \"send it to <friend>\". With NO `friend` it goes ONLY to the user's own Telegram chat (never to other users of the bot). With `friend` (a name or @username) it is checked against the Friends list and sent to that one friend; if the name is not in the list nothing is sent. Put the real content in `text` (copy the chat answer / note text), or give `note_title` to send an existing note.",
            mapOf("text" to p("string", "Message / content to send (optional when note_title is given)"), "friend" to p("string", "Friend's name or @username (optional)"), "note_title" to p("string", "Title (or part of it) of a note to send instead of text")), emptyList()),
        fn("telegram_save_note", "Save the last Telegram message a friend sent (that was not saved yet) as a note tagged [Friend name]. Call it when the user says yes to \"should I save it as a note?\".", emptyMap(), emptyList()),
        fn("telegram_session", "Stop a Telegram sharing session: which = ai (shared AI chat), note (shared notes) or all.",
            mapOf("which" to p("string", "ai, note or all")), listOf("which")),
        fn("distracted_mode", "Switch the \"I'm Feeling Distracted\" mode on or off.", mapOf("on" to p("boolean", "true = on")), listOf("on"))
    )
}

object MediaTools {
    suspend fun run(g: AppGraph, name: String, a: JSONObject): ToolOutcome? {
      return when (name) {
        "find_file" -> {
            val q = a.getString("query")
            val r = FileFinder.search(g.context, q)
            if (r.isEmpty()) ToolOutcome("Nothing found for \"$q\"." + if (!FileFinder.hasAllFiles()) " Only music, pictures and videos can be searched until the user allows 'files' access (request_permission kind files)." else "")
            else ToolOutcome(FileFinder.describe(r) + "\nAsk the user which number to open.", "Searched files: ${q.take(40)}")
        }
        "open_file" -> {
            val n = a.getInt("number")
            val f = FileFinder.last.getOrNull(n - 1) ?: return ToolOutcome("There is no result #$n – run find_file first.")
            val app = a.optString("app").takeIf { it.isNotBlank() }
            val res = FileFinder.open(g.context, f, app)
            if (app != null && a.optBoolean("make_default") && !res.startsWith("NEED") && !res.startsWith("No app")) {
                val pm = g.context.packageManager
                pm.getInstalledApplications(0).firstOrNull { pm.getApplicationLabel(it).toString().contains(app, true) }?.let { FileFinder.setDefault(g.context, f.ext, it.packageName) }
            }
            val shown = FileFinder.takeChatImage()
            ToolOutcome(res, if (res.startsWith("NEED")) null else "Opened: ${f.name}" + (shown?.let { " [[img:$it]]" } ?: ""))
        }
        "music" -> {
            val ctx = g.context
            MusicPlayer.init(ctx)
            when (a.getString("action").lowercase()) {
                "play" -> {
                    if (!MusicPlayer.hasPermission(ctx)) return ToolOutcome("The music permission is not allowed yet. Call request_permission with kind music.")
                    if (MusicPlayer.tracks.value.isEmpty()) MusicPlayer.scan(ctx)
                    val q = a.optString("query")
                    MusicUi.focus.value = true
                    if (q.isBlank()) { MusicPlayer.toggle(); ToolOutcome("Playing.", "Music: play") }
                    else {
                        val hits = MusicPlayer.search(q, 5)
                        when {
                            hits.isEmpty() -> ToolOutcome("No song on the phone matches \"$q\". You can try find_file.")
                            hits.size == 1 || hits[0].title.equals(q, true) -> { MusicPlayer.play(ctx, hits[0], hits); ToolOutcome("Playing ${hits[0].title}.", "Playing: ${hits[0].title}") }
                            else -> { MusicPlayer.play(ctx, hits[0], hits); ToolOutcome("Playing ${hits[0].title}. Other matches: ${hits.drop(1).joinToString { it.title }}.", "Playing: ${hits[0].title}") }
                        }
                    }
                }
                "pause" -> { MusicPlayer.pause(); ToolOutcome("Paused.") }
                "resume" -> { if (!MusicPlayer.playing.value) MusicPlayer.toggle(); ToolOutcome("Playing.") }
                "next" -> { MusicPlayer.next(); ToolOutcome("Next song.") }
                "previous" -> { MusicPlayer.previous(); ToolOutcome("Previous song.") }
                "stop" -> { MusicPlayer.stop(); ToolOutcome("Stopped.") }
                else -> { MusicUi.focus.value = true; ToolOutcome("Showing the music player at the top of the chat (swipe the stats bar).") }
            }
        }
        "call" -> {
            val r = DeviceTools.call(g.context, a.getString("who"))
            ToolOutcome(r, if (r.startsWith("Calling") || r.startsWith("Opened")) "Call: ${a.getString("who").take(30)}" else null)
        }
        "telegram_send" -> {
            var text = a.optString("text")
            val nt = a.optString("note_title")
            if (nt.isNotBlank()) {
                val n = g.db.noteDao().search(nt).firstOrNull() ?: return ToolOutcome("No note matches “$nt”.")
                text = n.title + "\n\n" + n.body
            }
            if (text.isBlank()) return ToolOutcome("Nothing to send – give the text or a note title.")
            val r = TelegramHub.send(g, text, a.optString("friend").takeIf { it.isNotBlank() })
            ToolOutcome(r, if (r.startsWith("Sent")) "Telegram: sent" else null)
        }
        "telegram_save_note" -> { val r = TelegramHub.saveLastFriendAsNote(g); ToolOutcome(r, if (r.startsWith("Saved")) "Saved Telegram message as note" else null) }
        "telegram_session" -> {
            when (a.optString("which")) {
                "ai" -> TelegramHub.endAi(g)
                "note" -> TelegramHub.noteSessions.value.keys.toList().forEach { TelegramHub.endNote(g, it) }
                else -> TelegramHub.endAll(g, lost = false)
            }
            ToolOutcome("Telegram sharing stopped.", "Telegram sharing stopped")
        }
        "distracted_mode" -> { g.settings.setDistractedMode(a.getBoolean("on")); ToolOutcome("Distracted mode is now ${if (a.getBoolean("on")) "ON" else "off"}.") }
        else -> null
    }
    }
}
