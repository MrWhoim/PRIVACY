package com.privacy.app.automation

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Data
import com.privacy.app.AppGraph
import com.privacy.app.ai.Net
import com.privacy.app.data.NoteEntity
import com.privacy.app.settings.Provider
import com.privacy.app.voice.GroqSpeech
import com.privacy.app.work.Notifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * "Notes audio" automation: reads the chosen notes aloud into ONE audio file (Music/P.R.I.V.A.C.Y.).
 * It does not give up: pieces already spoken are kept on disk, a rate limit ("no tokens left") is waited out for 90 seconds like the chat does,
 * and when this run's time budget is used up the job books itself again and continues where it stopped.
 */
object NotesAudio {
    private const val BUDGET_MS = 8 * 60_000L          // one run (a background worker is cut off after ~10 minutes)
    private const val WAIT_MS = 90_000L                // the free tier opens a new slot about every 90 seconds
    private const val MAX_FAILS = 8

    /** Notes the automation reads: a keyword in the title/body, or – with no keyword – everything written or changed since the last run (first run: last 24 h). */
    suspend fun pickNotes(g: AppGraph, keyword: String, sinceMs: Long): List<NoteEntity> {
        val all = g.db.noteDao().recent(500)
        val k = keyword.trim()
        return if (k.isNotBlank()) all.filter { it.title.contains(k, true) || it.body.contains(k, true) }
        else all.filter { it.updatedAt >= (if (sinceMs > 0) sinceMs else System.currentTimeMillis() - 24 * 3600_000L) }
    }

    private fun plain(md: String): String = md.replace(Regex("(?m)^!\\[img]\\(.*\\)$"), "").replace(Regex("(?m)^\\s*\\|?[-:| ]{3,}\\|?\\s*$"), "")
        .replace(Regex("[*_`>#~=]"), "").replace("|", ", ").replace(Regex("(?m)^\\s*[-•]\\s+"), "").replace(Regex("\\n{2,}"), ". ").replace('\n', ' ').trim()

    private fun dirOf(ctx: Context, id: Long) = File(ctx.cacheDir, "notes_audio/$id").apply { mkdirs() }

    fun enqueueLater(ctx: Context, automationId: Long, delaySec: Long) {
        val req = OneTimeWorkRequestBuilder<AutomationWorker>()
            .setInitialDelay(delaySec, TimeUnit.SECONDS)
            .setInputData(Data.Builder().putLong(AutomationScheduler.EXTRA_ID, automationId).build()).build()
        WorkManager.getInstance(ctx).enqueue(req)
    }

    /** Returns a short result line. */
    suspend fun run(g: AppGraph, a: com.privacy.app.data.AutomationEntity): String = withContext(Dispatchers.IO) {
        val ctx = g.context
        val nid = 3000 + (a.id % 5000).toInt()
        val dir = dirOf(ctx, a.id)
        val textFile = File(dir, "text.txt")
        if (!textFile.exists()) {
            val notes = pickNotes(g, a.message, a.lastRunAt)
            if (notes.isEmpty()) { dir.deleteRecursively(); return@withContext "No new notes to read." }
            textFile.writeText(notes.joinToString("\n\n") { "${com.privacy.app.ai.NoteRules.splitTag(it.title)?.second ?: it.title}. ${plain(it.body)}" }.take(60_000))
        }
        val text = textFile.readText()
        val latin = text.count { it.isLetter() && it.code < 0x250 } >= text.count { it.isLetter() } * 0.85
        val engineFile = File(dir, "engine.txt")
        var engine = engineFile.takeIf { it.exists() }?.readText() ?: if (latin) "groq" else "phone"
        val groqKeys = g.settings.keys.value.filter { it.enabled && it.provider == Provider.GROQ }
        if (engine == "groq" && groqKeys.isEmpty() && !com.privacy.app.credits.Credits.usable()) engine = "phone"
        engineFile.writeText(engine)

        val started = System.currentTimeMillis()
        val chunks = GroqSpeech.chunk(text, if (engine == "groq") 190 else 1500)
        val voice = GroqSpeech.voiceFor(g.settings.ttsVoice.value, g.settings.assistantGender.value, g.settings.assistantName.value)
        var fails = 0
        var phone: PhoneTts? = null
        var keyIdx = 0
        var i = 0
        while (i < chunks.size) {
            val f = File(dir, "%05d.wav".format(i))
            if (f.exists() && f.length() > 44) { i++; continue }
            if (System.currentTimeMillis() - started > BUDGET_MS) {
                phone?.close()
                enqueueLater(ctx, a.id, 90)
                Notifier.post(ctx, nid, Notifier.CH_AUTOMATION, a.name, "Audio ${i * 100 / chunks.size}% done – continuing in the background.", target = "notes")
                return@withContext "Paused at ${i * 100 / chunks.size}% – continues by itself in 90 s."
            }
            if (!Net.isOnline(ctx) && engine == "groq") { delay(15_000); continue }
            try {
                if (engine == "groq") {
                    val cr = com.privacy.app.credits.Credits
                    if (cr.usable() && (cr.creditsFirst || groqKeys.isEmpty())) f.writeBytes(cr.speak(chunks[i], voice))
                    else {
                        val k = groqKeys[keyIdx % groqKeys.size]
                        f.writeBytes(GroqSpeech.speak(chunks[i], k.key, voice, g.settings.ttsModel.value, k.endpoint))
                    }
                } else {
                    val p = phone ?: PhoneTts(ctx).also { phone = it }
                    if (!p.ready()) throw IllegalStateException("The phone voice is not available.")
                    if (!p.synth(chunks[i], f)) throw IllegalStateException("The phone voice failed.")
                }
                i++; fails = 0
            } catch (e: GroqSpeech.GroqException) {
                if (e.code == 429) {
                    // no token left: try another key, otherwise wait for the next slot (90 s) and go on
                    keyIdx++
                    if (keyIdx % groqKeys.size == 0) delay(WAIT_MS)
                } else if (e.code == 401 || e.code == 403 || e.code == 400) {
                    keyIdx++
                    if (++fails >= groqKeys.size * 2) return@withContext restartAsPhone(g, a)
                } else { if (++fails >= MAX_FAILS) return@withContext restartAsPhone(g, a); delay(5_000) }
            } catch (e: Exception) {
                if (engine == "phone") { phone?.close(); Notifier.post(ctx, nid, Notifier.CH_AUTOMATION, a.name, "Couldn't make the audio: ${e.message}", target = "notes"); dir.deleteRecursively(); return@withContext "Failed: ${e.message}" }
                if (++fails >= MAX_FAILS) return@withContext restartAsPhone(g, a)
                delay(5_000)
            }
        }
        phone?.close()
        val files = (0 until chunks.size).map { File(dir, "%05d.wav".format(it)) }
        val stamp = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("d-MMM-HHmm", Locale.US))
        val saved = saveMusic(ctx, "Notes-audio-$stamp.wav", mergeWav(files))
        dir.deleteRecursively()
        // the audio belongs to the note it was made from: a small player appears at the top of that note
        if (saved != null) runCatching {
            val audioOnly = a.name.startsWith("AudioOnly")
            val n = pickNotes(g, a.message, 0L).firstOrNull { com.privacy.app.ui.notes.NoteAudio.split(it.body).first == null }
            if (n != null) g.db.noteDao().update(n.copy(body = com.privacy.app.ui.notes.NoteAudio.join(saved.toString(), if (audioOnly) "" else n.body), updatedAt = System.currentTimeMillis()))
        }
        Notifier.post(ctx, nid, Notifier.CH_AUTOMATION, a.name, if (saved != null) "Your notes audio is ready (Music/P.R.I.V.A.C.Y.)." else "Couldn't save the audio file.", target = "music")
        if (saved != null) "Audio saved: Music/P.R.I.V.A.C.Y./Notes-audio-$stamp.wav" else "Couldn't save the audio file."
    }

    private fun switchToPhone(dir: File, engineFile: File) {
        dir.listFiles { f -> f.name.endsWith(".wav") }?.forEach { it.delete() }
        engineFile.writeText("phone")
    }

    private suspend fun restartAsPhone(g: AppGraph, a: com.privacy.app.data.AutomationEntity): String {
        switchToPhone(dirOf(g.context, a.id), File(dirOf(g.context, a.id), "engine.txt"))
        enqueueLater(g.context, a.id, 5)
        return "The online voice failed – continuing with the phone voice."
    }

    // ------------------------------------------------------------------ WAV helpers
    private class Wav(val fmt: ByteArray, val data: ByteArray)

    private fun parse(b: ByteArray): Wav? {
        if (b.size < 44 || String(b, 0, 4) != "RIFF") return null
        var p = 12; var fmt: ByteArray? = null; var data: ByteArray? = null
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        while (p + 8 <= b.size) {
            val id = String(b, p, 4); var len = bb.getInt(p + 4)
            if (len < 0 || p + 8 + len > b.size) len = b.size - p - 8   // streamed WAVs may report a huge/invalid length
            if (id == "fmt ") fmt = b.copyOfRange(p + 8, p + 8 + len)
            if (id == "data") { data = b.copyOfRange(p + 8, p + 8 + len); break }
            p += 8 + len + (len and 1)
        }
        return if (fmt != null && data != null) Wav(fmt, data) else null
    }

    private fun mergeWav(files: List<File>): ByteArray {
        val parts = files.mapNotNull { runCatching { parse(it.readBytes()) }.getOrNull() }
        if (parts.isEmpty()) return ByteArray(0)
        val fmt = parts[0].fmt
        val total = parts.sumOf { it.data.size }
        val out = ByteArrayOutputStream(44 + total)
        val h = ByteBuffer.allocate(12 + 8 + fmt.size + 8).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(4 + 8 + fmt.size + 8 + total); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(fmt.size); h.put(fmt)
        h.put("data".toByteArray()); h.putInt(total)
        out.write(h.array()); parts.forEach { out.write(it.data) }
        return out.toByteArray()
    }

    private fun saveMusic(ctx: Context, name: String, bytes: ByteArray): android.net.Uri? = runCatching {
        if (bytes.isEmpty()) return@runCatching null
        val cv = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name); put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
            put(MediaStore.Audio.Media.TITLE, name.removeSuffix(".wav"))
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/P.R.I.V.A.C.Y.")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv) ?: return@runCatching null
        ctx.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        uri
    }.getOrNull()

    /** The phone's own voice, writing straight to a WAV file. */
    private class PhoneTts(ctx: Context) {
        @Volatile private var ok: Boolean? = null
        private var tts: TextToSpeech? = TextToSpeech(ctx.applicationContext) { st -> ok = (st == TextToSpeech.SUCCESS) }
        suspend fun ready(): Boolean { repeat(100) { if (ok != null) return ok == true; delay(100) }; return ok == true }
        suspend fun synth(text: String, out: File): Boolean = suspendCancellableCoroutine { cont ->
            val t = tts ?: run { cont.resume(false); return@suspendCancellableCoroutine }
            val id = "u${System.nanoTime()}"
            var done = false
            fun finish(v: Boolean) { if (!done) { done = true; cont.resume(v) } }
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { finish(out.exists() && out.length() > 44) }
                @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { finish(false) }
            })
            if (t.synthesizeToFile(text, Bundle(), out, id) != TextToSpeech.SUCCESS) finish(false)
        }
        fun close() { runCatching { tts?.shutdown() }; tts = null }
    }
}
