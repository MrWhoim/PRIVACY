package com.privacy.app.settings

import android.content.Context
import com.privacy.app.AppInfo
import com.privacy.app.security.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Every provider here speaks the same OpenAI-style "/chat/completions" API. */
enum class Provider(val label: String, val baseUrl: String) {
    GROQ("Groq", "https://api.groq.com/openai/v1"),
    OPENAI("OpenAI", "https://api.openai.com/v1"),
    NVIDIA("NVIDIA", "https://integrate.api.nvidia.com/v1"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1"),
    GEMINI("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai"),
    CEREBRAS("Cerebras", "https://api.cerebras.ai/v1"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1"),
    // Claude, Grok, Perplexity, Mistral and Together all offer an OpenAI-style endpoint, so they work like the others
    ANTHROPIC("Claude (Anthropic)", "https://api.anthropic.com/v1"),
    XAI("Grok (xAI)", "https://api.x.ai/v1"),
    PERPLEXITY("Perplexity", "https://api.perplexity.ai"),
    MISTRAL("Mistral", "https://api.mistral.ai/v1"),
    TOGETHER("Together AI", "https://api.together.xyz/v1"),
    CUSTOM("Custom", "")
}

data class ApiKey(
    val id: String,
    val provider: Provider,
    val label: String,
    val key: String,
    val enabled: Boolean = true,
    val baseUrl: String? = null
) {
    /** Pre-installed trial keys (shared, quota may run out). */
    val isTrial: Boolean get() = id.startsWith("trial-")
    val endpoint: String get() = (baseUrl?.takeIf { it.isNotBlank() } ?: provider.baseUrl).trimEnd('/')
    fun masked(): String = if (key.length <= 8) "••••" else key.take(4) + "••••" + key.takeLast(4)
}

/** Models added automatically when a key for a provider is added (the user can change them in Models). model, tier, supportsTools */
object DefaultModels {
    fun forProvider(p: Provider): List<Triple<String, ModelTier, Boolean>> = when (p) {
        Provider.GROQ -> listOf(Triple("llama-3.3-70b-versatile", ModelTier.FAST, true), Triple("openai/gpt-oss-120b", ModelTier.BIG, true))
        Provider.OPENAI -> listOf(Triple("gpt-4o-mini", ModelTier.FAST, true), Triple("gpt-4.1", ModelTier.BIG, true))
        Provider.ANTHROPIC -> listOf(Triple("claude-haiku-5-5", ModelTier.FAST, true), Triple("claude-sonnet-5-5", ModelTier.BIG, true))
        Provider.XAI -> listOf(Triple("grok-3-mini", ModelTier.FAST, true), Triple("grok-4", ModelTier.BIG, true))
        Provider.PERPLEXITY -> listOf(Triple("sonar", ModelTier.FAST, false), Triple("sonar-pro", ModelTier.BIG, false))
        Provider.MISTRAL -> listOf(Triple("mistral-small-latest", ModelTier.FAST, true), Triple("mistral-large-latest", ModelTier.BIG, true))
        Provider.TOGETHER -> listOf(Triple("meta-llama/Llama-3.3-70B-Instruct-Turbo", ModelTier.FAST, true), Triple("deepseek-ai/DeepSeek-V3", ModelTier.BIG, true))
        Provider.GEMINI -> listOf(Triple("gemini-2.5-flash", ModelTier.FAST, true), Triple("gemini-2.5-pro", ModelTier.BIG, true))
        Provider.DEEPSEEK -> listOf(Triple("deepseek-chat", ModelTier.FAST, true), Triple("deepseek-reasoner", ModelTier.BIG, false))
        Provider.CEREBRAS -> listOf(Triple("llama-3.3-70b", ModelTier.FAST, true))
        Provider.NVIDIA -> listOf(Triple("meta/llama-3.3-70b-instruct", ModelTier.FAST, true))
        Provider.OPENROUTER -> listOf(Triple("openai/gpt-oss-20b:free", ModelTier.FAST, true))
        Provider.CUSTOM -> emptyList()
    }
}

/** FAST = small/cheap models that do the legwork. BIG = heavy reasoners used for planning and the final answer in Agent mode. */
enum class ModelTier(val label: String) { FAST("Fast"), BIG("Big") }

data class ModelRoute(
    val id: String,
    val provider: Provider,
    val model: String,
    val enabled: Boolean = true,
    val supportsTools: Boolean = true,
    val tier: ModelTier = ModelTier.FAST
)

/** Guesses from the model's name whether it can look at pictures (the API & Subscription screen has no capability flag). */
object VisionModels {
    private val hints = listOf(
        "vision", "-vl", "vl-", "llama-4", "maverick", "scout", "gemini", "gpt-4o", "gpt-4.1", "gpt-5", "pixtral", "llava",
        "internvl", "glm-4v", "glm-4.5v", "glm-4.6v", "minicpm-v", "phi-3.5-vision", "phi-4-multimodal", "kimi-vl",
        "gemma-3", "gemma3", "gemma-4", "gemma4", "mistral-small-3", "claude"
    )
    /** Voice / speech / safety / embedding models can't hold a chat – they are skipped when answering. */
    fun isNotChat(model: String): Boolean {
        val m = model.lowercase()
        return listOf("orpheus", "whisper", "tts", "playai", "guard", "embed", "rerank", "moderation").any { m.contains(it) }
    }

    fun isVision(model: String): Boolean {
        val m = model.lowercase()
        return !m.contains("guard") && hints.any { m.contains(it) }
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("dh_settings", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)

    private val _keys = MutableStateFlow(loadKeys())
    val keys: StateFlow<List<ApiKey>> = _keys.asStateFlow()

    private val _routes = MutableStateFlow(loadRoutes())
    val routes: StateFlow<List<ModelRoute>> = _routes.asStateFlow()

    init { purgeTrialKeys() }

    /** The old shared "Trial" keys are gone for good: the free plan (sign in with Google) replaces them. Removes any left on the phone. */
    private fun purgeTrialKeys() {
        if (_keys.value.none { it.isTrial }) return
        _keys.update { k -> k.filter { !it.isTrial } }
        saveKeys()
        val have = _keys.value.filter { it.enabled }.map { it.provider }.toSet()
        setRoutes(_routes.value.filter { it.provider in have })
    }

    private val _userName = MutableStateFlow(prefs.getString("user_real_name", "") ?: "")
    /** The user's own name (may be empty). */
    val userName: StateFlow<String> = _userName.asStateFlow()
    fun setUserName(v: String) { _userName.value = v.trim(); prefs.edit().putString("user_real_name", v.trim()).apply(); recomputeCall() }

    private val _userGender = MutableStateFlow(prefs.getString("user_gender", "") ?: "")
    /** "male", "female" or "" (not chosen yet). Decides Sir / Ma'am. */
    val userGender: StateFlow<String> = _userGender.asStateFlow()
    fun setUserGender(v: String) { _userGender.value = v; prefs.edit().putString("user_gender", v).apply(); recomputeCall() }

    private fun callFor(g: String) = if (g == "female") AppInfo.CALL_FEMALE else AppInfo.DEFAULT_USER_CALL
    private val _userCall = MutableStateFlow(callFor(_userGender.value))
    /** How the assistant addresses the user: "Sir" or "Ma'am". */
    val userCall: StateFlow<String> = _userCall.asStateFlow()
    private fun recomputeCall() { _userCall.value = callFor(_userGender.value) }

    /** True once the user has told us their name and gender (the greeting screen asks on first launch). */
    val profileDone: Boolean get() = _userGender.value.isNotBlank()

    private val _assistantName = MutableStateFlow(prefs.getString("assistant_name", AppInfo.DEFAULT_ASSISTANT_NAME)?.ifBlank { AppInfo.DEFAULT_ASSISTANT_NAME } ?: AppInfo.DEFAULT_ASSISTANT_NAME)
    /** What the user calls the assistant. Also decides the voice (male/female) unless a gender is chosen by hand. */
    val assistantName: StateFlow<String> = _assistantName.asStateFlow()
    fun setAssistantName(v: String) { _assistantName.value = v; prefs.edit().putString("assistant_name", v).apply() }

    private val _assistantGender = MutableStateFlow(prefs.getString("assistant_gender", "auto") ?: "auto")
    /** "auto" (guess from the name), "male" or "female". */
    val assistantGender: StateFlow<String> = _assistantGender.asStateFlow()
    fun setAssistantGender(v: String) { _assistantGender.value = v; prefs.edit().putString("assistant_gender", v).apply() }

    private val _fontKey = MutableStateFlow(prefs.getString("font_key", "bricolage") ?: "bricolage")
    val fontKey: StateFlow<String> = _fontKey.asStateFlow()
    fun setFontKey(v: String) { _fontKey.value = v; prefs.edit().putString("font_key", v).apply() }

    /** What language the assistant answers in: auto (the user's), english, hindi, hinglish. */
    private val _replyLang = MutableStateFlow(prefs.getString("reply_lang", "auto") ?: "auto")
    val replyLang: StateFlow<String> = _replyLang.asStateFlow()
    fun setReplyLang(v: String) { _replyLang.value = v; prefs.edit().putString("reply_lang", v).apply() }

    // ---- light / dark ----
    private val _themeMode = MutableStateFlow(prefs.getString("theme_mode", "dark") ?: "dark")
    /** "dark", "light" or "system" */
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()
    fun setThemeMode(v: String) { _themeMode.value = v; prefs.edit().putString("theme_mode", v).apply() }

    // ---- today's efficiency bar under the chat header ----
    private val _effBarOn = MutableStateFlow(prefs.getBoolean("eff_bar_on", true))
    val effBarOn: StateFlow<Boolean> = _effBarOn.asStateFlow()
    fun setEffBarOn(v: Boolean) { _effBarOn.value = v; prefs.edit().putBoolean("eff_bar_on", v).apply() }

    // ---- small companion that lives on screens ----
    private val _companionOn = MutableStateFlow(prefs.getBoolean("companion_on", true))
    val companionOn: StateFlow<Boolean> = _companionOn.asStateFlow()
    fun setCompanionOn(v: Boolean) { _companionOn.value = v; prefs.edit().putBoolean("companion_on", v).apply() }

    /** Screens where the companion may appear: chat, tasks, notes, tools, apis, settings. */
    private val _companionWhere = MutableStateFlow((prefs.getString("companion_where", "chat,apis") ?: "chat,apis").split(",").filter { it.isNotBlank() }.toSet())
    val companionWhere: StateFlow<Set<String>> = _companionWhere.asStateFlow()
    fun setCompanionWhere(key: String, on: Boolean) {
        val n = if (on) _companionWhere.value + key else _companionWhere.value - key
        _companionWhere.value = n; prefs.edit().putString("companion_where", n.joinToString(",")).apply()
    }
    /** Companion corner: "right" (default) or "left". */
    private val _companionSide = MutableStateFlow(prefs.getString("companion_side", "right") ?: "right")
    val companionSide: StateFlow<String> = _companionSide.asStateFlow()
    fun setCompanionSide(v: String) { _companionSide.value = v; prefs.edit().putString("companion_side", v).apply() }

    // ---- assistant starts conversations ----
    private val _proactiveOn = MutableStateFlow(prefs.getBoolean("proactive_on", true))
    val proactiveOn: StateFlow<Boolean> = _proactiveOn.asStateFlow()
    fun setProactiveOn(v: Boolean) { _proactiveOn.value = v; prefs.edit().putBoolean("proactive_on", v).apply() }
    private val _proactiveNotify = MutableStateFlow(prefs.getBoolean("proactive_notify", true))
    val proactiveNotify: StateFlow<Boolean> = _proactiveNotify.asStateFlow()
    fun setProactiveNotify(v: Boolean) { _proactiveNotify.value = v; prefs.edit().putBoolean("proactive_notify", v).apply() }
    private val _proactiveIdleHours = MutableStateFlow(prefs.getInt("proactive_idle_hours", 4))
    val proactiveIdleHours: StateFlow<Int> = _proactiveIdleHours.asStateFlow()
    fun setProactiveIdleHours(v: Int) { _proactiveIdleHours.value = v; prefs.edit().putInt("proactive_idle_hours", v).apply() }
    var lastProactiveAt: Long
        get() = prefs.getLong("proactive_last", 0L)
        set(v) { prefs.edit().putLong("proactive_last", v).apply() }
    /** Last time the user did anything in the app (chat message sent / screen opened). */
    var lastActiveAt: Long
        get() = prefs.getLong("last_active", 0L)
        set(v) { prefs.edit().putLong("last_active", v).apply() }

    /** How many check-ins were sent today (resets by itself on a new day). */
    fun proactiveCountToday(): Int {
        val day = java.time.LocalDate.now().toEpochDay()
        return if (prefs.getLong("proactive_day", -1L) == day) prefs.getInt("proactive_count", 0) else 0
    }
    /** True when a check-in of this kind has NOT been sent today. */
    fun proactiveKindDone(kind: String): Boolean {
        val day = java.time.LocalDate.now().toEpochDay()
        if (prefs.getLong("proactive_day", -1L) != day) return true
        return kind !in (prefs.getString("proactive_kinds", "") ?: "").split(",")
    }
    fun markProactive(kind: String) {
        val day = java.time.LocalDate.now().toEpochDay()
        val same = prefs.getLong("proactive_day", -1L) == day
        val kinds = if (same) (prefs.getString("proactive_kinds", "") ?: "") else ""
        prefs.edit().putLong("proactive_day", day).putInt("proactive_count", (if (same) prefs.getInt("proactive_count", 0) else 0) + 1)
            .putString("proactive_kinds", if (kinds.isBlank()) kind else "$kinds,$kind").apply()
        lastProactiveAt = System.currentTimeMillis()
    }

    // ---- hidden days (old days the user hid from the calendars and reports; the data is kept) ----
    private val _hiddenDays = MutableStateFlow((prefs.getString("hidden_days", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }.toSet())
    val hiddenDays: StateFlow<Set<Long>> = _hiddenDays.asStateFlow()
    fun setDayHidden(day: Long, hidden: Boolean) {
        val n = if (hidden) _hiddenDays.value + day else _hiddenDays.value - day
        _hiddenDays.value = n; prefs.edit().putString("hidden_days", n.joinToString(",")).apply()
    }

    // ---- "I'm Feeling Distracted" mode: the assistant starts and keeps a real-person conversation about the user's big ideas ----
    private val _distractedMode = MutableStateFlow(prefs.getBoolean("distracted_mode", false))
    val distractedMode: StateFlow<Boolean> = _distractedMode.asStateFlow()
    fun setDistractedMode(v: Boolean) { _distractedMode.value = v; prefs.edit().putBoolean("distracted_mode", v).apply() }
    var lastDistractedAt: Long
        get() = prefs.getLong("distracted_last", 0L)
        set(v) { prefs.edit().putLong("distracted_last", v).apply() }

    var lastHealthAlertAt: Long
        get() = prefs.getLong("ha_last", 0L)
        set(v) { prefs.edit().putLong("ha_last", v).apply() }
    // ---- health alerts (water / meals / movement / sleep nudges, written by the AI when it is reachable) ----
    private val _healthAlertsOn = MutableStateFlow(prefs.getBoolean("health_alerts_on", true))
    val healthAlertsOn: StateFlow<Boolean> = _healthAlertsOn.asStateFlow()
    fun setHealthAlertsOn(v: Boolean) { _healthAlertsOn.value = v; prefs.edit().putBoolean("health_alerts_on", v).apply() }
    fun healthAlertSentToday(kind: String): Boolean = prefs.getLong("ha_$kind", -1L) == java.time.LocalDate.now().toEpochDay()
    fun markHealthAlert(kind: String) { prefs.edit().putLong("ha_$kind", java.time.LocalDate.now().toEpochDay()).apply() }

    // ---- Telegram (bot token is encrypted with the Keystore like API keys) ----
    private val _telegramToken = MutableStateFlow(prefs.getString("tg_token_enc", null)?.let { secrets.decrypt(it) } ?: "")
    val telegramToken: StateFlow<String> = _telegramToken.asStateFlow()
    fun setTelegramToken(v: String) {
        _telegramToken.value = v.trim()
        prefs.edit().putString("tg_token_enc", if (v.isBlank()) null else secrets.encrypt(v.trim())).apply()
    }
    private val _telegramUserId = MutableStateFlow(prefs.getString("tg_user_id", "") ?: "")
    /** The user's own numeric Telegram id (the bot sends messages to it). Filled automatically on /start. */
    val telegramUserId: StateFlow<String> = _telegramUserId.asStateFlow()
    fun setTelegramUserId(v: String) { _telegramUserId.value = v.trim(); prefs.edit().putString("tg_user_id", v.trim()).apply() }
    private val _telegramConnected = MutableStateFlow(prefs.getBoolean("tg_connected", false))
    val telegramConnected: StateFlow<Boolean> = _telegramConnected.asStateFlow()
    fun setTelegramConnected(v: Boolean) { _telegramConnected.value = v; prefs.edit().putBoolean("tg_connected", v).apply() }
    private val _telegramMirror = MutableStateFlow(prefs.getBoolean("tg_mirror", true))
    /** Send what you type in the chat window to your Telegram bot too (live copy that saves into Telegram). */
    val telegramMirror: StateFlow<Boolean> = _telegramMirror.asStateFlow()
    fun setTelegramMirror(v: Boolean) { _telegramMirror.value = v; prefs.edit().putBoolean("tg_mirror", v).apply() }
    private val _telegramShowInApp = MutableStateFlow(prefs.getBoolean("tg_show_in_app", true))
    /** Show messages written in Telegram inside the main chat window. */
    val telegramShowInApp: StateFlow<Boolean> = _telegramShowInApp.asStateFlow()
    fun setTelegramShowInApp(v: Boolean) { _telegramShowInApp.value = v; prefs.edit().putBoolean("tg_show_in_app", v).apply() }
    var telegramHiddenChat: Long
        get() = prefs.getLong("tg_hidden_chat", 0L)
        set(v) { prefs.edit().putLong("tg_hidden_chat", v).apply() }
    var telegramOffset: Long
        get() = prefs.getLong("tg_offset", 0L)
        set(v) { prefs.edit().putLong("tg_offset", v).apply() }
    /** Friends: JSON array of {name, chatId, color}. A friend is someone who started the user's bot. */
    var telegramFriends: String
        get() = prefs.getString("tg_friends", "[]") ?: "[]"
        set(v) { prefs.edit().putString("tg_friends", v).apply() }

    /** Friend requests waiting for Accept / Reject: JSON array of {chatId, name, user}. */
    var telegramPending: String
        get() = prefs.getString("tg_pending", "[]") ?: "[]"
        set(v) { prefs.edit().putString("tg_pending", v).apply() }
    /** Chats that already got the "no server, keep the app active" notice. */
    var telegramNoticed: String
        get() = prefs.getString("tg_noticed", "") ?: ""
        set(v) { prefs.edit().putString("tg_noticed", v).apply() }
    /** The owner's own Telegram @username (filled on /start). */
    var telegramOwnerUser: String
        get() = prefs.getString("tg_owner_user", "") ?: ""
        set(v) { prefs.edit().putString("tg_owner_user", v).apply() }
    private val _telegramSaveAsNotes = MutableStateFlow(prefs.getBoolean("tg_save_notes", false))
    /** Save messages that friends send to the bot as notes tagged [Friend name]. */
    val telegramSaveAsNotes: StateFlow<Boolean> = _telegramSaveAsNotes.asStateFlow()
    fun setTelegramSaveAsNotes(v: Boolean) { _telegramSaveAsNotes.value = v; prefs.edit().putBoolean("tg_save_notes", v).apply() }
    /** Modes whose first-time explanation was already shown (comma separated ids). */
    var modesSeen: String
        get() = prefs.getString("modes_seen", "") ?: ""
        set(v) { prefs.edit().putString("modes_seen", v).apply() }

    var presetNewsDone: Boolean
        get() = prefs.getBoolean("preset_news_v1", false)
        set(v) { prefs.edit().putBoolean("preset_news_v1", v).apply() }
    var presetXDone: Boolean
        get() = prefs.getBoolean("preset_x_v1", false)
        set(v) { prefs.edit().putBoolean("preset_x_v1", v).apply() }

    // ---- "Fund the project" ----
    private val _fundUrl = MutableStateFlow(prefs.getString("fund_url", "") ?: "")
    val fundUrl: StateFlow<String> = _fundUrl.asStateFlow()
    fun setFundUrl(v: String) { _fundUrl.value = v.trim(); prefs.edit().putString("fund_url", v.trim()).apply() }

    // ---- floating overlays (notes quick-write, pixel assistant over other apps) ----
    private val _notesOverlayOn = MutableStateFlow(prefs.getBoolean("notes_overlay_on", false))
    val notesOverlayOn: StateFlow<Boolean> = _notesOverlayOn.asStateFlow()
    fun setNotesOverlayOn(v: Boolean) { _notesOverlayOn.value = v; prefs.edit().putBoolean("notes_overlay_on", v).apply() }
    private val _assistantOverlayOn = MutableStateFlow(prefs.getBoolean("assistant_overlay_on", false))
    val assistantOverlayOn: StateFlow<Boolean> = _assistantOverlayOn.asStateFlow()
    fun setAssistantOverlayOn(v: Boolean) { _assistantOverlayOn.value = v; prefs.edit().putBoolean("assistant_overlay_on", v).apply() }

    // ---- smart watch (Health Connect) ----
    private val _watchSyncOn = MutableStateFlow(prefs.getBoolean("watch_sync_on", false))
    val watchSyncOn: StateFlow<Boolean> = _watchSyncOn.asStateFlow()
    fun setWatchSyncOn(v: Boolean) { _watchSyncOn.value = v; prefs.edit().putBoolean("watch_sync_on", v).apply() }

    // ---- backup of the (non-secret) settings: names, gender, assistant, look, companion, animations … ----
    private val secretish = Regex("key|token|secret|pass|salt|pin|hash|vault", RegexOption.IGNORE_CASE)
    fun exportPrefs(): org.json.JSONObject = org.json.JSONObject().also { o ->
        prefs.all.forEach { (k, v) ->
            if (!secretish.containsMatchIn(k) && (v is String || v is Boolean || v is Int || v is Long || v is Float)) o.put(k, v)
        }
    }
    /** Restores settings from a backup. Names and a few live switches apply at once; the rest (look, fonts…) apply the next time the app starts. */
    fun importPrefs(o: org.json.JSONObject) {
        val e = prefs.edit()
        o.keys().forEach { k ->
            if (secretish.containsMatchIn(k)) return@forEach
            com.privacy.app.security.PrefsRestore.put(e, prefs, k, o.get(k))
        }
        e.apply()
        o.optString("user_real_name").takeIf { it.isNotBlank() }?.let { setUserName(it) }
        o.optString("user_gender").takeIf { it.isNotBlank() }?.let { setUserGender(it) }
        o.optString("assistant_name").takeIf { it.isNotBlank() }?.let { setAssistantName(it) }
        if (o.has("animations_on")) setAnimationsOn(o.optBoolean("animations_on", true))
    }

    // ---- animations ----
    private val _animOn = MutableStateFlow(prefs.getBoolean("animations_on", true))
    /** false = no decorative animations (pixel pulses, page fades, swipe transitions) – saves battery and calms the screen. */
    val animationsOn: StateFlow<Boolean> = _animOn.asStateFlow()
    fun setAnimationsOn(v: Boolean) { _animOn.value = v; prefs.edit().putBoolean("animations_on", v).apply() }

    // ---- splash ----
    private val _splashOn = MutableStateFlow(prefs.getBoolean("splash_on", true))
    val splashOn: StateFlow<Boolean> = _splashOn.asStateFlow()
    fun setSplashOn(v: Boolean) { _splashOn.value = v; prefs.edit().putBoolean("splash_on", v).apply() }

    private val _extra = MutableStateFlow(prefs.getString("extra", "") ?: "")
    val extraInstructions: StateFlow<String> = _extra.asStateFlow()

    private val _morningOn = MutableStateFlow(prefs.getBoolean("morning_on", true))
    val morningEnabled: StateFlow<Boolean> = _morningOn.asStateFlow()

    private val _morningMin = MutableStateFlow(prefs.getInt("morning_min", 7 * 60))
    val morningMinutes: StateFlow<Int> = _morningMin.asStateFlow()

    var lastBriefDay: Long
        get() = prefs.getLong("last_brief", -1L)
        set(v) = prefs.edit().putLong("last_brief", v).apply()

    var lastResetDay: Long
        get() = prefs.getLong("last_reset", -1L)
        set(v) = prefs.edit().putLong("last_reset", v).apply()

    // ---- widget + voice state (read by the home-screen widget) ---------------------------------

    /** "IDLE", "LISTENING" or "THINKING". */
    var voiceState: String
        get() = prefs.getString("voice_state", "IDLE") ?: "IDLE"
        set(v) = prefs.edit().putString("voice_state", v).apply()

    var voiceQuestion: String
        get() = prefs.getString("voice_q", "") ?: ""
        set(v) = prefs.edit().putString("voice_q", v).apply()

    var voiceAnswer: String
        get() = prefs.getString("voice_a", "") ?: ""
        set(v) = prefs.edit().putString("voice_a", v).apply()

    private val _voiceLang = MutableStateFlow(prefs.getString("voice_lang", "") ?: "")
    /** BCP-47 tag such as "en-IN" or "hi-IN". Blank = phone language. */
    val voiceLang: StateFlow<String> = _voiceLang.asStateFlow()
    fun setVoiceLang(v: String) { _voiceLang.value = v.trim(); prefs.edit().putString("voice_lang", v.trim()).apply() }

    // ---- voice, vision, goal -----------------------------------------------------------------
    private val _useGroqStt = MutableStateFlow(prefs.getBoolean("groq_stt", true))
    /** Listen with Groq's Whisper (much more accurate than the phone's recognizer). */
    val useGroqStt: StateFlow<Boolean> = _useGroqStt.asStateFlow()
    fun setUseGroqStt(v: Boolean) { _useGroqStt.value = v; prefs.edit().putBoolean("groq_stt", v).apply() }

    private val _useGroqTts = MutableStateFlow(prefs.getBoolean("groq_tts", true))
    /** Speak short replies with Groq's natural Orpheus voice. */
    val useGroqTts: StateFlow<Boolean> = _useGroqTts.asStateFlow()
    fun setUseGroqTts(v: Boolean) { _useGroqTts.value = v; prefs.edit().putBoolean("groq_tts", v).apply() }

    private val _ttsVoice = MutableStateFlow(prefs.getString("tts_voice", "") ?: "")
    /** A specific Orpheus voice chosen by hand. Blank = pick by the assistant's gender. */
    val ttsVoice: StateFlow<String> = _ttsVoice.asStateFlow()
    fun setTtsVoice(v: String) { _ttsVoice.value = v; prefs.edit().putString("tts_voice", v).apply() }

    private val _ttsModel = MutableStateFlow(prefs.getString("tts_model", "") ?: "")
    /** Groq text-to-speech model id. Blank = the Orpheus English model. */
    val ttsModel: StateFlow<String> = _ttsModel.asStateFlow()
    fun setTtsModel(v: String) { _ttsModel.value = v.trim(); prefs.edit().putString("tts_model", v.trim()).apply() }

    private val _sttModel = MutableStateFlow(prefs.getString("stt_model", "") ?: "")
    /** Groq speech-to-text (listening) model id. Blank = whisper-large-v3-turbo. */
    val sttModel: StateFlow<String> = _sttModel.asStateFlow()
    fun setSttModel(v: String) { _sttModel.value = v.trim(); prefs.edit().putString("stt_model", v.trim()).apply() }

    private val _ocrModel = MutableStateFlow(prefs.getString("ocr_model", "") ?: "")
    /** Optional model used only for "Read text" (OCR) from a photo. Blank = the same models as photo analysis. */
    val ocrModel: StateFlow<String> = _ocrModel.asStateFlow()
    fun setOcrModel(v: String) { _ocrModel.value = v.trim(); prefs.edit().putString("ocr_model", v.trim()).apply() }

    private val _visionModel = MutableStateFlow(prefs.getString("vision_model", "") ?: "")
    /** Optional override for the model that reads photos. Blank = best available (Gemini, then Groq Llama 4). */
    val visionModel: StateFlow<String> = _visionModel.asStateFlow()
    fun setVisionModel(v: String) { _visionModel.value = v.trim(); prefs.edit().putString("vision_model", v.trim()).apply() }

    private val _goal = MutableStateFlow(prefs.getString("goal", "") ?: "")
    /** The user's one big ambition – shown on the Tasks screen and known to the assistant. */
    val goal: StateFlow<String> = _goal.asStateFlow()
    fun setGoal(v: String) { _goal.value = v; prefs.edit().putString("goal", v).apply() }

    // ---- chats ------------------------------------------------------------------------------------
    private val _currentConversation = MutableStateFlow(prefs.getLong("conv_current", 0L))
    val currentConversation: StateFlow<Long> = _currentConversation.asStateFlow()
    fun setCurrentConversation(id: Long) { _currentConversation.value = id; prefs.edit().putLong("conv_current", id).apply() }

    private val _agentMode = MutableStateFlow(prefs.getBoolean("agent_mode", false))
    /** true = "Agent" (planner + parallel workers + big model), false = normal chat. */
    val agentMode: StateFlow<Boolean> = _agentMode.asStateFlow()
    fun setAgentMode(v: Boolean) { _agentMode.value = v; prefs.edit().putBoolean("agent_mode", v).apply() }

    var askedNotifications: Boolean
        get() = prefs.getBoolean("asked_notif", false)
        set(v) = prefs.edit().putBoolean("asked_notif", v).apply()

    // ---- keys ----------------------------------------------------------------------------
    /**
     * Adding the user's own key removes the shared Trial keys (and the routes that only worked through them), and gives the new
     * provider sensible default models if it has none yet.
     */
    fun addKey(k: ApiKey) {
        val hadTrial = _keys.value.any { it.isTrial }
        _keys.update { cur -> (if (!k.isTrial) cur.filterNot { it.isTrial } else cur) + k }
        saveKeys()
        if (k.isTrial) return
        if (hadTrial) {
            val have = _keys.value.filter { it.enabled }.map { it.provider }.toSet()
            setRoutes(_routes.value.filter { it.provider in have })
        }
        if (_routes.value.none { it.provider == k.provider }) {
            DefaultModels.forProvider(k.provider).forEach { (m, tier, tools) -> addRoute(k.provider, m, tier, tools) }
        }
    }
    fun updateKey(id: String, f: (ApiKey) -> ApiKey) { _keys.update { l -> l.map { if (it.id == id) f(it) else it } }; saveKeys() }
    fun removeKey(id: String) { _keys.update { l -> l.filterNot { it.id == id } }; saveKeys() }

    // ---- model routes (tried top to bottom) -----------------------------------------------
    fun addRoute(provider: Provider, model: String, tier: ModelTier = ModelTier.FAST, supportsTools: Boolean = true) {
        if (_routes.value.any { it.provider == provider && it.model == model }) return
        setRoutes(_routes.value + ModelRoute(UUID.randomUUID().toString(), provider, model.trim(), tier = tier, supportsTools = supportsTools))
    }
    fun updateRoute(id: String, f: (ModelRoute) -> ModelRoute) = setRoutes(_routes.value.map { if (it.id == id) f(it) else it })
    fun removeRoute(id: String) = setRoutes(_routes.value.filterNot { it.id == id })
    fun moveRoute(id: String, delta: Int) {
        val list = _routes.value.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i))
        setRoutes(list)
    }
    fun markNoTools(routeId: String) = updateRoute(routeId) { it.copy(supportsTools = false) }

    private fun setRoutes(list: List<ModelRoute>) {
        _routes.value = list
        prefs.edit().putString("routes", routesJson(list)).apply()
    }

    // ---- simple settings ------------------------------------------------------------------
    fun setExtra(v: String) { _extra.value = v; prefs.edit().putString("extra", v).apply() }
    fun setMorningEnabled(v: Boolean) { _morningOn.value = v; prefs.edit().putBoolean("morning_on", v).apply() }
    fun setMorningMinutes(v: Int) { _morningMin.value = v; prefs.edit().putInt("morning_min", v).apply() }

    fun aiReady(): Boolean {
        if (com.privacy.app.credits.Credits.usable()) return true
        val providers = _keys.value.filter { it.enabled }.map { it.provider }.toSet()
        return _routes.value.any { it.enabled && it.provider in providers }
    }

    /** Models set up in API & Subscription that can look at pictures (and have an enabled key). */
    fun visionRoutes(): List<ModelRoute> {
        val providers = _keys.value.filter { it.enabled }.map { it.provider }.toSet()
        return _routes.value.filter { it.enabled && it.provider in providers && VisionModels.isVision(it.model) }
    }

    /** True when "analyse a photo" can work: a vision-capable model is set up (or a vision model override is given for a key you have). */
    fun visionReady(): Boolean = visionRoutes().isNotEmpty() ||
        (_visionModel.value.isNotBlank() && _keys.value.any { it.enabled })

    fun primaryLabel(): String? {
        val providers = _keys.value.filter { it.enabled }.map { it.provider }.toSet()
        return _routes.value.firstOrNull { it.enabled && it.provider in providers }
            ?.let { "${it.provider.label} · ${it.model.substringAfterLast('/')}" }
    }

    // ---- persistence ----------------------------------------------------------------------
    private fun saveKeys() {
        val a = JSONArray()
        _keys.value.forEach {
            a.put(JSONObject().put("id", it.id).put("provider", it.provider.name).put("label", it.label)
                .put("key", it.key).put("enabled", it.enabled).put("baseUrl", it.baseUrl ?: ""))
        }
        prefs.edit().putString("keys_enc", secrets.encrypt(a.toString())).apply()
    }

    private fun loadKeys(): List<ApiKey> {
        val enc = prefs.getString("keys_enc", null) ?: return emptyList()
        val json = secrets.decrypt(enc) ?: return emptyList()
        return runCatching {
            val a = JSONArray(json)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                ApiKey(
                    id = o.getString("id"),
                    provider = Provider.valueOf(o.getString("provider")),
                    label = o.optString("label"),
                    key = o.getString("key"),
                    enabled = o.optBoolean("enabled", true),
                    baseUrl = o.optString("baseUrl").ifBlank { null }
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun defaultRoutes(): List<ModelRoute> = listOf(
        ModelRoute(UUID.randomUUID().toString(), Provider.GROQ, "openai/gpt-oss-120b", tier = ModelTier.FAST),
        ModelRoute(UUID.randomUUID().toString(), Provider.OPENROUTER, "nvidia/nemotron-3-ultra-550b-a55b:free", tier = ModelTier.BIG),
        ModelRoute(UUID.randomUUID().toString(), Provider.OPENROUTER, "google/gemma-4-31b-it:free", tier = ModelTier.FAST),
        ModelRoute(UUID.randomUUID().toString(), Provider.GEMINI, "gemini-2.5-flash", tier = ModelTier.BIG)
    )

    private fun routesJson(list: List<ModelRoute>): String {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("id", it.id).put("provider", it.provider.name).put("model", it.model)
                .put("enabled", it.enabled).put("tools", it.supportsTools).put("tier", it.tier.name))
        }
        return a.toString()
    }

    private fun loadRoutes(): List<ModelRoute> {
        val raw = prefs.getString("routes", null)
        val parsed = if (raw == null) null else runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                ModelRoute(
                    o.getString("id"), Provider.valueOf(o.getString("provider")), o.getString("model"),
                    o.optBoolean("enabled", true), o.optBoolean("tools", true),
                    runCatching { ModelTier.valueOf(o.optString("tier", "FAST")) }.getOrDefault(ModelTier.FAST)
                )
            }
        }.getOrNull()

        if (parsed == null) {
            prefs.edit().putBoolean("routes_v2", true).putBoolean("routes_v3", true).apply()
            return defaultRoutes()
        }

        var list = parsed
        var changed = false
        // One-time upgrade for installs made before Agent mode: add the DeepSeek (Big) and small Groq models.
        if (!prefs.getBoolean("routes_v2", false)) {
            val extra = defaultRoutes().filter { d ->
                list.none { it.provider == d.provider && it.model == d.model } && (d.tier == ModelTier.BIG || d.model.endsWith("8b-instant"))
            }
            if (extra.isNotEmpty()) { list = list + extra; changed = true }
            prefs.edit().putBoolean("routes_v2", true).apply()
        }
        // One-time repair: older versions switched tools off for good after any 400 error mentioning "tool", which left the
        // assistant able to chat but not to act (add notes, set automations…). Give every model a fresh chance.
        if (!prefs.getBoolean("routes_v3", false)) {
            if (list.any { !it.supportsTools }) { list = list.map { it.copy(supportsTools = true) }; changed = true }
            prefs.edit().putBoolean("routes_v3", true).apply()
        }
        if (changed) prefs.edit().putString("routes", routesJson(list)).apply()
        return list
    }
}
