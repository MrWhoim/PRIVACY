package com.privacy.app.voice

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Records the microphone to a small AAC file (16 kHz mono) and reports loudness.
 * With [autoStop] it also notices when you finish talking (about 1.4 s of quiet after speech) – used by the widget and the face.
 */
class Recorder(private val ctx: Context) {
    interface Callback {
        fun onLevel(level: Float)
        /** Fired once when [autoStop] is on and the speaker went quiet. Call [stop] to collect the file. */
        fun onSilence()
    }

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private val handler = Handler(Looper.getMainLooper())
    private var callback: Callback? = null
    private var autoStop = false
    private var startedAt = 0L
    private var lastLoud = 0L
    private var heardSpeech = false
    private var smooth = 0f
    private var notified = false

    val recording: Boolean get() = recorder != null

    private val poll = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            val amp = try { r.maxAmplitude } catch (_: Exception) { 0 }
            val now = System.currentTimeMillis()
            val raw = (amp / 9000f).coerceIn(0f, 1f)
            smooth = if (raw > smooth) raw else smooth * 0.8f + raw * 0.2f
            callback?.onLevel(smooth)
            if (amp > 1800) { heardSpeech = true; lastLoud = now }
            if (autoStop && !notified) {
                val quietFor = now - lastLoud
                val tooLong = now - startedAt > 45_000
                val nothingHeard = !heardSpeech && now - startedAt > 8_000
                if ((heardSpeech && quietFor > 1400) || tooLong || nothingHeard) {
                    notified = true
                    callback?.onSilence()
                }
            }
            handler.postDelayed(this, 70)
        }
    }

    fun start(cb: Callback, autoStop: Boolean): Boolean {
        stopQuietly()
        val dir = File(ctx.cacheDir, "voice").apply { mkdirs() }
        val f = File(dir, "rec-${System.currentTimeMillis()}.m4a")
        return try {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16000)
            r.setAudioEncodingBitRate(64000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = f
            callback = cb
            this.autoStop = autoStop
            startedAt = System.currentTimeMillis(); lastLoud = startedAt
            heardSpeech = false; notified = false; smooth = 0f
            handler.post(poll)
            true
        } catch (_: Exception) {
            stopQuietly()
            false
        }
    }

    /** Ends the recording and returns the audio file (null if nothing usable was captured). */
    fun stop(): File? {
        handler.removeCallbacks(poll)
        val r = recorder ?: return null
        recorder = null
        val f = file
        file = null
        return try {
            r.stop(); r.release()
            f?.takeIf { it.length() > 800 }
        } catch (_: Exception) {
            runCatching { r.release() }
            f?.delete()
            null
        }
    }

    fun cancel() { stop()?.delete() }

    private fun stopQuietly() { handler.removeCallbacks(poll); recorder?.let { runCatching { it.stop() }; runCatching { it.release() } }; recorder = null }
}
