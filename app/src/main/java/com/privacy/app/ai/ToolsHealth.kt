package com.privacy.app.ai

import com.privacy.app.AppGraph
import com.privacy.app.data.FoodDb
import com.privacy.app.data.HealthParser
import com.privacy.app.data.HealthRepo
import com.privacy.app.data.HealthScore
import com.privacy.app.data.fmt
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/** Health tools for the chat assistant. They write the very same rows as the Health screen's form (one source of truth). */
object HealthToolSpecs {
    private fun p(type: String, desc: String) = toolParam(type, desc)
    private fun fn(name: String, desc: String, props: Map<String, JSONObject> = emptyMap(), required: List<String> = emptyList()) = toolFn(name, desc, props, required)

    val list: List<JSONObject> = listOf(
        fn("estimate_food",
            "Estimate calories, protein and carbs of a described meal using the app's food table. Returns per-item estimates, the assumptions made and anything it could NOT estimate. Call this before log_health for food unless the user gave the numbers. If a food is not in the table, estimate it yourself (typical portion, say what you assumed) and pass kcal, protein, carbs, fat and groups to log_health with estimated=true.",
            mapOf("text" to p("string", "What was eaten, with portions if known, e.g. '2 rotis, 1 katori dal, paneer'")), listOf("text")),
        fn("log_health",
            "Record something the user ate, did, slept or drank for the Health of Day score. category: food | activity | sleep | water | weight. " +
                "FOOD: label=what was eaten, meal, kcal/protein ONLY from estimate_food or from the user (set estimated=true for estimates; never invent numbers for vague meals – ask for the portion). " +
                "ACTIVITY: label=exercise name (squats, run…), amount + unit (reps, km, min, sec) and sets if given; the app estimates calories burned (MET × weight × time) and the training stimulus itself – do not pass kcal. " +
                "SLEEP: amount in hours (counts for the day the user woke up). WATER: amount + unit (ml, l, glass=250 ml estimated, bottle=500 ml estimated). WEIGHT: amount in kg. " +
                "Keep the user's own words in original_text. Ask a clarifying question first only when a missing detail changes the result a lot.",
            mapOf(
                "category" to p("string", "food, activity, sleep, water or weight"),
                "label" to p("string", "What it was"),
                "amount" to p("number", "Quantity (not for food)"),
                "unit" to p("string", "reps, km, min, sec, sets, kg, h, ml, l, glass, bottle"),
                "meal" to p("string", "breakfast, lunch, dinner or snack (food)"),
                "kcal" to p("number", "Estimated or stated calories (food/activity)"),
                "protein" to p("number", "Estimated or stated protein in grams (food)"),
                "carbs" to p("number", "Estimated carbohydrates in grams (food)"),
                "fat" to p("number", "Estimated fat in grams (food)"),
                "fiber" to p("number", "Estimated fibre in grams (food), only if known"),
                "groups" to p("string", "Food kinds in the meal, comma separated from: grain, protein, legume, dairy, veg, fruit, nuts"),
                "sets" to p("integer", "Number of sets (strength exercise), if the user said"),
                "balance" to p("integer", "Meal balance rating 100 (protein + veg/fruit + energy source), 70 (one major part missing), 40 (very limited). Judge familiar regional meals fairly."),
                "estimated" to p("boolean", "true when kcal/protein/amount are estimates"),
                "original_text" to p("string", "The user's exact words"),
                "date" to p("string", "today (default), yesterday or YYYY-MM-DD"),
                "note" to p("string", "Assumptions made, e.g. 'assumed 250 ml per glass'")
            ), listOf("category", "label")),
        fn("edit_health_entry", "Correct a logged health entry by id (use health_summary to see ids). Only the given fields change.",
            mapOf("id" to p("integer", "Entry id"), "label" to p("string", "New label"), "amount" to p("number", "New amount"), "kcal" to p("number", "New calories"),
                "protein" to p("number", "New protein g"), "meal" to p("string", "New meal")), listOf("id")),
        fn("delete_health_entry", "Remove a wrongly logged health entry by id.", mapOf("id" to p("integer", "Entry id")), listOf("id")),
        fn("health_summary", "Today's (or another day's) health log, per-category scores, targets and entry ids.", mapOf("date" to p("string", "today (default), yesterday or YYYY-MM-DD")))
    )
}

object HealthTools {
    private fun dayOf(s: String?): Long = when (s?.lowercase()?.trim()) {
        null, "", "today" -> LocalDate.now().toEpochDay()
        "yesterday" -> LocalDate.now().toEpochDay() - 1
        else -> runCatching { LocalDate.parse(s.trim()).toEpochDay() }.getOrDefault(LocalDate.now().toEpochDay())
    }

    private fun describe(r: HealthRepo.LogResult): String = when (r) {
        is HealthRepo.LogResult.Added -> "Saved #${r.entry.id}: ${r.entry.category.lowercase()} – ${r.entry.label}" + (r.entry.amount?.let { " ${fmt(it)} ${r.entry.unit ?: ""}" } ?: "") +
            (r.entry.kcal?.let { " (~${it.toInt()} kcal, estimate)" } ?: "")
        is HealthRepo.LogResult.Updated -> r.note
        is HealthRepo.LogResult.Duplicate -> "Not added: it matches entry #${r.entry.id} logged moments ago (duplicate)."
        is HealthRepo.LogResult.Rejected -> "Not saved: ${r.reason}"
    }

    suspend fun run(g: AppGraph, name: String, a: JSONObject): ToolOutcome? = when (name) {
        "estimate_food" -> ToolOutcome(FoodDb.estimate(a.getString("text")).let { if (it.found) it.describe() else "No known foods found in that text. Ask the user for the food and a portion; do not invent calories." })
        "log_health" -> logHealth(g, a)
        "edit_health_entry" -> {
            val id = a.getLong("id")
            val n = g.health.edit(
                id, a.optString("label").ifBlank { null }, if (a.has("amount")) a.optDouble("amount") else null,
                if (a.has("kcal")) a.optDouble("kcal") else null, if (a.has("protein")) a.optDouble("protein") else null, a.optString("meal").ifBlank { null }
            )
            if (n == null) ToolOutcome("No active health entry #$id.") else ToolOutcome("Updated entry #$id (${n.label}). The day's score and report were recalculated.", "Corrected health entry")
        }
        "delete_health_entry" -> {
            val id = a.getLong("id")
            if (g.health.delete(id) == null) ToolOutcome("No active health entry #$id.") else ToolOutcome("Removed entry #$id. The day's score was recalculated.", "Removed health entry")
        }
        "health_summary" -> ToolOutcome(g.health.summary(dayOf(a.optString("date"))))
        else -> null
    }

    private suspend fun logHealth(g: AppGraph, a: JSONObject): ToolOutcome {
        val cat = a.getString("category").trim().uppercase(Locale.ROOT)
        val label = a.getString("label").trim()
        var amount = if (a.has("amount") && !a.isNull("amount")) a.optDouble("amount") else null
        var unit = a.optString("unit").trim().lowercase(Locale.ROOT).ifBlank { null }
        var estimated = a.optBoolean("estimated", false)
        var note = a.optString("note")
        val day = dayOf(a.optString("date"))
        // normalise units to the fixed ones the scores use
        when (cat) {
            "WATER" -> {
                val u = unit ?: "ml"
                when {
                    amount == null -> Unit
                    u.startsWith("l") && !u.startsWith("ml") -> { amount = amount?.times(1000.0); unit = "ml" }
                    u.startsWith("glass") || u.startsWith("cup") -> { amount = amount?.times(250.0); unit = "ml"; estimated = true; note = note.ifBlank { "assumed 250 ml per glass" } }
                    u.startsWith("bottle") -> { amount = amount?.times(500.0); unit = "ml"; estimated = true; note = note.ifBlank { "assumed 500 ml per bottle" } }
                    else -> unit = "ml"
                }
            }
            "SLEEP" -> { if (unit?.startsWith("min") == true) amount = amount?.div(60.0); unit = "h" }
            "WEIGHT" -> unit = "kg"
            "ACTIVITY" -> {
                val u = unit ?: "reps"
                when {
                    u == "m" || u.startsWith("meter") || u.startsWith("metre") -> { amount = amount?.div(1000.0); unit = "km" }
                    u.startsWith("mile") || u == "mi" -> { amount = amount?.times(1.609); unit = "km" }
                    u.startsWith("hour") || u == "h" || u == "hr" || u == "hrs" -> { amount = amount?.times(60.0); unit = "min" }
                    u.startsWith("min") -> unit = "min"
                    else -> unit = u
                }
            }
        }
        val meal = if (cat == "FOOD") (a.optString("meal").ifBlank { null } ?: HealthParser.mealFor(label + " " + a.optString("original_text"), LocalTime.now().hour)) else null
        val kcal = if (a.has("kcal") && !a.isNull("kcal")) a.optDouble("kcal") else null
        val protein = if (a.has("protein") && !a.isNull("protein")) a.optDouble("protein") else null
        val balance = if (cat == "FOOD" && meal != "snack") (if (a.has("balance")) a.optInt("balance").takeIf { it in setOf(100, 70, 40) } else FoodDb.estimate(label).balance) else null
        fun num(k: String) = if (a.has(k) && !a.isNull(k)) a.optDouble(k) else null
        val r = g.health.log(
            cat, label, amount, unit, day, kcal, protein, meal, balance, a.optString("original_text"), "chat",
            estimated = estimated || (cat == "FOOD" && kcal != null), confirmed = !(cat == "FOOD" && kcal != null) && !estimated,
            confidence = if (estimated) "low" else "high", note = note,
            carbs = num("carbs"), fat = num("fat"), fiber = num("fiber"), groups = a.optString("groups").replace(" ", ""),
            sets = if (a.has("sets") && !a.isNull("sets")) a.optInt("sets") else null
        )
        val sb = StringBuilder(describe(r))
        if (r is HealthRepo.LogResult.Added || r is HealthRepo.LogResult.Updated) {
            val ev = g.health.evaluate(day)
            if (cat == "ACTIVITY") {
                val line = ev.exercises.lastOrNull { HealthParser.canonName(it.entry.label) == HealthParser.canonName(label) }
                val est = line?.est
                if (est != null) sb.append("\nEstimated: ~${est.kcal?.let { "${it.toInt()} kcal" } ?: "kcal unknown"} (MET ${fmt(est.met)}, ${est.minutes.toInt()} min). ${est.stimulus} (estimates, say so).")
                else sb.append("\nThe app could not estimate this exercise (unknown type) – nothing was guessed.")
            }
            if (cat == "FOOD" && kcal == null) sb.append("\nNo calorie estimate was saved, so this meal lowers nothing and adds nothing to calories – ask the user for a portion or calories, or estimate it yourself with assumptions.")
            sb.append("\n" + ev.cats.joinToString(" · ") { c -> "${c.label}: " + (c.score?.let { String.format(Locale.US, "%.0f", it) } ?: "Not logged") })
            sb.append("\nHealth of Day now: " + (ev.score?.let { String.format(Locale.US, "%.1f%% (a category that is not logged counts as 0)", it) } ?: "nothing logged"))
        }
        return ToolOutcome(sb.toString(), if (r is HealthRepo.LogResult.Added || r is HealthRepo.LogResult.Updated) "Logged health: $label" else null)
    }
}
