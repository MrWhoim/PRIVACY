package com.privacy.app.data

import kotlin.math.max
import kotlin.math.min

/**
 * Efficiency of Day – one place so the Tasks screen, the widget, the chat bar and the reports agree.
 *
 *   Task Weight   = Difficulty × Priority × Category          (capped at 6.5)
 *                   Difficulty: Easy 1 · Normal 2 · Hard 3.5
 *                   Priority:   Low 0.8 · Normal 1 · High 1.3
 *                   Category:   Due-date / one-time 1.4 · Daily 0.6 · Weekly 1
 *   Status credit = Not started 0 % · In progress 50 % · Done 100 %
 *   Task Points   = 85 × min(1, Credited / max(Planned, 8))   – every planned task stays in "Planned"
 *   Star Points   = 3.75 per star, at most 4 stars = 15 points (Tough Commitment + Consistency together)
 *   Core cap      = 0 % core done → ≤ 54 · under 50 % → ≤ 74 · under 100 % → ≤ 89 · all done → no cap
 *   Final         = min(Task Points + Star Points, cap)
 *
 * No planned (required) tasks = Not Applicable, never an automatic 100. Optional tasks are bonus work and are not scored.
 * Until the user confirms the day the result is "provisional".
 */
object Efficiency {
    const val VERSION = 2
    const val MAX_STARS = 4
    /** Kept so older call sites keep compiling. */
    const val MAX_POINTS = MAX_STARS
    /** 4 stars share the 15 commitment points (3.75 each), so a day still tops out at 85 + 15 = 100. */
    const val STAR_POINTS = 3.75f
    const val TASK_MAX = 85f
    const val DAILY_TARGET = 8f
    const val WEIGHT_CAP = 6.5f

    const val CAT_DUE = 0
    const val CAT_DAILY = 1
    const val CAT_WEEKLY = 2

    fun difficultyFactor(d: Int): Float = when (d) { 0 -> 1f; 2 -> 3.5f; else -> 2f }
    fun priorityFactor(p: Int): Float = when (p) { 0 -> 0.8f; 2 -> 1.3f; else -> 1f }
    fun categoryFactor(c: Int): Float = when (c) { CAT_DUE -> 1.4f; CAT_DAILY -> 0.6f; else -> 1f }
    fun categoryOf(daily: Boolean, repeatMask: Int): Int = if (repeatMask != 0) CAT_WEEKLY else if (daily) CAT_DAILY else CAT_DUE
    fun difficultyName(d: Int) = when (d) { 0 -> "Easy"; 2 -> "Hard"; else -> "Normal" }
    fun priorityName(p: Int) = when (p) { 0 -> "Low"; 2 -> "High"; else -> "Normal" }
    fun categoryName(c: Int) = when (c) { CAT_DAILY -> "Daily"; CAT_WEEKLY -> "Weekly"; else -> "Due date / one-time" }

    fun weight(difficulty: Int, priority: Int, category: Int): Float =
        min(WEIGHT_CAP, difficultyFactor(difficulty) * priorityFactor(priority) * categoryFactor(category))

    fun credit(status: TaskStatus): Float = when (status) { TaskStatus.COMPLETED -> 1f; TaskStatus.IN_PROGRESS -> 0.5f; else -> 0f }

    /** One planned task, whether it comes from today's live list or from a saved day. */
    data class Line(
        val key: Long, val title: String, val status: TaskStatus, val difficulty: Int, val priority: Int,
        val category: Int, val core: Boolean, val optional: Boolean,
        /** A day before the LAST due date of a multi-day task: it carries no weight – "in progress" earns one commitment point, finishing early two. */
        val partial: Boolean = false
    ) {
        val weight: Float get() = weight(difficulty, priority, category) * (if (partial) 0.5f else 1f)
        val credited: Float get() = weight * (if (partial && status == TaskStatus.IN_PROGRESS) 1f else credit(status))
    }

    /** True on a day before the last chosen due date of a multi-day task. */
    fun isPartial(t: TaskEntity, today: Long = java.time.LocalDate.now().toEpochDay()): Boolean =
        t.dueDays.isNotBlank() && t.dueDay != null && today < t.dueDay

    fun line(t: TaskEntity) = Line(t.id, t.title, t.status, t.difficulty, t.priority, categoryOf(t.daily, t.repeatMask), t.core, t.optional, isPartial(t))
    fun line(h: TaskHistoryEntity) = Line(
        h.taskId, h.title, runCatching { TaskStatus.valueOf(h.status) }.getOrDefault(TaskStatus.NOT_STARTED),
        h.difficulty, h.priority, h.category, h.core, h.optional, h.partial
    )

    data class Result(
        /** False = Not Applicable (no planned tasks). */
        val applicable: Boolean,
        /** 0..100 */
        val score: Float,
        val taskPoints: Float,
        val starPoints: Float,
        val raw: Float,
        val plannedWeight: Float,
        val creditedWeight: Float,
        /** null = no core tasks designated. */
        val corePct: Float?,
        /** null = no cap applied. */
        val cap: Float?,
        val toughStars: Int,
        val consStars: Int,
        val planned: Int,
        val done: Int,
        val doing: Int,
        val notStarted: Int,
        val provisional: Boolean,
        /** What is missing before the score can be final. */
        val missing: List<String>
    ) {
        val stars: Int get() = min(MAX_STARS, toughStars + consStars)
        val fraction: Float get() = if (applicable) (score / 100f).coerceIn(0f, 1f) else 0f
        val statusLabel: String get() = if (!applicable) "Not applicable" else if (provisional) "Provisional" else "Final"
        /** Completion ratio used by the consistency streak (credited ÷ planned, no daily-target floor). */
        val completionRatio: Float get() = if (plannedWeight > 0f) creditedWeight / plannedWeight else 0f
    }

    fun capFor(corePct: Float?): Float? = when {
        corePct == null -> null
        corePct >= 99.999f -> null
        corePct <= 0.0001f -> 54f
        corePct < 50f -> 74f
        else -> 89f
    }

    @Suppress("UNUSED_PARAMETER")
    fun calc(lines: List<Line>, toughStars: Int, consStars: Int, confirmed: Boolean = true): Result {
        val scored = lines.filter { !it.optional && !it.partial }
        val tough = toughStars.coerceIn(0, MAX_STARS)
        val cons = consStars.coerceIn(0, 2)
        if (scored.isEmpty()) {
            return Result(false, 0f, 0f, 0f, 0f, 0f, 0f, null, null, tough, cons, 0, 0, 0, 0, !confirmed,
                listOf("No planned tasks for this day"))
        }
        val planned = scored.sumOf { it.weight.toDouble() }.toFloat()
        val credited = scored.sumOf { it.credited.toDouble() }.toFloat()
        val taskPoints = TASK_MAX * min(1f, credited / max(planned, DAILY_TARGET))
        val stars = min(MAX_STARS, tough + cons)
        val starPoints = STAR_POINTS * stars
        // (the "core task" cap was removed: the score is exactly tasks + stars)
        val corePct: Float? = null
        val cap: Float? = null
        val raw = taskPoints + starPoints
        val score = min(raw, 100f)
        val done = scored.count { it.status == TaskStatus.COMPLETED }
        val doing = scored.count { it.status == TaskStatus.IN_PROGRESS }
        val notStarted = scored.size - done - doing
        val missing = emptyList<String>()
        return Result(true, score, taskPoints, starPoints, raw, planned, credited, corePct, cap, tough, cons, scored.size, done, doing, notStarted, !confirmed, missing)
    }

    /**
     * Consistency stars for [day]: looks at the days before it. A confirmed day with a completion ratio ≥ 60 % advances the streak,
     * a confirmed day below that ends it, and a missing / unconfirmed day neither advances nor ends it (it is not a confirmed failure).
     * 3 qualifying days in a row = 1 star, 7 = 2 stars (instead of the 1).
     */
    fun consistencyStars(day: Long, scores: Map<Long, DayScoreEntity>): Int {
        var streak = 0
        var d = day - 1
        var looked = 0
        while (looked < 28 && streak < 7) {
            val s = scores[d]
            if (s != null && s.isFinal && s.score >= 0f) {
                val ratio = if (s.plannedWeight > 0f) s.creditedWeight / s.plannedWeight else 0f
                if (ratio >= 0.6f) streak++ else break
            }
            d--; looked++
        }
        return if (streak >= 7) 2 else if (streak >= 3) 1 else 0
    }

    /**
     * Consistency from the saved task days: walk back from the day before [day]. A day with tasks and a completion ratio ≥ 60 % advances the streak,
     * a day with tasks below that ends it, and a day with no tasks / no record neither advances nor ends it.
     */
    fun consistencyFromRows(day: Long, rows: List<TaskHistoryEntity>): Int {
        val byDay = rows.filter { !it.optional }.groupBy { it.day }
        var streak = 0
        var d = day - 1
        var looked = 0
        while (looked < 28 && streak < 7) {
            val l = byDay[d]
            if (l != null) {
                val r = calc(l.map { line(it) }, 0, 0, true)
                if (r.applicable) { if (r.completionRatio >= 0.6f) streak++ else break }
            }
            d--; looked++
        }
        return if (streak >= 7) 2 else if (streak >= 3) 1 else 0
    }

    // ---- compatibility helpers (widget, chat bar, calendar): a 0..1 fraction ----
    @Suppress("UNUSED_PARAMETER")
    fun bonus(optionalDone: Int, manual: Int, cons: Int = 0): Int = min(MAX_STARS, manual.coerceIn(0, MAX_STARS) + cons.coerceIn(0, 2))

    fun ofTasks(tasks: List<TaskEntity>, manual: Int, cons: Int = 0): Float =
        calc(tasks.map { line(it) }, manual, cons, confirmed = false).fraction

    fun ofHistory(rows: List<TaskHistoryEntity>, manual: Int, cons: Int = 0): Float =
        calc(rows.map { line(it) }, manual, cons, confirmed = false).fraction
}
