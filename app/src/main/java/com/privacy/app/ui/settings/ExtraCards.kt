@file:OptIn(ExperimentalMaterial3Api::class)

package com.privacy.app.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privacy.app.automation.AutomationScheduler
import com.privacy.app.automation.nextRunText
import com.privacy.app.data.AutoAction
import com.privacy.app.ui.MainViewModel
import com.privacy.app.ui.MonoLabel
import com.privacy.app.ui.SoftCard
import com.privacy.app.ui.theme.SuccessGreen
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

// ============================ "Me" entry ============================
@Composable
internal fun MeEntryCard(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    androidx.compose.foundation.layout.Box(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        SoftCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.Lock, null, tint = colors.primary)
                Column(Modifier.weight(1f)) {
                    Text("Me · private", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Behind your passcode: your profile & goals, the Assistant settings and memories, encrypted on this phone.",
                        fontSize = 13.sp, color = colors.onSurfaceVariant
                    )
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = colors.onSurfaceVariant)
            }
        }
    }
}

// ============================ Permissions ============================
private fun Context.openSafely(i: Intent) {
    try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
}

private fun Context.appDetails() = openSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
private fun Context.notificationSettings() =
    openSafely(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))

@Composable
internal fun PermissionsCard() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        tick++
        if (!ok) ctx.notificationSettings()
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        tick++
        if (!ok) ctx.appDetails()
    }

    val notifOn = remember(tick) { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }
    val micOn = remember(tick) { ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }
    val exactOn = remember(tick) { AutomationScheduler.canExact(ctx) }
    val batteryOn = remember(tick) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }

    SoftCard {
        CardTitle(
            Icons.Rounded.Security, "Permissions",
            "Switch on what you want the assistant to be able to do. Nothing is used until you allow it."
        )
        PermRow("Notifications", "Reminders, your task list at a set time, automation results.", notifOn) {
            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else ctx.notificationSettings()
        }
        PermRow("Microphone", "Voice in chat, the talking face and the widget.", micOn) { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }
        PermRow("Exact alarms", "So a 7:00 PM reminder arrives at 7:00, not a few minutes late.", exactOn) {
            if (Build.VERSION.SDK_INT >= 31) ctx.openSafely(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
        }
        PermRow("Run in background", "Stops the phone's battery saver from delaying automations.", batteryOn) {
            try {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                ctx.openSafely(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        Text(
            "Files & storage: no permission needed. Backups and exports use Android's own file picker, so this app never gets access to the rest of your storage.\n\n" +
                "Opening apps, links, alarms, timers and the flashlight need no permission either – Android only allows opening other apps while the app is on screen.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PermRow(title: String, description: String, granted: Boolean, onAllow: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, fontSize = 12.sp, color = colors.onSurfaceVariant)
        }
        if (granted) MonoLabel("On", color = SuccessGreen) else OutlinedButton(onClick = onAllow) { Text("Allow") }
    }
}

// ============================ Automations ============================
@Composable
internal fun AutomationsCard(vm: MainViewModel) {
    val list by vm.automations.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<com.privacy.app.data.AutomationEntity?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val colors = MaterialTheme.colorScheme

    SoftCard {
        CardTitle(
            Icons.Rounded.Bolt, "Automations",
            "Things the assistant does by itself at a set time. Just tell it, e.g. “remind me my tasks at 7 PM” or “every morning at 8 research AI news and save a note”."
        )
        if (list.isEmpty()) Text("None yet.", fontSize = 13.sp, color = colors.onSurfaceVariant)
        list.forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.name, style = MaterialTheme.typography.titleSmall)
                    MonoLabel(AutomationScheduler.describe(a))
                    if (a.enabled) a.nextRunText()?.let { MonoLabel("next · $it", color = colors.primary) }
                }
                IconButton(onClick = { editing = a }) { Icon(Icons.Rounded.Edit, "Edit", tint = colors.onSurfaceVariant) }
                IconButton(onClick = { vm.runAutomationNow(a) { message = it } }) { Icon(Icons.Rounded.PlayArrow, "Run now", tint = colors.primary) }
                Switch(checked = a.enabled, onCheckedChange = { vm.setAutomationEnabled(a, it) })
                IconButton(onClick = { vm.deleteAutomation(a.id) }) { Icon(Icons.Rounded.Close, "Delete", tint = colors.onSurfaceVariant) }
            }
        }
        message?.let { Text(it, fontSize = 13.sp, color = colors.primary) }
        OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) { Text("Add automation") }
    }

    if (showAdd || editing != null) {
        val ed = editing
        var name by remember { mutableStateOf(ed?.name ?: "") }
        var action by remember { mutableStateOf(ed?.let { runCatching { AutoAction.valueOf(it.action) }.getOrNull() } ?: AutoAction.NOTIFY_TASKS) }
        var audioToo by remember { mutableStateOf(ed?.message?.startsWith(AutomationScheduler.AUDIO_TAG) == true) }
        var text by remember { mutableStateOf(ed?.message?.removePrefix(AutomationScheduler.AUDIO_TAG)?.trim() ?: "") }
        var minutes by remember { mutableIntStateOf(ed?.timeMinutes ?: (19 * 60)) }
        var days by remember { mutableIntStateOf(ed?.daysMask ?: 127) }
        var dateDay by remember { mutableStateOf(ed?.dateDay) }
        var showDate by remember { mutableStateOf(false) }
        var showTime by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showAdd = false; editing = null },
            title = { Text(if (ed != null) "Edit automation" else "New automation") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = action == AutoAction.NOTIFY_TASKS, onClick = { action = AutoAction.NOTIFY_TASKS }, label = { Text("Task list") })
                        FilterChip(selected = action == AutoAction.NOTIFY_TEXT, onClick = { action = AutoAction.NOTIFY_TEXT }, label = { Text("Reminder") })
                        FilterChip(selected = action == AutoAction.AI_PROMPT, onClick = { action = AutoAction.AI_PROMPT }, label = { Text("AI task") })
                    }
                    if (action != AutoAction.NOTIFY_TASKS) {
                        OutlinedTextField(
                            value = text, onValueChange = { text = it }, minLines = 2, modifier = Modifier.fillMaxWidth(),
                            label = { Text(when (action) { AutoAction.AI_PROMPT -> "What should the AI do? (it can use the web)."; AutoAction.NOTES_AUDIO -> "Which notes? A word from the title (empty = notes written since the last run)"; else -> "Reminder text" }) }
                        )
                    }
                    if (action == AutoAction.AI_PROMPT) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Also create audio", fontSize = 14.sp)
                                Text(if (audioToo) "An audio player is added to the note." else "Off: no sound is made.", fontSize = 12.sp, color = colors.onSurfaceVariant)
                            }
                            Switch(checked = audioToo, onCheckedChange = { audioToo = it })
                        }
                    }
                    OutlinedButton(onClick = { showTime = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Time: " + LocalTime.of(minutes / 60, minutes % 60).format(DateTimeFormatter.ofPattern("hh:mm a")))
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = dateDay == null, onClick = { dateDay = null; if (days == 0) days = 127 }, label = { Text("Repeats weekly") })
                        FilterChip(
                            selected = dateDay != null, onClick = { showDate = true },
                            label = { Text(dateDay?.let { LocalDate.ofEpochDay(it).format(DateTimeFormatter.ofPattern("d MMM yyyy")) } ?: "One date") }
                        )
                    }
                    if (dateDay == null) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, l ->
                                val bit = 1 shl i
                                FilterChip(selected = (days and bit) != 0, onClick = { days = days xor bit }, label = { Text(l) })
                            }
                        }
                        Text("Pick the days it repeats on.", fontSize = 12.sp, color = colors.onSurfaceVariant)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = (action == AutoAction.NOTIFY_TASKS || action == AutoAction.NOTES_AUDIO || text.isNotBlank()) && (dateDay != null || (days and 127) != 0),
                    onClick = {
                        val msg = if (action == AutoAction.AI_PROMPT && audioToo) AutomationScheduler.AUDIO_TAG + " " + text.trim() else text
                        val nm = name.ifBlank { if (action == AutoAction.NOTIFY_TASKS) "Your tasks" else "Automation" }
                        if (ed != null) vm.updateAutomation(ed, nm, action, msg, minutes, days, dateDay) else vm.addAutomation(nm, action, msg, minutes, days, dateDay)
                        showAdd = false; editing = null
                    }
                ) { Text(if (ed != null) "Save" else "Add") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false; editing = null }) { Text("Cancel") } }
        )

        if (showDate) {
            val ds = androidx.compose.material3.rememberDatePickerState(
                initialSelectedDateMillis = (dateDay ?: LocalDate.now().toEpochDay()) * 86_400_000L
            )
            androidx.compose.material3.DatePickerDialog(
                onDismissRequest = { showDate = false },
                confirmButton = { TextButton(onClick = { ds.selectedDateMillis?.let { dateDay = it / 86_400_000L }; showDate = false }) { Text("OK") } },
                dismissButton = { TextButton(onClick = { showDate = false }) { Text("Cancel") } }
            ) { androidx.compose.material3.DatePicker(state = ds) }
        }
        if (showTime) {
            val st = rememberTimePickerState(initialHour = minutes / 60, initialMinute = minutes % 60, is24Hour = false)
            AlertDialog(
                onDismissRequest = { showTime = false },
                text = { TimePicker(state = st) },
                confirmButton = { TextButton(onClick = { minutes = st.hour * 60 + st.minute; showTime = false }) { Text("OK") } },
                dismissButton = { TextButton(onClick = { showTime = false }) { Text("Cancel") } }
            )
        }
    }
}

// ============================ Voice ============================
@Composable
internal fun VoiceCard(vm: MainViewModel) {
    val settings = vm.g.settings
    val lang by settings.voiceLang.collectAsStateWithLifecycle()
    SoftCard {
        CardTitle(
            Icons.Rounded.Mic, "Voice",
            "Language used to understand you and to read answers aloud. Leave blank to follow your phone's language."
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("" to "Phone default", "en-IN" to "English (India)", "hi-IN" to "Hindi", "en-US" to "English (US)", "en-GB" to "English (UK)").forEach { (tag, label) ->
                FilterChip(selected = lang == tag, onClick = { settings.setVoiceLang(tag) }, label = { Text(label) })
            }
        }
        val rl by settings.replyLang.collectAsStateWithLifecycle()
        Text("The assistant answers in", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("auto" to "Same as I write", "english" to "English", "hindi" to "Hindi", "hinglish" to "Hinglish").forEach { (k, l) ->
                FilterChip(selected = rl == k, onClick = { settings.setReplyLang(k) }, label = { Text(l) })
            }
        }
        OutlinedTextField(
            value = lang, onValueChange = settings::setVoiceLang, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            label = { Text("Language tag, e.g. en-IN") }
        )
    }
}

// ============================ Speech engines ============================
@Composable
internal fun SpeechCard(vm: MainViewModel) {
    val settings = vm.g.settings
    val stt by settings.useGroqStt.collectAsStateWithLifecycle()
    val tts by settings.useGroqTts.collectAsStateWithLifecycle()
    val voice by settings.ttsVoice.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    SoftCard {
        CardTitle(
            Icons.Rounded.Mic, "Speech quality",
            "Uses your API keys (Groq is free; OpenAI-compatible keys work too). When a quota runs out, the phone's own voice takes over instantly."
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Accurate listening", style = MaterialTheme.typography.titleSmall)
                Text("Groq Whisper: understands accents, Hindi and English mixed.", fontSize = 12.sp, color = colors.onSurfaceVariant)
            }
            Switch(checked = stt, onCheckedChange = { settings.setUseGroqStt(it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Natural voice", style = MaterialTheme.typography.titleSmall)
                Text("Used for every reply until the provider's quota is reached, then the phone's best voice.", fontSize = 12.sp, color = colors.onSurfaceVariant)
            }
            Switch(checked = tts, onCheckedChange = { settings.setUseGroqTts(it) })
        }
        if (tts) {
            Text("Voice: follows your assistant's name and gender by default. Pick one to override.", fontSize = 12.sp, color = colors.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = voice.isBlank(), onClick = { settings.setTtsVoice("") }, label = { Text("Follow name") })
                com.privacy.app.voice.GroqSpeech.VOICES.forEach { v ->
                    FilterChip(selected = voice == v, onClick = { settings.setTtsVoice(v) }, label = { Text(v.replaceFirstChar { it.uppercase() }) })
                }
            }
            Text(
                "Groq keys use the Orpheus model (${com.privacy.app.voice.GroqSpeech.TTS_MODEL}, English only). OpenAI or Custom (OpenAI-compatible) keys use the tts-1 model unless you set another model name – these also speak other languages. " +
                    "The free Groq plan allows about 100 voice requests a day; after that the next key or the phone's own voice speaks instead.",
                fontSize = 12.sp, color = colors.onSurfaceVariant
            )
        }
        if (tts) {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val speaker = remember { com.privacy.app.voice.Speaker(vm.g) }
            androidx.compose.runtime.DisposableEffect(Unit) { onDispose { speaker.shutdown() } }
            var report by remember { mutableStateOf<String?>(null) }
            var testing by remember { mutableStateOf(false) }
            val uri = androidx.compose.ui.platform.LocalUriHandler.current
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(enabled = !testing, onClick = { testing = true; scope.launch { report = speaker.testNatural(); testing = false } }) {
                    Text(if (testing) "Testing…" else "Test voice")
                }
                if (report?.contains("terms", true) == true) {
                    OutlinedButton(onClick = { runCatching { uri.openUri(com.privacy.app.voice.GroqSpeech.TERMS_URL) } }) { Text("Open Groq terms page") }
                }
            }
            report?.let { Text(it, fontSize = 12.sp, color = colors.onSurfaceVariant) }
        }
        Text(
            "Tip for the phone voice: install Google Speech Services and download a high-quality voice in Android Settings → Text-to-speech.",
            fontSize = 12.sp, color = colors.onSurfaceVariant
        )
    }
}

// ============================ Photo reading ============================
@Composable
internal fun VisionCard(vm: MainViewModel) {
    val settings = vm.g.settings
    val model by settings.visionModel.collectAsStateWithLifecycle()
    SoftCard {
        CardTitle(
            Icons.Rounded.Security, "Reading photos",
            "Tap + in chat to add a photo, then choose Analyse (describes the picture and answers what you type) or Read text (pulls out handwriting, tables, notes). Analysis needs a vision-capable model under Models (or entered below). Order tried: Gemini key → Groq Llama 4 Maverick → Llama 4 Scout."
        )
        OutlinedTextField(
            value = model, onValueChange = settings::setVisionModel, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("Model override (optional)") }, placeholder = { Text("e.g. gemini-2.5-flash") }
        )
        val ocr by settings.ocrModel.collectAsStateWithLifecycle()
        OutlinedTextField(
            value = ocr, onValueChange = settings::setOcrModel, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("OCR model override – “Read text” only (optional)") }, placeholder = { Text("e.g. gemini-2.5-flash") }
        )
    }
}

// ============================ Voice models ============================
@Composable
internal fun VoiceModelsCard(vm: MainViewModel) {
    val settings = vm.g.settings
    val tts by settings.ttsModel.collectAsStateWithLifecycle()
    val stt by settings.sttModel.collectAsStateWithLifecycle()
    SoftCard {
        CardTitle(Icons.Rounded.Security, "Voice models", "Used with a Groq key. Leave empty for the built-in choice.")
        OutlinedTextField(
            value = tts, onValueChange = settings::setTtsModel, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("Speaking voice – model override (optional)") }, placeholder = { Text("canopylabs/orpheus-v1-english") }
        )
        OutlinedTextField(
            value = stt, onValueChange = settings::setSttModel, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("Listening / detecting – model override (optional)") }, placeholder = { Text("whisper-large-v3-turbo") }
        )
    }
}


/** Shows the real Android prompt / settings page for a permission by name – used when the assistant asks for one. */
@Composable
fun rememberPermissionRequester(): (String) -> Unit {
    val ctx = LocalContext.current
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (!ok) ctx.notificationSettings() }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (!ok) ctx.appDetails() }
    val multi = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> if (r.values.any { !it }) ctx.appDetails() }
    return { kind ->
        when (kind) {
            "phone" -> multi.launch(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS))
            "music" -> multi.launch(arrayOf(com.privacy.app.media.MusicPlayer.permissionName))
            "files" -> if (Build.VERSION.SDK_INT >= 30) ctx.openSafely(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            "overlay" -> ctx.openSafely(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            "notifications" -> if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS) else ctx.notificationSettings()
            "microphone" -> mic.launch(Manifest.permission.RECORD_AUDIO)
            "exact_alarms" -> if (Build.VERSION.SDK_INT >= 31) ctx.openSafely(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
            "battery" -> try {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                ctx.openSafely(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }
}
