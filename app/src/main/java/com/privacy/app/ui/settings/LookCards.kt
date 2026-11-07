package com.privacy.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privacy.app.ui.MainViewModel
import com.privacy.app.ui.SoftCard
import com.privacy.app.ui.theme.AppFonts

// ============================ You + the assistant's look ============================
@Composable
internal fun ProfileCard(vm: MainViewModel) {
    val s = vm.g.settings
    val myName by s.userName.collectAsStateWithLifecycle()
    val myGender by s.userGender.collectAsStateWithLifecycle()
    val call by s.userCall.collectAsStateWithLifecycle()
    val aName by s.assistantName.collectAsStateWithLifecycle()
    val aGender by s.assistantGender.collectAsStateWithLifecycle()
    SoftCard {
        CardTitle(Icons.Rounded.Face, "You & your assistant", "Your name and gender decide how it greets you ($call). The assistant's gender decides its pixel figure and voice.")
        OutlinedTextField(myName, s::setUserName, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("male" to "I'm male · Sir", "female" to "I'm female · Ma'am").forEach { (v, l) ->
                FilterChip(selected = myGender == v, onClick = { s.setUserGender(v) }, label = { Text(l) })
            }
        }
        OutlinedTextField(aName, s::setAssistantName, label = { Text("Assistant name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Assistant figure & voice", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("auto" to "Follow the name", "male" to "Male", "female" to "Female").forEach { (v, l) ->
                FilterChip(selected = aGender == v, onClick = { s.setAssistantGender(v) }, label = { Text(l) })
            }
        }
    }
}

// ============================ Fonts ============================
@Composable
internal fun FontCard(vm: MainViewModel) {
    val key by vm.g.settings.fontKey.collectAsStateWithLifecycle()
    SoftCard {
        CardTitle(Icons.Rounded.TextFields, "Font", "Applies to the whole app straight away. Fonts are built in, so they work offline.")
        AppFonts.all.forEach { f ->
            val on = f.key == key
            Row(
                Modifier.fillMaxWidth().clickable { vm.g.settings.setFontKey(f.key) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(f.label, fontFamily = f.family, fontSize = 18.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                    Text(f.vibe, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                androidx.compose.material3.RadioButton(selected = on, onClick = { vm.g.settings.setFontKey(f.key) })
            }
        }
        Text("The quick brown fox jumps over the lazy dog · 0123456789", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ============================ Splash + companion ============================
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun CompanionCard(vm: MainViewModel) {
    val s = vm.g.settings
    val splash by s.splashOn.collectAsStateWithLifecycle()
    val on by s.companionOn.collectAsStateWithLifecycle()
    val where by s.companionWhere.collectAsStateWithLifecycle()
    val side by s.companionSide.collectAsStateWithLifecycle()
    SoftCard {
        CardTitle(Icons.Rounded.AutoAwesome, "Companion", "A small pixel assistant that watches the screen with you and reacts with looks, gestures and comments.")
        SwitchRow("Greeting screen when the app opens", splash) { s.setSplashOn(it) }
        SwitchRow("Show the small companion", on) { s.setCompanionOn(it) }
        val eff by s.effBarOn.collectAsStateWithLifecycle()
        SwitchRow("Today's efficiency bar in chat", eff) { s.setEffBarOn(it) }
        if (on) {
            Text("Where it appears", style = MaterialTheme.typography.titleSmall)
            val places = listOf("chat" to "Chat", "apis" to "API & Subscription", "tasks" to "Tasks", "notes" to "Notes", "tools" to "Tools", "settings" to "Settings pages")
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                places.forEach { (k, l) -> FilterChip(selected = k in where, onClick = { s.setCompanionWhere(k, k !in where) }, label = { Text(l) }) }
            }
            Text("Side", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("left" to "Left", "right" to "Right").forEach { (v, l) -> FilterChip(selected = side == v, onClick = { s.setCompanionSide(v) }, label = { Text(l) }) }
            }
            Text("Drag it anywhere. Drop it past the screen edge to hide it (tap the small tab to bring it back). In chat, tap it to open the full-screen assistant; on Tasks, Notes and Tools tap it for a suggestion.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ============================ Light / dark ============================
@Composable
internal fun ThemeCard(vm: MainViewModel) {
    val s = vm.g.settings
    val mode by s.themeMode.collectAsStateWithLifecycle()
    SoftCard {
        CardTitle(Icons.Rounded.AutoAwesome, "Appearance", "Dark is the default. Light is easier in bright sunlight. The whole app follows your choice.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("dark" to "Dark", "light" to "Light", "system" to "Follow phone").forEach { (k, l) ->
                FilterChip(selected = mode == k, onClick = { s.setThemeMode(k) }, label = { Text(l) })
            }
        }
    }
}

// ============================ Assistant starts chats ============================
@Composable
internal fun ProactiveCard(vm: MainViewModel) {
    val s = vm.g.settings
    val on by s.proactiveOn.collectAsStateWithLifecycle()
    val notify by s.proactiveNotify.collectAsStateWithLifecycle()
    val hours by s.proactiveIdleHours.collectAsStateWithLifecycle()
    SoftCard {
        CardTitle(Icons.Rounded.Notifications, "Assistant starts chats", "It can message you first: when you left things unfinished, finished tasks, or have been away a while. Never while you are talking to it.")
        SwitchRow("Let the assistant start conversations", on) { s.setProactiveOn(it) }
        if (on) {
            SwitchRow("Also send a notification", notify) { s.setProactiveNotify(it) }
            Text("After being away for $hours h", style = MaterialTheme.typography.titleSmall)
            Slider(value = hours.toFloat(), onValueChange = { s.setProactiveIdleHours(it.toInt().coerceIn(2, 24)) }, valueRange = 2f..24f, steps = 21)
            Text("At most a few messages a day, none late at night. The text comes from your own tasks, so it uses little or no API.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun AnimationCard(vm: MainViewModel) {
    val on by vm.g.settings.animationsOn.collectAsStateWithLifecycle()
    SoftCard {
        CardTitle(Icons.Rounded.AutoAwesome, "Animations", "Pixel pulses, glowing bars, page swipes and the set-up animation. Turn them off for a calmer screen and a little less battery use.")
        var changed by remember { mutableStateOf(false) }
        SwitchRow("Show animations", on) { vm.g.settings.setAnimationsOn(it); changed = true }
        if (changed) Text("Restart the app to apply this everywhere.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SettingsPage(vm: MainViewModel, onBack: () -> Unit) = SettingsPageOf("Settings", "Look · companion · animations · permissions · voice", onBack) {
    card(0) { ThemeCard(vm) }
    card(1) { FontCard(vm) }
    card(2) { AnimationCard(vm) }
    card(3) { CompanionCard(vm) }
    card(4) { ProactiveCard(vm) }
    card(5) { FeaturesCard(vm) }
    card(6) { PermissionsCard() }
    card(7) { SpeechCard(vm) }
    card(8) { VoiceCard(vm) }
}
