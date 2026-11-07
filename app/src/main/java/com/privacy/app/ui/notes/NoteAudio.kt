package com.privacy.app.ui.notes

import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privacy.app.ui.noDrawerSwipe
import com.privacy.app.ui.tasks.PixelProgressBar
import kotlinx.coroutines.delay

/** An audio file that belongs to a note is kept as one line `[[audio:<uri>]]` at the very top of the body; the editor hides it and shows the player instead. */
object NoteAudio {
    private val rx = Regex("^\\[\\[audio:([^\\]]+)\\]\\]\\s*\\n?")
    fun split(body: String): Pair<String?, String> { val m = rx.find(body); return if (m == null) null to body else m.groupValues[1] to body.removeRange(m.range) }
    fun join(uri: String?, rest: String) = if (uri == null) rest else "[[audio:$uri]]\n$rest"
    val inText = Regex("\\[\\[audio:[^\\]]+\\]\\]\\s*")
}

private fun mmss(ms: Int): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

/** Minimal player: play / pause, a pixel bar, elapsed and total length. */
@Composable
fun NoteAudioBar(uri: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    val mp = remember(uri) { runCatching { MediaPlayer().apply { setDataSource(ctx, Uri.parse(uri)); prepare() } }.getOrNull() }
    var playing by remember(uri) { mutableStateOf(false) }
    var pos by remember(uri) { mutableLongStateOf(0L) }
    var drag by remember(uri) { mutableStateOf<Float?>(null) }
    var barW by remember { mutableStateOf(1) }
    val dur = runCatching { mp?.duration ?: 0 }.getOrDefault(0)
    DisposableEffect(uri) { onDispose { runCatching { mp?.release() } } }
    LaunchedEffect(playing, uri) {
        while (playing) {
            pos = runCatching { mp?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)
            if (runCatching { mp?.isPlaying }.getOrNull() == false) { playing = false; pos = 0 }
            delay(250)
        }
    }
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfaceVariant).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.size(38.dp).clip(CircleShape).background(c.onSurface).clickable(enabled = mp != null) {
                if (playing) { mp?.pause(); playing = false } else { mp?.start(); playing = true }
            },
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
        ) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = c.background) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            PixelProgressBar(
                drag ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f,
                Modifier.onSizeChanged { barW = it.width.coerceAtLeast(1) }.noDrawerSwipe()
                    .pointerInput(dur) { detectTapGestures { o -> if (dur > 0) { mp?.seekTo((o.x / barW * dur).toInt().coerceIn(0, dur)); pos = (o.x / barW * dur).toLong() } } }
                    .pointerInput(dur) {
                        detectHorizontalDragGestures(
                            onDragStart = { o -> drag = (o.x / barW).coerceIn(0f, 1f) },
                            onDragEnd = { drag?.let { if (dur > 0) { mp?.seekTo((it * dur).toInt()); pos = (it * dur).toLong() } }; drag = null },
                            onDragCancel = { drag = null }
                        ) { ch, _ -> drag = (ch.position.x / barW).coerceIn(0f, 1f) }
                    },
                cols = 36, rows = 2, alive = playing
            )
            Text(mmss(pos.toInt()) + "  /  " + mmss(dur), fontSize = 11.sp, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
