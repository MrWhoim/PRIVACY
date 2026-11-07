package com.privacy.app.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import com.privacy.app.R
import com.privacy.app.data.AppDatabase
import com.privacy.app.data.FocusRepo
import com.privacy.app.PrivacyApp
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/** Dopamine vs Detox of today: the two percentages sit at the left and right edge, the graph of the day runs between them. Tap opens the Focus tab. */
class FocusWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val res = runCatching { (context.applicationContext as PrivacyApp).graph.focus.evaluate(LocalDate.now().toEpochDay()) }.getOrNull()
        provideContent { FocusContent(context, res) }
    }
}

class FocusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FocusWidget()
}

@Composable
private fun FocusContent(context: Context, res: FocusRepo.Result?) {
    val open = actionStartActivity(Intent(context, com.privacy.app.MainActivity::class.java).putExtra("open_screen", "focus").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    val size = LocalSize.current
    val dens = context.resources.displayMetrics.density
    val bmp = FocusPanel.panel(((size.width.value - 6f) * dens).toInt().coerceAtMost(900), ((size.height.value - 6f) * dens).toInt().coerceAtMost(900), res)
    Box(GlanceModifier.fillMaxSize().background(ImageProvider(R.drawable.widget_bg)).padding(3.dp).clickable(open)) {
        Image(ImageProvider(bmp), "Dopamine versus detox of the day", GlanceModifier.fillMaxSize())
    }
}

object FocusPanel {
    fun panel(wPx: Int, hPx: Int, res: FocusRepo.Result?): Bitmap {
        val w = wPx.coerceAtLeast(200); val h = hPx.coerceAtLeast(120)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val fw = w.toFloat(); val fh = h.toFloat()
        val bold = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        val reg = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        val big = minOf(fh * 0.26f, fw * 0.17f)
        val small = big * 0.38f
        val pad = fw * 0.035f
        val pBig = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = big; typeface = bold }
        val pSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 255, 255, 255); textSize = small; typeface = reg }
        val enough = res != null && res.enough
        val detox = if (enough) String.format(Locale.US, "%.0f%%", res!!.focus) else "—"
        val dop = if (enough) String.format(Locale.US, "%.0f%%", res!!.dist) else "—"
        val top = big * 0.95f + pad * 0.3f
        // left edge: detox, right edge: dopamine
        c.drawText(detox, pad, top, pBig)
        c.drawText("Detox", pad, top + small * 1.2f, pSmall)
        pBig.textAlign = Paint.Align.RIGHT; pSmall.textAlign = Paint.Align.RIGHT
        c.drawText(dop, fw - pad, top, pBig)
        c.drawText("Dopamine", fw - pad, top + small * 1.2f, pSmall)
        if (!enough) {
            pSmall.textAlign = Paint.Align.CENTER
            c.drawText("Not enough data yet", fw / 2f, fh * 0.72f, pSmall)
            return b
        }
        // the graph of the day between the two numbers
        val gl = pad; val gr = fw - pad
        val gt = top + small * 1.2f + fh * 0.07f; val gb = fh - pad - small * 1.3f
        val gh = (gb - gt).coerceAtLeast(10f)
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 255, 255, 255); strokeWidth = 1.5f }
        c.drawLine(gl, gt + gh / 2f, gr, gt + gh / 2f, grid)
        val nowH = (LocalTime.now().hour + 1).coerceAtMost(24)
        fun xOf(i: Int) = gl + (gr - gl) * i / 24f
        fun yOf(v: Float) = gb - gh * (v / 100f).coerceIn(0f, 1f)
        fun series(f: (Int) -> Float, color: Int, dashed: Boolean, fill: Boolean) {
            val path = Path()
            for (i in 0..nowH) { val x = xOf(i); val y = yOf(f(i)); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
            if (fill) {
                val area = Path(path); area.lineTo(xOf(nowH), gb); area.lineTo(xOf(0), gb); area.close()
                c.drawPath(area, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = Color.argb(45, 255, 255, 255) })
            }
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = fh * 0.028f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; this.color = color
                if (dashed) pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 10f), 0f)
            })
        }
        series({ res!!.focusAt(it) }, Color.WHITE, dashed = false, fill = true)
        series({ res!!.distAt(it) }, Color.argb(170, 255, 255, 255), dashed = true, fill = false)
        val ax = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 255, 255, 255); textSize = small * 0.85f; typeface = reg; textAlign = Paint.Align.CENTER }
        listOf(0, 6, 12, 18, 24).forEach { c.drawText(it.toString(), xOf(it).coerceIn(gl + 6f, gr - 6f), fh - pad * 0.6f, ax) }
        return b
    }
}
