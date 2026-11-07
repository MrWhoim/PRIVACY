package com.privacy.app.ui.reports

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.privacy.app.data.ReportEngine
import com.privacy.app.ui.face.AvatarState
import com.privacy.app.ui.face.PixelAvatar
import com.privacy.app.ui.tasks.PixelProgressBar
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * "Share as picture": a preview of the report card (scores, graphs, details and the assistant's face with its analysis).
 * The card is drawn once into a graphics layer and saved as a PNG for the share sheet.
 */
@Composable
fun ReportShareDialog(
    title: String, day: ReportEngine.CombinedDay?, stats: ReportEngine.WeekStats?, takeaways: List<String>, analysis: String?, call: String, onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = MaterialTheme.colorScheme
    val female = com.privacy.app.ui.face.LocalAssistantFemale.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(c.background.copy(alpha = 0.98f)).statusBarsPadding().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 20.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.widthIn(max = 380.dp).fillMaxWidth()) { ReportCard(title, day, stats, takeaways, analysis, call) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) { Text("Close") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    scope.launch {
                        runCatching {
                            // drawn with plain Canvas calls (no screen capture), so it cannot fail on animated content
                            val bmp: Bitmap = withContext(Dispatchers.Default) { ReportImage.render(title, day, stats, takeaways, analysis, call, female) }
                            val f = File(ctx.cacheDir, "shared").apply { mkdirs() }.let { File(it, "report-${System.currentTimeMillis()}.png") }
                            withContext(Dispatchers.IO) { f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                            val i = Intent(Intent.ACTION_SEND).apply { type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                            ctx.startActivity(Intent.createChooser(i, "Share report").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.onFailure { android.widget.Toast.makeText(ctx, "Couldn't create the picture: " + (it.message ?: it.javaClass.simpleName), android.widget.Toast.LENGTH_LONG).show() }
                    }
                }) { Text("Share picture") }
            }
        }
    }
}

@Composable
private fun ReportCard(title: String, day: ReportEngine.CombinedDay?, stats: ReportEngine.WeekStats?, takeaways: List<String>, analysis: String?, call: String) {
    val c = MaterialTheme.colorScheme
    fun p(v: Float?) = if (v == null) "—" else String.format(Locale.US, "%.0f%%", v)
    val eff = day?.eff?.shownScore ?: stats?.effMean
    val health = day?.health?.score ?: stats?.healthMean
    val combined = day?.combined ?: stats?.combinedMean
    val focusV = day?.focus ?: stats?.focusMean
    Column(Modifier.clip(RoundedCornerShape(22.dp)).background(c.surface).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("P.R.I.V.A.C.Y.", fontSize = 12.sp, letterSpacing = 3.sp, color = c.onSurfaceVariant)
        Text(title, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        // the assistant's face with its analysis
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            PixelAvatar(88.dp, Modifier, state = AvatarState.IDLE, assemble = false, autoGestures = false, fps = 12)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(c.surfaceVariant).padding(12.dp)) {
                Text(
                    (analysis?.replace(Regex("[*#`]"), "")?.trim()?.take(420) ?: takeaways.take(3).joinToString(" ")).ifBlank { "Here is how it went, $call." },
                    fontSize = 13.sp, lineHeight = 18.sp
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Efficiency" to eff, "Health" to health, "Focus" to focusV, "Combined" to combined).forEach { (l, v) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(c.surfaceVariant.copy(alpha = 0.6f)).padding(10.dp)) {
                    Text(p(v), fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text(l, fontSize = 11.sp, color = c.onSurfaceVariant)
                }
            }
        }
        PixelProgressBar(((combined ?: eff ?: health ?: 0f)) / 100f, cols = 36, rows = 2, alive = false)
        if (day != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                day.health.cats.forEach { cat ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Ring(cat.score)
                        Text(cat.label, fontSize = 10.sp, color = c.onSurfaceVariant)
                    }
                }
            }
            Text("Scores", fontSize = 12.sp, color = c.onSurfaceVariant)
            ScoreBars(listOf("Efficiency" to eff, "Health" to health, "Focus" to focusV))
            day.focusRes?.takeIf { it.enough }?.let { fr ->
                Text(String.format(Locale.US, "Dopamine vs Detox · Detox %.0f%% / Dopamine %.0f%%", fr.focus, fr.dist), fontSize = 12.sp, color = c.onSurfaceVariant)
                DopamineMini(fr)
            }
            takeaways.take(4).forEach { Text("• $it", fontSize = 12.sp, lineHeight = 17.sp) }
        } else if (stats != null) {
            val labels = stats.days.map { LabelOf(it.day) }
            Text("Efficiency per day", fontSize = 12.sp, color = c.onSurfaceVariant)
            DayBars(stats.days.map { it.eff.shownScore }, stats.days.map { it.eff.isFinal }, labels)
            Text("Health per day", fontSize = 12.sp, color = c.onSurfaceVariant)
            DayBars(stats.days.map { it.health.score }, stats.days.map { it.health.isFinal }, labels)
            Text("Efficiency vs Health", fontSize = 12.sp, color = c.onSurfaceVariant)
            TwoLineChart(stats.days.map { it.eff.shownScore }, stats.days.map { it.health.score }, labels)
            Text("Days with data: Efficiency ${stats.effDays}, Health ${stats.healthDays}", fontSize = 11.sp, color = c.onSurfaceVariant)
            takeaways.take(3).forEach { Text("• $it", fontSize = 12.sp, lineHeight = 17.sp) }
        }
    }
}

@Composable
private fun ScoreBars(items: List<Pair<String, Float?>>) {
    val c = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (l, v) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(l, Modifier.width(84.dp), fontSize = 11.sp, color = c.onSurfaceVariant)
                Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(c.onSurface.copy(alpha = 0.12f))) {
                    Box(Modifier.fillMaxWidth(((v ?: 0f) / 100f).coerceIn(0f, 1f)).fillMaxHeight().background(c.onSurface))
                }
                Text(if (v == null) "—" else String.format(Locale.US, "%.0f%%", v), Modifier.width(48.dp), fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }
}

@Composable
private fun DopamineMini(res: com.privacy.app.data.FocusRepo.Result) {
    val ink = MaterialTheme.colorScheme.onSurface
    val nowH = if (res.day == java.time.LocalDate.now().toEpochDay()) (java.time.LocalTime.now().hour + 1).coerceAtMost(24) else 24
    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
        val w = size.width; val h = size.height
        drawLine(ink.copy(alpha = 0.12f), androidx.compose.ui.geometry.Offset(0f, h / 2f), androidx.compose.ui.geometry.Offset(w, h / 2f), 1f)
        fun line(f: (Int) -> Float, a: Float, dashed: Boolean) {
            val path = androidx.compose.ui.graphics.Path()
            for (i in 0..nowH) { val x = w * i / 24f; val y = h - h * f(i).coerceIn(0f, 100f) / 100f; if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
            drawPath(path, ink.copy(alpha = a), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f, pathEffect = if (dashed) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null))
        }
        line({ res.focusAt(it) }, 0.9f, false)
        line({ res.distAt(it) }, 0.5f, true)
    }
}

private fun LabelOf(day: Long): String = java.time.LocalDate.ofEpochDay(day).dayOfMonth.toString()

/** The report picture, drawn directly with Canvas calls: the assistant's pixel face, scores, rings, bars and the line graph. */
object ReportImage {
    private const val BG = 0xFF161616.toInt()
    private const val CARD = 0xFF232323.toInt()
    private const val FG = 0xFFEDEDED.toInt()
    private const val DIM = 0xFF9A9A9A.toInt()
    private const val TRACK = 0xFF3A3A3A.toInt()

    private fun paint(size: Float, color: Int, bold: Boolean = false) = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; textSize = size; typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
    }
    private fun layout(text: String, p: android.text.TextPaint, width: Int) =
        android.text.StaticLayout.Builder.obtain(text, 0, text.length, p, width).setLineSpacing(0f, 1.15f).build()

    /** The assistant drawn with square pixels, exactly like on screen (one still frame). */
    private fun drawAvatar(cv: android.graphics.Canvas, female: Boolean, left: Float, top: Float, boxW: Float, boxH: Float) {
        val anim = com.privacy.app.ui.face.FaceAnimator()
        repeat(30) { anim.update(0.04f) }
        val sc = com.privacy.app.ui.face.PixelFace.Scratch()
        com.privacy.app.ui.face.PixelFace.render(sc, anim.p, female, anim.shownGesture, anim.gestureAmount, anim.time)
        val W = com.privacy.app.ui.face.PixelFace.W; val H = com.privacy.app.ui.face.PixelFace.H
        val cell = minOf(boxW / W, boxH / H)
        val ox = left + (boxW - cell * W) / 2f; val oy = top + (boxH - cell * H) / 2f
        val r = cell * 0.48f
        val on = android.graphics.Paint().apply { color = FG }
        val dimP = android.graphics.Paint().apply { color = FG; alpha = 51 }
        val scan = android.graphics.Paint().apply { color = android.graphics.Color.argb(56, 0, 0, 0) }
        for (iy in 0 until H) for (ix in 0 until W) {
            val cx = ox + (ix + 0.5f) * cell; val cy = oy + (iy + 0.5f) * cell
            if (sc.out[iy * W + ix]) {
                cv.drawRect(cx - r, cy - r, cx + r, cy + r, on)
                if (cell >= 4f) cv.drawRect(cx - r, cy - cell * 0.045f, cx + r, cy + cell * 0.045f, scan)
            } else if (sc.dim[iy * W + ix]) cv.drawRect(cx - r * 0.85f, cy - r * 0.85f, cx + r * 0.85f, cy + r * 0.85f, dimP)
        }
    }

    fun render(
        title: String, day: ReportEngine.CombinedDay?, stats: ReportEngine.WeekStats?, takeaways: List<String>, analysis: String?, call: String,
        female: Boolean = false
    ): Bitmap {
        val w = 1080; val pad = 56; val inner = w - pad * 2
        fun pct(v: Float?) = if (v == null) "—" else String.format(Locale.US, "%.0f%%", v)
        val eff = day?.eff?.shownScore ?: stats?.effMean
        val health = day?.health?.score ?: stats?.healthMean
        val focus = day?.focus ?: stats?.focusMean
        val comb = day?.combined ?: stats?.combinedMean
        val days = stats?.days.orEmpty()
        val sections = ArrayList<Pair<Float, (android.graphics.Canvas, Float) -> Unit>>()
        fun add(h: Float, d: (android.graphics.Canvas, Float) -> Unit) { sections += h to d }
        val cardP = android.graphics.Paint().apply { color = CARD }

        add(40f) { cv, y -> cv.drawText("P.R.I.V.A.C.Y.", pad.toFloat(), y + 30f, paint(28f, DIM).apply { letterSpacing = 0.25f }) }
        add(80f) { cv, y -> cv.drawText(title, pad.toFloat(), y + 52f, paint(52f, FG, true)) }

        // the assistant and what it says
        val say = (analysis?.replace(Regex("[*#`]"), "")?.trim()?.take(520) ?: takeaways.take(3).joinToString(" ")).ifBlank { "Here is how it went, $call." }
        val avW = 250f; val avH = avW * 52f / 40f
        val sayL = layout(say, paint(32f, FG), (inner - avW - 24 - 48).toInt())
        val topH = maxOf(avH, sayL.height + 48f)
        add(topH + 24f) { cv, y ->
            drawAvatar(cv, female, pad.toFloat(), y, avW, avH)
            val bx = pad + avW + 24f
            cv.drawRoundRect(bx, y, (w - pad).toFloat(), y + sayL.height + 48f, 28f, 28f, cardP)
            cv.save(); cv.translate(bx + 24f, y + 24f); sayL.draw(cv); cv.restore()
        }

        // score tiles: Efficiency · Health · Focus · Combined
        add(170f + 24f) { cv, y ->
            val tiles = listOf("Efficiency" to eff, "Health" to health, "Focus" to focus, "Combined" to comb)
            val gap = 16f; val bw = (inner - gap * 3) / 4
            tiles.forEachIndexed { i, (l, v) ->
                val x = pad + i * (bw + gap)
                cv.drawRoundRect(x, y, x + bw, y + 170f, 24f, 24f, cardP)
                cv.drawText(pct(v), x + 20f, y + 90f, paint(46f, FG, true))
                cv.drawText(l, x + 20f, y + 138f, paint(26f, DIM))
            }
        }

        // the pixel bar of the day / period
        add(80f) { cv, y ->
            val cols = 36; val rows = 2; val cell = inner.toFloat() / cols
            val frac = ((comb ?: eff ?: health ?: 0f) / 100f).coerceIn(0f, 1f)
            val filled = frac * cols * rows
            val on = android.graphics.Paint().apply { color = FG }; val off = android.graphics.Paint().apply { color = TRACK }
            for (c in 0 until cols) for (r in 0 until rows) {
                val idx = c * rows + r
                val l = pad + c * cell + cell * 0.12f; val t = y + (rows - 1 - r) * cell + cell * 0.12f
                cv.drawRect(l, t, l + cell * 0.76f, t + cell * 0.76f, if (idx < filled) on else off)
            }
        }

        if (day != null) {
            // the health rings
            add(190f) { cv, y ->
                val cats = day.health.cats
                val slot = inner.toFloat() / cats.size.coerceAtLeast(1)
                cats.forEachIndexed { i, cat ->
                    val cx = pad + slot * i + slot / 2f; val d = 120f; val stroke = 16f
                    val rect = android.graphics.RectF(cx - d / 2, y + stroke, cx + d / 2, y + stroke + d)
                    val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = stroke; strokeCap = android.graphics.Paint.Cap.ROUND; color = TRACK }
                    cv.drawArc(rect, 0f, 360f, false, ring)
                    cat.score?.let { sc -> ring.color = FG; cv.drawArc(rect, -90f, 360f * (sc / 100f).coerceIn(0f, 1f), false, ring) }
                    val tp = paint(30f, FG, true).apply { textAlign = android.graphics.Paint.Align.CENTER }
                    cv.drawText(cat.score?.let { String.format(Locale.US, "%.0f%%", it) } ?: "–", cx, y + stroke + d / 2 + 11f, tp)
                    cv.drawText(cat.label, cx, y + stroke + d + 38f, paint(24f, DIM).apply { textAlign = android.graphics.Paint.Align.CENTER })
                }
            }
            // horizontal score bars
            add(190f) { cv, y ->
                cv.drawText("Scores", pad.toFloat(), y + 30f, paint(28f, DIM))
                listOf("Efficiency" to eff, "Health" to health, "Focus" to focus).forEachIndexed { i, (l, v) ->
                    val ry = y + 56f + i * 44f
                    cv.drawText(l, pad.toFloat(), ry + 24f, paint(26f, DIM))
                    val bl = pad + 190f; val br = (w - pad - 110).toFloat()
                    cv.drawRoundRect(bl, ry + 6f, br, ry + 26f, 10f, 10f, android.graphics.Paint().apply { color = TRACK })
                    if (v != null) cv.drawRoundRect(bl, ry + 6f, bl + (br - bl) * (v / 100f).coerceIn(0f, 1f), ry + 26f, 10f, 10f, android.graphics.Paint().apply { color = FG })
                    cv.drawText(pct(v), (w - pad).toFloat(), ry + 26f, paint(26f, FG, true).apply { textAlign = android.graphics.Paint.Align.RIGHT })
                }
            }
            day.focusRes?.takeIf { it.enough }?.let { fr ->
                add(300f) { cv, y ->
                    cv.drawText(String.format(Locale.US, "Dopamine vs Detox · Detox %.0f%% / Dopamine %.0f%%", fr.focus, fr.dist), pad.toFloat(), y + 30f, paint(28f, DIM))
                    val top = y + 56f; val h = 190f
                    val nowH = if (fr.day == java.time.LocalDate.now().toEpochDay()) (java.time.LocalTime.now().hour + 1).coerceAtMost(24) else 24
                    val grid = android.graphics.Paint().apply { color = 0x22FFFFFF; strokeWidth = 2f }
                    listOf(0f, 50f, 100f).forEach { g -> cv.drawLine(pad.toFloat(), top + h - h * g / 100f, (w - pad).toFloat(), top + h - h * g / 100f, grid) }
                    fun line(f: (Int) -> Float, alpha: Int, dashed: Boolean) {
                        val path = android.graphics.Path()
                        for (i in 0..nowH) { val x = pad + inner * i / 24f; val yy = top + h - h * f(i).coerceIn(0f, 100f) / 100f; if (i == 0) path.moveTo(x, yy) else path.lineTo(x, yy) }
                        cv.drawPath(path, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            style = android.graphics.Paint.Style.STROKE; strokeWidth = 6f; color = FG; this.alpha = alpha; strokeCap = android.graphics.Paint.Cap.ROUND
                            if (dashed) pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f, 14f), 0f)
                        })
                    }
                    line({ fr.focusAt(it) }, 240, false)
                    line({ fr.distAt(it) }, 140, true)
                    listOf(0, 6, 12, 18, 24).forEach { hh -> cv.drawText(hh.toString(), pad + inner * hh / 24f, top + h + 34f, paint(22f, DIM).apply { textAlign = android.graphics.Paint.Align.CENTER }) }
                }
            }
        } else if (days.isNotEmpty()) {
            fun bars(label: String, vals: List<Float?>) {
                add(250f) { cv, y ->
                    cv.drawText(label, pad.toFloat(), y + 30f, paint(28f, DIM))
                    val areaTop = y + 50f; val areaH = 170f
                    val n = vals.size.coerceAtLeast(1); val slot = inner.toFloat() / n
                    val bp = android.graphics.Paint().apply { color = FG }; val gp = android.graphics.Paint().apply { color = TRACK }
                    vals.forEachIndexed { i, v ->
                        val bx = pad + i * slot + slot * 0.15f; val bx2 = pad + (i + 1) * slot - slot * 0.15f
                        cv.drawRect(bx, areaTop, bx2, areaTop + areaH, gp)
                        if (v != null) cv.drawRect(bx, areaTop + areaH * (1f - (v / 100f).coerceIn(0f, 1f)), bx2, areaTop + areaH, bp)
                    }
                }
            }
            bars("Efficiency per day", days.map { it.eff.shownScore })
            bars("Health per day", days.map { it.health.score })
            if (days.any { it.focus != null }) bars("Focus per day", days.map { it.focus })
            // the line graph: Efficiency (solid) against Health (dashed)
            add(330f) { cv, y ->
                cv.drawText("Efficiency vs Health", pad.toFloat(), y + 30f, paint(28f, DIM))
                val top = y + 56f; val h = 200f; val n = days.size.coerceAtLeast(1); val slot = inner.toFloat() / n
                val grid = android.graphics.Paint().apply { color = 0x22FFFFFF; strokeWidth = 2f }
                listOf(0f, 50f, 100f).forEach { g -> cv.drawLine(pad.toFloat(), top + h - h * g / 100f, (w - pad).toFloat(), top + h - h * g / 100f, grid) }
                fun series(vals: List<Float?>, alpha: Int, dashed: Boolean) {
                    val lp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = FG; this.alpha = alpha; strokeWidth = 6f; strokeCap = android.graphics.Paint.Cap.ROUND; style = android.graphics.Paint.Style.STROKE
                        if (dashed) pathEffect = android.graphics.DashPathEffect(floatArrayOf(18f, 14f), 0f)
                    }
                    val dot = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = FG; this.alpha = alpha }
                    var prev: android.graphics.PointF? = null
                    vals.forEachIndexed { i, v ->
                        if (v == null) { prev = null; return@forEachIndexed }
                        val pt = android.graphics.PointF(pad + i * slot + slot / 2f, top + h - h * v.coerceIn(0f, 100f) / 100f)
                        prev?.let { q -> cv.drawLine(q.x, q.y, pt.x, pt.y, lp) }
                        if (dashed) cv.drawRect(pt.x - 8f, pt.y - 8f, pt.x + 8f, pt.y + 8f, dot) else cv.drawCircle(pt.x, pt.y, 8f, dot)
                        prev = pt
                    }
                }
                series(days.map { it.eff.shownScore }, 235, false)
                series(days.map { it.health.score }, 150, true)
                days.forEachIndexed { i, d -> cv.drawText(LabelOf(d.day), pad + i * slot + slot / 2f, top + h + 34f, paint(22f, DIM).apply { textAlign = android.graphics.Paint.Align.CENTER }) }
                cv.drawText("● Efficiency    ■ Health (dashed)", pad.toFloat(), top + h + 76f, paint(24f, DIM))
            }
            add(40f) { cv, y -> cv.drawText("Days with data: Efficiency ${stats!!.effDays}, Health ${stats.healthDays}" + (if (stats.focusDays > 0) ", Focus ${stats.focusDays}" else ""), pad.toFloat(), y + 26f, paint(24f, DIM)) }
        }
        takeaways.take(5).forEach { t ->
            val l = layout("• $t", paint(30f, FG), inner)
            add(l.height + 14f) { cv, y -> cv.save(); cv.translate(pad.toFloat(), y); l.draw(cv); cv.restore() }
        }

        val total = (pad * 2 + sections.sumOf { it.first.toDouble() }).toInt().coerceAtLeast(900)
        val bmp = Bitmap.createBitmap(w, total, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        cv.drawColor(BG)
        var y = pad.toFloat()
        sections.forEach { (h, draw) -> draw(cv, y); y += h }
        return bmp
    }
}
