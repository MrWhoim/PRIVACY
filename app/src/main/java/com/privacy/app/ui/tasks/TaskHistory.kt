@file:OptIn(ExperimentalMaterial3Api::class)

package com.privacy.app.ui.tasks

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privacy.app.data.Efficiency
import com.privacy.app.data.TaskHistoryEntity
import com.privacy.app.data.TaskStatus
import com.privacy.app.ui.MainViewModel
import com.privacy.app.ui.MonoLabel
import com.privacy.app.ui.bounceClick
import com.privacy.app.ui.face.PixelAvatar
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Task history. Opened by tapping the date on the Tasks screen. A calendar where every day shows a small pixel bar
 * filled by how much of that day was completed; tap a day to read its tasks. Read-only on purpose – the past can't be edited.
 */
@Composable
fun TaskHistorySheet(vm: MainViewModel, health: Boolean = false, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val today = remember { LocalDate.now() }
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var picked by remember { mutableStateOf(today) }
    val hidden = emptySet<Long>()
    val showHidden = true

    val from = month.atDay(1).toEpochDay()
    val to = month.atEndOfMonth().toEpochDay()
    val rows by remember(month) { vm.historyRange(from, to) }.collectAsStateWithLifecycle(emptyList())
    val stats by remember(month) { vm.statsRange(from, to) }.collectAsStateWithLifecycle(emptyList())
    val byDay = remember(rows, hidden, showHidden) { rows.filter { showHidden || it.day !in hidden }.groupBy { it.day } }
    val manual = remember(stats) { stats.associate { it.day to it.manualPoints } }
    val hTick by vm.dataTick.collectAsStateWithLifecycle(0L)
    // Health mode: the day score of every day of the month, from the saved entries
    val hScores by androidx.compose.runtime.produceState(emptyMap<Long, Float>(), month, health, hTick) {
        if (!health) { value = emptyMap(); return@produceState }
        val ents = vm.g.db.healthDao().range(from, to).groupBy { it.day }
        val t = vm.g.health.targetsNow()
        value = ents.mapValues { (d, l) -> com.privacy.app.data.HealthScore.evaluate(d, l, null, t).score ?: 0f }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous month") }
                Text(
                    month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())),
                    Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 19.sp, fontWeight = FontWeight.Bold
                )
                IconButton(onClick = { month = month.plusMonths(1) }, enabled = month < YearMonth.from(today)) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next month")
                }
            }
            // weekday header (Monday first)
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until 7) {
                    val d = DayOfWeek.MONDAY.plus(i.toLong())
                    Text(d.getDisplayName(TextStyle.NARROW, Locale.getDefault()), Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 11.sp, color = c.onSurfaceVariant)
                }
            }
            val lead = month.atDay(1).dayOfWeek.value - 1
            val cells = lead + month.lengthOfMonth()
            val weeks = (cells + 6) / 7
            for (w in 0 until weeks) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (d in 0 until 7) {
                        val n = w * 7 + d - lead + 1
                        if (n < 1 || n > month.lengthOfMonth()) {
                            Spacer(Modifier.weight(1f).aspectRatio(0.82f))
                        } else {
                            val date = month.atDay(n)
                            val future = date.isAfter(today)
                            val dayRows = byDay[date.toEpochDay()].orEmpty()
                            val isHid = date.toEpochDay() in hidden && !showHidden
                            val has = if (isHid) false else if (health) hScores.containsKey(date.toEpochDay()) else dayRows.isNotEmpty() || (manual[date.toEpochDay()] ?: 0) > 0
                            val pct = if (health) (hScores[date.toEpochDay()] ?: 0f) / 100f else if (has) Efficiency.ofHistory(dayRows, manual[date.toEpochDay()] ?: 0) else 0f
                            DayCell(n, pct, has, selected = date == picked, isToday = date == today, enabled = !future, modifier = Modifier.weight(1f)) { picked = date }
                        }
                    }
                }
            }

            val pickedHidden = picked.toEpochDay() in hidden
            if (pickedHidden && !showHidden) MonoLabel("This day is hidden.")
            else if (health) HealthDayDetail(picked, vm, hTick) else DayDetail(picked, byDay[picked.toEpochDay()].orEmpty(), manual[picked.toEpochDay()] ?: 0, vm)
            // past days can be edited: add entries / change states – the day's report numbers are recalculated
            if (picked.isBefore(today)) {
                var newText by remember(picked, health) { mutableStateOf("") }
                androidx.compose.material3.OutlinedTextField(
                    value = newText, onValueChange = { newText = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(if (health) "Add to this day (e.g. 2 rotis, 1 glass water, slept 7 h)" else "Add a task to this day") },
                    trailingIcon = {
                        TextButton(enabled = newText.isNotBlank(), onClick = {
                            val t = newText.trim(); newText = ""
                            if (health) vm.logHealthText(t, picked.toEpochDay()) { }
                            else vm.addHistoryTask(picked.toEpochDay(), t)
                        }) { Text("Add") }
                    }
                )
                MonoLabel(if (health) "Past days can be edited – the score is recalculated." else "Tap a task to edit it – the day's score is recalculated.")
            }
        }
    }
}

@Composable
internal fun DayCell(day: Int, pct: Float, has: Boolean, selected: Boolean, isToday: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) c.onSurface.copy(alpha = 0.12f) else c.surfaceVariant.copy(alpha = if (enabled) 0.6f else 0.25f),
        border = when { selected -> BorderStroke(1.5.dp, c.onSurface); isToday -> BorderStroke(1.dp, c.outline); else -> null },
        modifier = modifier.aspectRatio(0.82f).clip(RoundedCornerShape(10.dp)).then(if (enabled) Modifier.bounceClick(onClick = onClick) else Modifier)
    ) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
            Text(
                "$day", fontSize = 14.sp, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                color = if (enabled) c.onSurface else c.onSurface.copy(alpha = 0.3f)
            )
            MiniPixelBar(pct, Modifier.fillMaxWidth(), cells = 5, dim = !has)
        }
    }
}

@Composable
private fun DayDetail(day: LocalDate, rows: List<TaskHistoryEntity>, manual: Int, vm: MainViewModel) {
    val c = MaterialTheme.colorScheme
    val pct = Efficiency.ofHistory(rows, manual)
    val optionalDone = rows.count { it.optional && it.status == TaskStatus.COMPLETED.name }
    var editRow by remember { mutableStateOf<TaskHistoryEntity?>(null) }
    editRow?.let { r -> HistoryRowEditDialog(r, onDismiss = { editRow = null }, onSave = { t, st, df, op, co -> vm.editHistoryRow(r.day, r.taskId, t, st, df, op, co); editRow = null }, onDelete = { vm.deleteHistoryRow(r.day, r.taskId); editRow = null }) }
    Surface(shape = RoundedCornerShape(14.dp), color = c.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(day.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    MonoLabel(
                        if (rows.isEmpty() && manual == 0) "Nothing recorded" else String.format(Locale.US, "%.0f%% · bonus %d", pct * 100f, Efficiency.bonus(optionalDone, manual))
                    )
                }
                
            }
            PixelProgressBar(pct, cols = 24, rows = 2)
            rows.forEach { r ->
                val done = r.status == TaskStatus.COMPLETED.name
                Row(
                    (if (day.isBefore(LocalDate.now())) Modifier.fillMaxWidth().clickable { editRow = r } else Modifier),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(10.dp).background(
                            when (r.status) {
                                TaskStatus.COMPLETED.name -> c.onSurface
                                TaskStatus.IN_PROGRESS.name -> c.onSurface.copy(alpha = 0.55f)
                                else -> c.onSurface.copy(alpha = 0.18f)
                            }
                        )
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        r.title, Modifier.weight(1f), fontSize = 14.sp,
                        textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None,
                        color = if (done) c.onSurfaceVariant else c.onSurface
                    )
                    val tag = buildList {
                        if (r.optional) add("optional")
                        if (r.daily) add("daily")
                        add(when (r.status) { TaskStatus.COMPLETED.name -> "done"; TaskStatus.IN_PROGRESS.name -> "in progress"; else -> "not done" })
                    }.joinToString(" · ")
                    MonoLabel(tag)
                }
            }
            if (manual > 0) MonoLabel("+$manual commitment point${if (manual == 1) "" else "s"} given by hand")
        }
    }
}

/** The selected day in the Health calendar: score, the four categories ("Not logged" is shown as such) and every entry with its time. */
@Composable
private fun HealthDayDetail(day: LocalDate, vm: MainViewModel, tick: Long) {
    val c = MaterialTheme.colorScheme
    val ev by androidx.compose.runtime.produceState<com.privacy.app.data.HealthScore.DayEval?>(null, day, tick) { value = vm.g.health.evaluate(day.toEpochDay()) }
    Surface(shape = RoundedCornerShape(14.dp), color = c.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val e = ev
            Text(day.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            if (e == null) MonoLabel("Loading…") else {
                MonoLabel(if (e.score == null) "Nothing recorded" else String.format(Locale.US, "%.0f%% health of the day", e.score))
                PixelProgressBar((e.score ?: 0f) / 100f, cols = 24, rows = 2)
                e.cats.forEach { cat ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(cat.label, Modifier.weight(1f), fontSize = 14.sp)
                        MonoLabel(cat.score?.let { String.format(Locale.US, "%.0f%%", it) } ?: "Not logged")
                    }
                }
                e.entries.sortedBy { it.createdAt }.forEach { en ->
                    val time = java.time.Instant.ofEpochMilli(en.createdAt).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
                    Text(
                        "$time · " + en.category.lowercase().replaceFirstChar { it.uppercase() } + ": " + en.label + (en.kcal?.let { " · ~${it.toInt()} kcal" } ?: "") +
                            (if (en.category != "FOOD") (en.amount?.let { " · ${com.privacy.app.data.fmt(it)} ${en.unit ?: ""}" } ?: "") else ""),
                        fontSize = 12.sp, color = c.onSurfaceVariant
                    )
                }
            }
        }
    }
}


/** Edit a task of a past day just like a normal task: name, state, difficulty, optional / core. */
@Composable
private fun HistoryRowEditDialog(r: TaskHistoryEntity, onDismiss: () -> Unit, onSave: (String, TaskStatus, Int, Boolean, Boolean) -> Unit, onDelete: () -> Unit) {
    var title by remember(r) { mutableStateOf(r.title) }
    var status by remember(r) { mutableStateOf(runCatching { TaskStatus.valueOf(r.status) }.getOrDefault(TaskStatus.NOT_STARTED)) }
    var diff by remember(r) { mutableStateOf(r.difficulty) }
    var optional by remember(r) { mutableStateOf(r.optional) }
    var core by remember(r) { mutableStateOf(r.core) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.OutlinedTextField(title, { title = it }, label = { Text("Task") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Work to do" to TaskStatus.NOT_STARTED, "In progress" to TaskStatus.IN_PROGRESS, "Completed" to TaskStatus.COMPLETED).forEach { (l, st) ->
                        androidx.compose.material3.FilterChip(selected = status == st, onClick = { status = st }, label = { Text(l) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Easy", "Normal", "Hard").forEachIndexed { i, l -> androidx.compose.material3.FilterChip(selected = diff == i, onClick = { diff = i }, label = { Text(l) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.material3.FilterChip(selected = optional, onClick = { optional = !optional; if (optional) core = false }, label = { Text("Optional") })
                    androidx.compose.material3.FilterChip(selected = core, onClick = { core = !core; if (core) optional = false }, label = { Text("Core") })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(title, status, diff, optional, core) }) { Text("Save") } },
        dismissButton = { Row { TextButton(onClick = onDelete) { Text("Remove", color = MaterialTheme.colorScheme.error) }; TextButton(onClick = onDismiss) { Text("Cancel") } } }
    )
}
