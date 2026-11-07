package com.privacy.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.privacy.app.R
import com.privacy.app.data.AppDatabase
import com.privacy.app.data.TaskEntity
import com.privacy.app.data.TaskRepo
import com.privacy.app.data.TaskStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

val TaskIdKey = ActionParameters.Key<Long>("task_id")

private val white = ColorProvider(Color(0xFFFFFFFF))
private val muted = ColorProvider(Color(0xFF8C8C8C))

/**
 * The task widget: today's checklist, nothing else. Tap the circle to cross a task out, tap the name to mark it "in progress".
 * Rows are tight and taps do one tiny database write followed by one refresh, so it responds immediately.
 */
class TaskWidget : GlanceAppWidget() {
    override val sizeMode: androidx.glance.appwidget.SizeMode = androidx.glance.appwidget.SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val db = AppDatabase.get(context)
        val tasks = db.taskDao().widgetTasks()
        val manual = runCatching { db.historyDao().stat(java.time.LocalDate.now().toEpochDay())?.manualPoints }.getOrNull() ?: 0
        val today = java.time.LocalDate.now().toEpochDay()
        val cons = runCatching { com.privacy.app.data.Efficiency.consistencyFromRows(today, db.historyDao().range(today - 30, today - 1)) }.getOrNull() ?: 0
        val eff = com.privacy.app.data.Efficiency.ofTasks(tasks, manual, cons)
        provideContent { WidgetContent(context, tasks, eff) }
    }
}

class TaskWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TaskWidget()
}

@Composable
private fun WidgetContent(context: Context, tasks: List<TaskEntity>, eff: Float) {
    val open = actionStartActivity(Intent(context, com.privacy.app.MainActivity::class.java).putExtra("open_screen", "tasks").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    Column(GlanceModifier.fillMaxSize().background(ImageProvider(R.drawable.widget_bg)).padding(horizontal = 12.dp, vertical = 10.dp).clickable(open)) {
        Row(GlanceModifier.fillMaxWidth().padding(bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(String.format(java.util.Locale.US, "%.0f%%", eff * 100f), style = TextStyle(color = white, fontSize = 20.sp, fontWeight = FontWeight.Medium))
            Spacer(GlanceModifier.width(6.dp))
            // a narrow widget says "Eff." so it stays on one line
            Text(if (androidx.glance.LocalSize.current.width < 190.dp) "Eff." else "Efficiency", maxLines = 1, style = TextStyle(color = muted, fontSize = 13.sp))
        }
        if (tasks.isEmpty()) {
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No tasks today", style = TextStyle(color = muted, fontSize = 13.sp))
            }
        } else {
            LazyColumn(GlanceModifier.fillMaxSize()) {
                items(tasks, itemId = { it.id }) { t -> TaskLine(t) }
            }
        }
    }
}

/** Read-only line: a small round bullet, then the task name (struck through when done, "*" while in progress). */
@Composable
private fun TaskLine(t: TaskEntity) {
    val done = t.status == TaskStatus.COMPLETED
    Row(GlanceModifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.Top) {
        Text(
            "●", modifier = GlanceModifier.width(15.dp),
            style = TextStyle(color = ColorProvider(Color(0xFF8A8A8A)), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        )
        Text(
            t.title + if (t.status == TaskStatus.IN_PROGRESS) " *" else "", maxLines = 2, modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(
                color = if (done) muted else white, fontSize = 14.sp, fontWeight = FontWeight.Normal,
                textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None
            )
        )
    }
}

// ------------------------------------------------------------------------------------------ actions
/**
 * Widget taps must feel instant, so they do the bare minimum: one tiny database write, then ONE refresh of this widget.
 * Work that is only bookkeeping (the history snapshot) runs after the refresh, never before it.
 * A mutex keeps quick successive taps in order instead of racing each other.
 */
private val tapLock = kotlinx.coroutines.sync.Mutex()
private var snapshotJob: kotlinx.coroutines.Job? = null
private val bookkeeping = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

private suspend fun refresh(context: Context, glanceId: GlanceId?) {
    try {
        if (glanceId != null) TaskWidget().update(context, glanceId) else TaskWidget().updateAll(context)
    } catch (_: Exception) { }
}

private suspend fun changeTask(context: Context, glanceId: GlanceId, id: Long, next: (TaskEntity) -> TaskStatus) {
    val app = context.applicationContext
    val dao = AppDatabase.get(app).taskDao()
    tapLock.withLock {
        val t = dao.get(id) ?: return
        val status = next(t)
        dao.update(t.copy(status = status, completedAt = if (status == TaskStatus.COMPLETED) System.currentTimeMillis() else null))
        refresh(app, glanceId)
    }
    // history snapshot is bookkeeping: wait until the taps stop, then do it once
    snapshotJob?.cancel()
    snapshotJob = bookkeeping.launch {
        kotlinx.coroutines.delay(2500)
        runCatching { TaskRepo(dao, AppDatabase.get(app).historyDao(), app).snapshotToday() }
    }
}

/** The circle: cross the task out (or bring it back). */
class TickAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[TaskIdKey] ?: return
        changeTask(context, glanceId, id) { if (it.status == TaskStatus.COMPLETED) TaskStatus.NOT_STARTED else TaskStatus.COMPLETED }
    }
}

/** The task name: mark it "in progress" (or back to not started). Widgets can't detect a long-press, so this is the tap for it. */
class ProgressAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[TaskIdKey] ?: return
        changeTask(context, glanceId, id) { if (it.status == TaskStatus.IN_PROGRESS) TaskStatus.NOT_STARTED else TaskStatus.IN_PROGRESS }
    }
}

