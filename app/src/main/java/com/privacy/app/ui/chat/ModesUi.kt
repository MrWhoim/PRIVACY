package com.privacy.app.ui.chat

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privacy.app.AppGraph
import com.privacy.app.ai.TelegramHub
import com.privacy.app.media.MusicUi
import com.privacy.app.ui.MainViewModel

// ---------------------------------------------------------------------------------------------------------------
// Pixel sprites (8×8 bitmaps drawn as squares)
// ---------------------------------------------------------------------------------------------------------------
private val SPR_MODES = listOf("##.##", "##.##", ".....", "##.##", "##.##")
private val SPR_DISTRACTED = listOf("..####..", ".#....#.", "#..##..#", "#.#..#.#", "#.#.##.#", "#..#...#", ".#....#.", "..####..")
private val SPR_MUSIC = listOf("..######", "..#....#", "..#....#", "..#....#", "###..###", "###..###", "###..###", "........")
private val SPR_TG_AI = listOf("......##", "....####", "..######", "########", "..###.#.", "...##.#.", "....###.", ".....##.")
private val SPR_TG_NOTE = listOf(".######.", ".#....#.", ".#.##.#.", ".#....#.", ".#.##.#.", ".#....#.", ".######.", "........")

@Composable
fun PixelSprite(rows: List<String>, color: Color, size: Dp) {
    val h = rows.size; val w = rows.maxOf { it.length }
    Canvas(Modifier.size(size)) {
        val px = minOf(this.size.width / w, this.size.height / h)
        val ox = (this.size.width - px * w) / 2f; val oy = (this.size.height - px * h) / 2f
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, ch ->
                if (ch == '#') drawRect(color, Offset(ox + x * px, oy + y * px), Size(px + 0.6f, px + 0.6f))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Modes button (chat header, right side)
// ---------------------------------------------------------------------------------------------------------------
@Composable
fun ModesButton(vm: MainViewModel) {
    val c = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.clip(RoundedCornerShape(14.dp)).background(c.surfaceVariant).clickable { open = true }.padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PixelSprite(SPR_MODES, c.onBackground, 16.dp)
    }
    if (open) ModesDialog(vm) { open = false }
}

private data class ModeDef(val id: String, val title: String, val short: String, val info: String, val sprite: List<String>)

private val MODES = listOf(
    ModeDef("distracted", "Feeling Distracted", "Gentle nudges & topics to get you back on track",
        "How it works: while this mode is ON the assistant speaks first every few minutes – a short thought, a topic or a nudge – so your mind drifts back to what matters.\n\nWhat it does: it never blocks anything. Say “I'm back” or switch it OFF here whenever you like.", SPR_DISTRACTED),
    ModeDef("music", "Music Mode", "Player on top of the chat",
        "How it works: the music player slides in under the header (swipe the bar to go back to your stats).\n\nWhat it does: plays songs found on this phone. Ask the assistant “play <song>”, shuffle, repeat – all from the chat. OFF brings the stats bar back.", SPR_MUSIC),
    ModeDef("tgai", "Telegram AI Connect", "Share your AI with friends in Telegram",
        "How it works: pair your bot with your friend's bot once (Account → Telegram). Then pick the friend here: a new chat “Telegram AI <name>” opens in both apps.\n\nWhat it does: what either of you types and every AI answer shows in both chats; your friend's AI is yours to ask through the shared chat. Several connections can be live at the same time – the list at the bottom of this window shows them, and each can be cancelled.\n\nPrivacy: there is no server. Both apps must be active in the background.", SPR_TG_AI),
    ModeDef("tgnote", "Telegram Notes Connect", "Live shared notes with friends",
        "How it works: open any note and tap the Telegram button in its top-right corner, pick a paired friend (or “Me”) and send.\n\nWhat it does: the note opens by itself in your friend's app and both copies stay in sync. Only ONE person edits at a time, the other sees it view only. To edit, ask the current editor (note menu → Request edit access); if he allows it, he goes to view mode and you edit.\n\nPrivacy: no server – keep the app active in the background.", SPR_TG_NOTE)
)

@Composable
private fun ModesDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val g = vm.g
    val c = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    val distracted by g.settings.distractedMode.collectAsStateWithLifecycle()
    val musicOn by MusicUi.mode
    com.privacy.app.ai.TelegramPeers.rev.collectAsStateWithLifecycle().value
    val liveList = com.privacy.app.ai.TelegramPeers.live(g)
    val titles = remember { androidx.compose.runtime.mutableStateMapOf<Long, String>() }
    androidx.compose.runtime.LaunchedEffect(liveList.size) {
        liveList.filter { it.kind == "note" }.forEach { l -> if (l.ref !in titles) g.db.noteDao().get(l.ref)?.let { titles[l.ref] = it.title.ifBlank { "Untitled" } } }
        liveList.filter { it.kind == "ai" }.forEach { l -> if (l.ref !in titles) g.db.conversationDao().get(l.ref)?.let { titles[l.ref] = it.title } }
    }
    var info by remember { mutableStateOf<ModeDef?>(null) }
    var aiMenu by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var connectInfo by remember { mutableStateOf(false) }

    fun on(id: String) = when (id) { "distracted" -> distracted; "music" -> musicOn; "tgai" -> liveList.any { it.kind == "ai" }; else -> liveList.any { it.kind == "note" } }
    fun tgReady(): Boolean {
        val ok = g.settings.telegramToken.value.isNotBlank()
        if (!ok) Toast.makeText(ctx, "Connect your Telegram bot first (Account → Telegram).", Toast.LENGTH_LONG).show()
        return ok
    }
    fun act(id: String) {
        when (id) {
            "distracted" -> g.settings.setDistractedMode(!distracted)
            "music" -> MusicUi.mode.value = !musicOn
            "tgai" -> if (tgReady()) aiMenu = true
            else -> if (tgReady()) { com.privacy.app.AppState.pendingScreen.value = "notes"; Toast.makeText(ctx, "Open a note and tap its Telegram button.", Toast.LENGTH_LONG).show(); onDismiss() }
        }
    }
    fun tap(m: ModeDef) {
        val seen = g.settings.modesSeen.split(',')
        // switching OFF never needs the explanation
        if (m.id in seen || on(m.id)) { act(m.id); return }
        info = m
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = c.surface, tonalElevation = 6.dp) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Modes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                MODES.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { m ->
                            val isOn = on(m.id)
                            Column(
                                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(20.dp))
                                    .background(if (isOn) c.onSurface.copy(alpha = 0.14f) else c.surfaceVariant.copy(alpha = 0.6f))
                                    .border(BorderStroke(if (isOn) 1.5.dp else 0.dp, if (isOn) c.onSurface else Color.Transparent), RoundedCornerShape(20.dp))
                                    .clickable { tap(m) }.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                PixelSprite(m.sprite, c.onSurface, 26.dp)
                                Text(m.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp)
                                Text(m.short, fontSize = 12.sp, lineHeight = 16.sp, color = c.onSurfaceVariant, modifier = Modifier.weight(1f))
                                Text(if (isOn) "ON" else "OFF", fontWeight = FontWeight.Bold, fontSize = 11.sp,
                                    color = if (isOn) c.background else c.onSurfaceVariant,
                                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (isOn) c.onSurface else c.outline.copy(alpha = 0.25f)).padding(horizontal = 10.dp, vertical = 3.dp))
                            }
                        }
                    }
                }
                Text("Tap a mode to switch it. The first time you will see how it works.", fontSize = 12.sp, lineHeight = 16.sp, color = c.onSurfaceVariant)
                if (liveList.isNotEmpty()) {
                    Text("Live connections", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Column(Modifier.heightIn(max = 190.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        liveList.forEach { l ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surfaceVariant.copy(alpha = 0.6f)).padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text((if (l.kind == "ai") "AI chat" else "Note") + " · " + l.who, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    val what = titles[l.ref]?.let { "“$it” · " }.orEmpty()
                                    Text(what + if (l.kind == "ai") (if (l.role == "host") "your AI shared with ${l.who}" else "${l.who}'s AI shared with you") else (if (l.role == "host") "your note shared with ${l.who}" else "shared by ${l.who}"),
                                        fontSize = 11.sp, color = c.onSurfaceVariant, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                }
                                TextButton(onClick = { com.privacy.app.ai.TelegramPeers.cancel(g, l) }) { Text("Cancel") }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onDismiss) { Text("Close") } }
            }
        }
    }

    info?.let { m ->
        AlertDialog(
            onDismissRequest = { info = null },
            title = { Text(m.title) },
            text = { Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) { Text(m.info, fontSize = 13.sp) } },
            confirmButton = {
                TextButton(onClick = {
                    g.settings.modesSeen = (g.settings.modesSeen.split(',').filter { it.isNotBlank() } + m.id).distinct().joinToString(",")
                    info = null; act(m.id)
                }) { Text("Got it – activate") }
            },
            dismissButton = { TextButton(onClick = { info = null }) { Text("Cancel") } }
        )
    }
    if (aiMenu) AlertDialog(
        onDismissRequest = { aiMenu = false },
        title = { Text("Telegram AI Connect") },
        text = { Text(if (liveList.any { it.kind == "ai" }) "A connection is already live (see the list in Modes). You can add another one." else "Share your AI with a paired friend, or join a friend's AI.") },
        confirmButton = { TextButton(onClick = { aiMenu = false; picker = true }) { Text("Share my AI with friends") } },
        dismissButton = { TextButton(onClick = { aiMenu = false; connectInfo = true }) { Text("Connect to a friend's AI") } }
    )
    if (connectInfo) AlertDialog(
        onDismissRequest = { connectInfo = false },
        title = { Text("Connect to a friend's AI") },
        text = { Text("When a friend who uses the app shares their AI with you, a new chat appears in your own app (“Telegram AI <name>”). Both chats stay in sync: what you write goes to their AI and the answers come back.\n\nFor that you both need your own bot, paired in Account → Telegram.") },
        confirmButton = { TextButton(onClick = { connectInfo = false }) { Text("OK") } }
    )
    if (picker) FriendPicker(
        g, "Share my AI with…", "Send request",
        onDismiss = { picker = false },
        alreadyWith = com.privacy.app.ai.TelegramPeers.sharedWith(g, "ai"),
        onConfirm = { chats ->
            picker = false
            Toast.makeText(ctx, TelegramHub.inviteAi(g, chats), Toast.LENGTH_LONG).show()
            onDismiss()
        }
    )
}

// ---------------------------------------------------------------------------------------------------------------
// Friend list with several selections (shared AI and shared notes)
// ---------------------------------------------------------------------------------------------------------------
@Composable
fun FriendPicker(g: AppGraph, title: String, confirm: String, onDismiss: () -> Unit, allowMe: Boolean = false, alreadyWith: Set<String> = emptySet(), onConfirm: (List<String>) -> Unit) {
    TelegramHub.rev.collectAsStateWithLifecycle().value
    com.privacy.app.ai.TelegramPeers.rev.collectAsStateWithLifecycle().value
    val friends = emptyList<TelegramHub.TgFriend>()
    val peers = com.privacy.app.ai.TelegramPeers.peers(g).filter { it.state == "ok" }
    var sel by remember { mutableStateOf(setOf<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                if (allowMe) Row(Modifier.fillMaxWidth().clickable { sel = if ("me" in sel) sel - "me" else sel + "me" }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = "me" in sel, onCheckedChange = { sel = if (it) sel + "me" else sel - "me" })
                    Column {
                        Text("Me (yourself)", fontSize = 14.sp)
                        Text("Sent to your own Telegram chat with your bot", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (peers.isNotEmpty()) Text("Friends with the app (live sync)", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                peers.forEach { f ->
                    val id = "peer:" + f.bot
                    val already = f.bot in alreadyWith
                    Row(Modifier.fillMaxWidth().clickable(enabled = !already) { sel = if (id in sel) sel - id else sel + id }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = id in sel, enabled = !already, onCheckedChange = { sel = if (it) sel + id else sel - id })
                        Column {
                            Text(f.name.ifBlank { "@" + f.bot }, fontSize = 14.sp, color = if (already) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                            Text(if (already) "Already sharing with this friend" else "@${f.bot} · synced app to app", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (friends.isNotEmpty() && peers.isNotEmpty()) Text("Friends through my bot (they use Telegram only)", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                if (friends.isEmpty() && peers.isEmpty()) Text("No paired friends yet. Pair with your friend’s bot in Account → Telegram first.", fontSize = 13.sp)
                friends.forEach { f ->
                    Row(Modifier.fillMaxWidth().clickable { sel = if (f.chatId in sel) sel - f.chatId else sel + f.chatId }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = f.chatId in sel, onCheckedChange = { sel = if (it) sel + f.chatId else sel - f.chatId })
                        Column {
                            Text(f.name, fontSize = 14.sp)
                            if (f.user.isNotBlank()) Text("@${f.user}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Both apps must be active in the background (Telegram itself need not be open).", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(enabled = sel.isNotEmpty(), onClick = { onConfirm(sel.toList()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ---------------------------------------------------------------------------------------------------------------
// Pop-ups the owner has to answer anywhere in the app: friend requests and edit requests
// ---------------------------------------------------------------------------------------------------------------
@Composable
fun TelegramPrompts(g: AppGraph) {
    TelegramHub.rev.collectAsStateWithLifecycle().value
    val prompts by TelegramHub.prompts.collectAsStateWithLifecycle()
    var dismissedFriend by remember { mutableStateOf(setOf<String>()) }
    val dismissedPeers by com.privacy.app.ai.TelegramPeers.dismissed.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val n = com.privacy.app.ai.TelegramPeers.live(g).size
        if (n > 0) Toast.makeText(ctx, "$n Telegram connection${if (n == 1) " is" else "s are"} still live. See Modes for the list.", Toast.LENGTH_LONG).show()
    }
    com.privacy.app.ai.TelegramPeers.rev.collectAsStateWithLifecycle().value
    val peerEdits by com.privacy.app.ai.TelegramPeers.editReqs.collectAsStateWithLifecycle()
    val pe = peerEdits.firstOrNull()
    if (pe != null) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Edit request") },
            text = { Text("${pe.name} wants to edit the shared note “${pe.title}”.\n\nOnly one person edits at a time. If you allow it, you go to view mode and ${pe.name} edits; every change reaches both apps. You can ask for editing back later.") },
            confirmButton = { TextButton(onClick = { com.privacy.app.ai.TelegramPeers.answerEdit(g, pe, true) }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { com.privacy.app.ai.TelegramPeers.answerEdit(g, pe, false) }) { Text("Decline") } }
        )
        return
    }
    val inv = com.privacy.app.ai.TelegramPeers.invites(g).firstOrNull()
    if (inv != null) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(if (inv.kind == "ai") "AI chat share request" else "Note share request") },
            text = { Text(if (inv.kind == "ai") "${inv.name} wants to share their AI chat with you.\n\nIf you accept, a new chat “Telegram AI ${inv.name}” opens and both of you see every message and answer."
                else "${inv.name} wants to share the note “${inv.title}” with you.\n\nIf you accept, it opens in your app and stays in sync. Only one of you edits at a time.") },
            confirmButton = { TextButton(onClick = { com.privacy.app.ai.TelegramPeers.answerInvite(g, inv, true) }) { Text("Accept") } },
            dismissButton = { TextButton(onClick = { com.privacy.app.ai.TelegramPeers.answerInvite(g, inv, false) }) { Text("Reject") } }
        )
        return
    }
    val pr = com.privacy.app.ai.TelegramPeers.peers(g).firstOrNull { it.state == "in" && it.bot !in dismissedPeers }
    if (pr != null) {
        AlertDialog(
            onDismissRequest = { com.privacy.app.ai.TelegramPeers.dismissRequest(pr.bot) },
            title = { Text("Pairing request") },
            text = { Text("${pr.name} (@${pr.bot}) wants to connect their app with yours, to share notes and AI chats that stay in sync.") },
            confirmButton = { TextButton(onClick = { com.privacy.app.ai.TelegramPeers.accept(g, pr.bot, true) }) { Text("Accept") } },
            dismissButton = { TextButton(onClick = { com.privacy.app.ai.TelegramPeers.accept(g, pr.bot, false) }) { Text("Reject") } }
        )
        return
    }
    val edit = prompts.firstOrNull()
    if (edit != null) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Edit request") },
            text = { Text(edit.text + "\n\nIf you allow it, you switch to view mode and ${edit.text.substringBefore(" wants")} edits. You can take it back any time.") },
            confirmButton = { TextButton(onClick = { TelegramHub.answerEdit(g, edit, true) }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { TelegramHub.answerEdit(g, edit, false) }) { Text("Decline") } }
        )
        return
    }
    val fr = TelegramHub.pending(g).firstOrNull { it.chatId !in dismissedFriend } ?: return
    AlertDialog(
        onDismissRequest = { dismissedFriend = dismissedFriend + fr.chatId },
        title = { Text("Friend request") },
        text = { Text("${fr.name}${if (fr.user.isNotBlank()) " (@${fr.user})" else ""} sent /start to your Telegram bot and wants to be your friend.\n\nAccepted friends can be invited to your shared AI and notes. You can also decide later in Vault → Telegram.") },
        confirmButton = { TextButton(onClick = { TelegramHub.answerFriend(g, fr.chatId, true) }) { Text("Accept") } },
        dismissButton = { TextButton(onClick = { TelegramHub.answerFriend(g, fr.chatId, false) }) { Text("Reject") } }
    )
}
