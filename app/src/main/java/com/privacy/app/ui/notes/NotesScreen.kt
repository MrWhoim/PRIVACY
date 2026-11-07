@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.privacy.app.ui.notes

import androidx.compose.foundation.layout.Box
import com.privacy.app.ui.pixelGlass
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.statusBarsPadding
import com.privacy.app.ui.Appear
import com.privacy.app.ui.MonoLabel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privacy.app.ai.NoteRules
import com.privacy.app.data.NoteEntity
import com.privacy.app.ui.EmptyState
import com.privacy.app.ui.MainViewModel
import com.privacy.app.ui.ScreenBackground
import com.privacy.app.ui.ScreenHeader
import com.privacy.app.ui.chat.MarkdownText
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.widget.Toast

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.io.File

private data class Editing(val note: NoteEntity?)
private const val MAX_UNDO = 8


@Composable
fun NotesScreen(vm: MainViewModel, onBack: () -> Unit, onOpenGuide: () -> Unit = {}) {
    val notes by vm.notes.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Editing?>(null) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var confirmDeleteMany by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()
    val ctx = LocalContext.current
    val newReq by com.privacy.app.AppState.newNoteRequest
    val openReq by com.privacy.app.AppState.openNoteId
    LaunchedEffect(openReq, notes) {
        val id = openReq
        if (id != null) notes.firstOrNull { it.id == id }?.let { editing = Editing(it); com.privacy.app.AppState.openNoteId.value = null }
    }
    LaunchedEffect(newReq) { if (newReq) { editing = Editing(null); com.privacy.app.AppState.newNoteRequest.value = false } }
    val colors = MaterialTheme.colorScheme
    com.privacy.app.ai.TelegramPeers.rev.collectAsStateWithLifecycle().value
    val sharedIds = com.privacy.app.ai.TelegramPeers.liveNoteIds(vm.g)
    val shown = if (query.isBlank()) notes else notes.filter { it.title.contains(query, true) || it.body.contains(query, true) }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val open = editing
    androidx.compose.runtime.SideEffect { com.privacy.app.AppState.blockMenuSwipe = open != null }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { com.privacy.app.AppState.blockMenuSwipe = false } }
    if (open != null) {
        NoteDetail(
            note = open.note,
            onClose = { editing = null },
            onSave = { id, t, b, p -> vm.saveNoteNow(id, t, b, p) },
            onFlush = { id, t, b, p -> vm.viewModelScope.launch { vm.saveNoteNow(id, t, b, p) } },
            onDelete = { id -> id?.let { vm.deleteNote(it) }; editing = null },
            onRewrite = { t, b, instr -> vm.rewriteNote(t, b, instr) },
            g = vm.g
        )
        return
    }

    BackHandler(enabled = selecting) { selected = emptySet() }
    if (confirmDeleteMany) AlertDialog(
        onDismissRequest = { confirmDeleteMany = false },
        title = { Text("Delete ${selected.size} note${if (selected.size == 1) "" else "s"}?") },
        text = { Text("They will be removed for good.") },
        confirmButton = { TextButton(onClick = { vm.deleteNotes(selected); selected = emptySet(); confirmDeleteMany = false }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDeleteMany = false }) { Text("Keep") } }
    )
    ScreenBackground {
        Column(Modifier.fillMaxSize()) {
            if (selecting) {
                val chosen = notes.filter { it.id in selected }
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Rounded.Close, "Cancel selection") }
                    Text("${selected.size} selected", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    IconButton(onClick = { selected = shown.map { it.id }.toSet() }) { Icon(Icons.Rounded.SelectAll, "Select all") }
                    IconButton(onClick = {
                        val text = chosen.joinToString("\n\n———\n\n") { it.title + "\n\n" + it.body }
                        ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, text) }, "Share notes").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Icon(Icons.Rounded.Share, "Share as text") }
                    IconButton(onClick = { NoteImage.shareMany(ctx, chosen.map { it.title to it.body }) }) { Icon(Icons.Rounded.IosShare, "Share as pictures") }
                    IconButton(onClick = {
                        val n = NoteImage.saveManyToGallery(ctx, chosen.map { it.title to it.body })
                        Toast.makeText(ctx, "Saved $n picture${if (n == 1) "" else "s"} to Pictures/PRIVACY", Toast.LENGTH_SHORT).show()
                        selected = emptySet()
                    }) { Icon(Icons.Rounded.Image, "Save as pictures") }
                    IconButton(onClick = { confirmDeleteMany = true }) { Icon(Icons.Rounded.Delete, "Delete") }
                }
            } else ScreenHeader("Notes", "${notes.size} saved", onBack = onBack) {
                IconButton(onClick = { editing = Editing(null) }) { Icon(Icons.Rounded.Add, "New note", modifier = Modifier.size(26.dp)) }
            }
            TextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), singleLine = true,
                placeholder = { Text("Search notes") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colors.surfaceVariant, unfocusedContainerColor = colors.surfaceVariant,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                )
            )
            if (shown.isEmpty()) {
                EmptyState(Icons.Rounded.Notes, if (notes.isEmpty()) "No notes yet" else "No matches",
                    "Tap + to write one, or tell the assistant: “save a note about…”.")
            } else {
                LazyColumn(state = listState, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 24.dp)) {
                    items(shown, key = { it.id }) { n ->
                        val picked = n.id in selected
                        Column(
                            Modifier.fillMaxWidth().background(if (picked) colors.onSurface.copy(alpha = 0.10f) else Color.Transparent).combinedClickable(
                                onClick = { if (selecting) selected = if (picked) selected - n.id else selected + n.id else editing = Editing(n) },
                                onLongClick = { selected = if (picked) selected - n.id else selected + n.id }
                            )
                        ) {
                            Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val parts = NoteRules.splitTag(n.title)
                                    if (parts != null) { TagChip(parts.first); Spacer(Modifier.size(8.dp)) }
                                    Text(parts?.second ?: n.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (n.id in sharedIds) { Text("Shared", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = colors.background, modifier = Modifier.padding(end = 6.dp).background(colors.onSurface, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp)) }
                                                                        if (n.pinned) Icon(Icons.Rounded.PushPin, "Pinned", tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                }
                                val preview = n.body.replace(NoteAudio.inText, "🔊 Audio\n").replace(Regex("(?m)^!\\[img\\]\\(.*\\)$"), "").replace("**", "").replace("==", "").replace("~~", "").replace(Regex("(?m)^#+ ?"), "").trim()
                                if (preview.isNotBlank()) Text(preview, maxLines = 2, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant, fontSize = 14.sp)
                                Text(
                                    Instant.ofEpochMilli(n.updatedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM, HH:mm")),
                                    fontSize = 12.sp, color = colors.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                            HorizontalDivider(color = colors.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ live styling for the editor (no hidden characters, so cursor maths stays exact)
private val liveRe = Regex("\\*\\*(.+?)\\*\\*|\\*(\\S(?:.*?\\S)?)\\*|==(.+?)==|~~(.+?)~~|`([^`]+)`")

private class MdStyler(val dim: Color, val ink: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val t = text.text
        val a = buildAnnotatedString {
            append(t)
            var pos = 0
            for (line in t.split("\n")) {
                if (line.startsWith("#")) {
                    val lvl = line.takeWhile { it == '#' }.length
                    addStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = (if (lvl == 1) 26 else if (lvl == 2) 22 else 19).sp), pos, pos + line.length)
                    addStyle(SpanStyle(color = dim), pos, pos + lvl.coerceAtMost(line.length))
                }
                if (line.startsWith("> ")) addStyle(SpanStyle(fontStyle = FontStyle.Italic), pos, pos + line.length)
                pos += line.length + 1
            }
            for (m in liveRe.findAll(t)) {
                val r = m.range
                val mk = when { m.groups[1] != null || m.groups[3] != null || m.groups[4] != null -> 2; else -> 1 }
                when {
                    m.groups[1] != null -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), r.first, r.last + 1)
                    m.groups[2] != null -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), r.first, r.last + 1)
                    m.groups[3] != null -> addStyle(SpanStyle(background = Color(0x66FFD54F)), r.first, r.last + 1)
                    m.groups[4] != null -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), r.first, r.last + 1)
                    else -> addStyle(SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, background = ink.copy(alpha = 0.12f)), r.first, r.last + 1)
                }
                addStyle(SpanStyle(color = dim), r.first, r.first + mk)
                addStyle(SpanStyle(color = dim), r.last + 1 - mk, r.last + 1)
            }
        }
        return TransformedText(a, OffsetMapping.Identity)
    }
}

private fun wrap(v: TextFieldValue, mark: String): TextFieldValue {
    val t = v.text; val s = v.selection.min; val e = v.selection.max
    val before = s >= mark.length && t.substring(s - mark.length, s) == mark
    val after = t.length >= e + mark.length && t.substring(e, e + mark.length) == mark
    return if (before && after) {
        TextFieldValue(t.removeRange(e, e + mark.length).removeRange(s - mark.length, s), TextRange(s - mark.length, e - mark.length))
    } else {
        val sel = t.substring(s, e)
        TextFieldValue(t.substring(0, s) + mark + sel + mark + t.substring(e), if (s == e) TextRange(s + mark.length) else TextRange(s + mark.length, e + mark.length))
    }
}

/** Puts [prefix] at the start of every selected line (or removes it if it is already there). Headings replace each other. */
private fun linePrefix(v: TextFieldValue, prefix: String, heading: Boolean = false): TextFieldValue {
    val t = v.text
    val from = t.lastIndexOf('\n', (v.selection.min - 1).coerceAtLeast(-1)).let { if (v.selection.min == 0) 0 else it + 1 }
    var to = t.indexOf('\n', v.selection.max); if (to < 0) to = t.length
    val lines = t.substring(from, to).split("\n")
    val allHave = lines.all { it.startsWith(prefix) }
    val out = lines.mapIndexed { i, l ->
        var x = l
        if (heading) x = x.trimStart('#').trimStart()
        else if (prefix == "1. ") { /* numbered */ }
        if (allHave) l.removePrefix(prefix) else if (prefix == "1. ") "${i + 1}. " + x else prefix + x
    }.joinToString("\n")
    val nt = t.substring(0, from) + out + t.substring(to)
    return TextFieldValue(nt, TextRange((from + out.length)))
}

private fun insertAt(v: TextFieldValue, text: String): TextFieldValue {
    val t = v.text; val s = v.selection.min; val e = v.selection.max
    return TextFieldValue(t.substring(0, s) + text + t.substring(e), TextRange(s + text.length))
}

/** Opens read-only. The eye switches to editing and back. Everything is saved automatically a moment after you stop typing. */
@Composable
private fun NoteDetail(
    note: NoteEntity?,
    onClose: () -> Unit,
    onSave: suspend (Long?, String, String, Boolean) -> Long,
    onFlush: (Long?, String, String, Boolean) -> Unit,
    onDelete: (Long?) -> Unit,
    onRewrite: suspend (String, String, String) -> kotlin.Result<String>,
    g: com.privacy.app.AppGraph
) {
    val colors = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    var noteId by remember { mutableStateOf(note?.id) }
    var title by remember { mutableStateOf(note?.title.orEmpty()) }
    var audio by remember { mutableStateOf(NoteAudio.split(note?.body.orEmpty()).first) }
    var tfv by remember { mutableStateOf(TextFieldValue(NoteAudio.split(note?.body.orEmpty()).second)) }
    var pinned by remember { mutableStateOf(note?.pinned ?: false) }
    var editMode by remember { mutableStateOf(note == null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var savedTriple by remember { mutableStateOf(Triple(note?.title.orEmpty(), note?.body.orEmpty(), note?.pinned ?: false)) }
    var savedLabel by remember { mutableStateOf("") }
    // AI rewrite: a prompt says what is needed; the last few versions are kept so the change can be undone step by step
    val undoStack = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    var rewriteDialog by remember { mutableStateOf(false) }
    var rewriting by remember { mutableStateOf(false) }
    val rewriteScope = rememberCoroutineScope()
    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
    )
    val styler = remember(colors.onSurfaceVariant, colors.onSurface) { MdStyler(colors.onSurface.copy(alpha = 0.32f), colors.onSurface) }

    // Telegram sharing: while a friend has edit access this copy is view-only and follows the note
    val tgSessions by com.privacy.app.ai.TelegramHub.noteSessions.collectAsStateWithLifecycle()
    val tgSession = noteId?.let { tgSessions[it] }
    com.privacy.app.ai.TelegramPeers.rev.collectAsStateWithLifecycle().value
    val peerInfo = noteId?.let { com.privacy.app.ai.TelegramPeers.info(g, it) }
    val peerView = peerInfo != null && ((peerInfo.kind == "guest" && peerInfo.mode == "view") || (peerInfo.kind == "host" && peerInfo.mode == "edit"))
    val locked = tgSession?.editor != null || peerView
    var tgPicker by remember { mutableStateOf(false) }
    var tgStop by remember { mutableStateOf(false) }
    LaunchedEffect(locked) { if (locked) editMode = false }
    // a note shared app-to-app follows the other side live (only while this screen has no unsaved typing of its own)
    LaunchedEffect(peerInfo != null, noteId) {
        val id = noteId
        while (peerInfo != null && id != null) {
            val n = g.db.noteDao().get(id)
            val cur = Triple(title, NoteAudio.join(audio, tfv.text), pinned)
            if (n != null && cur == savedTriple && (n.body != cur.second || n.title != title)) {
                val sp = NoteAudio.split(n.body); audio = sp.first; tfv = TextFieldValue(sp.second); title = n.title; savedTriple = Triple(n.title, n.body, pinned)
            }
            delay(1500)
        }
    }
    LaunchedEffect(locked, noteId) {
        val id = noteId
        while (locked && id != null) {
            val n = g.db.noteDao().get(id)
            if (n != null && n.body != NoteAudio.join(audio, tfv.text)) { val sp = NoteAudio.split(n.body); audio = sp.first; tfv = TextFieldValue(sp.second); savedTriple = Triple(title, n.body, pinned) }
            delay(1500)
        }
    }

    // autosave while typing
    LaunchedEffect(title, tfv.text, pinned) {
        val cur = Triple(title, NoteAudio.join(audio, tfv.text), pinned)
        if (locked) return@LaunchedEffect
        if (cur == savedTriple || (title.isBlank() && tfv.text.isBlank())) return@LaunchedEffect
        delay(600)
        noteId = onSave(noteId, title, cur.second, pinned)
        savedTriple = cur
        savedLabel = "Saved"
    }
    fun leave() {
        val cur = Triple(title, NoteAudio.join(audio, tfv.text), pinned)
        if (!locked && cur != savedTriple && (title.isNotBlank() || tfv.text.isNotBlank())) onFlush(noteId, title, cur.second, pinned)
        onClose()
    }
    BackHandler { leave() }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val name = runCatching {
                val dir = File(ctx.filesDir, "note_images").apply { mkdirs() }
                val f = File(dir, "n-${System.currentTimeMillis()}.jpg")
                val src = ctx.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it) }
                val sc = 1280f / maxOf(src.width, src.height).coerceAtLeast(1)
                val bmp = if (sc < 1f) android.graphics.Bitmap.createScaledBitmap(src, (src.width * sc).toInt(), (src.height * sc).toInt(), true) else src
                f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
                f.name
            }.getOrNull()
            if (name != null) tfv = insertAt(tfv, "\n![img]($name)\n")
            else Toast.makeText(ctx, "Couldn't add that picture.", Toast.LENGTH_SHORT).show()
        }
    }

    if (tgPicker) com.privacy.app.ui.chat.FriendPicker(
        g, "Share this note with…", "Send",
        onDismiss = { tgPicker = false },
        allowMe = true,
        alreadyWith = com.privacy.app.ai.TelegramPeers.sharedWith(g, "note", noteId),
        onConfirm = { chats ->
            tgPicker = false
            val id = noteId
            if (id != null) Toast.makeText(ctx, com.privacy.app.ai.TelegramHub.inviteNote(g, id, title.ifBlank { "Untitled" }, chats), Toast.LENGTH_LONG).show()
        }
    )
    if (tgStop) AlertDialog(
        onDismissRequest = { tgStop = false },
        title = { Text("Stop Telegram sharing?") },
        text = { Text("Your friends keep the last version in Telegram but it stops updating.") },
        confirmButton = { TextButton(onClick = { tgStop = false; noteId?.let { com.privacy.app.ai.TelegramHub.endNote(g, it) } }) { Text("Stop") } },
        dismissButton = { TextButton(onClick = { tgStop = false }) { Text("Keep") } }
    )
    ScreenBackground {
        Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 6.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { leave() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Spacer(Modifier.weight(1f))
                if (editMode && savedLabel.isNotEmpty()) Text(savedLabel, fontSize = 12.sp, color = colors.onSurfaceVariant)
                if (undoStack.isNotEmpty()) IconButton(onClick = {
                    val prev = undoStack.removeAt(undoStack.lastIndex)
                    tfv = TextFieldValue(prev, TextRange(prev.length))
                }) { Icon(Icons.AutoMirrored.Rounded.Undo, "Undo AI change (${undoStack.size} left)") }
                if (!locked) IconButton(onClick = { rewriteDialog = true }) { Icon(Icons.Rounded.AutoAwesome, "Rewrite with AI") }
                if (!locked) IconButton(onClick = { editMode = !editMode }) { Icon(if (editMode) Icons.Rounded.Visibility else Icons.Rounded.Edit, if (editMode) "Preview" else "Edit") }
                IconButton(onClick = { pinned = !pinned }) {
                    Icon(Icons.Rounded.PushPin, "Pin", tint = if (pinned) colors.onSurface else colors.onSurfaceVariant.copy(alpha = 0.5f))
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Share as image") }, leadingIcon = { Icon(Icons.Rounded.Share, null) },
                            onClick = { menu = false; NoteImage.share(ctx, title, tfv.text) }
                        )
                        DropdownMenuItem(
                            text = { Text("Save as image") }, leadingIcon = { Icon(Icons.Rounded.Image, null) },
                            onClick = {
                                menu = false
                                val ok = NoteImage.saveToGallery(ctx, title, tfv.text)
                                Toast.makeText(ctx, if (ok) "Saved to Pictures/PRIVACY" else "Couldn't save – use Share instead.", Toast.LENGTH_SHORT).show()
                            }
                        )
                        if (peerView) DropdownMenuItem(
                            text = { Text("Request edit access") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                            onClick = { menu = false; noteId?.let { com.privacy.app.ai.TelegramPeers.requestEdit(g, it) }; Toast.makeText(ctx, "Request sent to whoever is editing. You can edit once they allow it.", Toast.LENGTH_LONG).show() }
                        )
                        if (peerInfo != null && peerInfo.kind == "guest" && peerInfo.mode == "edit") DropdownMenuItem(
                            text = { Text("Release editing") }, leadingIcon = { Icon(Icons.Rounded.Visibility, null) },
                            onClick = { menu = false; noteId?.let { com.privacy.app.ai.TelegramPeers.releaseEdit(g, it) } }
                        )
                        if (peerInfo != null) DropdownMenuItem(
                            text = { Text(if (peerInfo.kind == "host") "Stop sharing" else "Stop following") }, leadingIcon = { Icon(Icons.Rounded.Send, null) },
                            onClick = { menu = false; noteId?.let { com.privacy.app.ai.TelegramPeers.endNote(g, it) } }
                        )
                        if (noteId != null) DropdownMenuItem(
                            text = { Text("Delete note") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                            onClick = { menu = false; confirmDelete = true }
                        )
                    }
                }
                IconButton(onClick = {
                    if (tgSession != null) tgStop = true
                    else if (g.settings.telegramToken.value.isBlank() || (g.settings.telegramUserId.value.isBlank() && com.privacy.app.ai.TelegramPeers.peers(g).none { it.state == "ok" }))
                        Toast.makeText(ctx, "Connect your Telegram bot first (Vault → Telegram).", Toast.LENGTH_LONG).show()
                    else if (title.isBlank() && tfv.text.isBlank()) Toast.makeText(ctx, "Write something first.", Toast.LENGTH_SHORT).show()
                    else rewriteScope.launch {
                        if (noteId == null || Triple(title, tfv.text, pinned) != savedTriple) { noteId = onSave(noteId, title, tfv.text, pinned); savedTriple = Triple(title, tfv.text, pinned) }
                        tgPicker = true
                    }
                }) {
                    Icon(Icons.Rounded.Send, "Telegram connect", tint = if (tgSession != null) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.6f))
                }
            }

            if (peerInfo != null) Text(
                if (peerInfo.kind == "host") (if (peerInfo.mode == "edit") "✏️ ${peerInfo.who} is editing · view only for you (menu → Request edit access)" else "Shared live with ${peerInfo.who} · you edit") else if (peerInfo.mode == "edit") "Shared by ${peerInfo.who} · you edit now, changes sync live" else "Shared by ${peerInfo.who} · view only (menu → Request edit access)",
                fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp)
            )
            if (tgSession != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (locked) "✏️ ${com.privacy.app.ai.TelegramHub.friendName(g, tgSession.editor.orEmpty())} is editing · view only" else "Shared in Telegram with ${tgSession.msgs.size} friend${if (tgSession.msgs.size == 1) "" else "s"} · you edit",
                        fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f)
                    )
                    if (locked) TextButton(onClick = { noteId?.let { com.privacy.app.ai.TelegramHub.takeBack(g, it) } }) { Text("Take back") }
                }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (editMode) {
                    TextField(
                        value = title, onValueChange = { title = it }, placeholder = { Text("Title", fontSize = 26.sp, fontWeight = FontWeight.SemiBold) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), colors = fieldColors,
                        textStyle = MaterialTheme.typography.headlineMedium
                    )
                    audio?.let { NoteAudioBar(it) }
                    TextField(
                        value = tfv, onValueChange = { tfv = it }, placeholder = { Text("Write here…") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 360.dp), colors = fieldColors,
                        visualTransformation = styler,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 25.sp)
                    )
                } else {
                    Column(Modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val parts = NoteRules.splitTag(title)
                        if (parts != null) Row(verticalAlignment = Alignment.CenterVertically) {
                            TagChip(parts.first)
                            Spacer(Modifier.size(8.dp))
                            MonoLabel(NoteRules.TAGS.firstOrNull { it.first == parts.first }?.second ?: "made by the app")
                        }
                        Text(parts?.second ?: title.ifBlank { "Untitled" }, style = MaterialTheme.typography.headlineMedium)
                        if (note != null) MonoLabel(Instant.ofEpochMilli(note.updatedAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")))
                        audio?.let { NoteAudioBar(it) }
                        SelectionContainer {
                            MarkdownText(tfv.text.ifBlank { "Empty note – tap the pencil to write." }, colors.onSurface, Modifier.fillMaxWidth(), fontSize = 17.sp)
                        }
                    }
                }
            }

            // ---- formatting strip: no symbols to type ----
            if (editMode) {
                HorizontalDivider(color = colors.outlineVariant)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 2.dp)) {
                    @Composable fun Tool(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
                        IconButton(onClick = onClick) { Icon(icon, label, tint = colors.onSurface) }
                    }
                    Tool(Icons.Rounded.FormatBold, "Bold") { tfv = wrap(tfv, "**") }
                    Tool(Icons.Rounded.FormatItalic, "Italic") { tfv = wrap(tfv, "*") }
                    Tool(Icons.Rounded.StrikethroughS, "Strikethrough") { tfv = wrap(tfv, "~~") }
                    Tool(Icons.Rounded.BorderColor, "Highlight") { tfv = wrap(tfv, "==") }
                    Tool(Icons.Rounded.Title, "Big heading") { tfv = linePrefix(tfv, "# ", heading = true) }
                    Tool(Icons.Rounded.TextFields, "Heading") { tfv = linePrefix(tfv, "## ", heading = true) }
                    Tool(Icons.Rounded.FormatListBulleted, "Bullets") { tfv = linePrefix(tfv, "- ") }
                    Tool(Icons.Rounded.FormatListNumbered, "Numbered list") { tfv = linePrefix(tfv, "1. ") }
                    Tool(Icons.Rounded.CheckBox, "Checklist") { tfv = linePrefix(tfv, "- [ ] ") }
                    Tool(Icons.Rounded.FormatQuote, "Quote") { tfv = linePrefix(tfv, "> ") }
                    Tool(Icons.Rounded.Code, "Code") { tfv = wrap(tfv, "`") }
                    Tool(Icons.Rounded.HorizontalRule, "Divider") { tfv = insertAt(tfv, "\n---\n") }
                    Tool(Icons.Rounded.Image, "Add picture") { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                }
            }
        }
    }

    if (rewriteDialog) {
        var prompt by remember { mutableStateOf("") }
        val selRange = remember { tfv.selection }
        val hasSel = !selRange.collapsed && selRange.max <= tfv.text.length
        var onlySel by remember { mutableStateOf(hasSel) }
        AlertDialog(
            onDismissRequest = { if (!rewriting) rewriteDialog = false },
            title = { Text("Rewrite with AI") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Say what kind of note you need. The old version stays in Undo (last ${MAX_UNDO} changes).", fontSize = 13.sp, color = colors.onSurfaceVariant)
                    if (hasSel) Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Switch(checked = onlySel, onCheckedChange = { onlySel = it })
                        Spacer(Modifier.size(8.dp))
                        Text(if (onlySel) "Only the selected text (${selRange.max - selRange.min} characters)" else "The whole note", fontSize = 13.sp)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "Clean & tidy" to "Clean up and tidy this note: fix spelling and grammar, clear structure.",
                            "Add Q&A" to "Keep the note as it is and add a '## Q&A' section at the end with 6 useful questions and short answers about it.",
                            "Study guide" to "Turn this into a study guide with headings, key points, definitions and a short recap.",
                            "Summarize" to "Summarize this note into the key points (short).",
                            "More detail" to "Expand this note with more useful detail and examples, without inventing facts."
                        ).forEach { (label, text) -> androidx.compose.material3.AssistChip(onClick = { prompt = text }, label = { Text(label, fontSize = 12.sp) }) }
                    }
                    TextField(
                        value = prompt, onValueChange = { prompt = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 90.dp),
                        placeholder = { Text("Describe it, e.g. “make it a revision sheet with a table of formulas”") }
                    )
                    if (rewriting) Row(verticalAlignment = Alignment.CenterVertically) { com.privacy.app.ui.PixelLoader(); Spacer(Modifier.size(8.dp)); Text("Rewriting…", fontSize = 13.sp) }
                }
            },
            confirmButton = {
                TextButton(enabled = !rewriting && (tfv.text.isNotBlank() || title.isNotBlank()), onClick = {
                    rewriting = true
                    val part = hasSel && onlySel
                    val src = if (part) tfv.text.substring(selRange.min, selRange.max) else tfv.text
                    val instr = if (part) "Rewrite ONLY the text under NOTE below, as asked: ${prompt.ifBlank { "make it clearer and tidier" }}. Reply with only the replacement for that passage, nothing else." else prompt
                    rewriteScope.launch {
                        onRewrite(title, src, instr).fold(
                            onSuccess = { n ->
                                undoStack.add(tfv.text); while (undoStack.size > MAX_UNDO) undoStack.removeAt(0)
                                tfv = if (part) {
                                    val full = tfv.text.substring(0, selRange.min) + n + tfv.text.substring(selRange.max)
                                    TextFieldValue(full, TextRange(selRange.min + n.length))
                                } else TextFieldValue(n, TextRange(n.length))
                                rewriteDialog = false
                            },
                            onFailure = { Toast.makeText(ctx, it.message ?: "Couldn't rewrite.", Toast.LENGTH_LONG).show() }
                        )
                        rewriting = false
                    }
                }) { Text("Rewrite") }
            },
            dismissButton = { TextButton(enabled = !rewriting, onClick = { rewriteDialog = false }) { Text("Cancel") } }
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this note?") },
            text = { Text("It will be removed for good.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(noteId) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } }
        )
    }
}

/** Small label for notes the app wrote itself ([Conversation], [Automation], [Routine]). */
@Composable
private fun TagChip(tag: String) {
    val c = MaterialTheme.colorScheme
    Text(
        tag, fontSize = 11.sp, color = c.onSurfaceVariant,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(c.surfaceVariant).padding(horizontal = 8.dp, vertical = 2.dp)
    )
}
