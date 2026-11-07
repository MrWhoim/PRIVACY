package com.privacy.app.ai

import com.privacy.app.settings.VisionModels
import com.privacy.app.settings.ApiKey
import com.privacy.app.settings.ModelRoute
import com.privacy.app.settings.ModelTier
import com.privacy.app.settings.Provider
import com.privacy.app.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

fun JSONObject.contentOrEmpty(): String = if (isNull("content")) "" else optString("content")

/**
 * Talks to any OpenAI-compatible provider (Groq, NVIDIA, OpenRouter, Gemini, Cerebras, custom).
 *
 * Routing: models are tried top-to-bottom. For each model, your keys for that provider are rotated round-robin, so several
 * free accounts share the load. Rate limits are tracked per key AND per model (that is how Groq counts them), so one
 * exhausted model never blocks the others.
 *
 * Queue: when everything is busy or rate-limited the request WAITS (up to about 90 seconds, retrying as soon as a slot
 * frees up) instead of failing at once. [queue] tells the UI what to show while it waits.
 */
/** Put in the coroutine context to let LlmClient wait longer than the chat's 90 s for a free AI slot (automations use 10 minutes). */
class LlmPatience(val ms: Long) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<LlmPatience>
}

class LlmClient(private val settings: SettingsStore) {

    sealed interface Result {
        data class Ok(val message: JSONObject, val route: ModelRoute, val keyLabel: String) : Result
        data class Fail(val reason: String, val retryable: Boolean) : Result
    }

    private sealed interface Attempt {
        data class Success(val msg: JSONObject) : Attempt
        data class Error(val code: Int, val body: String, val retryAfterMs: Long) : Attempt
    }

    private enum class Kind { RATE, SERVER, AUTH }
    private class Rest(val until: Long, val kind: Kind)

    private val resting = ConcurrentHashMap<String, Rest>()
    private val rotation = ConcurrentHashMap<Provider, AtomicInteger>()

    private val _queue = MutableStateFlow<String?>(null)
    /** Non-null while a request is waiting for a free slot, e.g. "In the queue · retrying in 12s". */
    val queue: StateFlow<String?> = _queue.asStateFlow()
    private val waiting = AtomicInteger(0)

    suspend fun chat(
        messages: List<JSONObject>,
        tools: JSONArray?,
        maxTokens: Int = 2048,
        temperature: Double = 0.6,
        /** Preferred model tier. Models of that tier are tried first, everything else is the fallback. */
        tier: ModelTier? = null,
        /** false = one quick pass, never waits in the queue (used by the little on-screen assistant so it can't hog or block the chat). */
        patient: Boolean = true
    ): Result {
        if (!Net.online()) return Result.Fail(Net.OFFLINE, false)
        val startedAt = System.currentTimeMillis()
        val limitMs = kotlin.coroutines.coroutineContext[LlmPatience]?.ms ?: QUEUE_LIMIT_MS
        val deadline = startedAt + limitMs
        while (true) {
            val r = tryAll(messages, tools, maxTokens, temperature, tier)
            if (r is Result.Ok) return r
            r as Result.Fail
            val now = System.currentTimeMillis()
            if (!patient) return Result.Fail(r.reason.lines().firstOrNull().orEmpty(), r.retryable)
            if (!r.retryable || now >= deadline) {
                return Result.Fail(friendly(r, waitedMs = now - startedAt), false)
            }
            val wait = nextSlotInMs().coerceIn(1_500L, if (limitMs > QUEUE_LIMIT_MS) 30_000L else 15_000L).coerceAtMost(deadline - now).coerceAtLeast(500L)
            waiting.incrementAndGet()
            try {
                var left = wait
                while (left > 0) {
                    val elapsed = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
                    _queue.value = "In the queue · all AI slots are busy · retrying in ${(left + 999) / 1000}s · ${elapsed}s of ${limitMs / 1000}s"
                    val step = minOf(1_000L, left)
                    delay(step)
                    left -= step
                }
            } finally {
                if (waiting.decrementAndGet() == 0) _queue.value = null
            }
        }
    }

    private fun friendly(r: Result.Fail, waitedMs: Long): String {
        val details = r.reason.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(6)
        val head = if (r.retryable) {
            "All your AI connections stayed busy for about ${(waitedMs / 1000).coerceAtLeast(1)} seconds. " +
                "Try again in a minute, or add another API (API & Subscription) to share the load."
        } else "I couldn't get an answer from any AI model."
        return head + if (details.isEmpty()) "" else "\n\nDetails:\n" + details.joinToString("\n") { "- $it" }
    }

    private fun nextSlotInMs(): Long {
        val now = System.currentTimeMillis()
        val waits = resting.values.filter { it.kind != Kind.AUTH && it.until > now }.map { it.until - now }
        return waits.minOrNull() ?: 3_000L
    }

    private suspend fun tryAll(
        messages: List<JSONObject>, tools: JSONArray?, maxTokens: Int, temperature: Double, tier: ModelTier?
    ): Result = withContext(Dispatchers.IO) {
        val problems = mutableListOf<String>()
        var retryable = false
        // Credits (built-in AI through the Supabase proxy). Own keys cost nothing, so by default credits go first only when the user chose that.
        val cl = kotlin.coroutines.coroutineContext[com.privacy.app.credits.CreditLabel]
        val label = cl?.text ?: if (tier == ModelTier.FAST) "Quick task" else "Chat reply"
        val task = cl?.task ?: java.util.UUID.randomUUID().toString()
        suspend fun viaCredits(): Result.Ok? {
            if (!com.privacy.app.credits.Credits.usable()) return null
            val cr = com.privacy.app.credits.Credits
            val big = tier != ModelTier.FAST
            var c = cr.chat(messages, tools, maxTokens, temperature, big, label, task)
            // too big for the per-minute allowance → same recovery as with own keys: essential tools + shorter answer, then a trimmed chat without tools
            if (c is com.privacy.app.credits.Credits.ChatResult.Err && c.tooLarge) {
                if (tools != null) c = cr.chat(messages, ToolSpecs.core, minOf(maxTokens, 1200), temperature, big, label, task)
                if (c is com.privacy.app.credits.Credits.ChatResult.Err && c.tooLarge) {
                    val sys = messages.firstOrNull { it.optString("role") == "system" }?.let { JSONObject().put("role", "system").put("content", it.contentOrEmpty().take(6000)) }
                    val tail = messages.filter { it.optString("role") == "user" || (it.optString("role") == "assistant" && !it.has("tool_calls")) }.takeLast(4)
                    c = cr.chat(listOfNotNull(sys) + tail, null, minOf(maxTokens, 1200), temperature, big, label, task)
                }
            }
            return when (c) {
                is com.privacy.app.credits.Credits.ChatResult.Ok ->
                    Result.Ok(c.message, ModelRoute("credits", Provider.GROQ, "credits", true, true, tier ?: ModelTier.BIG), "Credits")
                is com.privacy.app.credits.Credits.ChatResult.Err -> { problems += "Credits: ${c.reason}"; if (c.retryable) retryable = true; null }
            }
        }
        if (com.privacy.app.credits.Credits.creditsFirst) viaCredits()?.let { return@withContext it }
        val enabledRoutes = settings.routes.value.filter { it.enabled && !VisionModels.isNotChat(it.model) }
        val routes = if (tier == null) enabledRoutes else enabledRoutes.sortedBy { if (it.tier == tier) 0 else 1 }
        val allKeys = settings.keys.value.filter { it.enabled }
        if (routes.isEmpty() || allKeys.isEmpty()) {
            if (!com.privacy.app.credits.Credits.creditsFirst) viaCredits()?.let { return@withContext it }
            return@withContext Result.Fail(problems.joinToString("\n").ifBlank { "No AI is set up yet. Add your own API key, or sign in and get credits, in the menu → API & Subscription." }, retryable)
        }

        for (route in routes) {
            val keys = allKeys.filter { it.provider == route.provider }
            if (keys.isEmpty()) continue
            // getAndIncrement spreads parallel callers (Agent mode workers) over different keys.
            val start = rotation.getOrPut(route.provider) { AtomicInteger(0) }.getAndIncrement().coerceAtLeast(0)
            for (i in keys.indices) {
                val key = keys[(start + i) % keys.size]
                val now = System.currentTimeMillis()
                val auth = resting[key.id]
                if (auth != null && auth.kind == Kind.AUTH && auth.until > now) {
                    problems += "${key.label}: key rejected"
                    continue
                }
                val slot = resting[key.id + "|" + route.model]
                if (slot != null && slot.until > now) {
                    problems += "${route.provider.label}/${route.model} via ${key.label}: " +
                        if (slot.kind == Kind.RATE) "rate limit reached – cooling down" else "temporarily unavailable"
                    retryable = true
                    continue
                }
                var current = route
                var attempt = call(key, current, messages, tools, maxTokens, temperature)

                // Request too big for this model's per-minute allowance → retry with the essential tools and a smaller answer.
                if (attempt is Attempt.Error && attempt.code == 413 && tools != null) {
                    attempt = call(key, current, messages, ToolSpecs.core, minOf(maxTokens, 1200), temperature)
                }
                if (attempt is Attempt.Error && attempt.code == 400 && tools != null && current.supportsTools) {
                    val body = attempt.body
                    when {
                        // The model genuinely can't do function calling → remember that.
                        NO_TOOLS.containsMatchIn(body) -> {
                            settings.markNoTools(route.id)
                            current = route.copy(supportsTools = false)
                            attempt = call(key, current, messages, tools, maxTokens, temperature)
                        }
                        // The model produced a malformed tool call this time (common on Groq) → retry this request in text mode,
                        // but do NOT switch tools off for good.
                        BAD_CALL.containsMatchIn(body) -> {
                            current = route.copy(supportsTools = false)
                            attempt = call(key, current, messages, tools, maxTokens, temperature)
                        }
                    }
                }
                when (attempt) {
                    is Attempt.Success -> return@withContext Result.Ok(attempt.msg, current, key.label)
                    is Attempt.Error -> {
                        problems += "${route.provider.label}/${route.model} via ${key.label}: ${describe(attempt)}"
                        val slotKey = key.id + "|" + route.model
                        when {
                            attempt.code == 429 || attempt.code == 413 -> {
                                resting[slotKey] = Rest(System.currentTimeMillis() + maxOf(attempt.retryAfterMs, 15_000L), Kind.RATE)
                                retryable = true
                            }
                            attempt.code == 401 || attempt.code == 403 ->
                                resting[key.id] = Rest(System.currentTimeMillis() + 10 * 60_000L, Kind.AUTH)
                            attempt.code >= 500 || attempt.code == -1 -> {
                                resting[slotKey] = Rest(System.currentTimeMillis() + 8_000L, Kind.SERVER)
                                retryable = true
                            }
                            else -> break // bad model name / bad request: try the next model
                        }
                    }
                }
            }
        }
        if (!com.privacy.app.credits.Credits.creditsFirst) viaCredits()?.let { return@withContext it }
        Result.Fail(problems.joinToString("\n").ifBlank { "None of your keys match the enabled models." }, retryable)
    }

    private fun describe(e: Attempt.Error): String {
        val detail = runCatching { JSONObject(e.body).optJSONObject("error")?.optString("message") }.getOrNull()
            ?: e.body.take(160)
        return when (e.code) {
            -1 -> "network problem ($detail)"
            401, 403 -> "key rejected"
            413 -> "request too large for this model's per-minute allowance"
            429 -> "rate limit reached"
            else -> "HTTP ${e.code} $detail".trim()
        }
    }

    private fun call(
        key: ApiKey, route: ModelRoute, messages: List<JSONObject>, tools: JSONArray?,
        maxTokens: Int, temperature: Double
    ): Attempt {
        val useTools = tools != null && route.supportsTools
        val outgoing = if (tools != null && !route.supportsTools) downgrade(messages) else messages
        val msgs = JSONArray().also { a -> outgoing.forEach { a.put(it) } }
        val body = JSONObject().put("model", route.model).put("messages", msgs)
            .put("temperature", temperature).put("max_tokens", maxTokens).put("stream", false)
        if (useTools) body.put("tools", tools).put("tool_choice", "auto")
        return try {
            val conn = (URL(key.endpoint + "/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; connectTimeout = 20_000; readTimeout = 120_000; doOutput = true
                setRequestProperty("Authorization", "Bearer ${key.key}")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code in 200..299) {
                Attempt.Success(JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message"))
            } else {
                val retry = conn.getHeaderField("retry-after")?.toDoubleOrNull()?.times(1000)?.toLong() ?: 0L
                Attempt.Error(code, text, retry)
            }
        } catch (e: Exception) {
            Attempt.Error(-1, e.message ?: e.javaClass.simpleName, 0)
        }
    }

    /** For models without function calling: tool calls/results become plain text turns. */
    private fun downgrade(messages: List<JSONObject>): List<JSONObject> = messages.map { m ->
        when (m.optString("role")) {
            "system" -> JSONObject().put("role", "system").put("content", m.contentOrEmpty() + "\n\n" + ToolSpecs.textProtocol())
            "assistant" -> if (m.has("tool_calls")) {
                val calls = m.getJSONArray("tool_calls")
                val sb = StringBuilder(m.contentOrEmpty())
                for (i in 0 until calls.length()) {
                    val fn = calls.getJSONObject(i).getJSONObject("function")
                    sb.append("\n<tool>{\"name\":\"").append(fn.optString("name")).append("\",\"args\":")
                        .append(fn.optString("arguments").ifBlank { "{}" }).append("}</tool>")
                }
                JSONObject().put("role", "assistant").put("content", sb.toString().trim())
            } else m
            "tool" -> JSONObject().put("role", "user")
                .put("content", "Tool result for ${m.optString("name")}:\n${m.contentOrEmpty()}")
            else -> m
        }
    }

    // ---- helpers for Settings screen ---------------------------------------------------------
    suspend fun listModels(key: ApiKey): List<String> = withContext(Dispatchers.IO) {
        val conn = (URL(key.endpoint + "/models").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer ${key.key}")
            setRequestProperty("Accept", "application/json")
        }
        val code = conn.responseCode
        if (code !in 200..299) throw IllegalStateException(if (code == 401 || code == 403) "Key rejected (HTTP $code)" else "HTTP $code")
        val data = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getJSONArray("data")
        (0 until data.length()).map { data.getJSONObject(it).getString("id") }.sorted()
    }

    private companion object {
        const val QUEUE_LIMIT_MS = 90_000L
        val NO_TOOLS = Regex("(does not|doesn't|not)\\s+support.{0,40}(tool|function)|(tool|function).{0,40}(not supported|unsupported)", RegexOption.IGNORE_CASE)
        val BAD_CALL = Regex("tool_use_failed|failed to call a function|failed_generation|invalid.{0,20}tool", RegexOption.IGNORE_CASE)
    }
}
