package com.privacy.app.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/** Draws one round progress ring with an emoji icon in the middle (Glance widgets cannot draw, so the ring is a bitmap). */
object HealthRings {
    fun icon(key: String) = when (key) { "N" -> "🍎"; "A" -> "🏃"; "S" -> "🌙"; else -> "💧" }

    fun ring(key: String, score: Float?, logged: Boolean, px: Int = 132): Bitmap {
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val stroke = px * 0.11f
        val r = RectF(stroke, stroke, px - stroke, px - stroke)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND }
        p.color = Color.argb(60, 255, 255, 255); c.drawArc(r, 0f, 360f, false, p)
        val frac = ((score ?: 0f) / 100f).coerceIn(0f, 1f)
        if (logged && frac > 0f) { p.color = Color.WHITE; c.drawArc(r, -90f, 360f * frac, false, p) }
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = px * 0.36f; textAlign = Paint.Align.CENTER; alpha = if (logged) 255 else 120 }
        c.drawText(icon(key), px / 2f, px / 2f - (t.descent() + t.ascent()) / 2f, t)
        return b
    }

    /** The whole Health widget as one picture: "48% Health of day" and four rings (percentage inside, name below) in a 2×2 grid. */
    fun panel(wPx: Int, hPx: Int, overall: Float?, cats: List<Triple<String, Float?, Boolean>>): Bitmap {
        val w = wPx.coerceAtLeast(160); val h = hPx.coerceAtLeast(160)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val bold = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        val reg = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        val fw = w.toFloat(); val fh = h.toFloat()
        val headSize = minOf(fh * 0.115f, fw * 0.105f)
        val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = headSize; typeface = bold }
        val pctText = if (overall == null) "—" else String.format(java.util.Locale.US, "%.0f%%", overall)
        val headY = headSize * 0.95f
        c.drawText(pctText, fw * 0.035f, headY, head)
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255); textSize = headSize * 0.62f; typeface = reg }
        c.drawText("Health of day", fw * 0.035f + head.measureText(pctText) + fw * 0.03f, headY, sub)
        val top = headSize * 1.45f
        val cellW = fw / 2f; val cellH = (fh - top) / 2f
        val labelSize = minOf(cellH * 0.15f, cellW * 0.14f)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 255, 255, 255); textSize = labelSize; typeface = reg; textAlign = Paint.Align.CENTER }
        val names = mapOf("N" to "Nutrition", "A" to "Activity", "S" to "Sleep", "W" to "Water")
        cats.take(4).forEachIndexed { i, (key, score, logged) ->
            val cx = cellW * (i % 2) + cellW / 2f
            val cellTop = top + cellH * (i / 2)
            val d = minOf(cellW * 0.74f, cellH - labelSize * 1.6f).coerceAtLeast(20f)
            val stroke = d * 0.12f
            val r = RectF(cx - d / 2 + stroke / 2, cellTop + stroke / 2, cx + d / 2 - stroke / 2, cellTop + d - stroke / 2)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND }
            p.color = Color.argb(70, 255, 255, 255); c.drawArc(r, 0f, 360f, false, p)
            val frac = ((score ?: 0f) / 100f).coerceIn(0f, 1f)
            if (logged && frac > 0f) { p.color = Color.rgb(215, 215, 215); c.drawArc(r, -90f, 360f * frac, false, p) }
            val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = bold; textSize = d * 0.26f; textAlign = Paint.Align.CENTER }
            val txt = if (logged && score != null) String.format(java.util.Locale.US, "%.0f%%", score) else "–"
            c.drawText(txt, cx, cellTop + d / 2f - (t.descent() + t.ascent()) / 2f, t)
            c.drawText(names[key] ?: key, cx, cellTop + d + labelSize * 1.15f, label)
        }
        return b
    }
}
