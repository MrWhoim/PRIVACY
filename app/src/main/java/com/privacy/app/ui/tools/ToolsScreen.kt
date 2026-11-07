package com.privacy.app.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privacy.app.ai.Calculator
import com.privacy.app.ai.DeviceTools
import com.privacy.app.data.TaskStatus
import com.privacy.app.tools.CalendarTools
import com.privacy.app.tools.CurrencyRates
import com.privacy.app.tools.TimerState
import com.privacy.app.ui.MainViewModel
import com.privacy.app.ui.MonoLabel
import com.privacy.app.ui.ScreenBackground
import com.privacy.app.ui.ScreenHeader
import com.privacy.app.ui.SoftCard
import com.privacy.app.ui.bounceClick
import com.privacy.app.ui.theme.Mono
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

private enum class Tool(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    TIMER("Timer", Icons.Rounded.Timer), ALARM("Alarm", Icons.Rounded.Alarm), CALENDAR("Calendar", Icons.Rounded.CalendarMonth),
    CURRENCY("Currency", Icons.Rounded.CurrencyExchange), CALC("Calc", Icons.Rounded.Calculate)
}

/** Timer · Alarm · Calendar · Currency · Calculator. The assistant can use all of them too (ask it, e.g. "set a 10 minute timer"). */
@Composable
fun ToolsScreen(vm: MainViewModel, onBack: () -> Unit, startOn: String = "") {
    val tools = Tool.entries
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = tools.indexOfFirst { it.name.equals(startOn, true) }.coerceAtLeast(0)) { tools.size }
    val tool = tools[pager.currentPage]
    com.privacy.app.ui.TabSwipeGuard(pager)
    val c = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val call by vm.g.settings.userCall.collectAsStateWithLifecycle()
    var quote by remember { mutableStateOf("Tap me for a quick tip, $call.") }
    var thinking by remember { mutableStateOf(false) }
    ScreenBackground {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Tools", null, onBack = onBack)
            // the assistant lives in a box up here; it only calls the AI when you tap it
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(24.dp)).background(c.surfaceVariant)
                    .clickable(enabled = !thinking) {
                        thinking = true; quote = "Thinking…"
                        scope.launch {
                            val r = runCatching { com.privacy.app.ai.Agent(vm.g).oneShot("The user is using the ${tool.label} tool of their assistant app. Give ONE short, genuinely useful tip or idea (max 2 sentences).") }.getOrNull()
                            quote = r?.takeIf { it.error == null }?.text?.take(240) ?: "I couldn't reach the AI just now, $call."
                            thinking = false
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                com.privacy.app.ui.face.PixelAvatar(
                    80.dp, Modifier, state = if (thinking) com.privacy.app.ui.face.AvatarState.THINKING else com.privacy.app.ui.face.AvatarState.IDLE,
                    autoGestures = false, fps = 24
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(quote, fontSize = 14.sp, lineHeight = 19.sp)
                    Text("Tap me for help here", fontSize = 11.sp, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Tool.entries.forEach { t ->
                    val on = tool == t
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(if (on) c.surfaceVariant else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { scope.launch { pager.animateScrollToPage(tools.indexOf(t)) } }.padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(t.icon, t.label, tint = if (on) c.onSurface else c.onSurfaceVariant, modifier = Modifier.size(22.dp))
                        Text(t.label, fontSize = 11.sp, color = if (on) c.onSurface else c.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
            androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
                Column(
                    Modifier.fillMaxSize().graphicsLayer {
                        // pages slide, shrink a little and fade while you swipe
                        val off = kotlin.math.abs((pager.currentPage - page) + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                        alpha = 1f - 0.65f * off; scaleX = 1f - 0.1f * off; scaleY = 1f - 0.1f * off
                    }.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    when (tools[page]) {
                        Tool.TIMER -> TimerTool()
                        Tool.ALARM -> AlarmTool()
                        Tool.CALENDAR -> CalendarTool(vm)
                        Tool.CURRENCY -> CurrencyTool()
                        Tool.CALC -> CalculatorTool()
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ timer
@Composable
private fun TimerTool() {
    var stopwatch by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    com.privacy.app.ui.SegmentedTabs(listOf("Timer", "Countdown"), if (stopwatch) 0 else 1, { stopwatch = it == 0 })
    if (stopwatch) StopwatchTool() else CountdownTool()
}

/** "Start Timer": counts up from zero, with laps. */
@Composable
private fun StopwatchTool() {
    var running by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var startedAt by androidx.compose.runtime.saveable.rememberSaveable { mutableLongStateOf(0L) }
    var before by androidx.compose.runtime.saveable.rememberSaveable { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val laps = remember { androidx.compose.runtime.mutableStateListOf<Long>() }
    LaunchedEffect(running) { while (running) { now = System.currentTimeMillis(); delay(50) } }
    val total = before + if (running) (now - startedAt).coerceAtLeast(0) else 0L
    fun fmt(ms: Long) = "%02d:%02d:%02d.%02d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms / 10 % 100)
    SoftCard {
        MonoLabel("Timer")
        Text(fmt(total), fontFamily = Mono, fontSize = 40.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = {
                if (running) { before = total; running = false } else { startedAt = System.currentTimeMillis(); now = startedAt; running = true }
            }, modifier = Modifier.weight(1f)) { Text(if (running) "Stop" else if (total > 0) "Resume" else "Start Timer") }
            OutlinedButton(onClick = { if (running) laps.add(0, total) else { before = 0L; laps.clear() } }, modifier = Modifier.weight(1f), enabled = running || total > 0) { Text(if (running) "Lap" else "Reset") }
        }
        laps.forEachIndexed { i, l -> Text("Lap ${laps.size - i}  ${fmt(l)}", fontFamily = Mono, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun CountdownTool() {
    val ctx = LocalContext.current
    var end by remember { mutableLongStateOf(TimerState.endAt(ctx)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var minutes by remember { mutableStateOf("10") }
    var label by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    LaunchedEffect(end) { while (end > System.currentTimeMillis()) { now = System.currentTimeMillis(); delay(250) }; now = System.currentTimeMillis() }
    val left = ((end - now) / 1000).coerceAtLeast(0)
    SoftCard {
        MonoLabel("Countdown")
        Text(
            if (left > 0) "%02d:%02d:%02d".format(left / 3600, left % 3600 / 60, left % 60) else "00:00:00",
            fontFamily = Mono, fontSize = 44.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
        )
        if (left > 0) {
            if (TimerState.label(ctx).isNotBlank()) Text(TimerState.label(ctx), Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { TimerState.cancel(ctx); end = 0L }, modifier = Modifier.fillMaxWidth()) { Text("Cancel countdown") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 5, 10, 25, 45).forEach { m -> FilterChip(selected = minutes == "$m", onClick = { minutes = "$m" }, label = { Text("${m}m") }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(minutes, { minutes = it.filter(Char::isDigit).take(4) }, label = { Text("Minutes") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                OutlinedTextField(label, { label = it.take(40) }, label = { Text("Label (optional)") }, singleLine = true, modifier = Modifier.weight(1.4f))
            }
            Button(
                onClick = {
                    val m = minutes.toIntOrNull() ?: 0
                    if (m > 0) { msg = TimerState.start(ctx, m * 60, label); end = TimerState.endAt(ctx) }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Start Countdown") }
            if (msg.isNotBlank() && !msg.startsWith("Timer started")) Text(msg, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
        Text("The ring comes from your phone's clock app, so it sounds even when this app is closed.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ------------------------------------------------------------------ alarm
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmTool() {
    val ctx = LocalContext.current
    val now = remember { LocalTime.now() }
    val state = rememberTimePickerState(initialHour = now.hour, initialMinute = now.minute, is24Hour = false)
    var label by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    SoftCard {
        MonoLabel("Wake-up alarm")
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state) }
        OutlinedTextField(label, { label = it.take(40) }, label = { Text("Label (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { msg = DeviceTools.setAlarm(ctx, state.hour, state.minute, label.ifBlank { "P.R.I.V.A.C.Y." }) }, modifier = Modifier.fillMaxWidth()) { Text("Set alarm") }
        if (msg.isNotBlank()) Text(msg, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Alarms are created in your phone's clock app, which keeps them reliable.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ------------------------------------------------------------------ calendar
@Composable
private fun CalendarTool(vm: MainViewModel) {
    val ctx = LocalContext.current
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    var month by remember { mutableStateOf(YearMonth.now()) }
    var picked by remember { mutableStateOf(LocalDate.now()) }
    var title by remember { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    val dueByDay = remember(tasks) { tasks.filter { it.dueDay != null && it.status != TaskStatus.COMPLETED }.groupBy { it.dueDay!! } }
    // the phone's real calendar (Google calendar and others synced to the phone)
    var granted by remember { mutableStateOf(CalendarTools.hasRead(ctx)) }
    val askCal = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { granted = CalendarTools.hasRead(ctx) }
    var calTick by remember { mutableStateOf(0) }
    val phoneEvents by androidx.compose.runtime.produceState(emptyList<CalendarTools.Ev>(), month, granted, calTick) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { CalendarTools.events(ctx, month.atDay(1), month.atEndOfMonth()) }
    }
    val zone = java.time.ZoneId.systemDefault()
    val evByDay = remember(phoneEvents) {
        val m = HashMap<Long, MutableList<CalendarTools.Ev>>()
        phoneEvents.forEach { e ->
            var d = java.time.Instant.ofEpochMilli(e.start).atZone(if (e.allDay) java.time.ZoneOffset.UTC else zone).toLocalDate()
            val last = java.time.Instant.ofEpochMilli((e.end - 1).coerceAtLeast(e.start)).atZone(if (e.allDay) java.time.ZoneOffset.UTC else zone).toLocalDate()
            while (!d.isAfter(last)) { m.getOrPut(d.toEpochDay()) { mutableListOf() }.add(e); d = d.plusDays(1) }
        }
        m
    }

    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { month = month.minusMonths(1) }) { Text("‹") }
            Text("${month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${month.year}", Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { month = month.plusMonths(1) }) { Text("›") }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, fontFamily = Mono, fontSize = 11.sp, color = colors.onSurfaceVariant) }
        }
        val first = month.atDay(1)
        val offset = first.dayOfWeek.value - 1
        val rows = (offset + month.lengthOfMonth() + 6) / 7
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (c in 0 until 7) {
                    val dayNum = r * 7 + c - offset + 1
                    if (dayNum < 1 || dayNum > month.lengthOfMonth()) { Spacer(Modifier.weight(1f).aspectRatio(1f)); continue }
                    val d = month.atDay(dayNum)
                    val sel = d == picked
                    val today = d == LocalDate.now()
                    val has = dueByDay.containsKey(d.toEpochDay()) || evByDay.containsKey(d.toEpochDay())
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).padding(2.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (sel) colors.primary else if (today) colors.surfaceVariant else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { picked = d },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("$dayNum", color = if (sel) colors.onPrimary else colors.onSurface, fontSize = 14.sp, fontWeight = if (today) FontWeight.Bold else FontWeight.Normal)
                        if (has) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(4.dp).background(if (sel) colors.onPrimary else colors.primary, androidx.compose.foundation.shape.CircleShape))
                    }
                }
            }
        }
    }
    SoftCard {
        MonoLabel(picked.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMM yyyy")))
        val due = dueByDay[picked.toEpochDay()].orEmpty()
        if (due.isEmpty()) Text("No tasks due this day.", color = colors.onSurfaceVariant, fontSize = 14.sp)
        due.forEach { Text("• ${it.title}", fontSize = 15.sp) }
        val dayEvents = evByDay[picked.toEpochDay()].orEmpty()
        if (granted) {
            if (dayEvents.isEmpty()) Text("No events in your phone calendar this day.", color = colors.onSurfaceVariant, fontSize = 13.sp)
            dayEvents.forEach { e ->
                val t = if (e.allDay) "all day" else java.time.Instant.ofEpochMilli(e.start).atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
                Text("📅 $t · ${e.title}", fontSize = 14.sp)
            }
        } else OutlinedButton(onClick = { askCal.launch(arrayOf(android.Manifest.permission.READ_CALENDAR, android.Manifest.permission.WRITE_CALENDAR)) }, modifier = Modifier.fillMaxWidth()) { Text("Sync with phone calendar") }
        OutlinedTextField(title, { title = it }, label = { Text("New for this day") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.addTask(title.trim(), daily = false, due = picked.toEpochDay()); title = "" }, enabled = title.isNotBlank()) { Text("Add as task") }
            OutlinedButton(onClick = {
                val t = title.trim()
                if (CalendarTools.insertDirect(ctx, t, LocalDateTime.of(picked, LocalTime.of(9, 0)))) { android.widget.Toast.makeText(ctx, "Added to your phone calendar", android.widget.Toast.LENGTH_SHORT).show(); calTick++ }
                else CalendarTools.addEvent(ctx, t, LocalDateTime.of(picked, LocalTime.of(9, 0)))
                title = ""
            }, enabled = title.isNotBlank()) { Text("Add event") }
        }
        if (granted && due.isNotEmpty()) OutlinedButton(onClick = {
            val n = due.count { CalendarTools.insertDirect(ctx, it.title, LocalDateTime.of(picked, LocalTime.of(9, 0)), allDay = true) }
            android.widget.Toast.makeText(ctx, "$n task${if (n == 1) "" else "s"} added to your phone calendar", android.widget.Toast.LENGTH_SHORT).show(); calTick++
        }, modifier = Modifier.fillMaxWidth()) { Text("Send this day's tasks to the phone calendar") }
        TextButton(onClick = { CalendarTools.openDay(ctx, picked) }) { Text("Open in phone calendar") }
    }
}

// ------------------------------------------------------------------ currency
@Composable
private fun CurrencyTool() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rates by remember { mutableStateOf(CurrencyRates.cached(ctx)) }
    var loading by remember { mutableStateOf(false) }
    val cp = remember { ctx.getSharedPreferences("privacy_tools", android.content.Context.MODE_PRIVATE) }
    var amount by remember { mutableStateOf(cp.getString("cur_amount", "100") ?: "100") }
    var from by remember { mutableStateOf(cp.getString("cur_from", "USD") ?: "USD") }
    var to by remember { mutableStateOf(cp.getString("cur_to", "INR") ?: "INR") }
    LaunchedEffect(amount, from, to) { cp.edit().putString("cur_amount", amount).putString("cur_from", from).putString("cur_to", to).apply() }
    fun refresh(force: Boolean) { scope.launch { loading = true; rates = CurrencyRates.load(ctx, force); loading = false } }
    LaunchedEffect(Unit) { refresh(false) }
    val list = remember(rates) { (CurrencyRates.popular + (rates?.perUsd?.keys?.sorted() ?: emptyList())).distinct().filter { rates == null || rates!!.perUsd.containsKey(it) } }
    val result = rates?.let { r -> amount.toDoubleOrNull()?.let { a -> CurrencyRates.convert(r, a, from, to) } }
    SoftCard {
        MonoLabel("Currency converter")
        OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() || c == '.' }.take(14) }, label = { Text("Amount") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("From", fontSize = 13.sp)
            CurrencyMenu(list, from, Modifier.weight(1f)) { from = it }
            Text("to", fontSize = 13.sp)
            CurrencyMenu(list, to, Modifier.weight(1f)) { to = it }
        }
        TextButton(onClick = { val t = from; from = to; to = t }) { Text("Swap") }
        Text(
            when {
                result != null -> "${CurrencyRates.fmt(amount.toDoubleOrNull() ?: 0.0)} $from = ${CurrencyRates.fmt(result)} $to"
                loading -> "Getting rates…"
                rates == null -> "No rates yet – connect to the internet once and tap Refresh."
                else -> "Enter an amount."
            },
            fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 20.sp
        )
        val at = rates?.fetchedAt ?: 0L
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (at > 0) "Rates updated ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(at))}" else "",
                Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { refresh(true) }, enabled = !loading) { Text("Refresh") }
        }
        Text("Rates come from a free public source, are saved on this phone and work offline afterwards. They are indicative, not bank rates.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val COUNTRY = mapOf(
    "USD" to ("US" to "United States"), "EUR" to ("EU" to "Eurozone"), "GBP" to ("GB" to "United Kingdom"), "INR" to ("IN" to "India"), "JPY" to ("JP" to "Japan"),
    "AUD" to ("AU" to "Australia"), "CAD" to ("CA" to "Canada"), "CHF" to ("CH" to "Switzerland"), "CNY" to ("CN" to "China"), "AED" to ("AE" to "UAE"),
    "SGD" to ("SG" to "Singapore"), "SAR" to ("SA" to "Saudi Arabia"), "KRW" to ("KR" to "South Korea"), "BRL" to ("BR" to "Brazil"), "RUB" to ("RU" to "Russia"),
    "ZAR" to ("ZA" to "South Africa"), "NZD" to ("NZ" to "New Zealand"), "HKD" to ("HK" to "Hong Kong"), "SEK" to ("SE" to "Sweden"), "THB" to ("TH" to "Thailand"),
    "PKR" to ("PK" to "Pakistan"), "BDT" to ("BD" to "Bangladesh"), "LKR" to ("LK" to "Sri Lanka"), "NPR" to ("NP" to "Nepal"), "MYR" to ("MY" to "Malaysia"),
    "IDR" to ("ID" to "Indonesia"), "PHP" to ("PH" to "Philippines"), "VND" to ("VN" to "Vietnam"), "TRY" to ("TR" to "Turkey"), "MXN" to ("MX" to "Mexico"),
    "NOK" to ("NO" to "Norway"), "DKK" to ("DK" to "Denmark"), "PLN" to ("PL" to "Poland"), "EGP" to ("EG" to "Egypt"), "QAR" to ("QA" to "Qatar"),
    "KWD" to ("KW" to "Kuwait"), "OMR" to ("OM" to "Oman"), "BHD" to ("BH" to "Bahrain"), "NGN" to ("NG" to "Nigeria"), "KES" to ("KE" to "Kenya"), "ILS" to ("IL" to "Israel")
)
private fun flagOf(code: String): String = COUNTRY[code]?.first?.let { cc -> String(cc.map { Character.toChars(0x1F1E6 + (it - 'A')) }.flatMap { it.toList() }.toCharArray()) } ?: "🏳"
private fun currencyLine(code: String): String = "${flagOf(code)}  $code  ·  ${COUNTRY[code]?.second ?: runCatching { java.util.Currency.getInstance(code).displayName }.getOrDefault("")}"

@Composable
private fun CurrencyMenu(list: List<String>, selected: String, modifier: Modifier, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
            Text("${flagOf(selected)} $selected", fontFamily = Mono, maxLines = 1)
            Icon(Icons.Rounded.ArrowDropDown, null)
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            list.forEach { code ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(currencyLine(code), fontSize = 14.sp) }, onClick = { open = false; onPick(code) })
            }
        }
    }
}

// ------------------------------------------------------------------ calculator
@Composable
private fun CalculatorTool() {
    var expr by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf("0") }
    val history = remember { mutableStateOf(listOf<String>()) }
    val colors = MaterialTheme.colorScheme
    fun press(k: String) {
        when (k) {
            "C" -> { expr = ""; shown = "0" }
            "⌫" -> { expr = expr.dropLast(1); shown = if (expr.isEmpty()) "0" else expr }
            "=" -> {
                if (expr.isBlank()) return
                try {
                    val v = Calculator.eval(expr)
                    val out = if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else "%.10g".format(v).trimEnd('0').trimEnd('.')
                    history.value = (listOf("$expr = $out") + history.value).take(8)
                    expr = out; shown = out
                } catch (e: Exception) { shown = "Error" ; expr = "" }
            }
            else -> { expr += k; shown = expr }
        }
    }
    SoftCard {
        Text(shown, fontFamily = Mono, fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, maxLines = 2, modifier = Modifier.fillMaxWidth().height(86.dp))
        val keys = listOf(
            listOf("C", "(", ")", "÷"), listOf("7", "8", "9", "×"), listOf("4", "5", "6", "-"),
            listOf("1", "2", "3", "+"), listOf("0", ".", "⌫", "=")
        )
        keys.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { k ->
                    val op = k in listOf("÷", "×", "-", "+", "=")
                    Box(
                        Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (k == "=") colors.primary else if (op) colors.primaryContainer else colors.surfaceVariant)
                            .bounceClick { press(k) },
                        contentAlignment = Alignment.Center
                    ) { Text(k, fontSize = 22.sp, fontWeight = FontWeight.Medium, color = if (k == "=") colors.onPrimary else colors.onSurface) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("%", "^", "sqrt(", "pi").forEach { k ->
                Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp)).background(colors.surfaceVariant).bounceClick { press(k) }, contentAlignment = Alignment.Center) { Text(k, fontSize = 15.sp, fontFamily = Mono) }
            }
        }
        history.value.forEach { Text(it, fontFamily = Mono, fontSize = 12.sp, color = colors.onSurfaceVariant) }
    }
}
