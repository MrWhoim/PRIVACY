package com.privacy.app.security

import com.privacy.app.AppGraph
import com.privacy.app.automation.AutomationScheduler
import com.privacy.app.data.AutomationEntity
import com.privacy.app.data.ChatEntity
import com.privacy.app.data.ConversationEntity
import com.privacy.app.data.MemoryEntity
import com.privacy.app.data.NoteEntity
import com.privacy.app.data.RoutineEntity
import com.privacy.app.data.TaskEntity
import com.privacy.app.data.TaskStatus
import com.privacy.app.data.VaultEntity
import com.privacy.app.settings.ApiKey
import com.privacy.app.settings.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer

data class ExportOptions(
    val tasks: Boolean = true,
    val notes: Boolean = true,
    val memories: Boolean = true,
    val chat: Boolean = false,
    val vault: Boolean = true,
    val routines: Boolean = true,
    val apiKeys: Boolean = false
)

/**
 * File layout:  "DHB1" | salt(16) | iterations(4) | iv(12) | AES-256-GCM ciphertext
 * The key comes from a SEPARATE backup password (PBKDF2, 600k rounds), so the file is unreadable
 * without it – on any phone, in any cloud drive, in any chat app.
 */
class BackupManager(private val g: AppGraph) {
    private val magic = "DHB1".toByteArray()

    private val secretKey = Regex("key|token|secret|pass|salt|pin|hash|vault", RegexOption.IGNORE_CASE)
    private fun prefsFile(name: String): JSONObject = JSONObject().also { o ->
        g.context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE).all.forEach { (k, v) ->
            if (!secretKey.containsMatchIn(k) && (v is String || v is Boolean || v is Int || v is Long)) o.put(k, v) else if (!secretKey.containsMatchIn(k) && v is Float) o.put(k, v.toDouble())
        }
    }
    private fun restorePrefsFile(name: String, o: JSONObject) {
        val sp = g.context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
        val e = sp.edit()
        o.keys().forEach { k -> if (!secretKey.containsMatchIn(k)) PrefsRestore.put(e, sp, k, o.get(k)) }
        e.apply()
    }
    /** Encrypted backup bytes. Pictures are NEVER included (no note pictures, no chat pictures). */
    suspend fun export(opt: ExportOptions, password: CharArray): ByteArray = exportWithHash(opt, password).first

    /** Same, plus a fingerprint of the content (without the time stamp) so an unchanged account can skip the upload. */
    suspend fun exportWithHash(opt: ExportOptions, password: CharArray): Pair<ByteArray, String> = withContext(Dispatchers.Default) {
        val root = JSONObject().put("v", 2).put("app", "P.R.I.V.A.C.Y.").put("exportedAt", System.currentTimeMillis())

        if (opt.tasks) root.put("tasks", JSONArray().also { a ->
            g.db.taskDao().all().forEach {
                a.put(JSONObject().put("title", it.title).put("status", it.status.name).put("daily", it.daily)
                    .put("priority", it.priority).put("dueDay", it.dueDay ?: JSONObject.NULL)
                    .put("createdAt", it.createdAt).put("completedAt", it.completedAt ?: JSONObject.NULL)
                    .put("optional", it.optional).put("difficulty", it.difficulty).put("core", it.core)
                    .put("repeatMask", it.repeatMask).put("dueDays", it.dueDays))
            }
        })
        // health log + plan travel with the tasks option (same "Efficiency & Health" data)
        if (opt.tasks) {
            root.put("healthEntries", JSONArray().also { a ->
                g.db.healthDao().allEntries().filter { !it.deleted }.forEach {
                    a.put(JSONObject().put("day", it.day).put("category", it.category).put("label", it.label).put("amount", it.amount ?: JSONObject.NULL)
                        .put("unit", it.unit ?: JSONObject.NULL).put("kcal", it.kcal ?: JSONObject.NULL).put("protein", it.protein ?: JSONObject.NULL)
                        .put("meal", it.meal ?: JSONObject.NULL).put("balance", it.balance ?: JSONObject.NULL).put("originalText", it.originalText)
                        .put("source", it.source).put("estimated", it.estimated).put("confirmed", it.confirmed).put("confidence", it.confidence)
                        .put("note", it.note).put("createdAt", it.createdAt)
                        .put("carbs", it.carbs ?: JSONObject.NULL).put("fat", it.fat ?: JSONObject.NULL).put("fiber", it.fiber ?: JSONObject.NULL)
                        .put("groups", it.groups).put("extra", it.extra))
                }
            })
            root.put("healthProfile", g.health.profile.value.toJson())
            root.put("mealPlan", g.health.mealPlan())
            root.put("healthGoals", JSONArray().also { a -> g.db.healthDao().allGoals().forEach { a.put(JSONObject().put("effectiveDay", it.effectiveDay).put("json", it.json).put("createdAt", it.createdAt)) } })
            // every day's task list (with dates and when each task was done) and the stars given by hand
            root.put("taskHistory", JSONArray().also { a ->
                g.db.historyDao().range(0L, Long.MAX_VALUE).forEach {
                    a.put(JSONObject().put("day", it.day).put("taskId", it.taskId).put("title", it.title).put("status", it.status).put("priority", it.priority)
                        .put("daily", it.daily).put("optional", it.optional).put("difficulty", it.difficulty).put("core", it.core).put("category", it.category).put("doneAt", it.doneAt))
                }
            })
            root.put("dayStats", JSONArray().also { a -> g.db.historyDao().statsIn(0L, Long.MAX_VALUE).forEach { a.put(JSONObject().put("day", it.day).put("manualPoints", it.manualPoints)) } })
            // confirmed days (health + efficiency scores as they were), so old reports stay the same after a restore
            root.put("healthDays", JSONArray().also { a ->
                g.db.healthDao().allDays().forEach {
                    a.put(JSONObject().put("day", it.day).put("completeMask", it.completeMask).put("noActivity", it.noActivity).put("confirmed", it.confirmed).put("confirmedAt", it.confirmedAt)
                        .put("score", it.score.toDouble()).put("coverage", it.coverage.toDouble()).put("nScore", it.nScore.toDouble()).put("aScore", it.aScore.toDouble())
                        .put("sScore", it.sScore.toDouble()).put("wScore", it.wScore.toDouble()).put("snapshot", it.snapshot).put("revisions", it.revisions).put("version", it.version))
                }
            })
            root.put("dayScores", JSONArray().also { a ->
                g.db.scoreDao().all().forEach {
                    a.put(JSONObject().put("day", it.day).put("isFinal", it.isFinal).put("score", it.score.toDouble()).put("taskPoints", it.taskPoints.toDouble()).put("starPoints", it.starPoints.toDouble())
                        .put("plannedWeight", it.plannedWeight.toDouble()).put("creditedWeight", it.creditedWeight.toDouble()).put("corePct", it.corePct.toDouble()).put("cap", it.cap.toDouble())
                        .put("toughStars", it.toughStars).put("consStars", it.consStars).put("tasksPlanned", it.tasksPlanned).put("tasksDone", it.tasksDone).put("tasksDoing", it.tasksDoing)
                        .put("confirmedAt", it.confirmedAt).put("revisions", it.revisions).put("version", it.version))
                }
            })
            // Mind: manual screen-time / useful-time entries and the tracked-apps settings
            root.put("focusLogs", JSONArray().also { a ->
                g.db.focusDao().all().forEach {
                    a.put(JSONObject().put("day", it.day).put("startAt", it.startAt).put("minutes", it.minutes).put("kind", it.kind).put("cat", it.cat).put("label", it.label).put("rating", it.rating).put("createdAt", it.createdAt))
                }
            })
            root.put("focusPrefs", JSONObject().also { o ->
                g.context.getSharedPreferences("focus", android.content.Context.MODE_PRIVATE).all.forEach { (k, v) ->
                    when (v) { is String, is Boolean, is Int, is Long -> o.put(k, v); is Float -> o.put(k, v.toDouble()); is Set<*> -> o.put(k, JSONArray(v.map { it.toString() }).also { _ -> }); else -> Unit }
                }
            })
        }
        // who you are and how the app is set up (names, gender, assistant, look, companion, animations) – never keys or passcodes
        root.put("profile", g.settings.exportPrefs())
        if (opt.notes) root.put("notes", JSONArray().also { a ->
            g.db.noteDao().all().forEach {
                a.put(JSONObject().put("title", it.title).put("body", it.body.trim()).put("pinned", it.pinned)
                    .put("createdAt", it.createdAt).put("updatedAt", it.updatedAt))
            }
        })
        // smaller settings files: early-points progress, file-open defaults, and the friends you paired Telegram bots with (never the bot token)
        root.put("extraPrefs", JSONObject().also { o -> listOf("dh_early_points", "dh_file_defaults").forEach { n -> o.put(n, prefsFile(n)) } })
        root.put("telegramPeers", JSONArray().also { a -> com.privacy.app.ai.TelegramPeers.peers(g).filter { it.state == "ok" }.forEach { a.put(JSONObject().put("bot", it.bot).put("name", it.name)) } })
        if (opt.memories) root.put("memories", JSONArray().also { a ->
            g.db.memoryDao().all().forEach {
                a.put(JSONObject().put("category", it.category).put("content", it.content).put("createdAt", it.createdAt))
            }
        })
        if (opt.chat) {
            root.put("conversations", JSONArray().also { a ->
                g.db.conversationDao().all().forEach {
                    a.put(JSONObject().put("id", it.id).put("title", it.title).put("createdAt", it.createdAt).put("updatedAt", it.updatedAt))
                }
            })
            root.put("chat", JSONArray().also { a ->
                g.db.chatDao().all().forEach {
                    a.put(JSONObject().put("role", it.role).put("content", it.content).put("createdAt", it.createdAt).put("conv", it.conversationId))
                }
            })
        }
        if (opt.routines) root.put("routines", JSONArray().also { a ->
            g.db.routineDao().all().forEach {
                a.put(JSONObject().put("name", it.name).put("prompt", it.prompt).put("enabled", it.enabled).put("createdAt", it.createdAt))
            }
        })
        if (opt.routines) root.put("automations", JSONArray().also { a ->
            g.db.automationDao().all().forEach {
                a.put(JSONObject().put("name", it.name).put("action", it.action).put("message", it.message).put("timeMinutes", it.timeMinutes)
                    .put("daysMask", it.daysMask).put("dateDay", it.dateDay ?: JSONObject.NULL).put("enabled", it.enabled))
            }
        })
        if (opt.vault) {
            if (!g.vault.isUnlocked) throw IllegalStateException("Unlock your private vault first, or untick it.")
            root.put("vault", JSONArray().also { a ->
                g.db.vaultDao().all().mapNotNull { g.vault.decryptItem(it) }.forEach {
                    a.put(JSONObject().put("category", it.category.name).put("title", it.title).put("body", it.body)
                        .put("share", it.share).put("createdAt", it.createdAt).put("updatedAt", it.updatedAt))
                }
            })
        }
        if (opt.apiKeys) root.put("apiKeys", JSONArray().also { a ->
            g.settings.keys.value.forEach {
                a.put(JSONObject().put("provider", it.provider.name).put("label", it.label).put("key", it.key)
                    .put("baseUrl", it.baseUrl ?: ""))
            }
        })

        root.remove("exportedAt")
        val plain = root.toString().toByteArray()
        val fp = java.security.MessageDigest.getInstance("SHA-256").digest(plain).joinToString("") { "%02x".format(it) }
        root.put("exportedAt", System.currentTimeMillis())
        val salt = Crypto.randomBytes(16)
        val key = Crypto.pbkdf2(password, salt, Crypto.PBKDF2_ITERATIONS)
        val blob = Crypto.encrypt(key, root.toString().toByteArray(), magic)
        (magic + salt + ByteBuffer.allocate(4).putInt(Crypto.PBKDF2_ITERATIONS).array() + blob) to fp
    }

    /** Merges a backup into the phone (nothing is overwritten; duplicates are skipped). */
    suspend fun import(bytes: ByteArray, password: CharArray): String = withContext(Dispatchers.Default) {
        require(bytes.size > 40 && bytes.copyOfRange(0, 4).contentEquals(magic)) { "This is not a P.R.I.V.A.C.Y. backup file." }
        val salt = bytes.copyOfRange(4, 20)
        val iterations = ByteBuffer.wrap(bytes, 20, 4).int
        val json = try {
            val key = Crypto.pbkdf2(password, salt, iterations)
            JSONObject(String(Crypto.decrypt(key, bytes.copyOfRange(24, bytes.size), magic)))
        } catch (_: Exception) {
            throw IllegalArgumentException("Wrong backup password, or the file is damaged.")
        }

        val parts = mutableListOf<String>()

        json.optJSONArray("tasks")?.let { arr ->
            val existing = g.db.taskDao().all().map { it.title to it.createdAt }.toSet()
            var n = 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val title = o.getString("title"); val created = o.getLong("createdAt")
                if ((title to created) in existing) continue
                g.db.taskDao().insert(TaskEntity(
                    title = title, status = runCatching { TaskStatus.valueOf(o.getString("status")) }.getOrDefault(TaskStatus.NOT_STARTED),
                    daily = o.optBoolean("daily"), priority = o.optInt("priority", 1),
                    dueDay = if (o.isNull("dueDay")) null else o.getLong("dueDay"),
                    createdAt = created, completedAt = if (o.isNull("completedAt")) null else o.getLong("completedAt"),
                    optional = o.optBoolean("optional"), difficulty = o.optInt("difficulty", 1), core = o.optBoolean("core"),
                    repeatMask = o.optInt("repeatMask", 0), dueDays = o.optString("dueDays", "")))
                n++
            }
            parts += "$n tasks"
            g.taskRepo.refreshWidget()
        }
        json.optJSONArray("healthEntries")?.let { arr ->
            val existing = g.db.healthDao().allEntries().map { Triple(it.day, it.category + it.label, it.createdAt) }.toSet()
            var n = 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val key = Triple(o.getLong("day"), o.getString("category") + o.getString("label"), o.getLong("createdAt"))
                if (key in existing) continue
                fun dbl(k: String): Double? = if (o.isNull(k)) null else o.getDouble(k)
                g.db.healthDao().insert(com.privacy.app.data.HealthEntryEntity(
                    day = o.getLong("day"), category = o.getString("category"), label = o.getString("label"), amount = dbl("amount"),
                    unit = if (o.isNull("unit")) null else o.getString("unit"), kcal = dbl("kcal"), protein = dbl("protein"),
                    meal = if (o.isNull("meal")) null else o.getString("meal"), balance = if (o.isNull("balance")) null else o.getInt("balance"),
                    originalText = o.optString("originalText"), source = o.optString("source", "manual"), estimated = o.optBoolean("estimated"),
                    confirmed = o.optBoolean("confirmed", true), confidence = o.optString("confidence", "high"), note = o.optString("note"), createdAt = o.getLong("createdAt"),
                    carbs = dbl("carbs"), fat = dbl("fat"), fiber = dbl("fiber"), groups = o.optString("groups"), extra = o.optString("extra")))
                n++
            }
            if (n > 0) parts += "$n health entries"
        }
        json.optJSONArray("healthDays")?.let { arr ->
            val have = g.db.healthDao().allDays().map { it.day }.toSet()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i); if (o.getLong("day") in have) continue
                g.db.healthDao().upsertDay(com.privacy.app.data.HealthDayEntity(day = o.getLong("day"), completeMask = o.optInt("completeMask"), noActivity = o.optBoolean("noActivity"),
                    confirmed = o.optBoolean("confirmed"), confirmedAt = o.optLong("confirmedAt"), score = o.optDouble("score", -1.0).toFloat(), coverage = o.optDouble("coverage", 0.0).toFloat(),
                    nScore = o.optDouble("nScore", -1.0).toFloat(), aScore = o.optDouble("aScore", -1.0).toFloat(), sScore = o.optDouble("sScore", -1.0).toFloat(), wScore = o.optDouble("wScore", -1.0).toFloat(),
                    snapshot = o.optString("snapshot"), revisions = o.optInt("revisions"), version = o.optInt("version", 1)))
            }
        }
        json.optJSONArray("dayScores")?.let { arr ->
            val have = g.db.scoreDao().all().map { it.day }.toSet()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i); if (o.getLong("day") in have) continue
                g.db.scoreDao().upsert(com.privacy.app.data.DayScoreEntity(day = o.getLong("day"), isFinal = o.optBoolean("isFinal"), score = o.optDouble("score", -1.0).toFloat(),
                    taskPoints = o.optDouble("taskPoints").toFloat(), starPoints = o.optDouble("starPoints").toFloat(), plannedWeight = o.optDouble("plannedWeight").toFloat(),
                    creditedWeight = o.optDouble("creditedWeight").toFloat(), corePct = o.optDouble("corePct", -1.0).toFloat(), cap = o.optDouble("cap", 100.0).toFloat(),
                    toughStars = o.optInt("toughStars"), consStars = o.optInt("consStars"), tasksPlanned = o.optInt("tasksPlanned"), tasksDone = o.optInt("tasksDone"),
                    tasksDoing = o.optInt("tasksDoing"), confirmedAt = o.optLong("confirmedAt"), revisions = o.optInt("revisions"), version = o.optInt("version", 1)))
            }
        }
        json.optJSONArray("focusLogs")?.let { arr ->
            val have = g.db.focusDao().all().map { it.startAt to it.label }.toSet()
            var n = 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i); if ((o.getLong("startAt") to o.getString("label")) in have) continue
                g.db.focusDao().insert(com.privacy.app.data.FocusLogEntity(day = o.getLong("day"), startAt = o.getLong("startAt"), minutes = o.getInt("minutes"), kind = o.getString("kind"),
                    cat = o.optString("cat"), label = o.getString("label"), rating = o.optString("rating"), createdAt = o.optLong("createdAt")))
                n++
            }
            if (n > 0) parts += "$n Mind entries"
        }
        json.optJSONObject("focusPrefs")?.let { o ->
            val sp = g.context.getSharedPreferences("focus", android.content.Context.MODE_PRIVATE)
            val e = sp.edit()
            o.keys().forEach { k ->
                when (val v = o.get(k)) {
                    is JSONArray -> e.putStringSet(k, (0 until v.length()).map { v.getString(it) }.toSet())
                    else -> PrefsRestore.put(e, sp, k, v)
                }
            }
            e.apply(); g.focus.bump()
        }
        json.optJSONObject("extraPrefs")?.let { ep -> ep.keys().forEach { n -> ep.optJSONObject(n)?.let { restorePrefsFile(n, it) } } }
        json.optJSONArray("telegramPeers")?.let { arr ->
            val have = com.privacy.app.ai.TelegramPeers.peers(g).map { it.bot }.toSet()
            var n = 0
            for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); if (o.getString("bot") !in have) { com.privacy.app.ai.TelegramPeers.restorePeer(g, o.getString("bot"), o.optString("name")); n++ } }
            if (n > 0) parts += "$n Telegram friends (enter your bot token again)"
        }
        json.optJSONObject("profile")?.let { g.settings.importPrefs(it); parts += "your name, assistant and settings" }
        json.optJSONArray("taskHistory")?.let { arr ->
            var n = 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                g.db.historyDao().putRow(com.privacy.app.data.TaskHistoryEntity(
                    o.getLong("day"), o.getLong("taskId"), o.getString("title"), o.getString("status"), o.optInt("priority", 1), o.optBoolean("daily"), o.optBoolean("optional"),
                    o.optInt("difficulty", 1), o.optBoolean("core"), o.optInt("category", 0), o.optLong("doneAt", 0L)))
                n++
            }
            if (n > 0) parts += "$n days of task history"
        }
        json.optJSONArray("dayStats")?.let { arr ->
            for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); g.db.historyDao().upsertStat(com.privacy.app.data.DayStatEntity(o.getLong("day"), o.getInt("manualPoints"))) }
        }
        json.optJSONArray("healthGoals")?.let { arr ->
            val have = g.db.healthDao().allGoals().map { it.json }.toSet()
            for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); if (o.getString("json") !in have) g.db.healthDao().insertGoal(com.privacy.app.data.HealthGoalEntity(effectiveDay = o.getLong("effectiveDay"), json = o.getString("json"), createdAt = o.optLong("createdAt"))) }
        }
        json.optString("mealPlan").takeIf { it.isNotBlank() && g.health.mealPlan().isBlank() }?.let { g.health.saveMealPlan(it) }
        json.optJSONObject("healthProfile")?.let { if (g.health.profile.value.isEmpty) g.health.saveProfile(com.privacy.app.data.HealthProfile.fromJson(it)) }
        json.optJSONArray("notes")?.let { arr ->
            val existing = g.db.noteDao().all().map { it.title to it.createdAt }.toSet()
            var n = 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val title = o.getString("title"); val created = o.getLong("createdAt")
                if ((title to created) in existing) continue
                g.db.noteDao().insert(NoteEntity(title = title, body = o.optString("body"), pinned = o.optBoolean("pinned"),
                    createdAt = created, updatedAt = o.optLong("updatedAt", created)))
                n++
            }
            parts += "$n notes"
        }
        json.optJSONArray("memories")?.let { arr ->
            val existing = g.db.memoryDao().all().map { it.content }.toSet()
            var n = 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.getString("content") in existing) continue
                g.db.memoryDao().insert(MemoryEntity(category = o.optString("category", "general"), content = o.getString("content"), createdAt = o.optLong("createdAt")))
                n++
            }
            parts += "$n memories"
        }
        json.optJSONArray("chat")?.let { arr ->
            val convDao = g.db.conversationDao()
            val convMap = mutableMapOf<Long, Long>()
            json.optJSONArray("conversations")?.let { cs ->
                val known = convDao.all()
                for (i in 0 until cs.length()) {
                    val o = cs.getJSONObject(i)
                    val title = o.getString("title"); val created = o.getLong("createdAt")
                    val same = known.firstOrNull { it.title == title && it.createdAt == created }
                    convMap[o.getLong("id")] = same?.id ?: convDao.insert(ConversationEntity(title = title, createdAt = created, updatedAt = o.optLong("updatedAt", created)))
                }
            }
            var fallback = 0L
            val existing = g.db.chatDao().all().map { it.createdAt to it.content }.toSet()
            var n = 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if ((o.getLong("createdAt") to o.getString("content")) in existing) continue
                var cid = convMap[o.optLong("conv", 0)] ?: 0L
                if (cid == 0L) {
                    if (fallback == 0L) fallback = convDao.insert(ConversationEntity(title = "Imported chat"))
                    cid = fallback
                }
                g.db.chatDao().insert(ChatEntity(role = o.getString("role"), content = o.getString("content"), createdAt = o.getLong("createdAt"), conversationId = cid))
                n++
            }
            parts += "$n chat messages"
        }
        json.optJSONArray("routines")?.let { arr ->
            val existing = g.db.routineDao().all().map { it.name }.toSet()
            var n = 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.getString("name") in existing) continue
                g.db.routineDao().insert(RoutineEntity(name = o.getString("name"), prompt = o.getString("prompt"), enabled = o.optBoolean("enabled", true)))
                n++
            }
            parts += "$n routines"
        }
        json.optJSONArray("automations")?.let { arr ->
            val existing = g.db.automationDao().all().map { it.name to it.timeMinutes }.toSet()
            var n = 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if ((o.getString("name") to o.getInt("timeMinutes")) in existing) continue
                val ent = AutomationEntity(
                    name = o.getString("name"), action = o.getString("action"), message = o.optString("message"),
                    timeMinutes = o.getInt("timeMinutes"), daysMask = o.optInt("daysMask", 127),
                    dateDay = if (o.isNull("dateDay")) null else o.getLong("dateDay"), enabled = o.optBoolean("enabled", true)
                )
                val id = g.db.automationDao().insert(ent)
                AutomationScheduler.schedule(g.context, ent.copy(id = id))
                n++
            }
            parts += "$n automations"
        }
        json.optJSONArray("vault")?.let { arr ->
            if (!g.vault.isUnlocked) {
                parts += "vault skipped (unlock it first, then import again)"
            } else {
                val existing = g.db.vaultDao().all().mapNotNull { g.vault.decryptItem(it) }.map { it.title to it.createdAt }.toSet()
                var n = 0
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val title = o.getString("title"); val created = o.getLong("createdAt")
                    if ((title to created) in existing) continue
                    val cat = runCatching { VaultCategory.valueOf(o.getString("category")) }.getOrDefault(VaultCategory.PRIVATE)
                    g.db.vaultDao().insert(VaultEntity(blob = g.vault.encryptItem(cat, title, o.optString("body")),
                        share = o.optBoolean("share"), createdAt = created, updatedAt = o.optLong("updatedAt", created)))
                    n++
                }
                parts += "$n vault entries"
            }
        }
        json.optJSONArray("apiKeys")?.let { arr ->
            val existing = g.settings.keys.value.map { it.key }.toSet()
            var n = 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.getString("key") in existing) continue
                g.settings.addKey(ApiKey(
                    id = java.util.UUID.randomUUID().toString(),
                    provider = runCatching { Provider.valueOf(o.getString("provider")) }.getOrDefault(Provider.CUSTOM),
                    label = o.optString("label", "Imported"), key = o.getString("key"),
                    baseUrl = o.optString("baseUrl").ifBlank { null }))
                n++
            }
            parts += "$n API keys"
        }
        if (parts.isEmpty()) "Backup was empty." else "Restored: " + parts.joinToString(", ") + "."
    }
}
