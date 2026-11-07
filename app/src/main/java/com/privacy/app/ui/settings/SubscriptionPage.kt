package com.privacy.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import kotlinx.coroutines.launch
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privacy.app.credits.CreditCatalog
import com.privacy.app.credits.CreditPack
import com.privacy.app.credits.Credits
import com.privacy.app.credits.CreditsConfig
import com.privacy.app.ui.GoogleButton
import com.privacy.app.ui.MainViewModel
import androidx.compose.foundation.verticalScroll
import com.privacy.app.ui.MonoLabel
import com.privacy.app.ui.SoftCard
import com.privacy.app.ui.theme.Mono

private fun fmt(d: Double): String = java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(Math.round(d))

@Composable
fun ApisPage(vm: MainViewModel, onBack: () -> Unit) {
    var details by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = details) { details = false }
    if (details) { CreditsDetailsPage(onBack = { details = false }); return }
    SettingsPageOf("API & Subscription", "Credits · plans · your own AI keys", onBack) {
        card(0) { CreditsSection(onOpenDetails = { details = true }) }
        card(1) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("With credits, every feature just works – chat, photos, voice, briefs and automations. No API keys needed.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        card(2) { AdvancedKeys(vm) }
    }
}

/** The new window behind the Credits button: what each action costs, then the usage Log. */
@Composable
private fun CreditsDetailsPage(onBack: () -> Unit) {
    val balance by Credits.balance.collectAsStateWithLifecycle()
    val log by Credits.log.collectAsStateWithLifecycle()
    val busy by Credits.busy.collectAsStateWithLifecycle()
    val paid by Credits.paid.collectAsStateWithLifecycle()
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) { Credits.refresh() }
    val email by Credits.email.collectAsStateWithLifecycle()
    val name by Credits.name.collectAsStateWithLifecycle()
    SettingsPageOf(if (paid) "Credits" else "Free plan", if (paid) (balance?.let { "${fmt(it)} credits left" } ?: "Usage and costs") else "What each action costs", onBack) {
        card(0) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name.ifBlank { "Signed in" }, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(email, fontSize = 12.sp, color = dim)
                }
                OutlinedButton(onClick = { Credits.signOut(); onBack() }) { Text("Sign out") }
            }
        }
        card(1) {
            SoftCard {
                Text("What each action costs", style = MaterialTheme.typography.titleMedium)
                Text("You are charged for what is really used; these are typical amounts.", fontSize = 12.sp, color = dim, lineHeight = 17.sp)
                CreditCatalog.costs.forEach { (what, cost) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Text(what, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(end = 12.dp))
                        Text(cost, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
        if (paid) card(2) {
            SoftCard {
                Text("Log", style = MaterialTheme.typography.titleMedium)
                if (log.isEmpty()) Text(if (busy) "Loading…" else "Nothing used yet.", fontSize = 12.sp, color = dim)
                log.filter { Math.round(it.credits) != 0L }.take(100).forEach { r ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.action, fontSize = 13.sp)
                            Text(r.at.replace("T", " ").take(16), fontSize = 11.sp, color = dim)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text((if (r.credits > 0) "+" else "−") + fmt(Math.abs(r.credits)), fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                color = if (r.credits > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            Text("left ${fmt(r.balanceAfter)}", fontSize = 11.sp, color = dim)
                        }
                    }
                }
                OutlinedButton(onClick = { scope.launch { Credits.refresh() } }) { Text(if (busy) "Refreshing…" else "Refresh") }
            }
        }
    }
}

/** Own API keys: optional, tucked away, never needed when you have credits. */
@Composable
private fun AdvancedKeys(vm: MainViewModel) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("Advanced: your own AI keys", "Optional. Own keys never use credits.", open) { open = !open }
        if (open) { KeysCard(vm); ModelsCard(vm); VisionCard(vm); VoiceModelsCard(vm) }
    }
}

@Composable
private fun CreditsSection(onOpenDetails: () -> Unit) {
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    val email by Credits.email.collectAsStateWithLifecycle()
    val name by Credits.name.collectAsStateWithLifecycle()
    val balance by Credits.balance.collectAsStateWithLifecycle()
    val log by Credits.log.collectAsStateWithLifecycle()
    val busy by Credits.busy.collectAsStateWithLifecycle()
    val status by Credits.status.collectAsStateWithLifecycle()
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    var plansOpen by remember { mutableStateOf(true) }
    var topOpen by remember { mutableStateOf(false) }
    var pickedPlan by remember { mutableStateOf(1) }
    var pickedTop by remember { mutableStateOf(0) }
    var payDialog by remember { mutableStateOf(false) }
    var payIsPlan by remember { mutableStateOf(true) }
    var signInMsg by remember { mutableStateOf<String?>(null) }
    val paid by Credits.paid.collectAsStateWithLifecycle()
    val freeLeft by Credits.freeLeft.collectAsStateWithLifecycle()
    val freeDaily by Credits.freeDaily.collectAsStateWithLifecycle()
    val signedIn = email.isNotBlank()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(Unit) { Credits.load(); if (Credits.signedIn) Credits.refresh() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ---- the big credits button (light card: it is the most important thing on this page)
        val cardInk = androidx.compose.ui.graphics.Color(0xFF111111)
        Surface(
            modifier = Modifier.fillMaxWidth().clickable { if (signedIn) onOpenDetails() },
            shape = RoundedCornerShape(18.dp), color = androidx.compose.ui.graphics.Color(0xFFF2F2F2),
            border = BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0xFFD0D0D0))
        ) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    MonoLabel("CREDITS", color = cardInk.copy(alpha = 0.55f))
                    val hasCredits = (balance ?: 0.0) >= 1.0
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            if (!signedIn) "Sign in" else if (hasCredits) fmt(balance!!) else "FREE plan",
                            fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = cardInk, maxLines = 1, softWrap = false
                        )
                        if (signedIn && hasCredits) Text("  Credits", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cardInk.copy(alpha = 0.7f), maxLines = 1, softWrap = false, modifier = Modifier.padding(bottom = 5.dp))
                    }
                    Text(
                        if (!signedIn) "for free AI and credits"
                        else if (hasCredits) "Tap for costs and your usage log"
                        else "Free AI · depends on API load · tap for costs",
                        fontSize = 12.sp, color = cardInk.copy(alpha = 0.6f)
                    )
                }
                if (signedIn) androidx.compose.material3.Icon(Icons.Rounded.KeyboardArrowRight, null, tint = cardInk)
            }
        }

        if (!signedIn) {
            SoftCard {
                Text("Sign in", style = MaterialTheme.typography.titleLarge)
                Text(
                    "We just collect your NAME and EMAIL – only so we can add the credits you buy to the right account. " +
                        "No spam, no junk mail, no ads, no selling. We are PRIVACY and we stand with it.",
                    fontSize = 13.sp, color = dim, lineHeight = 19.sp
                )
                Text("When you use credits, the text of that one request goes through our secure server to secure AI providers and is not stored. Your notes, tasks, health data and vault stay on your phone. Own API keys never touch our server.", fontSize = 12.sp, color = dim, lineHeight = 17.sp)
                GoogleButton(onClick = {
                    val act = ctx.findActivity()
                    if (act != null) scope.launch { signInMsg = Credits.signInWithGoogle(act) } else Credits.startSignIn(ctx)
                })
                signInMsg?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                if (!CreditsConfig.configured) Text("Setup needed: add your Supabase URL and anon key in credits/CreditsConfig.kt (see supabase/SETUP.md).", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name.ifBlank { "Signed in" }, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(email, fontSize = 12.sp, color = dim)
                }
            }
            SubscriptionLine()
            CloudBackupToggle()
        }

        // ---- Plans (open by default) – opening Top Up closes it and the other way round
        SectionHeader("Plans", "Monthly credits", plansOpen) { plansOpen = !plansOpen; if (plansOpen) topOpen = false }
        if (plansOpen) {
            CreditCatalog.plans.forEachIndexed { i, p -> PackRow(p, i == pickedPlan) { pickedPlan = i } }
            ProceedButton(signedIn) { payIsPlan = true; if (signedIn) payDialog = true else scope.launch { signInMsg = ctx.findActivity()?.let { Credits.signInWithGoogle(it) } } }
        }
        SectionHeader("Top Up", "One-time credits", topOpen) { topOpen = !topOpen; if (topOpen) plansOpen = false }
        if (topOpen) {
            CreditCatalog.topUps.forEachIndexed { i, p -> PackRow(p, i == pickedTop) { pickedTop = i } }
            ProceedButton(signedIn) { payIsPlan = false; if (signedIn) payDialog = true else scope.launch { signInMsg = ctx.findActivity()?.let { Credits.signInWithGoogle(it) } } }
        }
        if (signedIn && (balance ?: 0.0) < 1.0) Text(
            "The FREE plan depends on API load, so it can pause at busy times. For non-stop use, add your own API key (below), or buy credits and never think about it.",
            fontSize = 12.sp, color = dim, lineHeight = 17.sp
        )
        if (status.isNotBlank()) Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
    }

    if (payDialog) {
        val pack = if (payIsPlan) CreditCatalog.plans[pickedPlan] else CreditCatalog.topUps[pickedTop]
        val url = if (payIsPlan) CreditsConfig.BMC_PLANS_URL else CreditsConfig.BMC_TOPUP_URL
        val msg = "Hi! I'd like the ${pack.name} ${if (payIsPlan) "plan" else "top-up"} ($${pack.price} → ${pack.credits} credits). My login email is $email."
        AlertDialog(
            onDismissRequest = { payDialog = false },
            title = { Text("Contact the admin to pay") },
            text = {
                Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Admin on Telegram: @${CreditsConfig.ADMIN_TELEGRAM}", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("We are operating at a very early stage, so we are giving 1-on-1 support to understand what you need and to make this better for you.", fontSize = 13.sp, lineHeight = 19.sp)
                    Text("How it works", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(
                        "1. Message the admin on Telegram with the pack you want: ${pack.name} · $${pack.price} · ${pack.credits} credits.\n" +
                            "2. Tell him the email you signed in with: $email.\n" +
                            "3. He guides you through the payment.\n" +
                            "4. Your credits are added within 24 hours of the payment.",
                        fontSize = 13.sp, lineHeight = 19.sp
                    )
                    Text("You can also pay directly on Buy Me a Coffee (use the same email: $email). But please prefer contacting me – I'm a pretty nice person to talk with ;-)", fontSize = 13.sp, lineHeight = 19.sp)
                }
            },
            confirmButton = {
                Button(onClick = {
                    runCatching { (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("msg", msg)) }
                    android.widget.Toast.makeText(ctx, "Message copied – paste it in the chat", android.widget.Toast.LENGTH_LONG).show()
                    payDialog = false
                    runCatching { uri.openUri("https://t.me/" + CreditsConfig.ADMIN_TELEGRAM) }
                }) { Text("Copy message & open Telegram") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { payDialog = false; runCatching { uri.openUri(url) } }) { Text("Pay directly") }
                    TextButton(onClick = { payDialog = false }) { Text("Cancel") }
                }
            }
        )
    }
}

@Composable
private fun ProceedButton(signedIn: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text(if (signedIn) "Proceed to Pay" else "Sign in to buy credits") }
}

private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun Icon(icon: androidx.compose.ui.graphics.vector.ImageVector) =
    androidx.compose.material3.Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun SectionHeader(title: String, sub: String, open: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(if (open) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown)
    }
}

@Composable
private fun PackRow(p: CreditPack, selected: Boolean, onPick: () -> Unit) {
    val line = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPick), shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface, border = BorderStroke(if (selected) 2.dp else 1.dp, line)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    if (p.recommended) { Spacer(Modifier.width(8.dp)); MonoLabel("RECOMMENDED", color = MaterialTheme.colorScheme.primary) }
                }
                Text("${p.credits} credits · ${p.note}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("$${p.price}", fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
    }
}


private fun niceDate(iso: String): String = runCatching {
    java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate()
        .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.getDefault()))
}.getOrDefault("")

/** Active monthly plan and the last purchase, as the server knows them. */
@Composable
private fun SubscriptionLine() {
    val sub by Credits.sub.collectAsStateWithLifecycle()
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    SoftCard {
        MonoLabel("SUBSCRIPTION")
        if (sub.planActive) {
            Text("${sub.planName.ifBlank { "Monthly plan" }} · active until ${niceDate(sub.planUntil)}", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        } else if (sub.planName.isNotBlank()) {
            Text("${sub.planName} · ended ${niceDate(sub.planUntil)}", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        } else {
            Text("No monthly plan", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
        Text(
            if (sub.lastPack.isBlank()) "No purchase yet"
            else "Last purchase: ${sub.lastPack} (${if (sub.lastKind == "topup") "top-up" else "monthly plan"}) · ${niceDate(sub.lastAt)}",
            fontSize = 12.sp, color = dim
        )
    }
}

/** Daily encrypted backup to the cloud – monthly plan only. No pictures. */
@Composable
internal fun CloudBackupCard() {
    val ctx = LocalContext.current
    val g = remember { (ctx.applicationContext as com.privacy.app.PrivacyApp).graph }
    val sub by Credits.sub.collectAsStateWithLifecycle()
    val st by com.privacy.app.security.CloudBackup.state.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    var pw1 by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var editPw by remember { mutableStateOf(false) }
    var restoreDlg by remember { mutableStateOf(false) }
    var restorePw by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { com.privacy.app.security.CloudBackup.load(ctx); runCatching { Credits.refresh() } }

    SoftCard {
        MonoLabel("CLOUD BACKUP")
        Text("Daily encrypted backup", style = MaterialTheme.typography.titleMedium)
        if (!sub.planActive) {
            Text("Cloud backup is only for monthly plan users. Your tasks, notes, memories, chats and routines are saved once a day, encrypted with a password only you know. Pictures are never backed up.", fontSize = 12.sp, color = dim, lineHeight = 17.sp)
            if (sub.planName.isNotBlank()) {
                OutlinedButton(onClick = { restoreDlg = true }, modifier = Modifier.fillMaxWidth()) { Text("Restore from cloud") }
            }
        } else {
            Text("Turn the daily backup on or off in API & Subscription. Once a day your data is encrypted on this phone with your backup password and uploaded. The server keeps only the latest copy and cannot read it. Pictures are never included. Nothing is sent when nothing changed.", fontSize = 12.sp, color = dim, lineHeight = 17.sp)
            if (!st.hasPassword || editPw) {
                androidx.compose.material3.OutlinedTextField(pw1, { pw1 = it }, label = { Text("Backup password (min 8)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                androidx.compose.material3.OutlinedTextField(pw2, { pw2 = it }, label = { Text("Repeat password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                Text("If you forget this password the backup cannot be opened by anyone – not even us.", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                Button(onClick = {
                    if (pw1.length < 8) result = "Use at least 8 characters."
                    else if (pw1 != pw2) result = "The two passwords are different."
                    else {
                        com.privacy.app.security.CloudBackup.setPassword(ctx, pw1)
                        com.privacy.app.security.CloudBackup.setEnabled(ctx, true)
                        pw1 = ""; pw2 = ""; editPw = false; result = "Password saved. Backing up…"
                        scope.launch { result = com.privacy.app.security.CloudBackup.run(g, force = true) }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Save password & turn on") }
            } else {
                Text(
                    if (st.lastAt > 0) "Last backup: " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(st.lastAt)) else "No backup yet",
                    fontSize = 12.sp, color = dim
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !st.running, onClick = { scope.launch { result = com.privacy.app.security.CloudBackup.run(g, force = true) } }, modifier = Modifier.weight(1f)) { Text(if (st.running) "Working…" else "Back up now") }
                    OutlinedButton(onClick = { restoreDlg = true }, modifier = Modifier.weight(1f)) { Text("Restore") }
                }
                TextButton(onClick = { editPw = true }) { Text("Change password") }
            }
        }
        (result ?: st.message.takeIf { it.isNotBlank() })?.let { Text(it, fontSize = 12.sp, color = dim) }
    }

    if (restoreDlg) AlertDialog(
        onDismissRequest = { restoreDlg = false },
        title = { Text("Restore from cloud") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter the backup password. Your cloud copy is merged into this phone – nothing is overwritten.", fontSize = 13.sp)
                androidx.compose.material3.OutlinedTextField(restorePw, { restorePw = it }, label = { Text("Backup password") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            }
        },
        confirmButton = { TextButton(onClick = {
            val p = restorePw; restorePw = ""; restoreDlg = false; result = "Restoring…"
            scope.launch { result = com.privacy.app.security.CloudBackup.restore(g, p) }
        }) { Text("Restore") } },
        dismissButton = { TextButton(onClick = { restoreDlg = false }) { Text("Cancel") } }
    )
}


/** On/off switch for the daily cloud backup, right under the subscription. The password and restore live in Account → Backup. */
@Composable
private fun CloudBackupToggle() {
    val ctx = LocalContext.current
    val sub by Credits.sub.collectAsStateWithLifecycle()
    val st by com.privacy.app.security.CloudBackup.state.collectAsStateWithLifecycle()
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    LaunchedEffect(Unit) { com.privacy.app.security.CloudBackup.load(ctx) }
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Daily cloud backup", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(
                    when {
                        !sub.planActive -> "Available for monthly plan users only."
                        !st.hasPassword -> "Set your backup password in Account → Backup first."
                        st.enabled -> "On · encrypted, no pictures."
                        else -> "Off"
                    }, fontSize = 12.sp, color = dim
                )
            }
            androidx.compose.material3.Switch(
                checked = st.enabled && st.hasPassword && sub.planActive,
                enabled = sub.planActive && st.hasPassword,
                onCheckedChange = { com.privacy.app.security.CloudBackup.setEnabled(ctx, it) }
            )
        }
    }
}
