package com.privacy.app.data

import android.content.Context
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The one door to health data. The chat assistant's tools, the offline commands and the Health screen's forms all call the same
 * functions here, so there is a single set of rows and a single set of totals. Corrections update the saved day and its report.
 */
class HealthRepo(private val context: Context, private val dao: HealthDao) {
    /** Set by the app graph; lets the repo ask the AI for exercises and foods it does not know. */
    var graph: com.privacy.app.AppGraph? = null


    private val prefs = context.getSharedPreferences("health_profile", Context.MODE_PRIVATE)
    private val _profile = MutableStateFlow(load())
    val profile: StateFlow<HealthProfile> = _profile.asStateFlow()

    private fun load(): HealthProfile =
        runCatching { HealthProfile.fromJson(JSONObject(prefs.getString("json", "{}") ?: "{}")) }.getOrDefault(HealthProfile())

    /** The last generated meal plan (JSON text) – kept so it is still there tomorrow. */
    fun mealPlan(): String = prefs.getString("plan", "") ?: ""
    fun saveMealPlan(json: String) { prefs.edit().putString("plan", json).apply() }

    fun today(): Long = LocalDate.now().toEpochDay()
    fun targetsNow(): HealthTargets = HealthCalc.targets(_profile.value, today())

    /** Saves the profile and – when the numbers changed – a new goal version that is effective from today onward only. */
    suspend fun saveProfile(p: HealthProfile) {
        prefs.edit().putString("json", p.toJson().toString()).apply()
        _profile.value = p
        val t = HealthCalc.targets(p, today())
        val latest = dao.goalFor(today())?.let { runCatching { HealthTargets.fromJson(JSONObject(it.json)) }.getOrNull() }
        if (latest == null || !latest.sameAs(t)) dao.insertGoal(HealthGoalEntity(effectiveDay = today(), json = t.toJson().toString()))
    }

    /** The goals that applied on [day]: the saved snapshot of a confirmed day, else the goal version of that time, else today's. */
    suspend fun targetsForDay(day: Long, row: HealthDayEntity? = null): HealthTargets {
        val r = row ?: dao.dayRow(day)
        if (r?.confirmed == true && r.snapshot.isNotBlank()) {
            runCatching { HealthTargets.fromJson(JSONObject(r.snapshot).getJSONObject("targets")) }.getOrNull()?.let { return it }
        }
        dao.goalFor(day)?.let { g -> runCatching { HealthTargets.fromJson(JSONObject(g.json)) }.getOrNull()?.let { return it } }
        return targetsNow()
    }

    // ------------------------------------------------------------------------------------------ logging
    sealed interface LogResult {
        class Added(val entry: HealthEntryEntity) : LogResult
        class Updated(val entry: HealthEntryEntity, val note: String) : LogResult
        class Duplicate(val entry: HealthEntryEntity) : LogResult
        class Rejected(val reason: String) : LogResult
    }

    private fun key(category: String, label: String, amount: Double?, unit: String?, meal: String?): String =
        listOf(category, HealthParser.canonName(label), amount?.let { String.format(Locale.US, "%.2f", it) } ?: "", unit ?: "", meal ?: "").joinToString("|")

    private fun stamp(): String = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm"))

    suspend fun log(
        category: String, label: String, amount: Double?, unit: String?, day: Long = today(), kcal: Double? = null, protein: Double? = null,
        meal: String? = null, balance: Int? = null, originalText: String = "", source: String = "manual",
        estimated: Boolean = false, confirmed: Boolean = true, confidence: String = "high", note: String = "", force: Boolean = false,
        atMillis: Long? = null, carbs: Double? = null, fat: Double? = null, fiber: Double? = null, groups: String = "", extra: String = "", sets: Int? = null
    ): LogResult {
        val cat = category.uppercase(Locale.ROOT)
        if (cat !in setOf("FOOD", "ACTIVITY", "SLEEP", "WATER", "WEIGHT")) return LogResult.Rejected("Unknown health category “$category”.")
        if (cat == "FOOD" && label.isBlank()) return LogResult.Rejected("Say what was eaten.")
        if (cat != "FOOD" && (amount == null || amount <= 0.0)) return LogResult.Rejected("I need an amount greater than zero.")
        if (cat == "SLEEP" && amount!! > 20.0) return LogResult.Rejected("That is more than 20 hours of sleep – please check the number.")
        if (cat == "WATER" && amount!! > 10_000.0) return LogResult.Rejected("That is more than 10 litres of water in one go – please check the number.")
        val existing = dao.forDay(day)
        val k = key(cat, label, amount, unit, meal)

        // one night = one sleep session: a second "I slept N hours" corrects the first instead of adding to it
        if (cat == "SLEEP" && !label.contains("nap", true)) {
            existing.firstOrNull { it.category == "SLEEP" && !it.label.contains("nap", true) }?.let { old ->
                if (abs((old.amount ?: 0.0) - amount!!) < 0.01) return LogResult.Duplicate(old)
                val n = old.copy(amount = amount, originalText = originalText.ifBlank { old.originalText }, updatedAt = System.currentTimeMillis(),
                    history = old.history + "${stamp()}: sleep ${fmt(old.amount ?: 0.0)} h → ${fmt(amount)} h\n")
                dao.update(n); afterChange(day)
                return LogResult.Updated(n, "Replaced the earlier sleep entry for this night (${fmt(old.amount ?: 0.0)} h → ${fmt(amount)} h) – one night is counted once.")
            }
        }
        if (!force) {
            val at = atMillis ?: System.currentTimeMillis()
            existing.firstOrNull { it.dedupeKey == k && abs(at - it.createdAt) < 5 * 60_000L }?.let { return LogResult.Duplicate(it) }
        }
        if (cat == "WEIGHT") {
            // keep the profile weight current for targets; the daily score is never affected by day-to-day weight changes
            val p = _profile.value
            if (day == today()) saveProfile(p.copy(weightKg = amount))
        }
        // an exercise gets its estimate (kcal, MET, minutes, training stimulus) when it is saved – from the table, or from the AI for unknown ones
        var extraJson = extra
        var burned = kcal
        if (cat == "ACTIVITY" && extraJson.isBlank()) {
            val kg = _profile.value.weightKg
            val est = ExerciseCalc.estimate(label, amount, unit, kg, sets) ?: graph?.let { ExerciseCalc.askAi(it, label, amount, unit, kg, _profile.value.age) }
            if (est != null) { extraJson = ExerciseCalc.toJson(est); burned = kcal ?: est.kcal }
        }
        val e = HealthEntryEntity(
            day = day, category = cat, label = label.trim(), amount = amount, unit = unit, kcal = burned, protein = protein, meal = meal,
            balance = balance, originalText = originalText, source = source, estimated = estimated || (cat == "ACTIVITY" && extraJson.isNotBlank() && kcal == null), confirmed = confirmed,
            confidence = confidence, note = note, dedupeKey = k,
            createdAt = atMillis ?: System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
            carbs = carbs, fat = fat, fiber = fiber, groups = groups, extra = extraJson
        )
        val id = dao.insert(e)
        afterChange(day)
        return LogResult.Added(e.copy(id = id))
    }

    suspend fun logDrafts(parsed: HealthParser.Parsed, originalText: String, source: String, day: Long = today(), atMillis: Long? = null): List<LogResult> =
        parsed.drafts.map { d ->
            log(
                d.category, d.label, d.amount, d.unit, day, d.kcal, d.protein, d.meal, d.balance, originalText, source,
                estimated = d.estimated, confirmed = !d.estimated || d.category == "WATER", confidence = d.confidence, note = d.note, atMillis = atMillis,
                carbs = d.carbs, fat = d.fat, groups = d.groups.joinToString(","), sets = d.sets
            )
        }

    /** Corrects an entry. Only the fields given are changed; the change is written to the entry's history. */
    suspend fun edit(
        id: Long, label: String? = null, amount: Double? = null, kcal: Double? = null, protein: Double? = null, meal: String? = null,
        balance: Int? = null, confirm: Boolean = false
    ): HealthEntryEntity? {
        val old = dao.get(id)?.takeIf { !it.deleted } ?: return null
        val changes = buildList {
            if (label != null && label != old.label) add("label “${old.label}” → “$label”")
            if (amount != null && amount != old.amount) add("amount ${old.amount?.let { fmt(it) } ?: "–"} → ${fmt(amount)}")
            if (kcal != null && kcal != old.kcal) add("kcal ${old.kcal?.let { fmt(it) } ?: "–"} → ${fmt(kcal)}")
            if (protein != null && protein != old.protein) add("protein ${old.protein?.let { fmt(it) } ?: "–"} → ${fmt(protein)}")
            if (meal != null && meal != old.meal) add("meal ${old.meal ?: "–"} → $meal")
            if (confirm && !old.confirmed) add("confirmed by user")
        }
        if (changes.isEmpty()) return old
        val userEnteredNumbers = kcal != null || protein != null
        val reEst = if (old.category == "ACTIVITY" && amount != null && amount != old.amount)
            ExerciseCalc.estimate(label ?: old.label, amount, old.unit, _profile.value.weightKg) else null
        val n = old.copy(
            extra = reEst?.let { ExerciseCalc.toJson(it) } ?: old.extra,
            label = label ?: old.label, amount = amount ?: old.amount, kcal = kcal ?: reEst?.kcal ?: old.kcal, protein = protein ?: old.protein,
            meal = meal ?: old.meal, balance = balance ?: old.balance,
            estimated = if (userEnteredNumbers) false else old.estimated, confirmed = if (confirm || userEnteredNumbers || amount != null) true else old.confirmed,
            updatedAt = System.currentTimeMillis(), history = old.history + "${stamp()}: ${changes.joinToString("; ")}\n",
            dedupeKey = key(old.category, label ?: old.label, amount ?: old.amount, old.unit, meal ?: old.meal)
        )
        dao.update(n); afterChange(old.day)
        return n
    }

    /** Soft delete – the row stays (marked deleted, with a history line) but no longer counts. */
    suspend fun delete(id: Long): HealthEntryEntity? {
        val old = dao.get(id)?.takeIf { !it.deleted } ?: return null
        val n = old.copy(deleted = true, updatedAt = System.currentTimeMillis(), history = old.history + "${stamp()}: deleted\n")
        dao.update(n); afterChange(old.day)
        return n
    }

    // ------------------------------------------------------------------------------------------ days
    suspend fun evaluate(day: Long): HealthScore.DayEval {
        val row = dao.dayRow(day)
        return HealthScore.evaluate(day, dao.forDay(day), row, targetsForDay(day, row))
    }

    /** Nothing to finalise any more: scores are always calculated from the entries as they are right now. */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun afterChange(day: Long) {
        if (day == today()) runCatching { com.privacy.app.widget.HealthWidget().updateAll(context) }
    }

    /** How today's amount of an exercise compares with the user's recent days – the "progression" of the training. */
    suspend fun progressionNote(e: HealthEntryEntity): String? {
        if (e.category != "ACTIVITY" || e.amount == null) return null
        val canon = HealthParser.canonName(e.label)
        val prev = dao.range(e.day - 14, e.day - 1).filter { it.category == "ACTIVITY" && HealthParser.canonName(it.label) == canon && it.unit == e.unit }
            .groupBy { it.day }.mapValues { (_, l) -> l.sumOf { it.amount ?: 0.0 } }
        if (prev.isEmpty()) return "First time logged in the last two weeks – a baseline."
        val avg = prev.values.average()
        val today = dao.forDay(e.day).filter { it.category == "ACTIVITY" && HealthParser.canonName(it.label) == canon && it.unit == e.unit }.sumOf { it.amount ?: 0.0 }
        val pct = ((today - avg) / avg * 100.0).roundToInt()
        return (if (pct >= 0) "+$pct%" else "$pct%") + " vs your recent average (${prev.size} day${if (prev.size > 1) "s" else ""} in 2 weeks)."
    }

    suspend fun entries(day: Long) = dao.forDay(day)
    suspend fun entry(id: Long) = dao.get(id)

    /** A compact text the AI reads (and the offline command prints): today's health at a glance. */
    suspend fun summary(day: Long = today()): String {
        val ev = evaluate(day)
        return buildString {
            appendLine("Health of ${LocalDate.ofEpochDay(day)}" + (ev.score?.let { String.format(Locale.US, ": %.1f%% (H = 0.35N + 0.25A + 0.20S + 0.20W; a category that is not logged counts as 0)", it) } ?: ": nothing logged yet"))
            ev.cats.forEach { c ->
                appendLine("- ${c.label}: " + (c.score?.let { String.format(Locale.US, "%.0f", it) } ?: "Not logged") + " · ${c.summary} · ${c.state.lowercase().replace('_', ' ')}" +
                    (if (c.estimated) " · includes estimates" else "") + (c.notes.firstOrNull()?.let { " · $it" } ?: ""))
            }
            ev.entries.forEach { e -> appendLine("  #${e.id} ${e.category.lowercase()}: ${e.label}" + (e.amount?.let { " ${fmt(it)} ${e.unit ?: ""}" } ?: "") + (e.kcal?.let { " ~${it.toInt()} kcal" } ?: "")) }
        }
    }
}
