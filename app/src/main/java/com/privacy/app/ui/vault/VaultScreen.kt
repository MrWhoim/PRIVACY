@file:OptIn(ExperimentalMaterial3Api::class)

package com.privacy.app.ui.vault

import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privacy.app.security.UnlockResult
import com.privacy.app.security.VaultCategory
import com.privacy.app.security.VaultItem
import com.privacy.app.ui.MainViewModel
import com.privacy.app.ui.ScreenBackground
import com.privacy.app.ui.settings.AssistantCard
import com.privacy.app.ui.ScreenHeader
import com.privacy.app.ui.findActivity
import com.privacy.app.ui.theme.SuccessGreen
import com.privacy.app.ui.theme.WarnAmber
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class VaultEditing(val item: VaultItem?, val category: VaultCategory)

@Composable
fun VaultScreen(vm: MainViewModel, onBack: () -> Unit) {
    ScreenBackground {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Vault", "Private · passcode protected", onBack = onBack)
            Box(Modifier.weight(1f)) { VaultBody(vm) }
        }
    }
}

/** The vault (set-up, unlock or contents) without its own header, so it can sit inside the Account page. */
@Composable
fun VaultBody(vm: MainViewModel) {
    val ctx = LocalContext.current
    // Blocks screenshots and the "recent apps" thumbnail while this tab is open.
    DisposableEffect(Unit) {
        val window = ctx.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    val unlocked by vm.g.vault.unlocked.collectAsStateWithLifecycle()
    var setUp by remember { mutableStateOf(vm.g.vault.isSetUp) }

    Box(Modifier.fillMaxSize()) {
        when {
            !setUp -> SetupPanel(vm) { setUp = true }
            !unlocked -> UnlockPanel(vm) { setUp = vm.g.vault.isSetUp }
            else -> VaultContent(vm)
        }
    }
}

@Composable
private fun PrivacyNote(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = colors.primaryContainer.copy(alpha = 0.55f)
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.Shield, null, tint = colors.primary)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Private and stored only on this phone", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = colors.onPrimaryContainer)
                Text(
                    "Everything here is encrypted (AES-256) with a key made from your passcode. There is no account and no server, " +
                        "and nothing is uploaded. Two things can move it off the phone, and only if you choose them: a backup you export yourself, " +
                        "and entries you switch on as “Share with assistant” – those are sent to your AI provider as part of a chat.",
                    fontSize = 13.sp, color = colors.onPrimaryContainer
                )
            }
        }
    }
}

private fun strength(p: String): Triple<String, Float, Color> {
    var score = 0
    if (p.length >= 8) score++
    if (p.length >= 12) score++
    if (p.any { it.isDigit() } && p.any { it.isLetter() }) score++
    if (p.any { !it.isLetterOrDigit() }) score++
    return when {
        p.length < 6 -> Triple("Too short (6+ characters)", 0.1f, Color(0xFFE5484D))
        score <= 1 -> Triple("Weak – use 8+ characters or a short phrase", 0.35f, WarnAmber)
        score == 2 -> Triple("Good", 0.7f, Color(0xFF3B82F6))
        else -> Triple("Strong", 1f, SuccessGreen)
    }
}

@Composable
private fun PasscodeField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Show or hide") }
        }
    )
}

@Composable
private fun SetupPanel(vm: MainViewModel, onDone: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var p1 by remember { mutableStateOf("") }
    var p2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val (label, frac, tint) = strength(p1)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(Modifier.size(64.dp).background(colors.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Lock, null, tint = colors.primary, modifier = Modifier.size(30.dp))
        }
        Text("Your private vault", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Keep your personal details, future goals and desires here. A passcode is required every time you open this section.",
            color = colors.onSurfaceVariant
        )
        PrivacyNote()
        PasscodeField("Choose a passcode", p1, { p1 = it })
        if (p1.isNotEmpty()) {
            LinearProgressIndicator(progress = { frac }, color = tint, trackColor = colors.outlineVariant, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
            Text(label, fontSize = 12.sp, color = tint)
        }
        PasscodeField("Repeat passcode", p2, { p2 = it })
        Text(
            "If you forget this passcode, nobody can recover the vault – not even me. That is what keeps it safe. Export an encrypted backup from Settings to be safe.",
            fontSize = 13.sp, color = colors.onSurfaceVariant
        )
        Button(
            onClick = {
                busy = true
                scope.launch { vm.setUpVault(p1.toCharArray()); busy = false; onDone() }
            },
            enabled = p1.length >= 6 && p1 == p2 && !busy,
            modifier = Modifier.fillMaxWidth().height(54.dp)
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.onPrimary) else Text("Create vault")
        }
        if (busy) Text("Please wait… encrypting your vault. This takes a few seconds.", fontSize = 13.sp, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun UnlockPanel(vm: MainViewModel, onErased: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var lockMs by remember { mutableLongStateOf(vm.g.vault.lockedOutMs()) }
    var confirmErase by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        while (true) { lockMs = vm.g.vault.lockedOutMs(); delay(1000) }
    }

    fun tryUnlock() {
        if (pass.isEmpty() || busy) return
        busy = true
        scope.launch {
            when (vm.unlockVault(pass.toCharArray())) {
                UnlockResult.OK -> { pass = ""; error = null }
                UnlockResult.WRONG -> error = "That passcode is wrong."
                UnlockResult.LOCKED_OUT -> error = null
            }
            lockMs = vm.g.vault.lockedOutMs()
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        Box(Modifier.size(76.dp).background(colors.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Lock, null, tint = colors.primary, modifier = Modifier.size(34.dp))
        }
        Text("Vault locked", style = MaterialTheme.typography.headlineMedium)
        Text("Enter your passcode to open your private section.", color = colors.onSurfaceVariant)
        PasscodeField("Passcode", pass, { pass = it; error = null })
        if (lockMs > 0) Text("Too many attempts. Try again in ${(lockMs / 1000) + 1}s.", color = colors.error, fontSize = 13.sp)
        else error?.let { Text(it, color = colors.error, fontSize = 13.sp) }
        Button(onClick = ::tryUnlock, enabled = pass.isNotEmpty() && lockMs <= 0 && !busy, modifier = Modifier.fillMaxWidth().height(54.dp)) {
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.onPrimary)
                    Text("Wait, decrypting your vault…")
                }
            } else Text("Unlock")
        }
        TextButton(onClick = { confirmErase = true }) { Text("Forgot passcode?") }
    }

    if (confirmErase) {
        AlertDialog(
            onDismissRequest = { confirmErase = false },
            title = { Text("Erase the vault?") },
            text = { Text("Without the passcode the vault can't be opened. Erasing deletes everything in it permanently so you can start fresh.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmErase = false
                    scope.launch {
                        vm.g.db.vaultDao().deleteAll()
                        vm.g.vault.wipe()
                        onErased()
                    }
                }) { Text("Erase everything", color = colors.error) }
            },
            dismissButton = { TextButton(onClick = { confirmErase = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun VaultContent(vm: MainViewModel) {
    val colors = MaterialTheme.colorScheme
    val items by vm.vaultItems.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<VaultEditing?>(null) }
    var showChange by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(SuccessGreen, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text("Unlocked · encrypted on this phone", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    }
                }
                TextButton(onClick = { showChange = true }) { Text("Passcode") }
                OutlinedButton(onClick = { vm.lockVault() }) {
                    Icon(Icons.Rounded.Lock, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Lock")
                }
            }
        }
        item(key = "privacy") { PrivacyNote() }
        item(key = "assistant") { AssistantCard(vm) }
        item(key = "telegram") { com.privacy.app.ui.settings.TelegramCard(vm) }

        VaultCategory.values().forEach { cat ->
            val list = items.filter { it.category == cat }
            item(key = "head-${cat.name}") {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(cat.label, style = MaterialTheme.typography.titleMedium)
                        Text(cat.hint, fontSize = 12.sp, color = colors.onSurfaceVariant)
                    }
                    IconButton(onClick = { editing = VaultEditing(null, cat) }) { Icon(Icons.Rounded.Add, "Add to ${cat.label}", tint = colors.primary) }
                }
            }
            if (list.isEmpty()) {
                item(key = "empty-${cat.name}") {
                    Text("Nothing here yet.", fontSize = 13.sp, color = colors.onSurfaceVariant.copy(alpha = 0.7f))
                }
            } else {
                items(list, key = { "item-${it.id}" }) { item ->
                    Surface(shape = RoundedCornerShape(12.dp), color = colors.surface, border = BorderStroke(1.dp, colors.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.clickable { editing = VaultEditing(item, item.category) }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (item.category == VaultCategory.CREDENTIALS) {
                                val cr = remember(item.body) { parseCred(item.body) }
                                if (cr.first.isNotBlank()) Text("Username  ${cr.first}", color = colors.onSurfaceVariant, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (cr.second.isNotBlank()) Text("Password  ••••••••", color = colors.onSurfaceVariant, fontSize = 14.sp)
                            } else if (item.body.isNotBlank()) Text(item.body, maxLines = 3, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant, fontSize = 14.sp)
                            if (item.share) Text("Shared with assistant", fontSize = 12.sp, color = colors.primary)
                        }
                    }
                }
            }
        }
        item(key = "memory") { MemorySection(vm) }
        item(key = "plan") { com.privacy.app.ui.settings.HealthPlanCard(vm) }
        item(key = "backup") { com.privacy.app.ui.settings.BackupCard(vm) }
        item(key = "cloudbackup") { com.privacy.app.ui.settings.CloudBackupCard() }
    }

    editing?.let { e ->
        VaultEditSheet(
            e, onDismiss = { editing = null },
            onSave = { cat, t, b, share -> vm.saveVaultItem(e.item?.id, cat, t, b, share); editing = null },
            onDelete = { e.item?.let { vm.deleteVaultItem(it.id) }; editing = null }
        )
    }
    if (showChange) ChangePasscodeDialog(vm) { showChange = false }
}

/** "Username: x / Password: y / Note: z" → (x, y, z) */
private fun parseCred(body: String): Triple<String, String, String> {
    var u = ""; var p = ""; var n = ""
    body.lines().forEach { l ->
        when {
            l.startsWith("Username:") -> u = l.removePrefix("Username:").trim()
            l.startsWith("Password:") -> p = l.removePrefix("Password:").trim()
            l.startsWith("Note:") -> n = l.removePrefix("Note:").trim()
        }
    }
    if (u.isEmpty() && p.isEmpty() && n.isEmpty() && body.isNotBlank()) n = body   // an old free-text entry stays readable
    return Triple(u, p, n)
}

@Composable
private fun VaultEditSheet(
    e: VaultEditing, onDismiss: () -> Unit,
    onSave: (VaultCategory, String, String, Boolean) -> Unit, onDelete: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var cat by remember { mutableStateOf(e.category) }
    var title by remember { mutableStateOf(e.item?.title.orEmpty()) }
    var body by remember { mutableStateOf(e.item?.body.orEmpty()) }
    var share by remember { mutableStateOf(e.item?.share ?: false) }
    val credInit = remember { parseCred(e.item?.body.orEmpty()) }
    var user by remember { mutableStateOf(credInit.first) }
    var pass by remember { mutableStateOf(credInit.second) }
    var credNote by remember { mutableStateOf(credInit.third) }
    var showPass by remember { mutableStateOf(false) }
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = colors.surfaceVariant, unfocusedContainerColor = colors.surfaceVariant,
        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.surface) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(if (e.item == null) "New private entry" else "Edit entry", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VaultCategory.values().forEach { c -> FilterChip(selected = cat == c, onClick = { cat = c }, label = { Text(c.label) }) }
            }
            if (cat == VaultCategory.CREDENTIALS) {
                TextField(value = title, onValueChange = { title = it }, placeholder = { Text("Website or app, e.g. “Gmail”") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = fieldColors)
                TextField(value = user, onValueChange = { user = it }, placeholder = { Text("Username or email") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = fieldColors,
                    trailingIcon = { if (user.isNotBlank()) IconButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(user)) }) { Icon(Icons.Rounded.ContentCopy, "Copy username") } })
                TextField(value = pass, onValueChange = { pass = it }, placeholder = { Text("Password") }, singleLine = true,
                    visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = fieldColors,
                    trailingIcon = {
                        Row {
                            IconButton(onClick = { showPass = !showPass }) { Icon(if (showPass) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Show or hide") }
                            if (pass.isNotBlank()) IconButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(pass)) }) { Icon(Icons.Rounded.ContentCopy, "Copy password") }
                        }
                    })
                TextField(value = credNote, onValueChange = { credNote = it }, placeholder = { Text("Note (optional) – security question, recovery email…") },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = fieldColors)
            } else {
            TextField(value = title, onValueChange = { title = it }, placeholder = { Text("Title, e.g. “Run a marathon by 2028”") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = fieldColors)
            TextField(value = body, onValueChange = { body = it }, placeholder = { Text("Details") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp), shape = RoundedCornerShape(18.dp), colors = fieldColors)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Share with assistant", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Off = this stays 100% on the phone. On = the text is sent to your AI provider whenever you chat (while the vault is unlocked) so answers can be personal.",
                        fontSize = 12.sp, color = colors.onSurfaceVariant
                    )
                }
                Switch(checked = share, onCheckedChange = { share = it }, modifier = Modifier.padding(start = 12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (e.item != null) OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) { Text("Delete", color = colors.error) }
                Button(onClick = {
                    val b = if (cat == VaultCategory.CREDENTIALS) "Username: ${user.trim()}\nPassword: $pass" + if (credNote.isNotBlank()) "\nNote: ${credNote.trim()}" else "" else body
                    onSave(cat, title, b, share)
                }, enabled = title.isNotBlank() || body.isNotBlank() || user.isNotBlank() || pass.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Save") }
            }
        }
    }
}

@Composable
private fun ChangePasscodeDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var old by remember { mutableStateOf("") }
    var n1 by remember { mutableStateOf("") }
    var n2 by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Change passcode") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PasscodeField("Current passcode", old, { old = it; error = null })
                PasscodeField("New passcode", n1, { n1 = it; error = null })
                PasscodeField("Repeat new passcode", n2, { n2 = it; error = null })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && old.isNotEmpty() && n1.length >= 6 && n1 == n2,
                onClick = {
                    busy = true
                    scope.launch {
                        val ok = vm.changePasscode(old.toCharArray(), n1.toCharArray())
                        busy = false
                        if (ok) onDismiss() else error = "Current passcode is wrong."
                    }
                }
            ) { Text(if (busy) "Working…" else "Change") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } }
    )
}

/** "Memory": tap + to open everything the assistant remembers about you; add or forget items. */
@Composable
private fun MemorySection(vm: MainViewModel) {
    val colors = MaterialTheme.colorScheme
    val memories by vm.memories.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    var newMemory by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Memory (${memories.size})", style = MaterialTheme.typography.titleMedium)
                Text("What the assistant remembers about you. It adds these itself when you tell it something lasting.", fontSize = 12.sp, color = colors.onSurfaceVariant)
            }
            IconButton(onClick = { open = !open }) {
                Icon(if (open) Icons.Rounded.Remove else Icons.Rounded.Add, if (open) "Hide memories" else "Show memories", tint = colors.primary)
            }
        }
        if (open) {
            memories.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.content, Modifier.weight(1f), fontSize = 14.sp)
                    IconButton(onClick = { vm.deleteMemory(m.id) }) { Icon(Icons.Rounded.Close, "Forget", tint = colors.onSurfaceVariant) }
                }
            }
            if (memories.isEmpty()) Text("Nothing remembered yet.", fontSize = 13.sp, color = colors.onSurfaceVariant.copy(alpha = 0.7f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = newMemory, onValueChange = { newMemory = it }, label = { Text("Add a memory") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.addMemory(newMemory); newMemory = "" }, enabled = newMemory.isNotBlank()) { Text("Save") }
            }
        }
    }
}
