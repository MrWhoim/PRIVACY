package com.privacy.app.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import com.privacy.app.AppGraph
import com.privacy.app.settings.ApiKey
import com.privacy.app.settings.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Photo → text with a *vision AI model* instead of classic OCR, so messy handwriting, whiteboards and tables work.
 * Free options, tried in order: Gemini (best at handwriting) → Groq Llama 4 Maverick → Groq Llama 4 Scout.
 * Tables come back as Markdown tables.
 */
class VisionOcr(private val g: AppGraph) {

    private class Candidate(val key: ApiKey, val model: String)

    suspend fun extract(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        val bitmap = decode(uri) ?: return@withContext Result.failure(IllegalStateException("I couldn't open that image."))
        val candidates = (overrideCandidates(g.settings.ocrModel.value) + candidates()).distinctBy { it.key.id + "|" + it.model }
        val cr = com.privacy.app.credits.Credits
        var creditErr: Exception? = null
        if (cr.usable() && (cr.creditsFirst || candidates.isEmpty())) {
            runCatching { cr.vision(encode(bitmap, 3_800_000), PROMPT, "Photo to text") }
                .onSuccess { if (it.isNotBlank()) return@withContext Result.success(it) }.onFailure { creditErr = it as? Exception }
        }
        if (candidates.isEmpty()) {
            return@withContext Result.failure(
                creditErr ?: IllegalStateException("Reading text from photos needs credits or a key. Get credits in the menu → API & Subscription.")
            )
        }
        var last: Exception? = null
        for (c in candidates) {
            val base64 = encode(bitmap, limitChars = if (c.key.provider == Provider.GROQ) 3_800_000 else 12_000_000)
            try {
                val text = post(c, base64)
                if (text.isNotBlank()) return@withContext Result.success(text)
                last = IllegalStateException("The model returned no text.")
            } catch (e: Exception) {
                last = e
            }
        }
        // own keys all failed → the server (credits / FREE plan) answers
        if (cr.usable()) {
            runCatching { cr.vision(encode(bitmap, 3_800_000), PROMPT, "Photo to text") }
                .onSuccess { if (it.isNotBlank()) return@withContext Result.success(it) }
        }
        Result.failure(last ?: IllegalStateException("Couldn't read the photo."))
    }

    /** Visual analysis (not text extraction): uses the vision-capable models set up in API & Subscription. */
    suspend fun analyze(uri: Uri, question: String): Result<String> = withContext(Dispatchers.IO) {
        val bitmap = decode(uri) ?: return@withContext Result.failure(IllegalStateException("I couldn't open that image."))
        val list = analysisCandidates()
        val prompt = "You are a careful visual analyst. Look at the image and answer the user's request. Reply in the user's language, concisely, " +
            "and say so if something is unclear.\n\nUSER REQUEST: $question"
        val cr = com.privacy.app.credits.Credits
        var creditErr: Exception? = null
        if (cr.usable() && (cr.creditsFirst || list.isEmpty())) {
            runCatching { cr.vision(encode(bitmap, 3_800_000), prompt, "Photo analysis") }
                .onSuccess { if (it.isNotBlank()) return@withContext Result.success(it) }.onFailure { creditErr = it as? Exception }
        }
        if (list.isEmpty()) {
            return@withContext Result.failure(creditErr ?: IllegalStateException("No vision model available. Get credits in menu → API & Subscription."))
        }
        val errors = mutableListOf<String>()
        for (c in list) {
            val base64 = encode(bitmap, limitChars = if (c.key.provider == Provider.GROQ) 3_800_000 else 12_000_000)
            try {
                val text = post(c, base64, prompt)
                if (text.isNotBlank()) return@withContext Result.success(text)
                errors += "${c.key.provider.label} · ${c.model}: no answer"
            } catch (e: Exception) {
                errors += "${c.key.provider.label} · ${c.model}: ${e.message}"
            }
        }
        // own keys all failed → the server (credits / FREE plan) answers
        if (cr.usable()) {
            runCatching { cr.vision(encode(bitmap, 3_800_000), prompt, "Photo analysis") }
                .onSuccess { if (it.isNotBlank()) return@withContext Result.success(it) }
        }
        // the first error belongs to the best-matching provider, so it is the one worth reading
        Result.failure(IllegalStateException(errors.firstOrNull() ?: "Couldn't analyse the photo."))
    }

    /**
     * Which key serves a vision model? A model typed by hand (e.g. "google/gemma-4-31b-it:free") is matched to the provider
     * it looks like it belongs to first – OpenRouter for ":free" / "vendor/model" names, Gemini for "gemini-…" – and then
     * every other enabled key is tried as a fallback, so a wrong guess never ends in "model does not exist".
     */
    private fun analysisCandidates(): List<Candidate> {
        val keys = g.settings.keys.value.filter { it.enabled }
        val out = mutableListOf<Candidate>()
        out += overrideCandidates(g.settings.visionModel.value)
        keys.filter { it.provider == Provider.GEMINI }.forEach { out += Candidate(it, "gemini-2.5-flash") }   // Gemini Flash first
        g.settings.visionRoutes().forEach { r -> keys.filter { it.provider == r.provider }.forEach { out += Candidate(it, r.model) } }
        return out.distinctBy { it.key.id + "|" + it.model }
    }

    /** A model typed by hand: matched to the provider it looks like it belongs to, then every other key as a fallback. */
    private fun overrideCandidates(override: String): List<Candidate> {
        val keys = g.settings.keys.value.filter { it.enabled }
        val out = mutableListOf<Candidate>()
        val ov = override.trim()
        if (ov.isNotBlank()) {
            val m = ov.lowercase()
            val preferred = when {
                m.startsWith("gemini") || m.startsWith("models/gemini") -> listOf(Provider.GEMINI)
                m.endsWith(":free") || m.contains("openrouter") -> listOf(Provider.OPENROUTER)
                m.startsWith("meta-llama/llama-4") || m.startsWith("llama-4") -> listOf(Provider.GROQ, Provider.OPENROUTER, Provider.NVIDIA)
                m.startsWith("nvidia/") || m.startsWith("meta/") -> listOf(Provider.NVIDIA, Provider.OPENROUTER)
                m.contains("/") -> listOf(Provider.OPENROUTER, Provider.NVIDIA, Provider.CUSTOM)
                else -> emptyList()
            }
            val ranked = keys.sortedBy { k -> preferred.indexOf(k.provider).let { if (it < 0) 99 else it } }
            ranked.forEach { out += Candidate(it, ov) }
        }
        return out
    }

    private fun candidates(): List<Candidate> {
        val keys = g.settings.keys.value.filter { it.enabled }
        val out = analysisCandidates().toMutableList()
        // built-in defaults, tried last
        keys.filter { it.provider == Provider.GEMINI }.forEach { out += Candidate(it, "gemini-2.5-flash") }
        keys.filter { it.provider == Provider.GROQ }.forEach { k ->
            listOf("meta-llama/llama-4-maverick-17b-128e-instruct", "meta-llama/llama-4-scout-17b-16e-instruct").forEach { out += Candidate(k, it) }
        }
        return out.distinctBy { it.key.id + "|" + it.model }
    }

    private fun post(c: Candidate, base64: String, prompt: String = PROMPT): String {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64")))
        val body = JSONObject()
            .put("model", c.model).put("temperature", 0).put("max_tokens", 4096)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        val conn = URL(c.key.endpoint + "/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 90_000
        conn.setRequestProperty("Authorization", "Bearer ${c.key.key}")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).getJSONObject("error").optString("message") }.getOrNull().orEmpty()
            throw IllegalStateException(msg.ifBlank { "${c.key.provider.label} error $code" })
        }
        return JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content").trim()
    }

    // ---- image handling: fix rotation, shrink to a sensible size, JPEG ----
    private fun decode(uri: Uri): Bitmap? = try {
        val resolver = g.context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3000) sample *= 2
        val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        val degrees = resolver.openInputStream(uri)?.use { s ->
            when (android.media.ExifInterface(s).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (raw != null && degrees != 0f) Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(degrees) }, true) else raw
    } catch (_: Exception) { null }

    private fun encode(src: Bitmap, limitChars: Int): String {
        var longSide = 2000
        var quality = 85
        while (true) {
            val scale = minOf(1f, longSide / maxOf(src.width, src.height).toFloat())
            val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            if (b64.length <= limitChars || longSide <= 700) return b64
            longSide = (longSide * 0.8f).toInt()
            quality = maxOf(60, quality - 5)
        }
    }

    private companion object {
        const val PROMPT = "You are an expert transcription engine for photos of handwriting, notes, whiteboards, documents and screenshots. " +
            "Transcribe ALL text exactly as written, in reading order, keeping line breaks. The handwriting may be messy: use the surrounding context to work out unclear words, " +
            "and write [?] only for words that are truly illegible. Convert every table into a Markdown table (header row, separator row, one row per line; leave empty cells empty). " +
            "Keep checklists as '- [ ]' and '- [x]'. Keep the original language (Hindi, English or mixed) – do not translate. " +
            "Output ONLY the transcription, with no introduction or commentary."
    }
}
