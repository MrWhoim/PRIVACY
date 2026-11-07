package com.privacy.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val inlineRegex = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`|\\*(\\S(?:.*?\\S)?)\\*|==(.+?)==|~~(.+?)~~")

private fun inline(s: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in inlineRegex.findAll(s)) {
        append(s.substring(last, m.range.first))
        when {
            m.groups[1] != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
            m.groups[2] != null -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(m.groupValues[2]) }
            m.groups[4] != null -> withStyle(SpanStyle(background = Color(0x66FFD54F))) { append(m.groupValues[4]) }
            m.groups[5] != null -> withStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)) { append(m.groupValues[5]) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(m.groupValues[3]) }
        }
        last = m.range.last + 1
    }
    append(s.substring(last))
}

private fun tableCells(line: String): List<String> = line.trim().trim('|').split('|').map { it.trim() }
private fun isTableSeparator(line: String): Boolean {
    val t = line.trim()
    if (!t.startsWith("|") && !t.contains('|')) return false
    val cs = tableCells(t)
    return cs.isNotEmpty() && cs.all { Regex("^:?-+:?$").matches(it) }
}

/** A Markdown table: header row, divider lines, aligned columns; wide tables scroll sideways instead of squeezing. */
@Composable
private fun MarkdownTable(header: List<String>, rows: List<List<String>>, aligns: List<androidx.compose.ui.text.style.TextAlign>, color: Color, fontSize: TextUnit, codeBg: Color) {
    val n = header.size.coerceAtLeast(1)
    val scroll = n > 4
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    val rowMod = if (scroll) Modifier.width((n * 112).dp) else Modifier.fillMaxWidth()
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(shape).border(1.dp, color.copy(alpha = 0.25f), shape)
            .then(if (scroll) Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()) else Modifier)
    ) {
        @Composable
        fun rowOf(cells: List<String>, bold: Boolean, bg: Color) {
            Row(rowMod.background(bg)) {
                for (ci in 0 until n) {
                    Text(
                        inline(cells.getOrElse(ci) { "" }, codeBg), color = color, fontSize = (fontSize.value - 1f).sp,
                        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, textAlign = aligns.getOrElse(ci) { androidx.compose.ui.text.style.TextAlign.Start },
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }
        }
        rowOf(header, true, color.copy(alpha = 0.10f))
        rows.forEach { r ->
            androidx.compose.foundation.layout.Box(rowMod.height(1.dp).background(color.copy(alpha = 0.15f)))
            rowOf(r, false, Color.Transparent)
        }
    }
}

/** Small Markdown renderer: headings, bullets, numbered lists, tables, **bold**, *italic*, `code`, code blocks. */
@Composable
fun MarkdownText(text: String, color: Color, modifier: Modifier = Modifier, fontSize: TextUnit = 15.sp) {
    val codeBg = color.copy(alpha = 0.12f)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        var inCode = false
        val all = text.trim().lines()
        var li = 0
        var olN = 0 // numbering of the current numbered list always counts 1, 2, 3 … whatever numbers the text has
        val olRe = Regex("^(\\d+)[.)]\\s+(.*)")
        while (li < all.size) {
            val raw = all[li]; li++
            val line = raw.trimEnd()
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```")) { inCode = !inCode; continue }
            if (inCode) {
                Text(
                    line.ifEmpty { " " }, color = color, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().background(codeBg).padding(horizontal = 8.dp)
                )
                continue
            }
            // | a | b |  /  |---|---|  tables, as in standard Markdown
            if (trimmed.startsWith("|") && li < all.size && isTableSeparator(all[li])) {
                val header = tableCells(trimmed)
                val aligns = tableCells(all[li]).map { c -> if (c.startsWith(":") && c.endsWith(":")) androidx.compose.ui.text.style.TextAlign.Center else if (c.endsWith(":")) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start }
                li++
                val rows = mutableListOf<List<String>>()
                while (li < all.size && all[li].trim().startsWith("|")) { rows += tableCells(all[li]); li++ }
                MarkdownTable(header, rows, aligns, color, fontSize, codeBg)
                continue
            }
            val indent = ((line.length - trimmed.length) / 2).coerceAtMost(4)
            val om = olRe.matchEntire(trimmed)
            if (om != null && indent == 0) olN++
            else if (om == null && trimmed.isNotEmpty() && indent == 0 && !(trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• "))) olN = 0
            when {
                trimmed.isEmpty() -> Spacer(Modifier.height(4.dp))
                om != null -> Row(Modifier.padding(start = (indent * 12).dp)) {
                    Text((if (indent == 0) olN.toString() else om.groupValues[1]) + ".", color = color, fontSize = fontSize, modifier = Modifier.width(26.dp))
                    Text(inline(om.groupValues[2], codeBg), color = color, fontSize = fontSize, modifier = Modifier.weight(1f))
                }
                trimmed.startsWith("![img](") && trimmed.endsWith(")") -> {
                    val name = trimmed.removePrefix("![img](").removeSuffix(")")
                    val bmp = androidx.compose.runtime.remember(name) {
                        runCatching { android.graphics.BitmapFactory.decodeFile(java.io.File(java.io.File(ctx.filesDir, "note_images"), name).path)?.asImageBitmap() }.getOrNull()
                    }
                    if (bmp != null) androidx.compose.foundation.Image(
                        bmp, "Picture", Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.FillWidth
                    )
                }
                trimmed == "---" || trimmed == "***" -> androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(color.copy(alpha = 0.25f))
                )
                trimmed.startsWith("#") -> {
                    val level = trimmed.takeWhile { it == '#' }.length
                    Text(
                        inline(trimmed.trimStart('#').trim(), codeBg), color = color,
                        fontSize = (fontSize.value + (if (level == 1) 5 else if (level == 2) 3 else 1)).sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = if (level <= 2) 6.dp else 2.dp)
                    )
                }
                trimmed.startsWith("- [ ] ") || trimmed.startsWith("- [x] ") || trimmed.startsWith("- [X] ") -> Row(Modifier.padding(start = (indent * 12).dp)) {
                    Text(if (trimmed[3] == ' ') "☐" else "☑", color = color, fontSize = fontSize, modifier = Modifier.width(22.dp))
                    Text(inline(trimmed.drop(6), codeBg), color = color, fontSize = fontSize, modifier = Modifier.weight(1f))
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ") -> Row(Modifier.padding(start = (indent * 12).dp)) {
                    Text("•", color = color, fontSize = fontSize, modifier = Modifier.width(16.dp))
                    Text(inline(trimmed.drop(2), codeBg), color = color, fontSize = fontSize, modifier = Modifier.weight(1f))
                }
                trimmed.startsWith("> ") -> Text(
                    inline(trimmed.drop(2), codeBg), color = color.copy(alpha = 0.8f), fontSize = fontSize,
                    fontStyle = FontStyle.Italic, modifier = Modifier.padding(start = 8.dp)
                )
                else -> Text(
                    inline(line, codeBg), color = color, fontSize = fontSize,
                    modifier = Modifier.padding(start = (indent * 12).dp)
                )
            }
        }
    }
}
