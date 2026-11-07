package com.privacy.app.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privacy.app.data.Efficiency
import com.privacy.app.data.TaskStatus
import com.privacy.app.ui.MainViewModel
import com.privacy.app.ui.tasks.PixelProgressBar
import java.util.Locale

/**
 * Today's efficiency as a pixel bar attached to the chat header. Tap to open today's list: tick a task done, set it in progress or cancel it
 * (tough ones included). (the switch is in Settings › Look).
 */
@Composable
fun EffBar(vm: MainViewModel) {
    val s = vm.g.settings
    val on by s.effBarOn.collectAsStateWithLifecycle()
    if (!on) return
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val stat by remember { vm.historyStat(vm.today()) }.collectAsStateWithLifecycle(null)
    var open by rememberSaveable { mutableStateOf(false) }
    val c = MaterialTheme.colorScheme
    val cons by vm.consStars.collectAsStateWithLifecycle()
    val taskRes = Efficiency.calc(tasks.map { Efficiency.line(it) }, stat?.manualPoints ?: 0, cons, true)
    val tick by vm.dataTick.collectAsStateWithLifecycle(0L)
    val hev by androidx.compose.runtime.produceState<com.privacy.app.data.HealthScore.DayEval?>(null, tick) { value = runCatching { vm.g.health.evaluate(vm.today()) }.getOrNull() }
    val healthPct = (hev?.score ?: 0f).coerceIn(0f, 100f)
    // the combined day: tasks and health count equally (with no planned tasks the day is judged by health alone)
    val fres by androidx.compose.runtime.produceState<com.privacy.app.data.FocusRepo.Result?>(null, tick) { value = runCatching { vm.g.focus.evaluate(vm.today()) }.getOrNull() }
    val focusPct = fres?.focus
    // the day = (tasks + health + focus) / 3; a part that has no data yet is left out
    val eff = (listOfNotNull(if (taskRes.applicable) taskRes.score else null, healthPct, focusPct).average().toFloat()) / 100f
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var foodDialog by remember { mutableStateOf(false) }
    fun say(m: String) { if (m.isNotBlank()) android.widget.Toast.makeText(ctx, m, android.widget.Toast.LENGTH_LONG).show() }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Today", fontSize = 12.sp, color = c.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(String.format(Locale.US, "%.0f%%", com.privacy.app.ui.rememberFillIn(eff) * 100f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(
                (if (taskRes.applicable) String.format(Locale.US, "Tasks %.0f%% · ", taskRes.score) else "") + String.format(Locale.US, "Health %.0f%%", healthPct) + (if (focusPct != null) String.format(Locale.US, " · Focus %.0f%%", focusPct) else ""),
                fontSize = 11.sp, color = c.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (open) "Collapse" else "Expand", tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        PixelProgressBar(eff, Modifier.padding(top = 2.dp, bottom = 6.dp), cols = 40, rows = 2)
        if (open) {
            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
            // ---- the four health categories, same look as the tasks: name, how far today is, and a quick-add on the right ----
            hev?.let { e ->
                HorizontalDivider(color = c.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))
                val day = vm.today()
                fun r1(v: Double) = String.format(Locale.US, "%.1f", v).removeSuffix(".0")
                val t = e.targets
                val rows = listOf(
                    Triple("Nutrition", e.cat("N").let { cat ->
                        if (!cat.logged) "Not logged · target ${t.kcal ?: "—"} kcal" else "${(e.kcal ?: 0.0).toInt()} of ${t.kcal ?: "—"} kcal · protein ${(e.protein ?: 0.0).toInt()} g"
                    }, "Add Food"),
                    Triple("Activity", e.cat("A").let { cat ->
                        if (!cat.logged) "Not logged · aim ~${t.activityMetMin.toInt()} MET-min" else "${e.metMin.toInt()} of ~${t.activityMetMin.toInt()} MET-min · ${e.activeMinutes.toInt()} min active"
                    }, "10 Pushups"),
                    Triple("Sleep", e.cat("S").let { cat ->
                        if (!cat.logged) "Not logged · range ${r1(t.sleepLoH ?: 7.0)}–${r1(t.sleepHiH ?: 9.0)} h" else "${r1(e.sleepH ?: 0.0)} h of ${r1(t.sleepLoH ?: 7.0)}–${r1(t.sleepHiH ?: 9.0)} h"
                    }, "½ hr nap"),
                    Triple("Water", e.cat("W").let { cat ->
                        if (!cat.logged) "Not logged · target ${t.waterMl ?: 2000} ml" else "${(e.waterMl ?: 0.0).toInt()} of ${t.waterMl ?: 2000} ml"
                    }, "1 Glass")
                )
                rows.forEachIndexed { i, (name, desc, action) ->
                    val key = listOf("N", "A", "S", "W")[i]
                    val sc = e.cat(key).score
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(name + (sc?.let { String.format(Locale.US, "  %.0f%%", it) } ?: ""), fontSize = 14.sp, color = c.onSurface)
                            Text(desc, fontSize = 11.sp, color = c.onSurfaceVariant)
                        }
                        androidx.compose.material3.TextButton(onClick = {
                            when (i) {
                                0 -> foodDialog = true
                                1 -> vm.logHealth("ACTIVITY", "push-ups", 10.0, "reps", day, force = true) { say(it) }
                                2 -> vm.logHealth("SLEEP", "nap", 0.5, "h", day, force = true) { say(it) }
                                else -> vm.logHealth("WATER", "water (1 glass)", 250.0, "ml", day, force = true) { say(it) }
                            }
                        }) { Text(action, fontSize = 12.sp) }
                    }
                }
            }
            HorizontalDivider(color = c.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))
            val list = tasks.filter { it.status != TaskStatus.DELETED }
            if (list.isEmpty()) Text("No tasks today.", fontSize = 13.sp, color = c.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            list.forEach { t ->
                val done = t.status == TaskStatus.COMPLETED
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = done, onCheckedChange = { vm.setTaskStatus(t.id, if (it) TaskStatus.COMPLETED else TaskStatus.NOT_STARTED) })
                    Column(Modifier.weight(1f)) {
                        Text(
                            t.title, fontSize = 14.sp, color = if (done) c.onSurfaceVariant else c.onSurface,
                            textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None
                        )
                        val meta = buildList { if (t.priority == 2) add("Tough"); if (t.optional) add("Optional"); if (t.status == TaskStatus.IN_PROGRESS) add("In progress") }
                        if (meta.isNotEmpty()) Text(meta.joinToString(" · "), fontSize = 11.sp, color = c.onSurfaceVariant)
                    }
                    if (!done) {
                        androidx.compose.material3.TextButton(onClick = { vm.setTaskStatus(t.id, if (t.status == TaskStatus.IN_PROGRESS) TaskStatus.NOT_STARTED else TaskStatus.IN_PROGRESS) }) {
                            Text(if (t.status == TaskStatus.IN_PROGRESS) "Pause" else "Start", fontSize = 12.sp)
                        }
                    }
                }
            }
            }
            HorizontalDivider(color = c.outlineVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
    if (foodDialog) {
        var what by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { foodDialog = false },
            title = { Text("Add food") },
            text = {
                androidx.compose.material3.OutlinedTextField(what, { what = it }, placeholder = { Text("e.g. 2 rotis and dal") }, singleLine = true)
            },
            confirmButton = {
                androidx.compose.material3.TextButton(enabled = what.isNotBlank(), onClick = {
                    val w = what.trim(); foodDialog = false
                    vm.logHealthText("ate $w", vm.today()) { say(it) }
                }) { Text("Add") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { foodDialog = false }) { Text("Cancel") } }
        )
    }
}
