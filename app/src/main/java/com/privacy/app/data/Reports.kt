package com.privacy.app.data

import com.privacy.app.AppGraph
import com.privacy.app.ai.LlmClient
import com.privacy.app.ai.contentOrEmpty
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Builds the Efficiency, Health and Combined reports from what was actually saved for the selected day or week.
 * Nothing here invents data: a day with nothing recorded is "no data" (never 0 %), provisional days are not averaged,
 * and every insight carries the number of days that support it.
 */
class ReportEngine(private val g: AppGraph) {

    // ---------------------------------------------------------------------------------------- model
    class EffDay(val day: Long, val result: Efficiency.Result, val isFinal: Boolean, val lines: List<Efficiency.Line>, val revisions: Int) {
        val hasData: Boolean get() = lines.isNotEmpty() || result.toughStars > 0
        /** The score shown for the day: final or provisional; null when the day has no planned tasks (Not Applicable). */
        val shownScore: Float? get() = if (result.applicable) result.score else null
        /** Counts toward weekly averages only when confirmed and applicable. */
        val eligible: Boolean get() = isFinal && result.applicable
    }

    class Insight(val priority: Int, val domain: String, val observed: String, val evidence: String, val contributor: String?, val action: String, val signal: String)

    class Breakdown(val name: String, val tasks: Int, val done: Int, val doing: Int, val planned: Float, val credited: Float)

    class CombinedDay(val day: Long, val eff: EffDay, val health: HealthScore.DayEval, val focusRes: FocusRepo.Result? = null) {
        /** Focus % of the day (null = not enough data). */
        val focus: Float? get() = focusRes?.focus
        /** (Tasks + Health + Focus) / 3. Tasks and Health must both be there; if there is not enough Focus data the other two are averaged. */
        val combined: Float? get() {
            if (!(eff.eligible && health.isFinal && health.score != null)) return null
            val f = focus
            return if (f != null) (eff.result.score + health.score + f) / 3f else (eff.result.score + health.score) / 2f
        }
    }

    class CatMean(val key: String, val label: String, val mean: Float?, val days: Int)
    class WeekStats(
        val start: Long, val days: List<CombinedDay>,
        val effMean: Float?, val effDays: Int, val healthMean: Float?, val healthDays: Int, val cats: List<CatMean>, val combinedMean: Float?, val combinedDays: Int,
        val focusMean: Float? = null, val focusDays: Int = 0
    )
    class Compare(val label: String, val delta: Float?, val note: String)

    // ---------------------------------------------------------------------------------------- days
    suspend fun effDay(day: Long): EffDay {
        // calculated from the tasks as they are right now – there is no confirmation step any more
        val lines = g.taskRepo.linesFor(day)
        val res = g.taskRepo.evaluate(day)
        return EffDay(day, res, true, lines, 0)
    }

    suspend fun combinedDay(day: Long) = CombinedDay(day, effDay(day), g.health.evaluate(day), runCatching { g.focus.evaluate(day) }.getOrNull())

    fun weekStart(d: LocalDate): LocalDate = d.with(DayOfWeek.MONDAY)

    suspend fun week(start: Long): WeekStats = period(start, 7)

    /** Any run of days (a week or a month) aggregated the same way: confirmed days only, with the number of days behind every average. */
    suspend fun period(start: Long, n: Int): WeekStats {
        val days = (0 until n).map { combinedDay(start + it) }
        val effOk = days.filter { it.eff.eligible }
        val hOk = days.filter { it.health.isFinal && it.health.score != null }
        val comb = days.mapNotNull { it.combined }
        val cats = listOf("N" to "Nutrition", "A" to "Activity", "S" to "Sleep", "W" to "Hydration").map { (k, label) ->
            val ok = days.map { it.health.cat(k) }.filter { it.score != null && it.state == "COMPLETE" }
            CatMean(k, label, if (ok.isEmpty()) null else ok.map { it.score!! }.average().toFloat(), ok.size)
        }
        return WeekStats(
            start, days,
            if (effOk.isEmpty()) null else effOk.map { it.eff.result.score }.average().toFloat(), effOk.size,
            if (hOk.isEmpty()) null else hOk.map { it.health.score!! }.average().toFloat(), hOk.size,
            cats, if (comb.isEmpty()) null else comb.average().toFloat(), comb.size,
            days.mapNotNull { it.focus }.let { f -> if (f.isEmpty()) null else f.average().toFloat() }, days.count { it.focus != null }
        )
    }

    /** Week-over-week changes – only where both weeks have at least 3 eligible days; a difference in coverage is disclosed. */
    fun compare(now: WeekStats, prev: WeekStats): List<Compare> {
        fun one(label: String, a: Float?, an: Int, b: Float?, bn: Int): Compare {
            if (a == null || b == null || an < 3 || bn < 3) return Compare(label, null, "Not enough days in both weeks for a fair comparison ($an vs $bn days).")
            val diff = if (abs(an - bn) >= 2) " Note: this week has $an days of data, last week $bn." else ""
            return Compare(label, a - b, "$an days vs $bn days.$diff")
        }
        return listOf(
            one("Efficiency", now.effMean, now.effDays, prev.effMean, prev.effDays),
            one("Health", now.healthMean, now.healthDays, prev.healthMean, prev.healthDays)
        ) + now.cats.mapIndexed { i, c -> one(c.label, c.mean, c.days, prev.cats[i].mean, prev.cats[i].days) }
    }

    // ---------------------------------------------------------------------------------------- breakdowns
    fun breakdown(lines: List<Efficiency.Line>, by: (Efficiency.Line) -> String, order: List<String>): List<Breakdown> {
        val scored = lines.filter { !it.optional }
        return order.mapNotNull { name ->
            val l = scored.filter { by(it) == name }
            if (l.isEmpty()) null else Breakdown(
                name, l.size, l.count { it.status == TaskStatus.COMPLETED }, l.count { it.status == TaskStatus.IN_PROGRESS },
                l.sumOf { it.weight.toDouble() }.toFloat(), l.sumOf { it.credited.toDouble() }.toFloat()
            )
        }
    }

    /** A suggested starting order for the open tasks of a day, each with the reason. Never changes anything by itself. */
    fun suggestOrder(lines: List<Efficiency.Line>, dueByKey: Map<Long, Long?>, today: Long): List<Pair<Efficiency.Line, String>> {
        val open = lines.filter { !it.optional && it.status != TaskStatus.COMPLETED }
        return open.sortedWith(
            compareByDescending<Efficiency.Line> { it.core }
                .thenBy { dueByKey[it.key] ?: Long.MAX_VALUE }
                .thenByDescending { it.priority }
                .thenByDescending { it.status == TaskStatus.IN_PROGRESS }
                .thenByDescending { it.weight }
        ).take(6).map { l ->
            val due = dueByKey[l.key]
            val why = buildList {
                if (l.core) add("core commitment – the day's score is capped until it is done")
                if (due != null && due < today) add("overdue")
                else if (due != null && due == today) add("due today")
                if (l.priority == 2) add("high priority")
                if (l.status == TaskStatus.IN_PROGRESS) add("already started – finishing it is the cheapest credit")
                if (l.difficulty == 2) add("hard: best tackled early in a focused 45–60 min block, then a short break")
            }.ifEmpty { listOf("fits after the more urgent work") }
            l to why.joinToString("; ")
        }
    }

    // ---------------------------------------------------------------------------------------- insights
    /** Looks back up to 28 days from [endDay] and returns at most a few well-supported suggestions. */
    suspend fun insights(endDay: Long): List<Insight> {
        val from = endDay - 27
        val rows = g.db.historyDao().range(from, endDay).filter { !it.optional }
        val stats = g.db.historyDao().statsIn(from, endDay).associate { it.day to it.manualPoints }
        val scores = g.db.scoreDao().range(from, endDay).associateBy { it.day }
        val out = mutableListOf<Insight>()
        fun d(day: Long) = LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))

        // --- repeatedly postponed / left in progress
        rows.groupBy { it.title.trim().lowercase() }.mapNotNull { (_, list) ->
            val unfinished = list.filter { it.status != TaskStatus.COMPLETED.name }
            if (list.size >= 3 && unfinished.size >= 3) list to unfinished else null
        }.sortedByDescending { (l, u) -> (if (l.any { it.core || it.priority == 2 }) 100 else 0) + u.size }.firstOrNull()?.let { (list, unfinished) ->
            val important = list.any { it.core || it.priority == 2 }
            out += Insight(
                if (important) 1 else 3, "Efficiency",
                "“${list.first().title}”${if (important) " (a core or high-priority task)" else ""} was left unfinished on ${unfinished.size} of the ${list.size} days it was planned.",
                "Days: " + unfinished.take(5).joinToString { d(it.day) } + ".",
                "It may be too large for one sitting, or not tied to a specific time – the logs cannot say which.",
                "Split it into a first step of about 20 minutes and put that step first on tomorrow's list.",
                "Whether it is marked Done within the next 2 planned days."
            )
        }

        // --- workload vs what actually gets done
        val perDay = rows.groupBy { it.day }.mapNotNull { (day, list) ->
            val r = Efficiency.calc(list.map { Efficiency.line(it) }, stats[day] ?: 0, 0, confirmed = false)
            if (r.applicable) day to r else null
        }
        if (perDay.size >= 5) {
            val planned = perDay.map { it.second.plannedWeight }.average()
            val credited = perDay.map { it.second.creditedWeight }.average()
            if (planned > 0 && credited / planned < 0.7) {
                out += Insight(
                    2, "Efficiency",
                    "On ${perDay.size} planned days you set about ${String.format(Locale.US, "%.1f", planned)} weight units and completed about ${String.format(Locale.US, "%.1f", credited)}.",
                    "Completion averaged ${(credited / planned * 100).roundToInt()}% of the planned weight over those days.",
                    "The plans may regularly be larger than a day allows (or tasks are being added late) – the data does not show which.",
                    "Plan about ${String.format(Locale.US, "%.0f", max(4.0, credited * 1.2))} units tomorrow and make the rest Optional.",
                    "Whether credited weight comes closer to planned weight over the next week."
                )
            }
        }

        // --- hard tasks
        val hard = rows.filter { it.difficulty == 2 }
        if (hard.size >= 4) {
            val done = hard.count { it.status == TaskStatus.COMPLETED.name }
            if (done * 2 < hard.size) out += Insight(
                3, "Efficiency", "Hard tasks were completed ${done} of ${hard.size} times.", "Across ${hard.map { it.day }.distinct().size} days with hard tasks.",
                "Hard work may be getting pushed behind easier tasks – an assumption, not something the logs prove.",
                "Put one hard task first and give it a focused 45–60 minute block.", "The hard-task completion rate next week."
            )
        }

        // --- health patterns (only complete days)
        val evals = (from..endDay).map { g.health.evaluate(it) }
        val sleepDays = evals.filter { it.cat("S").score != null }
        val sleepTarget = sleepDays.firstOrNull()?.targets?.sleepH
        if (sleepDays.size >= 3 && sleepTarget != null) {
            val avg = sleepDays.mapNotNull { it.sleepH }.average()
            if (avg < sleepTarget * 0.9) out += Insight(
                2, "Health", "Logged sleep averaged ${String.format(Locale.US, "%.1f", avg)} h against your ${fmt(sleepTarget)} h goal.",
                "${sleepDays.size} nights logged in the last 4 weeks.",
                "Bedtime and wake time are not recorded, so the logs cannot say whether late nights or early mornings explain it.",
                "Try going to bed 15 minutes earlier for 7 nights.", "Average logged sleep hours over those nights."
            )
        }
        val protDays = evals.filter { it.cat("N").state == "COMPLETE" && it.protein != null && (it.targets.proteinG ?: 0) > 0 && it.entries.filter { e -> e.category == "FOOD" }.all { e -> e.protein != null } }
        if (protDays.size >= 3) {
            val ratio = protDays.map { it.protein!! / it.targets.proteinG!! }.average()
            if (ratio < 0.75) {
                val byMeal = protDays.flatMap { it.entries }.filter { it.category == "FOOD" && it.meal in HealthScore.MAIN_MEALS }.groupBy { it.meal!! }
                    .filter { it.value.size >= 3 }.mapValues { e -> e.value.mapNotNull { it.protein }.average() }
                val low = byMeal.minByOrNull { it.value }
                out += Insight(
                    2, "Health", "Protein reached about ${(ratio * 100).roundToInt()}% of your target on days with a complete food log.",
                    "${protDays.size} complete days." + (low?.let { " ${it.key.replaceFirstChar { c -> c.uppercase() }} averaged about ${it.value.roundToInt()} g." } ?: ""),
                    "Protein may be concentrated in one meal; the estimates are rough, so treat this as a hint.",
                    "Add one protein source to ${low?.key ?: "your lightest meal"} for the next week.", "Daily protein as a share of target."
                )
            }
        }
        val waterDays = evals.filter { it.cat("W").score != null && it.cat("W").state == "COMPLETE" }
        if (waterDays.size >= 3) {
            val r = waterDays.map { (it.waterMl ?: 0.0) / (it.targets.waterMl ?: 1) }.average()
            if (r < 0.7) out += Insight(
                3, "Health", "Water averaged about ${(r * 100).roundToInt()}% of your goal.", "${waterDays.size} days marked complete.",
                "Water may only be logged at some points of the day, or the goal may be set high for your routine.",
                "Log a glass with each meal for a week – or lower the goal if it does not fit you.", "Average water as a share of goal."
            )
        }
        val actDays = evals.filter { it.cat("A").score != null }
        if (actDays.size >= 3) {
            val a = actDays.map { it.cat("A").score!! }.average()
            if (a < 60) out += Insight(
                3, "Health", "Activity goals were about ${a.roundToInt()}% met on average.", "${actDays.size} days with a scored activity log.",
                "The goals may be larger than your usual routine allows.", "Pick the smallest goal and do it on three fixed days.", "Activity score on those days."
            )
        }
        val logged = evals.takeLast(14).count { it.entries.isNotEmpty() }
        if (logged in 1..6) out += Insight(
            4, "Health", "Health entries exist on only $logged of the last 14 days.", "Fewer logged days means weaker averages.",
            null, "Choose one category (for example water or sleep) and log it daily for a week.", "Number of logged days next week."
        )

        // --- cross-domain
        val overlap = evals.mapNotNull { e ->
            val s = scores[e.day]
            if (s != null && s.isFinal && s.score >= 0f && e.cat("S").score != null && e.sleepH != null) Triple(e.day, e.sleepH, s.score) else null
        }
        val tgt = sleepTarget
        if (tgt != null) {
            if (overlap.size < 6) out += Insight(
                5, "Combined", "Not enough overlapping days to compare sleep and task completion.",
                "${overlap.size} of 6 days with both a confirmed Efficiency score and a logged night of sleep.",
                null, "Keep logging sleep and confirming each day.", "Reaching 6 overlapping days."
            ) else {
                val low = overlap.filter { it.second < tgt * 0.9 }
                val ok = overlap.filter { it.second >= tgt * 0.9 }
                if (low.size >= 2 && ok.size >= 2) {
                    val diff = ok.map { it.third }.average() - low.map { it.third }.average()
                    out += if (abs(diff) >= 10) Insight(
                        1, "Combined",
                        "On ${low.size} days with less sleep than your goal the Efficiency score averaged ${low.map { it.third }.average().roundToInt()}%, versus ${ok.map { it.third }.average().roundToInt()}% on ${ok.size} days with enough sleep.",
                        "${overlap.size} days, last 4 weeks. This is a pattern in your logs, not proof of cause.",
                        "Sleep may be one contributor – workload, the kind of day or anything else that varied could also explain both. Does this match your experience?",
                        "Protect your sleep window on days with core tasks and see whether the gap narrows.", "Efficiency on short-sleep vs enough-sleep days."
                    ) else Insight(
                        5, "Combined", "No clear difference in Efficiency between short-sleep and enough-sleep days.",
                        "${overlap.size} days compared (${low.size} short, ${ok.size} enough).", null,
                        "Nothing to change on this account.", "Re-check after more days."
                    )
                }
            }
        }
        return out.sortedBy { it.priority }
    }

    // ---------------------------------------------------------------------------------------- entry-impact timelines
    /** One point of an impact graph: x = hours into the day, y = the running percentage after this entry, delta = what it added. */
    class Pt(val x: Float, val y: Float, val label: String, val delta: Float)
    class Timeline(val points: List<Pt>, val note: String)

    private fun hoursSince(start: Long, at: Long) = ((at - start) / 3_600_000.0).toFloat().coerceIn(0f, 24f)

    /** Task completions through the day: each completed task adds its share of the 85 task points. */
    suspend fun taskTimeline(day: Long): Timeline {
        val start = LocalDate.ofEpochDay(day).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        class T(val line: Efficiency.Line, val at: Long)
        val items: List<T> = if (day == LocalDate.now().toEpochDay())
            g.db.taskDao().activeNow().map { T(Efficiency.line(it), if (it.status == TaskStatus.COMPLETED) (it.completedAt ?: 0L) else 0L) }
        else g.db.historyDao().forDay(day).map { T(Efficiency.line(it), it.doneAt) }
        val scored = items.filter { !it.line.optional }
        if (scored.isEmpty()) return Timeline(emptyList(), "No tasks on this day.")
        val denom = max(scored.sumOf { it.line.weight.toDouble() }.toFloat(), Efficiency.DAILY_TARGET)
        var credited = 0f
        val pts = scored.filter { it.line.status == TaskStatus.COMPLETED && it.at > 0 }.sortedBy { it.at }.map { t ->
            credited += t.line.weight
            val d = Efficiency.TASK_MAX * t.line.weight / denom
            Pt(hoursSince(start, t.at), Efficiency.TASK_MAX * min(1f, credited / denom), t.line.title, d)
        }
        val untimed = scored.count { it.line.status == TaskStatus.COMPLETED && it.at <= 0 }
        val doing = scored.count { it.line.status == TaskStatus.IN_PROGRESS }
        val res = Efficiency.calc(scored.map { it.line }, g.db.historyDao().stat(day)?.manualPoints ?: 0, g.taskRepo.consStars(day), true)
        val note = buildList {
            if (untimed > 0) add("$untimed done task${if (untimed > 1) "s have" else " has"} no recorded time, so ${if (untimed > 1) "they are" else "it is"} not on the graph.")
            if (doing > 0) add("$doing task${if (doing > 1) "s are" else " is"} in progress (half credit, no time recorded).")
            if (res.starPoints > 0) add("Commitment stars add ${String.format(Locale.US, "%.1f", res.starPoints)} points to the final score.")
        }.joinToString(" ")
        return Timeline(pts, note)
    }

    /** Health entries through the day: the Health of Day score after each one, and what that entry added. */
    suspend fun healthTimeline(day: Long): Timeline {
        val start = LocalDate.ofEpochDay(day).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val entries = g.db.healthDao().forDay(day).filter { it.category in setOf("FOOD", "ACTIVITY", "SLEEP", "WATER") }.sortedBy { it.createdAt }
        if (entries.isEmpty()) return Timeline(emptyList(), "Nothing logged on this day.")
        val t = g.health.targetsForDay(day)
        var prev = 0f
        val pts = entries.indices.map { i ->
            val sc = HealthScore.evaluate(day, entries.take(i + 1), null, t).score ?: 0f
            val e = entries[i]
            val label = when (e.category) {
                "FOOD" -> (e.meal ?: "meal").replaceFirstChar { it.uppercase() } + ": " + e.label.take(26)
                "ACTIVITY" -> e.label.replaceFirstChar { it.uppercase() }
                "SLEEP" -> "Sleep ${fmt(e.amount ?: 0.0)} h"
                else -> "Water ${(e.amount ?: 0.0).roundToInt()} ml"
            }
            Pt(hoursSince(start, e.createdAt), sc, label, sc - prev).also { prev = sc }
        }
        return Timeline(pts, "Each point is the Health of Day score right after that entry. Categories you have not logged count as 0.")
    }

    /** Short factual bullets for the combined day report – only numbers that were saved, no causal claims. */
    fun takeaways(c: CombinedDay): List<String> {
        val out = mutableListOf<String>()
        val r = c.eff.result
        if (r.applicable) {
            out += "${r.done} of ${r.planned} planned tasks done; ${String.format(Locale.US, "%.1f", r.creditedWeight)} of ${String.format(Locale.US, "%.1f", r.plannedWeight)} weight credited."
        } else out += "No planned tasks, so Efficiency is not applicable."
        val valid = c.health.cats.filter { it.score != null }
        if (!c.health.hasData) out += "No health data recorded for this day."
        else {
            val hi = valid.maxByOrNull { it.score!! }!!; val lo = valid.minByOrNull { it.score!! }!!
            out += "Highest health category: ${hi.label} (${hi.score!!.roundToInt()}%)." + if (lo !== hi) " Lowest: ${lo.label} (${lo.score!!.roundToInt()}%)." else ""
            val miss = c.health.cats.filter { it.score == null }
            if (miss.isNotEmpty()) out += "${miss.joinToString { it.label }} not logged – counted as 0 in the Health score."
        }
        c.focusRes?.takeIf { it.enough }?.let { f ->
            out += String.format(Locale.US, "Focus %.0f%% / Distraction %.0f%%", f.focus, f.dist) +
                (if (f.morningDistMin >= 1f) " – ${f.morningDistMin.roundToInt()} min of distracting apps in the first two hours after waking." else ".")
        }
        return out.take(5)
    }

    // ---------------------------------------------------------------------------------------- AI narrative
    /** Plain facts for the AI: only saved numbers, with day counts. */
    suspend fun facts(start: Long, end: Long): String {
        val sb = StringBuilder()
        for (day in start..end) {
            val c = combinedDay(day)
            val e = c.eff; val h = c.health
            sb.append(LocalDate.ofEpochDay(day)).append(": ")
            sb.append(if (!e.hasData) "no task data" else if (!e.result.applicable) "efficiency n/a" else String.format(Locale.US, "efficiency %.1f%% (%s, planned %.1f, credited %.1f%s)", e.result.score, e.result.statusLabel.lowercase(), e.result.plannedWeight, e.result.creditedWeight, e.result.cap?.let { ", core cap $it" } ?: ""))
            sb.append("; ")
            sb.append(if (h.score == null) "no health data" else String.format(Locale.US, "health %.1f%% (%s, coverage %.0f%%) [N %s, A %s, S %s, W %s]", h.score, h.statusLabel.lowercase(), h.coverage,
                h.cat("N").score?.roundToInt() ?: "–", h.cat("A").score?.roundToInt() ?: "–", h.cat("S").score?.roundToInt() ?: "–", h.cat("W").score?.roundToInt() ?: "–"))
            c.focusRes?.takeIf { it.enough }?.let { f -> sb.append("; ").append(String.format(Locale.US, "focus %.0f%%, distraction %.0f%% (distracting apps %d min, %d min of them in the first 2 h after waking)", f.focus, f.dist, f.distMin.roundToInt(), f.morningDistMin.roundToInt())) }
            sb.append('\n')
        }
        return sb.toString()
    }
}

object ReportAi {
    private const val RULES =
        "You write the improvement section of a personal Efficiency + Health + Focus report. Use ONLY the data given. Rules: " +
            "(1) Give at most 3 recommendations, each with five short labelled parts: Observed pattern; Evidence (dates, values, number of days, data coverage); " +
            "Possible contributor (a cautious hypothesis using wording like 'may be contributing' – or say there is not enough information); One realistic adjustment; What to track. " +
            "(2) Missing data is not zero and provisional days are not failures. (3) Never diagnose any disease, deficiency, sleep disorder or mental-health condition; if something looks like a medical concern, say the logs cannot tell and suggest a professional. " +
            "(4) Correlation is not causation: state how many days support a pattern and ask whether the explanation fits the user. (5) Do not change goals, diets or schedules – only suggest. " +
            "(6) If data is too thin, say so plainly. (7) When focus data exists (focus / distraction %, minutes in distracting apps, minutes right after waking), include practical tips about the sleep schedule, an early calm morning without feeds and the distracting apps; the first two hours after waking matter most. Plain text, no markdown tables, under 260 words."

    suspend fun analyse(g: AppGraph, facts: String, insightsText: String): String {
        if (!g.settings.aiReady()) return "Connect an AI key (menu → API & Subscription) to get a written analysis. The rule-based suggestions above work offline."
        val msgs = listOf(
            JSONObject().put("role", "system").put("content", RULES),
            JSONObject().put("role", "user").put("content", "DAILY RECORDS:\n$facts\nRULE-BASED OBSERVATIONS ALREADY SHOWN:\n$insightsText\nWrite the improvement section.")
        )
        return when (val r = g.llm.chat(msgs, null, maxTokens = 700, temperature = 0.4)) {
            is LlmClient.Result.Ok -> r.message.contentOrEmpty().replace(Regex("(?s)<think>.*?</think>"), "").trim().ifBlank { "The AI returned nothing – try again." }
            is LlmClient.Result.Fail -> "The AI could not be reached right now (${r.reason.lines().firstOrNull().orEmpty().take(120)}). The rule-based suggestions above still apply."
        }
    }
}
