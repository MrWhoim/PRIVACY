package com.privacy.app.data

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.privacy.app.widget.TaskWidget
import java.time.LocalDate

/**
 * All task changes go through here so the home-screen widget is always refreshed and today's history snapshot stays current.
 *
 * Efficiency of a day is calculated by [Efficiency] (weights, status credit, core cap, stars). This class feeds it:
 * today's live list for today, the saved snapshot for every other day, and the stars (hand-given Tough Commitment stars
 * + automatic Consistency stars). Confirming a day saves the result in `day_scores`; correcting a confirmed day
 * recalculates and saves it again (the revision counter goes up so the report can say so).
 */
class TaskRepo(
    private val dao: TaskDao,
    private val history: HistoryDao,
    private val context: Context
) {
    private val scores: ScoreDao by lazy { AppDatabase.get(context).scoreDao() }

    suspend fun add(
        title: String, daily: Boolean = false, priority: Int = 1, dueDay: Long? = null, optional: Boolean = false,
        repeatMask: Int = 0, difficulty: Int = 1, core: Boolean = false, dueDays: List<Long> = emptyList()
    ): Long {
        val days = dueDays.distinct().sorted()
        val id = dao.insert(
            TaskEntity(
                title = title.trim(), daily = daily || repeatMask != 0, priority = priority.coerceIn(0, 2), dueDay = if (days.isNotEmpty()) days.last() else dueDay,
                optional = optional, repeatMask = repeatMask, difficulty = difficulty.coerceIn(0, 2), core = core && !optional,
                dueDays = if (days.size > 1 || (days.size == 1 && days[0] != LocalDate.now().toEpochDay())) days.joinToString(",") else ""
            )
        )
        refreshWidget()
        snapshotToday()
        return id
    }

    suspend fun setStatus(id: Long, status: TaskStatus): TaskEntity? {
        val t = dao.get(id) ?: return null
        val today = LocalDate.now().toEpochDay()
        val partial = Efficiency.isPartial(t, today)
        // finishing a future / multi-day task early: it becomes a task of TODAY (full weight, full marks) and earns 2 commitment stars
        val n = t.copy(
            status = status, completedAt = if (status == TaskStatus.COMPLETED) System.currentTimeMillis() else null,
            dueDay = if (partial && status == TaskStatus.COMPLETED) today else t.dueDay,
            dueDays = if (partial && status == TaskStatus.COMPLETED) "" else t.dueDays,
            hiddenDay = if (partial && status == TaskStatus.COMPLETED) null else t.hiddenDay
        )
        dao.update(n)
        if (partial) {
            // "in progress" = 1 commitment point (once, no weight); "finished" = 2 more
            fun give(key: String, target: Int) {
                val given = earlyPrefs.getInt(key, 0)
                if (target > given) {
                    earlyPrefs.edit().putInt(key, target).apply()
                }
            }
            val ip = earlyPrefs.getInt("p${t.id}", 0)
            val dn = earlyPrefs.getInt("c${t.id}", 0)
            var add = 0
            if (status == TaskStatus.IN_PROGRESS && ip < 1) { give("p${t.id}", 1); add += 1 }
            if (status == TaskStatus.COMPLETED) {
                if (dn < 2) { give("c${t.id}", 2); add += 2 - dn }
            }
            if (add > 0) {
                val cur = history.stat(today)?.manualPoints ?: 0
                history.upsertStat(DayStatEntity(today, (cur + add).coerceIn(0, Efficiency.MAX_STARS)))
            }
        }
        refreshWidget()
        snapshotToday()
        return n
    }

    private val earlyPrefs by lazy { context.getSharedPreferences("dh_early_points", Context.MODE_PRIVATE) }

    suspend fun toggle(id: Long) {
        val t = dao.get(id) ?: return
        setStatus(id, if (t.status == TaskStatus.COMPLETED) TaskStatus.NOT_STARTED else TaskStatus.COMPLETED)
    }

    /** "Delete for today": hides a daily habit until tomorrow. */
    suspend fun hideToday(id: Long) {
        val t = dao.get(id) ?: return
        dao.update(t.copy(hiddenDay = LocalDate.now().toEpochDay()))
        refreshWidget()
        snapshotToday()
    }

    /** Hold-gesture: Not started / Completed → In progress, In progress → Not started. */
    suspend fun toggleProgress(id: Long) {
        val t = dao.get(id) ?: return
        setStatus(id, if (t.status == TaskStatus.IN_PROGRESS) TaskStatus.NOT_STARTED else TaskStatus.IN_PROGRESS)
    }

    suspend fun update(task: TaskEntity) {
        dao.update(task.copy(core = task.core && !task.optional))
        refreshWidget()
        snapshotToday()
    }

    /** Really deletes the task from the app. */
    suspend fun delete(id: Long) {
        dao.deleteById(id)
        refreshWidget()
        snapshotToday()
    }
    suspend fun restore(id: Long) = setStatus(id, TaskStatus.NOT_STARTED)

    /** The Tough Commitment stars the user gives himself by hand (0..3 for the day; the third only for an exceptional day). */
    suspend fun setManualPoints(day: Long, points: Int) {
        history.upsertStat(DayStatEntity(day, points.coerceIn(0, Efficiency.MAX_STARS)))
        refreshFinal(day)
    }

    /** Writes today's task list into history (replacing the earlier snapshot of today). */
    suspend fun snapshotToday() {
        try {
            val today = LocalDate.now().toEpochDay()
            val rows = dao.activeNow().map {
                TaskHistoryEntity(
                    today, it.id, it.title, it.status.name, it.priority, it.daily, it.optional,
                    it.difficulty, it.core, Efficiency.categoryOf(it.daily, it.repeatMask),
                    if (it.status == TaskStatus.COMPLETED) (it.completedAt ?: 0L) else 0L, Efficiency.isPartial(it, today)
                )
            }
            history.replaceDay(today, rows)
            refreshFinal(today)
        } catch (_: Exception) {
            // history is a nice-to-have; never break a task change because of it
        }
    }

    // ------------------------------------------------------------------------------------------ scores
    /** The consistency stars earned for [day] from the confirmed days before it. */
    suspend fun consStars(day: Long): Int = Efficiency.consistencyFromRows(day, history.range(day - 30, day - 1))

    /** The planned tasks of a day: today from the live list, other days from the saved snapshot. */
    suspend fun linesFor(day: Long): List<Efficiency.Line> =
        if (day == LocalDate.now().toEpochDay()) dao.activeNow().map { Efficiency.line(it) }
        else history.forDay(day).map { Efficiency.line(it) }

    @Suppress("UNUSED_PARAMETER")
    suspend fun evaluate(day: Long, confirmed: Boolean = true): Efficiency.Result {
        val tough = history.stat(day)?.manualPoints ?: 0
        return Efficiency.calc(linesFor(day), tough, consStars(day), true)
    }

    /** Confirms the day: saves the weights, credits, stars, core ratio, cap and final score. */
    suspend fun finalizeDay(day: Long): Efficiency.Result {
        val r = evaluate(day, confirmed = true)
        val old = scores.get(day)
        scores.upsert(
            DayScoreEntity(
                day = day, isFinal = true, score = if (r.applicable) r.score else -1f, taskPoints = r.taskPoints, starPoints = r.starPoints,
                plannedWeight = r.plannedWeight, creditedWeight = r.creditedWeight, corePct = r.corePct ?: -1f, cap = r.cap ?: 100f,
                toughStars = r.toughStars, consStars = r.consStars, tasksPlanned = r.planned, tasksDone = r.done, tasksDoing = r.doing,
                confirmedAt = System.currentTimeMillis(), revisions = if (old?.isFinal == true) old.revisions + 1 else 0, version = Efficiency.VERSION
            )
        )
        return r
    }

    /** A confirmed day was corrected – recalculate it so the report stays true (and counts the revision). */
    suspend fun refreshFinal(day: Long) {
        val old = scores.get(day) ?: return
        if (!old.isFinal) return
        finalizeDay(day)
    }

    suspend fun saved(day: Long): DayScoreEntity? = scores.get(day)

    /** Correct a task of a past day (from the report). The day's score is recalculated transparently. */
    suspend fun setHistoryStatus(day: Long, taskId: Long, status: TaskStatus) {
        val row = history.forDay(day).firstOrNull { it.taskId == taskId } ?: return
        history.putRow(row.copy(status = status.name))
        if (day == LocalDate.now().toEpochDay()) setStatus(taskId, status) else refreshFinal(day)
    }

    /** Edits one row of a past day (title, state, difficulty, optional, core) and recalculates that day's report. */
    suspend fun editHistoryRow(day: Long, taskId: Long, title: String, status: TaskStatus, difficulty: Int, optional: Boolean, core: Boolean) {
        val row = history.forDay(day).firstOrNull { it.taskId == taskId } ?: return
        history.putRow(row.copy(title = title.trim().ifBlank { row.title }, status = status.name, difficulty = difficulty, optional = optional, core = core && !optional,
            doneAt = if (status == TaskStatus.COMPLETED) (if (row.doneAt > 0) row.doneAt else System.currentTimeMillis()) else 0))
        if (day == LocalDate.now().toEpochDay()) { dao.get(taskId)?.let { setStatus(taskId, status) } } else refreshFinal(day)
    }
    suspend fun deleteHistoryRow(day: Long, taskId: Long) { history.deleteRow(day, taskId); if (day != LocalDate.now().toEpochDay()) refreshFinal(day) }

    /** A task that is added to a PAST day afterwards (the day is edited). It only exists in that day's history. */
    suspend fun addHistoryTask(day: Long, title: String) {
        if (title.isBlank()) return
        val id = -(System.currentTimeMillis() % 1_000_000_000L) - 1
        history.putRow(TaskHistoryEntity(day, id, title.trim(), TaskStatus.COMPLETED.name, 1, false, false, 1, false, 0, System.currentTimeMillis(), false))
        refreshFinal(day)
    }

    suspend fun refreshWidget() {
        try {
            TaskWidget().updateAll(context)
        } catch (_: Exception) {
            // no widget placed yet – nothing to refresh
        }
    }
}
