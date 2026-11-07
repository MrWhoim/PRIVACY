package com.privacy.app.widget

import android.app.Activity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import androidx.glance.appwidget.lazy.items as gitems
import androidx.glance.appwidget.lazy.LazyColumn as GLazyColumn
import androidx.compose.runtime.collectAsState
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Column as GColumn
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight as GFontWeight
import androidx.glance.text.Text as GText
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.privacy.app.R
import com.privacy.app.data.AppDatabase
import com.privacy.app.data.NoteEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private fun prefs(c: Context) = c.getSharedPreferences("note_widgets", Context.MODE_PRIVATE)

/** A dark rounded card that shows one note you picked (title + body, scrollable, Markdown shown as headings / bullets). Tap the title to open Notes. */
class NoteViewWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        // everything is read INSIDE the content, as live flows: the widget fills in the moment the note is picked or edited
        val db = AppDatabase.get(context)
        val pref = context.getSharedPreferences("note_widgets", Context.MODE_PRIVATE)
        val noteIdFlow = callbackFlow {
            trySend(pref.getLong("w$appId", -1L))
            val l = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sp, k -> if (k == "w$appId") trySend(sp.getLong(k, -1L)) }
            pref.registerOnSharedPreferenceChangeListener(l)
            awaitClose { pref.unregisterOnSharedPreferenceChangeListener(l) }
        }
        val noteFlow = combine(noteIdFlow, db.noteDao().observe()) { nid, list -> list.firstOrNull { it.id == nid } }
        provideContent {
            val note by noteFlow.collectAsState(null)
            val open = actionStartActivity(Intent(context, com.privacy.app.MainActivity::class.java).putExtra("open_screen", "notes")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            val n = note
            GColumn(GlanceModifier.fillMaxSize().background(ImageProvider(R.drawable.widget_bg)).padding(top = 14.dp, start = 14.dp, end = 14.dp, bottom = 6.dp)) {
                if (n == null) {
                    GText("No note chosen yet. Tap to open Notes; remove this widget and add it again to pick a note.", style = TextStyle(color = ColorProvider(Color(0xFFB0B0B0)), fontSize = 13.sp), modifier = GlanceModifier.clickable(open))
                } else {
                    GText(com.privacy.app.ai.NoteRules.splitTag(n.title)?.second ?: n.title, maxLines = 2, style = TextStyle(color = ColorProvider(Color.White), fontSize = 17.sp, fontWeight = GFontWeight.Bold), modifier = GlanceModifier.clickable(open).padding(bottom = 6.dp))
                    val lines = remember(n.body) { md(n.body) }
                    GLazyColumn(GlanceModifier.fillMaxSize()) {
                        gitems(lines) { l ->
                            GText(
                                l.text,
                                style = TextStyle(
                                    color = ColorProvider(if (l.head > 0 || l.bold) Color.White else Color(0xFFD0D0D0)),
                                    fontSize = when (l.head) { 1 -> 16.sp; 2 -> 15.sp; 3 -> 14.sp; else -> 13.sp },
                                    fontWeight = if (l.head > 0 || l.bold) GFontWeight.Bold else GFontWeight.Normal
                                ),
                                modifier = GlanceModifier.padding(top = if (l.head > 0) 6.dp else 2.dp, start = (l.indent * 12).dp)
                            )
                        }
                    }
                }
            }
        }
    }

    private class Line(val text: String, val head: Int = 0, val bold: Boolean = false, val indent: Int = 0)

    /** Light Markdown for the widget: headings, bullets, checkboxes, quotes and tables become plain readable lines. */
    private fun md(body: String): List<Line> {
        val out = mutableListOf<Line>()
        for (raw in NoteAudioStrip(body).lines()) {
            val t = raw.trimEnd()
            if (t.isBlank()) { out += Line(" "); continue }
            if (t.trim().startsWith("![")) continue
            if (Regex("^\\s*\\|?[-:| ]{3,}\\|?\\s*$").matches(t)) continue
            val ind = (t.length - t.trimStart().length) / 2
            val s = t.trim()
            val h = Regex("^(#{1,3})\\s+(.*)$").find(s)
            when {
                h != null -> out += Line(clean(h.groupValues[2]), head = h.groupValues[1].length)
                s.startsWith("- [ ]") -> out += Line("☐ " + clean(s.removePrefix("- [ ]")), indent = ind)
                s.startsWith("- [x]") || s.startsWith("- [X]") -> out += Line("☑ " + clean(s.drop(5)), indent = ind)
                s.startsWith("- ") || s.startsWith("* ") -> out += Line("• " + clean(s.drop(2)), indent = ind)
                s.startsWith(">") -> out += Line("❝ " + clean(s.removePrefix(">")))
                s.startsWith("|") -> out += Line(s.trim('|').split('|').joinToString("  ·  ") { clean(it.trim()) })
                s == "---" -> out += Line("──────────")
                else -> out += Line(clean(s), bold = s.startsWith("**") && s.endsWith("**") && s.length > 4)
            }
        }
        return out.take(300)
    }
    private fun NoteAudioStrip(b: String) = b.replace(Regex("^\\[\\[audio:[^\\]]+\\]\\]\\s*"), "")
    private fun clean(s: String) = s.replace("**", "").replace("__", "").replace("==", "").replace("~~", "").replace("`", "").replace(Regex("(?<!\\*)\\*(?!\\*)"), "").trim()
}

class NoteViewWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NoteViewWidget()
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        prefs(context).edit().apply { appWidgetIds.forEach { remove("w$it") } }.apply()
    }
}

/** Refresh every "assigned note" widget (called after a note is saved). */
suspend fun refreshNoteWidgets(context: Context) { runCatching { NoteViewWidget().updateAll(context) } }

/** Opens when the widget is placed: pick which note it shows. */
class NoteWidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appId))
        if (appId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val ctx = applicationContext
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    var notes by remember { mutableStateOf<List<NoteEntity>>(emptyList()) }
                    LaunchedEffect(Unit) { notes = AppDatabase.get(ctx).noteDao().all().sortedByDescending { it.updatedAt } }
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Text("Choose a note for this widget", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
                        if (notes.isEmpty()) Text("You have no notes yet. Create one in the app first, then add this widget again.")
                        LazyColumn {
                            items(notes) { n ->
                                Text(n.title, modifier = Modifier.fillMaxWidth().clickable { choose(ctx, appId, n.id) }.padding(vertical = 14.dp), fontSize = 16.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun choose(ctx: Context, appId: Int, noteId: Long) {
        prefs(ctx).edit().putLong("w$appId", noteId).apply()
        CoroutineScope(Dispatchers.Main).launch {
            runCatching {
                val gid = GlanceAppWidgetManager(ctx).getGlanceIdBy(appId)
                NoteViewWidget().update(ctx, gid)
            }
            setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appId))
            finish()
        }
    }
}
