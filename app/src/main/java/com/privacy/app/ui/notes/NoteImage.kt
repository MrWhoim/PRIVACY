package com.privacy.app.ui.notes

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.provider.MediaStore
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import android.text.style.StrikethroughSpan
import androidx.core.content.FileProvider
import java.io.File

/** Draws a note onto a picture (dark, clean, with the app's name in the corner) so it can be shared anywhere or saved to the gallery. */
object NoteImage {
    private const val W = 1080
    private const val PAD = 84

    private fun styled(line: String): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        val re = Regex("\\*\\*(.+?)\\*\\*|\\*(\\S(?:.*?\\S)?)\\*|==(.+?)==|~~(.+?)~~|`([^`]+)`")
        var last = 0
        for (m in re.findAll(line)) {
            sb.append(line.substring(last, m.range.first))
            val start = sb.length
            val txt = m.groupValues.drop(1).first { it.isNotEmpty() }
            sb.append(txt)
            val span: Any = when {
                m.groups[1] != null -> StyleSpan(Typeface.BOLD)
                m.groups[2] != null -> StyleSpan(Typeface.ITALIC)
                m.groups[3] != null -> BackgroundColorSpan(0x66FFD54F)
                m.groups[4] != null -> StrikethroughSpan()
                else -> BackgroundColorSpan(0x33FFFFFF)
            }
            sb.setSpan(span, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            last = m.range.last + 1
        }
        sb.append(line.substring(last))
        return sb
    }

    fun render(ctx: Context, title: String, body: String): Bitmap {
        val textW = W - PAD * 2
        fun paint(sizePx: Float, bold: Boolean = false, alpha: Int = 255) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(alpha, 236, 236, 236); textSize = sizePx
            typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
        }
        class Block(val layout: StaticLayout?, val bmp: Bitmap?, val gap: Int, val indent: Int = 0)
        val blocks = ArrayList<Block>()
        fun layoutOf(t: CharSequence, p: TextPaint, width: Int) =
            StaticLayout.Builder.obtain(t, 0, t.length, p, width).setLineSpacing(0f, 1.18f).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()

        blocks += Block(layoutOf(title.ifBlank { "Untitled" }, paint(64f, true), textW), null, 36)
        var inCode = false
        for (raw in body.trim().lines()) {
            val t = raw.trim()
            if (t.startsWith("```")) { inCode = !inCode; continue }
            when {
                t.isEmpty() -> blocks += Block(null, null, 18)
                t.startsWith("![img](") && t.endsWith(")") -> {
                    val f = File(File(ctx.filesDir, "note_images"), t.removePrefix("![img](").removeSuffix(")"))
                    val b = runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull()
                    if (b != null) blocks += Block(null, b, 24)
                }
                t == "---" || t == "***" -> blocks += Block(null, null, 40)
                t.startsWith("#") -> {
                    val lvl = t.takeWhile { it == '#' }.length
                    blocks += Block(layoutOf(styled(t.trimStart('#').trim()), paint(if (lvl == 1) 54f else if (lvl == 2) 46f else 40f, true), textW), null, 20)
                }
                t.startsWith("- [ ] ") -> blocks += Block(layoutOf(styled("☐  " + t.drop(6)), paint(38f), textW), null, 12)
                t.startsWith("- [x] ") || t.startsWith("- [X] ") -> blocks += Block(layoutOf(styled("☑  " + t.drop(6)), paint(38f, alpha = 170), textW), null, 12)
                t.startsWith("- ") || t.startsWith("* ") -> blocks += Block(layoutOf(styled("•  " + t.drop(2)), paint(38f), textW), null, 12)
                t.startsWith("> ") -> blocks += Block(layoutOf(styled(t.drop(2)), paint(38f, alpha = 190), textW - 40), null, 14, 40)
                inCode -> blocks += Block(layoutOf(raw, paint(32f).apply { typeface = Typeface.MONOSPACE }, textW), null, 6)
                else -> blocks += Block(layoutOf(styled(t), paint(38f), textW), null, 14)
            }
        }
        var h = PAD
        for (b in blocks) {
            h += when {
                b.layout != null -> b.layout.height
                b.bmp != null -> (b.bmp.height * (textW.toFloat() / b.bmp.width)).toInt()
                else -> 0
            } + b.gap
        }
        h += PAD + 70
        val out = Bitmap.createBitmap(W, h.coerceAtLeast(640), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(27, 27, 27))
        var y = PAD.toFloat()
        for (b in blocks) {
            when {
                b.layout != null -> {
                    c.save(); c.translate((PAD + b.indent).toFloat(), y); b.layout.draw(c); c.restore()
                    if (b.indent > 0) c.drawRect(PAD.toFloat(), y, PAD + 8f, y + b.layout.height, Paint().apply { color = Color.rgb(120, 120, 120) })
                    y += b.layout.height
                }
                b.bmp != null -> {
                    val hh = b.bmp.height * (textW.toFloat() / b.bmp.width)
                    c.drawBitmap(b.bmp, null, android.graphics.RectF(PAD.toFloat(), y, (PAD + textW).toFloat(), y + hh), Paint(Paint.FILTER_BITMAP_FLAG))
                    y += hh
                }
            }
            y += b.gap
        }
        val mark = paint(26f, alpha = 110)
        c.drawText("P.R.I.V.A.C.Y.", PAD.toFloat(), out.height - PAD / 2f, mark)
        return out
    }

    private fun png(ctx: Context, bmp: Bitmap, name: String): File {
        val f = File(File(ctx.cacheDir, "share").apply { mkdirs() }, name)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    fun share(ctx: Context, title: String, body: String) {
        val file = png(ctx, render(ctx, title, body), "note-${System.currentTimeMillis()}.png")
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "Share note").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Saves into Pictures/PRIVACY – returns true on success. */
    fun saveToGallery(ctx: Context, title: String, body: String): Boolean = runCatching {
        val bmp = render(ctx, title, body)
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "note-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PRIVACY")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)!!
        ctx.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    }.getOrDefault(false)

    /** Several notes as several pictures in one share sheet. */
    fun shareMany(ctx: Context, items: List<Pair<String, String>>) {
        val uris = ArrayList<android.net.Uri>()
        items.forEachIndexed { i, (t, b) ->
            val f = png(ctx, render(ctx, t, b), "note-${System.currentTimeMillis()}-$i.png")
            uris += FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        }
        val i = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/png"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "Share notes").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Saves every note as its own picture. Returns how many were saved. */
    fun saveManyToGallery(ctx: Context, items: List<Pair<String, String>>): Int = items.count { (t, b) -> saveToGallery(ctx, t, b) }
}
