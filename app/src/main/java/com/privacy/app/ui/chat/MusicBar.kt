package com.privacy.app.ui.chat

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.East
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privacy.app.media.MusicPlayer
import com.privacy.app.media.MusicUi
import com.privacy.app.ui.MainViewModel
import com.privacy.app.ui.noDrawerSwipe
import com.privacy.app.ui.tasks.PixelProgressBar
import kotlinx.coroutines.launch

private fun mmss(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

/** The strip under the chat header: swipe left/right between today's stats and the music player. */
@Composable
fun HeaderPager(vm: MainViewModel) {
    val pager = rememberPagerState(initialPage = if (MusicUi.mode.value) 1 else 0) { 2 }
    val scope = rememberCoroutineScope()
    val focus by MusicUi.focus
    LaunchedEffect(focus) { if (focus) { pager.animateScrollToPage(1); MusicUi.focus.value = false } }
    // Modes → Music Mode switches the page; swiping keeps the mode flag in step
    val musicMode by MusicUi.mode
    LaunchedEffect(musicMode) { if (pager.currentPage != (if (musicMode) 1 else 0)) pager.animateScrollToPage(if (musicMode) 1 else 0) }
    LaunchedEffect(pager.settledPage) { MusicUi.mode.value = pager.settledPage == 1 }
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { MusicPlayer.init(ctx); if (MusicPlayer.hasPermission(ctx) && MusicPlayer.tracks.value.isEmpty()) MusicPlayer.scan(ctx) }
    HorizontalPager(pager, Modifier.fillMaxWidth().noDrawerSwipe(), verticalAlignment = Alignment.Top) { page ->
        if (page == 0) EffBar(vm) else MusicBar(onClose = { scope.launch { pager.animateScrollToPage(0) } })
    }
    // two tiny dots show which page is open
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), horizontalArrangement = Arrangement.Center) {
        repeat(2) { i ->
            val dot = if (pager.currentPage == i) c.onSurface else c.onSurface.copy(alpha = 0.2f)
            Box(Modifier.padding(horizontal = 3.dp).size(4.dp).drawBehind { drawCircle(dot) })
        }
    }
}

@Composable
fun MusicBar(onClose: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val current by MusicPlayer.current.collectAsStateWithLifecycle()
    val playing by MusicPlayer.playing.collectAsStateWithLifecycle()
    val pos by MusicPlayer.positionMs.collectAsStateWithLifecycle()
    val dur by MusicPlayer.durationMs.collectAsStateWithLifecycle()
    val tracks by MusicPlayer.tracks.collectAsStateWithLifecycle()
    val mode by MusicPlayer.mode.collectAsStateWithLifecycle()
    val speed by MusicPlayer.speed.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var granted by remember { mutableStateOf(MusicPlayer.hasPermission(ctx)) }
    val scope = rememberCoroutineScope()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) scope.launch { MusicPlayer.scan(ctx) } else Toast.makeText(ctx, "Music needs access to the audio files on your phone.", Toast.LENGTH_LONG).show()
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (!granted) ask.launch(MusicPlayer.permissionName) else MusicPlayer.toggle() }, modifier = Modifier.size(36.dp)) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = c.onSurface)
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(current?.title ?: "Music", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (current == null) (if (granted) "Tap ▶ to play" else "Tap ▶ to allow music access") else mmss(pos) + " / " + mmss(dur) + (current?.artist?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                    fontSize = 11.sp, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (current != null) IconButton(onClick = { MusicPlayer.stop() }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, "Stop music", tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
            IconButton(onClick = { open = !open }, modifier = Modifier.size(32.dp)) {
                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (open) "Less" else "More", tint = c.onSurfaceVariant)
            }
        }
        // the pixel bar is the only slider: tap or drag on it to jump inside the song
        var barW by remember { mutableStateOf(1) }
        var dragFrac by remember { mutableStateOf<Float?>(null) }
        PixelProgressBar(
            dragFrac ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f,
            Modifier.padding(top = 2.dp, bottom = 6.dp).onSizeChanged { barW = it.width.coerceAtLeast(1) }
                .noDrawerSwipe()
                .pointerInput(dur) { detectTapGestures { o -> if (dur > 0) MusicPlayer.seekTo((o.x / barW * dur).toLong().coerceIn(0, dur)) } }
                // while dragging only the bar moves; the song jumps ONCE to the final point and carries on at normal speed from there
                .pointerInput(dur) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragFrac = (o.x / barW).coerceIn(0f, 1f) },
                        onDragEnd = { dragFrac?.let { if (dur > 0) MusicPlayer.seekTo((it * dur).toLong().coerceIn(0, dur)) }; dragFrac = null },
                        onDragCancel = { dragFrac = null }
                    ) { ch, _ -> dragFrac = (ch.position.x / barW).coerceIn(0f, 1f) }
                },
            cols = 40, rows = 2
        )
        if (open) {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalMinimumInteractiveComponentSize provides 0.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                // one button, four modes: normal → shuffle → repeat all → repeat one
                IconButton(onClick = { MusicPlayer.cycleMode() }, modifier = Modifier.size(44.dp)) {
                    Icon(
                        when (mode) { 1 -> Icons.Rounded.Shuffle; 2 -> Icons.Rounded.Repeat; 3 -> Icons.Rounded.RepeatOne; else -> Icons.Rounded.East },
                        when (mode) { 1 -> "Shuffle"; 2 -> "Repeat all"; 3 -> "Repeat one"; else -> "Play in order" },
                        tint = if (mode > 0) c.onSurface else c.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                IconButton(onClick = { MusicPlayer.previous() }, modifier = Modifier.size(44.dp)) { Icon(Icons.Rounded.SkipPrevious, "Previous") }
                IconButton(onClick = { if (!granted) ask.launch(MusicPlayer.permissionName) else MusicPlayer.toggle() }, modifier = Modifier.size(48.dp)) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play / pause", modifier = Modifier.size(32.dp)) }
                IconButton(onClick = { MusicPlayer.next() }, modifier = Modifier.size(44.dp)) { Icon(Icons.Rounded.SkipNext, "Next") }
                IconButton(onClick = { searching = !searching; if (!searching) query = "" }, modifier = Modifier.size(44.dp)) { Icon(Icons.Rounded.Search, "Search songs", tint = if (searching) c.onSurface else c.onSurfaceVariant) }
                TextButton(onClick = { MusicPlayer.cycleSpeed() }) { Text("×" + speed.toString().removeSuffix(".0"), fontSize = 13.sp) }
            }
            }
            if (!granted) Button(onClick = { ask.launch(MusicPlayer.permissionName) }, modifier = Modifier.fillMaxWidth()) { Text("Allow access to my music") }
            else {
                if (searching) androidx.compose.material3.OutlinedTextField(
                    query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Search your songs") }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
                )
                val shown = if (query.isBlank()) tracks else MusicPlayer.search(query, 60)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                    items(shown, key = { it.id }) { t ->
                        Row(Modifier.fillMaxWidth().clickable { MusicPlayer.play(ctx, t, shown) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.MusicNote, null, tint = if (current?.id == t.id) c.onSurface else c.onSurfaceVariant, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (current?.id == t.id) FontWeight.Bold else FontWeight.Normal)
                                if (t.artist.isNotBlank()) Text(t.artist, fontSize = 11.sp, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(mmss(t.durationMs), fontSize = 12.sp, color = c.onSurfaceVariant)
                        }
                        HorizontalDivider(color = c.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }
        }
    }
}
