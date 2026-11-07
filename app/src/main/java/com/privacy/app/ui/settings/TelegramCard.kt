package com.privacy.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privacy.app.ai.TelegramHub
import com.privacy.app.ui.MainViewModel
import com.privacy.app.ui.SoftCard
import kotlinx.coroutines.launch

/** Account → Telegram: bot token, friend's bot, requests. Only bot-to-bot app sync; no messages to any person. */
@Composable
fun TelegramCard(vm: MainViewModel) {
    val g = vm.g
    val st = g.settings
    val token by st.telegramToken.collectAsStateWithLifecycle()
    val connected by st.telegramConnected.collectAsStateWithLifecycle()
    var tokenIn by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var help by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val tp = com.privacy.app.ai.TelegramPeers
    tp.rev.collectAsStateWithLifecycle().value
    val peers = tp.peers(g)
    val myBot = tp.myBotSaved(g)
    var pBot by remember { mutableStateOf("") }
    LaunchedEffect(token) {
        if (token.isNotBlank() && tp.myBotSaved(g).isBlank()) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { tp.myBotName(g) }
    }
    SoftCard {
        CardTitle(Icons.Rounded.Send, "Telegram", "Connect your app to a friend's app through two Telegram bots. Share notes and AI chats that stay in sync. Nothing goes through a server, and nothing is sent to any person's chat.")
        OutlinedButton(onClick = { help = !help }) { Text(if (help) "Hide instructions" else "How to create the bot and enable Bot-to-Bot") }
        if (help) {
            Text(
                "Do this once, in Telegram:\n" +
                    "1. Open @BotFather and send /newbot. Choose a name and a username ending in “bot”.\n" +
                    "2. BotFather answers with a token (like 123456:ABC…). Paste it in “Bot token” below and tap Save & connect.\n" +
                    "3. Open your new bot and press Start once.\n" +
                    "4. Back in @BotFather send /mybots → pick your bot → Bot Settings → turn on “Bot-to-Bot Communication Mode”.\n" +
                    "5. Your friend does the same with his own bot. Both bots must have the mode ON.\n" +
                    "6. Type your friend's bot name (like @friend_bot) below and send the request. He accepts it in his app, and his name appears here by itself.",
                fontSize = 12.sp
            )
            OutlinedButton(onClick = {
                runCatching { (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("cmd", "/newbot")) }
                android.widget.Toast.makeText(ctx, "“/newbot” copied – paste it in the chat and send", android.widget.Toast.LENGTH_LONG).show()
                val tg = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("tg://resolve?domain=BotFather&start=newbot")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(tg) }.onFailure {
                    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/BotFather")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }) { Text("Open @BotFather (/newbot)") }
        }
        OutlinedTextField(
            value = tokenIn, onValueChange = { tokenIn = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text(if (token.isBlank()) "Bot token" else "Bot token (saved – paste to replace)") },
            visualTransformation = PasswordVisualTransformation()
        )
        Button(onClick = {
            scope.launch {
                if (tokenIn.isNotBlank()) { st.setTelegramToken(tokenIn); tokenIn = "" }
                val t = st.telegramToken.value
                if (t.isBlank()) { status = "Paste the bot token first."; return@launch }
                val who = TelegramHub.check(t)
                if (who.startsWith("@")) { tp.rememberMyBot(g, who); TelegramHub.start(g); status = "" } else status = who
            }
        }) { Text("Save & connect") }
        Text(
            if (myBot.isNotBlank()) "Your bot: @$myBot · " + (if (connected) "connected ✅" else "saved") else if (token.isBlank()) "No bot yet." else "Looking up your bot…",
            fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
        )
        if (status.isNotBlank()) Text(status, fontSize = 12.sp)

        OutlinedTextField(value = pBot, onValueChange = { pBot = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Friend's bot, e.g. @friend_bot") })
        Button(enabled = token.isNotBlank() && pBot.isNotBlank(), onClick = { status = tp.pair(g, pBot); pBot = "" }) { Text("Send request") }
        if (tp.status.isNotBlank()) Text(tp.status, fontSize = 12.sp)

        peers.forEach { p ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text((p.name.ifBlank { "@" + p.bot }) + "  ·  @${p.bot}", fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text(when (p.state) { "ok" -> "Connected ✅ (name synced from his app)"; "out" -> "Request sent – waiting for him to accept"; else -> "Wants to connect with you" }, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (p.state == "in") Button(onClick = { tp.accept(g, p.bot, true) }) { Text("Accept") }
                    if (p.state == "in") OutlinedButton(onClick = { tp.accept(g, p.bot, false) }) { Text("Reject") }
                    if (p.state != "in") TextButton(onClick = { tp.remove(g, p.bot) }) { Text(if (p.state == "ok") "Disconnect" else "Cancel request") }
                }
            }
        }
        if (peers.any { it.state == "in" }) OutlinedButton(onClick = { tp.reshowRequests() }) { Text("Show the request pop-up") }
        Text("There is no server: the bot only works while this app is active in the background. Telegram itself need not stay open.", fontSize = 11.sp)
    }
}
