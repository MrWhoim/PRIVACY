@file:OptIn(ExperimentalMaterial3Api::class)

package com.privacy.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.ui.text.font.FontFamily
import com.privacy.app.settings.ModelTier
import com.privacy.app.ui.vault.VaultScreen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privacy.app.security.ExportOptions
import com.privacy.app.settings.ApiKey
import com.privacy.app.settings.Provider
import com.privacy.app.ui.MainViewModel
import com.privacy.app.ui.ScreenBackground
import com.privacy.app.ui.ScreenHeader
import com.privacy.app.ui.SoftCard
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable
internal fun CardTitle(icon: ImageVector, title: String, subtitle: String? = null) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (subtitle != null) Text(subtitle, fontSize = 13.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant)
    }
}

/** A card whose body opens and closes with an arrow (closed by default). */
@Composable
internal fun Fold(title: String, subtitle: String? = null, startOpen: Boolean = false, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    var open by androidx.compose.runtime.saveable.rememberSaveable(title) { mutableStateOf(startOpen) }
    val colors = MaterialTheme.colorScheme
    SoftCard {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                if (subtitle != null && open) Text(subtitle, fontSize = 13.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant)
            }
            Icon(if (open) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, if (open) "Collapse" else "Expand", tint = colors.onSurfaceVariant)
        }
        if (open) content()
    }
}

// ============================ API keys ============================
@Composable
internal fun KeysCard(vm: MainViewModel) {
    val settings = vm.g.settings
    val keys by settings.keys.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf<String?>(null) }
    val colors = MaterialTheme.colorScheme

    SoftCard {
        CardTitle(Icons.Rounded.Key, "AI connections",
            "Add as many free keys as you want. When one hits its limit the next one takes over automatically.")
        if (keys.isEmpty()) Text("No keys yet – the assistant works with offline commands only.", fontSize = 14.sp, color = colors.onSurfaceVariant)
        keys.forEach { k ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(k.label, fontWeight = FontWeight.Medium)
                    Text("${k.provider.label} · ${k.masked()}", fontSize = 12.sp, color = colors.onSurfaceVariant)
                }
                if (testing == k.id) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else IconButton(onClick = {
                    scope.launch {
                        testing = k.id
                        message = try { "✓ ${k.label} works (${vm.listModels(k).size} models available)" } catch (e: Exception) { "✗ ${k.label}: ${e.message}" }
                        testing = null
                    }
                }) { Icon(Icons.Rounded.Bolt, "Test key", tint = colors.primary) }
                Switch(checked = k.enabled, onCheckedChange = { on -> settings.updateKey(k.id) { it.copy(enabled = on) } })
                IconButton(onClick = { settings.removeKey(k.id) }) { Icon(Icons.Rounded.Delete, "Remove", tint = colors.onSurfaceVariant) }
            }
        }
        if (keys.any { it.isTrial }) Text(
            "Keys marked “Trial” come pre-installed so you can try the app right away. They are shared by every new user, so their quota can run out at any time. " +
                "For a smoother experience please add your own keys below. Questions? Write to 2005vishalstorage@gmail.com",
            fontSize = 12.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant
        )
        message?.let { Text(it, fontSize = 13.sp, color = colors.onSurface) }
        Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) { Text("Add API key") }
        Text(
            "Free keys: console.groq.com · build.nvidia.com · openrouter.ai · aistudio.google.com. " +
                "Keys are encrypted with the Android Keystore and never leave this phone except to talk to that provider.",
            fontSize = 12.sp, color = colors.onSurfaceVariant
        )
    }

    if (showAdd) {
        var provider by remember { mutableStateOf(Provider.GROQ) }
        var label by remember { mutableStateOf("") }
        var key by remember { mutableStateOf("") }
        var base by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add API key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Provider.values().forEach { p -> FilterChip(selected = provider == p, onClick = { provider = p }, label = { Text(p.label) }) }
                    }
                    OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Label (e.g. Groq account 2)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = key, onValueChange = { key = it }, label = { Text("API key") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (provider == Provider.CUSTOM) {
                        OutlinedTextField(value = base, onValueChange = { base = it }, label = { Text("Base URL (…/v1)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = key.isNotBlank() && (provider != Provider.CUSTOM || base.isNotBlank()),
                    onClick = {
                        settings.addKey(ApiKey(UUID.randomUUID().toString(), provider, label.ifBlank { "${provider.label} key" }, key.trim(), true, base.ifBlank { null }))
                        showAdd = false
                    }
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } }
        )
    }
}

// ============================ Models ============================
@Composable
internal fun ModelsCard(vm: MainViewModel) {
    val settings = vm.g.settings
    val routes by settings.routes.collectAsStateWithLifecycle()
    val keys by settings.keys.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    SoftCard {
        CardTitle(Icons.Rounded.Tune, "Models (tried top to bottom)",
            "FAST models answer chat and do the legwork in Agent mode. BIG models (e.g. DeepSeek) plan and write the final answer in Agent mode. If one fails or is rate-limited, the next takes over.")
        routes.forEachIndexed { i, r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.model, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    val hasKey = keys.any { it.enabled && it.provider == r.provider }
                    Text(
                        r.provider.label + (if (!hasKey) " · no key" else "") + (if (!r.supportsTools) " · text-mode tools" else ""),
                        fontSize = 12.sp, color = if (hasKey) colors.onSurfaceVariant else colors.error
                    )
                    Text(
                        "${r.tier.label} model · tap to change", fontSize = 12.sp,
                        color = if (r.tier == ModelTier.BIG) colors.onSurface else colors.onSurfaceVariant,
                        fontWeight = if (r.tier == ModelTier.BIG) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.clickable { settings.updateRoute(r.id) { it.copy(tier = if (it.tier == ModelTier.BIG) ModelTier.FAST else ModelTier.BIG) } }.padding(vertical = 6.dp)
                    )
                }
                IconButton(onClick = { settings.moveRoute(r.id, -1) }, enabled = i > 0) { Icon(Icons.Rounded.KeyboardArrowUp, "Move up") }
                IconButton(onClick = { settings.moveRoute(r.id, 1) }, enabled = i < routes.lastIndex) { Icon(Icons.Rounded.KeyboardArrowDown, "Move down") }
                Switch(checked = r.enabled, onCheckedChange = { on -> settings.updateRoute(r.id) { it.copy(enabled = on) } })
                IconButton(onClick = { settings.removeRoute(r.id) }) { Icon(Icons.Rounded.Close, "Remove", tint = colors.onSurfaceVariant) }
            }
        }
        OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) { Text("Add model") }
    }

    if (showAdd) {
        var provider by remember { mutableStateOf(Provider.NVIDIA) }
        var model by remember { mutableStateOf("") }
        var tier by remember { mutableStateOf(ModelTier.FAST) }
        var available by remember { mutableStateOf<List<String>>(emptyList()) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Add model") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Provider.values().forEach { p -> FilterChip(selected = provider == p, onClick = { provider = p; available = emptyList() }, label = { Text(p.label) }) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModelTier.values().forEach { t -> FilterChip(selected = tier == t, onClick = { tier = t }, label = { Text(t.label + " model") }) }
                    }
                    OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Model id, e.g. deepseek-reasoner") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(
                        onClick = {
                            val key = keys.firstOrNull { it.enabled && it.provider == provider }
                            if (key == null) { error = "Add an API key for ${provider.label} first."; return@OutlinedButton }
                            scope.launch {
                                loading = true; error = null
                                try { available = vm.listModels(key) } catch (e: Exception) { error = e.message }
                                loading = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (loading) "Loading…" else "List models available to my key") }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                    val filtered = available.filter { model.isBlank() || it.contains(model, true) }.take(40)
                    if (filtered.isNotEmpty()) {
                        Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                            filtered.forEach { id ->
                                Text(id, fontSize = 14.sp, modifier = Modifier.fillMaxWidth().clickable { model = id }.padding(vertical = 8.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = model.isNotBlank(), onClick = { settings.addRoute(provider, model.trim(), tier); showAdd = false }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } }
        )
    }
}

// ============================ Morning briefing ============================
private fun formatTime(minutes: Int): String = LocalTime.of(minutes / 60, minutes % 60).format(DateTimeFormatter.ofPattern("hh:mm a"))

@Composable
internal fun MorningCard(vm: MainViewModel) {
    val settings = vm.g.settings
    val on by settings.morningEnabled.collectAsStateWithLifecycle()
    val minutes by settings.morningMinutes.collectAsStateWithLifecycle()
    var showTime by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    SoftCard {
        CardTitle(Icons.Rounded.WbSunny, "Morning briefing",
            "Each morning: daily tasks reset, and the AI writes a short briefing (yesterday, today's plan, leftovers) into Notes and notifies you.")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Every morning", Modifier.weight(1f))
            Switch(checked = on, onCheckedChange = { vm.applyMorning(it, minutes) })
        }
        if (on) OutlinedButton(onClick = { showTime = true }, modifier = Modifier.fillMaxWidth()) { Text("At ${formatTime(minutes)}") }
        Text("Needs notification permission. Android may delay it by a few minutes to save battery.", fontSize = 12.sp, color = colors.onSurfaceVariant)
    }

    if (showTime) {
        val st = rememberTimePickerState(initialHour = minutes / 60, initialMinute = minutes % 60, is24Hour = false)
        Dialog(onDismissRequest = { showTime = false }) {
            Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp) {
                Column(Modifier.padding(24.dp)) {
                    Text("Briefing time", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.size(16.dp))
                    TimePicker(state = st)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showTime = false }) { Text("Cancel") }
                        TextButton(onClick = { vm.applyMorning(on, st.hour * 60 + st.minute); showTime = false }) { Text("OK") }
                    }
                }
            }
        }
    }
}

// ============================ Routines ============================
private val templates = listOf(
    Triple(
        "Daily opportunity finder",
        "Find clients for my freelance AI services",
        "You are a market-research analyst for a small freelancer who sells AI chatbots, AI voice/text agents, workflow automation, Google-review follow-up automation and website design, and who gets clients only through cold email, Instagram DM and LinkedIn DM (no ad budget, no inbound).\n\n" +
            "Find 6-8 opportunities. Each is exactly: Industry + Location + Service, where owners can plausibly pay $500-$3000+/month, there is a concrete provable pain, existing outreach saturation is low, and the owner is individually reachable.\n" +
            "Path 1: unsaturated industries in high-paying English-speaking countries (skip over-pitched niches such as dentists, plumbers, realtors, restaurants, gyms).\n" +
            "Path 2: unsaturated locations (secondary/tertiary cities, states, countries) for popular niches.\n" +
            "For each opportunity give: why high value, why low saturation, how to verify it (Google searches, Facebook Ads Library, Clutch/UpCity, Upwork volume), and the best outreach channel + angle. End with one TOP PICK and the reasoning. Use short markdown sections and be concrete."
    ),
    Triple("Morning game plan", "Plan today from my task list",
        "Look at my open tasks and give me a short, energetic plan for today: pick the 3 most important, suggest a realistic order, and finish with one motivating sentence. Keep it under 150 words."),
    Triple("Learn one thing", "A tiny daily lesson",
        "Teach me one interesting and useful thing today in under 150 words. If you know my goals, relate it to them. End with one question to think about.")
)

@Composable
internal fun RoutinesCard(vm: MainViewModel) {
    val routines by vm.routines.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf<Long?>(null) }
    val colors = MaterialTheme.colorScheme

    SoftCard {
        CardTitle(Icons.Rounded.Bolt, "Daily routines",
            "Prompts that run by themselves every morning (when you have internet) and save the answer as a note. Your daily lead research fits here.")
        if (routines.isEmpty()) Text("No routines yet.", fontSize = 14.sp, color = colors.onSurfaceVariant)
        routines.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.name, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                if (running == r.id) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else IconButton(onClick = {
                    running = r.id; message = null
                    vm.runRoutineNow(r) { message = it; running = null }
                }) { Icon(Icons.Rounded.PlayArrow, "Run now", tint = colors.primary) }
                Switch(checked = r.enabled, onCheckedChange = { vm.setRoutineEnabled(r, it) })
                IconButton(onClick = { vm.deleteRoutine(r.id) }) { Icon(Icons.Rounded.Delete, "Delete", tint = colors.onSurfaceVariant) }
            }
        }
        message?.let { Text(it, fontSize = 13.sp) }
        Button(onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) { Text("Add routine") }
    }

    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var prompt by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("New routine") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("Start from a template:", fontSize = 13.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        templates.forEach { (n, _, p) -> FilterChip(selected = name == n, onClick = { name = n; prompt = p }, label = { Text(n) }) }
                    }
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("What should it do every morning?") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                    Text("Routines now run with the full toolbox: they can search the web, read pages, and use your tasks, notes and memories.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && prompt.isNotBlank(), onClick = { vm.addRoutine(name, prompt); showAdd = false }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Cancel") } }
        )
    }
}

// ============================ Backup ============================
@Composable
internal fun BackupCard(vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val vaultUnlocked by vm.g.vault.unlocked.collectAsStateWithLifecycle()
    var showExport by remember { mutableStateOf(false) }
    var importBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pending
        if (uri != null && bytes != null) {
            message = try {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                "Encrypted backup saved. Without the backup password nobody can read it."
            } catch (e: Exception) { "Couldn't save the file: ${e.message}" }
        }
        pending = null
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { importBytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
            catch (e: Exception) { message = "Couldn't open the file: ${e.message}" }
        }
    }

    SoftCard {
        CardTitle(Icons.Rounded.Shield, "Encrypted backup",
            "Export your data as one encrypted file (AES-256, protected by a password you choose). Text only – pictures are never included. Safe to keep on Drive, WhatsApp or a USB stick.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { showExport = true }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.FileUpload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Export")
            }
            OutlinedButton(onClick = { openLauncher.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.FileDownload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Import")
            }
        }
        if (busy) Text("Encrypting…", fontSize = 13.sp, color = colors.onSurfaceVariant)
        message?.let { Text(it, fontSize = 13.sp) }
        Text("Forget the backup password and the file can never be opened. Import merges into what is already on the phone.", fontSize = 12.sp, color = colors.onSurfaceVariant)
    }

    if (showExport) {
        var tasks by remember { mutableStateOf(true) }
        var notes by remember { mutableStateOf(true) }
        var memories by remember { mutableStateOf(true) }
        var routines by remember { mutableStateOf(true) }
        var chat by remember { mutableStateOf(false) }
        var vault by remember { mutableStateOf(true) }
        var keys by remember { mutableStateOf(true) }
        var p1 by remember { mutableStateOf("") }
        var p2 by remember { mutableStateOf("") }
        val vaultBlocked = vault && !vaultUnlocked
        AlertDialog(
            onDismissRequest = { showExport = false },
            title = { Text("Export encrypted backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    @Composable
                    fun opt(label: String, v: Boolean, set: (Boolean) -> Unit) {
                        Row(Modifier.fillMaxWidth().clickable { set(!v) }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = v, onCheckedChange = null); Spacer(Modifier.width(10.dp)); Text(label, fontSize = 14.sp)
                        }
                    }
                    opt("Tasks, health, Mind & focus history", tasks) { tasks = it }
                    opt("Notes (text only)", notes) { notes = it }
                    opt("Memories", memories) { memories = it }
                    opt("Daily routines & automations", routines) { routines = it }
                    opt("Chat history (text only)", chat) { chat = it }
                    opt("Private vault (Account tab)", vault) { vault = it }
                    opt("API keys", keys) { keys = it }
                    Text("Always included: your name, assistant and settings, and your Telegram friends. Pictures are never included.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (vaultBlocked) Text("Unlock the vault in the Account tab first, or untick it.", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.size(6.dp))
                    OutlinedTextField(
                        value = p1, onValueChange = { p1 = it }, label = { Text("Backup password (8+ characters)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = p2, onValueChange = { p2 = it }, label = { Text("Repeat password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = p1.length >= 8 && p1 == p2 && !vaultBlocked,
                    onClick = {
                        showExport = false
                        busy = true; message = null
                        scope.launch {
                            try {
                                pending = vm.exportBackup(ExportOptions(tasks, notes, memories, chat, vault, routines, keys), p1.toCharArray())
                                saveLauncher.launch("privacy-backup-${LocalDate.now()}.dhb")
                            } catch (e: Exception) { message = e.message ?: "Export failed." }
                            busy = false
                        }
                    }
                ) { Text("Export") }
            },
            dismissButton = { TextButton(onClick = { showExport = false }) { Text("Cancel") } }
        )
    }

    importBytes?.let { bytes ->
        var pass by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { importBytes = null },
            title = { Text("Import backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter the password you chose when exporting.", fontSize = 14.sp)
                    OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text("Backup password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    if (!vaultUnlocked) Text("Tip: unlock your vault first if the backup contains vault entries.", fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(enabled = pass.isNotEmpty(), onClick = {
                    importBytes = null
                    busy = true
                    scope.launch {
                        message = try { vm.importBackup(bytes, pass.toCharArray()) } catch (e: Exception) { e.message ?: "Import failed." }
                        busy = false
                    }
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { importBytes = null }) { Text("Cancel") } }
        )
    }
}

// ============================ Widget + privacy ============================
@Composable
internal fun WidgetCard() {
    Fold("Home-screen widgets") {
        Text(
            "Long-press your home screen → Widgets:\n• PRIVACY · Tasks (3×2): today's checklist – tap the circle to cross a task out, tap its name to mark it in progress.\n• PRIVACY · Health (3×2): health of the day with four rings.\n• PRIVACY · Dopamine vs Detox (3×2): detox % at the left edge, dopamine % at the right edge and the graph of the day.\n• PRIVACY · Note: shows one note you choose, scrollable.\n• PRIVACY · Mic (1×1): tap it and the pixel assistant appears over whatever you are doing, already listening. Swipe down to dismiss.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 19.sp
        )
    }
}

@Composable
internal fun PrivacyCard() {
    val colors = MaterialTheme.colorScheme
    Fold("Privacy · where your data goes") {
        Text(
            "• Tasks, notes, memories, chat history and your vault are stored in a database on this phone only. There is no server or account behind this app.\n" +
                "• Android cloud backup is switched off for this app.\n" +
                "• The vault is encrypted with a key derived from your passcode. API keys are encrypted by the Android Keystore.\n" +
                "• When you chat with the AI, your message plus some context (open tasks, memories, vault entries you marked “Share”) is sent to the provider you selected (Groq, NVIDIA, …). Their privacy terms apply to that text. Offline /commands send nothing.\n" +
                "• The assistant can search the web (DuckDuckGo), read pages and Wikipedia when needed; only the search words or address it requests are sent.\n" +
                "• Voice input uses your phone's speech recognizer (usually Google's), which may send the audio to its servers. Spoken replies use the phone's text-to-speech.\n" +
                "• Automations run on this phone using Android alarms. AI automations send their prompt to your chosen AI provider when they fire.",
            fontSize = 13.sp, color = colors.onSurfaceVariant
        )
    }
}
