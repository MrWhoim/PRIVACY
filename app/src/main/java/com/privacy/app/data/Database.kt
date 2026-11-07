package com.privacy.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks WHERE status != 'DELETED' AND (hiddenDay IS NULL OR hiddenDay != CAST(strftime('%s','now','localtime') / 86400 AS INTEGER)) AND (repeatMask = 0 OR ((repeatMask >> ((CAST(strftime('%w','now','localtime') AS INTEGER) + 6) % 7)) & 1) = 1) AND (dueDays = '' OR dueDay < CAST(strftime('%s','now','localtime') / 86400 AS INTEGER) OR instr(',' || dueDays || ',', ',' || CAST(strftime('%s','now','localtime') / 86400 AS INTEGER) || ',') > 0) ORDER BY CASE status WHEN 'COMPLETED' THEN 1 ELSE 0 END, priority DESC, createdAt ASC")
    fun observeActive(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE status != 'DELETED' AND (hiddenDay IS NULL OR hiddenDay != CAST(strftime('%s','now','localtime') / 86400 AS INTEGER)) AND (repeatMask = 0 OR ((repeatMask >> ((CAST(strftime('%w','now','localtime') AS INTEGER) + 6) % 7)) & 1) = 1) AND (dueDays = '' OR dueDay < CAST(strftime('%s','now','localtime') / 86400 AS INTEGER) OR instr(',' || dueDays || ',', ',' || CAST(strftime('%s','now','localtime') / 86400 AS INTEGER) || ',') > 0)")
    suspend fun activeNow(): List<TaskEntity>

    /** Not on today's list: due on later days, or removed "for today". */
    @Query("SELECT * FROM tasks WHERE status != 'DELETED' AND ((hiddenDay = CAST(strftime('%s','now','localtime') / 86400 AS INTEGER)) OR (dueDays != '' AND dueDay >= CAST(strftime('%s','now','localtime') / 86400 AS INTEGER) AND instr(',' || dueDays || ',', ',' || CAST(strftime('%s','now','localtime') / 86400 AS INTEGER) || ',') = 0)) ORDER BY dueDay ASC, createdAt ASC")
    fun observeFuture(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE status = 'DELETED' ORDER BY id DESC LIMIT 200")
    fun observeArchive(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks")
    suspend fun all(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: Long): TaskEntity?

    @Query("SELECT * FROM tasks WHERE status IN ('NOT_STARTED','IN_PROGRESS') AND (hiddenDay IS NULL OR hiddenDay != CAST(strftime('%s','now','localtime') / 86400 AS INTEGER)) AND (repeatMask = 0 OR ((repeatMask >> ((CAST(strftime('%w','now','localtime') AS INTEGER) + 6) % 7)) & 1) = 1) AND (dueDays = '' OR dueDay < CAST(strftime('%s','now','localtime') / 86400 AS INTEGER) OR instr(',' || dueDays || ',', ',' || CAST(strftime('%s','now','localtime') / 86400 AS INTEGER) || ',') > 0) ORDER BY priority DESC, createdAt ASC")
    suspend fun open(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE status != 'DELETED' AND (hiddenDay IS NULL OR hiddenDay != CAST(strftime('%s','now','localtime') / 86400 AS INTEGER)) AND (repeatMask = 0 OR ((repeatMask >> ((CAST(strftime('%w','now','localtime') AS INTEGER) + 6) % 7)) & 1) = 1) AND (dueDays = '' OR dueDay < CAST(strftime('%s','now','localtime') / 86400 AS INTEGER) OR instr(',' || dueDays || ',', ',' || CAST(strftime('%s','now','localtime') / 86400 AS INTEGER) || ',') > 0) ORDER BY CASE status WHEN 'COMPLETED' THEN 1 ELSE 0 END, priority DESC, createdAt ASC LIMIT 20")
    suspend fun widgetTasks(): List<TaskEntity>

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Insert
    suspend fun insert(task: TaskEntity): Long

    @Update
    suspend fun update(task: TaskEntity)

    @Query("UPDATE tasks SET status = 'NOT_STARTED', completedAt = NULL WHERE status IN ('COMPLETED','IN_PROGRESS') AND daily = 1")
    suspend fun resetDaily()

    /** A multi-day task starts every new day again (yesterday's "in progress" must not carry credit over). */
    @Query("UPDATE tasks SET status = 'NOT_STARTED' WHERE status = 'IN_PROGRESS' AND dueDays != ''")
    suspend fun resetMultiDay()

    @Query("UPDATE tasks SET status = 'DELETED' WHERE status = 'COMPLETED' AND daily = 0")
    suspend fun archiveCompleted()
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes ORDER BY pinned DESC, updatedAt DESC")
    fun observe(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes")
    suspend fun all(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun get(id: Long): NoteEntity?

    @Query("SELECT * FROM notes WHERE title LIKE '%' || :q || '%' OR body LIKE '%' || :q || '%' ORDER BY updatedAt DESC LIMIT 10")
    suspend fun search(q: String): List<NoteEntity>

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC LIMIT :n")
    suspend fun recent(n: Int): List<NoteEntity>

    @Insert
    suspend fun insert(note: NoteEntity): Long

    @Update
    suspend fun update(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY id DESC")
    fun observe(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY id DESC")
    suspend fun all(): List<MemoryEntity>

    @Insert
    suspend fun insert(memory: MemoryEntity): Long

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM (SELECT * FROM chat WHERE conversationId = :cid ORDER BY id DESC LIMIT 300) ORDER BY id ASC")
    fun observe(cid: Long): Flow<List<ChatEntity>>

    @Query("SELECT * FROM (SELECT * FROM chat WHERE conversationId = :cid AND role != 'action' ORDER BY id DESC LIMIT :n) ORDER BY id ASC")
    suspend fun recent(cid: Long, n: Int): List<ChatEntity>

    @Query("SELECT * FROM chat ORDER BY id ASC")
    suspend fun all(): List<ChatEntity>

    @Query("SELECT * FROM chat WHERE conversationId = :cid ORDER BY id ASC")
    suspend fun forConversation(cid: Long): List<ChatEntity>

    @Query("SELECT COUNT(*) FROM chat WHERE conversationId = :cid")
    suspend fun count(cid: Long): Int

    @Query("SELECT * FROM chat WHERE role != 'action' AND content LIKE '%' || :q || '%' ORDER BY id DESC LIMIT :n")
    suspend fun search(q: String, n: Int): List<ChatEntity>

    @Insert
    suspend fun insert(chat: ChatEntity): Long

    @Query("DELETE FROM chat WHERE conversationId = :cid")
    suspend fun clear(cid: Long)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observe(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    suspend fun all(): List<ConversationEntity>

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC LIMIT :n")
    suspend fun recent(n: Int): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: Long): ConversationEntity?

    @Insert
    suspend fun insert(c: ConversationEntity): Long

    @Update
    suspend fun update(c: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface AutomationDao {
    @Query("SELECT * FROM automations ORDER BY id ASC")
    fun observe(): Flow<List<AutomationEntity>>

    @Query("SELECT * FROM automations ORDER BY id ASC")
    suspend fun all(): List<AutomationEntity>

    @Query("SELECT * FROM automations WHERE id = :id")
    suspend fun get(id: Long): AutomationEntity?

    @Insert
    suspend fun insert(a: AutomationEntity): Long

    @Update
    suspend fun update(a: AutomationEntity)

    @Query("DELETE FROM automations WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface VaultDao {
    @Query("SELECT * FROM vault ORDER BY createdAt ASC")
    fun observe(): Flow<List<VaultEntity>>

    @Query("SELECT * FROM vault ORDER BY createdAt ASC")
    suspend fun all(): List<VaultEntity>

    @Insert
    suspend fun insert(entry: VaultEntity): Long

    @Update
    suspend fun update(entry: VaultEntity)

    @Query("DELETE FROM vault WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM vault")
    suspend fun deleteAll()
}

@Dao
interface RoutineDao {
    @Query("SELECT * FROM routines ORDER BY id ASC")
    fun observe(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routines ORDER BY id ASC")
    suspend fun all(): List<RoutineEntity>

    @Query("SELECT * FROM routines WHERE enabled = 1")
    suspend fun enabled(): List<RoutineEntity>

    @Insert
    suspend fun insert(routine: RoutineEntity): Long

    @Update
    suspend fun update(routine: RoutineEntity)

    @Query("DELETE FROM routines WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
abstract class HistoryDao {
    @Query("SELECT * FROM task_history WHERE day = :day ORDER BY priority DESC, taskId ASC")
    abstract suspend fun forDay(day: Long): List<TaskHistoryEntity>

    @Query("SELECT * FROM task_history WHERE day BETWEEN :from AND :to")
    abstract fun observeRange(from: Long, to: Long): Flow<List<TaskHistoryEntity>>

    @Query("SELECT * FROM task_history WHERE day = :day ORDER BY priority DESC, taskId ASC")
    abstract fun observeDay(day: Long): Flow<List<TaskHistoryEntity>>

    @Query("DELETE FROM task_history WHERE day = :day")
    abstract suspend fun clearDay(day: Long)

    @Query("DELETE FROM day_stats WHERE day = :day")
    abstract suspend fun deleteStat(day: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(rows: List<TaskHistoryEntity>)

    @Transaction
    open suspend fun replaceDay(day: Long, rows: List<TaskHistoryEntity>) {
        clearDay(day)
        if (rows.isNotEmpty()) insertAll(rows)
    }

    @Query("SELECT * FROM day_stats WHERE day = :day")
    abstract suspend fun stat(day: Long): DayStatEntity?

    @Query("SELECT * FROM day_stats WHERE day = :day")
    abstract fun observeStat(day: Long): Flow<DayStatEntity?>

    @Query("SELECT * FROM day_stats WHERE day BETWEEN :from AND :to")
    abstract fun observeStats(from: Long, to: Long): Flow<List<DayStatEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertStat(s: DayStatEntity)

    @Query("SELECT * FROM task_history WHERE day BETWEEN :from AND :to ORDER BY day ASC, taskId ASC")
    abstract suspend fun range(from: Long, to: Long): List<TaskHistoryEntity>

    @Query("SELECT * FROM day_stats WHERE day BETWEEN :from AND :to")
    abstract suspend fun statsIn(from: Long, to: Long): List<DayStatEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putRow(r: TaskHistoryEntity)

    @Query("DELETE FROM task_history WHERE day = :day AND taskId = :taskId")
    abstract suspend fun deleteRow(day: Long, taskId: Long)
}

@Dao
interface ScoreDao {
    @Query("SELECT * FROM day_scores WHERE day = :day")
    suspend fun get(day: Long): DayScoreEntity?

    @Query("SELECT * FROM day_scores WHERE day = :day")
    fun observe(day: Long): Flow<DayScoreEntity?>

    @Query("SELECT * FROM day_scores WHERE day BETWEEN :from AND :to")
    suspend fun range(from: Long, to: Long): List<DayScoreEntity>

    @Query("SELECT * FROM day_scores WHERE day BETWEEN :from AND :to")
    fun observeRange(from: Long, to: Long): Flow<List<DayScoreEntity>>

    @Query("SELECT * FROM day_scores")
    suspend fun all(): List<DayScoreEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: DayScoreEntity)

    @Query("DELETE FROM day_scores WHERE day = :day")
    suspend fun deleteDay(day: Long)
}

@Dao
interface HealthDao {
    @Query("SELECT * FROM health_entries WHERE day = :day AND deleted = 0 ORDER BY createdAt ASC")
    suspend fun forDay(day: Long): List<HealthEntryEntity>

    @Query("SELECT * FROM health_entries WHERE day = :day AND deleted = 0 ORDER BY createdAt ASC")
    fun observeDay(day: Long): Flow<List<HealthEntryEntity>>

    @Query("SELECT * FROM health_entries WHERE day BETWEEN :from AND :to AND deleted = 0 ORDER BY day ASC, createdAt ASC")
    suspend fun range(from: Long, to: Long): List<HealthEntryEntity>

    @Query("SELECT * FROM health_entries WHERE day BETWEEN :from AND :to AND deleted = 0 ORDER BY day ASC, createdAt ASC")
    fun observeRange(from: Long, to: Long): Flow<List<HealthEntryEntity>>

    @Query("SELECT * FROM health_entries WHERE id = :id")
    suspend fun get(id: Long): HealthEntryEntity?

    @Query("SELECT * FROM health_entries")
    suspend fun allEntries(): List<HealthEntryEntity>

    @Insert
    suspend fun insert(e: HealthEntryEntity): Long

    @Update
    suspend fun update(e: HealthEntryEntity)

    @Query("SELECT * FROM health_days WHERE day = :day")
    suspend fun dayRow(day: Long): HealthDayEntity?

    @Query("SELECT * FROM health_days WHERE day = :day")
    fun observeDayRow(day: Long): Flow<HealthDayEntity?>

    @Query("SELECT * FROM health_days WHERE day BETWEEN :from AND :to")
    suspend fun dayRows(from: Long, to: Long): List<HealthDayEntity>

    @Query("SELECT * FROM health_days WHERE day BETWEEN :from AND :to")
    fun observeDayRows(from: Long, to: Long): Flow<List<HealthDayEntity>>

    @Query("SELECT * FROM health_days")
    suspend fun allDays(): List<HealthDayEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDay(d: HealthDayEntity)

    @Query("UPDATE health_entries SET deleted = 1 WHERE day = :day")
    suspend fun deleteDayEntries(day: Long)

    @Query("DELETE FROM health_days WHERE day = :day")
    suspend fun deleteDayRow(day: Long)

    @Query("SELECT * FROM health_goals WHERE effectiveDay <= :day ORDER BY effectiveDay DESC, id DESC LIMIT 1")
    suspend fun goalFor(day: Long): HealthGoalEntity?

    @Query("SELECT * FROM health_goals ORDER BY effectiveDay ASC, id ASC")
    suspend fun allGoals(): List<HealthGoalEntity>

    @Query("SELECT * FROM health_goals ORDER BY id ASC")
    fun observeGoals(): Flow<List<HealthGoalEntity>>

    @Insert
    suspend fun insertGoal(g: HealthGoalEntity): Long
}

@Dao
interface FocusDao {
    @Query("SELECT * FROM focus_logs WHERE day = :day ORDER BY startAt ASC")
    suspend fun forDay(day: Long): List<FocusLogEntity>

    @Query("SELECT * FROM focus_logs WHERE day = :day ORDER BY startAt ASC")
    fun observeDay(day: Long): Flow<List<FocusLogEntity>>

    @Query("SELECT * FROM focus_logs ORDER BY startAt ASC")
    suspend fun all(): List<FocusLogEntity>

    @Insert
    suspend fun insert(e: FocusLogEntity): Long

    @Update
    suspend fun update(e: FocusLogEntity)

    @Query("DELETE FROM focus_logs WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(
    entities = [
        TaskEntity::class, NoteEntity::class, MemoryEntity::class, ChatEntity::class, VaultEntity::class,
        RoutineEntity::class, ConversationEntity::class, AutomationEntity::class,
        TaskHistoryEntity::class, DayStatEntity::class,
        DayScoreEntity::class, HealthEntryEntity::class, HealthDayEntity::class, HealthGoalEntity::class, FocusLogEntity::class
    ],
    version = 9,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun noteDao(): NoteDao
    abstract fun memoryDao(): MemoryDao
    abstract fun chatDao(): ChatDao
    abstract fun vaultDao(): VaultDao
    abstract fun routineDao(): RoutineDao
    abstract fun conversationDao(): ConversationDao
    abstract fun automationDao(): AutomationDao
    abstract fun historyDao(): HistoryDao
    abstract fun scoreDao(): ScoreDao
    abstract fun healthDao(): HealthDao
    abstract fun focusDao(): FocusDao

    companion object {
        /** v1 → v2: saved chats (conversations) and automations. Existing messages move into one "Earlier chat". */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `conversations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `automations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `action` TEXT NOT NULL, `message` TEXT NOT NULL, `timeMinutes` INTEGER NOT NULL, `daysMask` INTEGER NOT NULL, `dateDay` INTEGER, `enabled` INTEGER NOT NULL, `lastRunAt` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("ALTER TABLE `chat` ADD COLUMN `conversationId` INTEGER NOT NULL DEFAULT 0")
                var count = 0L
                var first = 0L
                var last = 0L
                db.query("SELECT COUNT(*), MIN(createdAt), MAX(createdAt) FROM chat").use { c ->
                    if (c.moveToFirst()) { count = c.getLong(0); first = c.getLong(1); last = c.getLong(2) }
                }
                if (count > 0) {
                    db.execSQL("INSERT INTO conversations (title, createdAt, updatedAt) VALUES ('Earlier chat', $first, $last)")
                    db.execSQL("UPDATE chat SET conversationId = (SELECT MAX(id) FROM conversations)")
                }
            }
        }

        /** v2 → v3: "delete a daily habit for today only". */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `hiddenDay` INTEGER")
            }
        }

        /** v3 → v4: optional tasks, and a per-day history of tasks + hand-given commitment points. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `optional` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS `task_history` (`day` INTEGER NOT NULL, `taskId` INTEGER NOT NULL, `title` TEXT NOT NULL, `status` TEXT NOT NULL, `priority` INTEGER NOT NULL, `daily` INTEGER NOT NULL, `optional` INTEGER NOT NULL, PRIMARY KEY(`day`, `taskId`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `day_stats` (`day` INTEGER NOT NULL, `manualPoints` INTEGER NOT NULL, PRIMARY KEY(`day`))")
            }
        }

        /** v4 → v5: tasks can repeat on chosen weekdays (bit 0 = Monday … bit 6 = Sunday; 0 = no weekly pattern). */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `repeatMask` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v5 → v6: task difficulty + core flag, saved day scores, and the whole health log (entries, per-day state, goal versions). */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `difficulty` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `core` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `task_history` ADD COLUMN `difficulty` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `task_history` ADD COLUMN `core` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `task_history` ADD COLUMN `category` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `task_history` SET `category` = 1 WHERE `daily` = 1")
                db.execSQL("CREATE TABLE IF NOT EXISTS `day_scores` (`day` INTEGER NOT NULL, `isFinal` INTEGER NOT NULL, `score` REAL NOT NULL, `taskPoints` REAL NOT NULL, `starPoints` REAL NOT NULL, `plannedWeight` REAL NOT NULL, `creditedWeight` REAL NOT NULL, `corePct` REAL NOT NULL, `cap` REAL NOT NULL, `toughStars` INTEGER NOT NULL, `consStars` INTEGER NOT NULL, `tasksPlanned` INTEGER NOT NULL, `tasksDone` INTEGER NOT NULL, `tasksDoing` INTEGER NOT NULL, `confirmedAt` INTEGER NOT NULL, `revisions` INTEGER NOT NULL, `version` INTEGER NOT NULL, PRIMARY KEY(`day`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `health_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `day` INTEGER NOT NULL, `category` TEXT NOT NULL, `label` TEXT NOT NULL, `amount` REAL, `unit` TEXT, `kcal` REAL, `protein` REAL, `meal` TEXT, `balance` INTEGER, `originalText` TEXT NOT NULL, `source` TEXT NOT NULL, `estimated` INTEGER NOT NULL, `confirmed` INTEGER NOT NULL, `confidence` TEXT NOT NULL, `note` TEXT NOT NULL, `dedupeKey` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deleted` INTEGER NOT NULL, `history` TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `health_days` (`day` INTEGER NOT NULL, `completeMask` INTEGER NOT NULL, `noActivity` INTEGER NOT NULL, `confirmed` INTEGER NOT NULL, `confirmedAt` INTEGER NOT NULL, `score` REAL NOT NULL, `coverage` REAL NOT NULL, `nScore` REAL NOT NULL, `aScore` REAL NOT NULL, `sScore` REAL NOT NULL, `wScore` REAL NOT NULL, `snapshot` TEXT NOT NULL, `revisions` INTEGER NOT NULL, `version` INTEGER NOT NULL, PRIMARY KEY(`day`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS `health_goals` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `effectiveDay` INTEGER NOT NULL, `json` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
            }
        }

        /** v6 → v7: nutrition detail (carbs, fat, fibre, food kinds) and the exercise estimate on health entries. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `health_entries` ADD COLUMN `carbs` REAL")
                db.execSQL("ALTER TABLE `health_entries` ADD COLUMN `fat` REAL")
                db.execSQL("ALTER TABLE `health_entries` ADD COLUMN `fiber` REAL")
                db.execSQL("ALTER TABLE `health_entries` ADD COLUMN `groups` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `health_entries` ADD COLUMN `extra` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `task_history` ADD COLUMN `doneAt` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v7 → v8: tasks that run over several chosen due dates (half weight before the last one). */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `dueDays` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `task_history` ADD COLUMN `partial` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v8 → v9: the Focus log (manual useful / distracting time). */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `focus_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `day` INTEGER NOT NULL, `startAt` INTEGER NOT NULL, `minutes` INTEGER NOT NULL, `kind` TEXT NOT NULL, `cat` TEXT NOT NULL, `label` TEXT NOT NULL, `rating` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
            }
        }

        @Volatile private var instance: AppDatabase? = null

        /** One shared database for the whole process – the widget and the app use the very same instance (fast, no duplicate opens). */
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "privacy.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                .build().also { instance = it }
        }

        fun build(context: Context): AppDatabase = get(context)
    }
}
