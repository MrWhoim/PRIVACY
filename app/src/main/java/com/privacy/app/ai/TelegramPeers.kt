package com.privacy.app.ai

import android.content.Context
import com.privacy.app.AppGraph
import com.privacy.app.data.ChatEntity
import com.privacy.app.data.ConversationEntity
import com.privacy.app.data.NoteEntity
import com.privacy.app.work.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * App ↔ app sync through two Telegram bots (Telegram's "Bot-to-Bot Communication Mode").
 *
 * Both people create their own bot and switch on "Bot-to-Bot Communication Mode" for it in @BotFather. After pairing (one sends a request to the
 * other's @bot, the other accepts) the two APPS talk to each other directly in a private chat between the bots:
 *  • a shared note appears as a note in the friend's app (view only until he asks for edit access; the host allows it) and both copies follow every change;
 *  • a shared AI chat appears as a new chat in the friend's app; both sides see every message, and the host's AI answers.
 * Messages between the apps are plain text lines starting with [PREFIX] (long ones are split into parts). Nothing goes through any server of ours.
 */
object TelegramPeers {
    const val PREFIX = "PRVC1|"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Peer(val bot: String, val name: String, val state: String)   // state: out = I asked, in = he asked, ok = paired
    /** noteId = the note in THIS app, rid = the note's id in the other app, asGuest = I am the guest who is editing and the host asks to take over. */
    data class EditReq(val bot: String, val name: String, val noteId: Long, val title: String, val rid: Long = noteId, val asGuest: Boolean = false)
    /** One live connection: kind ai | note, role host | guest, [id] is what cancel() needs. */
    data class Live(val kind: String, val role: String, val who: String, val bot: String, val ref: Long, val key: String)
    data class Info(val kind: String, val mode: String, val who: String)     // kind: host | guest, mode: view | edit

    /** An invitation from a friend's app that waits for my Accept / Reject. */
    data class Invite(val key: String, val kind: String, val bot: String, val name: String, val title: String)

    val rev = MutableStateFlow(0)
    private val _editReqs = MutableStateFlow<List<EditReq>>(emptyList())
    val editReqs: StateFlow<List<EditReq>> = _editReqs.asStateFlow()
    private fun bump() { rev.value = rev.value + 1 }

    private fun prefs(g: AppGraph) = g.context.getSharedPreferences("tg_peers", Context.MODE_PRIVATE)
    private fun obj(g: AppGraph, k: String) = runCatching { JSONObject(prefs(g).getString(k, "{}") ?: "{}") }.getOrDefault(JSONObject())
    private fun save(g: AppGraph, k: String, o: JSONObject) { prefs(g).edit().putString(k, o.toString()).apply() }
    private fun me(g: AppGraph) = g.settings.userName.value.ifBlank { g.settings.userCall.value }.ifBlank { "A friend" }

    // ------------------------------------------------------------------ peers
    fun peers(g: AppGraph): List<Peer> = runCatching {
        val a = JSONArray(prefs(g).getString("peers", "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it); Peer(o.getString("bot"), o.optString("name"), o.optString("state")) }
    }.getOrDefault(emptyList())

    private fun setPeers(g: AppGraph, list: List<Peer>) {
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("bot", it.bot).put("name", it.name).put("state", it.state)) }
        prefs(g).edit().putString("peers", a.toString()).apply(); bump()
    }
    private fun clean(bot: String) = bot.trim().removePrefix("@").removePrefix("https://t.me/").lowercase()
    private fun isOk(g: AppGraph, bot: String) = peers(g).any { it.bot == bot && it.state == "ok" }
    private fun nameOf(g: AppGraph, bot: String) = peers(g).firstOrNull { it.bot == bot }?.name?.ifBlank { "@$bot" } ?: "@$bot"

    /** The user's own bot name as saved (no network). */
    fun myBotSaved(g: AppGraph): String = prefs(g).getString("my_bot", "") ?: ""
    fun rememberMyBot(g: AppGraph, name: String) { prefs(g).edit().putString("my_bot", name.removePrefix("@").lowercase()).apply(); bump() }
    /** Own bot name; asks Telegram once if it is not saved yet (call off the main thread). */
    fun myBotName(g: AppGraph): String = myBot(g)
    private fun myBot(g: AppGraph): String {
        val saved = prefs(g).getString("my_bot", "") ?: ""
        if (saved.isNotBlank()) return saved
        val t = g.settings.telegramToken.value
        val u = runCatching { TelegramHub.api(t, "getMe").getJSONObject("result").optString("username") }.getOrDefault("")
        if (u.isNotBlank()) prefs(g).edit().putString("my_bot", u.lowercase()).apply()
        return u.lowercase()
    }

    /** Ask a friend's bot to pair. Returns a short status line. */
    fun pair(g: AppGraph, botName: String): String {
        val bot = clean(botName)
        if (bot.isBlank()) return "Type your friend's bot name, like @friend_bot."
        if (g.settings.telegramToken.value.isBlank()) return "Connect your own bot first (token above)."
        if (peers(g).any { it.bot == bot && it.state == "ok" }) return "Already paired."
        setPeers(g, peers(g).filter { it.bot != bot } + Peer(bot, "", "out"))
        scope.launch {
            val mine = myBot(g)
            if (mine == bot) {
                setPeers(g, peers(g).filter { it.bot != bot })
                setStatus(g, "@$bot is YOUR OWN bot. A bot cannot message itself – type your friend's bot name.")
                return@launch
            }
            setStatus(g, "")
            val ok = send(g, bot, JSONObject().put("t", "pair").put("name", me(g)).put("bot", mine))
            if (!ok) setStatus(g, "Couldn't reach @$bot: ${lastErr.ifBlank { "no answer from Telegram" }}.\nBoth bots need “Bot-to-Bot Communication Mode” switched on in @BotFather (Bot Settings), and the bot must exist.")
        }
        return "Request sent to @$bot. Your friend accepts it inside his app – his name then appears here by itself."
    }
    private val _dismissed = MutableStateFlow<Set<String>>(emptySet())
    val dismissed: StateFlow<Set<String>> = _dismissed.asStateFlow()
    fun dismissRequest(bot: String) { _dismissed.value = _dismissed.value + bot }
    /** Shows the pairing-request pop-up again. */
    fun reshowRequests() { _dismissed.value = emptySet() }
    @Volatile var status = ""
    @Volatile private var lastErr = ""
    private fun setStatus(g: AppGraph, s: String) { status = s; bump() }

    fun accept(g: AppGraph, bot: String, yes: Boolean) {
        val p = peers(g).firstOrNull { it.bot == bot } ?: return
        if (yes) { setPeers(g, peers(g).map { if (it.bot == bot) it.copy(state = "ok") else it }); scope.launch { send(g, bot, JSONObject().put("t", "pair_ok").put("name", me(g)).put("bot", myBot(g))) } }
        else { setPeers(g, peers(g).filter { it.bot != bot }); scope.launch { send(g, bot, JSONObject().put("t", "unpair")) } }
        if (p.state == "in") bump()
    }

    /** Used by the backup import: a friend that was paired on the old phone. */
    fun restorePeer(g: AppGraph, bot: String, name: String) { setPeers(g, peers(g).filter { it.bot != bot } + Peer(bot, name, "ok")) }

    fun remove(g: AppGraph, bot: String) {
        scope.launch { send(g, bot, JSONObject().put("t", "unpair")) }
        setPeers(g, peers(g).filter { it.bot != bot })
    }

    // ------------------------------------------------------------------ transport (private chat bot → bot, long texts split in parts)
    private fun send(g: AppGraph, bot: String, o: JSONObject): Boolean {
        val token = g.settings.telegramToken.value
        if (token.isBlank()) return false
        val data = o.toString()
        val id = System.currentTimeMillis().toString(36) + (0..99).random()
        val parts = data.chunked(3300)
        var ok = true
        parts.forEachIndexed { i, piece ->
            val r = runCatching { TelegramHub.api(token, "sendMessage", mapOf("chat_id" to "@$bot", "text" to "$PREFIX$id|${i + 1}|${parts.size}|$piece")) }.getOrNull()
            if (r?.optBoolean("ok") != true) { ok = false; lastErr = r?.optString("description").orEmpty().ifBlank { "network error" } }
            if (parts.size > 1) Thread.sleep(400)
        }
        return ok
    }

    private val buffers = HashMap<String, MutableMap<Int, String>>()

    /** A Telegram message that starts with the prefix arrived from bot [fromBot]. */
    suspend fun onMessage(g: AppGraph, fromBot: String, text: String) {
        val bot = clean(fromBot)
        val rest = text.removePrefix(PREFIX)
        val p = rest.split('|', limit = 4)
        if (p.size < 4) return
        val total = p[2].toIntOrNull() ?: return
        val idx = p[1].toIntOrNull() ?: return
        val full = if (total == 1) p[3] else {
            val buf = buffers.getOrPut(bot + p[0]) { HashMap() }
            buf[idx] = p[3]
            if (buf.size < total) return
            buffers.remove(bot + p[0]); (1..total).joinToString("") { buf[it] ?: "" }
        }
        val o = runCatching { JSONObject(full) }.getOrNull() ?: return
        runCatching { handle(g, bot, o) }
    }

    private suspend fun handle(g: AppGraph, bot: String, o: JSONObject) {
        when (o.optString("t")) {
            "pair" -> {
                val nm = o.optString("name").ifBlank { "@$bot" }
                if (isOk(g, bot)) { send(g, bot, JSONObject().put("t", "pair_ok").put("name", me(g)).put("bot", myBot(g))); return }
                _dismissed.value = _dismissed.value - bot
                setPeers(g, peers(g).filter { it.bot != bot } + Peer(bot, nm, "in"))
                Notifier.post(g.context, 7100, Notifier.CH_AUTOMATION, "Friend request", "$nm (@$bot) wants to sync notes and chats with you.", target = "settings")
            }
            "pair_ok" -> setPeers(g, peers(g).filter { it.bot != bot } + Peer(bot, o.optString("name").ifBlank { nameOf(g, bot) }, "ok"))
            "unpair" -> setPeers(g, peers(g).filter { it.bot != bot })
            else -> if (isOk(g, bot)) handleSynced(g, bot, o)
        }
    }

    private suspend fun handleSynced(g: AppGraph, bot: String, o: JSONObject) {
        val rid = o.optLong("rid")
        when (o.optString("t")) {
            // ---------------- notes
            "note_invite" -> {
                val key = "$bot|$rid"
                val gm = obj(g, "guestNotes")
                val title = o.optString("title"); val body = o.optString("body")
                val existing = gm.optJSONObject(key)?.optLong("local", -1L) ?: -1L
                if (existing > 0 && g.db.noteDao().get(existing) != null) { applyNote(g, existing, title, body, guest = true); return }
                // a new share: wait for Accept / Reject
                val inv = obj(g, "invIn"); inv.put("note|$key", o); save(g, "invIn", inv); bump()
                Notifier.post(g.context, 7101, Notifier.CH_AUTOMATION, "${nameOf(g, bot)} wants to share a note", "“$title” – open the app to accept or reject.", target = "notes")
            }
            "note_up" -> {
                val gm = obj(g, "guestNotes")
                val gl = gm.optJSONObject("$bot|$rid")?.optLong("local", -1L) ?: -1L
                if (gl > 0) { applyNote(g, gl, o.optString("title"), o.optString("body"), guest = true); return }
                // I am the host: a friend with edit rights changed my note
                val hs = obj(g, "hostShares")
                if (hs.optJSONObject(rid.toString())?.optString(bot) == "edit") {
                    applyNote(g, rid, o.optString("title"), o.optString("body"), guest = false)
                    pushNote(g, rid, except = bot)
                }
            }
            "edit_req" -> {
                if (o.optBoolean("h")) {
                    // the host asks me (the guest who is editing) to hand editing back
                    val gm = obj(g, "guestNotes"); val e = gm.optJSONObject("$bot|$rid")
                    if (e == null || e.optString("mode") != "edit") { send(g, bot, JSONObject().put("t", "edit_no").put("rid", rid).put("why", "Nobody is editing on that side.")); return }
                    val local = e.optLong("local")
                    val n = g.db.noteDao().get(local)
                    _editReqs.value = _editReqs.value + EditReq(bot, nameOf(g, bot), local, n?.title ?: "a note", rid = rid, asGuest = true)
                    Notifier.post(g.context, 7102, Notifier.CH_AUTOMATION, "Edit request", "${nameOf(g, bot)} wants to edit “${n?.title ?: "a note"}”.", target = "notes")
                    return
                }
                val hs = obj(g, "hostShares")
                val shares = hs.optJSONObject(rid.toString())
                if (shares?.has(bot) == true) {
                    val other = shares.keys().asSequence().firstOrNull { it != bot && shares.optString(it) == "edit" }
                    if (other != null) { send(g, bot, JSONObject().put("t", "edit_no").put("rid", rid).put("why", "${nameOf(g, other)} is editing right now.")); return }
                    val n = g.db.noteDao().get(rid)
                    _editReqs.value = _editReqs.value.filter { !(it.bot == bot && it.noteId == rid) } + EditReq(bot, nameOf(g, bot), rid, n?.title ?: "a note")
                    Notifier.post(g.context, 7102, Notifier.CH_AUTOMATION, "Edit request", "${nameOf(g, bot)} wants to edit “${n?.title ?: "a note"}”.", target = "notes")
                }
            }
            "edit_ok" -> {   // I am a guest: the host handed editing to me
                val gm = obj(g, "guestNotes"); val e = gm.optJSONObject("$bot|$rid") ?: return
                e.put("mode", "edit"); save(g, "guestNotes", gm); bump()
                Notifier.post(g.context, 7103, Notifier.CH_AUTOMATION, nameOf(g, bot), "You can edit the shared note now. ${nameOf(g, bot)} is in view mode.", target = "notes")
            }
            "edit_ok_h" -> {   // I am the host: the editing guest handed editing back to me
                val hs = obj(g, "hostShares")
                hs.optJSONObject(rid.toString())?.put(bot, "view"); save(g, "hostShares", hs); bump()
                Notifier.post(g.context, 7103, Notifier.CH_AUTOMATION, nameOf(g, bot), "You can edit the shared note again.", target = "notes")
            }
            "edit_rel" -> {   // a guest released editing
                val hs = obj(g, "hostShares")
                hs.optJSONObject(rid.toString())?.let { if (it.has(bot)) { it.put(bot, "view"); save(g, "hostShares", hs); bump() } }
            }
            "edit_no" -> Notifier.post(g.context, 7103, Notifier.CH_AUTOMATION, nameOf(g, bot), "Edit request declined. " + o.optString("why"), target = "notes")
            "note_end" -> {
                val gm = obj(g, "guestNotes")
                if (gm.has("$bot|$rid")) { gm.remove("$bot|$rid"); save(g, "guestNotes", gm); bump() }
                val hs = obj(g, "hostShares")
                hs.optJSONObject(rid.toString())?.let { it.remove(bot); if (it.length() == 0) hs.remove(rid.toString()); save(g, "hostShares", hs); bump() }
            }
            // ---------------- shared AI chat
            "ai_invite" -> {
                val key = "$bot|$rid"
                if (obj(g, "aiGuest").has(key)) return
                val inv = obj(g, "invIn"); inv.put("ai|$key", o); save(g, "invIn", inv); bump()
                Notifier.post(g.context, 7104, Notifier.CH_AUTOMATION, "${nameOf(g, bot)} wants to share their AI", "Open the app to accept or reject.", target = "chat")
            }
            "inv_ok" -> {
                val k = o.optString("k")
                val po = obj(g, "pendOut"); po.remove("$k|$bot"); save(g, "pendOut", po); bump()
                Notifier.post(g.context, 7105, Notifier.CH_AUTOMATION, nameOf(g, bot), "Accepted your ${if (k.startsWith("ai")) "AI chat" else "note"} share.", target = if (k.startsWith("ai")) "chat" else "notes")
                if (k.startsWith("note")) pushNote(g, k.substringAfter('|').toLong(), only = listOf(bot))   // he may have missed edits made while the request was open
            }
            "inv_no" -> {
                val k = o.optString("k")
                val po = obj(g, "pendOut"); po.remove("$k|$bot"); save(g, "pendOut", po)
                val id = k.substringAfter('|')
                if (k.startsWith("ai")) {
                    val hm = obj(g, "aiHost"); val arr = hm.optJSONArray(id)
                    if (arr != null) { val keep = JSONArray(); (0 until arr.length()).map { arr.getString(it) }.filter { it != bot }.forEach { keep.put(it) }; if (keep.length() == 0) hm.remove(id) else hm.put(id, keep); save(g, "aiHost", hm) }
                } else {
                    val hs = obj(g, "hostShares"); hs.optJSONObject(id)?.let { it.remove(bot); if (it.length() == 0) hs.remove(id); save(g, "hostShares", hs) }
                }
                bump()
                Notifier.post(g.context, 7105, Notifier.CH_AUTOMATION, nameOf(g, bot), "Declined your ${if (k.startsWith("ai")) "AI chat" else "note"} share.", target = if (k.startsWith("ai")) "chat" else "notes")
            }
            "ai_msg", "ai_reply" -> {
                val key = "$bot|$rid"
                val am = obj(g, "aiGuest")
                val gc = if (am.has(key)) am.optLong(key) else -1L
                if (gc > 0) {   // I am the guest: show what the host (or another member) wrote and what the AI answered
                    if (o.optString("t") == "ai_reply") g.db.chatDao().insert(ChatEntity(role = "assistant", content = o.optString("text"), conversationId = gc))
                    else g.db.chatDao().insert(ChatEntity(role = "user", content = "[[from:${o.optString("from").ifBlank { nameOf(g, bot) }}]]${o.optString("text")}", conversationId = gc))
                    return
                }
                // I am the host: a member wrote into my shared chat
                val members = obj(g, "aiHost").optJSONArray(rid.toString())
                if (o.optString("t") == "ai_msg" && members != null && (0 until members.length()).any { members.getString(it) == bot }) {
                    runCatching { g.chatService.ask("[[from:${nameOf(g, bot)}]]" + o.optString("text"), fromTelegram = true, cidOverride = rid) }
                }
            }
            "ai_leave" -> {   // a guest left my shared chat
                val hm = obj(g, "aiHost"); val arr = hm.optJSONArray(rid.toString()) ?: return
                val keep = JSONArray(); (0 until arr.length()).map { arr.getString(it) }.filter { it != bot }.forEach { keep.put(it) }
                if (keep.length() == 0) hm.remove(rid.toString()) else hm.put(rid.toString(), keep)
                save(g, "aiHost", hm); bump()
                g.db.chatDao().insert(ChatEntity(role = "action", content = "${nameOf(g, bot)} left the shared chat", conversationId = rid))
            }
            "ai_end" -> {
                val am = obj(g, "aiGuest"); val key = "$bot|$rid"
                if (am.has(key)) { val c = am.optLong(key); am.remove(key); save(g, "aiGuest", am); g.db.chatDao().insert(ChatEntity(role = "action", content = "Sharing ended", conversationId = c)); bump() }
            }
        }
    }

    // ------------------------------------------------------------------ notes
    fun info(g: AppGraph, noteId: Long): Info? {
        val gm = obj(g, "guestNotes")
        for (k in gm.keys()) { val e = gm.getJSONObject(k); if (e.optLong("local") == noteId) return Info("guest", e.optString("mode", "view"), e.optString("who")) }
        val hs = obj(g, "hostShares").optJSONObject(noteId.toString()) ?: return null
        val active = hs.keys().asSequence().filter { !pendingOut(g, "note|$noteId", it) }.toList()   // friends who have not accepted yet do not count
        if (active.isEmpty()) return null
        val editor = active.firstOrNull { hs.optString(it) == "edit" }
        // only ONE person edits: if a friend edits, my copy is view only
        return if (editor != null) Info("host", "edit", nameOf(g, editor)) else Info("host", "view", active.joinToString { nameOf(g, it) })
    }
    /** True when this copy of the note must not be edited here (a guest without edit rights, or a host while a friend edits). */
    fun isReadOnly(g: AppGraph, noteId: Long): Boolean = info(g, noteId).let { it != null && ((it.kind == "guest" && it.mode == "view") || (it.kind == "host" && it.mode == "edit")) }

    fun shareNote(g: AppGraph, noteId: Long, bots: List<String>): String {
        val ok = bots.map { clean(it) }.filter { isOk(g, it) }
        if (ok.isEmpty()) return "Pick a paired friend."
        val hs = obj(g, "hostShares"); val cur = hs.optJSONObject(noteId.toString()) ?: JSONObject()
        ok.forEach { if (!cur.has(it)) { cur.put(it, "view"); markPending(g, "note|$noteId", it) } }
        hs.put(noteId.toString(), cur); save(g, "hostShares", hs); bump()
        scope.launch { pushNote(g, noteId, only = ok) }
        return "Request sent to ${ok.joinToString { nameOf(g, it) }}. The note opens in their app once they accept (view only for them)."
    }

    private suspend fun pushNote(g: AppGraph, noteId: Long, except: String? = null, only: List<String>? = null) {
        val n = g.db.noteDao().get(noteId) ?: return
        val body = n.body
        val title = NoteRules.splitTag(n.title)?.takeIf { it.first == "Shared" }?.second ?: n.title
        val hs = obj(g, "hostShares").optJSONObject(noteId.toString())
        if (hs != null) hs.keys().asSequence().filter { it != except && (only == null || it in only) }.toList().forEach {
            send(g, it, JSONObject().put("t", if (only != null) "note_invite" else "note_up").put("rid", noteId).put("title", title).put("body", body).put("rev", n.updatedAt))
        }
        val gm = obj(g, "guestNotes")
        for (k in gm.keys()) { val e = gm.getJSONObject(k)
            if (e.optLong("local") == noteId && e.optString("mode") == "edit" && k.substringBefore('|') != except)
                send(g, k.substringBefore('|'), JSONObject().put("t", "note_up").put("rid", k.substringAfter('|').toLong()).put("title", title).put("body", body).put("rev", n.updatedAt))
        }
    }

    private val noteJobs = HashMap<Long, Job>()
    /** A note was saved in this app: if it is shared, send the new text (after a short pause, so typing is not sent letter by letter). */
    fun noteChanged(g: AppGraph, noteId: Long) {
        if (info(g, noteId) == null) return
        noteJobs[noteId]?.cancel()
        noteJobs[noteId] = scope.launch { delay(1200); pushNote(g, noteId) }
    }

    private suspend fun applyNote(g: AppGraph, localId: Long, title: String, body: String, guest: Boolean) {
        val n = g.db.noteDao().get(localId) ?: return
        val t = if (guest) NoteRules.tagged("Shared", title) else title
        if (n.body == body && n.title == t) return
        g.db.noteDao().update(n.copy(title = t, body = body, updatedAt = System.currentTimeMillis()))
        bump()
    }

    /** I only view this note: ask whoever edits it right now (the host, or the guest who has it) to hand editing over. */
    fun requestEdit(g: AppGraph, noteId: Long) {
        val gm = obj(g, "guestNotes")
        for (k in gm.keys()) if (gm.getJSONObject(k).optLong("local") == noteId)
            scope.launch { send(g, k.substringBefore('|'), JSONObject().put("t", "edit_req").put("rid", k.substringAfter('|').toLong())) }
        val shares = obj(g, "hostShares").optJSONObject(noteId.toString())
        val editor = shares?.keys()?.asSequence()?.firstOrNull { shares.optString(it) == "edit" }
        if (editor != null) scope.launch { send(g, editor, JSONObject().put("t", "edit_req").put("h", true).put("rid", noteId)) }
    }

    /** The editor answers a request: yes = he goes to view mode and the asker edits. */
    fun answerEdit(g: AppGraph, r: EditReq, yes: Boolean) {
        _editReqs.value = _editReqs.value - r
        if (!yes) { scope.launch { send(g, r.bot, JSONObject().put("t", "edit_no").put("rid", r.rid)) }; return }
        if (r.asGuest) {
            // I am the guest editor: send my last text, then become view only
            scope.launch {
                pushNote(g, r.noteId)
                val gm = obj(g, "guestNotes"); gm.optJSONObject("${r.bot}|${r.rid}")?.put("mode", "view"); save(g, "guestNotes", gm); bump()
                send(g, r.bot, JSONObject().put("t", "edit_ok_h").put("rid", r.rid))
            }
        } else {
            // I am the host editor: friend edits from now on, my copy is view only
            val hs = obj(g, "hostShares"); hs.optJSONObject(r.noteId.toString())?.put(r.bot, "edit"); save(g, "hostShares", hs); bump()
            scope.launch { pushNote(g, r.noteId, only = listOf(r.bot)); send(g, r.bot, JSONObject().put("t", "edit_ok").put("rid", r.noteId)) }
        }
    }

    /** A guest who edits gives editing back to the host. */
    fun releaseEdit(g: AppGraph, noteId: Long) {
        val gm = obj(g, "guestNotes")
        for (k in gm.keys().asSequence().toList()) { val e = gm.getJSONObject(k); if (e.optLong("local") == noteId && e.optString("mode") == "edit") {
            val key = k
            scope.launch {
                pushNote(g, noteId)
                val gm2 = obj(g, "guestNotes"); gm2.optJSONObject(key)?.put("mode", "view"); save(g, "guestNotes", gm2); bump()
                send(g, key.substringBefore('|'), JSONObject().put("t", "edit_rel").put("rid", key.substringAfter('|').toLong()))
            }
        } }
    }

    /** Host: stop sharing one note with one friend. */
    fun endNoteWith(g: AppGraph, noteId: Long, bot: String) {
        val hs = obj(g, "hostShares"); val cur = hs.optJSONObject(noteId.toString()) ?: return
        cur.remove(bot); if (cur.length() == 0) hs.remove(noteId.toString())
        save(g, "hostShares", hs); bump()
        scope.launch { send(g, bot, JSONObject().put("t", "note_end").put("rid", noteId)) }
    }

    /** Stop sharing a note (host) or stop following it (guest: the copy stays as a normal note). */
    fun endNote(g: AppGraph, noteId: Long) {
        val gm = obj(g, "guestNotes")
        for (k in gm.keys().asSequence().toList()) if (gm.getJSONObject(k).optLong("local") == noteId) {
            val b = k.substringBefore('|'); val r = k.substringAfter('|').toLong()
            gm.remove(k); save(g, "guestNotes", gm); scope.launch { send(g, b, JSONObject().put("t", "note_end").put("rid", r)) }
        }
        val hs = obj(g, "hostShares"); val cur = hs.optJSONObject(noteId.toString())
        if (cur != null) { cur.keys().asSequence().toList().forEach { b -> scope.launch { send(g, b, JSONObject().put("t", "note_end").put("rid", noteId)) } }; hs.remove(noteId.toString()); save(g, "hostShares", hs) }
        bump()
    }

    // ------------------------------------------------------------------ shared AI chat
    fun shareAi(g: AppGraph, bots: List<String>): String {
        val ok = bots.map { clean(it) }.filter { isOk(g, it) }
        if (ok.isEmpty()) return "Pick a paired friend."
        val names = ok.joinToString(" & ") { nameOf(g, it) }
        scope.launch {
            // a fresh chat for this connection, opened right away
            val cid = g.db.conversationDao().insert(ConversationEntity(title = "Telegram AI $names"))
            g.db.chatDao().insert(ChatEntity(role = "action", content = "Your AI is shared with $names", conversationId = cid))
            val hm = obj(g, "aiHost"); val arr = JSONArray(); ok.forEach { arr.put(it) }
            hm.put(cid.toString(), arr); save(g, "aiHost", hm)
            ok.forEach { markPending(g, "ai|$cid", it) }
            bump()
            g.settings.setCurrentConversation(cid)
            ok.forEach { send(g, it, JSONObject().put("t", "ai_invite").put("rid", cid).put("host", me(g))) }
        }
        return "Request sent to $names. Once they accept, the chat “Telegram AI $names” works on both sides."
    }

    private fun hostBots(g: AppGraph, cid: Long): List<String> {
        val a = obj(g, "aiHost").optJSONArray(cid.toString()) ?: return emptyList()
        return (0 until a.length()).map { a.getString(it) }
    }

    /** The user wrote [text] in conversation [cid]. If it is a chat shared WITH me, the text goes to the host and the local AI stays silent (returns true). */
    fun onUserMessage(g: AppGraph, cid: Long, text: String): Boolean {
        val am = obj(g, "aiGuest")
        for (k in am.keys()) if (am.optLong(k) == cid) {
            val b = k.substringBefore('|'); val r = k.substringAfter('|').toLong()
            scope.launch { send(g, b, JSONObject().put("t", "ai_msg").put("rid", r).put("from", me(g)).put("text", text.replace(Regex("^\\[\\[from:[^\\]]+\\]\\]"), ""))) }
            return true
        }
        // a chat I share: copy what is written (by me or by a member) to the other members
        val bots = hostBots(g, cid)
        if (bots.isNotEmpty()) {
            val from = Regex("^\\[\\[from:([^\\]]+)\\]\\]").find(text)?.groupValues?.get(1) ?: me(g)
            val clean = text.replace(Regex("^\\[\\[from:[^\\]]+\\]\\]"), "")
            scope.launch { bots.filter { nameOf(g, it) != from }.forEach { send(g, it, JSONObject().put("t", "ai_msg").put("rid", cid).put("from", from).put("text", clean)) } }
        }
        return false
    }

    /** The AI answered in conversation [cid]: members of a shared chat get the answer. */
    fun onAiReply(g: AppGraph, cid: Long, text: String) {
        val bots = hostBots(g, cid)
        if (bots.isEmpty()) return
        scope.launch { bots.forEach { send(g, it, JSONObject().put("t", "ai_reply").put("rid", cid).put("text", text)) } }
    }

    fun endAi(g: AppGraph, cid: Long) {
        val hm = obj(g, "aiHost"); val bots = hostBots(g, cid)
        scope.launch { bots.forEach { send(g, it, JSONObject().put("t", "ai_end").put("rid", cid)) } }
        hm.remove(cid.toString()); save(g, "aiHost", hm); bump()
    }
    fun isSharedChat(g: AppGraph, cid: Long) = hostBots(g, cid).isNotEmpty()

    // ------------------------------------------------------------------ invitations (Accept / Reject pop-up)
    fun invites(g: AppGraph): List<Invite> {
        val inv = obj(g, "invIn")
        return inv.keys().asSequence().map { k ->
            val o = inv.getJSONObject(k); val kind = k.substringBefore('|'); val bot = k.substringAfter('|').substringBefore('|')
            Invite(k, kind, bot, nameOf(g, bot), o.optString("title"))
        }.toList()
    }

    /** The user answers an invitation. Accept creates the chat / note here and opens it. */
    fun answerInvite(g: AppGraph, inv: Invite, yes: Boolean) {
        val all = obj(g, "invIn"); val o = all.optJSONObject(inv.key) ?: return
        all.remove(inv.key); save(g, "invIn", all); bump()
        val rid = o.optLong("rid")
        scope.launch {
            if (!yes) { send(g, inv.bot, JSONObject().put("t", "inv_no").put("k", "${inv.kind}|$rid")); return@launch }
            if (inv.kind == "note") {
                val title = o.optString("title"); val body = o.optString("body")
                val local = g.db.noteDao().insert(NoteEntity(title = NoteRules.tagged("Shared", title), body = body))
                val gm = obj(g, "guestNotes")
                gm.put("${inv.bot}|$rid", JSONObject().put("local", local).put("mode", "view").put("who", nameOf(g, inv.bot))); save(g, "guestNotes", gm); bump()
                com.privacy.app.AppState.openNoteId.value = local
                com.privacy.app.AppState.pendingScreen.value = "notes"
            } else {
                val cid = g.db.conversationDao().insert(ConversationEntity(title = "Telegram AI ${nameOf(g, inv.bot)}"))
                g.db.chatDao().insert(ChatEntity(role = "action", content = "${nameOf(g, inv.bot)} shared their AI chat with you", conversationId = cid))
                val am = obj(g, "aiGuest"); am.put("${inv.bot}|$rid", cid); save(g, "aiGuest", am); bump()
                g.settings.setCurrentConversation(cid)
                com.privacy.app.AppState.pendingScreen.value = "chat"
            }
            send(g, inv.bot, JSONObject().put("t", "inv_ok").put("k", "${inv.kind}|$rid"))
        }
    }

    private fun pendingOut(g: AppGraph, k: String, bot: String) = obj(g, "pendOut").has("$k|$bot")
    private fun markPending(g: AppGraph, k: String, bot: String) { val po = obj(g, "pendOut"); po.put("$k|$bot", true); save(g, "pendOut", po) }

    /** Bot names I am already sharing with (accepted or waiting), for greying them out in the picker. */
    fun sharedWith(g: AppGraph, kind: String, noteId: Long? = null): Set<String> {
        val out = HashSet<String>()
        if (kind == "ai") { val hm = obj(g, "aiHost"); for (k in hm.keys()) { val a = hm.optJSONArray(k) ?: continue; for (i in 0 until a.length()) out += a.getString(i) } }
        else { val hs = obj(g, "hostShares").optJSONObject((noteId ?: -1L).toString()); if (hs != null) out += hs.keys().asSequence() }
        return out
    }
    fun liveNoteIds(g: AppGraph): Set<Long> = live(g).filter { it.kind == "note" }.map { it.ref }.toSet()
    fun liveChatIds(g: AppGraph): Set<Long> = live(g).filter { it.kind == "ai" }.map { it.ref }.toSet() + obj(g, "aiGuest").keys().asSequence().map { obj(g, "aiGuest").optLong(it) }

    // ------------------------------------------------------------------ live connections (for the Modes list)
    fun live(g: AppGraph): List<Live> {
        val out = ArrayList<Live>()
        val hm = obj(g, "aiHost")
        for (k in hm.keys()) { val a = hm.optJSONArray(k) ?: continue; for (i in 0 until a.length()) if (!pendingOut(g, "ai|$k", a.getString(i))) out += Live("ai", "host", nameOf(g, a.getString(i)), a.getString(i), k.toLong(), "$k|${a.getString(i)}") }
        val am = obj(g, "aiGuest")
        for (k in am.keys()) out += Live("ai", "guest", nameOf(g, k.substringBefore('|')), k.substringBefore('|'), am.optLong(k), k)
        val hs = obj(g, "hostShares")
        for (k in hs.keys()) { val o = hs.optJSONObject(k) ?: continue; for (b in o.keys()) if (!pendingOut(g, "note|$k", b)) out += Live("note", "host", nameOf(g, b), b, k.toLong(), "$k|$b") }
        val gm = obj(g, "guestNotes")
        for (k in gm.keys()) { val e = gm.getJSONObject(k); out += Live("note", "guest", e.optString("who").ifBlank { nameOf(g, k.substringBefore('|')) }, k.substringBefore('|'), e.optLong("local"), k) }
        return out
    }
    fun hasLive(g: AppGraph, kind: String) = live(g).any { it.kind == kind }

    fun cancel(g: AppGraph, l: Live) {
        when {
            l.kind == "ai" && l.role == "host" -> endAiWith(g, l.ref, l.bot)
            l.kind == "ai" -> leaveAi(g, l.key)
            l.kind == "note" && l.role == "host" -> endNoteWith(g, l.ref, l.bot)
            else -> endNote(g, l.ref)
        }
    }
    private fun endAiWith(g: AppGraph, cid: Long, bot: String) {
        val hm = obj(g, "aiHost"); val arr = hm.optJSONArray(cid.toString()) ?: return
        val keep = JSONArray(); (0 until arr.length()).map { arr.getString(it) }.filter { it != bot }.forEach { keep.put(it) }
        if (keep.length() == 0) hm.remove(cid.toString()) else hm.put(cid.toString(), keep)
        save(g, "aiHost", hm); bump()
        scope.launch { send(g, bot, JSONObject().put("t", "ai_end").put("rid", cid)); g.db.chatDao().insert(ChatEntity(role = "action", content = "Sharing with ${nameOf(g, bot)} ended", conversationId = cid)) }
    }
    private fun leaveAi(g: AppGraph, key: String) {
        val am = obj(g, "aiGuest"); if (!am.has(key)) return
        val cid = am.optLong(key); am.remove(key); save(g, "aiGuest", am); bump()
        scope.launch { send(g, key.substringBefore('|'), JSONObject().put("t", "ai_leave").put("rid", key.substringAfter('|').toLong())); g.db.chatDao().insert(ChatEntity(role = "action", content = "You left the shared chat", conversationId = cid)) }
    }
}
