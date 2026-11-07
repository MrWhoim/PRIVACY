@file:OptIn(ExperimentalMaterial3Api::class)

package com.privacy.app.ui.health

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privacy.app.ai.LlmClient
import com.privacy.app.ai.contentOrEmpty
import com.privacy.app.data.ActivityLevel
import com.privacy.app.data.CalcMethod
import com.privacy.app.data.ExerciseCalc
import com.privacy.app.data.FoodAi
import com.privacy.app.data.FoodDb
import com.privacy.app.data.HealthCalc
import com.privacy.app.data.HealthEntryEntity
import com.privacy.app.data.HealthGoal
import com.privacy.app.data.HealthParser
import com.privacy.app.data.HealthProfile
import com.privacy.app.data.HealthScore
import com.privacy.app.data.fmt
import com.privacy.app.ui.MainViewModel
import com.privacy.app.ui.MonoLabel
import com.privacy.app.ui.reports.EntryEditDialog
import com.privacy.app.ui.reports.GoalBar
import com.privacy.app.ui.reports.PixelGlyph
import com.privacy.app.ui.reports.Ring
import com.privacy.app.ui.reports.Tag
import com.privacy.app.ui.tasks.AnimatedPercent
import com.privacy.app.ui.tasks.PixelProgressBar
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private fun dayText(d: Long) = LocalDate.ofEpochDay(d).format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault()))
private fun timeText(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
private fun String.cap() = replaceFirstChar { it.uppercase() }

/** Everything the Health tab needs to remember while the user moves around (shared with the Tasks & Efficiency screen). */
class HealthUi {
    var day by mutableStateOf(LocalDate.now().toEpochDay())
    var message by mutableStateOf<String?>(null)
    var adding by mutableStateOf(false)
    var showProfile by mutableStateOf(false)
    var confirmMsg by mutableStateOf<String?>(null)
    var plan by mutableStateOf("")
    var planBusy by mutableStateOf(false)
    var edit by mutableStateOf<HealthEntryEntity?>(null)
    /** The section sheet that is open (N, A, S or W). */
    var sheet by mutableStateOf<String?>(null)
    var addCategory by mutableStateOf("FOOD")
}

@Composable
fun rememberHealthUi(vm: MainViewModel): HealthUi = remember { HealthUi().also { it.plan = vm.g.health.mealPlan() } }

@Composable
fun rememberHealthEval(vm: MainViewModel, ui: HealthUi): State<HealthScore.DayEval?> {
    val tick by vm.dataTick.collectAsStateWithLifecycle(0L)
    return produceState<HealthScore.DayEval?>(null, ui.day, tick) { value = vm.g.health.evaluate(ui.day) }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(16.dp), color = c.surfaceVariant.copy(alpha = 0.55f), border = BorderStroke(1.dp, c.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

// ============================================================================================ the tab
/** The Health tab: pixel score, four tappable rings, and the day's entries. Tap a ring to see / edit that section (and the meal plan). */
fun LazyListScope.healthItems(vm: MainViewModel, ui: HealthUi, ev: HealthScore.DayEval?) {
    ui.message?.let { m ->
        item {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).clickable { ui.message = null }) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(m, fontSize = 13.sp)
                    Text("Tap to dismiss", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    item { HealthHero(ev) }
    if (ev != null) {
        item { RingsRow(ev) { ui.sheet = it } }
        items(ev.entries.sortedBy { it.createdAt }, key = { it.id }) { e -> EntryRow(e) { ui.edit = e } }
    }
}

@Composable
private fun HealthHero(ev: HealthScore.DayEval?) {
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PixelProgressBar((ev?.score ?: 0f) / 100f, cols = 22, rows = 7, strength = 0.5f)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (ev != null) AnimatedPercent((ev.score ?: 0f) / 100f)
                else Text("—", fontSize = 58.sp, fontWeight = FontWeight.Bold, color = c.onSurface.copy(alpha = 0.6f))
                Text(
                    "health of this day", fontSize = 12.sp, color = c.onSurface.copy(alpha = 0.85f),
                    style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(c.background.copy(alpha = 0.9f), blurRadius = 10f))
                )
            }
        }
    }
}

/** The four rings (tap one to open that section). Used by the Health tab and the Health report. */
@Composable
fun RingsRow(ev: HealthScore.DayEval, onClick: ((String) -> Unit)? = null) {
    val c = MaterialTheme.colorScheme
    val t = ev.targets
    fun lines(key: String): Pair<String, String> = when (key) {
        "N" -> ((ev.kcal?.roundToInt()?.toString() ?: "—") + (t.kcal?.let { " / $it" } ?: "") + " kcal") to ((ev.protein?.roundToInt()?.toString() ?: "—") + (t.proteinG?.let { " / $it" } ?: "") + " g protein")
        "A" -> (if (ev.activeMinutes > 0) "${ev.activeMinutes.roundToInt()} min" else "—") to (ev.burnedKcal?.let { "~${it.roundToInt()} kcal burned" } ?: "")
        "S" -> ((ev.sleepH?.let { fmt(it) } ?: "—") + " h") to ("range " + fmt(t.sleepLoH ?: 7.0) + "–" + fmt(t.sleepHiH ?: 9.0) + " h")
        else -> (((ev.waterMl ?: 0.0) / 1000.0).let { if (ev.waterMl == null) "—" else String.format(Locale.US, "%.1f", it) } + (t.waterMl?.let { " / " + String.format(Locale.US, "%.1f", it / 1000.0) } ?: "") + " L") to ""
    }
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ev.cats.forEach { cat ->
            val (l1, l2) = lines(cat.key)
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).then(if (onClick != null) Modifier.clickable { onClick(cat.key) } else Modifier).padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Ring(cat.score)
                Spacer(Modifier.height(6.dp))
                Text(cat.label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                if (!cat.logged) Text("Not logged", fontSize = 10.sp, color = c.onSurfaceVariant)
                else {
                    Text(l1, fontSize = 10.sp, color = c.onSurfaceVariant, textAlign = TextAlign.Center)
                    if (l2.isNotBlank()) Text(l2, fontSize = 10.sp, color = c.onSurfaceVariant, textAlign = TextAlign.Center)
                    if (cat.estimated) Text("estimate", fontSize = 9.sp, color = c.onSurfaceVariant.copy(alpha = 0.7f))
                }
            }
        }
    }
}

private fun glyphFor(cat: String) = when (cat) { "FOOD" -> "food"; "ACTIVITY" -> "run"; "SLEEP" -> "sleep"; "WATER" -> "water"; else -> "weight" }

@Composable
private fun EntryRow(e: HealthEntryEntity, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val tilde = if (e.estimated) "~" else ""
    val time = timeText(e.createdAt)
    val title: String; val sub: String; val r1: String; val r2: String
    when (e.category) {
        "FOOD" -> { title = (e.meal ?: "Meal").cap(); sub = "$time · ${e.label}"; r1 = e.kcal?.let { "$tilde${it.roundToInt()} kcal" } ?: "kcal unknown"; r2 = e.protein?.let { "$tilde${fmt(it)} g protein" } ?: "" }
        "ACTIVITY" -> {
            val est = ExerciseCalc.fromJson(e.extra)
            title = e.label.cap(); sub = "$time · ${fmt(e.amount ?: 0.0)} ${e.unit ?: ""}".trim()
            r1 = (e.kcal ?: est?.kcal)?.let { "~${it.roundToInt()} kcal" } ?: ""; r2 = est?.minutes?.let { "${it.roundToInt()} min" } ?: ""
        }
        "SLEEP" -> { title = if (e.label.contains("nap", true)) "Nap" else "Sleep"; sub = "$time · slept ${fmt(e.amount ?: 0.0)} hours"; r1 = ""; r2 = "" }
        "WATER" -> { title = "Water"; sub = time + if (e.estimated) " · estimated amount" else ""; r1 = String.format(Locale.US, "%.2f L", (e.amount ?: 0.0) / 1000.0).replace(".00", ".0"); r2 = "" }
        else -> { title = "Weight"; sub = time; r1 = "${fmt(e.amount ?: 0.0)} kg"; r2 = "" }
    }
    Surface(shape = RoundedCornerShape(14.dp), color = c.surfaceVariant.copy(alpha = 0.45f), modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(c.onSurface.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) { PixelGlyph(glyphFor(e.category), size = 22, animated = true) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(sub, fontSize = 12.sp, color = c.onSurfaceVariant, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                if (r1.isNotBlank()) Text(r1, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                if (r2.isNotBlank()) Text(r2, fontSize = 12.sp, color = c.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.onSurfaceVariant)
        }
    }
}

// ============================================================================================ one section (tap a ring)
private val catEntity = mapOf("N" to "FOOD", "A" to "ACTIVITY", "S" to "SLEEP", "W" to "WATER")

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(14.dp), color = c.surfaceVariant.copy(alpha = 0.45f), border = BorderStroke(1.dp, c.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { content() }
    }
}

@Composable
private fun CategorySheet(vm: MainViewModel, ui: HealthUi) {
    val key = ui.sheet ?: return
    val c = MaterialTheme.colorScheme
    val ev by rememberHealthEval(vm, ui)
    val e = ev
    val water = key == "W"
    ModalBottomSheet(onDismissRequest = { ui.sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (e == null) { Text("Loading…"); return@Column }
            val cat = e.cat(key)
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelGlyph(glyphFor(catEntity.getValue(key)), size = 26)
                Spacer(Modifier.width(12.dp))
                Text(cat.label, Modifier.weight(1f), fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(cat.score?.let { String.format(Locale.US, "%.0f%%", it) } ?: "Not logged", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(cat.summary, fontSize = 14.sp, color = c.onSurfaceVariant)
            if (cat.parts.isNotEmpty()) SectionCard {
                Text("How this score is made", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                cat.parts.forEach { p ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(p.label, Modifier.width(92.dp), fontSize = 13.sp)
                        Text(p.detail, Modifier.weight(1f), fontSize = 12.sp, color = c.onSurfaceVariant)
                        Text(p.score?.let { String.format(Locale.US, "%.0f", it) } ?: "—", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            cat.notes.forEach { Text("• $it", fontSize = 12.sp, color = c.onSurfaceVariant) }

            when (key) {
                "N" -> {
                    SectionCard {
                        Text("Today's nutrition", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("Calories ${e.kcal?.roundToInt() ?: "—"} · Protein ${e.protein?.let { fmt(it) } ?: "—"} g · Carbs ${e.carbs?.let { fmt(it) } ?: "not measured"} g · Fat ${e.fat?.let { fmt(it) } ?: "not measured"} g · Fibre ${e.fiber?.let { fmt(it) } ?: "not measured"} g", fontSize = 13.sp)
                        Text("Targets: ${e.targets.kcal ?: "—"} kcal · ${e.targets.proteinG ?: "—"} g protein · carbs ${e.targets.carbLowG ?: "—"}–${e.targets.carbHighG ?: "—"} g. ${e.targets.kcalSource}", fontSize = 11.sp, color = c.onSurfaceVariant)
                    }
                    MealPlanSection(vm, ui)
                }
                "A" -> {
                    SectionCard {
                        Text("Training today", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        if (e.exercises.isEmpty()) Text("Nothing logged. Log what you did – for example “30 squats”, “ran 2 km” or “3 sets of 10 push-ups” – and the app estimates calories burned and the training stimulus.", fontSize = 13.sp)
                        else Text("${e.metMin.roundToInt()} MET-min of ~${e.targets.activityMetMin.roundToInt()} for a day like yours · ${e.activeMinutes.roundToInt()} active min" + (e.burnedKcal?.let { " · ~${it.roundToInt()} kcal burned (estimate)" } ?: ""), fontSize = 13.sp)
                    }
                    e.exercises.forEach { ExerciseCard(vm, it) }
                }
                "S" -> SectionCard {
                    Text("Enter after how many hours of sleep you woke up today – the hours you actually slept.", fontSize = 13.sp)
                    Text("Your range: ${fmt(e.targets.sleepLoH ?: 7.0)}–${fmt(e.targets.sleepHiH ?: 9.0)} h. Inside it Sleep is 100; too little or too much lowers it. ${e.targets.sleepSource}", fontSize = 11.sp, color = c.onSurfaceVariant)
                }
                else -> SectionCard {
                    Text("Reference: ${e.targets.waterMl ?: 2000} ml a day. ${e.targets.waterSource}", fontSize = 12.sp, color = c.onSurfaceVariant)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(250 to "+250 ml", 500 to "+500 ml", 1000 to "+1 L").forEach { (ml, l) ->
                            FilterChip(selected = false, onClick = { vm.logHealth("WATER", "water", ml.toDouble(), "ml", ui.day, force = true) { if (it.isNotBlank()) ui.message = it } }, label = { Text(l) })
                        }
                    }
                }
            }

            val mine = e.entries.filter { it.category == catEntity.getValue(key) }.sortedBy { it.createdAt }
            Text("Entries · tap to edit", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            if (mine.isEmpty()) Text("Nothing logged yet.", fontSize = 13.sp, color = c.onSurfaceVariant)
            mine.forEach { en -> EntryRow(en) { ui.edit = en } }
            Button(onClick = { ui.addCategory = catEntity.getValue(key); ui.sheet = null; ui.adding = true }, modifier = Modifier.fillMaxWidth()) { Text("Add " + cat.label.lowercase() + " entry") }
        }
    }
}

@Composable
private fun ExerciseCard(vm: MainViewModel, line: HealthScore.ExerciseLine) {
    val c = MaterialTheme.colorScheme
    val prog by produceState<String?>(null, line.entry.id) { value = vm.g.health.progressionNote(line.entry) }
    val est = line.est
    SectionCard {
        Text("${line.entry.label.cap()} · ${fmt(line.entry.amount ?: 0.0)} ${line.entry.unit ?: ""}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        if (est == null) Text("Not in the estimate table and no AI answered – nothing is guessed.", fontSize = 12.sp, color = c.onSurfaceVariant)
        else {
            Text("~${est.kcal?.roundToInt() ?: "—"} kcal burned (estimate) · MET ${fmt(est.met)} · ${est.minutes.roundToInt()} min · ${est.type}, ${est.intensity}", fontSize = 13.sp)
            if (est.stimulus.isNotBlank()) Text(est.stimulus, fontSize = 12.sp)
            if (est.assumptions.isNotEmpty()) Text("Assumed: " + est.assumptions.joinToString("; "), fontSize = 11.sp, color = c.onSurfaceVariant)
        }
        prog?.let { Text("Progress: $it", fontSize = 12.sp, color = c.onSurfaceVariant) }
    }
}

// ---------------------------------------------------------------------------------------- the AI meal plan, editable
private class PlanItem(var name: String, var portion: String, var kcal: String, var protein: String)
private class PlanMeal(var meal: String, val items: MutableList<PlanItem>, var note: String)

private fun parsePlan(json: String): List<PlanMeal> = runCatching {
    val arr = JSONArray(json)
    (0 until arr.length()).map { i ->
        val m = arr.getJSONObject(i)
        val items = m.optJSONArray("items") ?: JSONArray()
        PlanMeal(m.optString("meal"), (0 until items.length()).map { j ->
            val it = items.getJSONObject(j)
            PlanItem(it.optString("name"), it.optString("portion"), if (it.has("kcal")) fmt(it.optDouble("kcal")) else "", if (it.has("protein")) fmt(it.optDouble("protein")) else "")
        }.toMutableList(), m.optString("note"))
    }
}.getOrDefault(emptyList())

private fun planJson(list: List<PlanMeal>): String = JSONArray().also { a ->
    list.forEach { m ->
        a.put(JSONObject().put("meal", m.meal).put("note", m.note).put("items", JSONArray().also { ia ->
            m.items.forEach { i -> ia.put(JSONObject().put("name", i.name).put("portion", i.portion).put("kcal", i.kcal.toDoubleOrNull() ?: 0.0).put("protein", i.protein.toDoubleOrNull() ?: 0.0)) }
        }))
    }
}.toString()

@Composable
private fun MealPlanSection(vm: MainViewModel, ui: HealthUi) {
    val c = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val profile by vm.g.health.profile.collectAsStateWithLifecycle()
    var editIndex by remember { mutableStateOf<Int?>(null) }
    val meals = remember(ui.plan) { parsePlan(ui.plan) }
    SectionCard {
        Text("Meal plan", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text("Made by the AI from your diet, allergies and targets. Edit anything to match what you really eat – all numbers are estimates.", fontSize = 12.sp, color = c.onSurfaceVariant)
        meals.forEachIndexed { i, m ->
            HorizontalDivider(color = c.outlineVariant, modifier = Modifier.padding(vertical = 4.dp))
            val k = m.items.sumOf { it.kcal.toDoubleOrNull() ?: 0.0 }; val p = m.items.sumOf { it.protein.toDoubleOrNull() ?: 0.0 }
            Text(m.meal.cap() + " · ~${k.roundToInt()} kcal · ~${fmt(p)} g protein", fontWeight = FontWeight.Medium, fontSize = 14.sp)
            m.items.forEach { Text("• ${it.name} – ${it.portion}", fontSize = 13.sp) }
            Row {
                TextButton(onClick = { editIndex = i }) { Text("Edit") }
                TextButton(onClick = {
                    vm.logHealth("FOOD", m.items.joinToString(", ") { "${it.portion} ${it.name}".trim() }, null, null, ui.day, k, p, m.meal, estimated = true, confidence = "low", note = "from the meal plan") { if (it.isNotBlank()) ui.message = it }
                }) { Text("I ate this") }
            }
        }
        OutlinedButton(enabled = !ui.planBusy, onClick = {
            ui.planBusy = true
            scope.launch {
                val r = generatePlan(vm, profile)
                if (r != null) ui.plan = r else ui.message = "Couldn't get a meal plan right now (needs an AI key and a connection)."
                ui.planBusy = false
            }
        }) { Text(if (ui.planBusy) "Thinking…" else if (ui.plan.isBlank()) "Create my meal plan" else "Make a new plan with AI") }
    }
    editIndex?.let { idx ->
        val meal = meals.getOrNull(idx)
        if (meal != null) MealEditDialog(meal, onDismiss = { editIndex = null }) { edited ->
            val all = meals.toMutableList(); all[idx] = edited
            val json = planJson(all); vm.g.health.saveMealPlan(json); ui.plan = json; editIndex = null
        }
    }
}

@Composable
private fun MealEditDialog(meal: PlanMeal, onDismiss: () -> Unit, onSave: (PlanMeal) -> Unit) {
    val names = remember { mutableStateListOf<String>().also { l -> meal.items.forEach { l.add(it.name) } } }
    val portions = remember { mutableStateListOf<String>().also { l -> meal.items.forEach { l.add(it.portion) } } }
    val kcals = remember { mutableStateListOf<String>().also { l -> meal.items.forEach { l.add(it.kcal) } } }
    val prots = remember { mutableStateListOf<String>().also { l -> meal.items.forEach { l.add(it.protein) } } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit " + meal.meal) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                names.indices.toList().forEach { i ->
                    OutlinedTextField(names[i], { names[i] = it }, label = { Text("Food") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(portions[i], { portions[i] = it }, label = { Text("Portion") }, singleLine = true, modifier = Modifier.weight(1.3f))
                        OutlinedTextField(kcals[i], { kcals[i] = it }, label = { Text("kcal") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        OutlinedTextField(prots[i], { prots[i] = it }, label = { Text("g prot.") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    }
                    TextButton(onClick = { names.removeAt(i); portions.removeAt(i); kcals.removeAt(i); prots.removeAt(i) }) { Text("Remove this food") }
                }
                TextButton(onClick = { names.add(""); portions.add(""); kcals.add(""); prots.add("") }) { Text("Add a food") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(PlanMeal(meal.meal, names.indices.filter { names[it].isNotBlank() }.map { PlanItem(names[it].trim(), portions[it].trim(), kcals[it].trim(), prots[it].trim()) }.toMutableList(), meal.note))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ============================================================================================ dialogs + the "Add Health Entry" sheet
@Composable
fun HealthDialogs(vm: MainViewModel, ui: HealthUi) {
    val profile by vm.g.health.profile.collectAsStateWithLifecycle()
    if (ui.adding) AddHealthSheet(vm, ui)
    if (ui.showProfile) ProfileSheet(vm, profile) { ui.showProfile = false }
    if (ui.sheet != null) CategorySheet(vm, ui)
    ui.edit?.let { EntryEditDialog(vm, it) { ui.edit = null } }
    ui.confirmMsg?.let { AlertDialog(onDismissRequest = { ui.confirmMsg = null }, title = { Text("Confirm day") }, text = { Text(it) }, confirmButton = { TextButton(onClick = { ui.confirmMsg = null }) { Text("OK") } }) }
}

@Composable
private fun AddHealthSheet(vm: MainViewModel, ui: HealthUi) {
    val c = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val now = remember { java.time.LocalTime.now() }
    var cat by remember { mutableStateOf(ui.addCategory) }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val profile by vm.g.health.profile.collectAsStateWithLifecycle()
    val aiReady = remember { vm.g.settings.aiReady() }
    var meal by remember { mutableStateOf(HealthParser.mealFor("", now.hour)) }
    var hour by remember { mutableIntStateOf(if (ui.day == LocalDate.now().toEpochDay()) now.hour else 12) }
    var minute by remember { mutableIntStateOf(if (ui.day == LocalDate.now().toEpochDay()) now.minute else 0) }
    var portion by remember { mutableDoubleStateOf(1.0) }
    var calories by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var pickTime by remember { mutableStateOf(false) }

    val mic = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { text = it; error = null }
    }

    fun atMillis(): Long = LocalDate.ofEpochDay(ui.day).atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    fun sentence(): String {
        val t = text.trim()
        val noLetters = t.none { it.isLetter() }
        return when (cat) {
            "SLEEP" -> if (noLetters) "slept $t hours" else if (Regex("\\b(slept|sleep)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t)) t else "slept $t"
            "WATER" -> if (noLetters) (t.toDoubleOrNull()?.let { if (it <= 10) "drank $t litres of water" else "drank $t ml of water" } ?: "drank $t ml of water") else if (t.contains("water", true)) t else "drank $t water"
            else -> t
        }
    }
    val preview: String = remember(text, cat, portion) {
        if (text.isBlank()) "" else if (cat == "FOOD") {
            val est = FoodDb.estimate(text)
            if (!est.found) (if (aiReady) "Not in my food table – I'll ask the AI to estimate calories, protein and carbs." else "I don't recognise that food yet – describe it with a portion, or enter the calories below.")
            else (est.kcal ?: est.partialKcal).let { k -> "≈ ${(k * portion).roundToInt()} kcal · ${fmt((est.protein ?: est.partialProtein) * portion)} g protein (estimate)" } +
                (if (est.unknown.isNotEmpty()) "\nCan't estimate: " + est.unknown.joinToString(", ") else "") + (if (est.assumptions.isNotEmpty()) "\nAssumed: " + est.assumptions.joinToString("; ") else "")
        } else {
            val p = HealthParser.parse(sentence(), now.hour)
            if (p.drafts.isEmpty()) p.questions.firstOrNull() ?: "I can't read that yet." else p.drafts.joinToString(" · ") { d -> "→ " + d.label + (d.amount?.let { " " + fmt(it) + " " + (d.unit ?: "") } ?: "") }
        }
    }

    fun submit() {
        error = null
        if (busy) return
        if (text.isBlank()) { error = when (cat) { "FOOD" -> "Tell me what you ate."; "ACTIVITY" -> "Tell me what you did."; "SLEEP" -> "Tell me how many hours you slept."; else -> "Tell me how much water." }; return }
        val at = atMillis()
        if (cat != "FOOD") {
            vm.logHealthText(sentence(), ui.day, at) { if (it.isNotBlank()) ui.message = it }
            ui.adding = false
            return
        }
        scope.launch {
            busy = true
            val est = FoodDb.estimate(text)
            val typed = calories.trim().toDoubleOrNull()
            // a food the table does not know (a sandwich, a special dish…) is estimated by the AI: calories, protein, carbs, fat, fibre and food groups
            if (typed == null && (!est.found || est.unknown.isNotEmpty())) {
                val ai = FoodAi.estimate(vm.g, text, profile)
                if (ai != null) {
                    val label = ai.label + if (portion != 1.0) " ×${fmt(portion)}" else ""
                    vm.logHealth(
                        "FOOD", label, null, null, ui.day, ai.kcal * portion, ai.protein * portion, meal, estimated = true, confidence = ai.confidence,
                        note = "AI estimate" + if (ai.assumptions.isNotBlank()) ": ${ai.assumptions}" else "", atMillis = at, originalText = text.trim(),
                        carbs = ai.carbs * portion, fat = ai.fat * portion, fiber = ai.fiber?.let { it * portion }, groups = ai.groups.joinToString(",")
                    ) { if (it.isNotBlank()) ui.message = it }
                    busy = false; ui.adding = false; return@launch
                }
                if (!est.found) { error = "I couldn't estimate that" + (FoodAi.lastError.takeIf { it.isNotBlank() }?.let { " – $it" } ?: "") + ". Describe it with a portion, or enter the calories below."; busy = false; return@launch }
            }
            val k = typed ?: est.kcal?.let { it * portion }
            val p = if (typed != null) null else est.protein?.let { it * portion }
            val label = (if (est.found) est.lines.joinToString(", ") { it.label } else text.trim()) + if (portion != 1.0) " ×${fmt(portion)}" else ""
            val extra = if (est.unknown.isNotEmpty() && typed == null) "Couldn't estimate: ${est.unknown.joinToString(", ")} – tap the entry to add its calories." else ""
            vm.logHealth(
                "FOOD", label, null, null, ui.day, k, p, meal, estimated = typed == null && k != null, confidence = if (typed != null) "high" else est.confidence,
                note = est.assumptions.joinToString("; "), atMillis = at, originalText = text.trim(),
                carbs = if (typed == null) est.carbs?.let { it * portion } else null, groups = if (typed == null) est.groups.joinToString(",") else ""
            ) { r -> val m = listOf(r, extra).filter { it.isNotBlank() }.joinToString("\n"); if (m.isNotBlank()) ui.message = m }
            busy = false; ui.adding = false
        }
    }

    ModalBottomSheet(onDismissRequest = { ui.adding = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Add Health Entry", fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("FOOD" to "Food", "ACTIVITY" to "Exercise", "SLEEP" to "Sleep", "WATER" to "Water").forEach { (k, l) ->
                    val on = cat == k
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { cat = k; error = null }) {
                        Box(Modifier.size(58.dp).clip(CircleShape).background(if (on) c.onSurface.copy(alpha = 0.85f) else c.onSurface.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                            PixelGlyph(glyphFor(k), size = 28, tint = if (on) c.background else c.onSurface.copy(alpha = 0.7f))
                        }
                        Text(l, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp), fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
            OutlinedTextField(
                text, { text = it; error = null }, modifier = Modifier.fillMaxWidth(), maxLines = 3,
                placeholder = { Text(when (cat) { "FOOD" -> "What did you eat?"; "ACTIVITY" -> "What did you do?"; "SLEEP" -> "After how many hours of sleep did you wake up today?"; else -> "How much water?" }) },
                trailingIcon = {
                    IconButton(onClick = {
                        try {
                            mic.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your entry"))
                        } catch (_: ActivityNotFoundException) { error = "Speech input isn't available on this phone." }
                    }) { Icon(Icons.Rounded.Mic, "Speak") }
                },
                isError = error != null, supportingText = { error?.let { Text(it) } }
            )
            if (preview.isNotBlank()) Text(preview, fontSize = 13.sp, color = c.onSurfaceVariant)
            Text("Quick suggestions", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (cat) {
                    "FOOD" -> listOf("breakfast", "lunch", "dinner", "snack").forEach { m -> FilterChip(selected = meal == m, onClick = { meal = m }, label = { Text(m.cap()) }) }
                    "ACTIVITY" -> listOf("walk 30 min", "run 2 km", "30 squats", "yoga 20 min", "20 push-ups").forEach { s -> FilterChip(selected = false, onClick = { text = s }, label = { Text(s) }) }
                    "SLEEP" -> listOf("6 hours", "7 hours", "7.5 hours", "8 hours").forEach { s -> FilterChip(selected = false, onClick = { text = s }, label = { Text(s) }) }
                    else -> listOf("250 ml", "500 ml", "1 litre", "2 glasses").forEach { s -> FilterChip(selected = false, onClick = { text = s }, label = { Text(s) }) }
                }
            }
            Text("Details (optional)", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Surface(shape = RoundedCornerShape(14.dp), color = c.surfaceVariant.copy(alpha = 0.45f), border = BorderStroke(1.dp, c.outlineVariant)) {
                Column {
                    Row(Modifier.fillMaxWidth().clickable { pickTime = true }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Time", Modifier.weight(1f), fontSize = 15.sp)
                        Text(java.time.LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())), color = c.onSurfaceVariant)
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.onSurfaceVariant)
                    }
                    if (cat == "FOOD") {
                        HorizontalDivider(color = c.outlineVariant)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Portion", Modifier.weight(1f), fontSize = 15.sp)
                            TextButton(onClick = { if (portion > 0.5) portion -= 0.5 }) { Text("−", fontSize = 20.sp) }
                            Text(fmt(portion) + if (portion == 1.0) " serving" else " servings", color = c.onSurfaceVariant)
                            TextButton(onClick = { if (portion < 6.0) portion += 0.5 }) { Text("+", fontSize = 20.sp) }
                        }
                        HorizontalDivider(color = c.outlineVariant)
                        OutlinedTextField(
                            calories, { calories = it }, modifier = Modifier.fillMaxWidth().padding(10.dp), singleLine = true, label = { Text("Calories – only if you know them") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                }
            }
            Surface(shape = RoundedCornerShape(14.dp), color = c.surfaceVariant.copy(alpha = 0.35f)) {
                Text(
                    when (cat) {
                        "FOOD" -> "I'll estimate calories, protein and other nutrition from your input. You can edit the details later."
                        "ACTIVITY" -> "I'll estimate the calories burned and what it trains from the exercise, sets, reps, time or distance, and your weight. These are estimates."
                        "SLEEP" -> "Enter after how many hours of sleep you woke up today – the hours you actually slept. Logging again for the same day corrects the first entry."
                        else -> "A glass is taken as 250 ml and a bottle as 500 ml (saved as estimates)."
                    }, Modifier.padding(14.dp), fontSize = 13.sp, color = c.onSurfaceVariant
                )
            }
            Button(onClick = { submit() }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(26.dp)) {
                Text((if (busy) "Estimating with AI… " else "Add ") + when (cat) { "FOOD" -> "Food"; "ACTIVITY" -> "Exercise"; "SLEEP" -> "Sleep"; else -> "Water" } + " Entry", fontWeight = FontWeight.SemiBold)
            }
        }
    }
    if (pickTime) {
        val st = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = false)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            title = { Text("Time") },
            text = { TimePicker(state = st) },
            confirmButton = { TextButton(onClick = { hour = st.hour; minute = st.minute; pickTime = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } }
        )
    }
}

// ============================================================================================ profile
@Composable
internal fun ProfileSheet(vm: MainViewModel, p0: HealthProfile, onDismiss: () -> Unit) {
    var age by remember { mutableStateOf(p0.age?.toString() ?: "") }
    var height by remember { mutableStateOf(p0.heightCm?.let { fmt(it) } ?: "") }
    var weight by remember { mutableStateOf(p0.weightKg?.let { fmt(it) } ?: "") }
    var goal by remember { mutableStateOf(p0.goal) }
    var method by remember { mutableStateOf(p0.method) }
    var activity by remember { mutableStateOf(p0.activity) }
    var diet by remember { mutableStateOf(p0.diet) }
    var allergies by remember { mutableStateOf(p0.allergies) }
    var foodPrefs by remember { mutableStateOf(p0.foodPrefs) }
    var exPrefs by remember { mutableStateOf(p0.exercisePrefs) }
    var equipment by remember { mutableStateOf(p0.equipment) }
    var injuries by remember { mutableStateOf(p0.injuries) }
    var medical by remember { mutableStateOf(p0.medical) }
    var sleepGoal by remember { mutableStateOf(p0.sleepGoalH?.let { fmt(it) } ?: "") }
    var waterGoal by remember { mutableStateOf(p0.waterGoalMl?.toString() ?: "") }
    var kcalSet by remember { mutableStateOf(p0.kcalOverride?.toString() ?: "") }
    var protSet by remember { mutableStateOf(p0.proteinOverride?.toString() ?: "") }
    var budget by remember { mutableStateOf(p0.budget) }
    var schedule by remember { mutableStateOf(p0.mealSchedule) }
    var cook by remember { mutableStateOf(p0.cookTime) }

    fun build() = HealthProfile(
        age = age.toIntOrNull(), heightCm = height.toDoubleOrNull(), weightKg = weight.toDoubleOrNull(), goal = goal, method = method, activity = activity,
        diet = diet.trim(), allergies = allergies.trim(), foodPrefs = foodPrefs.trim(), exercisePrefs = exPrefs.trim(), equipment = equipment.trim(),
        injuries = injuries.trim(), medical = medical.trim(), sleepGoalH = sleepGoal.toDoubleOrNull(), waterGoalMl = waterGoal.toIntOrNull(),
        kcalOverride = kcalSet.toIntOrNull(), proteinOverride = protSet.toIntOrNull(), exerciseGoals = emptyList(), budget = budget.trim(), mealSchedule = schedule.trim(), cookTime = cook.trim()
    )
    val preview = HealthCalc.targets(build(), LocalDate.now().toEpochDay())
    val num = KeyboardOptions(keyboardType = KeyboardType.Decimal)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("My health plan", fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Text("Everything is optional. Skipped items are never guessed – the plan just stays provisional. Stored only on this phone.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(age, { age = it }, label = { Text("Age") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(height, { height = it }, label = { Text("Height cm") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = num)
                OutlinedTextField(weight, { weight = it }, label = { Text("Weight kg") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = num)
            }
            Text("Goal", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { HealthGoal.values().forEach { g -> FilterChip(selected = goal == g, onClick = { goal = g }, label = { Text(g.label) }) } }
            Text("Calorie estimate method", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { CalcMethod.values().forEach { m -> FilterChip(selected = method == m, onClick = { method = m }, label = { Text(m.label) }) } }
            Text("Normal activity", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { ActivityLevel.values().forEach { a -> FilterChip(selected = activity == a, onClick = { activity = a }, label = { Text(a.label) }) } }
            OutlinedTextField(diet, { diet = it }, label = { Text("Diet (vegetarian, vegan, eggetarian, …)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(allergies, { allergies = it }, label = { Text("Allergies / foods to avoid") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(foodPrefs, { foodPrefs = it }, label = { Text("Foods you like / eat locally") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(exPrefs, { exPrefs = it }, label = { Text("Exercise you enjoy and your level") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(equipment, { equipment = it }, label = { Text("Equipment available") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(injuries, { injuries = it }, label = { Text("Injuries / limits") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(medical, { medical = it }, label = { Text("Medical restrictions (optional)") }, modifier = Modifier.fillMaxWidth(), supportingText = { Text("Only used to keep targets conservative. This app does not give medical advice.") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(sleepGoal, { sleepGoal = it }, label = { Text("Sleep goal (h)") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = num)
                OutlinedTextField(waterGoal, { waterGoal = it }, label = { Text("Water goal (ml)") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(kcalSet, { kcalSet = it }, label = { Text("Calories (set myself)") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(protSet, { protSet = it }, label = { Text("Protein g (set myself)") }, singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Text("Exercise: no goals to set – your Activity score is estimated from what you log (type, sets, reps, time, distance) against a benchmark for your age and ability.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(budget, { budget = it }, label = { Text("Food budget (optional)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(schedule, { schedule = it }, label = { Text("Meal schedule (optional)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cook, { cook = it }, label = { Text("Cooking time available (optional)") }, modifier = Modifier.fillMaxWidth())
            HorizontalDivider()
            Text("Targets with these inputs (confidence: ${preview.confidence})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text("Calories: ${preview.kcal ?: "needs age, height, weight"}" + (if (preview.kcalLow != null && preview.kcalHigh != null && preview.kcalLow != preview.kcalHigh) " (range ${preview.kcalLow}–${preview.kcalHigh})" else ""), fontSize = 13.sp)
            Text("Protein: ${preview.proteinG ?: "needs weight"} g · Carbs: ${preview.carbLowG ?: "—"}–${preview.carbHighG ?: "—"} g · Sleep: ${fmt(preview.sleepLoH ?: 7.0)}–${fmt(preview.sleepHiH ?: 9.0)} h · Water: ${preview.waterMl ?: "—"} ml", fontSize = 13.sp)
            preview.notes.forEach { Text("• $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Button(onClick = { vm.saveHealthProfile(build()); onDismiss() }, modifier = Modifier.fillMaxWidth()) { Text("Save my plan") }
        }
    }
}

// ============================================================================================ meal plan (AI, estimates only)
private suspend fun generatePlan(vm: MainViewModel, p: HealthProfile): String? {
    val g = vm.g
    if (!g.settings.aiReady()) return null
    val t = g.health.targetsNow()
    val sys = "You plan one day of meals as JSON only (no prose, no markdown). Format: [{\"meal\":\"breakfast|lunch|dinner|snack\",\"items\":[{\"name\":\"…\",\"portion\":\"…\",\"kcal\":number,\"protein\":number}],\"note\":\"short\"}]. " +
        "Use foods that fit the user's diet, allergies and locally familiar choices; give portions; all numbers are rough ESTIMATES. Aim near the calorie and protein targets without going far above or below. " +
        "Never include an allergen. No extreme restriction. Include 3 meals and at most 2 snacks."
    val info = buildString {
        append("Calorie target: ${t.kcal ?: "unknown"} kcal; protein target: ${t.proteinG ?: "unknown"} g. Diet: ${p.diet.ifBlank { "not stated" }}. Allergies/avoid: ${p.allergies.ifBlank { "none stated" }}. ")
        append("Likes/local foods: ${p.foodPrefs.ifBlank { "not stated" }}. Budget: ${p.budget.ifBlank { "not stated" }}. Meal schedule: ${p.mealSchedule.ifBlank { "not stated" }}. Cooking time: ${p.cookTime.ifBlank { "not stated" }}. ")
        if (p.medical.isNotBlank()) append("Medical restriction noted: ${p.medical}. ")
    }
    val msgs = listOf(JSONObject().put("role", "system").put("content", sys), JSONObject().put("role", "user").put("content", info))
    val r = g.llm.chat(msgs, null, maxTokens = 1200, temperature = 0.5) as? LlmClient.Result.Ok ?: return null
    val raw = r.message.contentOrEmpty().replace(Regex("(?s)<think>.*?</think>"), "")
    val json = raw.substring(raw.indexOf('[').takeIf { it >= 0 } ?: return null, (raw.lastIndexOf(']') + 1).takeIf { it > 0 } ?: return null)
    return runCatching { JSONArray(json); json }.getOrNull()?.also { g.health.saveMealPlan(it) }
}

@Composable
private fun PlanView(planJson: String, onLog: (meal: String, text: String, kcal: Double?, protein: Double?) -> Unit) {
    val c = MaterialTheme.colorScheme
    val arr = remember(planJson) { runCatching { JSONArray(planJson) }.getOrNull() } ?: return
    var total = 0.0
    for (i in 0 until arr.length()) {
        val m = arr.getJSONObject(i)
        val items = m.optJSONArray("items") ?: JSONArray()
        val names = (0 until items.length()).map { items.getJSONObject(it) }
        val kcal = names.sumOf { it.optDouble("kcal", 0.0) }
        val prot = names.sumOf { it.optDouble("protein", 0.0) }
        total += kcal
        Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(m.optString("meal").replaceFirstChar { it.uppercase() } + " · ~${kcal.toInt()} kcal · ~${fmt(prot)} g protein (est.)", fontWeight = FontWeight.Medium, fontSize = 14.sp)
            names.forEach { Text("• ${it.optString("name")} – ${it.optString("portion")}", fontSize = 13.sp) }
            if (m.optString("note").isNotBlank()) Text(m.optString("note"), fontSize = 11.sp, color = c.onSurfaceVariant)
            TextButton(onClick = { onLog(m.optString("meal"), names.joinToString(", ") { "${it.optString("portion")} ${it.optString("name")}".trim() }, kcal, prot) }) { Text("I ate this") }
        }
    }
    Text("Plan total ≈ ${total.toInt()} kcal (estimate).", fontSize = 12.sp, color = c.onSurfaceVariant)
}
