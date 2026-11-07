package com.privacy.app.ai

import com.privacy.app.AppGraph
import com.privacy.app.data.ChatEntity
import com.privacy.app.data.ConversationEntity
import com.privacy.app.data.NoteEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Telegram bridge using the Bot API (long polling – there is NO server; the app itself is the bot, so it only works while the app is alive).
 *
 *  • Connect bot: the owner sends /start to their own bot; that chat becomes the owner chat.
 *  • Friends: anybody else who sends /start creates a friend REQUEST. The owner accepts / rejects it (Vault → Telegram, a pop-up, or the buttons in Telegram).
 *  • Shared AI (/shared_ai): the owner's app is the hub. What the owner types, what accepted friends type in Telegram and every AI answer
 *    are relayed to all members (answers are labelled with whose AI replied) and kept in ONE special chat so the AI always sees the whole conversation.
 *  • Shared note (/shared_note): the note text is a Telegram message that is edited again and again while the note is saved. Members view it live;
 *    a member can ask for edit access – while he edits the owner's copy is view-only.
 *  • "Share to my Telegram": only the owner's own chat – or one verified friend.
 */
object TelegramHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var fromTelegram = false
    @Volatile private var late = false
    private val colors = listOf("#E57373", "#64B5F6", "#81C784", "#FFB74D", "#BA68C8", "#4DB6AC")
    private val aiLock = Mutex()

    data class TgFriend(val name: String, val chatId: String, val color: String, val user: String)
    data class Pending(val chatId: String, val name: String, val user: String)
    data class AiSession(val convId: Long, val members: List<String>)
    data class NoteSession(val noteId: Long, val title: String, val msgs: Map<String, Long>, val editor: String?)
    /** Something the owner has to decide (edit access for a shared note). */
    data class Prompt(val id: String, val kind: String, val noteId: Long, val chatId: String, val text: String)

    /** Bumped whenever friends / pending requests / sessions change, so screens can refresh. */
    val rev = MutableStateFlow(0)
    private val _ai = MutableStateFlow<AiSession?>(null)
    val aiSession: StateFlow<AiSession?> = _ai.asStateFlow()
    private val _notes = MutableStateFlow<Map<Long, NoteSession>>(emptyMap())
    val noteSessions: StateFlow<Map<Long, NoteSession>> = _notes.asStateFlow()
    private val _prompts = MutableStateFlow<List<Prompt>>(emptyList())
    val prompts: StateFlow<List<Prompt>> = _prompts.asStateFlow()
    private fun bump() { rev.value = rev.value + 1 }

    private val aiInvited = HashSet<String>()
    private val noteInvited = HashMap<Long, MutableSet<String>>()
    private val counts = HashMap<String, Int>()
    private val pushJobs = HashMap<Long, Job>()
    /** The last message a friend sent that was NOT saved as a note (so "yes, save it" can do it). */
    @Volatile private var lastFriendMsg: Triple<String, String, String>? = null

    private const val NOTICE = "🔒 Privacy note: this bot does not connect to any server. It talks directly to the PRIVACY app on the phone, " +
        "so keep the main app active in the background for the bot to work."

    fun start(g: AppGraph) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val token = g.settings.telegramToken.value
                if (token.isBlank()) { delay(5000); continue }
                val ok = runCatching { pollOnce(g, token) }.isSuccess
                if (!ok) delay(8000)
            }
        }
    }

    // ------------------------------------------------------------------ low level
    internal fun api(token: String, method: String, params: Map<String, String> = emptyMap(), timeoutMs: Int = 15000): JSONObject {
        val body = params.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
        val c = URL("https://api.telegram.org/bot$token/$method").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.connectTimeout = 10000; c.readTimeout = timeoutMs; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(body.toByteArray()) }
            val s = (if (c.responseCode in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
            return JSONObject(s)
        } finally { c.disconnect() }
    }

    private fun kb(vararg buttons: Pair<String, String>): String {
        val row = JSONArray()
        buttons.forEach { row.put(JSONObject().put("text", it.first).put("callback_data", it.second)) }
        return JSONObject().put("inline_keyboard", JSONArray().put(row)).toString()
    }

    /** Sends one message and returns its Telegram message id (null if it failed). */
    private fun sendMsg(token: String, chat: String, text: String, markup: String? = null): Long? {
        val p = mutableMapOf("chat_id" to chat, "text" to text.take(4000))
        if (markup != null) p["reply_markup"] = markup
        val r = runCatching { api(token, "sendMessage", p) }.getOrNull()
        return if (r?.optBoolean("ok") == true) r.getJSONObject("result").optLong("message_id") else null
    }

    private fun editMsg(token: String, chat: String, mid: Long, text: String, markup: String?) {
        val p = mutableMapOf("chat_id" to chat, "message_id" to mid.toString(), "text" to text.take(4000))
        if (markup != null) p["reply_markup"] = markup
        runCatching { api(token, "editMessageText", p) }   // "message is not modified" is fine
    }

    private fun reply(token: String, chat: String, text: String) {
        text.chunked(3900).forEach { runCatching { api(token, "sendMessage", mapOf("chat_id" to chat, "text" to it)) } }
    }

    /** After about five messages in a chat the bot explains once that it has no server. */
    private fun countAndNotice(g: AppGraph, token: String, chat: String) {
        val done = g.settings.telegramNoticed.split(',').filter { it.isNotBlank() }
        if (chat in done) return
        val n = (counts[chat] ?: 0) + 1
        counts[chat] = n
        if (n >= 5) {
            g.settings.telegramNoticed = (done + chat).joinToString(",")
            reply(token, chat, NOTICE)
        }
    }

    // ------------------------------------------------------------------ polling
    private suspend fun pollOnce(g: AppGraph, token: String) {
        val r = withContext(Dispatchers.IO) { api(token, "getUpdates", mapOf("timeout" to "25", "offset" to (g.settings.telegramOffset + 1).toString()), 35000) }
        if (!r.optBoolean("ok")) { delay(8000); return }
        val arr = r.optJSONArray("result") ?: return
        for (i in 0 until arr.length()) {
            val u = arr.getJSONObject(i)
            g.settings.telegramOffset = u.optLong("update_id")
            runCatching {
                val cb = u.optJSONObject("callback_query")
                if (cb != null) { handleCallback(g, token, cb); return@runCatching }
                val m = u.optJSONObject("message") ?: return@runCatching
                val chat = m.optJSONObject("chat")?.optLong("id")?.toString() ?: return@runCatching
                val from = m.optJSONObject("from")
                val name = from?.optString("first_name").orEmpty().ifBlank { "Friend" }
                val user = from?.optString("username").orEmpty()
                val text = m.optString("text").trim()
                // another APP talking to this one through its bot (bot-to-bot)
                if (text.startsWith(TelegramPeers.PREFIX)) { TelegramPeers.onMessage(g, from?.optString("username").orEmpty(), text); return@runCatching }
                // a message that waited in Telegram for a while (the app was offline) is treated as "received while away"
                late = System.currentTimeMillis() / 1000 - m.optLong("date", System.currentTimeMillis() / 1000) > 120
                if (text.isNotEmpty()) handle(g, token, chat, name, user, text)
            }
        }
    }

    private suspend fun handle(g: AppGraph, token: String, chat: String, name: String, user: String, text: String) {
        val owner = g.settings.telegramUserId.value
        if (text.startsWith("/start")) {
            when {
                owner.isBlank() -> {
                    g.settings.setTelegramUserId(chat); g.settings.setTelegramConnected(true); g.settings.telegramOwnerUser = user
                    reply(token, chat, "Connected ✅ This chat is now linked with your P.R.I.V.A.C.Y. assistant. Write here and I will answer; what you type in the app is copied here too.")
                }
                chat == owner -> {
                    g.settings.setTelegramConnected(true); if (user.isNotBlank()) g.settings.telegramOwnerUser = user
                    reply(token, chat, "Already connected ✅")
                }
                else -> reply(token, chat, "This bot is private. Friends connect app to app, not through this chat.")
            }
            return
        }
        countAndNotice(g, token, chat)
        if (chat == owner) ownerMessage(g, token, chat, text)
    }

    private suspend fun ownerMessage(g: AppGraph, token: String, chat: String, text: String) {
        if (text.startsWith("/shared_stop") || text.startsWith("/stop_shared")) { endAll(g, lost = false); reply(token, chat, "All shared sessions are stopped."); return }
        if (text.startsWith("/shared")) { reply(token, chat, sharedText(g)); return }
        val s = _ai.value
        if (s != null) {
            sendToMembers(g, s.members, "💬 ${hostName(g)}: $text")
            askShared(g, s.convId, text)
            return
        }
        fromTelegram = true
        try {
            val ans = g.chatService.ask(text, fromTelegram = true)
            if (!ans.isNullOrBlank()) reply(token, chat, ans)
        } finally { fromTelegram = false }
    }

    private suspend fun friendMessage(g: AppGraph, token: String, chat: String, name: String, text: String) {
        val owner = g.settings.telegramUserId.value
        if (!isFriend(g, chat)) {
            reply(token, chat, if (pending(g).any { it.chatId == chat }) "Your friend request is still waiting for the owner." else "Send /start first – the owner has to accept you.")
            return
        }
        if (text.startsWith("/leave")) { leave(g, chat, name); return }
        // 1) shared AI
        val s = _ai.value
        if (s != null && chat in s.members && !text.startsWith("/")) {
            sendToMembers(g, s.members.filter { it != chat }, "💬 $name: $text")
            if (owner.isNotBlank()) { /* the owner sees it in the app chat */ }
            askShared(g, s.convId, "[[from:$name]]$text")
            return
        }
        // 2) a note this friend may edit
        val ns = _notes.value.values.firstOrNull { it.editor == chat }
        if (ns != null) {
            when {
                text.startsWith("/done") -> release(g, ns.noteId)
                text.startsWith("/replace") -> applyFriendEdit(g, ns.noteId, text.removePrefix("/replace").trim(), replace = true)
                text.startsWith("/") -> reply(token, chat, "You are editing “${ns.title}”. Send text to add a line, /replace <text> to rewrite everything, /done to finish.")
                else -> applyFriendEdit(g, ns.noteId, text, replace = false)
            }
            return
        }
        when {
            text.startsWith("/shared") -> reply(token, chat, sharedText(g))
            text.startsWith("+") -> {
                appendShared(g, name, text.removePrefix("+").trim())
                reply(token, chat, "Added to the shared note ✅")
                if (owner.isNotBlank()) reply(token, owner, "📝 $name added to the shared note: ${text.removePrefix("+").trim()}")
            }
            text.startsWith("/") -> reply(token, chat, "Commands: /leave · `+ text` adds to the shared note · /shared reads it. Anything else you write is delivered to the owner.")
            else -> friendInbox(g, token, chat, name, text)
        }
    }

    /** A friend wrote something to the owner: optionally save it as a note tagged [Friend name] and tell the owner in the main chat. */
    private suspend fun friendInbox(g: AppGraph, token: String, chat: String, name: String, text: String) {
        val wasAway = late          // it reached the app late (no connection at the time): it is saved so nothing is lost
        val save = g.settings.telegramSaveAsNotes.value || wasAway
        if (save) saveFriendNote(g, name, text, viaTelegram = wasAway) else lastFriendMsg = Triple(name, chat, text)
        reply(token, chat, "Delivered ✅")
        val note = if (wasAway) "It reached me late, so I saved it as a note tagged [via Telegram]." else if (save) "I saved it as a note tagged [$name]." else "Should I save it as a note?"
        runCatching { g.chatService.addAssistant("📨 **$name** sent you a message in Telegram:\n\n> ${text.take(600)}\n\n$note") }
    }

    private suspend fun saveFriendNote(g: AppGraph, name: String, text: String, viaTelegram: Boolean = false) {
        val title = if (viaTelegram) NoteRules.tagged("via Telegram", "$name – " + text.lines().first().take(40)) else NoteRules.tagged(name, text.lines().first().take(40))
        g.db.noteDao().insert(NoteEntity(title = title, body = text))
    }

    /** "Yes, save it" – the AI calls this for the last friend message that was not saved. */
    suspend fun saveLastFriendAsNote(g: AppGraph): String {
        val m = lastFriendMsg ?: return "There is no unsaved Telegram message from a friend."
        saveFriendNote(g, m.first, m.third); lastFriendMsg = null
        return "Saved as a note tagged [${m.first}]."
    }

    // ------------------------------------------------------------------ callbacks (buttons in Telegram)
    private suspend fun handleCallback(g: AppGraph, token: String, cb: JSONObject) {
        val id = cb.optString("id")
        val from = cb.optJSONObject("from")
        val chat = from?.optLong("id")?.toString() ?: return
        val name = from.optString("first_name").ifBlank { "Friend" }
        val data = cb.optString("data")
        runCatching { api(token, "answerCallbackQuery", mapOf("callback_query_id" to id)) }
        val owner = g.settings.telegramUserId.value
        val parts = data.split(":")
        when (parts[0]) {
            "fr_yes", "fr_no" -> if (chat == owner) answerFriend(g, parts.getOrElse(1) { "" }, parts[0] == "fr_yes")
            "ai_yes" -> if (isFriend(g, chat) && chat in aiInvited) joinAi(g, token, chat, name) else reply(token, chat, "This invitation is no longer valid.")
            "ai_no" -> { aiInvited.remove(chat); reply(token, chat, "Declined."); runCatching { g.chatService.addAssistant("$name declined to join the shared AI.") } }
            "note_yes" -> { val n = parts.getOrNull(1)?.toLongOrNull(); if (n != null && isFriend(g, chat) && noteInvited[n]?.contains(chat) == true) joinNote(g, token, chat, n, name) }
            "note_no" -> { reply(token, chat, "Declined."); runCatching { g.chatService.addAssistant("$name declined to join the shared note.") } }
            "note_edit" -> parts.getOrNull(1)?.toLongOrNull()?.let { requestEdit(g, token, chat, name, it) }
            "note_done" -> parts.getOrNull(1)?.toLongOrNull()?.let { release(g, it) }
        }
    }

    // ------------------------------------------------------------------ friends & requests
    fun friends(g: AppGraph): List<TgFriend> {
        val a = runCatching { JSONArray(g.settings.telegramFriends) }.getOrDefault(JSONArray())
        return (0 until a.length()).map { val o = a.getJSONObject(it); TgFriend(o.optString("name"), o.optString("chatId"), o.optString("color"), o.optString("user")) }
    }
    private fun isFriend(g: AppGraph, chat: String) = friends(g).any { it.chatId == chat }
    private fun addFriend(g: AppGraph, chat: String, name: String, user: String) {
        if (isFriend(g, chat)) return
        val a = runCatching { JSONArray(g.settings.telegramFriends) }.getOrDefault(JSONArray())
        a.put(JSONObject().put("name", name).put("chatId", chat).put("color", colors[a.length() % colors.size]).put("user", user))
        g.settings.telegramFriends = a.toString()
        bump()
    }
    fun colorOf(g: AppGraph, name: String): String = friends(g).firstOrNull { it.name == name }?.color ?: "#9E9E9E"
    fun removeFriend(g: AppGraph, chat: String) {
        val a = JSONArray()
        friends(g).filter { it.chatId != chat }.forEach { a.put(JSONObject().put("name", it.name).put("chatId", it.chatId).put("color", it.color).put("user", it.user)) }
        g.settings.telegramFriends = a.toString(); bump()
    }

    fun pending(g: AppGraph): List<Pending> {
        val a = runCatching { JSONArray(g.settings.telegramPending) }.getOrDefault(JSONArray())
        return (0 until a.length()).map { val o = a.getJSONObject(it); Pending(o.optString("chatId"), o.optString("name"), o.optString("user")) }
    }
    private fun savePending(g: AppGraph, list: List<Pending>) {
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("chatId", it.chatId).put("name", it.name).put("user", it.user)) }
        g.settings.telegramPending = a.toString(); bump()
    }
    private fun addPending(g: AppGraph, p: Pending) = savePending(g, pending(g) + p)

    /** Accept / reject a friend request (from the app or from the buttons in Telegram). */
    fun answerFriend(g: AppGraph, chat: String, accept: Boolean) {
        val p = pending(g).firstOrNull { it.chatId == chat } ?: return
        savePending(g, pending(g).filter { it.chatId != chat })
        if (accept) addFriend(g, chat, p.name, p.user)
        val token = g.settings.telegramToken.value
        scope.launch {
            if (token.isBlank()) return@launch
            reply(token, chat, if (accept) "✅ ${hostName(g)} accepted your friend request. You are connected now." else "❌ Your friend request was not accepted.")
            val owner = g.settings.telegramUserId.value
            if (owner.isNotBlank()) reply(token, owner, if (accept) "✅ ${p.name} is now your friend." else "Request from ${p.name} rejected.")
        }
    }

    // ------------------------------------------------------------------ names
    fun hostName(g: AppGraph): String = g.settings.userName.value.ifBlank { g.settings.assistantName.value }
    private fun hostLabel(g: AppGraph): String {
        val u = g.settings.telegramOwnerUser
        return hostName(g) + if (u.isNotBlank()) " (@$u)" else " (Telegram id ${g.settings.telegramUserId.value})"
    }
    fun friendName(g: AppGraph, chat: String) = friends(g).firstOrNull { it.chatId == chat }?.name ?: "Friend"

    /** A friend by name or @username (case-insensitive) – used to verify who a message goes to. */
    fun findFriend(g: AppGraph, who: String): TgFriend? {
        val w = who.trim().removePrefix("@")
        if (w.isBlank()) return null
        return friends(g).firstOrNull { it.name.equals(w, true) || it.user.equals(w, true) }
    }

    // ------------------------------------------------------------------ sessions: shared AI
    private fun sendToMembers(g: AppGraph, chats: List<String>, text: String) {
        val token = g.settings.telegramToken.value
        if (token.isBlank()) return
        scope.launch { chats.forEach { reply(token, it, text) } }
    }

    /** Sends the invitation to the picked friends. They accept with a button in Telegram. */
    fun inviteAi(g: AppGraph, allChats: List<String>): String {
        val peerPart = allChats.filter { it.startsWith("peer:") }.map { it.removePrefix("peer:") }
        if (peerPart.isEmpty()) return "Pick a paired friend (Account → Telegram)."
        return TelegramPeers.shareAi(g, peerPart)
    }

    private suspend fun joinAi(g: AppGraph, token: String, chat: String, name: String) {
        aiInvited.remove(chat)
        val cur = _ai.value
        val s: AiSession
        if (cur == null) {
            val cid = g.db.conversationDao().insert(ConversationEntity(title = "Telegram AI · $name"))
            g.db.chatDao().insert(ChatEntity(role = "action", content = "Shared AI started with $name (Telegram)", conversationId = cid))
            g.settings.setCurrentConversation(cid)
            s = AiSession(cid, listOf(chat))
        } else {
            if (chat in cur.members) return
            g.db.chatDao().insert(ChatEntity(role = "action", content = "$name joined the shared AI", conversationId = cur.convId))
            s = cur.copy(members = cur.members + chat)
        }
        _ai.value = s; bump()
        val names = s.members.joinToString { friendName(g, it) }
        // the owner is the one with main access: the /shared_ai message goes out from him
        s.members.forEach { reply(token, it, "/shared_ai\n🤝 Shared AI with ${hostName(g)}${if (s.members.size > 1) " · members: $names" else ""}.\nWrite here – your messages reach ${hostName(g)}'s AI and everyone else. Send /leave to leave.") }
        val owner = g.settings.telegramUserId.value
        if (owner.isNotBlank()) reply(token, owner, "✅ $name joined the shared AI.")
    }

    private fun leave(g: AppGraph, chat: String, name: String) {
        val token = g.settings.telegramToken.value
        _ai.value?.let { s ->
            if (chat in s.members) {
                val rest = s.members - chat
                _ai.value = if (rest.isEmpty()) null else s.copy(members = rest)
                sendToMembers(g, rest, "👋 $name left the shared AI.")
                scope.launch { runCatching { g.db.chatDao().insert(ChatEntity(role = "action", content = "$name left the shared AI", conversationId = s.convId)) } }
            }
        }
        _notes.update { m -> m.mapValues { (_, ns) -> if (chat in ns.msgs) ns.copy(msgs = ns.msgs - chat, editor = if (ns.editor == chat) null else ns.editor) else ns } }
        if (token.isNotBlank()) reply(token, chat, "You left. 👋")
        bump()
    }

    private fun askShared(g: AppGraph, convId: Long, text: String) {
        scope.launch { aiLock.withLock { runCatching { g.chatService.ask(text, fromTelegram = true, cidOverride = convId) } } }
    }

    /** Host typed in the app. */
    private fun relayHostTyped(g: AppGraph, text: String) {
        val s = _ai.value ?: return
        if (g.settings.currentConversation.value != s.convId || text.startsWith("/")) return
        sendToMembers(g, s.members, "💬 ${hostName(g)}: $text")
    }

    /** An AI answer was produced in conversation [cid]: send it to the members, labelled with whose AI answered. */
    fun relayAi(g: AppGraph, cid: Long, text: String) {
        val s = _ai.value ?: return
        if (cid != s.convId) return
        sendToMembers(g, s.members, "🤖 ${hostName(g)}'s AI:\n$text")
    }

    fun endAi(g: AppGraph, lost: Boolean = false) {
        val s = _ai.value ?: return
        _ai.value = null; bump()
        sendToMembers(g, s.members, if (lost) lostText(g) else "🔌 ${hostName(g)} ended the shared AI.")
        scope.launch { runCatching { g.db.chatDao().insert(ChatEntity(role = "action", content = "Shared AI ended", conversationId = s.convId)) } }
    }

    // ------------------------------------------------------------------ sessions: shared note
    fun inviteNote(g: AppGraph, noteId: Long, title: String, allChats: List<String>): String {
        val peerPart = allChats.filter { it.startsWith("peer:") }.map { it.removePrefix("peer:") }
        val toMe = "me" in allChats
        var meMsg = ""
        if (toMe) {
            meMsg = if (g.settings.telegramUserId.value.isBlank()) "To send a note to yourself, press Start on your bot once (Account → Telegram)." else "Sending the note to your own Telegram…"
            if (g.settings.telegramUserId.value.isNotBlank()) scope.launch {
                val n = g.db.noteDao().get(noteId)
                val r = send(g, "📝 ${n?.title ?: title}\n\n${n?.body.orEmpty()}", null)
                if (!r.startsWith("Sent")) android.os.Handler(android.os.Looper.getMainLooper()).post { android.widget.Toast.makeText(g.context, r, android.widget.Toast.LENGTH_LONG).show() }
            }
        }
        val peerMsg = if (peerPart.isNotEmpty()) TelegramPeers.shareNote(g, noteId, peerPart) else ""
        return listOf(meMsg, peerMsg).filter { it.isNotBlank() }.joinToString("\n").ifBlank { "Pick a paired friend or “Me”." }
    }

    private fun renderNote(g: AppGraph, s: NoteSession, body: String, chat: String): String {
        val foot = when {
            s.editor == chat -> "✏️ You can edit now (send text · /replace <text> · /done)"
            s.editor != null -> "✏️ ${friendName(g, s.editor)} is editing · view only"
            else -> "👁 View only"
        }
        return "/shared_note\n📝 ${s.title}\n\n${body.take(3300)}\n\n— $foot"
    }
    private fun noteKeyboard(s: NoteSession, chat: String): String =
        if (s.editor == chat) kb("✅ Done editing" to "note_done:${s.noteId}") else kb("✏️ Request edit" to "note_edit:${s.noteId}")

    private suspend fun joinNote(g: AppGraph, token: String, chat: String, noteId: Long, name: String) {
        val note = g.db.noteDao().get(noteId) ?: return
        noteInvited[noteId]?.remove(chat)
        val cur = _notes.value[noteId] ?: NoteSession(noteId, note.title, emptyMap(), null)
        val withChat = cur.copy(title = note.title)
        val mid = withContext(Dispatchers.IO) { sendMsg(token, chat, renderNote(g, withChat, note.body, chat), noteKeyboard(withChat, chat)) } ?: return
        _notes.update { it + (noteId to withChat.copy(msgs = withChat.msgs + (chat to mid))) }
        bump()
        val owner = g.settings.telegramUserId.value
        if (owner.isNotBlank()) reply(token, owner, "✅ $name joined the shared note “${note.title}”.")
    }

    /** The owner's note was saved in the app – edit the Telegram messages (debounced). */
    fun noteSaved(g: AppGraph, noteId: Long, title: String) {
        TelegramPeers.noteChanged(g, noteId)
        val s = _notes.value[noteId] ?: return
        if (s.title != title) _notes.update { m -> m[noteId]?.let { m + (noteId to it.copy(title = title)) } ?: m }
        pushJobs[noteId]?.cancel()
        pushJobs[noteId] = scope.launch { delay(1200); pushNow(g, noteId) }
    }

    private suspend fun pushNow(g: AppGraph, noteId: Long) {
        val s = _notes.value[noteId] ?: return
        val note = g.db.noteDao().get(noteId) ?: return
        val token = g.settings.telegramToken.value
        if (token.isBlank()) return
        withContext(Dispatchers.IO) {
            s.msgs.forEach { (chat, mid) -> editMsg(token, chat, mid, renderNote(g, s, note.body, chat), noteKeyboard(s, chat)) }
        }
    }

    private fun requestEdit(g: AppGraph, token: String, chat: String, name: String, noteId: Long) {
        val s = _notes.value[noteId] ?: return
        if (s.editor == chat) { reply(token, chat, "You can already edit."); return }
        val pid = "edit:$noteId:$chat"
        if (_prompts.value.none { it.id == pid }) _prompts.update { it + Prompt(pid, "edit", noteId, chat, "$name wants to edit the note “${s.title}”.") }
        reply(token, chat, "✋ Edit request sent to ${hostName(g)}. Keep Telegram open – you'll be told when it is accepted.")
        bump()
    }

    /** Owner's decision on an edit request. */
    fun answerEdit(g: AppGraph, prompt: Prompt, accept: Boolean) {
        _prompts.update { l -> l.filter { it.id != prompt.id } }
        val token = g.settings.telegramToken.value
        scope.launch {
            if (!accept) { if (token.isNotBlank()) reply(token, prompt.chatId, "❌ ${hostName(g)} did not give edit access."); return@launch }
            val s = _notes.value[prompt.noteId] ?: return@launch
            val previous = s.editor
            _notes.update { m -> m + (prompt.noteId to s.copy(editor = prompt.chatId)) }
            bump()
            if (token.isNotBlank()) {
                if (previous != null && previous != prompt.chatId) reply(token, previous, "${hostName(g)} gave the editing to ${friendName(g, prompt.chatId)}. You are in view mode now.")
                reply(token, prompt.chatId, "✅ You can edit “${s.title}” now.\nSend text to add a line · /replace <text> rewrites the note · /done when finished.")
            }
            pushNow(g, prompt.noteId)
        }
    }

    /** Owner takes the editing back. */
    fun takeBack(g: AppGraph, noteId: Long) {
        val s = _notes.value[noteId] ?: return
        val prev = s.editor ?: return
        _notes.update { m -> m + (noteId to s.copy(editor = null)) }; bump()
        val token = g.settings.telegramToken.value
        scope.launch { if (token.isNotBlank()) reply(token, prev, "${hostName(g)} took the editing back. You are in view mode."); pushNow(g, noteId) }
    }

    private fun release(g: AppGraph, noteId: Long) {
        val s = _notes.value[noteId] ?: return
        if (s.editor == null) return
        _notes.update { m -> m + (noteId to s.copy(editor = null)) }; bump()
        scope.launch { pushNow(g, noteId) }
    }

    private suspend fun applyFriendEdit(g: AppGraph, noteId: Long, text: String, replace: Boolean) {
        val n = g.db.noteDao().get(noteId) ?: return
        val body = if (replace) text else (n.body.trimEnd() + "\n" + text).trim()
        g.db.noteDao().update(n.copy(body = body, updatedAt = System.currentTimeMillis()))
        pushNow(g, noteId)
    }

    fun endNote(g: AppGraph, noteId: Long, lost: Boolean = false) {
        val s = _notes.value[noteId] ?: return
        _notes.update { it - noteId }; bump()
        sendToMembers(g, s.msgs.keys.toList(), if (lost) lostText(g) else "🔌 ${hostName(g)} stopped sharing “${s.title}”.")
    }

    // ------------------------------------------------------------------ end / lost connection
    private fun lostText(g: AppGraph): String {
        val u = g.settings.telegramOwnerUser
        return "⚠️ ${if (u.isNotBlank()) "@$u" else hostName(g)} lost connection through Telegram (the app was closed). Sharing is paused until it is opened again."
    }

    fun endAll(g: AppGraph, lost: Boolean) {
        endAi(g, lost)
        _notes.value.keys.toList().forEach { endNote(g, it, lost) }
    }

    /** The app is being removed from the background: tell everybody in a session (best effort). */
    fun goodbye(g: AppGraph) {
        val token = g.settings.telegramToken.value
        if (token.isBlank()) return
        val chats = HashSet<String>()
        _ai.value?.let { chats.addAll(it.members) }
        _notes.value.values.forEach { chats.addAll(it.msgs.keys) }
        if (chats.isEmpty()) return
        val msg = lostText(g)
        val t = Thread { chats.forEach { runCatching { api(token, "sendMessage", mapOf("chat_id" to it, "text" to msg), 4000) } } }
        t.start(); runCatching { t.join(2500) }
        _ai.value = null; _notes.value = emptyMap()
    }

    // ------------------------------------------------------------------ old shared note: each contribution is a line "name: text"
    private const val SHARED_TITLE = "Shared note (Telegram)"
    private suspend fun sharedNote(g: AppGraph): NoteEntity {
        val dao = g.db.noteDao()
        val ex = dao.all().firstOrNull { it.title == SHARED_TITLE }
        if (ex != null) return ex
        val id = dao.insert(NoteEntity(title = SHARED_TITLE, body = ""))
        return dao.get(id)!!
    }
    private suspend fun sharedText(g: AppGraph) = sharedNote(g).body.ifBlank { "The shared note is empty." }
    private suspend fun appendShared(g: AppGraph, who: String, text: String) {
        val n = sharedNote(g)
        g.db.noteDao().update(n.copy(body = (n.body + "\n" + who + ": " + text).trim(), updatedAt = System.currentTimeMillis()))
    }

    // ------------------------------------------------------------------ sending / mirroring
    /** To the owner's own chat (friend == null) or to ONE verified friend (name or @username from the Friends list). */
    suspend fun send(g: AppGraph, text: String, friend: String?): String {
        val token = g.settings.telegramToken.value
        if (token.isBlank()) return "No Telegram bot is set up. Add the bot token in Vault → Telegram."
        val chat: String
        val body: String
        if (friend == null) {
            chat = g.settings.telegramUserId.value
            body = text
            if (chat.isBlank()) return "Not connected yet – open your bot in Telegram and press Start, then tap Connect bot."
        } else {
            val f = findFriend(g, friend)
                ?: return "“$friend” is not in your Friends list (Vault → Telegram), so I did not send anything. Friends: " + friends(g).joinToString { it.name + if (it.user.isNotBlank()) " (@${it.user})" else "" }.ifBlank { "none yet" }
            chat = f.chatId
            body = "📨 From ${hostLabel(g)}:\n$text"
        }
        return withContext(Dispatchers.IO) {
            val ok = text.chunked(3800).mapIndexed { i, part ->
                val r = runCatching { api(token, "sendMessage", mapOf("chat_id" to chat, "text" to (if (i == 0) body.take(3900) else part))) }.getOrNull()
                r?.optBoolean("ok") == true
            }.all { it }
            if (chat == g.settings.telegramUserId.value) countAndNotice(g, token, chat)
            if (ok) (if (friend == null) "Sent to your Telegram." else "Sent to ${findFriend(g, friend)?.name} in Telegram.") else "Telegram refused it – the person must have pressed Start on the bot."
        }
    }

    /** What the user typed in the chat window (live copy + shared AI). */
    fun mirrorUser(g: AppGraph, text: String) {
        relayHostTyped(g, text)
        return
    }
    suspend fun mirrorReply(g: AppGraph, text: String) {
        return
    }
    /** Checks the token and returns the bot's name. */
    suspend fun check(token: String): String = withContext(Dispatchers.IO) {
        val r = runCatching { api(token, "getMe") }.getOrNull()
        if (r?.optBoolean("ok") == true) "@" + r.getJSONObject("result").optString("username") else "Token not accepted."
    }
}
