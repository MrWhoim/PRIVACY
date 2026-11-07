@file:OptIn(ExperimentalMaterial3Api::class)

package com.privacy.app.ui.reports

import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privacy.app.data.Efficiency
import com.privacy.app.data.HealthEntryEntity
import com.privacy.app.data.ReportAi
import com.privacy.app.data.ReportEngine
import com.privacy.app.data.TaskStatus
import com.privacy.app.data.fmt
import com.privacy.app.ui.MainViewModel
import com.privacy.app.ui.MonoLabel
import com.privacy.app.ui.ScreenBackground
import com.privacy.app.ui.health.RingsRow
import com.privacy.app.ui.tasks.CommitmentInfoButton
import com.privacy.app.ui.tasks.PixelProgressBar
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

private enum class Mode { DAY, WEEK, MONTH }

private class DayBundle(val day: ReportEngine.CombinedDay, val prevAvg: Float?, val prevN: Int)
private class PeriodBundle(val stats: ReportEngine.WeekStats, val prev: ReportEngine.WeekStats, val compare: List<ReportEngine.Compare>)
private class AiState { var text by mutableStateOf<String?>(null); var busy by mutableStateOf(false) }
private class RCtx(val vm: MainViewModel, val ai: AiState, val insights: List<ReportEngine.Insight>, val call: String, val analyse: () -> Unit)

private fun dayLabel(d: Long, pattern: String = "EEE d MMM") = LocalDate.ofEpochDay(d).format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))

/**
 * Reports: Task | Health | Overall (swipe or tap). The date button opens the calendar, where Day / Week / Month is chosen.
 * Everything is read from what was saved for the period – nothing is invented, and a day with nothing recorded is "no data", not 0.
 */
@Composable
fun ReportsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val engine = vm.g.reports
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val today = remember { LocalDate.now().toEpochDay() }
    var modeName by rememberSaveable { mutableStateOf(Mode.DAY.name) }
    var date by rememberSaveable { mutableStateOf(today) }
    var showCal by remember { mutableStateOf(false) }
    var lastModeIdx by remember { mutableStateOf(0) }
    var showShare by remember { mutableStateOf(false) }
    val pager = androidx.compose.foundation.pager.rememberPagerState { 3 }
    com.privacy.app.ui.TabSwipeGuard(pager)
    val ai = remember { AiState() }
    val mode = Mode.valueOf(modeName)
    val tick by vm.dataTick.collectAsStateWithLifecycle(0L)
    val call by vm.g.settings.userCall.collectAsStateWithLifecycle()
    val ld = LocalDate.ofEpochDay(date)
    val pStart = when (mode) { Mode.DAY -> date; Mode.WEEK -> engine.weekStart(ld).toEpochDay(); Mode.MONTH -> ld.withDayOfMonth(1).toEpochDay() }
    val pLen = when (mode) { Mode.DAY -> 1; Mode.WEEK -> 7; Mode.MONTH -> ld.lengthOfMonth() }

    val dayData by produceState<DayBundle?>(null, date, tick, modeName) {
        if (mode != Mode.DAY) { value = null; return@produceState }
        val d = engine.combinedDay(date)
        val prev = (1..7).map { engine.effDay(date - it) }.filter { it.eligible }
        value = DayBundle(d, if (prev.isEmpty()) null else prev.map { it.result.score }.average().toFloat(), prev.size)
    }
    val taskTl by produceState<ReportEngine.Timeline?>(null, date, tick, modeName) { value = if (mode == Mode.DAY) engine.taskTimeline(date) else null }
    val healthTl by produceState<ReportEngine.Timeline?>(null, date, tick, modeName) { value = if (mode == Mode.DAY) engine.healthTimeline(date) else null }
    val periodData by produceState<PeriodBundle?>(null, pStart, tick, modeName) {
        if (mode == Mode.DAY) { value = null; return@produceState }
        val w = engine.period(pStart, pLen)
        val pm = if (mode == Mode.MONTH) YearMonth.from(ld).minusMonths(1) else null
        val prev = if (pm != null) engine.period(pm.atDay(1).toEpochDay(), pm.lengthOfMonth()) else engine.period(pStart - 7, 7)
        value = PeriodBundle(w, prev, engine.compare(w, prev))
    }
    val insights by produceState(emptyList<ReportEngine.Insight>(), date, modeName, tick) { value = engine.insights(minOf(today, pStart + pLen - 1)) }

    fun shift(dir: Int) {
        date = when (mode) { Mode.DAY -> date + dir; Mode.WEEK -> date + 7L * dir; Mode.MONTH -> ld.plusMonths(dir.toLong()).toEpochDay() }
        ai.text = null
    }
    fun periodTitle(): String = when (mode) {
        Mode.DAY -> dayLabel(date, "d MMM yyyy")
        Mode.WEEK -> "Week of " + dayLabel(pStart, "d") + " – " + dayLabel(pStart + 6, "d MMM")
        Mode.MONTH -> ld.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + ld.year
    }
    fun share() {
        val t = StringBuilder("P.R.I.V.A.C.Y. report · ${periodTitle()}\n")
        val d = dayData; val p = periodData
        if (d != null) {
            t.append("Efficiency: ${pct(d.day.eff.shownScore)}\nHealth: ${pct(d.day.health.score)}\n")
            d.day.focus?.let { t.append("Focus: ${pct(it)}\n") }
            d.day.combined?.let { t.append("Combined: ${pct(it)}\n") }
            vm.g.reports.takeaways(d.day).forEach { t.append("• $it\n") }
        } else if (p != null) {
            t.append("Efficiency average: ${pct(p.stats.effMean)} (${p.stats.effDays} of $pLen days)\nHealth average: ${pct(p.stats.healthMean)} (${p.stats.healthDays} of $pLen days)\n")
            p.stats.focusMean?.let { t.append("Focus average: ${pct(it)} (${p.stats.focusDays} of $pLen days)\n") }
            p.stats.combinedMean?.let { t.append("Combined: ${pct(it)}\n") }
        }
        insights.take(3).forEach { t.append("• ${it.observed} Try: ${it.action}\n") }
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t.toString()), "Share report"))
    }
    val x = RCtx(vm, ai, insights, call) {
        ai.busy = true; ai.text = null
        scope.launch {
            val end = minOf(today, pStart + pLen - 1)
            val start = maxOf(pStart, end - 30)
            ai.text = ReportAi.analyse(vm.g, engine.facts(start, end), insights.joinToString("\n") { "- ${it.observed} ${it.evidence}" })
            ai.busy = false
        }
    }

    ScreenBackground {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp, start = 18.dp, end = 18.dp)) {
                com.privacy.app.ui.MenuButton(Modifier.align(Alignment.CenterStart))
                Text("Report", Modifier.align(Alignment.Center), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = { showShare = true }, modifier = Modifier.align(Alignment.CenterEnd)) { Icon(Icons.Rounded.Share, "Share report") }
            }
            com.privacy.app.ui.SegmentedTabs(listOf("Task", "Health", "Overall"), pager.currentPage, { scope.launch { pager.animateScrollToPage(it) } }, Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { shift(-1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous") }
                Surface(shape = RoundedCornerShape(50), color = c.surfaceVariant, modifier = Modifier.weight(1f).clickable { showCal = true }) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Text(periodTitle(), fontWeight = FontWeight.Medium, fontSize = 15.sp, maxLines = 1)
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Rounded.CalendarMonth, "Open calendar", Modifier.size(18.dp))
                    }
                }
                IconButton(onClick = { shift(1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next") }
            }
            if (date != today && !(mode != Mode.DAY && today in pStart until pStart + pLen)) TextButton(onClick = { date = today }, Modifier.align(Alignment.CenterHorizontally)) { Text("Back to today") }
            androidx.compose.animation.AnimatedContent(
                targetState = mode, modifier = Modifier.weight(1f).fillMaxWidth(), label = "reportMode",
                transitionSpec = {
                    val dir = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                    (androidx.compose.animation.slideInHorizontally { it * dir } + androidx.compose.animation.fadeIn()) togetherWith
                        (androidx.compose.animation.slideOutHorizontally { -it * dir } + androidx.compose.animation.fadeOut())
                }
            ) { _ ->
            androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    val d = dayData; val p = periodData
                    val loading = if (mode == Mode.DAY) d == null else p == null
                    if (loading) item { MonoLabel("Loading…") } else when (page) {
                        0 -> if (d != null) taskDay(x, d, taskTl) else if (p != null) taskPeriod(x, p, pLen, pStart)
                        1 -> if (d != null) healthDay(x, d, healthTl) else if (p != null) healthPeriod(x, p, pLen, pStart)
                        else -> if (d != null) combinedDay(x, d, taskTl, healthTl) else if (p != null) combinedPeriod(x, p, pLen, pStart)
                    }
                }
            }
            }
        }
    }

    if (showShare) ReportShareDialog(
        periodTitle(), dayData?.day, periodData?.stats,
        dayData?.let { vm.g.reports.takeaways(it.day) } ?: insights.take(3).map { it.observed + " Try: " + it.action },
        ai.text, call, onDismiss = { showShare = false }
    )
    if (showCal) ReportCalendarSheet(vm, mode, date, onMode = { i -> if (Mode.values()[i].name != modeName) { lastModeIdx = mode.ordinal; modeName = Mode.values()[i].name; ai.text = null } }, onDismiss = { showCal = false }) { d -> date = d; ai.text = null; showCal = false }
}

/** The calendar follows the chosen tab: Day = days of a month, Week = the weeks of a year, Month = the months of a year. */
@Composable
private fun ReportCalendarSheet(vm: MainViewModel, mode: Mode, selected: Long, onMode: (Int) -> Unit, onDismiss: () -> Unit, onApply: (Long) -> Unit) {
    val scroll = androidx.compose.foundation.rememberScrollState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var vpTop by remember { mutableStateOf(0f) }
    var vpH by remember { mutableStateOf(0) }
    // the weeks list opens with the current week in the middle of the screen
    val centerOn: (Float, Int) -> Unit = { rowY, rowH ->
        scope.launch {
            kotlinx.coroutines.delay(90)
            val target = (rowY - vpTop + scroll.value - vpH / 2f + rowH / 2f).toInt().coerceIn(0, scroll.maxValue.coerceAtLeast(0))
            scroll.animateScrollTo(target)
        }
    }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        // the Day / Week / Month switch stays at the top; only the calendar below it scrolls
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
            com.privacy.app.ui.SegmentedTabs(listOf("Day", "Week", "Month"), mode.ordinal, onMode, Modifier.padding(bottom = 12.dp))
        Column(
            Modifier.fillMaxWidth().weight(1f, fill = false)
                .androidx_onSize { vpH = it }
                .androidx_onPos { vpTop = it }
                .verticalScroll(scroll).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // switching Day / Week / Month slides the calendar sideways
            androidx.compose.animation.AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(280)) { w -> dir * w / 2 } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(280))) togetherWith
                        (androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(280)) { w -> -dir * w / 2 } + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)))
                },
                label = "calMode"
            ) { m ->
                when (m) {
                    Mode.DAY -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("Tap a day to open its report.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); MonthCalendar(vm, selected) { onApply(it) } }
                    else -> YearGrid(vm, m == Mode.WEEK, selected, onApply, centerOn)
                }
            }
        }
        }
    }
}

private fun Modifier.androidx_onSize(f: (Int) -> Unit): Modifier = this.then(Modifier.onSizeChanged { f(it.height) })
private fun Modifier.androidx_onPos(f: (Float) -> Unit): Modifier = this.then(Modifier.onGloballyPositioned { f(it.positionInRoot().y) })

/** Weeks of a year (4 per row) or months of a year (3 per row), each with its average Efficiency (E) and Health (H). */
@Composable
private fun YearGrid(vm: MainViewModel, weekly: Boolean, selected: Long, onPick: (Long) -> Unit, centerOn: (Float, Int) -> Unit = { _, _ -> }) {
    val c = MaterialTheme.colorScheme
    var year by remember { mutableStateOf(LocalDate.ofEpochDay(selected).year) }
    val tick by vm.dataTick.collectAsStateWithLifecycle(0L)
    val today = LocalDate.now().toEpochDay()
    class Item(val label: String, val start: Long, val sub: String, val eff: Float?, val health: Float?)
    val items by produceState(emptyList<Item>(), year, weekly, tick) {
        val y0 = LocalDate.of(year, 1, 1); val y1 = LocalDate.of(year, 12, 31)
        val from = y0.minusDays(7).toEpochDay(); val to = y1.plusDays(7).toEpochDay()
        val scores = vm.g.db.scoreDao().range(from, to).filter { it.isFinal && it.score >= 0f }.associate { it.day to it.score }
        val hs = vm.g.db.healthDao().dayRows(from, to).filter { it.confirmed && it.score >= 0f }.associate { it.day to it.score }
        fun avg(a: Long, b: Long, m: Map<Long, Float>): Float? = (a..b).mapNotNull { m[it] }.let { if (it.isEmpty()) null else it.average().toFloat() }
        value = if (weekly) {
            val first = LocalDate.of(year, 1, 4).with(java.time.DayOfWeek.MONDAY) // Monday of ISO week 1
            generateSequence(first) { it.plusWeeks(1) }.takeWhile { it.plusDays(3).year == year }.mapIndexed { i, st ->
                val a = st.toEpochDay()
                Item("W${i + 1}", a, st.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())), avg(a, a + 6, scores), avg(a, a + 6, hs))
            }.toList()
        } else (1..12).map { m ->
            val ym = YearMonth.of(year, m); val a = ym.atDay(1).toEpochDay()
            Item(ym.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()), a, "", avg(a, ym.atEndOfMonth().toEpochDay(), scores), avg(a, ym.atEndOfMonth().toEpochDay(), hs))
        }
    }
    Section("") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { year-- }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous year") }
            Text("$year", Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
            IconButton(onClick = { year++ }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next year") }
        }
        val perRow = if (weekly) 4 else 3
        items.chunked(perRow).forEach { row ->
            val hasSel = weekly && row.any { selected in it.start until it.start + 7 } && year == LocalDate.ofEpochDay(selected).year
            var centered by remember(weekly, year) { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp).then(
                    if (hasSel) Modifier.onGloballyPositioned { if (!centered) { centered = true; centerOn(it.positionInRoot().y, it.size.height) } } else Modifier
                ),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                row.forEach { it2 ->
                    val len = if (weekly) 7L else YearMonth.from(LocalDate.ofEpochDay(it2.start)).lengthOfMonth().toLong()
                    val isSel = selected in it2.start until it2.start + len
                    val future = it2.start > today
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                            .then(if (isSel) Modifier.border(1.5.dp, c.onSurface, RoundedCornerShape(10.dp)) else Modifier.border(1.dp, c.outlineVariant, RoundedCornerShape(10.dp)))
                            .clickable(enabled = !future) { onPick(it2.start) }.padding(vertical = 8.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(it2.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface.copy(alpha = if (future) 0.3f else 1f))
                        if (it2.sub.isNotBlank()) Text(it2.sub, fontSize = 9.sp, color = c.onSurfaceVariant)
                        com.privacy.app.ui.tasks.MiniPixelBar((it2.eff ?: 0f) / 100f, Modifier.padding(top = 4.dp), cells = 5, dim = it2.eff == null)
                        Text(it2.eff?.let { "E${it.toInt()}" } ?: " ", fontSize = 9.sp, color = c.onSurface.copy(alpha = 0.85f))
                        Text(it2.health?.let { "H${it.toInt()}" } ?: " ", fontSize = 9.sp, color = c.onSurface.copy(alpha = 0.7f))
                    }
                }
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Text("E = average Efficiency, H = average Health. Empty = nothing recorded.", fontSize = 11.sp, color = c.onSurfaceVariant)
    }
}

// ============================================================================================ building blocks
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(16.dp), color = c.surfaceVariant.copy(alpha = 0.55f), border = BorderStroke(1.dp, c.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (title.isNotBlank()) Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

/** The big score with the app's pixel grid beside it (reports 8–10 of the reference). */
@Composable
private fun ScoreBox(label: String, score: Float?, tag: String?, sub: String? = null) {
    val c = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(14.dp), color = c.surfaceVariant.copy(alpha = 0.4f), border = BorderStroke(1.dp, c.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (score == null) "—" else String.format(Locale.US, "%.1f%%", score), fontSize = 38.sp, fontWeight = FontWeight.Bold)
                Text(label, fontSize = 13.sp, color = c.onSurfaceVariant)
                tag?.let { Tag(it, strong = it == "Final") }
                sub?.let { Text(it, fontSize = 11.sp, color = c.onSurfaceVariant) }
            }
            Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(0.8f)) { PixelProgressBar((score ?: 0f) / 100f, cols = 10, rows = 7, strength = 0.7f, alive = false) }
        }
    }
}

@Composable
private fun ThinBar(fraction: Float, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        drawRoundRect(ink.copy(alpha = 0.10f), Offset.Zero, size, CornerRadius(4f))
        drawRoundRect(ink.copy(alpha = 0.85f), Offset.Zero, Size(size.width * fraction.coerceIn(0f, 1f), size.height), CornerRadius(4f))
    }
}

@Composable
private fun MiniScore(label: String, glyph: String, score: Float?, sub: String?, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(14.dp), color = c.surfaceVariant.copy(alpha = 0.4f), border = BorderStroke(1.dp, c.outlineVariant), modifier = modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { PixelGlyph(glyph, size = 18); Spacer(Modifier.width(8.dp)); Text(label, fontSize = 13.sp, color = c.onSurfaceVariant) }
            Text(if (score == null) "—" else String.format(Locale.US, "%.1f%%", score), fontSize = 28.sp, fontWeight = FontWeight.Bold)
            ThinBar((score ?: 0f) / 100f)
            sub?.let { Text(it, fontSize = 11.sp, color = c.onSurfaceVariant) }
        }
    }
}

@Composable
private fun StatTable(rows: List<Pair<String, String>>) {
    val c = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(12.dp), color = c.surfaceVariant.copy(alpha = 0.3f), border = BorderStroke(1.dp, c.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column {
            rows.forEachIndexed { i, (k, v) ->
                if (i > 0) HorizontalDivider(color = c.outlineVariant)
                val last = i == rows.lastIndex
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp)) {
                    Text(k, Modifier.weight(1f), fontSize = 14.sp, fontWeight = if (last) FontWeight.Bold else FontWeight.Normal)
                    Text(v, fontSize = 14.sp, fontWeight = if (last) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun CountBar(label: String, n: Int, total: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(96.dp), fontSize = 13.sp)
        Box(Modifier.weight(1f)) { ThinBar(if (total == 0) 0f else n.toFloat() / total) }
        Text(n.toString(), Modifier.width(30.dp), fontSize = 13.sp, textAlign = TextAlign.End)
    }
}

@Composable
private fun InsightCard(i: ReportEngine.Insight) {
    val c = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Tag(i.domain)
        Text(i.observed, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text("Evidence: " + i.evidence, fontSize = 12.sp, color = c.onSurfaceVariant)
        i.contributor?.let { Text("Possible contributor: $it", fontSize = 12.sp, color = c.onSurfaceVariant) }
        Text("Try: " + i.action, fontSize = 13.sp)
        Text("Watch: " + i.signal, fontSize = 12.sp, color = c.onSurfaceVariant)
    }
}

/** The pixel assistant in a box under the main percentage: tap it and it opens the analysis (rule-based suggestions, then the optional written AI analysis). */
@Composable
private fun AssistantBox(x: RCtx, domains: Set<String>, topic: String) {
    val c = MaterialTheme.colorScheme
    var open by rememberSaveable(topic) { mutableStateOf(false) }
    val list = x.insights.filter { it.domain in domains }.take(3)
    Surface(shape = RoundedCornerShape(28.dp), color = c.surfaceVariant.copy(alpha = 0.6f), modifier = Modifier.fillMaxWidth().clickable { open = !open }) {
        Column {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                com.privacy.app.ui.face.PixelAvatar(size = 84.dp, color = c.onSurface, assemble = false, fps = 12)
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text("Can I give you some analysis, ${x.call}?", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Text(if (open) "Tap me again to close" else "Tap me – I'll look at your $topic", fontSize = 13.sp, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (open) Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (list.isEmpty()) Text("Nothing stands out yet. Suggestions appear when your saved days show a pattern – each one lists the days behind it.", fontSize = 13.sp)
                list.forEachIndexed { i, ins -> if (i > 0) HorizontalDivider(color = c.outlineVariant); InsightCard(ins) }
                HorizontalDivider(color = c.outlineVariant)
                Text("I can also read the saved records of this period and write a short, cautious analysis. I never change your goals, diets or tasks.", fontSize = 12.sp, color = c.onSurfaceVariant)
                OutlinedButton(enabled = !x.ai.busy, onClick = x.analyse) { Text(if (x.ai.busy) "Reading your records…" else "Yes, analyse") }
                x.ai.text?.let { Text(it, fontSize = 14.sp, lineHeight = 20.sp) }
            }
        }
    }
}

@Composable
private fun TimelineSection(title: String, tl: ReportEngine.Timeline?, series2: ReportEngine.Timeline? = null, label1: String = "", label2: String = "") {
    if (tl == null && series2 == null) return
    val c = MaterialTheme.colorScheme
    Section(title) {
        val all = (tl?.points ?: emptyList()) + (series2?.points ?: emptyList())
        if (all.isEmpty()) Text((tl?.note ?: series2?.note) ?: "", fontSize = 13.sp, color = c.onSurfaceVariant)
        else {
            val lo = (kotlin.math.floor(all.minOf { it.x }.toDouble()).toFloat() - 1f).coerceAtLeast(0f)
            val hi = (kotlin.math.ceil(all.maxOf { it.x }.toDouble()).toFloat() + 1f).coerceAtMost(24f).coerceAtLeast(lo + 4f)
            CurveChart(
                listOfNotNull(tl?.takeIf { it.points.isNotEmpty() }?.let { ChartSeries(it.points.map { p -> ChartPt(p.x, p.y) }) },
                    series2?.takeIf { it.points.isNotEmpty() }?.let { ChartSeries(it.points.map { p -> ChartPt(p.x, p.y) }, 0.55f, true) }),
                lo, hi, { hourLabel(it) }
            )
            if (label1.isNotBlank()) Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("● $label1", fontSize = 11.sp); Text("■ $label2 (dashed)", fontSize = 11.sp, color = c.onSurfaceVariant)
            }
            Text("What each entry added", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            ImpactList((tl?.points ?: emptyList()).takeLast(8).map { Triple(hourLabel(it.x), it.label, it.delta) })
            series2?.let { ImpactList(it.points.takeLast(8).map { p -> Triple(hourLabel(p.x), p.label, p.delta) }) }
            val note = listOfNotNull(tl?.note, series2?.note).filter { it.isNotBlank() }.joinToString(" ")
            if (note.isNotBlank()) Text(note, fontSize = 11.sp, color = c.onSurfaceVariant)
        }
    }
}

/** Two daily-score curves across the days of a week / month (gaps stay gaps). */
@Composable
private fun PeriodCurve(w: ReportEngine.WeekStats, pStart: Long, eff: Boolean, health: Boolean) {
    val c = MaterialTheme.colorScheme
    val e = if (eff) w.days.mapIndexedNotNull { i, d -> d.eff.shownScore?.takeIf { d.eff.eligible }?.let { ChartPt(i.toFloat(), it) } } else emptyList()
    val h = if (health) w.days.mapIndexedNotNull { i, d -> d.health.score?.takeIf { d.health.hasData }?.let { ChartPt(i.toFloat(), it) } } else emptyList()
    Section("Daily curve") {
        if (e.isEmpty() && h.isEmpty()) Text("No scored days in this period yet.", fontSize = 13.sp, color = c.onSurfaceVariant)
        else {
            CurveChart(listOfNotNull(e.takeIf { it.isNotEmpty() }?.let { ChartSeries(it) }, h.takeIf { it.isNotEmpty() }?.let { ChartSeries(it, if (eff) 0.55f else 0.85f, eff) }), 0f, (w.days.size - 1).coerceAtLeast(1).toFloat(), { dayLabel(pStart + it.toLong(), "d MMM") })
            if (eff && health) Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { Text("● Efficiency", fontSize = 11.sp); Text("■ Health (dashed)", fontSize = 11.sp, color = c.onSurfaceVariant) }
            Text("Each dot is one day with data; days without entries are gaps, not zeros.", fontSize = 11.sp, color = c.onSurfaceVariant)
        }
    }
}

private fun nextStatus(s: TaskStatus) = when (s) { TaskStatus.NOT_STARTED -> TaskStatus.IN_PROGRESS; TaskStatus.IN_PROGRESS -> TaskStatus.COMPLETED; else -> TaskStatus.NOT_STARTED }
private fun statusText(s: TaskStatus) = when (s) { TaskStatus.NOT_STARTED -> "Not started"; TaskStatus.IN_PROGRESS -> "In progress"; else -> "Done" }

private fun labelsFor(w: ReportEngine.WeekStats): List<String> =
    w.days.mapIndexed { i, d -> if (w.days.size <= 7 || i % 5 == 0) dayLabel(d.day, "d") else "" }

private fun bestWorst(items: List<Pair<Long, Float>>): Pair<Pair<Long, Float>?, Pair<Long, Float>?> =
    if (items.isEmpty()) null to null else items.maxByOrNull { it.second } to items.minByOrNull { it.second }

@Composable
private fun BestWorst(items: List<Pair<Long, Float>>) {
    val (best, worst) = bestWorst(items)
    if (best == null || worst == null) { Text("Best / worst day: needs at least one confirmed day.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); return }
    Row(Modifier.fillMaxWidth()) { Text("Best day", Modifier.weight(1f), fontSize = 14.sp); Text(String.format(Locale.US, "%.1f%% (%s)", best.second, dayLabel(best.first, "d MMM")), fontSize = 14.sp, fontWeight = FontWeight.Medium) }
    Row(Modifier.fillMaxWidth()) { Text("Worst day", Modifier.weight(1f), fontSize = 14.sp); Text(String.format(Locale.US, "%.1f%% (%s)", worst.second, dayLabel(worst.first, "d MMM")), fontSize = 14.sp, fontWeight = FontWeight.Medium) }
}

// ============================================================================================ TASK · day
private fun LazyListScope.taskDay(x: RCtx, b: DayBundle, tl: ReportEngine.Timeline?) {
    val vm = x.vm
    val e = b.day.eff
    val r = e.result
    item { ScoreBox("Efficiency of this day", e.shownScore, if (!e.hasData) "No data" else null) }
    item { AssistantBox(x, setOf("Efficiency", "Combined"), "tasks") }
    item { TimelineSection("Task impact through the day", tl) }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!e.hasData) Text("Nothing was planned or recorded on this day. It is shown as no data – not as 0 %.", fontSize = 13.sp)
            if (r.applicable) {
                Text("${r.done} / ${r.planned} done" + if (r.stars > 0) " · +${r.stars} bonus" else "", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (r.applicable) {
        item {
            StatTable(
                listOf(
                    "Planned weight" to String.format(Locale.US, "%.1f", r.plannedWeight),
                    "Credited weight" to String.format(Locale.US, "%.1f", r.creditedWeight),
                    "Task points" to String.format(Locale.US, "%.1f / 85", r.taskPoints),
                    "Commitment stars" to String.format(Locale.US, "+%.1f  (%d ★: tough %d · consistency %d)", r.starPoints, r.stars, r.toughStars, r.consStars)
                ) + (r.cap?.let { listOf("Core-task cap" to "max ${it.toInt()}%  (core ${r.corePct?.toInt() ?: 0}% done)") } ?: emptyList()) +
                    listOf("Final score" to String.format(Locale.US, "%.1f", r.score))
            )
        }
        item {
            Section("Task breakdown") {
                CountBar("Done", r.done, r.planned); CountBar("In Progress", r.doing, r.planned); CountBar("Not Started", r.notStarted, r.planned)
                Text("Daily target: ${Efficiency.DAILY_TARGET.toInt()} weight units – a smaller plan is still divided by ${Efficiency.DAILY_TARGET.toInt()}.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (r.corePct == null) Text("No core tasks were designated, so no core cap applies.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                r.missing.forEach { Text("• $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (e.revisions > 0) Text("Recalculated ${e.revisions}× after corrections.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                b.prevAvg?.let {
                    val diff = r.score - it
                    if (b.prevN >= 3 && kotlin.math.abs(diff) >= 15f) Text(
                        String.format(Locale.US, "That is %.0f points %s than your average of %.0f%% over the previous %d confirmed days.", kotlin.math.abs(diff), if (diff > 0) "higher" else "lower", it, b.prevN),
                        fontSize = 13.sp, fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
    if (e.lines.isNotEmpty()) item {
        Section("Individual tasks · tap a status to correct it") {
            e.lines.forEach { l ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.title, fontSize = 14.sp, textDecoration = if (l.status == TaskStatus.COMPLETED) TextDecoration.LineThrough else TextDecoration.None)
                        Text(
                            (if (l.optional) "Optional · not scored" else "weight ${String.format(Locale.US, "%.1f", l.weight)} · credit ${String.format(Locale.US, "%.1f", l.credited)}") +
                                " · ${Efficiency.difficultyName(l.difficulty)} · ${Efficiency.priorityName(l.priority)} · ${Efficiency.categoryName(l.category)}" + if (l.core) " · Core" else "",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Tag(statusText(l.status), Modifier.clickable { vm.setHistoryStatus(e.day, l.key, nextStatus(l.status)) }, strong = l.status == TaskStatus.COMPLETED)
                }
            }
            Text("Correcting a task recalculates this day (and its saved score) openly.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (r.applicable) item {
        Section("By difficulty, priority and type") {
            val eng = vm.g.reports
            val bd = eng.breakdown(e.lines, { Efficiency.difficultyName(it.difficulty) }, listOf("Easy", "Normal", "Hard"))
            val bp = eng.breakdown(e.lines, { Efficiency.priorityName(it.priority) }, listOf("Low", "Normal", "High"))
            val bc = eng.breakdown(e.lines, { Efficiency.categoryName(it.category) }, listOf("Due date / one-time", "Daily", "Weekly"))
            Text("Difficulty", fontSize = 12.sp, fontWeight = FontWeight.SemiBold); bd.forEach { RatioRow(it.name, it.done, it.doing, it.tasks) }
            Text("Priority", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp)); bp.forEach { RatioRow(it.name, it.done, it.doing, it.tasks) }
            Text("Type", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp)); bc.forEach { RatioRow(it.name, it.done, it.doing, it.tasks) }
        }
    }
    if (r.applicable && e.day >= LocalDate.now().toEpochDay() && r.notStarted + r.doing > 0) item {
        val sug = remember(e.lines) { vm.g.reports.suggestOrder(e.lines, emptyMap(), e.day) }
        Section("Suggested starting order") {
            sug.forEachIndexed { i, (l, why) -> Text("${i + 1}. ${l.title} – $why", fontSize = 13.sp) }
            Text("A suggestion only – nothing on your list is changed. Work in blocks of about 45–50 minutes with a short break between.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ============================================================================================ TASK · week / month
private fun LazyListScope.taskPeriod(x: RCtx, pb: PeriodBundle, n: Int, pStart: Long) {
    val vm = x.vm
    val w = pb.stats
    item { ScoreBox("Average efficiency", w.effMean, if (w.effMean == null) "No task days" else null, "Efficiency data: ${w.effDays} of $n days") }
    item { AssistantBox(x, setOf("Efficiency", "Combined"), "tasks") }
    item { PeriodCurve(w, pStart, eff = true, health = false) }
    item {
        Section("") {
            DayBars(w.days.map { it.eff.shownScore }, w.days.map { it.eff.isFinal }, labelsFor(w))
            Text("Bars = days with tasks · dotted stub = no tasks that day.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BestWorst(w.days.filter { it.eff.eligible }.map { it.day to it.eff.result.score })
        }
    }
    item {
        Section("Planned vs credited weight") {
            PlannedCredited(w.days.map { d -> d.eff.result.takeIf { it.applicable }?.plannedWeight }, w.days.map { d -> d.eff.result.takeIf { it.applicable }?.creditedWeight }, labelsFor(w))
        }
    }
    item {
        val lines = w.days.flatMap { it.eff.lines }.filter { !it.optional }
        Section("Completion by difficulty, priority and type") {
            if (lines.isEmpty()) Text("No planned tasks in this period.", fontSize = 13.sp)
            listOf(
                "Difficulty" to (listOf("Easy", "Normal", "Hard") to { l: Efficiency.Line -> Efficiency.difficultyName(l.difficulty) }),
                "Priority" to (listOf("Low", "Normal", "High") to { l: Efficiency.Line -> Efficiency.priorityName(l.priority) }),
                "Type" to (listOf("Due date / one-time", "Daily", "Weekly") to { l: Efficiency.Line -> Efficiency.categoryName(l.category) })
            ).forEach { (title, pair) ->
                val (order, by) = pair
                val rows = order.mapNotNull { name -> lines.filter { by(it) == name }.takeIf { it.isNotEmpty() }?.let { l -> Triple(name, l.count { it.status == TaskStatus.COMPLETED }, l.count { it.status == TaskStatus.IN_PROGRESS } to l.size) } }
                if (rows.isNotEmpty()) {
                    Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                    rows.forEach { RatioRow(it.first, it.second, it.third.first, it.third.second) }
                }
            }
            val repeated = lines.groupBy { it.title.trim().lowercase() }.filter { (_, l) -> l.count { it.status != TaskStatus.COMPLETED } >= 2 }
            if (repeated.isNotEmpty()) {
                Text("Left unfinished on 2 or more days", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                repeated.values.take(5).forEach { l -> Text("• ${l.first().title} – ${l.count { it.status != TaskStatus.COMPLETED }} days", fontSize = 13.sp) }
            }
        }
    }
    item { CompareSection(pb, listOf("Efficiency")) }
}

// ============================================================================================ HEALTH · day
private fun LazyListScope.healthDay(x: RCtx, b: DayBundle, tl: ReportEngine.Timeline?) {
    val vm = x.vm
    val h = b.day.health
    item { ScoreBox("Health of this day", h.score, if (!h.hasData) "No data" else null, if (h.hasData) "H = 0.35 N + 0.25 A + 0.20 S + 0.20 W · a category you did not log counts as 0" else null) }
    item { AssistantBox(x, setOf("Health", "Combined"), "health") }
    item { RingsRow(h) }
    item { TimelineSection("Health impact through the day", tl) }
    item {
        Section("Category breakdown") {
            h.cats.forEachIndexed { i, cat ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${cat.label} · ${(cat.weight * 100).toInt()}%", Modifier.weight(1f), fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Text(cat.score?.let { String.format(Locale.US, "%.0f%%", it) } ?: "Not logged", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                if (cat.estimated) Tag("includes estimates")
                Text(cat.summary, fontSize = 13.sp)
                cat.parts.forEach { p ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(p.label, Modifier.width(84.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(p.detail, Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(p.score?.let { String.format(Locale.US, "%.0f", it) } ?: "—", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                cat.notes.forEach { Text("• $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    item {
        Section("Nutrition detail") {
            val t = h.targets
            GoalBar("Calories", h.kcal, t.kcal?.toDouble(), "kcal", estimated = h.entries.any { it.category == "FOOD" && it.estimated })
            GoalBar("Protein", h.protein, t.proteinG?.toDouble(), "g", estimated = h.entries.any { it.category == "FOOD" && it.estimated })
            GoalBar("Carbs", h.carbs, t.carbHighG?.toDouble(), "g (range ${t.carbLowG ?: "—"}–${t.carbHighG ?: "—"})", estimated = true)
            Text("Fat ${h.fat?.let { fmt(it) } ?: "not measured"} g · Fibre ${h.fiber?.let { fmt(it) } ?: "not measured"} g. Unknown nutrients are shown as not measured, never guessed.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Targets: ${t.kcalSource.ifBlank { "calories not set" }}. Confidence: ${t.confidence}. Estimates are marked “est.”.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            t.notes.forEach { Text("• $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if (h.exercises.isNotEmpty()) item {
        Section("Training") {
            Text("${h.metMin.roundToInt()} MET-min of ~${h.targets.activityMetMin.roundToInt()} benchmark · ${h.activeMinutes.roundToInt()} active min" + (h.burnedKcal?.let { " · ~${it.roundToInt()} kcal burned (estimate)" } ?: ""), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            h.exercises.forEach { l ->
                val est = l.est
                Text("${l.entry.label.replaceFirstChar { it.uppercase() }} · ${fmt(l.entry.amount ?: 0.0)} ${l.entry.unit ?: ""}" + (est?.let { " → ~${it.kcal?.roundToInt() ?: "—"} kcal, MET ${fmt(it.met)}, ${it.minutes.roundToInt()} min" } ?: " → not estimated"), fontSize = 13.sp)
                est?.stimulus?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text("Calories are estimates (MET × weight × time). Strength is a training stimulus, not exact muscle gained.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    item { EntriesSection(vm, h.entries) }
}

@Composable
private fun EntriesSection(vm: MainViewModel, entries: List<HealthEntryEntity>) {
    var edit by remember { mutableStateOf<HealthEntryEntity?>(null) }
    Section("Individual entries · tap to edit") {
        if (entries.isEmpty()) Text("No entries were saved for this day.", fontSize = 13.sp)
        entries.forEach { e ->
            Column(Modifier.fillMaxWidth().clickable { edit = e }.padding(vertical = 5.dp)) {
                Text("${e.category.lowercase().replaceFirstChar { it.uppercase() }}: ${e.label}" + (e.amount?.let { " · ${fmt(it)} ${e.unit ?: ""}" } ?: ""), fontSize = 14.sp)
                Text(
                    listOfNotNull(e.meal, e.kcal?.let { "~${it.toInt()} kcal" }, e.protein?.let { "~${fmt(it)} g protein" }, if (e.estimated) "estimate" else null, if (e.confirmed) "confirmed" else "not confirmed", "via ${e.source}").joinToString(" · "),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    edit?.let { EntryEditDialog(vm, it) { edit = null } }
}

// ============================================================================================ HEALTH · week / month
@Composable
private fun PeriodRings(w: ReportEngine.WeekStats, n: Int) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        w.cats.forEach { cm ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Ring(cm.mean)
                Spacer(Modifier.height(6.dp))
                Text(cm.label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text("${cm.days} of $n ${if (cm.key == "S") "nights" else "days"}", fontSize = 10.sp, color = c.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
}

private fun LazyListScope.healthPeriod(x: RCtx, pb: PeriodBundle, n: Int, pStart: Long) {
    val w = pb.stats
    item { ScoreBox("Average health", w.healthMean, if (w.healthMean == null) "No health days" else null, "Health data: ${w.healthDays} of $n days") }
    item { AssistantBox(x, setOf("Health", "Combined"), "health") }
    item { PeriodRings(w, n) }
    item { PeriodCurve(w, pStart, eff = false, health = true) }
    item {
        Section("") {
            DayBars(w.days.map { it.health.score }, w.days.map { it.health.isFinal }, labelsFor(w))
            Text("Bars = days with entries · dotted stub = nothing logged. A category you did not log counts as 0 in that day\u2019s score.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BestWorst(w.days.filter { it.health.isFinal && it.health.score != null }.map { it.day to it.health.score!! })
        }
    }
    item {
        val ds = w.days.map { it.health }
        fun avg(sel: (com.privacy.app.data.HealthScore.DayEval) -> Double?): Pair<Double?, Int> { val v = ds.mapNotNull(sel); return (if (v.isEmpty()) null else v.average()) to v.size }
        val kc = avg { if (it.cat("N").state == "COMPLETE") it.kcal else null }
        val pr = avg { if (it.cat("N").state == "COMPLETE") it.protein else null }
        val sl = avg { it.sleepH }
        val wa = avg { if (it.cat("W").state == "COMPLETE") it.waterMl else null }
        val t = ds.lastOrNull { it.entries.isNotEmpty() }?.targets ?: ds.last().targets
        Section("Actual vs goals (daily average)") {
            GoalBar("Calories · ${kc.second} days", kc.first, t.kcal?.toDouble(), "kcal", estimated = true)
            GoalBar("Protein · ${pr.second} days", pr.first, t.proteinG?.toDouble(), "g", estimated = true)
            GoalBar("Sleep · ${sl.second} nights", sl.first, t.sleepH, "h")
            GoalBar("Water · ${wa.second} days", wa.first, t.waterMl?.toDouble(), "ml")
            Text("Calories and protein are estimates. Goals shown are the most recent that applied in this period.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    item { CompareSection(pb, listOf("Health", "Nutrition", "Activity", "Sleep", "Hydration")) }
}

// ============================================================================================ COMBINED
private fun LazyListScope.combinedDay(x: RCtx, b: DayBundle, ttl: ReportEngine.Timeline?, htl: ReportEngine.Timeline?) {
    val vm = x.vm
    val e = b.day.eff; val h = b.day.health
    item {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MiniScore("Efficiency", "grid", e.shownScore, if (!e.hasData) "no data" else null, Modifier.weight(1f))
            MiniScore("Health", "heart", h.score, if (h.score == null) "no data" else null, Modifier.weight(1f))
            MiniScore("Focus", "focus", b.day.focus, if (b.day.focus == null) "not enough data" else null, Modifier.weight(1f))
        }
    }
    item {
        val combined = b.day.combined
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (combined != null) {
                Text(String.format(Locale.US, "%.1f%%", combined), fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text("Combined day score", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ThinBar(combined / 100f)
                Text("(Tasks + Health + Focus) ÷ 3, each counts equally. Without enough Focus data the other two are averaged. The scores stay separate and independent.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("Combined day score not calculated", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("It needs both tasks and health entries for the day (" + listOfNotNull(if (!e.eligible) "no tasks" else null, if (!h.hasData) "no health entries" else null).joinToString(" and ") + " missing).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
    item { AssistantBox(x, setOf("Efficiency", "Health", "Combined"), "day") }
    item { TimelineSection("Efficiency and Health through the day", ttl, htl, "Efficiency", "Health") }
    item {
        Section("Key takeaways") { vm.g.reports.takeaways(b.day).forEach { Text("• $it", fontSize = 14.sp) } }
    }
    item {
        Section("Side by side") {
            Text("Efficiency", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            if (e.result.applicable) {
                GoalBar("Task points (of 85)", e.result.taskPoints.toDouble(), 85.0, "pts")
                GoalBar("Star points (of 15)", e.result.starPoints.toDouble(), 15.0, "pts")
            } else Text("Not applicable – no planned tasks.", fontSize = 12.sp)
            Text("Health", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            h.cats.forEach { GoalBar(it.label + " (" + (it.weight * 100).toInt() + "%)", it.score?.toDouble(), 100.0, "/100", estimated = it.estimated) }
        }
    }
}

private fun LazyListScope.combinedPeriod(x: RCtx, pb: PeriodBundle, n: Int, pStart: Long) {
    val w = pb.stats
    item {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MiniScore("Avg. Efficiency", "grid", w.effMean, "${w.effDays} of $n days", Modifier.weight(1f))
            MiniScore("Avg. Health", "heart", w.healthMean, "${w.healthDays} of $n days", Modifier.weight(1f))
            MiniScore("Avg. Focus", "focus", w.focusMean, "${w.focusDays} of $n days", Modifier.weight(1f))
        }
    }
    item {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (w.combinedMean != null) {
                Text(String.format(Locale.US, "%.1f%%", w.combinedMean), fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text("Combined score", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ThinBar(w.combinedMean / 100f)
                Text("Average of the ${w.combinedDays} days that have both tasks and health entries (tasks + health + focus, equal weight).", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else Text("No combined score yet: it needs days with both tasks and health entries.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    item { AssistantBox(x, setOf("Efficiency", "Health", "Combined"), "week") }
    item { PeriodCurve(w, pStart, eff = true, health = true) }
    item {
        Section("Trends") {
            TwoLineChart(w.days.map { it.eff.shownScore.takeIf { _ -> it.eff.eligible } }, w.days.map { it.health.score.takeIf { _ -> it.health.isFinal } }, labelsFor(w))
            Text("Only confirmed days are drawn – missing days are gaps, not zeros.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    item { CompareSection(pb, listOf("Efficiency", "Health")) }
}

@Composable
private fun CompareSection(pb: PeriodBundle, labels: List<String>) {
    Section("Compared with the previous period") {
        pb.compare.filter { it.label in labels }.forEach { cmp ->
            Row(Modifier.fillMaxWidth()) {
                Text(cmp.label, Modifier.weight(1f), fontSize = 13.sp)
                Text(cmp.delta?.let { String.format(Locale.US, "%+.1f pts", it) } ?: "—", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
            Text(cmp.note, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ============================================================================================ month calendar
@Composable
private fun MonthCalendar(vm: MainViewModel, selected: Long, onPick: (Long) -> Unit) {
    val c = MaterialTheme.colorScheme
    var month by remember { mutableStateOf(YearMonth.from(LocalDate.ofEpochDay(selected))) }
    val tick by vm.dataTick.collectAsStateWithLifecycle(0L)
    class Cell(val eff: Float?, val health: Float?, val hasData: Boolean)
    val cells by produceState(emptyMap<Long, Cell>(), month, tick) {
        val from = month.atDay(1).toEpochDay(); val to = month.atEndOfMonth().toEpochDay()
        val scores = vm.g.db.scoreDao().range(from, to).associateBy { it.day }
        val hdays = vm.g.db.healthDao().dayRows(from, to).associateBy { it.day }
        val taskDays = vm.g.db.historyDao().range(from, to).map { it.day }.toSet()
        val healthDays = vm.g.db.healthDao().range(from, to).map { it.day }.toSet()
        value = (from..to).associateWith { d ->
            val s = scores[d]; val h = hdays[d]
            Cell(s?.takeIf { it.isFinal && it.score >= 0f }?.score, h?.takeIf { it.confirmed && it.score >= 0f }?.score, d in taskDays || d in healthDays)
        }
    }
    Section("") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous month") }
            Text(month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + month.year, Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Medium)
            IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next month") }
        }
        Row(Modifier.fillMaxWidth()) { listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 11.sp, color = c.onSurfaceVariant) } }
        val lead = month.atDay(1).dayOfWeek.value - 1
        val rows = (lead + month.lengthOfMonth() + 6) / 7
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                for (col in 0 until 7) {
                    val dayNum = r * 7 + col - lead + 1
                    if (dayNum < 1 || dayNum > month.lengthOfMonth()) { Spacer(Modifier.weight(1f)) } else {
                        val ep = month.atDay(dayNum).toEpochDay()
                        val cell = cells[ep]
                        Column(
                            Modifier.weight(1f).padding(1.dp).clip(RoundedCornerShape(8.dp))
                                .then(if (ep == selected) Modifier.border(1.5.dp, c.onSurface, RoundedCornerShape(8.dp)) else Modifier)
                                .clickable { onPick(ep) }.padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("$dayNum", fontSize = 12.sp)
                            Text(cell?.eff?.let { "E" + it.toInt() } ?: if (cell?.hasData == true) "·" else " ", fontSize = 9.sp, color = c.onSurface.copy(alpha = if (cell?.eff != null) 0.9f else 0.4f))
                            Text(cell?.health?.let { "H" + it.toInt() } ?: " ", fontSize = 9.sp, color = c.onSurface.copy(alpha = 0.7f))
                        }
                    }
                }
            }
        }
        Text("E = Efficiency, H = Health for that day. Days with nothing recorded stay blank.", fontSize = 11.sp, color = c.onSurfaceVariant)
    }
}
