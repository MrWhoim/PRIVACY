package com.privacy.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import com.privacy.app.AppInfo
import com.privacy.app.ui.MonoLabel
import com.privacy.app.ui.PixelMark
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privacy.app.ai.LocalCommands
import com.privacy.app.ui.Appear
import com.privacy.app.ui.MainViewModel
import com.privacy.app.ui.ScreenBackground
import com.privacy.app.ui.ScreenHeader
import com.privacy.app.ui.SoftCard
import com.privacy.app.ui.theme.Mono

/** One settings category = one page. Cards slide in one after another. */
@Composable
internal fun SettingsPageOf(title: String, subtitle: String, onBack: () -> Unit, content: LazyListScope.() -> Unit) {
    ScreenBackground {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(title, subtitle, onBack = onBack)
            LazyColumn(
                contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content
            )
        }
    }
}

internal fun LazyListScope.card(index: Int, body: @Composable () -> Unit) {
    item { Appear(delayMs = index * 70) { body() } }
}

@Composable
fun AutomationsPage(vm: MainViewModel, onBack: () -> Unit) = SettingsPageOf("Automations", "Reminders · automations · morning brief", onBack) {
    card(0) { AutomationsCard(vm) }
    card(1) { MorningCard(vm) }
}

@Composable
fun PermissionsPage(vm: MainViewModel, onBack: () -> Unit) = SettingsPageOf("Permissions & voice", "What the assistant may do", onBack) {
    card(0) { PermissionsCard() }
    card(1) { SpeechCard(vm) }
    card(2) { VoiceCard(vm) }
}

@Composable
fun ExportPage(vm: MainViewModel, onBack: () -> Unit) = SettingsPageOf("Export & backup", "Encrypted file you keep", onBack) {
    card(0) { BackupCard(vm) }
}

@Composable
fun AboutPage(onBack: () -> Unit) = SettingsPageOf("About & privacy", "${AppInfo.NAME} · v${AppInfo.VERSION}", onBack) {
    card(0) {
        SoftCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.privacy.app.ui.BrandLogo(64.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(AppInfo.NAME, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = 2.sp)
                    MonoLabel("Version ${AppInfo.VERSION}")
                }
            }
            Text(AppInfo.FULL_FORM, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            AppInfo.ACRONYM.forEach { (letter, meaning) ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(letter, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.width(26.dp))
                    Text(meaning, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
                }
            }
        }
    }
    card(1) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SoftCard {
                Text("What is this app?", style = MaterialTheme.typography.titleLarge)
                Text(AppInfo.OVERVIEW, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 19.sp)
            }
            Fold("What it does") {
                val c = MaterialTheme.colorScheme.onSurfaceVariant
                AppInfo.FEATURES.forEach { (t, d) ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(t, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(d, fontSize = 13.sp, color = c, lineHeight = 19.sp)
                    }
                }
                Text("Type / in the chat box to see every command – they work with no internet.", fontSize = 12.sp, color = c)
            }
            val gctx = androidx.compose.ui.platform.LocalContext.current
            Fold("How the app changed", "Every update with its date – the version stays 1.0.0.") {
                com.privacy.app.ui.chat.MarkdownText(com.privacy.app.AppGuide.section(gctx, "Timeline"), MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            Fold("AI tokens & cost", "What each task uses and what a paid key costs.") {
                com.privacy.app.ui.chat.MarkdownText(com.privacy.app.AppGuide.section(gctx, "Token system"), MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        }
    }
    card(2) {
        Fold("Commands", "Type / in the chat box and tap one. These work offline.") {
            LocalCommands.catalog.forEach { (cmd, desc) ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(cmd, fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.width(96.dp))
                    Text(desc, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("Anything else is sent to your AI (needs internet and an API key).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    card(3) { WidgetCard() }
    card(4) {
        Fold("Writing notes in Markdown", "Notes understand this simple Markdown. The assistant follows the same rules when it saves a note for you.") {
            com.privacy.app.ai.NoteRules.GUIDE.forEach { (syntax, meaning) ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(syntax, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.width(138.dp))
                    Text(meaning, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("Tags", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
            Text(
                "A note the app wrote (not you) starts with a tag in square brackets, so you always know where it came from:",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            com.privacy.app.ai.NoteRules.TAGS.forEach { (tag, meaning) ->
                Row(verticalAlignment = Alignment.Top) {
                    Text("[$tag]", fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(138.dp))
                    Text(meaning, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("Normal chats are NOT saved as notes. They stay in the chat, until you say “save it” or “add this to notes”.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    card(5) { PrivacyCard() }
    card(6) {
        Fold("How the day is scored") {
            val dim = MaterialTheme.colorScheme.onSurfaceVariant
            Text("Each task has a weight: Difficulty × Priority × Type (max 6.5).", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("• Difficulty: Easy 1 · Normal 2 · Hard 3.5\n• Priority: Low 0.8 · Normal 1 · High 1.3\n• Type: due-date / one-time 1.4 · daily 0.6 · weekly 1", fontSize = 13.sp, color = dim)
            Text("Credit: Not started 0 % · In progress 50 % · Done 100 %. Up to 85 points come from tasks (your credited weight against the planned weight, at least 8 units a day). Unfinished tasks always stay in the plan.", fontSize = 13.sp, color = dim)
            Text("Future tasks (due on a later day) carry no weight in the day's score. Marking one In progress gives 1 commitment point; finishing it 2 or more days before its due date gives 2. A task with neither repeat nor date is due today. Past days can be edited – their report is recalculated.", fontSize = 13.sp, color = dim)
            Text("Commitment points – up to 15", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("Four stars share these 15 points (3.75 each):\n• Tough Commitment: a star you give yourself only when you did something truly great against your own laziness (normally 0–2, a third or fourth only on an exceptional day).\n• Consistency: earned by itself – 1 star after 3 confirmed days in a row with at least 60 % done, 2 stars after 7.", fontSize = 13.sp, color = dim)
            Text("A day with no tasks is N/A, not 100 %. The score always follows your tasks as they are right now.", fontSize = 13.sp, color = dim)
            Text("Health score = 35 % Nutrition + 25 % Activity + 20 % Sleep + 20 % Water. Fat, carbs, protein and calories are tracked in Nutrition.", fontSize = 13.sp, color = dim)
            Text("Mind – Focus and Distraction", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("Focus % (shown as “Detox”) = 100 × (30 + P) ÷ (60 + P + D) and Distraction % (“Dopamine”) = 100 × (30 + D) ÷ (60 + P + D). Together they are always 100 %. “Not enough data” is shown when nothing was recorded.", fontSize = 13.sp, color = dim)
            Text("P = minutes of useful time × weight: focused work or a finished task 1.0, learning 0.9, exercise 1.2, useful personal task 0.7. Time in the first two hours after waking counts ×1.25. Each finished task adds 5 points (max 15 a day). A finished task counts about 15 / 30 / 60 minutes for Easy / Normal / Hard.", fontSize = 13.sp, color = dim)
            Text("D = minutes in a distracting app × category (short videos 1.2, social media 1.0, entertainment video 0.8, games 0.9) × how worthwhile you rated it (somewhat relevant 0.45, low value 0.75, mindless 1.0; unrated = mindless). If you rate it “Useful” (YouTube for study…), that time counts as learning time in P instead of costing dopamine × timing (first two hours after waking 1.5, normal 1.0, within 30 minutes before a nap 0.45, last 90 minutes before bedtime 0.65).", fontSize = 13.sp, color = dim)
            Text("The wake-up time comes from your Sleep entry (or the time you set in Mind); bedtime from your usual sleep. Every app is tracked: known distracting apps and games count in D, known useful apps (Duolingo, Coursera, fitness and productivity apps…) count in P, and any other app gives no credit until you tap it and decide (Distracting, Useful or Ignore). App time needs “Usage access” in Android settings and stays on the phone; you can also add time by hand.", fontSize = 13.sp, color = dim)
            Text("Overall score in Reports and in the chat bar = (Tasks + Health + Focus) ÷ 3. If there is not enough Focus data the other two are averaged.", fontSize = 13.sp, color = dim)
        }
    }
    card(7) {
        val uri = LocalUriHandler.current
        SoftCard {
            CardTitle(Icons.Rounded.Apps, "Made by ${AppInfo.CREATOR}")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { runCatching { uri.openUri(AppInfo.GITHUB_URL) } }) { Text(AppInfo.GITHUB, fontSize = 12.sp) }
                OutlinedButton(onClick = { runCatching { uri.openUri(AppInfo.X_URL) } }) { Text(AppInfo.X_HANDLE, fontSize = 12.sp) }
            }
        }
    }
}
