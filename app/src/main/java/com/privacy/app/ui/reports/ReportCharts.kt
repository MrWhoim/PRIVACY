package com.privacy.app.ui.reports

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Charts for the reports. Every chart is drawn only from saved values: a day without a value gets no bar and no point
 * (never a zero), provisional values are drawn hollow, and estimates are drawn lighter and tagged.
 */

/** One bar per day (0..100). null = no data (dotted stub), final = solid, provisional = outline only. */
@Composable
fun DayBars(values: List<Float?>, final: List<Boolean>, labels: List<String>, modifier: Modifier = Modifier, height: Int = 120) {
    val ink = MaterialTheme.colorScheme.onSurface
    val k = com.privacy.app.ui.rememberFillIn(1f, 1000)
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(height.dp)) {
            val n = values.size.coerceAtLeast(1)
            val colW = size.width / n
            val barW = colW * 0.5f
            for (g in listOf(0f, 50f, 100f)) {
                val y = size.height - size.height * g / 100f
                drawLine(ink.copy(alpha = 0.10f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            values.forEachIndexed { i, v ->
                val x = i * colW + (colW - barW) / 2f
                if (v == null) {
                    drawLine(
                        ink.copy(alpha = 0.25f), Offset(x + barW / 2f, size.height), Offset(x + barW / 2f, size.height - 8f), strokeWidth = 3f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 4f))
                    )
                } else {
                    val h = size.height * (v.coerceIn(0f, 100f) / 100f) * k
                    if (final.getOrElse(i) { false }) drawRoundRect(ink.copy(alpha = 0.78f), Offset(x, size.height - h), Size(barW, max(h, 2f)), CornerRadius(3f))
                    else drawRoundRect(ink.copy(alpha = 0.55f), Offset(x, size.height - h), Size(barW, max(h, 2f)), CornerRadius(3f), style = Stroke(2f))
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEach { Text(it, Modifier.weight(1f), fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1, softWrap = false, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}


/** Two lines over the days (for example Efficiency vs Health). Gaps stay gaps – missing days are not joined or zero-filled. */
@Composable
fun TwoLineChart(a: List<Float?>, b: List<Float?>, labels: List<String>, modifier: Modifier = Modifier, height: Int = 130) {
    val ink = MaterialTheme.colorScheme.onSurface
    val k = com.privacy.app.ui.rememberFillIn(1f, 1100)
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(height.dp)) {
            val n = max(a.size, b.size).coerceAtLeast(1)
            val colW = size.width / n
            for (g in listOf(0f, 50f, 100f)) {
                val y = size.height - size.height * g / 100f
                drawLine(ink.copy(alpha = 0.10f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            fun series(v: List<Float?>, alpha: Float, dashed: Boolean) {
                var prev: Offset? = null
                v.forEachIndexed { i, x ->
                    if (x == null) { prev = null; return@forEachIndexed }
                    val p = Offset(i * colW + colW / 2f, size.height - size.height * x.coerceIn(0f, 100f) / 100f * k)
                    prev?.let { q ->
                        if (!dashed) {
                            // soft shadow fading from the line down to the bottom axis
                            val area = Path().apply { moveTo(q.x, size.height); lineTo(q.x, q.y); lineTo(p.x, p.y); lineTo(p.x, size.height); close() }
                            drawPath(area, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(ink.copy(alpha = 0.20f), Color.Transparent), startY = minOf(q.y, p.y), endY = size.height))
                        }
                        drawLine(ink.copy(alpha = alpha), q, p, strokeWidth = 3f, cap = StrokeCap.Round, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null)
                    }
                    if (dashed) drawRect(ink.copy(alpha = alpha), Offset(p.x - 5f, p.y - 5f), Size(10f, 10f))
                    else drawCircle(ink.copy(alpha = alpha), 5f, p)
                    prev = p
                }
            }
            series(a, 0.85f, false)
            series(b, 0.55f, true)
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEach { Text(it, Modifier.weight(1f), fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1, softWrap = false, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("● Efficiency", fontSize = 11.sp, color = ink.copy(alpha = 0.85f))
            Text("■ Health (dashed)", fontSize = 11.sp, color = ink.copy(alpha = 0.55f))
        }
    }
}

/** A labelled progress bar: actual against a target, with a tick at 100 %. Estimated values are lighter and tagged. */
@Composable
fun GoalBar(label: String, actual: Double?, target: Double?, unit: String, estimated: Boolean = false, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    val k = com.privacy.app.ui.rememberFillIn(1f, 1000)
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
            val a = actual?.let { fmtNum(it) } ?: "no data"
            Text(a + (target?.let { " / ${fmtNum(it)}" } ?: "") + " $unit" + if (estimated && actual != null) " (est.)" else "", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(Modifier.fillMaxWidth().height(10.dp).padding(top = 3.dp)) {
            drawRoundRect(ink.copy(alpha = 0.08f), Offset.Zero, size, CornerRadius(4f))
            if (actual != null && target != null && target > 0) {
                val f = min(1.2, actual / target).toFloat()
                val w = size.width / 1.2f * f * k
                drawRoundRect(ink.copy(alpha = if (estimated) 0.40f else 0.75f), Offset.Zero, Size(w, size.height), CornerRadius(4f))
                val tx = size.width / 1.2f
                drawLine(ink.copy(alpha = 0.7f), Offset(tx, 0f), Offset(tx, size.height), strokeWidth = 2f)
            }
        }
    }
}

/** Paired bars: planned weight vs credited weight for each day. */
@Composable
fun PlannedCredited(planned: List<Float?>, credited: List<Float?>, labels: List<String>, modifier: Modifier = Modifier, height: Int = 110) {
    val ink = MaterialTheme.colorScheme.onSurface
    val top = max(8f, (planned.filterNotNull() + credited.filterNotNull()).maxOrNull() ?: 8f)
    val k = com.privacy.app.ui.rememberFillIn(1f, 1000)
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(height.dp)) {
            val n = planned.size.coerceAtLeast(1)
            val colW = size.width / n
            val bw = colW * 0.26f
            planned.forEachIndexed { i, p ->
                val x0 = i * colW + colW * 0.2f
                if (p != null) {
                    val h = size.height * p / top * k
                    drawRoundRect(ink.copy(alpha = 0.55f), Offset(x0, size.height - h), Size(bw, max(h, 2f)), CornerRadius(2f), style = Stroke(2f))
                }
                val c = credited.getOrNull(i)
                if (c != null) {
                    val h = size.height * c / top * k
                    drawRoundRect(ink.copy(alpha = 0.78f), Offset(x0 + bw + 4f, size.height - h), Size(bw, max(h, 2f)), CornerRadius(2f))
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEach { Text(it, Modifier.weight(1f), fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1, softWrap = false, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("▢ planned weight", fontSize = 11.sp, color = ink.copy(alpha = 0.6f))
            Text("■ credited weight", fontSize = 11.sp, color = ink.copy(alpha = 0.85f))
        }
    }
}

/** Done / total as a bar, for the difficulty / priority / type breakdowns. */
@Composable
fun RatioRow(name: String, done: Int, doing: Int, total: Int, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    Column(modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(name, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("$done done" + (if (doing > 0) " · $doing started" else "") + " of $total", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(Modifier.fillMaxWidth().height(8.dp).padding(top = 3.dp)) {
            drawRoundRect(ink.copy(alpha = 0.08f), Offset.Zero, size, CornerRadius(3f))
            if (total > 0) {
                val dw = size.width * done / total
                val pw = size.width * doing / total
                drawRoundRect(ink.copy(alpha = 0.78f), Offset.Zero, Size(dw, size.height), CornerRadius(3f))
                if (pw > 0f) drawRoundRect(ink.copy(alpha = 0.35f), Offset(dw, 0f), Size(pw, size.height), CornerRadius(3f))
            }
        }
    }
}

/** A small coloured-by-ink chip. */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier, strong: Boolean = false) {
    val c = MaterialTheme.colorScheme
    Box(modifier.clip(RoundedCornerShape(50)).background(c.onSurface.copy(alpha = if (strong) 0.20f else 0.09f)).padding(horizontal = 9.dp, vertical = 3.dp)) {
        Text(text, fontSize = 11.sp)
    }
}

fun fmtNum(v: Double): String = if (v >= 100) String.format(Locale.US, "%.0f", v) else if (v == v.toInt().toDouble()) v.toInt().toString() else String.format(Locale.US, "%.1f", v)
fun pct(v: Float?): String = v?.let { String.format(Locale.US, "%.1f%%", it) } ?: "—"


/** A circular gauge. score == null draws only the empty track and a dash (no data – never a zero). */
@Composable
fun Ring(score: Float?, modifier: Modifier = Modifier, size: Int = 62) {
    val ink = MaterialTheme.colorScheme.onSurface
    val shown = com.privacy.app.ui.rememberFillIn(((score ?: 0f) / 100f).coerceIn(0f, 1f), 1100)
    Box(modifier.size(size.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(4.dp)) {
            val sw = 6.dp.toPx()
            drawArc(ink.copy(alpha = 0.12f), 0f, 360f, false, style = Stroke(sw))
            if (score != null) drawArc(ink.copy(alpha = 0.85f), -90f, 360f * shown, false, style = Stroke(sw, cap = StrokeCap.Round))
        }
        Text(if (score == null) "—" else "${(shown * 100f).roundToInt()}%", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

private val GLYPHS = mapOf(
    "food" to listOf("X.X.XX.", "X.X.XX.", "XXX.XX.", ".X..XX.", ".X..XX.", ".X..XX.", ".X..XX."),
    "run" to listOf("....XX.", "....XX.", "..XXX..", ".X.X...", "...XX..", "..X..X.", ".X...X."),
    "sleep" to listOf("..XXXX.", ".XXX...", "XXX....", "XXX....", "XXX....", ".XXX..X", "..XXXX."),
    "water" to listOf("...X...", "...X...", "..XXX..", "..XXX..", ".XXXXX.", ".XXXXX.", "..XXX.."),
    "heart" to listOf(".XX.XX.", "XXXXXXX", "XXXXXXX", "XXXXXXX", ".XXXXX.", "..XXX..", "...X..."),
    "focus" to listOf("..XXX..", ".X...X.", "X..X..X", "X.XXX.X", "X..X..X", ".X...X.", "..XXX.."),
    "phone" to listOf("..XXX..", ".X...X.", ".X...X.", ".X...X.", ".X...X.", ".XXXXX.", "..X.X.."),
    "book" to listOf("XXXXXXX", "X..X..X", "X..X..X", "X..X..X", "X..X..X", "XXXXXXX", "......."),
    "check" to listOf(".......", "......X", ".....XX", "X...XX.", "XX.XX..", ".XXX...", "..X...."),
    "grid" to listOf("XX.XX.X", "XX.XX.X", ".......", "XX.XX.X", "XX.XX.X", ".......", "XX.XX.X"),
    "chart" to listOf(".....X.", "..X..X.", "..X.XX.", "X.X.XX.", "XXXXXX.", "XXXXXX.", "XXXXXXX"),
    "weight" to listOf("..XXX..", ".X...X.", ".X...X.", "XXXXXXX", "X.....X", "X.....X", "XXXXXXX")
)

/** Tiny pixel-art icons in the app's own pixel style (food, run, sleep, water, heart, grid, weight). */
@Composable
fun PixelGlyph(kind: String, modifier: Modifier = Modifier, size: Int = 22, tint: Color = MaterialTheme.colorScheme.onSurface, animated: Boolean = false) {
    val g = GLYPHS[kind] ?: GLYPHS.getValue("grid")
    val on = animated && com.privacy.app.ui.Anim.enabled
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "glyph")
    val waveA by t.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1800, easing = androidx.compose.animation.core.LinearEasing)), label = "glyphWave")
    val wave = if (on) waveA else 0f
    Canvas(modifier.size(size.dp)) {
        val cell = this.size.width / 7f
        val gap = cell * 0.1f
        g.forEachIndexed { r, row -> row.forEachIndexed { c, ch ->
            if (ch == 'X') {
                // a gentle light travelling over the pixels, like the task pixels
                val a = if (on) 0.55f + 0.45f * ((kotlin.math.sin(((wave - (r + c) / 14f) * 2f * Math.PI).toFloat()) + 1f) / 2f) else 1f
                drawRect(tint.copy(alpha = a), Offset(c * cell + gap, r * cell + gap), Size(cell - gap * 2, cell - gap * 2))
            }
        } }
    }
}


class ChartPt(val x: Float, val y: Float)
class ChartSeries(val pts: List<ChartPt>, val alpha: Float = 0.85f, val dashed: Boolean = false)

/** A smooth curve through the points (no overshoot), with a dot at every real entry. Gaps are never filled in or joined with zeros. */
@Composable
fun CurveChart(series: List<ChartSeries>, xMin: Float, xMax: Float, xLabel: (Float) -> String, modifier: Modifier = Modifier, height: Int = 150) {
    val ink = MaterialTheme.colorScheme.onSurface
    val k = com.privacy.app.ui.rememberFillIn(1f, 1200)
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(height.dp)) {
            val span = (xMax - xMin).coerceAtLeast(0.001f)
            fun px(x: Float) = (x - xMin) / span * size.width
            fun py(y: Float) = size.height - size.height * y.coerceIn(0f, 100f) / 100f
            for (g in listOf(0f, 50f, 100f)) drawLine(ink.copy(alpha = 0.10f), Offset(0f, py(g)), Offset(size.width, py(g)), strokeWidth = 1f)
            clipRect(right = size.width * k) {
            series.forEachIndexed { si, s ->
                val p = s.pts.sortedBy { it.x }
                if (p.isEmpty()) return@forEachIndexed
                if (p.size > 1) {
                    val path = Path()
                    path.moveTo(px(p[0].x), py(p[0].y))
                    // monotone cubic (Fritsch–Carlson): never overshoots, and entries at the same minute become a straight step instead of a loop
                    val n = p.size
                    val d = FloatArray(n - 1) { i -> val dx = p[i + 1].x - p[i].x; if (dx > 1e-4f) (p[i + 1].y - p[i].y) / dx else 0f }
                    val m = FloatArray(n) { i ->
                        when {
                            i == 0 -> d[0]
                            i == n - 1 -> d[n - 2]
                            d[i - 1] * d[i] <= 0f -> 0f
                            else -> (d[i - 1] + d[i]) / 2f
                        }
                    }
                    for (i in 0 until n - 1) {
                        if (d[i] != 0f || true) {
                            val a1 = if (d[i] == 0f) 0f else m[i] / d[i]; val b1 = if (d[i] == 0f) 0f else m[i + 1] / d[i]
                            val h = kotlin.math.sqrt(a1 * a1 + b1 * b1)
                            if (h > 3f) { m[i] = 3f * a1 / h * d[i]; m[i + 1] = 3f * b1 / h * d[i] }
                        }
                    }
                    for (i in 0 until n - 1) {
                        val p1 = p[i]; val p2 = p[i + 1]; val dx = p2.x - p1.x
                        if (dx <= 1e-4f) { path.lineTo(px(p2.x), py(p2.y)); continue }
                        val c1y = (p1.y + m[i] * dx / 3f).coerceIn(min(p1.y, p2.y), max(p1.y, p2.y))
                        val c2y = (p2.y - m[i + 1] * dx / 3f).coerceIn(min(p1.y, p2.y), max(p1.y, p2.y))
                        path.cubicTo(px(p1.x + dx / 3f), py(c1y), px(p2.x - dx / 3f), py(c2y), px(p2.x), py(p2.y))
                    }
                    if (!s.dashed) {
                        val area = Path().apply { addPath(path); lineTo(px(p.last().x), size.height); lineTo(px(p[0].x), size.height); close() }
                        drawPath(area, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(ink.copy(alpha = 0.22f), Color.Transparent), startY = 0f, endY = size.height))
                    }
                    drawPath(path, ink.copy(alpha = s.alpha), style = Stroke(3.5f, cap = StrokeCap.Round, pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) else null))
                }
                p.forEach { pt ->
                    if (s.dashed) drawRect(ink.copy(alpha = s.alpha), Offset(px(pt.x) - 5f, py(pt.y) - 5f), Size(10f, 10f))
                    else drawCircle(ink.copy(alpha = s.alpha), 6f, Offset(px(pt.x), py(pt.y)))
                }
            }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0f, 1f / 3f, 2f / 3f, 1f).forEach { f -> Text(xLabel(xMin + (xMax - xMin) * f), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        }
    }
}

/** "08:30 Breakfast +12.4%" rows under an impact graph. */
@Composable
fun ImpactList(rows: List<Triple<String, String, Float>>, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        rows.forEach { (time, name, delta) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(time, Modifier.width(52.dp), fontSize = 12.sp, color = c.onSurfaceVariant)
                Text(name, Modifier.weight(1f), fontSize = 13.sp, maxLines = 1)
                Text(String.format(Locale.US, "%+.1f%%", delta), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

fun hourLabel(h: Float): String { val t = (h * 60).toInt().coerceIn(0, 24 * 60); return String.format(Locale.US, "%02d:%02d", t / 60, t % 60) }
