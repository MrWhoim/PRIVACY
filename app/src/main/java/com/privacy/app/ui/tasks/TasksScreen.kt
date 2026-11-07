@file:OptIn(ExperimentalMaterial3Api::class)

package com.privacy.app.ui.tasks

import com.privacy.app.ui.focus.focusItems
import com.privacy.app.ui.pixelGlass
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.saveable.rememberSaveable
import com.privacy.app.ui.goTo
import kotlinx.coroutines.launch
import com.privacy.app.ui.health.HealthDialogs
import com.privacy.app.ui.health.healthItems
import com.privacy.app.ui.health.rememberHealthEval
import com.privacy.app.ui.health.rememberHealthUi
import com.privacy.app.ui.reports.PixelGlyph
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.foundation.border
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privacy.app.ai.formatDay
import com.privacy.app.data.Efficiency
import com.privacy.app.data.TaskEntity
import com.privacy.app.ui.face.PixelAvatar
import com.privacy.app.data.TaskStatus
import com.privacy.app.ui.Appear
import com.privacy.app.ui.MainViewModel
import com.privacy.app.ui.MonoLabel
import com.privacy.app.ui.ScreenBackground
import com.privacy.app.ui.bounceClick
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Today at a glance: a pixel progress bar (done = full, in progress = half, optional tasks and commitment points add on top), commitment-point stars,
 * and every task as a card. Tap a card to set its state or delete it – for daily habits you can delete just for today.
 */
@Composable
fun TasksScreen(vm: MainViewModel, onBack: () -> Unit, initialSection: String = "task") {
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val todayStat by remember { vm.historyStat(vm.today()) }.collectAsStateWithLifecycle(null)
    var selected by remember { mutableStateOf<TaskEntity?>(null) }
    var editing by remember { mutableStateOf<TaskEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var starAsk by remember { mutableStateOf(false) }
    var showCal by remember { mutableStateOf(false) }
    var showFuture by remember { mutableStateOf(false) }
    val future by vm.futureTasks.collectAsStateWithLifecycle()
    val hui = rememberHealthUi(vm)
    val hev by rememberHealthEval(vm, hui)
    val colors = MaterialTheme.colorScheme
    val cons by vm.consStars.collectAsStateWithLifecycle()
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = when (initialSection) { "health" -> 1; "focus" -> 2; else -> 0 }) { 3 }
    val fui = com.privacy.app.ui.focus.rememberFocusUi()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.snapshotToday() }

    val required = tasks.filter { !it.optional }
    val optionalTasks = tasks.filter { it.optional }
    val manual = todayStat?.manualPoints ?: 0
    // the day's score is always calculated from the tasks as they are right now – nothing to confirm
    val res = remember(tasks, manual, cons) { Efficiency.calc(tasks.map { Efficiency.line(it) }, manual, cons, true) }
    val points = res.stars
    val efficiency = res.fraction
    val done = res.done
    val doing = res.doing
    val healthTab = pager.currentPage == 1
    val focusTab = pager.currentPage == 2
    val fres by com.privacy.app.ui.focus.rememberFocusEval(vm, fui.day)
    com.privacy.app.ui.TabSwipeGuard(pager)

    ScreenBackground {
        Column(Modifier.fillMaxSize()) {
            // ---- top bar: back · date (opens the calendar) · add (corner) ----
            Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp, start = 18.dp, end = 18.dp)) {
                com.privacy.app.ui.MenuButton(Modifier.align(Alignment.CenterStart))
                Row(
                    Modifier.align(Alignment.Center).clip(RoundedCornerShape(50)).background(colors.surfaceVariant).border(1.dp, colors.outline, RoundedCornerShape(50))
                        .clickable { showCal = true }.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        LocalDate.ofEpochDay(if (focusTab) fui.day else if (healthTab) hui.day else vm.today()).format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())),
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Rounded.CalendarMonth, "Open calendar", modifier = Modifier.size(18.dp), tint = colors.onSurface)
                    Icon(Icons.Rounded.ExpandMore, null, modifier = Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                }
                IconButton(onClick = { if (focusTab) fui.adding = true else if (healthTab) hui.adding = true else adding = true }, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Icon(Icons.Rounded.Add, if (focusTab) "Add to Mind" else if (healthTab) "Add health entry" else "Add task", modifier = Modifier.size(28.dp))
                }
            }
            // ---- Task | Health (tap, or swipe the page) ----
            com.privacy.app.ui.SegmentedTabs(
                listOf("Task", "Health", "Mind"), pager.currentPage, { scope.launch { pager.goTo(it) } },
                Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 4.dp)
            )
            androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    if (page == 2) focusItems(vm, fui, fres) else if (page == 1) healthItems(vm, hui, hev) else {

            // ---- the day's pixel progress ----
            item {
                Appear(delayMs = 80) {
                    Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            PixelProgressBar(efficiency, cols = 22, rows = 7, strength = 0.5f)
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (res.applicable) AnimatedPercent(res.score / 100f)
                                else Text("N/A", fontSize = 58.sp, fontWeight = FontWeight.Bold, color = colors.onSurface.copy(alpha = 0.6f))
                                Text(
                                    if (!res.applicable) "no tasks yet" else "efficiency of this day",
                                    fontSize = 12.sp, color = colors.onSurface.copy(alpha = 0.85f),
                                    style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(colors.background.copy(alpha = 0.9f), blurRadius = 10f))
                                )
                            }
                        }
                        MonoLabel(
                            "$done of ${res.planned} done" + (if (doing > 0) " · $doing in progress" else "") + (if (points > 0) " · +$points bonus" else ""),
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            }

            // ---- commitment points ----
            item {
                Appear(delayMs = 200) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Commitment Points", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            for (i in 0 until Efficiency.MAX_STARS) {
                                val auto = i < cons
                                val j = i - cons // index among the hand-given Tough Commitment stars
                                StarTile(filled = auto || (j >= 0 && j < manual), auto = auto) {
                                    when {
                                        auto -> Unit // earned by the streak
                                        j < manual -> vm.setManualPoints(vm.today(), j)   // take a hand-given star back
                                        else -> starAsk = true                              // asks first: did you really earn it?
                                    }
                                }
                            }
                        }
                        MonoLabel(if (cons > 0) "Consistency ★ earned automatically · tap a free star only for a truly tough win" else "Tap a star only when you did something truly tough today")
                    }
                }
            }

            // ---- task cards ----
            if (tasks.isEmpty()) {
                item { MonoLabel("No tasks yet – tap + to add one", Modifier.fillMaxWidth().padding(top = 18.dp), color = colors.onSurfaceVariant) }
            }
            items(required.size) { i ->
                val t = required[i]
                Appear(delayMs = 300 + (i % 8) * 70) { TaskCard(t, i) { selected = t } }
            }
            if (optionalTasks.isNotEmpty()) {
                item { MonoLabel("Optional · bonus – not scored", Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp), color = colors.onSurfaceVariant) }
                items(optionalTasks.size) { i ->
                    val t = optionalTasks[i]
                    Appear(delayMs = 60 + (i % 8) * 60) { TaskCard(t, i) { selected = t } }
                }
            }
            if (future.isNotEmpty()) item {
                Text(
                    "Future Tasks (${future.size})", fontSize = 13.sp, color = colors.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth().clickable { showFuture = true }.padding(top = 26.dp, bottom = 10.dp), textAlign = TextAlign.Center
                )
            }
                    }
                }
            }
        }
    }

    selected?.let { t ->
        // always show the latest version of the task in the sheet
        val live = tasks.firstOrNull { it.id == t.id } ?: t
        TaskActionSheet(
            task = live,
            onDismiss = { selected = null },
            onStatus = { vm.setTaskStatus(live.id, it); selected = null },
            onEdit = { selected = null; editing = live },
            onDeleteToday = { vm.deleteTaskForToday(live.id); selected = null },
            onDeleteForever = { vm.deleteTask(live.id); selected = null }
        )
    }
    if (showFuture) ModalBottomSheet(onDismissRequest = { showFuture = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Future Tasks", fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Text("Tasks due on a later day and tasks you removed for today. They carry no weight in today's score: marking one “in progress” earns 1 commitment point, finishing it 2 or more days early earns 2.", fontSize = 12.sp, color = colors.onSurfaceVariant)
            future.forEach { t ->
                val due = t.dueDay?.let { LocalDate.ofEpochDay(it).format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())) }
                Surface(shape = RoundedCornerShape(10.dp), color = colors.surfaceVariant, modifier = Modifier.fillMaxWidth().bounceClick { showFuture = false; selected = t }) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.title, fontSize = 15.sp)
                            Text(
                                (if (t.hiddenDay != null && t.hiddenDay == vm.today()) "removed for today" else "due $due") +
                                    (if (t.status == TaskStatus.IN_PROGRESS) " · in progress" else if (t.status == TaskStatus.COMPLETED) " · done" else ""),
                                fontSize = 11.sp, color = colors.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
    if (starAsk) StarWarning(
        exceptional = manual + 1 >= 3,
        onYes = { vm.setManualPoints(vm.today(), manual + 1); starAsk = false },
        onNo = { starAsk = false }
    )
    HealthDialogs(vm, hui)
    com.privacy.app.ui.focus.FocusDialogs(vm, fui, fui.day)
    if (showCal && focusTab) com.privacy.app.ui.focus.MindHistorySheet(vm, fui) { showCal = false }
    else if (showCal) com.privacy.app.ui.tasks.TaskHistorySheet(vm, health = healthTab, onDismiss = { showCal = false })
    if (adding) AddTaskSheet(onDismiss = { adding = false }, onAdd = { title, daily, p, due, opt, mask, diff, core, days -> vm.addTask(title, daily, p, due, opt, mask, diff, core, days); adding = false })
    editing?.let { t ->
        TaskEditSheet(task = t, onDismiss = { editing = null }, onSave = { vm.updateTask(it); editing = null }, onDelete = { vm.deleteTask(t.id); editing = null })
    }
}

// ------------------------------------------------------------------------------------------------ pixel header bits
@Composable
internal fun AnimatedPercent(efficiency: Float) {
    val animated = com.privacy.app.ui.rememberFillIn(efficiency, 1300)
    Text(String.format(Locale.US, "%.1f%%", animated * 100f), fontSize = 58.sp, fontWeight = FontWeight.Bold, style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(MaterialTheme.colorScheme.background.copy(alpha = 0.95f), offset = androidx.compose.ui.geometry.Offset(0f, 2f), blurRadius = 16f)))
}

@Composable
private fun StarTile(filled: Boolean, auto: Boolean = false, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier.size(56.dp)
            .clip(CircleShape).background(if (filled) c.onSurface.copy(alpha = if (auto) 0.55f else 0.78f) else c.onSurface.copy(alpha = 0.07f))
            .then(if (auto) Modifier.border(1.5.dp, c.onSurface.copy(alpha = 0.5f), CircleShape) else Modifier)
            .bounceClick(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Rounded.Star, "Commitment point", tint = if (filled) c.background else c.onSurface.copy(alpha = 0.30f), modifier = Modifier.size(32.dp))
    }
}

/** Tapping a free star asks first – the points are only worth something if they were really earned. */
@Composable
private fun StarWarning(exceptional: Boolean, onYes: () -> Unit, onNo: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onNo,
        title = { Text(if (exceptional) "Another tough star?" else "Be honest…") },
        text = {
            Text(
                if (exceptional) "Did you really push past your own resistance and finish that important job today? A third or fourth Tough Commitment star is only for an exceptional day."
                else "Did you really push past your own resistance and finish that important job? Only then does the star count."
            )
        },
        confirmButton = { TextButton(onClick = onYes) { Text("Yes, I earned it") } },
        dismissButton = { TextButton(onClick = onNo) { Text("Not really") } }
    )
}

// ------------------------------------------------------------------------------------------------ cards
private val CARD_SHADES = floatArrayOf(0.05f, 0.10f, 0.15f, 0.08f)

@Composable
private fun TaskCard(t: TaskEntity, index: Int, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val done = t.status == TaskStatus.COMPLETED
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth()) {
            Text(
                "${index + 1}".padStart(2, '0'), color = c.onSurface.copy(alpha = 0.07f), fontSize = 64.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                PixelStatus(t.status)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        t.title, fontSize = 17.sp, fontWeight = FontWeight.Medium,
                        color = if (done) c.onSurfaceVariant else c.onSurface,
                        textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None
                    )
                }
            }
        }
        androidx.compose.material3.HorizontalDivider(color = c.outlineVariant)
    }
}

/**
 * A 3×3 block of pixels that is alive: not started = a faint wave of light; in progress = the lit part pulses and the next pixels glow
 * as if trying to fill the block; done = full with a slow shimmer.
 */
@Composable
private fun PixelStatus(status: TaskStatus) {
    val ink = MaterialTheme.colorScheme.onSurface
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "px")
    val pulseA by t.animateFloat(0.45f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "pulse")
    val waveA by t.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1300, easing = LinearEasing)), label = "wave")
    Canvas(Modifier.size(26.dp)) {
        val pulse = if (com.privacy.app.ui.Anim.enabled) pulseA else 0.8f
        val wave = if (com.privacy.app.ui.Anim.enabled) waveA else 0.5f
        val cell = size.width / 3f
        val gap = cell * 0.16f
        val lit = when (status) { TaskStatus.COMPLETED -> 9; TaskStatus.IN_PROGRESS -> 4; else -> 0 }
        if (status == TaskStatus.IN_PROGRESS) {
            // soft glow behind the block
            drawCircle(ink.copy(alpha = 0.10f + 0.12f * pulse), radius = size.width * 0.62f, center = Offset(size.width / 2f, size.height / 2f))
        }
        for (i in 0 until 9) {
            val r = i / 3; val col = i % 3
            val order = (2 - r) * 3 + col // fills from the bottom
            val on = order < lit
            val a = when {
                status == TaskStatus.COMPLETED -> 0.82f + 0.18f * kotlin.math.sin((wave * 2f - order / 9f) * 2f * PI.toFloat()).let { (it + 1f) / 2f }
                on && status == TaskStatus.IN_PROGRESS -> pulse
                status == TaskStatus.IN_PROGRESS -> {
                    // the pixels just above the lit ones flicker upward one after another – "trying to fill"
                    val ahead = order - lit
                    if (ahead in 0..2) 0.12f + 0.55f * ((1f - ((wave + ahead * 0.28f) % 1f))).let { it * it } else 0.10f
                }
                else -> 0.10f + 0.20f * ((sin((wave - order / 9f) * 2f * PI.toFloat()) + 1f) / 2f)
            }
            drawRect(ink.copy(alpha = a.coerceIn(0.05f, 1f)), Offset(col * cell + gap, r * cell + gap), Size(cell - gap * 2, cell - gap * 2))
        }
    }
}

/** (i) button that explains the day's score and the commitment points – used on the Tasks screen and in the reports. */
@Composable
internal fun CommitmentInfoButton() {
    var show by remember { mutableStateOf(false) }
    IconButton(onClick = { show = true }) { Icon(Icons.Rounded.Info, "About efficiency and commitment points", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    if (show) androidx.compose.material3.AlertDialog(
        onDismissRequest = { show = false },
        title = { Text("How the day is scored") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Each planned task has a weight: Difficulty × Priority × Type (max 6.5).", fontWeight = FontWeight.SemiBold)
                Text("• Difficulty: Easy 1 · Normal 2 · Hard 3.5\n• Priority: Low 0.8 · Normal 1 · High 1.3\n• Type: due-date / one-time 1.4 · daily 0.6 · weekly 1")
                Text("Credit: Not started 0 % · In progress 50 % · Done 100 %.")
                Text("Up to 85 points come from tasks: your credited weight against the planned weight (at least 8 units a day). Unfinished tasks always stay in the plan.")
                Text("Commitment points – up to 15", fontWeight = FontWeight.SemiBold)
                Text("Four stars share these 15 points (3.75 each):\n• Tough Commitment: a star you give yourself only when you did something truly great against your own laziness (normally 0–2, a third or fourth only on an exceptional day).\n• Consistency: earned by itself – 1 star after 3 confirmed days in a row with at least 60 % done, 2 stars after 7.")
                Text("Optional tasks are bonus work and are not scored. A day with no tasks is N/A, not 100 %. The score always follows your tasks as they are right now.")
            }
        },
        confirmButton = { TextButton(onClick = { show = false }) { Text("Got it") } }
    )
}

@Composable
private fun StatusPill(status: TaskStatus, highlighted: Boolean = false) {
    val c = MaterialTheme.colorScheme
    val label = when (status) { TaskStatus.NOT_STARTED -> "Not started"; TaskStatus.IN_PROGRESS -> "In progress"; else -> "Done" }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(c.onSurface.copy(alpha = if (highlighted) 0.20f else 0.11f)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot(status)
        Spacer(Modifier.width(7.dp))
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StatusDot(status: TaskStatus) {
    val c = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(16.dp)) {
        val r = size.minDimension / 2f
        when (status) {
            TaskStatus.NOT_STARTED -> drawCircle(c.copy(alpha = 0.45f), r)
            TaskStatus.IN_PROGRESS -> {
                drawCircle(c, r - 0.8.dp.toPx(), style = Stroke(1.6.dp.toPx()))
                drawArc(c, 90f, 180f, true)
            }
            else -> drawCircle(c, r)
        }
    }
}

// ------------------------------------------------------------------------------------------------ sheets
@Composable
private fun TaskActionSheet(
    task: TaskEntity, onDismiss: () -> Unit, onStatus: (TaskStatus) -> Unit, onEdit: () -> Unit,
    onDeleteToday: () -> Unit, onDeleteForever: () -> Unit
) {
    val c = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 22.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.size(40.dp))
                Text(task.title, Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp)
                IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, "Edit details") }
            }
            Spacer(Modifier.height(4.dp))
            listOf(
                Triple("Work to do", TaskStatus.NOT_STARTED, 0),
                Triple("Started work but still incomplete", TaskStatus.IN_PROGRESS, 1),
                Triple("Work Completed", TaskStatus.COMPLETED, 2)
            ).forEach { (label, status, _) ->
                Text(label, fontSize = 13.sp, letterSpacing = 0.9.sp, modifier = Modifier.padding(top = 6.dp))
                val chosen = task.status == status
                Surface(
                    shape = RoundedCornerShape(10.dp), color = c.surfaceVariant,
                    border = if (chosen) BorderStroke(1.5.dp, c.onSurface) else null,
                    modifier = Modifier.fillMaxWidth().bounceClick { onStatus(status) }
                ) { Box(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) { StatusPill(status, highlighted = chosen) } }
            }
            Spacer(Modifier.height(6.dp))
            if (task.daily) {
                DeleteRow("Delete Habit for Today", onDeleteToday)
                DeleteRow("Delete Habit from Now onwards", onDeleteForever)
            } else {
                DeleteRow("Delete Task", onDeleteForever)
            }
        }
    }
}

@Composable
private fun DeleteRow(label: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(10.dp), color = c.surfaceVariant, modifier = Modifier.fillMaxWidth().bounceClick(onClick = onClick)) {
        Text(label, Modifier.padding(horizontal = 16.dp, vertical = 14.dp), color = c.error, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

/** New task – the advanced options (daily, tough/priority, due date) are right there from the start. */
@Composable
private fun AddTaskSheet(onDismiss: () -> Unit, onAdd: (String, Boolean, Int, Long?, Boolean, Int, Int, Boolean, List<Long>) -> Unit) {
    val c = MaterialTheme.colorScheme
    var timing by remember { mutableStateOf(Timing.start()) }
    var title by remember { mutableStateOf("") }
    var priority by remember { mutableIntStateOf(1) }
    var difficulty by remember { mutableIntStateOf(1) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("New task", fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            TextField(
                value = title, onValueChange = { title = it }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                placeholder = { Text("What needs doing?") }, singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = c.surfaceVariant, unfocusedContainerColor = c.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                )
            )
            Text("Difficulty  ·  Easy 1 · Normal 2 · Hard 3.5", fontSize = 13.sp, letterSpacing = 0.6.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Easy", 1 to "Normal", 2 to "Hard").forEach { (d, l) -> FilterChip(selected = difficulty == d, onClick = { difficulty = d }, label = { Text(l) }) }
            }
            Text("Priority  ·  Low 0.8 · Normal 1 · High 1.3", fontSize = 13.sp, letterSpacing = 0.6.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Low", 1 to "Normal", 2 to "High").forEach { (p, l) -> FilterChip(selected = priority == p, onClick = { priority = p }, label = { Text(l) }) }
            }
            TimingSection(timing) { timing = it }
            Button(onClick = { val r = timing.resolve(); onAdd(title, r.daily, priority, r.dueDay, false, r.repeatMask, difficulty, false, r.dueDays) }, enabled = title.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Add task") }
        }
    }
}

@Composable
private fun TaskEditSheet(task: TaskEntity, onDismiss: () -> Unit, onSave: (TaskEntity) -> Unit, onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var title by remember(task.id) { mutableStateOf(task.title) }
    var status by remember(task.id) { mutableStateOf(task.status) }
    var timing by remember(task.id) { mutableStateOf(Timing.of(task)) }
    var priority by remember(task.id) { mutableIntStateOf(task.priority) }
    var difficulty by remember(task.id) { mutableIntStateOf(task.difficulty) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.surface) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Edit task", style = MaterialTheme.typography.titleLarge)
            TextField(
                value = title, onValueChange = { title = it }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colors.surfaceVariant, unfocusedContainerColor = colors.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                )
            )
            Text("Status", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(TaskStatus.NOT_STARTED to "Not started", TaskStatus.IN_PROGRESS to "In progress", TaskStatus.COMPLETED to "Completed").forEach { (s, l) ->
                    FilterChip(selected = status == s, onClick = { status = s }, label = { Text(l) })
                }
            }
            Text("Difficulty", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Easy", 1 to "Normal", 2 to "Hard").forEach { (d, l) -> FilterChip(selected = difficulty == d, onClick = { difficulty = d }, label = { Text(l) }) }
            }
            Text("Priority", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Low", 1 to "Normal", 2 to "High").forEach { (p, l) ->
                    FilterChip(selected = priority == p, onClick = { priority = p }, label = { Text(l) })
                }
            }
            TimingSection(timing) { timing = it }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) { Text("Cancel task", color = colors.error) }
                Button(
                    onClick = {
                        val r = timing.resolve()
                        onSave(task.copy(
                            title = title.trim().ifBlank { task.title }, status = status, daily = r.daily, repeatMask = r.repeatMask, priority = priority,
                            dueDay = r.dueDay, dueDays = if (r.dueDays.size > 1) r.dueDays.joinToString(",") else "", difficulty = difficulty
                        ))
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Save") }
            }
        }
    }
}

@Composable
private fun Modifier.horizontalScrollCompat(): Modifier =
    this.then(Modifier.horizontalScroll(rememberScrollState()))
