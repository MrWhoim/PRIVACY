package com.privacy.app.ui.history

import com.privacy.app.ui.pixelGlass
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privacy.app.data.ConversationEntity
import com.privacy.app.ui.EmptyState
import com.privacy.app.ui.MainViewModel
import com.privacy.app.ui.MonoLabel
import com.privacy.app.ui.ScreenBackground
import com.privacy.app.ui.ScreenHeader
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private sealed interface HistoryRow {
    data class Day(val label: String) : HistoryRow
    data class Chat(val c: ConversationEntity) : HistoryRow
}

private fun dayLabel(d: LocalDate): String {
    val today = LocalDate.now()
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> d.format(DateTimeFormatter.ofPattern(if (d.year == today.year) "EEEE, d MMM" else "d MMM yyyy", Locale.getDefault()))
    }
}

/** Saved chats, newest first, grouped by day. Tap one to continue it – the assistant can also search them itself. */
@Composable
fun HistoryScreen(vm: MainViewModel, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val all by vm.conversations.collectAsStateWithLifecycle()
    val current by vm.currentChat.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    com.privacy.app.ai.TelegramPeers.rev.collectAsStateWithLifecycle().value
    val liveIds = com.privacy.app.ai.TelegramPeers.liveChatIds(vm.g)
    var toDelete by remember { mutableStateOf<ConversationEntity?>(null) }
    val colors = MaterialTheme.colorScheme
    val zone = ZoneId.systemDefault()

    val rows = remember(all, query) {
        val filtered = all.filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
        val out = mutableListOf<HistoryRow>()
        var last: LocalDate? = null
        for (c in filtered) {
            val d = Instant.ofEpochMilli(c.updatedAt).atZone(zone).toLocalDate()
            if (d != last) { out += HistoryRow.Day(dayLabel(d)); last = d }
            out += HistoryRow.Chat(c)
        }
        out
    }

    ScreenBackground {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("History", "Saved chats · by day", onBack = onBack) {
                IconButton(onClick = { vm.newChat(); onBack() }) { Icon(Icons.Rounded.Add, "New chat") }
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Search chats") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(10.dp)
            )
            if (rows.isEmpty()) {
                EmptyState(Icons.Rounded.History, if (query.isBlank()) "No saved chats yet" else "Nothing found", "Every conversation is saved here automatically. Start a new one with +.")
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rows) { row ->
                        when (row) {
                            is HistoryRow.Day -> MonoLabel(row.label, Modifier.padding(top = 10.dp, bottom = 2.dp))
                            is HistoryRow.Chat -> {
                                val c = row.c
                                Box(
                                    Modifier.fillMaxWidth().clickable { onOpen(c.id) }
                                        .pixelGlass(RoundedCornerShape(14.dp), colors.surface.copy(alpha = 0.94f), strong = c.id == current)
                                ) {
                                    Row(Modifier.padding(start = 14.dp, top = 4.dp, bottom = 4.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (c.id == current) {
                                            Box(Modifier.size(7.dp).background(colors.primary, CircleShape))
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (c.id in liveIds) Text("Live", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = colors.background, modifier = Modifier.padding(end = 6.dp).background(colors.onSurface, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp))
                                                Text(c.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
                                            }
                                            MonoLabel(Instant.ofEpochMilli(c.updatedAt).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())))
                                        }
                                        IconButton(onClick = { toDelete = c }) { Icon(Icons.Rounded.Delete, "Delete", tint = colors.onSurfaceVariant) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    toDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete this chat?") },
            text = { Text("“${c.title}” and all its messages will be removed for good.") },
            confirmButton = { TextButton(onClick = { vm.deleteChat(c.id); toDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Keep") } }
        )
    }
}
