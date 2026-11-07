package com.privacy.app.ui

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.privacy.app.PrivacyApp
import com.privacy.app.ai.contentOrEmpty
import com.privacy.app.automation.AutomationScheduler
import com.privacy.app.data.AutoAction
import com.privacy.app.data.AutomationEntity
import com.privacy.app.data.ConversationEntity
import com.privacy.app.data.MemoryEntity
import com.privacy.app.data.NoteEntity
import com.privacy.app.data.RoutineEntity
import com.privacy.app.data.TaskEntity
import com.privacy.app.data.TaskStatus
import com.privacy.app.data.VaultEntity
import com.privacy.app.security.ExportOptions
import com.privacy.app.security.UnlockResult
import com.privacy.app.security.VaultCategory
import com.privacy.app.security.VaultItem
import com.privacy.app.settings.ApiKey
import com.privacy.app.work.DailyReset
import com.privacy.app.work.RoutineRunner
import com.privacy.app.work.Scheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val g = (app as PrivacyApp).graph
    private val db = g.db

    private fun <T> Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val currentChat: StateFlow<Long> = g.settings.currentConversation
    @OptIn(ExperimentalCoroutinesApi::class)
    /** null until the first load finishes, so the screen can show a calm loader instead of an empty flash. Kept warm so chats open instantly. */
    val chat: StateFlow<List<com.privacy.app.data.ChatEntity>?> =
        currentChat.flatMapLatest { db.chatDao().observe(it) }.flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    /** "In the queue · retrying in 12s" while the AI is busy; null otherwise. */
    val queueStatus: StateFlow<String?> = g.llm.queue
    val conversations = db.conversationDao().observe().map { l -> l.filter { it.title != "Telegram (private)" } }.state(emptyList())
    val automations = db.automationDao().observe().state(emptyList())
    /** True while the app has chosen Agent mode for a big request. */
    val agentActive: StateFlow<Boolean> = g.chatService.agentActive

    /** Every finished assistant reply – the talking-face screen listens to this to speak it. */
    private val _replies = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val replies: SharedFlow<String> = _replies
    val tasks = db.taskDao().observeActive().state(emptyList())
    val archive = db.taskDao().observeArchive().state(emptyList())
    val futureTasks = db.taskDao().observeFuture().state(emptyList())
    val notes = db.noteDao().observe().state(emptyList())
    val memories = db.memoryDao().observe().state(emptyList())
    val routines = db.routineDao().observe().state(emptyList())

    val vaultItems: StateFlow<List<VaultItem>> =
        combine(db.vaultDao().observe(), g.vault.unlocked) { rows, unlocked ->
            if (unlocked) rows.mapNotNull { g.vault.decryptItem(it) } else emptyList()
        }.flowOn(Dispatchers.Default).state(emptyList())

    val busy = MutableStateFlow(false)
    val online = MutableStateFlow(true)
    val aiStatus: StateFlow<String?> =
        combine(g.settings.keys, g.settings.routes) { _, _ -> g.settings.primaryLabel() }.state(g.settings.primaryLabel())

    private var job: Job? = null
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { online.value = true }
        override fun onLost(network: Network) { online.value = cm.activeNetwork != null }
    }

    init {
        online.value = cm.activeNetwork?.let { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } ?: false
        cm.registerDefaultNetworkCallback(netCallback)
        viewModelScope.launch { DailyReset.runIfNeeded(g) }
        com.privacy.app.ai.TelegramHub.start(g)
        // an example automation, switched OFF: shows how an "AI task" works (turn it on in Automations)
        viewModelScope.launch(Dispatchers.IO) {
            if (!g.settings.presetNewsDone) {
                g.settings.presetNewsDone = true
                db.automationDao().insert(com.privacy.app.data.AutomationEntity(
                    name = "Daily 6AM News", action = com.privacy.app.data.AutoAction.AI_PROMPT.name,
                    message = "Search on web for latest news about global, approx cover around every major field of news and give summarise form of News like a Newsletter",
                    timeMinutes = 6 * 60, daysMask = 127, enabled = false
                ))
            }
            if (!g.settings.presetXDone) {
                g.settings.presetXDone = true
                db.automationDao().insert(com.privacy.app.data.AutomationEntity(
                    name = "Check for latest post on X", action = com.privacy.app.data.AutoAction.AI_PROMPT.name,
                    message = "Check x.com/v_shal_ and tell about the latest post",
                    timeMinutes = 10 * 60, daysMask = 127, enabled = false
                ))
            }
        }
        runCatching { com.privacy.app.overlay.OverlayService.sync(getApplication()) }
        // while the app is open the assistant can speak first: "I'm Feeling Distracted" topics and health nudges
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                kotlinx.coroutines.delay(45_000L)
                if (!com.privacy.app.AppState.foreground || busy.value) continue
                if (g.settings.watchSyncOn.value && System.currentTimeMillis() - lastWatchSync > 10 * 60_000L) {
                    lastWatchSync = System.currentTimeMillis(); runCatching { com.privacy.app.watch.WatchSync.sync(g) }
                }
                runCatching {
                    if (g.settings.distractedMode.value) {
                        com.privacy.app.ai.Distracted.maybeNudge(g, getApplication(), 4 * 60_000L, notify = false)
                    } else if (g.settings.healthAlertsOn.value && System.currentTimeMillis() - g.settings.lastHealthAlertAt > 90 * 60_000L &&
                        System.currentTimeMillis() - g.settings.lastActiveAt > 60_000L) {
                        if (com.privacy.app.ai.HealthCoach.maybeAlert(g, getApplication(), notify = false)) g.settings.lastHealthAlertAt = System.currentTimeMillis()
                    }
                }
            }
        }
    }

    override fun onCleared() {
        runCatching { cm.unregisterNetworkCallback(netCallback) }
        super.onCleared()
    }

    // ---- chat -----------------------------------------------------------------------------
    fun send(text: String, spoken: Boolean = false) {
        val t = text.trim()
        if (t.isEmpty() || busy.value) return
        com.privacy.app.ai.TelegramHub.mirrorUser(g, t)
        job = viewModelScope.launch {
            busy.value = true
            try {
                val reply = g.chatService.ask(t, spoken)
                if (reply != null) _replies.emit(reply)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { g.chatService.addAssistant("Stopped.") }
                throw e
            } catch (e: Exception) {
                g.chatService.addAssistant("⚠️ Something went wrong: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy.value = false
            }
        }
    }

    private var lastWatchSync = 0L
    fun stop() { job?.cancel() }

    /** True when a vision-capable model is set up, so the "analyse a photo" option should be offered. */
    val visionReady: StateFlow<Boolean> =
        combine(g.settings.keys, g.settings.routes, g.settings.visionModel) { _, _, _ -> g.settings.visionReady() }
            .state(g.settings.visionReady())

    /** Sends a photo for visual analysis (not text extraction). */
    fun analyzePhoto(uri: android.net.Uri, question: String) {
        if (busy.value) return
        job = viewModelScope.launch {
            busy.value = true
            try {
                val reply = g.chatService.analyzePhoto(uri, question)
                _replies.emit(reply)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                g.chatService.addAssistant("⚠️ Couldn't analyse the photo: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy.value = false
            }
        }
    }

    /** Reads the text out of a photo (handwriting and tables included) with a free vision AI model. */
    suspend fun extractText(uri: android.net.Uri): Result<String> = g.vision.extract(uri)

    /** New chat: the current one stays saved in History. */
    fun newChat() { viewModelScope.launch { g.chatService.startNewChat() } }
    fun openChat(id: Long) { g.settings.setCurrentConversation(id) }
    fun deleteChat(id: Long) {
        viewModelScope.launch {
            db.chatDao().clear(id)
            db.conversationDao().delete(id)
            if (g.settings.currentConversation.value == id) g.settings.setCurrentConversation(0)
        }
    }
    fun renameChat(c: ConversationEntity, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch { db.conversationDao().update(c.copy(title = title.trim().take(60))) }
    }

    // ---- tasks ----------------------------------------------------------------------------
    fun addTask(title: String, daily: Boolean, priority: Int = 1, due: Long? = null, optional: Boolean = false, repeatMask: Int = 0, difficulty: Int = 1, core: Boolean = false, dueDays: List<Long> = emptyList()) {
        if (title.isBlank()) return
        viewModelScope.launch { g.taskRepo.add(title, daily, priority, due, optional, repeatMask, difficulty, core, dueDays) }
    }

    /** Deletes everything recorded for one past day (tasks history, score, stars, health entries). Today cannot be deleted. */
    fun deleteDayData(day: Long, tasks: Boolean, health: Boolean) = viewModelScope.launch {
        if (day >= today()) return@launch
        if (tasks) { db.historyDao().clearDay(day); db.historyDao().deleteStat(day); db.scoreDao().deleteDay(day) }
        if (health) { db.healthDao().deleteDayEntries(day); db.healthDao().deleteDayRow(day) }
        g.settings.setDayHidden(day, false)
    }
    fun setDayHidden(day: Long, hidden: Boolean) = g.settings.setDayHidden(day, hidden)

    // ---- efficiency & health reports ------------------------------------------------------
    /** Consistency stars for today (3 confirmed days in a row = 1, 7 = 2). */
    val consStars: StateFlow<Int> = db.historyDao().observeRange(java.time.LocalDate.now().toEpochDay() - 40, java.time.LocalDate.now().toEpochDay())
        .map { rows -> com.privacy.app.data.Efficiency.consistencyFromRows(java.time.LocalDate.now().toEpochDay(), rows) }
        .state(0)
    fun scoreFlow(day: Long): Flow<com.privacy.app.data.DayScoreEntity?> = db.scoreDao().observe(day)
    fun finalizeDay(day: Long) = viewModelScope.launch { g.taskRepo.finalizeDay(day) }
    fun addHistoryTask(day: Long, title: String) = viewModelScope.launch { g.taskRepo.addHistoryTask(day, title) }
    fun editHistoryRow(day: Long, taskId: Long, title: String, status: TaskStatus, difficulty: Int, optional: Boolean, core: Boolean) = viewModelScope.launch { g.taskRepo.editHistoryRow(day, taskId, title, status, difficulty, optional, core) }
    fun deleteHistoryRow(day: Long, taskId: Long) = viewModelScope.launch { g.taskRepo.deleteHistoryRow(day, taskId) }
    fun setHistoryStatus(day: Long, taskId: Long, status: TaskStatus) = viewModelScope.launch { g.taskRepo.setHistoryStatus(day, taskId, status) }
    /** Emits whenever anything the reports are built from changes (tasks history, stars, saved scores, health entries, goals). */
    val dataTick: Flow<Long> = combine(
        db.historyDao().observeRange(0L, Long.MAX_VALUE), db.historyDao().observeStats(0L, Long.MAX_VALUE), db.scoreDao().observeRange(0L, Long.MAX_VALUE),
        db.healthDao().observeRange(0L, Long.MAX_VALUE), db.healthDao().observeDayRows(0L, Long.MAX_VALUE)
    ) { _, _, _, _, _ -> System.nanoTime() }
    val healthGoals = db.healthDao().observeGoals().state(emptyList())
    fun editHealth(id: Long, label: String?, amount: Double?, kcal: Double?, protein: Double?, confirm: Boolean = false) =
        viewModelScope.launch { g.health.edit(id, label, amount, kcal, protein, confirm = confirm) }
    fun deleteHealth(id: Long) = viewModelScope.launch { g.health.delete(id) }

    private fun describe(r: com.privacy.app.data.HealthRepo.LogResult): String = when (r) {
        is com.privacy.app.data.HealthRepo.LogResult.Added -> ""
        is com.privacy.app.data.HealthRepo.LogResult.Updated -> ""
        is com.privacy.app.data.HealthRepo.LogResult.Duplicate -> "That looks like a duplicate of an entry you just made, so I did not add it again."
        is com.privacy.app.data.HealthRepo.LogResult.Rejected -> r.reason
    }
    /** The manual form. Writes the same rows the chat assistant writes. */
    fun logHealth(
        category: String, label: String, amount: Double?, unit: String?, day: Long, kcal: Double? = null, protein: Double? = null, meal: String? = null,
        estimated: Boolean = false, confidence: String = "high", note: String = "", atMillis: Long? = null, originalText: String = label,
        force: Boolean = false, carbs: Double? = null, fat: Double? = null, fiber: Double? = null, groups: String = "",
        onResult: (String) -> Unit = {}
    ) = viewModelScope.launch {
        val bal = if (category == "FOOD" && meal != null && meal != "snack") com.privacy.app.data.FoodDb.estimate(label).balance else null
        onResult(describe(g.health.log(category, label, amount, unit, day, kcal, protein, meal, bal, originalText = originalText, source = "manual", estimated = estimated, confirmed = !estimated, confidence = confidence, note = note, force = force, atMillis = atMillis, carbs = carbs, fat = fat, fiber = fiber, groups = groups)))
    }
    /** The quick box on the Health screen understands the same sentences as the chat. */
    fun logHealthText(text: String, day: Long, atMillis: Long? = null, onResult: (String) -> Unit) = viewModelScope.launch {
        val parsed = com.privacy.app.data.HealthParser.parse(text, java.time.LocalTime.now().hour)
        val saved = g.health.logDrafts(parsed, text, "manual", day, atMillis).map { describe(it) }
        val all = saved.filter { it.isNotBlank() } + parsed.questions
        val nothing = parsed.drafts.isEmpty() && parsed.questions.isEmpty()
        onResult(if (nothing) "I couldn't find anything to log in that. Try: “2 rotis and dal for lunch”, “slept 7 hours”, “drank 1.5 litres”, “ran 2 km”." else all.joinToString("\n"))
    }
    fun saveHealthProfile(p: com.privacy.app.data.HealthProfile) = viewModelScope.launch { g.health.saveProfile(p) }

    // ---- commitment points & history ------------------------------------------------------
    fun today(): Long = java.time.LocalDate.now().toEpochDay()
    fun snapshotToday() = viewModelScope.launch { g.taskRepo.snapshotToday() }
    fun setManualPoints(day: Long, points: Int) = viewModelScope.launch { g.taskRepo.setManualPoints(day, points) }
    val todayStat: StateFlow<com.privacy.app.data.DayStatEntity?> =
        db.historyDao().observeStat(java.time.LocalDate.now().toEpochDay()).state(null)
    fun historyDay(day: Long): Flow<List<com.privacy.app.data.TaskHistoryEntity>> = db.historyDao().observeDay(day)
    fun historyStat(day: Long): Flow<com.privacy.app.data.DayStatEntity?> = db.historyDao().observeStat(day)
    fun historyRange(from: Long, to: Long): Flow<List<com.privacy.app.data.TaskHistoryEntity>> = db.historyDao().observeRange(from, to)
    fun statsRange(from: Long, to: Long): Flow<List<com.privacy.app.data.DayStatEntity>> = db.historyDao().observeStats(from, to)
    fun toggleTask(id: Long) = viewModelScope.launch { g.taskRepo.toggle(id) }
    fun deleteTaskForToday(id: Long) = viewModelScope.launch { g.taskRepo.hideToday(id) }
    fun toggleProgress(id: Long) = viewModelScope.launch { g.taskRepo.toggleProgress(id) }
    fun setTaskStatus(id: Long, status: TaskStatus) = viewModelScope.launch { g.taskRepo.setStatus(id, status) }
    fun updateTask(t: TaskEntity) = viewModelScope.launch {
        g.taskRepo.update(t.copy(completedAt = if (t.status == TaskStatus.COMPLETED) (t.completedAt ?: System.currentTimeMillis()) else null))
    }
    fun deleteTask(id: Long) = viewModelScope.launch { g.taskRepo.delete(id) }
    fun restoreTask(id: Long) = viewModelScope.launch { g.taskRepo.restore(id) }

    // ---- notes / memories -----------------------------------------------------------------
    fun saveNote(id: Long?, title: String, body: String, pinned: Boolean) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val t = title.ifBlank { body.lines().firstOrNull().orEmpty().take(40).ifBlank { "Untitled" } }
        if (id == null) db.noteDao().insert(NoteEntity(title = t, body = body, pinned = pinned))
        else db.noteDao().get(id)?.let { db.noteDao().update(it.copy(title = t, body = body, pinned = pinned, updatedAt = now)) }
    }
    /** Autosave: creates the note on first call and returns its id so later calls update the same row. */
    suspend fun saveNoteNow(id: Long?, title: String, body: String, pinned: Boolean): Long {
        val t = title.ifBlank { body.lines().firstOrNull().orEmpty().take(40).ifBlank { "Untitled" } }
        val saved = if (id == null) db.noteDao().insert(NoteEntity(title = t, body = body, pinned = pinned))
        else { db.noteDao().get(id)?.let { db.noteDao().update(it.copy(title = t, body = body, pinned = pinned, updatedAt = System.currentTimeMillis())) }; id }
        com.privacy.app.ai.TelegramHub.noteSaved(g, saved, t)
        com.privacy.app.widget.refreshNoteWidgets(getApplication<Application>())
        return saved
    }
    fun deleteNote(id: Long) = viewModelScope.launch { db.noteDao().delete(id) }
    fun deleteNotes(ids: Collection<Long>) = viewModelScope.launch { ids.forEach { db.noteDao().delete(it) } }

    /** "Rewrite with AI": the user says what is needed (a prompt), the AI returns the whole note again. Nothing is saved here – the editor keeps an undo list. */
    suspend fun rewriteNote(title: String, body: String, instruction: String): kotlin.Result<String> = withContext(Dispatchers.IO) {
        if (!g.settings.aiReady()) return@withContext kotlin.Result.failure(IllegalStateException("Add an AI key first (API & Subscription)."))
        val sys = "You edit the user's personal note exactly as asked. Reply with ONLY the complete new note body in simple Markdown – no preface, no explanation, no code fence around it. " +
            "Keep the user's facts; never invent facts. Keep any line like ![img](file.jpg) unchanged. " + com.privacy.app.ai.NoteRules.FOR_AI
        val msgs = listOf(
            org.json.JSONObject().put("role", "system").put("content", sys),
            org.json.JSONObject().put("role", "user").put("content", "NOTE TITLE: $title\n\nNOTE:\n$body\n\nWHAT TO DO: ${instruction.ifBlank { "Rewrite it clearly and tidily." }}")
        )
        val r = g.llm.chat(msgs, null, maxTokens = 4000, temperature = 0.4, tier = null)
        when (r) {
            is com.privacy.app.ai.LlmClient.Result.Ok -> {
                val t = r.message.contentOrEmpty().replace(Regex("(?s)<think>.*?</think>"), "").trim().removePrefix("```markdown").removePrefix("```md").removePrefix("```").removeSuffix("```").trim()
                if (t.isBlank()) kotlin.Result.failure(IllegalStateException("The AI returned nothing.")) else kotlin.Result.success(t)
            }
            is com.privacy.app.ai.LlmClient.Result.Fail -> kotlin.Result.failure(IllegalStateException(r.reason.lines().firstOrNull().orEmpty().ifBlank { "The AI is busy – try again." }))
        }
    }
    fun addMemory(text: String) { if (text.isNotBlank()) viewModelScope.launch { db.memoryDao().insert(MemoryEntity(content = text.trim())) } }
    fun deleteMemory(id: Long) = viewModelScope.launch { db.memoryDao().delete(id) }

    // ---- routines -------------------------------------------------------------------------
    fun addRoutine(name: String, prompt: String) {
        if (name.isBlank() || prompt.isBlank()) return
        viewModelScope.launch { db.routineDao().insert(RoutineEntity(name = name.trim(), prompt = prompt.trim())) }
    }
    fun setRoutineEnabled(r: RoutineEntity, on: Boolean) = viewModelScope.launch { db.routineDao().update(r.copy(enabled = on)) }
    fun deleteRoutine(id: Long) = viewModelScope.launch { db.routineDao().delete(id) }
    fun runRoutineNow(r: RoutineEntity, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val err = withContext(Dispatchers.IO) { RoutineRunner.run(g, r) }
            onDone(err?.let { "Couldn't run: $it" } ?: "Done – the result is in your Notes.")
        }
    }

    // ---- automations ----------------------------------------------------------------------
    fun addAutomation(name: String, action: AutoAction, message: String, minutes: Int, daysMask: Int, dateDay: Long? = null) {
        viewModelScope.launch {
            val ent = AutomationEntity(name = name.trim().ifBlank { "Automation" }, action = action.name, message = message.trim(), timeMinutes = minutes, daysMask = if (dateDay != null) 0 else daysMask, dateDay = dateDay)
            val id = db.automationDao().insert(ent)
            AutomationScheduler.schedule(getApplication(), ent.copy(id = id))
        }
    }
    fun updateAutomation(a: AutomationEntity, name: String, action: AutoAction, message: String, minutes: Int, daysMask: Int, dateDay: Long? = null) {
        viewModelScope.launch {
            val n = a.copy(name = name.trim().ifBlank { "Automation" }, action = action.name, message = message.trim(), timeMinutes = minutes, daysMask = if (dateDay != null) 0 else daysMask, dateDay = dateDay, enabled = if (dateDay != null) true else a.enabled)
            db.automationDao().update(n)
            if (n.enabled) AutomationScheduler.schedule(getApplication(), n) else AutomationScheduler.cancel(getApplication(), n.id)
        }
    }
    fun setAutomationEnabled(a: AutomationEntity, on: Boolean) {
        viewModelScope.launch {
            val n = a.copy(enabled = on)
            db.automationDao().update(n)
            AutomationScheduler.schedule(getApplication(), n)
        }
    }
    fun deleteAutomation(id: Long) {
        viewModelScope.launch {
            AutomationScheduler.cancel(getApplication(), id)
            db.automationDao().delete(id)
        }
    }
    fun runAutomationNow(a: AutomationEntity, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val msg = try { withContext(Dispatchers.IO) { AutomationScheduler.execute(g, a) } } catch (e: Exception) { "Failed: ${e.message}" }
            onDone(msg)
        }
    }

    // ---- settings -------------------------------------------------------------------------
    fun applyMorning(enabled: Boolean, minutes: Int) {
        g.settings.setMorningEnabled(enabled)
        g.settings.setMorningMinutes(minutes)
        Scheduler.scheduleMorning(getApplication(), enabled, minutes, replace = true)
    }
    suspend fun listModels(key: ApiKey): List<String> = g.llm.listModels(key)

    // ---- vault ----------------------------------------------------------------------------
    suspend fun setUpVault(pass: CharArray) = withContext(Dispatchers.Default) { g.vault.setUp(pass) }
    suspend fun unlockVault(pass: CharArray): UnlockResult = withContext(Dispatchers.Default) { g.vault.unlock(pass) }
    fun lockVault() = g.vault.lock()
    suspend fun changePasscode(old: CharArray, new: CharArray): Boolean = withContext(Dispatchers.Default) { g.vault.changePasscode(old, new) }
    fun saveVaultItem(id: Long?, category: VaultCategory, title: String, body: String, share: Boolean) {
        viewModelScope.launch(Dispatchers.Default) {
            val blob = g.vault.encryptItem(category, title.trim(), body.trim())
            val now = System.currentTimeMillis()
            if (id == null) db.vaultDao().insert(VaultEntity(blob = blob, share = share))
            else {
                val old = db.vaultDao().all().firstOrNull { it.id == id } ?: return@launch
                db.vaultDao().update(old.copy(blob = blob, share = share, updatedAt = now))
            }
        }
    }
    fun deleteVaultItem(id: Long) = viewModelScope.launch { db.vaultDao().delete(id) }
    fun eraseVault() = viewModelScope.launch { db.vaultDao().deleteAll(); g.vault.wipe() }

    // ---- backup ---------------------------------------------------------------------------
    suspend fun exportBackup(opt: ExportOptions, pass: CharArray): ByteArray = g.backup.export(opt, pass)
    suspend fun importBackup(bytes: ByteArray, pass: CharArray): String = g.backup.import(bytes, pass)
}
