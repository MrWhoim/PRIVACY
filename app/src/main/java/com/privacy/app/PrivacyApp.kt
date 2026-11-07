package com.privacy.app

import android.app.Application
import android.content.Context
import com.privacy.app.ai.Agent
import com.privacy.app.ai.ChatService
import com.privacy.app.automation.AutomationScheduler
import com.privacy.app.ai.LlmClient
import com.privacy.app.ai.ToolExecutor
import com.privacy.app.data.AppDatabase
import com.privacy.app.data.TaskRepo
import com.privacy.app.security.BackupManager
import com.privacy.app.security.VaultManager
import com.privacy.app.settings.SettingsStore
import com.privacy.app.work.Notifier
import com.privacy.app.work.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** One place that wires everything together (no dependency-injection framework needed). */
class AppGraph(val context: Context) {
    val db = AppDatabase.build(context)
    val settings = SettingsStore(context)
    val vault = VaultManager(context)
    val taskRepo = TaskRepo(db.taskDao(), db.historyDao(), context)
    val health = com.privacy.app.data.HealthRepo(context, db.healthDao()).also { it.graph = this }
    val reports = com.privacy.app.data.ReportEngine(this)
    val focus = com.privacy.app.data.FocusRepo(this)
    val llm = LlmClient(settings)
    val tools = ToolExecutor(this)
    val agent = Agent(this)
    val backup = BackupManager(this)
    val chatService = ChatService(this)
    val vision = com.privacy.app.ai.VisionOcr(this)
}

class PrivacyApp : Application() {
    /** Built on first use, so a widget tap does not wait for the whole app to wire itself up. */
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        runCatching { com.privacy.app.security.PrefsRestore.repair(this) }
        com.privacy.app.ai.Net.ctx = applicationContext
        runCatching { Notifier.createChannels(this) }
        runCatching { com.privacy.app.voice.Speaker.warmUp(this) }
        CoroutineScope(Dispatchers.IO).launch {
            // Let a widget tap (which may have just started this process) finish first – the full app graph is not needed for it.
            kotlinx.coroutines.delay(3_000)
            val g = runCatching { graph }.getOrNull() ?: return@launch
            runCatching { Scheduler.scheduleMorning(this@PrivacyApp, g.settings.morningEnabled.value, g.settings.morningMinutes.value, replace = false) }
            runCatching { com.privacy.app.work.FocusWatch.schedule(this@PrivacyApp, true) }
            // Alarms are lost on force-stop/updates, so re-book every automation whenever the app process starts.
            runCatching { AutomationScheduler.rescheduleAll(g) }
            runCatching { com.privacy.app.work.Proactive.schedule(this@PrivacyApp) }
            runCatching { com.privacy.app.widget.WidgetSync.schedule(this@PrivacyApp) }
            runCatching { com.privacy.app.security.CloudBackup.load(this@PrivacyApp); com.privacy.app.security.CloudBackup.schedule(this@PrivacyApp) }
            runCatching { AppResume.run(g) }
        }
    }
}

/** Everything that should catch up whenever the app is opened: widgets, missed morning brief, automations that waited for internet. */
object AppResume {
    suspend fun run(g: AppGraph) {
        runCatching { com.privacy.app.work.Scheduler.catchUpMorning(g) }
        runCatching { com.privacy.app.automation.AutomationScheduler.resumePending(g.context) }
        runCatching { com.privacy.app.widget.WidgetSync.all(g.context) }
    }
}

/** Whether the app is on screen right now (the assistant never starts a chat while you are in the app). */
object AppState {
    /** True while the visible page has a previous tab (a right swipe must change the tab, not open the menu). */
    @Volatile var tabCanGoPrev = false
    /** True while a note is open for editing: the menu swipe is off. */
    @Volatile var blockMenuSwipe = false
    /** > 0 while a finger is on something that uses sideways drags itself (text fields, selection, seek bars, the assistant). */
    @Volatile var touchBlock = 0
    @Volatile var selecting = false
    @Volatile var foreground = false
    /** Screen a tapped notification / widget wants to open (consumed by the root composable). */
    val pendingScreen = androidx.compose.runtime.mutableStateOf<String?>(null)
    /** The note widget asks the Notes screen to open a new empty note. */
    /** A note (id) the Notes screen should open at once, e.g. a note a friend just shared. */
    val openNoteId = androidx.compose.runtime.mutableStateOf<Long?>(null)
    val newNoteRequest = androidx.compose.runtime.mutableStateOf(false)
    /** A picture / a Markdown file the assistant wants shown inside the app. */
    val imageRequest = androidx.compose.runtime.mutableStateOf<android.net.Uri?>(null)
    val mdRequest = androidx.compose.runtime.mutableStateOf<Pair<String, String>?>(null)
}
