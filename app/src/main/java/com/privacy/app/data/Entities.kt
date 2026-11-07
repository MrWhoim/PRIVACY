package com.privacy.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Same life-cycle as your Google Sheet: Not Started / In Progress / Completed / Deleted (= archived). */
enum class TaskStatus { NOT_STARTED, IN_PROGRESS, COMPLETED, DELETED }

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val status: TaskStatus = TaskStatus.NOT_STARTED,
    /** Daily tasks come back as "Not started" every morning; other completed tasks get archived. */
    val daily: Boolean = false,
    /** 0 = low, 1 = normal, 2 = high */
    val priority: Int = 1,
    val dueDay: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    /** "Delete for today": a daily habit hidden on this day (epoch day) that comes back tomorrow. */
    val hiddenDay: Long? = null,
    /** Optional (bonus) task: never lowers your day's percentage, and completing it earns one commitment point. */
    @ColumnInfo(defaultValue = "0") val optional: Boolean = false,
    /** Weekly pattern: bit 0 = Monday … bit 6 = Sunday. 0 = every day (when [daily]) / no pattern. */
    @ColumnInfo(defaultValue = "0") val repeatMask: Int = 0,
    /** 0 = Easy, 1 = Normal, 2 = Hard (weights 1 / 2 / 3.5 in the efficiency formula). */
    @ColumnInfo(defaultValue = "1") val difficulty: Int = 1,
    /** Core commitment: an essential task whose completion caps the day's score (see [Efficiency]). */
    @ColumnInfo(defaultValue = "0") val core: Boolean = false,
    /** Due-date tasks over several days: the chosen days (epoch days, comma separated). Empty = a single due date / no due date. [dueDay] is the LAST of them. */
    @ColumnInfo(defaultValue = "") val dueDays: String = ""
) {
    /** The chosen days as numbers. */
    val dueDayList: List<Long> get() = dueDays.split(',').mapNotNull { it.trim().toLongOrNull() }
}

/**
 * What a day looked like, kept so past days can be browsed read-only. One row per task per day,
 * rewritten whenever today's tasks change (so the last state of the day is what stays in history).
 */
@Entity(tableName = "task_history", primaryKeys = ["day", "taskId"])
data class TaskHistoryEntity(
    val day: Long,
    val taskId: Long,
    val title: String,
    val status: String,
    val priority: Int,
    val daily: Boolean,
    val optional: Boolean,
    @ColumnInfo(defaultValue = "1") val difficulty: Int = 1,
    @ColumnInfo(defaultValue = "0") val core: Boolean = false,
    /** 0 = due-date / one-time, 1 = daily, 2 = weekly (chosen weekdays). */
    @ColumnInfo(defaultValue = "0") val category: Int = 0,
    /** When the task was completed that day (0 = not completed or not recorded) – used for the entry-impact graph. */
    @ColumnInfo(defaultValue = "0") val doneAt: Long = 0,
    /** A day before the last due date of a multi-day task: the task counts for half its weight that day. */
    @ColumnInfo(defaultValue = "0") val partial: Boolean = false
)

/** Commitment points the user gave himself by hand on a given day (they count toward that day's percentage). */
@Entity(tableName = "day_stats")
data class DayStatEntity(
    @PrimaryKey val day: Long,
    val manualPoints: Int = 0
)

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val body: String,
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String = "general",
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)

/** role = user | assistant | action (action rows are UI-only "✓ Added task" chips). */
@Entity(tableName = "chat")
data class ChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val conversationId: Long = 0
)

/** One saved chat. Messages point at it through [ChatEntity.conversationId]. */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class AutoAction { NOTIFY_TASKS, NOTIFY_TEXT, AI_PROMPT, NOTES_AUDIO }

/**
 * Something that runs by itself at a set time: a notification with your task list, a plain reminder,
 * or a prompt the AI works on (with web access) whose answer is saved as a note.
 * [daysMask]: bit0 = Monday … bit6 = Sunday (127 = every day). When [dateDay] is set it fires once on that day.
 */
@Entity(tableName = "automations")
data class AutomationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val action: String,
    val message: String,
    val timeMinutes: Int,
    val daysMask: Int,
    val dateDay: Long? = null,
    val enabled: Boolean = true,
    val lastRunAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
)

/** Encrypted private entry. [blob] = AES-256-GCM(iv + ciphertext) of a small JSON document. */
@Entity(tableName = "vault")
data class VaultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val blob: ByteArray,
    val share: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val prompt: String,
    val enabled: Boolean = true,
    val lastRunDay: Long = -1,
    val createdAt: Long = System.currentTimeMillis()
)


/**
 * One day's Efficiency result, saved when the user confirms the day (isFinal = true) so the report keeps
 * the weights, credits, stars, core ratio and cap that applied at the time.
 */
@Entity(tableName = "day_scores")
data class DayScoreEntity(
    @PrimaryKey val day: Long,
    val isFinal: Boolean,
    /** 0..100, or -1 when the day had no planned tasks (Not Applicable). */
    val score: Float,
    val taskPoints: Float,
    val starPoints: Float,
    val plannedWeight: Float,
    val creditedWeight: Float,
    /** -1 = no core tasks designated. */
    val corePct: Float,
    /** 100 = no cap applied. */
    val cap: Float,
    val toughStars: Int,
    val consStars: Int,
    val tasksPlanned: Int,
    val tasksDone: Int,
    val tasksDoing: Int,
    val confirmedAt: Long,
    /** How many times a confirmed day was recalculated after a correction. */
    val revisions: Int,
    val version: Int
)

/**
 * One thing the user ate, did, slept or drank. The chat assistant and the manual form write the very same rows.
 * category: FOOD | ACTIVITY | SLEEP | WATER | WEIGHT.  Amounts use fixed units: ml for water, hours for sleep, kg for weight.
 */
@Entity(tableName = "health_entries")
data class HealthEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Local calendar day (epoch day). Sleep belongs to the day the user woke up. */
    val day: Long,
    val category: String,
    val label: String,
    val amount: Double? = null,
    val unit: String? = null,
    val kcal: Double? = null,
    val protein: Double? = null,
    /** breakfast | lunch | dinner | snack (food only). */
    val meal: String? = null,
    /** Meal-balance rating 100 / 70 / 40 (food only; null = not rated). */
    val balance: Int? = null,
    val originalText: String = "",
    /** manual | chat | plan */
    val source: String = "manual",
    /** True when numbers were estimated by the app or the AI rather than typed by the user. */
    val estimated: Boolean = false,
    val confirmed: Boolean = true,
    /** low | medium | high */
    val confidence: String = "high",
    val note: String = "",
    val dedupeKey: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
    /** Edit history, one line per change. */
    val history: String = "",
    /** Food: carbohydrates, fat and fibre in grams (null = unknown, never guessed). */
    val carbs: Double? = null,
    val fat: Double? = null,
    val fiber: Double? = null,
    /** Food: comma-separated food kinds (grain, protein, legume, dairy, veg, fruit, nuts) used for the variety score. */
    val groups: String = "",
    /** Activity: JSON with the estimate (met, minutes, kcal, type, intensity, stimulus, source). */
    val extra: String = ""
)

/** Per-day health bookkeeping: which categories the user says are fully logged, and the saved final result. */
@Entity(tableName = "health_days")
data class HealthDayEntity(
    @PrimaryKey val day: Long,
    /** bit0 food, bit1 activity, bit2 sleep, bit3 water: "everything for this category is logged". */
    val completeMask: Int = 0,
    /** The user explicitly said no exercise was done (only then does a missed activity goal score 0). */
    val noActivity: Boolean = false,
    val confirmed: Boolean = false,
    val confirmedAt: Long = 0,
    val score: Float = -1f,
    val coverage: Float = 0f,
    val nScore: Float = -1f,
    val aScore: Float = -1f,
    val sScore: Float = -1f,
    val wScore: Float = -1f,
    /** JSON: the goals and inputs as they were when the day was confirmed. */
    val snapshot: String = "",
    val revisions: Int = 0,
    val version: Int = 1
)

/** A version of the personal targets, effective from a day onward – so changing a goal never rewrites old reports. */
@Entity(tableName = "health_goals")
data class HealthGoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val effectiveDay: Long,
    val json: String,
    val createdAt: Long = System.currentTimeMillis()
)


/**
 * One thing the user logged by hand for the Focus score (or a rating of automatically tracked app use).
 * kind POS = useful time (cat work | learning | exercise | personal), DIST = distraction (cat short | social | video | game, with a rating).
 */
@Entity(tableName = "focus_logs")
data class FocusLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: Long,
    val startAt: Long,
    val minutes: Int,
    val kind: String,
    val cat: String,
    val label: String,
    val rating: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
