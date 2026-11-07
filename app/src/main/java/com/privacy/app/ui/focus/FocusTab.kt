package com.privacy.app.ui.focus

import android.app.TimePickerDialog
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privacy.app.data.FocusCfg
import com.privacy.app.data.FocusLogEntity
import com.privacy.app.data.FocusRepo
import com.privacy.app.ui.MainViewModel
import com.privacy.app.ui.MonoLabel
import com.privacy.app.ui.reports.PixelGlyph
import com.privacy.app.ui.tasks.AnimatedPercent
import com.privacy.app.ui.tasks.PixelProgressBar
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** State of the Mind tab. */
class FocusUi {
    /** The day shown (today unless a past day was picked in the calendar). */
    var day by mutableStateOf(LocalDate.now().toEpochDay())
    var adding by mutableStateOf(false)
    var apps by mutableStateOf(false)
    var edit by mutableStateOf<FocusRepo.Item?>(null)
}

@Composable
fun rememberFocusUi(): FocusUi = remember { FocusUi() }

@Composable
fun rememberFocusEval(vm: MainViewModel, day: Long): State<FocusRepo.Result?> {
    val tick by vm.dataTick.collectAsStateWithLifecycle(0L)
    val rev by vm.g.focus.rev.collectAsStateWithLifecycle()
    return produceState<FocusRepo.Result?>(null, day, tick, rev) { value = runCatching { vm.g.focus.evaluate(day) }.getOrNull() }
}

internal fun hm(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime().let { String.format(Locale.US, "%02d:%02d", it.hour, it.minute) }

@Composable
private fun Card(content: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(16.dp), color = c.surfaceVariant.copy(alpha = 0.55f), border = BorderStroke(1.dp, c.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

// ============================================================================================ the tab
fun LazyListScope.focusItems(vm: MainViewModel, ui: FocusUi, res: FocusRepo.Result?) {
    item { FocusHero(res) }
    item { Spacer(Modifier.height(14.dp)); DopamineGraph(vm, res, ui.day) }
    if (res != null && !res.usageAccess) item { UsageAccessBlock() }
    item { MonoLabel(if (ui.day == LocalDate.now().toEpochDay()) "Today's activity" else "Activity of this day", modifier = Modifier.padding(top = 18.dp, bottom = 2.dp)) }
    if (res == null || res.items.isEmpty()) item {
        Text("Nothing yet. Finished tasks, exercise, distracting apps (with usage access) and what you add with + are counted here.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    } else {
        res.items.forEachIndexed { i, it0 -> item(key = "$i-${it0.source}-${it0.pkg}-${it0.logId}") { ItemRow(it0) { ui.edit = it0 } } }
    }
    item { GuardSection(vm, ui, res) }
}

@Composable
private fun FocusHero(res: FocusRepo.Result?) {
    val c = MaterialTheme.colorScheme
    val f = res?.focus
    Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PixelProgressBar((f ?: 0f) / 100f, cols = 22, rows = 7, strength = 0.5f)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (f != null) AnimatedPercent(f / 100f)
                else Text("—", fontSize = 58.sp, fontWeight = FontWeight.Bold, color = c.onSurface.copy(alpha = 0.6f))
                Text(
                    if (f != null) "Detox status" else "Not enough data",
                    fontSize = 12.sp, color = c.onSurface.copy(alpha = 0.85f),
                    style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(c.background.copy(alpha = 0.9f), blurRadius = 10f))
                )
            }
        }
        if (res != null) MonoLabel(
            if (res.enough) String.format(Locale.US, "Detox %.0f%% · Dopamine %.0f%% · %d min useful · %d min distracting", res.focus, res.dist, res.posMin.toInt(), res.distMin.toInt())
            else "Finish a task, log exercise or allow app usage to start.", color = c.onSurfaceVariant
        )
    }
}

/** The one graph: Dopamine vs Detox through the day. Contrast (compact) is the default; the choice is remembered. */
@Composable
private fun DopamineGraph(vm: MainViewModel, res: FocusRepo.Result?, day: Long) {
    val c = MaterialTheme.colorScheme
    val ink = c.onSurface
    val rev by vm.g.focus.rev.collectAsStateWithLifecycle()
    val expanded = remember(rev) { vm.g.focus.graphExpanded }
    val nowH = if (day == LocalDate.now().toEpochDay()) (java.time.LocalTime.now().hour + 1).coerceAtMost(24) else 24
    Card {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Dopamine vs Detox", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Row(Modifier.clip(RoundedCornerShape(50)).clickable { vm.g.focus.graphExpanded = !expanded }.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (expanded) "Contrast" else "Expand", fontSize = 12.sp, color = c.onSurfaceVariant)
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "Contrast" else "Expand", tint = c.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
        if (res == null || !res.enough) {
            Text("The graph appears as soon as the day has data.", fontSize = 12.sp, color = c.onSurfaceVariant)
            return@Card
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(String.format(Locale.US, "Detox %.0f%%", res.focus), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(String.format(Locale.US, "Dopamine %.0f%%", res.dist), fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Canvas(Modifier.fillMaxWidth().height(if (expanded) 240.dp else 100.dp)) {
            val w = size.width; val h = size.height
            for (g in listOf(0f, 50f, 100f)) drawLine(ink.copy(alpha = 0.10f), Offset(0f, h - h * g / 100f), Offset(w, h - h * g / 100f), strokeWidth = 1f)
            val ds = (res.wakeAt - java.time.LocalDate.ofEpochDay(day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()) / 3_600_000f
            drawRect(ink.copy(alpha = 0.07f), Offset(w * ds.coerceIn(0f, 24f) / 24f, 0f), androidx.compose.ui.geometry.Size(w * (2f / 24f), h))
            if (expanded) {
                val mx = (res.hourD.maxOrNull() ?: 0f).coerceAtLeast(1f)
                for (i in 0 until 24) {
                    val bh = h * 0.35f * (res.hourD[i] / mx)
                    if (bh > 0.5f) drawRect(ink.copy(alpha = 0.22f), Offset(w * i / 24f + 1.5f, h - bh), androidx.compose.ui.geometry.Size(w / 24f - 3f, bh))
                }
            }
            fun line(f: (Int) -> Float, alpha: Float, dashed: Boolean) {
                val path = Path()
                for (i in 0..nowH) {
                    val x = w * i / 24f; val y = h - h * f(i).coerceIn(0f, 100f) / 100f
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, ink.copy(alpha = alpha), style = Stroke(width = 3.5f, cap = StrokeCap.Round,
                    pathEffect = if (dashed) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null))
            }
            line({ res.focusAt(it) }, 0.9f, false)
            line({ res.distAt(it) }, 0.55f, true)
        }
        if (expanded) {
            Row(Modifier.fillMaxWidth()) { listOf("0", "6", "12", "18", "24").forEach { Text(it, Modifier.weight(1f), fontSize = 10.sp, color = c.onSurfaceVariant) } }
            Text("Solid = detox, dashed = dopamine, bars = distracting-app load per hour. The shaded strip is the first two hours after waking (wake-up ${hm(res.wakeAt)}), when distraction costs most.", fontSize = 11.sp, color = c.onSurfaceVariant)
        }
    }
}

@Composable
private fun UsageAccessBlock() {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Track distracting apps", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text("Allow “Usage access” so the app can see how long YouTube, Instagram, TikTok, Facebook, X, Snapchat and games were open. It only reads the time per app, stays on this phone, and you can remove it any time in Android settings.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Open usage access settings") }
    }
}

/** Morning guard: a header you can expand (switch, wake-up time, apps) or contrast. */
@Composable
private fun GuardSection(vm: MainViewModel, ui: FocusUi, res: FocusRepo.Result?) {
    val ctx = LocalContext.current
    val rev by vm.g.focus.rev.collectAsStateWithLifecycle()
    val on = remember(rev) { vm.g.focus.guardOn }
    var open by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { open = !open }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Morning guard", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(if (on) "On – reminds you when a distracting app is open in the first two hours after waking" else "Off", fontSize = 12.sp, color = c.onSurfaceVariant)
            }
            Text(if (open) "Contrast" else "Expand", fontSize = 12.sp, color = c.onSurfaceVariant)
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = c.onSurfaceVariant)
        }
        if (open) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("A reminder when a distracting app is open in the first two hours after you wake up.", fontSize = 12.sp, color = c.onSurfaceVariant, modifier = Modifier.weight(1f))
                Switch(checked = on, onCheckedChange = {
                    if (it && !vm.g.focus.hasUsageAccess()) android.widget.Toast.makeText(ctx, "Allow usage access first.", android.widget.Toast.LENGTH_LONG).show()
                    vm.g.focus.guardOn = it
                })
            }
            if (res != null) {
                Text(
                    "Wake-up " + hm(res.wakeAt) + (if (res.wakeKnown) "" else " (usual time – log your Sleep or set it)") + " · morning window until " + hm(res.morningEnd) + " · usual bedtime " + hm(res.bedAt),
                    fontSize = 12.sp, color = c.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val cal = java.util.Calendar.getInstance().apply { timeInMillis = res.wakeAt }
                        TimePickerDialog(ctx, { _, h, m -> vm.g.focus.setWake(ui.day, h * 60 + m) }, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), true).show()
                    }) { Text("Set wake-up time") }
                    OutlinedButton(onClick = { ui.apps = true }) { Text("Choose apps") }
                }
            }
        }
    }
}

private fun glyphOf(it: FocusRepo.Item) = when {
    it.kind == "DIST" || it.kind == "APP" -> "phone"
    it.cat == "learning" -> "book"
    it.cat == "exercise" -> "run"
    it.cat == "personal" -> "heart"
    else -> "check"
}

/** Same look as an entry in the Health tab: pixel icon, bold heading, a line of detail, value on the right. Tap for the details. */
@Composable
private fun ItemRow(it: FocusRepo.Item, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val dist = it.kind == "DIST"
    val timeTxt = if (it.startAt > 0) hm(it.startAt) + " · " else ""
    val unrated = it.kind == "APP"
    val sub = if (unrated) timeTxt + String.format(Locale.US, "%.0f min", it.minutes) + " · app usage · not scored – tap to decide" else timeTxt + FocusCfg.catLabel(it.cat) + " · " + String.format(Locale.US, "%.0f min", it.minutes) +
        (if (it.source == "app") " · app usage" else if (it.source == "task") " · finished task" else if (it.source == "health") " · from Health" else "") +
        (if (dist && it.rating.isNotBlank()) " · " + FocusCfg.ratingLabel(it.rating).substringBefore(" /").lowercase() else "")
    Surface(shape = RoundedCornerShape(14.dp), color = c.surfaceVariant.copy(alpha = 0.45f), modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(c.onSurface.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) { PixelGlyph(glyphOf(it), size = 22, animated = true) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(it.label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
                Text(sub, fontSize = 12.sp, color = c.onSurfaceVariant, maxLines = 2)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (unrated) "0" else (if (dist) "−" else "+") + String.format(Locale.US, "%.0f", it.points), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(if (unrated) "no credit" else if (dist) "dopamine" else "detox", fontSize = 11.sp, color = c.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.onSurfaceVariant)
        }
    }
}

// ============================================================================================ dialogs
@Composable
fun FocusDialogs(vm: MainViewModel, ui: FocusUi, day: Long) {
    if (ui.adding) AddDialog(vm, day, onDismiss = { ui.adding = false })
    ui.edit?.let { e -> DetailDialog(vm, day, e) { ui.edit = null } }
    if (ui.apps) AppsDialog(vm) { ui.apps = false }
}

/** More detail on one entry – and the way to change it. */
@Composable
private fun DetailDialog(vm: MainViewModel, day: Long, e: FocusRepo.Item, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val c = MaterialTheme.colorScheme
    val dist = e.kind == "DIST"
    val row by produceState<FocusLogEntity?>(null, e.logId) { value = if (e.logId > 0) vm.g.db.focusDao().forDay(day).firstOrNull { it.id == e.logId } else null }
    var label by remember(row) { mutableStateOf(row?.label ?: e.label) }
    var minutes by remember(row) { mutableStateOf((row?.minutes ?: e.minutes.toInt()).toString()) }
    var rating by remember(row, e) { mutableStateOf(row?.rating?.ifBlank { "mindless" } ?: e.rating.ifBlank { "mindless" }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(e.label) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    (if (e.kind == "APP") "Not scored yet" else if (dist) "Distraction" else "Useful time") + (if (e.cat.isNotBlank()) " · " + FocusCfg.catLabel(e.cat) else "") + "\n" +
                        String.format(Locale.US, "%.0f minutes", e.minutes) + (if (e.startAt > 0) " from ${hm(e.startAt)}" else "") + "\n" +
                        String.format(Locale.US, "%s%.1f points", if (dist) "−" else "+", e.points) + (if (e.note.isNotBlank()) " · ${e.note}" else ""),
                    fontSize = 13.sp, color = c.onSurfaceVariant
                )
                Text(
                    when (e.source) {
                        "app" -> if (e.kind == "APP") "The app does not know this app, so it gives no credit and takes none. Decide what it is:" else "Counted automatically from your app usage. Change how it counts below."
                        "task" -> "A finished task counts about 15 / 30 / 60 minutes for Easy / Normal / Hard, plus 5 bonus points per task (max 15 a day)."
                        "health" -> "From an exercise entry in Health (minutes × 1.2). Edit it in the Health tab."
                        else -> "Added by you. You can change it or delete it."
                    }, fontSize = 12.sp, color = c.onSurfaceVariant
                )
                if (e.source == "app" && e.hourMin != null) {
                    UsageChart("Minutes used per hour", e.hourMin, null)
                    if (e.hourPts != null && e.kind != "APP") UsageChart("Credits per hour (− dopamine lost · + detox gained)", e.hourPts, e.hourMin)
                }
                if (e.source == "app") {
                    val f = vm.g.focus
                    val now = remember(e.pkg) { f.modeOf(e.pkg) }
                    Text("How should this app count?", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = now?.first == "DIST", onClick = { f.setAppMode(e.pkg, "DIST", "social"); onDismiss() }, label = { Text("Distracting") })
                        FilterChip(selected = now?.first == "POS", onClick = { f.setAppMode(e.pkg, "POS", "learning"); onDismiss() }, label = { Text("Useful") })
                        FilterChip(selected = now?.first == "IGN", onClick = { f.setAppMode(e.pkg, "IGN"); onDismiss() }, label = { Text("Ignore") })
                    }
                    if (now?.first == "DIST" || now?.first == "POS") {
                        val nn = now!!
                        val cats = if (nn.first == "DIST") FocusCfg.DIST_CATS else FocusCfg.POS_CATS
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            cats.forEach { (k, l) -> FilterChip(selected = nn.second == k, onClick = { f.setAppMode(e.pkg, nn.first, k); onDismiss() }, label = { Text(l) }) }
                        }
                    }
                    if (now?.first == "DIST") {
                        Text("Was it worth it today? (“Useful” turns it into learning time)", fontSize = 12.sp, color = c.onSurfaceVariant)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FocusCfg.RATINGS.forEach { (k, l) -> FilterChip(selected = vm.g.focus.ratingOf(day, e.pkg) == k, onClick = { f.setRating(day, e.pkg, k); onDismiss() }, label = { Text(l) }) }
                        }
                    }
                }
                if (e.source == "manual" && row != null) {
                    OutlinedTextField(label, { label = it.take(60) }, label = { Text("What") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(minutes, { minutes = it.filter { ch -> ch.isDigit() }.take(4) }, label = { Text("Minutes") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    if (dist) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FocusCfg.RATINGS.forEach { (k, l) -> FilterChip(selected = rating == k, onClick = { rating = k }, label = { Text(l) }) }
                    }
                }
            }
        },
        confirmButton = {
            if (e.source == "manual" && row != null) TextButton(onClick = {
                scope.launch { vm.g.focus.updateLog(row!!.copy(label = label.ifBlank { row!!.label }, minutes = (minutes.toIntOrNull() ?: row!!.minutes).coerceIn(1, 1440), rating = if (dist) rating else "")) }
                onDismiss()
            }) { Text("Save") } else TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            Row {
                if (e.source == "manual") TextButton(onClick = { scope.launch { vm.g.focus.deleteLog(e.logId) }; onDismiss() }) { Text("Delete", color = c.error) }
                if (e.source == "manual") TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun AddDialog(vm: MainViewModel, day: Long, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf("POS") }
    var cat by remember { mutableStateOf("work") }
    var label by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("30") }
    var rating by remember { mutableStateOf("mindless") }
    val isToday = day == LocalDate.now().toEpochDay()
    var startMin by remember { mutableStateOf(if (isToday) (java.time.LocalTime.now().let { it.hour * 60 + it.minute } - 30).coerceAtLeast(0) else 12 * 60) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to Focus") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = kind == "POS", onClick = { kind = "POS"; cat = "work" }, label = { Text("Useful time") })
                    FilterChip(selected = kind == "DIST", onClick = { kind = "DIST"; cat = "social" }, label = { Text("Distracted") })
                }
                val cats = if (kind == "POS") FocusCfg.POS_CATS else FocusCfg.DIST_CATS
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    cats.forEach { (k, l) -> FilterChip(selected = cat == k, onClick = { cat = k }, label = { Text(l) }) }
                }
                OutlinedTextField(label, { label = it.take(60) }, label = { Text(if (kind == "POS") "What did you do?" else "Which app / what was it?") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(minutes, { minutes = it.filter { ch -> ch.isDigit() }.take(4) }, label = { Text("Minutes") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = {
                    TimePickerDialog(ctx, { _, h, m -> startMin = h * 60 + m }, startMin / 60, startMin % 60, true).show()
                }) { Text("Started at " + String.format(Locale.US, "%02d:%02d", startMin / 60, startMin % 60)) }
                if (kind == "DIST") {
                    Text("Was it worth it?", fontSize = 12.sp)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FocusCfg.RATINGS.forEach { (k, l) -> FilterChip(selected = rating == k, onClick = { rating = k }, label = { Text(l) }) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = (minutes.toIntOrNull() ?: 0) > 0, onClick = {
                val start = vm.g.focus.dayStart(day) + startMin * 60_000L
                scope.launch { vm.g.focus.addLog(day, start, minutes.toIntOrNull() ?: 1, kind, cat, label, rating) }
                onDismiss()
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Bars for the 24 hours of the day. With [minutesRef] the bars are credits (up = detox, down = dopamine). */
@Composable
private fun UsageChart(title: String, values: FloatArray, minutesRef: FloatArray?) {
    val c = MaterialTheme.colorScheme
    val signed = minutesRef != null
    val maxAbs = values.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1f)
    val bar = c.onSurface; val neg = c.onSurface.copy(alpha = 0.45f); val grid = c.onSurface.copy(alpha = 0.15f)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontSize = 12.sp, color = c.onSurfaceVariant)
        Canvas(Modifier.fillMaxWidth().height(64.dp)) {
            val w = size.width; val h = size.height; val slot = w / 24f; val mid = if (signed) h / 2f else h
            drawLine(grid, Offset(0f, mid), Offset(w, mid), strokeWidth = 2f)
            for (i in 0 until 24) {
                val v = values[i]; if (v == 0f) continue
                val bh = (kotlin.math.abs(v) / maxAbs) * (if (signed) h / 2f else h) * 0.95f
                val x = i * slot + slot * 0.15f; val bw = slot * 0.7f
                if (v >= 0f) drawRect(bar, Offset(x, mid - bh), androidx.compose.ui.geometry.Size(bw, bh))
                else drawRect(neg, Offset(x, mid), androidx.compose.ui.geometry.Size(bw, bh))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("0", "6", "12", "18", "24").forEach { Text(it, fontSize = 10.sp, color = c.onSurfaceVariant) }
        }
        val total = values.sum()
        Text(if (signed) String.format(Locale.US, "Total %s%.0f credits · biggest hour %.0f", if (total >= 0) "+" else "−", kotlin.math.abs(total), maxAbs) else String.format(Locale.US, "Total %.0f min · busiest hour %.0f min", total, maxAbs), fontSize = 11.sp, color = c.onSurfaceVariant)
    }
}

@Composable
private fun AppsDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val f = vm.g.focus
    val rev by f.rev.collectAsStateWithLifecycle()
    val apps = remember {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(i, 0).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How apps count") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text("Every app is tracked. Tap an app to switch it: Not scored → Distracting → Useful → Ignore. Tap the label on the right to change its category. Unknown apps give no credit until you decide.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                apps.forEach { (pkg, name) ->
                    val mode = remember(rev, pkg) { f.modeOf(pkg) }
                    Row(Modifier.fillMaxWidth().clickable {
                        when (mode?.first) { null -> f.setAppMode(pkg, "DIST", "social"); "DIST" -> f.setAppMode(pkg, "POS", "learning"); "POS" -> f.setAppMode(pkg, "IGN"); else -> f.setAppMode(pkg, "UNSET") }
                    }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        val label = when (mode?.first) { "DIST" -> "Distracting · " + FocusCfg.catLabel(mode!!.second); "POS" -> "Useful · " + FocusCfg.catLabel(mode!!.second); "IGN" -> "Ignored"; else -> "Not scored" }
                        TextButton(onClick = {
                            val m = mode
                            if (m != null && (m.first == "DIST" || m.first == "POS")) {
                                val order = (if (m.first == "DIST") FocusCfg.DIST_CATS else FocusCfg.POS_CATS).map { it.first }
                                f.setAppMode(pkg, m.first, order[(order.indexOf(m.second) + 1) % order.size])
                            }
                        }) { Text(label, fontSize = 11.sp) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}
