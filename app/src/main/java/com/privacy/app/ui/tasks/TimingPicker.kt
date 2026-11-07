package com.privacy.app.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** What the "Timings" section of a task produces. */
data class Timing(val repeating: Boolean, val mask: Int, val dueMode: Boolean, val dueDays: Set<Long>) {
    /** daily flag, weekday mask (0 = every day), due day (last chosen date), all chosen days. Nothing chosen = due today. */
    fun resolve(): Resolved {
        val today = LocalDate.now().toEpochDay()
        return when {
            repeating -> Resolved(true, if (mask == 127 || mask == 0) 0 else mask, null, emptyList())
            dueMode && dueDays.isNotEmpty() -> { val d = dueDays.sorted(); Resolved(false, 0, d.last(), d) }
            else -> Resolved(false, 0, today, emptyList())
        }
    }
    class Resolved(val daily: Boolean, val repeatMask: Int, val dueDay: Long?, val dueDays: List<Long>)
    companion object {
        fun start() = Timing(false, 127, false, emptySet())
        fun of(t: com.privacy.app.data.TaskEntity): Timing = when {
            t.daily -> Timing(true, if (t.repeatMask == 0) 127 else t.repeatMask, false, emptySet())
            t.dueDays.isNotBlank() -> Timing(false, 127, true, t.dueDayList.toSet())
            t.dueDay != null -> Timing(false, 127, true, setOf(t.dueDay))
            else -> start()
        }
    }
}

/** "Timings": two buttons in one row – Repeating (weekdays, all on by default) and Due date (pick the exact days on a calendar). */
@Composable
fun TimingSection(timing: Timing, onChange: (Timing) -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Timings", fontSize = 13.sp, letterSpacing = 0.6.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            val shape = RoundedCornerShape(12.dp)
            if (timing.repeating) Button(onClick = { onChange(timing.copy(repeating = false)) }, shape = shape, modifier = Modifier.weight(1f)) { Text("Repeating") }
            else OutlinedButton(onClick = { onChange(timing.copy(repeating = true, mask = 127, dueMode = false)) }, shape = shape, modifier = Modifier.weight(1f)) { Text("Repeating") }
            if (timing.dueMode) Button(onClick = { onChange(timing.copy(dueMode = false)) }, shape = shape, modifier = Modifier.weight(1f)) { Text("Due date") }
            else OutlinedButton(onClick = { onChange(timing.copy(dueMode = true, repeating = false)) }, shape = shape, modifier = Modifier.weight(1f)) { Text("Due date") }
        }
        if (timing.repeating) {
            Text("Repeat on  ·  all days = every day", fontSize = 12.sp, color = c.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEachIndexed { i, d ->
                    FilterChip(
                        selected = (timing.mask shr i) and 1 == 1,
                        onClick = {
                            val m = timing.mask xor (1 shl i)
                            onChange(timing.copy(mask = if (m == 0) 1 shl i else m)) // never empty: at least one day stays
                        },
                        label = { Text(d.take(2), fontSize = 12.sp, maxLines = 1) }, modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        if (timing.dueMode) {
            MultiDateCalendar(timing.dueDays) { onChange(timing.copy(dueDays = it)) }
            Text(
                if (timing.dueDays.size > 1) "Days before the last date count half; the last date counts in full. “In progress” earns the whole half. Finish early = done + 1 commitment point."
                else "Pick one or more days. With several, only the last day counts in full.",
                fontSize = 12.sp, color = c.onSurfaceVariant
            )
        }
        if (!timing.repeating && !timing.dueMode) Text("Nothing chosen: the due date is today.", fontSize = 12.sp, color = c.onSurfaceVariant)
    }
}

/** A month grid where several days can be picked (past days are disabled). */
@Composable
fun MultiDateCalendar(selected: Set<Long>, onChange: (Set<Long>) -> Unit) {
    val c = MaterialTheme.colorScheme
    val today = remember { LocalDate.now() }
    var month by remember { mutableStateOf(YearMonth.from(selected.minOrNull()?.let { LocalDate.ofEpochDay(it) } ?: today)) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surfaceVariant.copy(alpha = 0.5f)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { month = month.minusMonths(1) }, enabled = month > YearMonth.from(today)) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous month") }
            Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())), Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next month") }
        }
        Row(Modifier.fillMaxWidth()) {
            for (i in 0 until 7) Text(DayOfWeek.MONDAY.plus(i.toLong()).getDisplayName(TextStyle.NARROW, Locale.getDefault()), Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 11.sp, color = c.onSurfaceVariant)
        }
        val lead = month.atDay(1).dayOfWeek.value - 1
        val weeks = (lead + month.lengthOfMonth() + 6) / 7
        val last = selected.maxOrNull()
        for (w in 0 until weeks) {
            Row(Modifier.fillMaxWidth()) {
                for (d in 0 until 7) {
                    val n = w * 7 + d - lead + 1
                    if (n < 1 || n > month.lengthOfMonth()) { Spacer(Modifier.weight(1f).aspectRatio(1f)); continue }
                    val date = month.atDay(n); val ep = date.toEpochDay()
                    val past = date.isBefore(today); val on = ep in selected
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).padding(2.dp).clip(CircleShape)
                            .background(if (on) c.onSurface else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable(enabled = !past) { onChange(if (on) selected - ep else selected + ep) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            n.toString(), fontSize = 13.sp, fontWeight = if (on || date == today) FontWeight.Bold else FontWeight.Normal,
                            color = when { on -> c.background; past -> c.onSurfaceVariant.copy(alpha = 0.35f); else -> c.onSurface }
                        )
                    }
                }
            }
        }
        if (selected.isNotEmpty()) Text(
            selected.sorted().joinToString(" · ") { LocalDate.ofEpochDay(it).format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())) } + (if (last != null && selected.size > 1) "   (last = full weight)" else ""),
            fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)
        )
    }
}
