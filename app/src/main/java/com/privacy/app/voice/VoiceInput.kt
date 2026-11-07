package com.privacy.app.voice

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.privacy.app.AppGraph
import com.privacy.app.ai.Net
import com.privacy.app.settings.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Speech → text. Create and use it on the main thread.
 *
 * Best path (needs a Groq key and internet): record the voice, send it to Groq's Whisper – accurate with accents,
 * Hindi, English and mixed speech. Fallback: the phone's own recognizer (live captions, works offline on some phones).
 *
 * start() begins; stop() ends and delivers the final text through [Listener.onFinal].
 * With autoStop = true it also ends by itself when you stop talking.
 */
class VoiceInput(private val g: AppGraph) {

    interface Listener {
        fun onListening(on: Boolean) {}
        fun onPartial(text: String) {}
        /** Loudness 0..1, for the pulsing mic. */
        fun onLevel(level: Float) {}
        /** The audio is being turned into text. */
        fun onProcessing(on: Boolean) {}
        fun onFinal(text: String)
        fun onError(message: String) {}
    }

    private enum class Mode { NONE, GROQ, SYSTEM }

    private val ctx = g.context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val recorder = Recorder(ctx)
    private var system: SpeechRecognizer? = null
    private var mode = Mode.NONE
    private var listener: Listener? = null
    private var rotate = 0

    var listening = false
        private set

    private fun groqKeys() = g.settings.keys.value.filter { it.enabled && it.provider == Provider.GROQ }

    fun start(l: Listener, autoStop: Boolean = false) {
        if (listening) return
        listener = l
        val useGroq = g.settings.useGroqStt.value && groqKeys().isNotEmpty() && Net.isOnline(ctx)
        if (useGroq) startGroq(l, autoStop) else startSystem(l)
    }

    fun stop() {
        when (mode) {
            Mode.GROQ -> finishGroq()
            Mode.SYSTEM -> system?.stopListening()
            Mode.NONE -> {}
        }
    }

    fun cancel() {
        scope.coroutineContext.cancelChildren()
        recorder.cancel()
        cleanupSystem()
        listening = false
        mode = Mode.NONE
    }

    fun destroy() { cancel() }

    // ------------------------------------------------------------------ Groq Whisper path
    private fun startGroq(l: Listener, autoStop: Boolean) {
        val ok = recorder.start(object : Recorder.Callback {
            override fun onLevel(level: Float) { l.onLevel(level) }
            override fun onSilence() { finishGroq() }
        }, autoStop)
        if (!ok) { l.onError("Couldn't start the microphone."); return }
        mode = Mode.GROQ
        listening = true
        l.onListening(true)
    }

    private fun finishGroq() {
        if (!listening || mode != Mode.GROQ) return
        listening = false
        mode = Mode.NONE
        val l = listener ?: return
        l.onListening(false)
        val file = recorder.stop()
        if (file == null) { l.onError("I didn't hear anything."); return }
        l.onProcessing(true)
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { transcribe(file) } }
            file.delete()
            l.onProcessing(false)
            result.fold(
                onSuccess = { text -> if (text.isBlank()) l.onError("I didn't catch that – try again.") else l.onFinal(text) },
                onFailure = { e -> l.onError(e.message ?: "Couldn't turn the audio into text.") }
            )
        }
    }

    private fun transcribe(file: java.io.File): String {
        val keys = groqKeys()
        val lang = g.settings.voiceLang.value.substringBefore('-').lowercase().takeIf { it.length == 2 }
        val cr = if (com.privacy.app.credits.Credits.usable() && (com.privacy.app.credits.Credits.creditsFirst || keys.isEmpty())) runCatching { com.privacy.app.credits.Credits.transcribe(file, lang) } else null
        cr?.getOrNull()?.let { return it }
        if (keys.isEmpty()) throw cr?.exceptionOrNull() ?: IllegalStateException("No voice service: get credits in API & Subscription.")
        var lastError: Exception? = null
        val start = rotate++
        for (i in keys.indices) {
            val key = keys[(start + i) % keys.size]
            try {
                return GroqSpeech.transcribe(file, key.key, lang, g.settings.sttModel.value.ifBlank { "whisper-large-v3-turbo" })
            } catch (e: GroqSpeech.GroqException) {
                lastError = e
                if (e.code != 429 && e.code != 401 && e.code != 403 && e.code < 500) break
            } catch (e: Exception) {
                lastError = e
                break
            }
        }
        throw lastError ?: IllegalStateException("Transcription failed.")
    }

    // ------------------------------------------------------------------ phone recognizer path
    private fun startSystem(l: Listener) {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { l.onError("Speech recognition isn't available on this phone."); return }
        cleanupSystem()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        system = r
        var lastPartial = ""
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true; l.onListening(true) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { l.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (t.isNotBlank()) { lastPartial = t; l.onPartial(t) }
            }
            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().ifBlank { lastPartial }
                endSystem(); l.onListening(false); l.onFinal(t.trim())
            }
            override fun onError(error: Int) {
                endSystem(); l.onListening(false)
                if ((error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && lastPartial.isNotBlank()) {
                    l.onFinal(lastPartial.trim()); return
                }
                l.onError(describe(error))
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            g.settings.voiceLang.value.takeIf { it.isNotBlank() }?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
        }
        mode = Mode.SYSTEM
        listening = true
        l.onListening(true)
        r.startListening(intent)
    }

    private fun endSystem() { listening = false; mode = Mode.NONE; cleanupSystem() }

    private fun cleanupSystem() {
        system?.let { runCatching { it.setRecognitionListener(null); it.cancel(); it.destroy() } }
        system = null
    }

    private fun describe(code: Int) = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that – try again."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "The speech service needs internet."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recognizer is busy – try again."
        else -> "Speech recognition failed (code $code)."
    }
}
