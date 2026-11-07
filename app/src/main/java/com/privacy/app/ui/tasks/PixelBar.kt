package com.privacy.app.ui.tasks

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * The day's progress as a block of pixels. It fills column by column, a light keeps sweeping through the lit pixels
 * and the front edge flickers like a live signal.
 */
@Composable
fun PixelProgressBar(fraction: Float, modifier: Modifier = Modifier, cols: Int = 24, rows: Int = 4, strength: Float = 1f, alive: Boolean = true) {
    val on = com.privacy.app.ui.Anim.enabled
    // fills up when it first appears, then glides to new values
    val shown = com.privacy.app.ui.rememberFillIn(fraction.coerceIn(0f, 1f), 1200)
    val t = rememberInfiniteTransition(label = "pixelBar")
    // calm: a soft pulse on the pixels just in front of the fill, trying to reach 2–3 pixels further – nothing else moves
    val pulseA by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.fillMaxWidth().aspectRatio(cols.toFloat() / rows)) {
        val pulse = if (on && alive) pulseA else 0f
        val cell = size.width / cols
        val gap = cell * 0.14f
        val s = Size(cell - gap * 2, cell - gap * 2)
        val total = cols * rows
        val filled = shown * total
        for (c in 0 until cols) for (r in 0 until rows) {
            val idx = c * rows + r // column by column, bottom to top
            val tl = Offset(c * cell + gap, (rows - 1 - r) * cell + gap)
            if (idx < filled) {
                val a = 0.45f + 0.5f * (c / cols.toFloat())
                drawRect(ink.copy(alpha = (a * strength).coerceIn(0.1f, 1f)), tl, s)
            } else {
                var a = 0.08f
                if (alive && fraction < 0.999f && on) {
                    val ahead = (idx - filled) / rows.toFloat() // columns in front of the edge
                    if (ahead in 0f..2.6f) a += 0.30f * pulse * (1f - ahead / 2.6f)
                }
                drawRect(ink.copy(alpha = (a * strength).coerceIn(0.04f, 1f)), tl, s)
            }
        }
    }
}

/** Small static version for calendar days: [cells] blocks in a row, filled in proportion to [fraction]. */
@Composable
fun MiniPixelBar(fraction: Float, modifier: Modifier = Modifier, cells: Int = 6, dim: Boolean = false) {
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.fillMaxWidth().aspectRatio(cells.toFloat())) {
        val cell = size.width / cells
        val gap = cell * 0.14f
        val filled = fraction.coerceIn(0f, 1f) * cells
        for (i in 0 until cells) {
            val part = (filled - i).coerceIn(0f, 1f)
            val base = if (dim) 0.05f else 0.10f
            drawRect(ink.copy(alpha = base), Offset(i * cell + gap, gap), Size(cell - gap * 2, cell - gap * 2))
            if (part > 0f) drawRect(ink.copy(alpha = 0.85f), Offset(i * cell + gap, gap), Size((cell - gap * 2) * part, cell - gap * 2))
        }
    }
}
