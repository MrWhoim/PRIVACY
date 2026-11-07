package com.privacy.app.voice

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Groq's free speech endpoints.
 * - Listening: Whisper large-v3-turbo – far better than the phone's built-in recognizer, handles accents, Hindi and English mixed.
 * - Speaking: Orpheus (English) – a natural, expressive voice. Each request may be about 200 characters (the free plan allows roughly 100 voice requests a day, then the phone voice takes over), so replies are spoken in chunks.
 * Both use your existing Groq API key; free-tier rate limits apply.
 */
object GroqSpeech {
    private const val BASE = "https://api.groq.com/openai/v1"
    const val TTS_MODEL = "canopylabs/orpheus-v1-english"
    val FEMALE_VOICES = listOf("autumn", "diana", "hannah")
    val MALE_VOICES = listOf("austin", "daniel", "troy")
    val VOICES = FEMALE_VOICES + MALE_VOICES

    private val FEMALE_NAMES = setOf("friday", "siri", "alexa", "cortana", "jarvisa", "ruby", "luna", "nova", "eve", "ivy", "zoe", "maya", "emma", "olivia", "sophia", "anna", "priya", "riya", "anya", "diya", "kavya", "neha", "pooja", "isha", "sara", "sarah", "lisa", "kate", "amy", "ella", "lily", "mia")

    /** Does this assistant name sound female? (Used only when the voice is left on "follow the name".) */
    fun nameLooksFemale(name: String): Boolean {
        val n = name.trim().lowercase().split(' ', '.').firstOrNull { it.isNotBlank() }.orEmpty()
        if (n.length < 3) return false
        return n in FEMALE_NAMES || n.endsWith("a") || n.endsWith("i") || n.endsWith("ie")
    }

    /** "male" or "female": the chosen gender, or a guess from the assistant's name. */
    fun genderOf(setting: String, name: String): String = when (setting) {
        "male", "female" -> setting
        else -> if (nameLooksFemale(name)) "female" else "male"
    }

    /** The Orpheus voice to use: a hand-picked one if it is set, otherwise one that matches the gender. */
    fun voiceFor(picked: String, setting: String, name: String): String =
        if (picked.isNotBlank()) picked else if (genderOf(setting, name) == "female") FEMALE_VOICES[0] else MALE_VOICES[0]

    class GroqException(val code: Int, message: String) : Exception(message)

    /** Audio file → text. [language] is an ISO code like "en" or "hi", or null to auto-detect. */
    fun transcribe(file: File, apiKey: String, language: String?, model: String = "whisper-large-v3-turbo"): String {
        val boundary = "----dh${System.nanoTime()}"
        val c = URL("$BASE/audio/transcriptions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.setRequestProperty("Authorization", "Bearer $apiKey")
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        DataOutputStream(c.outputStream).use { out ->
            fun field(name: String, value: String) {
                out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
            }
            field("model", model)
            field("response_format", "json")
            field("temperature", "0")
            if (!language.isNullOrBlank()) field("language", language)
            out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\nContent-Type: audio/mp4\r\n\r\n")
            file.inputStream().use { it.copyTo(out) }
            out.writeBytes("\r\n--$boundary--\r\n")
        }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        if (code !in 200..299) throw GroqException(code, errorText(body, code))
        return JSONObject(body).optString("text").trim()
    }

    /** Text (≤ ~200 characters) → WAV bytes. */
    fun speak(text: String, apiKey: String, voice: String, model: String = TTS_MODEL, base: String = BASE): ByteArray {
        val c = URL("${base.trimEnd('/')}/audio/speech").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.setRequestProperty("Authorization", "Bearer $apiKey")
        c.setRequestProperty("Content-Type", "application/json")
        val json = JSONObject().put("model", model.ifBlank { TTS_MODEL }).put("input", text).put("voice", voice).put("response_format", "wav")
        c.outputStream.use { it.write(json.toString().toByteArray()) }
        val code = c.responseCode
        if (code !in 200..299) {
            val err = c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            c.disconnect()
            throw GroqException(code, errorText(err, code))
        }
        val bytes = ByteArrayOutputStream().also { o -> c.inputStream.use { it.copyTo(o) } }.toByteArray()
        c.disconnect()
        return bytes
    }

    const val TERMS_URL = "https://console.groq.com/playground?model=canopylabs%2Forpheus-v1-english"

    /** Keeps Groq's own wording (it says exactly what is wrong) and adds the fix when it is a known case. */
    private fun errorText(body: String, code: Int): String {
        val msg = runCatching { JSONObject(body).getJSONObject("error").optString("message") }.getOrNull().orEmpty()
        val tail = when {
            code == 429 -> " (rate limit or quota used up)"
            code == 401 -> " (the key was rejected)"
            msg.contains("terms", true) -> " → open $TERMS_URL while signed in as the owner of that Groq account and accept the model terms once."
            else -> ""
        }
        return (msg.ifBlank { "Groq error $code" }) + tail
    }

    /** Splits text into pieces that fit Orpheus's per-request limit, preferring sentence ends. */
    fun chunk(text: String, max: Int = 190): List<String> {
        val sentences = Regex("(?<=[.!?।])\\s+|\\n+").split(text.trim()).map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        var cur = StringBuilder()
        fun flush() { if (cur.isNotBlank()) out += cur.toString().trim(); cur = StringBuilder() }
        for (s in sentences) {
            if (s.length > max) {
                flush()
                var rest = s
                while (rest.length > max) {
                    val cut = rest.lastIndexOfAny(charArrayOf(',', ';', ':', ' '), max).let { if (it < max / 2) max else it + 1 }
                    out += rest.substring(0, cut).trim()
                    rest = rest.substring(cut)
                }
                cur.append(rest)
            } else if (cur.length + s.length + 1 > max) {
                flush(); cur.append(s)
            } else {
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(s)
            }
        }
        flush()
        return out.filter { it.isNotBlank() }
    }
}
