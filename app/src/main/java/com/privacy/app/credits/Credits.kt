package com.privacy.app.credits

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.privacy.app.ai.Net
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.AbstractCoroutineContextElement

/** Put in the coroutine context to name what an AI call is for; it appears in the credit Log. */
class CreditLabel(val text: String, val task: String = java.util.UUID.randomUUID().toString()) : AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<CreditLabel>
}

data class LogRow(val at: String, val action: String, val credits: Double, val balanceAfter: Double)

/**
 * Login (Google through Supabase) + credit balance + the AI proxy.
 * Only NAME and EMAIL are kept about the user. The Groq key lives on the Supabase server, never in the app.
 */
object Credits {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun prefs() = Net.ctx!!.getSharedPreferences("credits", Context.MODE_PRIVATE)

    private val _email = MutableStateFlow("")
    private val _name = MutableStateFlow("")
    private val _balance = MutableStateFlow<Double?>(null)
    private val _log = MutableStateFlow<List<LogRow>>(emptyList())
    private val _support = MutableStateFlow("")
    val support: StateFlow<String> = _support.asStateFlow()
    private val _paid = MutableStateFlow(false)
    private val _freeLeft = MutableStateFlow<Double?>(null)
    private val _freeDaily = MutableStateFlow(150.0)
    val paid: StateFlow<Boolean> = _paid.asStateFlow()
    val freeLeft: StateFlow<Double?> = _freeLeft.asStateFlow()
    val freeDaily: StateFlow<Double> = _freeDaily.asStateFlow()
    /** Subscription as the server knows it (filled by `refresh`). */
    data class Sub(val lastPack: String = "", val lastKind: String = "", val lastAt: String = "", val planName: String = "", val planUntil: String = "", val planActive: Boolean = false)
    private val _sub = MutableStateFlow(Sub())
    val sub: StateFlow<Sub> = _sub.asStateFlow()
    private val _busy = MutableStateFlow(false)
    private val _status = MutableStateFlow("")
    val email: StateFlow<String> = _email.asStateFlow()
    val name: StateFlow<String> = _name.asStateFlow()
    val balance: StateFlow<Double?> = _balance.asStateFlow()
    val log: StateFlow<List<LogRow>> = _log.asStateFlow()
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    val status: StateFlow<String> = _status.asStateFlow()

    private var loaded = false
    fun load() {
        if (loaded || Net.ctx == null) return
        loaded = true
        val p = prefs()
        _email.value = p.getString("email", "").orEmpty()
        _name.value = p.getString("name", "").orEmpty()
        _balance.value = p.getString("balance", null)?.toDoubleOrNull()
    }

    val signedIn: Boolean get() { load(); return _email.value.isNotBlank() && prefs().getString("refresh", "").orEmpty().isNotBlank() }
    /** true when the built-in AI can answer right now: paid credits, or today's free allowance (the server has the final say) */
    fun usable(): Boolean = CreditsConfig.configured && signedIn && ((_balance.value ?: 0.0) >= 1.0 || (_freeLeft.value ?: 1.0) >= 0.3)
    /** Your own API keys are ALWAYS used first. Credits / the free plan only answer when you have no key or your keys fail. */
    val creditsFirst: Boolean get() = false

    // ---------------------------------------------------------------- sign-in
    fun signInUri(): Uri = Uri.parse(
        CreditsConfig.SUPABASE_URL + "/auth/v1/authorize?provider=google&redirect_to=" + URLEncoder.encode(CreditsConfig.REDIRECT, "UTF-8")
    )

    fun startSignIn(ctx: Context) {
        if (!CreditsConfig.configured) { _status.value = "Sign-in is not set up yet (Supabase URL and key are missing in CreditsConfig.kt)."; return }
        _status.value = ""
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, signInUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { _status.value = "No browser found to sign in." }
    }

    /** Called by MainActivity when Supabase sends the browser back to privacy://auth#access_token=… */
    fun handleRedirect(uri: Uri) {
        load()
        val frag = uri.fragment ?: uri.query ?: return
        val m = frag.split("&").mapNotNull { it.split("=", limit = 2).takeIf { p -> p.size == 2 }?.let { p -> p[0] to Uri.decode(p[1]) } }.toMap()
        val access = m["access_token"]; val refresh = m["refresh_token"]
        if (access == null || refresh == null) { _status.value = m["error_description"] ?: "Sign-in was cancelled."; return }
        val exp = System.currentTimeMillis() + (m["expires_in"]?.toLongOrNull() ?: 3600L) * 1000
        prefs().edit().putString("access", access).putString("refresh", refresh).putLong("exp", exp).apply()
        scope.launch { fetchUser(); refresh() }
    }

    /**
     * "Continue with Google" inside the app: the phone's account sheet → Google ID token → Supabase login. Falls back to the browser
     * flow when the native one isn't set up. Returns null on success or a message to show.
     */
    suspend fun signInWithGoogle(activity: android.app.Activity): String? {
        load()
        if (!CreditsConfig.configured) return "Sign-in is not set up yet (Supabase URL and key are missing in CreditsConfig.kt)."
        if (!CreditsConfig.nativeGoogle) { startSignIn(activity); return null }
        return try {
            val idToken = NativeGoogle.idToken(activity)
            withContext(Dispatchers.IO) {
                val r = http("POST", CreditsConfig.SUPABASE_URL + "/auth/v1/token?grant_type=id_token", null,
                    JSONObject().put("provider", "google").put("id_token", idToken).toString())
                if (r.code !in 200..299) return@withContext "Sign-in was refused by the server (HTTP ${r.code}). ${runCatching { JSONObject(r.body).optString("msg") }.getOrNull().orEmpty()}".trim()
                val j = JSONObject(r.body)
                prefs().edit().putString("access", j.getString("access_token")).putString("refresh", j.getString("refresh_token"))
                    .putLong("exp", System.currentTimeMillis() + j.optLong("expires_in", 3600L) * 1000).apply()
                fetchUser(); refresh()
                null
            }
        } catch (e: NativeGoogle.Cancelled) { null
        } catch (e: Exception) { _status.value = ""; "Google sign-in failed: ${e.message}" }
    }

    fun signOut() {
        prefs().edit().clear().apply()
        _email.value = ""; _name.value = ""; _balance.value = null; _log.value = emptyList(); _status.value = ""; _support.value = ""; _paid.value = false; _freeLeft.value = null; _sub.value = Sub()
        runCatching { com.privacy.app.security.CloudBackup.forget() }
    }

    // ---------------------------------------------------------------- http
    private class Resp(val code: Int, val body: String)

    private fun http(method: String, url: String, token: String?, body: String? = null): Resp {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 20_000; readTimeout = 120_000
            setRequestProperty("apikey", CreditsConfig.SUPABASE_ANON_KEY)
            setRequestProperty("Authorization", "Bearer " + (token ?: CreditsConfig.SUPABASE_ANON_KEY))
            setRequestProperty("Content-Type", "application/json")
            if (body != null) doOutput = true
        }
        if (body != null) c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        return Resp(code, text)
    }

    /** Calls a Supabase function (RPC) as the signed-in user. Returns HTTP code + body, or null when not signed in / offline. */
    fun rpc(name: String, json: String = "{}"): Pair<Int, String>? {
        if (!CreditsConfig.configured || !signedIn) return null
        val t = token() ?: return null
        return runCatching { http("POST", CreditsConfig.SUPABASE_URL + "/rest/v1/rpc/" + name, t, json).let { it.code to it.body } }.getOrNull()
    }

    private fun token(): String? {
        val p = prefs()
        val access = p.getString("access", null) ?: return null
        if (System.currentTimeMillis() < p.getLong("exp", 0L) - 60_000L) return access
        val rt = p.getString("refresh", null) ?: return null
        val r = runCatching { http("POST", CreditsConfig.SUPABASE_URL + "/auth/v1/token?grant_type=refresh_token", null, JSONObject().put("refresh_token", rt).toString()) }.getOrNull() ?: return null
        if (r.code !in 200..299) { if (r.code == 400 || r.code == 401) _status.value = "Please sign in again."; return null }
        val j = JSONObject(r.body)
        p.edit().putString("access", j.getString("access_token")).putString("refresh", j.optString("refresh_token", rt))
            .putLong("exp", System.currentTimeMillis() + j.optLong("expires_in", 3600L) * 1000).apply()
        return j.getString("access_token")
    }

    private fun fetchUser() {
        val t = token() ?: return
        val r = runCatching { http("GET", CreditsConfig.SUPABASE_URL + "/auth/v1/user", t) }.getOrNull() ?: return
        if (r.code !in 200..299) return
        val j = JSONObject(r.body)
        val email = j.optString("email")
        val meta = j.optJSONObject("user_metadata")
        val name = meta?.optString("full_name").orEmpty().ifBlank { meta?.optString("name").orEmpty() }
        prefs().edit().putString("email", email).putString("name", name).apply()
        _email.value = email; _name.value = name
    }

    /** Reloads balance and the usage log. Safe to call often. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (!CreditsConfig.configured || !signedIn) return@withContext
        _busy.value = true
        try {
            val t = token() ?: return@withContext
            val st = http("POST", CreditsConfig.SUPABASE_URL + "/rest/v1/rpc/my_status", t, "{}")
            if (st.code in 200..299) {
                val o = JSONObject(st.body)
                val b = o.optDouble("credits", 0.0)
                _balance.value = b; prefs().edit().putString("balance", b.toString()).apply()
                _paid.value = o.optBoolean("paid", false)
                _freeLeft.value = o.optDouble("free_left", 0.0); _freeDaily.value = o.optDouble("free_daily", 150.0)
                fun str(k: String) = if (o.isNull(k)) "" else o.optString(k)
                _sub.value = Sub(str("last_pack"), str("last_kind"), str("last_at"), str("plan_name"), str("plan_until"), o.optBoolean("plan_active", false))
                prefs().edit().putBoolean("plan_active", _sub.value.planActive).apply()
            } else _status.value = "Couldn't load your plan (HTTP ${st.code})."
            // the founder's Telegram: the server only answers if this account has paid
            val sup = http("POST", CreditsConfig.SUPABASE_URL + "/rest/v1/rpc/get_support", t, "{}")
            _support.value = if (sup.code in 200..299) sup.body.trim().trim('"').takeIf { it != "null" }.orEmpty() else ""
            val lg = http("GET", CreditsConfig.SUPABASE_URL + "/rest/v1/credit_log?select=at,action,credits,balance_after&order=at.desc&limit=100", t)
            if (lg.code in 200..299) {
                val a = JSONArray(lg.body)
                _log.value = (0 until a.length()).map { val o = a.getJSONObject(it); LogRow(o.optString("at"), o.optString("action"), o.optDouble("credits"), o.optDouble("balance_after")) }
            }
        } catch (e: Exception) { _status.value = "Couldn't reach the server: ${e.message}" } finally { _busy.value = false }
    }

    // ---------------------------------------------------------------- AI through the proxy
    sealed interface ChatResult {
        data class Ok(val message: JSONObject, val charged: Double) : ChatResult
        data class Err(val reason: String, val retryable: Boolean, val noCredits: Boolean = false, val tooLarge: Boolean = false) : ChatResult
    }

    /** One chat request through the Supabase proxy. The server picks the model, meters the tokens and charges the credits. */
    suspend fun chat(messages: List<JSONObject>, tools: JSONArray?, maxTokens: Int, temperature: Double, big: Boolean, action: String, task: String = java.util.UUID.randomUUID().toString()): ChatResult =
        withContext(Dispatchers.IO) {
            val t = token() ?: return@withContext ChatResult.Err("Sign in again to use credits.", false, noCredits = true)
            val body = JSONObject().put("messages", JSONArray().also { a -> messages.forEach { a.put(it) } })
                .put("max_tokens", maxTokens).put("temperature", temperature).put("tier", if (big) "big" else "small").put("action", action).put("task", task)
            if (tools != null) body.put("tools", tools)
            try {
                val r = http("POST", CreditsConfig.SUPABASE_URL + "/functions/v1/ai-proxy", t, body.toString())
                when {
                    r.code in 200..299 -> {
                        val j = JSONObject(r.body)
                        val charged = j.optJSONObject("credits")
                        charged?.optDouble("balance")?.takeIf { !it.isNaN() }?.let { _balance.value = it; prefs().edit().putString("balance", it.toString()).apply() }
                        charged?.optDouble("free_left")?.takeIf { !it.isNaN() }?.let { _freeLeft.value = it }
                        ChatResult.Ok(j.getJSONArray("choices").getJSONObject(0).getJSONObject("message"), charged?.optDouble("charged") ?: 0.0)
                    }
                    r.code == 402 -> { _balance.value = 0.0; ChatResult.Err("You're out of credits.", false, noCredits = true) }
                    r.code == 413 -> ChatResult.Err("Request too large for the AI's per-minute allowance.", false, tooLarge = true)
                    r.code == 429 || r.code >= 500 -> ChatResult.Err(if (r.code == 503) "The free/credit AI is not available right now. Please try again later, or add your own key in API & Subscription." else "The AI is busy right now (it depends on API load). Try again, or use your own key.", true)
                    else -> ChatResult.Err("The AI could not answer (code ${r.code}).", false)
                }
            } catch (e: Exception) { ChatResult.Err("Couldn't reach the credit server (${e.message}).", true) }
        }

    // ---------------------------------------------------------------- photo, listening and speaking through the proxy
    /** Calls the proxy with a ready JSON body; updates the balance; returns the parsed JSON or throws with a readable message. */
    private fun proxy(body: JSONObject): JSONObject {
        val t = token() ?: throw IllegalStateException("Sign in again to use credits.")
        val r = http("POST", CreditsConfig.SUPABASE_URL + "/functions/v1/ai-proxy", t, body.toString())
        if (r.code == 402) { _balance.value = 0.0; throw com.privacy.app.voice.GroqSpeech.GroqException(402, "You're out of credits.") }
        if (r.code !in 200..299) throw com.privacy.app.voice.GroqSpeech.GroqException(r.code, "Credit server error ${r.code}: ${r.body.take(140)}")
        val j = JSONObject(r.body)
        j.optJSONObject("credits")?.optDouble("balance")?.takeIf { !it.isNaN() }?.let { _balance.value = it; prefs().edit().putString("balance", it.toString()).apply() }
        j.optJSONObject("credits")?.optDouble("free_left")?.takeIf { !it.isNaN() }?.let { _freeLeft.value = it }
        return j
    }

    /** Photo + instruction → text, using the server's vision model. Blocking: call from a background thread. */
    fun vision(jpegBase64: String, prompt: String, action: String): String {
        val j = proxy(JSONObject().put("kind", "vision").put("image_b64", jpegBase64).put("prompt", prompt).put("action", action))
        return j.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content").trim()
    }

    /** Audio file → text (Whisper on the server). Blocking. */
    fun transcribe(file: java.io.File, language: String?): String {
        val secs = runCatching {
            val m = android.media.MediaMetadataRetriever()
            try { m.setDataSource(file.absolutePath); (m.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0 } finally { m.release() }
        }.getOrDefault(0.0).let { if (it <= 0.0) file.length() / 16_000.0 else it }
        val b64 = android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
        val j = proxy(JSONObject().put("kind", "stt").put("audio_b64", b64).put("seconds", secs).put("language", language ?: "").put("action", "Voice to text"))
        return j.optString("text").trim()
    }

    /** Text (≤ ~200 characters) → WAV bytes (Orpheus on the server). Blocking. */
    fun speak(text: String, voice: String): ByteArray {
        val j = proxy(JSONObject().put("kind", "tts").put("text", text).put("voice", voice).put("action", "Spoken reply"))
        return android.util.Base64.decode(j.getString("audio_b64"), android.util.Base64.DEFAULT)
    }
}
