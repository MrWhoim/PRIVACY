package com.privacy.app.media

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.privacy.app.MainActivity
import com.privacy.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class Track(val id: Long, val uri: Uri, val title: String, val artist: String, val durationMs: Long, val albumId: Long, val fileName: String = "")

/** The in-app music (mp3 and other audio) player. Plays files from the phone's storage; a small foreground service keeps it alive when the app is closed. */
object MusicPlayer {
    private var mp: MediaPlayer? = null
    private val main = Handler(Looper.getMainLooper())
    private lateinit var appCtx: Context

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()
    private val _current = MutableStateFlow<Track?>(null)
    val current: StateFlow<Track?> = _current.asStateFlow()
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()
    private val _position = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _position.asStateFlow()
    private val _duration = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _duration.asStateFlow()
    /** One button, four modes: 0 = plain · 1 = shuffle · 2 = repeat all · 3 = repeat one */
    private val _mode = MutableStateFlow(0)
    val mode: StateFlow<Int> = _mode.asStateFlow()
    private val played = LinkedHashSet<Long>()      // songs already played in this shuffle round
    private val history = ArrayList<Track>()        // for "previous" while shuffling
    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private var queue: List<Track> = emptyList()
    private val ticker = object : Runnable {
        override fun run() {
            val p = mp
            if (p != null && _playing.value) { _position.value = runCatching { p.currentPosition.toLong() }.getOrDefault(0L); main.postDelayed(this, 500) }
        }
    }

    fun hasPermission(ctx: Context): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
    }
    val permissionName: String get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun init(ctx: Context) { if (!::appCtx.isInitialized) appCtx = ctx.applicationContext }

    /** Reads every music file on the phone. */
    suspend fun scan(ctx: Context) {
        init(ctx)
        if (!hasPermission(ctx)) { _tracks.value = emptyList(); return }
        val list = withContext(Dispatchers.IO) {
            val out = ArrayList<Track>()
            val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM_ID, MediaStore.Audio.Media.DISPLAY_NAME)
            runCatching {
                ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 15000", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                        // the media database can still list songs that were deleted or moved: only keep files that really open
                        val real = runCatching { ctx.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
                        if (!real) continue
                        out += Track(id, uri, c.getString(1) ?: "Unknown", (c.getString(2) ?: "").let { if (it == "<unknown>") "" else it }, c.getLong(3), c.getLong(4), c.getString(5) ?: "")
                    }
                }
            }
            out
        }
        _tracks.value = list
    }

    /** Words in the query must all appear in the title, artist or file name. */
    fun search(query: String, limit: Int = 8): List<Track> {
        val words = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 && it !in setOf("play", "song", "music", "the", "mp3", "by") }
        if (words.isEmpty()) return emptyList()
        return _tracks.value.map { t ->
            val hay = (t.title + " " + t.artist + " " + t.fileName).lowercase()
            t to words.count { hay.contains(it) }
        }.filter { it.second == words.size }.sortedBy { it.first.title.length }.map { it.first }.take(limit)
    }

    fun trackFromUri(ctx: Context, uri: Uri): Track {
        var title = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Audio"
        var dur = 0L
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.DURATION), null, null, null)?.use { c -> if (c.moveToFirst()) { c.getString(0)?.let { title = it }; dur = c.getLong(1) } }
        }
        return Track(uri.hashCode().toLong(), uri, title, "", dur, 0)
    }

    private fun prepareAndStart(t: Track) {
        val ctx = appCtx
        runCatching { mp?.release() }
        val p = MediaPlayer()
        mp = p
        p.setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setUsage(AudioAttributes.USAGE_MEDIA).build())
        try {
            p.setDataSource(ctx, t.uri)
            p.setOnPreparedListener {
                _duration.value = it.duration.toLong()
                runCatching { if (Build.VERSION.SDK_INT >= 23) it.playbackParams = PlaybackParams().setSpeed(_speed.value) }
                it.start(); _playing.value = true; main.removeCallbacks(ticker); main.post(ticker); MusicService.update(ctx)
            }
            p.setOnCompletionListener { onEnded() }
            p.setOnErrorListener { _, _, _ -> dropBroken(t); true }
            _current.value = t; _position.value = 0; _duration.value = t.durationMs
            p.prepareAsync()
        } catch (e: Exception) { _playing.value = false }
    }

    fun play(ctx: Context, t: Track, within: List<Track> = _tracks.value) {
        init(ctx)
        queue = if (within.any { it.id == t.id }) within else listOf(t) + within
        played.clear(); history.clear()
        prepareAndStart(t)
        MusicService.start(ctx)
    }

    fun playUri(ctx: Context, uri: Uri) { val t = trackFromUri(ctx, uri); play(ctx, t, listOf(t)) }

    fun toggle() {
        val p = mp ?: run { _tracks.value.firstOrNull()?.let { play(appCtx, it) }; return }
        if (p.isPlaying) { p.pause(); _playing.value = false } else { p.start(); _playing.value = true; main.removeCallbacks(ticker); main.post(ticker) }
        MusicService.update(appCtx)
    }
    fun pause() { val p = mp ?: return; if (p.isPlaying) { p.pause(); _playing.value = false; MusicService.update(appCtx) } }

    /** A file that can't be played is removed from the lists and the next song starts (never loops on the same few). */
    private fun dropBroken(t: Track) {
        _tracks.value = _tracks.value.filter { it.id != t.id }
        val i = queue.indexOfFirst { it.id == t.id }
        queue = queue.filter { it.id != t.id }
        if (queue.isEmpty()) { stop(); return }
        _playing.value = false
        prepareAndStart(queue[(if (i < 0) 0 else i) % queue.size])
    }

    private fun pickNext(): Track? {
        if (queue.isEmpty()) return null
        val cur = _current.value
        if (_mode.value == 1 && queue.size > 1) {
            // shuffle without repeating: every song once, then a new round
            cur?.let { played += it.id }
            var pool = queue.filter { it.id !in played }
            if (pool.isEmpty()) { played.clear(); cur?.let { played += it.id }; pool = queue.filter { it.id != cur?.id } }
            return pool.random()
        }
        val i = queue.indexOfFirst { it.id == cur?.id }
        return queue[(i + 1).coerceAtLeast(0) % queue.size]
    }

    fun next() {
        val n = pickNext() ?: return
        _current.value?.let { history += it; if (history.size > 200) history.removeAt(0) }
        prepareAndStart(n)
    }
    fun previous() {
        if (queue.isEmpty()) return
        if ((mp?.currentPosition ?: 0) > 4000) { seekTo(0); return }
        if (_mode.value == 1 && history.isNotEmpty()) { prepareAndStart(history.removeAt(history.lastIndex)); return }
        val i = queue.indexOfFirst { it.id == _current.value?.id }
        prepareAndStart(queue[(i - 1 + queue.size) % queue.size])
    }
    private fun onEnded() {
        when (_mode.value) {
            3 -> _current.value?.let { prepareAndStart(it) }
            1, 2 -> next()
            else -> {
                val i = queue.indexOfFirst { it.id == _current.value?.id }
                if (i in 0 until queue.size - 1) prepareAndStart(queue[i + 1]) else { _playing.value = false; _position.value = 0; MusicService.update(appCtx) }
            }
        }
    }
    fun seekTo(ms: Long) { runCatching { if (Build.VERSION.SDK_INT >= 26) mp?.seekTo(ms, android.media.MediaPlayer.SEEK_CLOSEST) else mp?.seekTo(ms.toInt()) }; _position.value = ms }
    fun cycleMode() { _mode.value = (_mode.value + 1) % 4; played.clear(); history.clear() }
    fun cycleSpeed() {
        val next = when (_speed.value) { 1f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 2f; 2f -> 0.75f; else -> 1f }
        _speed.value = next
        runCatching { if (Build.VERSION.SDK_INT >= 23 && mp?.isPlaying == true) mp?.playbackParams = PlaybackParams().setSpeed(next) }
    }
    fun stop() { runCatching { mp?.release() }; mp = null; _playing.value = false; _current.value = null; main.removeCallbacks(ticker); MusicService.stop(appCtx) }
}

/** Keeps the player alive in the background and shows a small notification with Previous / Play-Pause / Next. */
class MusicService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> MusicPlayer.toggle()
            ACTION_NEXT -> MusicPlayer.next()
            ACTION_PREV -> MusicPlayer.previous()
            ACTION_STOP -> { MusicPlayer.stop(); stopSelf(); return START_NOT_STICKY }
        }
        val n = build(this)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(ID, n)
        if (!MusicPlayer.playing.value) stopForeground(STOP_FOREGROUND_DETACH)
        return START_NOT_STICKY
    }

    companion object {
        const val ID = 4100
        const val CH = "music"
        const val ACTION_TOGGLE = "dh.music.toggle"; const val ACTION_NEXT = "dh.music.next"; const val ACTION_PREV = "dh.music.prev"; const val ACTION_STOP = "dh.music.stop"

        fun start(ctx: Context) { runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, MusicService::class.java)) } }
        fun update(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            runCatching { nm.notify(ID, build(ctx)) }
        }
        fun stop(ctx: Context) { runCatching { ctx.stopService(Intent(ctx, MusicService::class.java)); ctx.getSystemService(NotificationManager::class.java).cancel(ID) } }

        private fun act(ctx: Context, a: String, code: Int) =
            PendingIntent.getService(ctx, code, Intent(ctx, MusicService::class.java).setAction(a), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        fun build(ctx: Context): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(NotificationChannel(CH, "Music player", NotificationManager.IMPORTANCE_LOW))
            val t = MusicPlayer.current.value
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).putExtra("open_screen", "music").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return NotificationCompat.Builder(ctx, CH)
                .setSmallIcon(R.drawable.ic_stat_privacy).setContentTitle(t?.title ?: "Music").setContentText(t?.artist?.ifBlank { null } ?: "P.R.I.V.A.C.Y.")
                .setContentIntent(open).setOngoing(MusicPlayer.playing.value).setSilent(true)
                .addAction(android.R.drawable.ic_media_previous, "Previous", act(ctx, ACTION_PREV, 1))
                .addAction(if (MusicPlayer.playing.value) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, "Play/Pause", act(ctx, ACTION_TOGGLE, 2))
                .addAction(android.R.drawable.ic_media_next, "Next", act(ctx, ACTION_NEXT, 3))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", act(ctx, ACTION_STOP, 4))
                .build()
        }
    }
}
