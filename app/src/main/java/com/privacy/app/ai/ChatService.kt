package com.privacy.app.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.privacy.app.AppGraph
import kotlinx.coroutines.launch
import com.privacy.app.data.ChatEntity
import com.privacy.app.data.ConversationEntity

object Net {
    /** Set once at start-up so every layer (LLM client, automations) can ask "is the internet on?". */
    @Volatile var ctx: Context? = null
    const val OFFLINE = "Internet is off. Turn it on and try again."
    fun online(): Boolean = ctx?.let { isOnline(it) } ?: true
    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val n = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
}

/**
 * Single entry point for "the user said something": used by the chat screen, the talking-face screen and the
 * home-screen widget's voice mode, so every conversation ends up in the same saved history.
 */
class ChatService(private val g: AppGraph) {

    private val _agentActive = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** True while a big task is being handled by Agent mode (the app chose that by itself). */
    val agentActive: kotlinx.coroutines.flow.StateFlow<Boolean> = _agentActive

    /** The chat currently open. Creates one if there is none yet. */
    suspend fun ensureConversation(): Long {
        val dao = g.db.conversationDao()
        val cur = g.settings.currentConversation.value
        if (cur > 0 && dao.get(cur) != null) return cur
        val id = dao.insert(ConversationEntity(title = NEW_TITLE))
        g.settings.setCurrentConversation(id)
        return id
    }

    /** Starts a fresh chat (the old one stays saved). Does nothing if the open chat is still empty. */
    suspend fun startNewChat() {
        val cur = g.settings.currentConversation.value
        if (cur > 0 && g.db.conversationDao().get(cur) != null && g.db.chatDao().count(cur) == 0) return
        val id = g.db.conversationDao().insert(ConversationEntity(title = NEW_TITLE))
        g.settings.setCurrentConversation(id)
    }

    suspend fun addAssistant(text: String) {
        val cid = ensureConversation()
        g.db.chatDao().insert(ChatEntity(role = "assistant", content = text, conversationId = cid))
        touch(cid, null)
    }

    private suspend fun touch(cid: Long, firstUserText: String?) {
        val dao = g.db.conversationDao()
        val c = dao.get(cid) ?: return
        val title = if (c.title == NEW_TITLE && firstUserText != null) firstUserText.replace('\n', ' ').trim().take(48) else c.title
        dao.update(c.copy(title = title, updatedAt = System.currentTimeMillis()))
    }

    /**
     * Saves the user's message, produces the reply (offline command, normal AI chat, or Agent mode) and saves it.
     * Returns the reply text, or null for housekeeping commands (/new, /clear) that produce no reply.
     */
    suspend fun ask(text: String, spoken: Boolean = false, fromTelegram: Boolean = false, cidOverride: Long? = null): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (t.equals("/new", ignoreCase = true)) { startNewChat(); return null }
        if (t.equals("/clear", ignoreCase = true)) { g.db.chatDao().clear(ensureConversation()); return null }

        val cid = cidOverride ?: ensureConversation()
        val chat = g.db.chatDao()
        chat.insert(ChatEntity(role = "user", content = t, conversationId = cid))
        touch(cid, t.replace(Regex("^\\[\\[reply:.*?]]\\n?", RegexOption.DOT_MATCHES_ALL), ""))
        // a chat shared WITH me by a friend's app: the text goes to the friend's app (his AI answers there); a chat I share: members get a copy
        if (TelegramPeers.onUserMessage(g, cid, t)) return null

        runCatching { AutoMemory.capture(g, t) }
        // "I'm feeling distracted" mode is switched on / off by saying so (or /distracted)
        val wasDistracted = g.settings.distractedMode.value
        if (Distracted.wantsOff(t)) g.settings.setDistractedMode(false)
        else if (Distracted.wantsOn(t)) g.settings.setDistractedMode(true)
        val justOff = wasDistracted && !g.settings.distractedMode.value
        val local = if (t.startsWith("/distracted", ignoreCase = true)) { if (justOff) "Distracted mode is off. Back to normal." else null } else LocalCommands.handle(t, g)
        if (justOff && local == null) { val m = "Distracted mode is off. Back to normal."; chat.insert(ChatEntity(role = "assistant", content = m, conversationId = cid)); return m }
        val reply = when {
            local != null -> local
            !g.settings.aiReady() ->
                (if (com.privacy.app.credits.Credits.signedIn) "The FREE plan is busy or used up right now – it depends on API load. For uninterrupted use, add your own API key or buy credits in **menu → API & Subscription**. Offline commands still work – type `/help`."
                else "Sign in with Google for FREE API credits, or add your own key, in the section **API & Subscription** (menu). Offline commands still work – type `/help`.")
            !Net.isOnline(g.context) ->
                "You're offline, so I can't reach the AI. These commands still work: `/add`, `/tasks`, `/done`, `/note`, `/remember`, `/calc`… (type `/help`)."
            else -> {
                val onAction: suspend (String) -> Unit = { a -> chat.insert(ChatEntity(role = "action", content = a, conversationId = cid)) }
                // The app decides by itself: big / deep / research-style requests use Agent mode, everything else a quick reply.
                val useAgent = AgentRouter.shouldUseAgent(t)
                _agentActive.value = useAgent
                val r = try {
                    // everything this message needs (several AI calls, tools, retries) is ONE task in the credit Log
                    kotlinx.coroutines.withContext(com.privacy.app.credits.CreditLabel(if (useAgent) "Agent task" else "Chat reply")) {
                        if (useAgent) {
                            onAction("Big task detected · using Agent mode")
                            g.agent.agentic(chat.recent(cid, 20), t, onAction, spoken)
                        } else g.agent.chat(chat.recent(cid, 20), onAction, spoken)
                    }
                } finally {
                    _agentActive.value = false
                }
                if (r.error != null) "⚠️ ${r.error}" else r.text
            }
        }
        val shownReply = if (g.settings.distractedMode.value && local == null && !reply.startsWith("⚠️")) Distracted.deliver(g, cid, reply)
            else { chat.insert(ChatEntity(role = "assistant", content = reply.replace("|||", "\n\n"), conversationId = cid)); reply.replace("|||", "\n\n") }
        touch(cid, null)
        if (local == null && !reply.startsWith("⚠️")) runCatching { TelegramHub.mirrorReply(g, shownReply) }
        if (local == null && !reply.startsWith("⚠️")) runCatching { TelegramHub.relayAi(g, cid, shownReply) }
        if (local == null && !reply.startsWith("⚠️")) runCatching { TelegramPeers.onAiReply(g, cid, shownReply) }
        // every few messages, quietly distil what the conversation says about the user (goals, struggles, preferences)
        if (local == null && !reply.startsWith("⚠️")) {
            val userCount = runCatching { chat.forConversation(cid).count { it.role == "user" } }.getOrDefault(0)
            if (userCount > 0 && userCount % 4 == 0) MemoryDigest.launch(g, cid)
        }
        return shownReply
    }

    /** A separate saved chat for Telegram messages when the user turned off "show Telegram messages in the app". */
    private suspend fun hiddenTelegramChat(): Long {
        val dao = g.db.conversationDao()
        val id = g.settings.telegramHiddenChat
        if (id > 0 && dao.get(id) != null) return id
        val n = dao.insert(ConversationEntity(title = "Telegram (private)"))
        g.settings.telegramHiddenChat = n
        return n
    }

    /** "Analyse a photo": the picture goes to a vision-capable model together with the user's question. The photo itself is not saved. */
    suspend fun analyzePhoto(uri: android.net.Uri, question: String): String {
        val q = question.trim().ifBlank { "Describe this photo in detail and tell me anything useful or notable in it." }
        val cid = ensureConversation()
        val chat = g.db.chatDao()
        val saved = runCatching {
            val dir = java.io.File(g.context.filesDir, "chat_images").apply { mkdirs() }
            val f = java.io.File(dir, "img-${System.currentTimeMillis()}.jpg")
            val src = g.context.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it) }
            val scale = 1024f / maxOf(src.width, src.height).coerceAtLeast(1)
            val bmp = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
            f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it) }
            f.name
        }.getOrNull()
        chat.insert(ChatEntity(role = "user", content = "📷 Photo · $q" + (saved?.let { "\n[[img:$it]]" } ?: ""), conversationId = cid))
        touch(cid, "Photo · $q")
        val reply = when {
            !Net.isOnline(g.context) -> "You're offline, so I can't analyse the photo right now."
            else -> g.vision.analyze(uri, q).fold(onSuccess = { it }, onFailure = { "⚠️ ${it.message ?: "Couldn't analyse that photo."}" })
        }
        chat.insert(ChatEntity(role = "assistant", content = reply, conversationId = cid))
        touch(cid, null)
        return reply
    }

    private companion object { const val NEW_TITLE = "New chat" }
}


/**
 * Safety net for "remember things from the chat": clear statements about the user are saved even when the model forgets to call save_memory.
 * Only first-person, lasting statements are caught; the assistant's own save_memory call is de-duplicated by text.
 */
object AutoMemory {
    private val patterns = listOf(
        Regex("(?i)\\bmy name is ([\\p{L}][\\p{L} .'-]{1,40})") to { m: MatchResult -> "The user's name is ${m.groupValues[1].trim().trimEnd('.')}." },
        Regex("(?i)\\bremember (?:that )?(.{6,200})") to { m: MatchResult -> m.groupValues[1].trim().trimEnd('.') + "." },
        Regex("(?i)\\bi (?:study|am studying|work(?:ed)? (?:at|as|in|for)|live in|am from|stay in|am a|am an) (.{3,80})") to { m: MatchResult -> "The user " + m.value.trim().replace(Regex("(?i)^i am "), "is ").replace(Regex("(?i)^i "), "").trimEnd('.') + "." },
        Regex("(?i)\\bi (?:really )?(?:like|love|prefer|hate|dislike|enjoy)s? (.{3,80})") to { m: MatchResult -> "The user " + m.value.trim().replace(Regex("(?i)^i "), "").trimEnd('.') + "." },
        Regex("(?i)\\bmy (?:favou?rite [a-z ]{2,20}|birthday|exam|goal|target|dream|hobby|hobbies) (?:is|are) (.{2,80})") to { m: MatchResult -> "The user's " + m.value.trim().removePrefix("my ").removePrefix("My ").trimEnd('.') + "." }
    )

    suspend fun capture(g: AppGraph, text: String) {
        if (text.startsWith("/") || text.length > 400) return
        val dao = g.db.memoryDao()
        val have = dao.all().map { it.content.lowercase() }
        for ((re, make) in patterns) {
            val m = re.find(text) ?: continue
            val fact = make(m)
            if (fact.length < 12 || have.any { it == fact.lowercase() }) continue
            if (Regex("(?i)password|passcode|otp|\\bpin\\b|card number|cvv").containsMatchIn(fact)) continue
            dao.insert(com.privacy.app.data.MemoryEntity(category = "chat", content = fact))
            return // at most one automatic fact per message
        }
    }
}


/** Background "getting to know you": reads the latest part of a chat and keeps durable facts, goals and recurring problems as memories. */
object MemoryDigest {
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    fun launch(g: AppGraph, cid: Long) {
        scope.launch {
            runCatching {
                if (!g.settings.aiReady() || !Net.isOnline(g.context)) return@launch
                val msgs = g.db.chatDao().recent(cid, 14).filter { it.role == "user" || it.role == "assistant" }
                if (msgs.size < 4) return@launch
                val convo = msgs.joinToString("\n") { (if (it.role == "user") "User: " else "Assistant: ") + it.content.take(400) }
                val prompt = "From this conversation, extract up to 3 DURABLE things worth remembering about the user so an assistant can understand them better later: " +
                    "their goals (short and long term), recurring problems or struggles, preferences, routines, important people, situations. " +
                    "Skip temporary requests, tasks, small talk and secrets (passwords, PINs, card numbers). Each item is one short sentence starting with \"The user\". " +
                    "Answer ONLY with a JSON array of strings, e.g. [\"The user is preparing for a medical entrance exam.\"] – or [] if nothing qualifies.\n\n" + convo
                val messages = listOf(
                    org.json.JSONObject().put("role", "system").put("content", "You extract memories. Output only JSON."),
                    org.json.JSONObject().put("role", "user").put("content", prompt)
                )
                val r = g.llm.chat(messages, null, maxTokens = 600, temperature = 0.2)
                val text = (r as? LlmClient.Result.Ok)?.message?.contentOrEmpty().orEmpty()
                val json = Regex("\\[.*]", RegexOption.DOT_MATCHES_ALL).find(text)?.value ?: return@launch
                val arr = org.json.JSONArray(json)
                val dao = g.db.memoryDao()
                val have = dao.all().map { it.content.lowercase() }
                for (i in 0 until minOf(arr.length(), 3)) {
                    val fact = arr.optString(i).trim()
                    if (fact.length < 12 || fact.length > 220) continue
                    if (Regex("(?i)password|passcode|\\bpin\\b|otp|card number").containsMatchIn(fact)) continue
                    if (have.any { it == fact.lowercase() || (it.length > 20 && fact.lowercase().contains(it)) }) continue
                    dao.insert(com.privacy.app.data.MemoryEntity(category = "chat", content = fact))
                }
            }
        }
    }
}
