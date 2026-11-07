package com.privacy.app.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.privacy.app.AppGraph
import com.privacy.app.ai.Net
import com.privacy.app.settings.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.sqrt

/** The word being spoken right now – lets the pixel face shape its mouth in time with the voice. */
data class SpokenWord(val word: String, val seq: Int)

/**
 * Text → speech, in two tiers:
 *  1. Natural: Groq's Orpheus voice for short, English replies (free, expressive). The loudness of the audio itself drives the face's mouth.
 *  2. Best available phone voice for everything else (long answers, Hindi, offline). It prefers Google's neural voices and the highest-quality
 *     voice installed instead of the default robotic one.
 */
class Speaker(private val g: AppGraph) {
    private val ctx = g.context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var track: AudioTrack? = null

    private var pending: ((TextToSpeech) -> Unit)? = null
    private val chunks = ConcurrentHashMap<String, String>()
    @Volatile private var remaining = 0

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()
    private val _word = MutableStateFlow(SpokenWord("", 0))
    val word: StateFlow<SpokenWord> = _word.asStateFlow()
    /** Loudness of the audio being played (0..1) when known, otherwise -1. */
    private val _level = MutableStateFlow(-1f)
    val level: StateFlow<Float> = _level.asStateFlow()

    /** Why the natural voice was not used the last time (null = it worked). Shown by the face screen and the Test voice button. */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private class Clip(val sampleRate: Int, val channels: Int, val pcm: ShortArray, val env: FloatArray)

    // ------------------------------------------------------------------ public API
    fun speak(text: String, languageTag: String) {
        stop()
        val plain = plainSpeech(text)
        if (plain.isBlank()) return
        val now = System.currentTimeMillis()
        val all = ttsKeys()
        val ready = all.filter { (cooldown[it.id] ?: 0L) < now }
        // Judge the language from the reply itself (letters only – digits and symbols must not count): Groq's Orpheus is English-only.
        val letters = plain.filter { it.isLetter() }
        val latin = letters.isEmpty() || letters.count { it.code < 0x250 } >= letters.length * 0.85
        val usable = if (latin) ready else ready.filter { it.provider != Provider.GROQ }
        val cr = com.privacy.app.credits.Credits
        val viaCredits = latin && cr.usable() && (cr.creditsFirst || usable.isEmpty())
        val reason = when {
            !g.settings.useGroqTts.value -> "Natural voice is switched off in settings."
            viaCredits && Net.isOnline(ctx) -> null
            all.isEmpty() -> "No key for a natural voice – get credits (API & Subscription) or add a key."
            ready.isEmpty() -> "Voice quota used up for now – using the phone voice."
            usable.isEmpty() -> "This language needs a multilingual voice key (e.g. OpenAI) – using the phone voice."
            !Net.isOnline(ctx) -> "Offline – using the phone voice."
            else -> null
        }
        _notice.value = reason
        if (reason == null) speakNatural(plain, usable, viaCredits) else speakSystem(plain, languageTag)
    }

    /** Keys that can speak: Groq (Orpheus) first because it is free, then paid OpenAI-compatible endpoints. */
    private fun ttsKeys(): List<com.privacy.app.settings.ApiKey> =
        g.settings.keys.value.filter { it.enabled && (it.provider == Provider.GROQ || it.provider == Provider.OPENAI || it.provider == Provider.CUSTOM) }
            .sortedBy { if (it.provider == Provider.GROQ) 0 else 1 }

    private fun voiceFor(k: com.privacy.app.settings.ApiKey): Pair<String, String> {
        val female = GroqSpeech.genderOf(g.settings.assistantGender.value, g.settings.assistantName.value) == "female"
        val m = g.settings.ttsModel.value
        return if (k.provider == Provider.GROQ) voiceName() to m
        else (if (female) "nova" else "onyx") to (if (m.isNotBlank() && !m.contains("orpheus", true)) m else "tts-1")
    }

    private fun synth(k: com.privacy.app.settings.ApiKey, part: String): ByteArray {
        val (voice, model) = voiceFor(k)
        return GroqSpeech.speak(part, k.key, voice, model, k.endpoint)
    }

    fun stop() {
        job?.cancel()
        job = null
        track?.let { runCatching { it.stop() }; runCatching { it.release() } }
        track = null
        sharedTts?.stop()
        chunks.clear()
        remaining = 0
        _speaking.value = false
        _level.value = -1f
    }

    fun shutdown() {
        stop()
        scope.coroutineContext[Job]?.cancel()
    }

    // ------------------------------------------------------------------ natural voice (Groq Orpheus)
    private fun speakNatural(plain: String, keys: List<com.privacy.app.settings.ApiKey>, viaCredits: Boolean = false) {
        _speaking.value = true
        job = scope.launch {
            val parts = GroqSpeech.chunk(plain, if (viaCredits || keys.first().provider == Provider.GROQ) 190 else 900)
            fun fetch(part: String): Result<Clip> = runCatching {
                var last: Exception? = null
                var clip: Clip? = null
                if (viaCredits) {
                    try { clip = parseWav(com.privacy.app.credits.Credits.speak(part, voiceName())) } catch (e: Exception) { last = e }
                }
                if (clip == null) for (k in keys) {
                    if ((cooldown[k.id] ?: 0L) > System.currentTimeMillis()) continue
                    if (k.provider == Provider.GROQ && part.length > 200) continue
                    try {
                        clip = parseWav(synth(k, part)) ?: throw IllegalStateException("Unreadable audio")
                        break
                    } catch (e: GroqSpeech.GroqException) {
                        last = e
                        // quota / rate limit / bad key: stop using this key for a while so the next reply goes straight to the phone voice
                        if (e.code == 429 || e.code == 401 || e.code == 402 || e.code == 403) cooldown[k.id] = System.currentTimeMillis() + (if (e.code == 429) 20 else 60) * 60_000L
                    } catch (e: Exception) {
                        last = e
                    }
                }
                clip ?: throw (last ?: IllegalStateException("No audio"))
            }

            var next: Deferred<Result<Clip>>? = async(Dispatchers.IO) { fetch(parts[0]) }
            for (i in parts.indices) {
                val current = next!!.await()
                next = if (i + 1 < parts.size) async(Dispatchers.IO) { fetch(parts[i + 1]) } else null
                val clip = current.getOrNull()
                if (clip == null) {
                    _notice.value = "Natural voice unavailable (" + (current.exceptionOrNull()?.message ?: "unknown error") + ") – using the phone voice."
                    next?.cancel()
                    speakSystem(parts.drop(i).joinToString(" "), "")
                    return@launch
                }
                play(clip, parts[i])
            }
            _speaking.value = false
            _level.value = -1f
        }
    }

    /** Tries every key that can speak with a short sentence and reports exactly what the provider answered. Plays the sound on success. */
    suspend fun testNatural(): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val keys = ttsKeys()
        if (keys.isEmpty()) return@withContext "No key that can speak. Add a Groq or OpenAI-compatible key in API & Subscription."
        val lines = mutableListOf<String>()
        var played = false
        for (k in keys) {
            try {
                val clip = parseWav(synth(k, "Hello. This is a voice test."))
                if (clip == null) lines += "✗ ${k.label}: answered, but the audio could not be read."
                else {
                    cooldown.remove(k.id)
                    lines += "✓ ${k.label} (${k.provider.label}): works (${voiceFor(k).first})"
                    if (!played) { played = true; kotlinx.coroutines.withContext(Dispatchers.Main) { job = scope.launch { _speaking.value = true; play(clip, "Hello. This is a voice test."); _speaking.value = false } } }
                }
            } catch (e: Exception) {
                lines += "✗ ${k.label}: ${e.message}"
            }
        }
        lines.joinToString("\n")
    }

    private fun voiceName() = GroqSpeech.voiceFor(g.settings.ttsVoice.value, g.settings.assistantGender.value, g.settings.assistantName.value)

    private suspend fun play(clip: Clip, text: String) {
        val mask = if (clip.channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(clip.sampleRate).setChannelMask(mask).build())
            .setBufferSizeInBytes(clip.pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(clip.pcm, 0, clip.pcm.size)
        track = t
        t.play()
        val channels = max(1, clip.channels)
        val frames = clip.pcm.size / channels
        val step = max(1, clip.sampleRate / 50) // 20 ms of audio per envelope value
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        var lastWord = -1
        val startedAt = System.currentTimeMillis()
        val durationMs = frames * 1000L / clip.sampleRate
        while (scope.isActive && job?.isActive == true) {
            val pos = t.playbackHeadPosition
            _level.value = clip.env[(pos / step).coerceIn(0, clip.env.lastIndex)]
            if (words.isNotEmpty()) {
                val w = ((pos.toFloat() / frames) * words.size).toInt().coerceIn(0, words.size - 1)
                if (w != lastWord) { lastWord = w; _word.value = SpokenWord(words[w], _word.value.seq + 1) }
            }
            if (pos >= frames - 1 || System.currentTimeMillis() - startedAt > durationMs + 2500) break
            delay(20)
        }
        runCatching { t.stop() }
        runCatching { t.release() }
        if (track === t) track = null
    }

    private fun parseWav(b: ByteArray): Clip? {
        fun le16(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun le32(o: Int) = le16(o) or (le16(o + 2) shl 16)
        if (b.size < 44 || String(b, 0, 4, Charsets.US_ASCII) != "RIFF") return null
        var pos = 12
        var sampleRate = 48000
        var channels = 1
        var bits = 16
        var dataStart = -1
        var dataLen = 0
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            val size = le32(pos + 4)
            val body = pos + 8
            if (id == "fmt ") {
                channels = le16(body + 2); sampleRate = le32(body + 4); bits = le16(body + 14)
            } else if (id == "data") {
                dataStart = body
                dataLen = if (size <= 0 || body + size > b.size) b.size - body else size
                break
            }
            if (size < 0) break
            pos = body + size + (size and 1)
        }
        if (dataStart < 0 || bits != 16 || channels < 1) return null
        val n = dataLen / 2
        val pcm = ShortArray(n) { i -> ((b[dataStart + 2 * i + 1].toInt() shl 8) or (b[dataStart + 2 * i].toInt() and 0xFF)).toShort() }
        // loudness envelope, one value per 20 ms, used to move the face's mouth
        val frame = max(1, sampleRate / 50) * channels
        val count = max(1, n / frame)
        val env = FloatArray(count)
        var peak = 1e-6f
        for (k in 0 until count) {
            var sum = 0.0
            val from = k * frame
            val to = minOf(n, from + frame)
            for (i in from until to) { val v = pcm[i] / 32768.0; sum += v * v }
            val rms = sqrt(sum / max(1, to - from)).toFloat()
            env[k] = rms
            if (rms > peak) peak = rms
        }
        for (k in 0 until count) env[k] = sqrt((env[k] / peak).coerceIn(0f, 1f))
        return Clip(sampleRate, channels, pcm, env)
    }

    // ------------------------------------------------------------------ phone voice (best one available)
    private fun speakSystem(plain: String, languageTag: String) {
        _speaking.value = true
        ensureTts { t ->
            val locale = when {
                languageTag.isNotBlank() -> Locale.forLanguageTag(languageTag)
                plain.any { it.code in 0x0900..0x097F } -> Locale("hi", "IN")
                else -> Locale.getDefault()
            }
            configure(t, locale)
            val parts = split(plain, 3000)
            remaining = parts.size
            parts.forEachIndexed { i, part ->
                val id = "dh-${System.nanoTime()}-$i"
                chunks[id] = part
                t.speak(part, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
            }
        }
    }

    private fun attach(engine: TextToSpeech) {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { _speaking.value = true }
            override fun onDone(utteranceId: String?) { chunkFinished(utteranceId) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { chunkFinished(utteranceId) }
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                val id = utteranceId ?: return
                val text = chunks[id] ?: return
                val w = text.substring(start.coerceIn(0, text.length), end.coerceIn(0, text.length))
                _word.value = SpokenWord(w, _word.value.seq + 1)
            }
        })
    }

    private fun ensureTts(action: (TextToSpeech) -> Unit) {
        pending = action
        initEngine(ctx) { engine ->
            val act = pending; pending = null
            if (engine == null) { _speaking.value = false; return@initEngine }
            attach(engine)
            act?.invoke(engine)
        }
    }

    /** Picks the best installed voice for the language: highest quality, neural/network when online, regional accent match. */
    private fun configure(t: TextToSpeech, locale: Locale) {
        if (t.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) t.language = locale
        val online = Net.isOnline(ctx)
        val best: Voice? = runCatching {
            t.voices.orEmpty()
                .filter { it.locale.language == locale.language }
                .filter { !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                .filter { online || !it.isNetworkConnectionRequired }
                .sortedWith(
                    compareByDescending<Voice> { it.quality }
                        .thenByDescending { if (it.locale.country == locale.country) 1 else 0 }
                        .thenByDescending { if (online && it.name.contains("network")) 1 else 0 }
                )
                .firstOrNull()
        }.getOrNull()
        if (best != null) t.voice = best
        t.setSpeechRate(0.96f)
        // The phone voice follows the assistant's gender as far as the engine allows.
        val female = GroqSpeech.genderOf(g.settings.assistantGender.value, g.settings.assistantName.value) == "female"
        val named = runCatching {
            t.voices.orEmpty()
                .filter { it.locale.language == locale.language }
                .filter { !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
                .filter { online || !it.isNetworkConnectionRequired }
                .filter { v -> v.name.lowercase().let { n -> if (female) n.contains("female") else (n.contains("male") && !n.contains("female")) } }
                .maxByOrNull { it.quality }
        }.getOrNull()
        if (named != null) t.voice = named
        t.setPitch(if (female) 1.08f else 0.92f)
    }

    private fun chunkFinished(id: String?) {
        if (id != null) chunks.remove(id)
        remaining = (remaining - 1).coerceAtLeast(0)
        if (remaining == 0) _speaking.value = false
    }

    private fun split(s: String, max: Int): List<String> {
        if (s.length <= max) return listOf(s)
        val out = mutableListOf<String>()
        var rest = s
        while (rest.length > max) {
            val cut = rest.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'), max).let { if (it < max / 2) max else it + 1 }
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut)
        }
        if (rest.isNotBlank()) out += rest.trim()
        return out
    }

    companion object {
        /** Phone voice engine shared by the whole app and started at launch, so the first spoken reply has no start-up delay. */
        private var sharedTts: TextToSpeech? = null
        @Volatile private var sharedReady = false
        private val waiters = mutableListOf<(TextToSpeech?) -> Unit>()
        /** key id → time until which that key is skipped (its quota ran out). */
        private val cooldown = ConcurrentHashMap<String, Long>()

        fun warmUp(context: android.content.Context) { initEngine(context) { } }

        private fun initEngine(context: android.content.Context, cb: (TextToSpeech?) -> Unit) {
            val existing: TextToSpeech?
            synchronized(waiters) {
                existing = sharedTts
                if (sharedReady && existing != null) { cb(existing); return }
                waiters += cb
                if (existing != null) return
            }
            val google = "com.google.android.tts"
            val hasGoogle = runCatching { context.packageManager.getPackageInfo(google, 0); true }.getOrDefault(false)
            sharedTts = TextToSpeech(context.applicationContext, { status ->
                val ok = status == TextToSpeech.SUCCESS
                val e = sharedTts
                sharedReady = ok
                if (!ok) { runCatching { e?.shutdown() }; sharedTts = null }
                val list = synchronized(waiters) { waiters.toList().also { waiters.clear() } }
                list.forEach { it(if (ok) e else null) }
            }, if (hasGoogle) google else null)
        }

        /** Turns a Markdown answer into clean text for the voice (no asterisks, links or emoji noise). */
        fun plainSpeech(md: String): String =
            md.replace(Regex("P\\.\\s?R\\.\\s?I\\.\\s?V\\.\\s?A\\.\\s?C\\.\\s?Y\\.?", RegexOption.IGNORE_CASE), "Privacy")
                .replace(Regex("(?s)```.*?```"), " ")
                .replace(Regex("`([^`]*)`"), "$1")
                .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
                .replace(Regex("https?://\\S+"), "link")
                .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
                .replace(Regex("(?m)^\\s*[-*•]\\s+"), "")
                .replace(Regex("(?m)^\\s*\\|.*\\|\\s*$"), "")
                .replace("**", "").replace("*", "").replace("_", " ")
                .replace(Regex("[\\p{So}\\p{Cs}]"), "")
                .replace(Regex("[ \\t]+"), " ")
                .replace(Regex("\\n{2,}"), "\n")
                .trim()
    }
}
