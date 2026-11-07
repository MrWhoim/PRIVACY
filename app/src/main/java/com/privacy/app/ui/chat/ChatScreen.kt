package com.privacy.app.ui.chat

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import com.privacy.app.ui.noDrawerSwipe
import com.privacy.app.ui.blockOnLongPress
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material3.Button
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privacy.app.ai.LocalCommands
import com.privacy.app.data.ChatEntity
import com.privacy.app.ui.Appear
import com.privacy.app.ui.MainViewModel
import com.privacy.app.ui.MonoLabel
import com.privacy.app.ui.PixelLoader
import com.privacy.app.AppInfo
import com.privacy.app.ui.BrandLogo
import com.privacy.app.ui.PixelMark
import com.privacy.app.ui.pixelGlass
import com.privacy.app.ui.PixelMicIcon
import com.privacy.app.ui.face.AvatarState
import com.privacy.app.ui.face.PixelAvatar
import kotlinx.coroutines.delay
import com.privacy.app.ui.ScreenBackground
import com.privacy.app.ui.bounceClick
import com.privacy.app.ui.theme.Mono
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

@Composable
fun ChatScreen(
    vm: MainViewModel,
    openDrawer: () -> Unit,
    /** listen = true opens the talking face and starts listening at once (the mic button). */
    openFace: (listen: Boolean) -> Unit,
    openApis: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val messages by vm.chat.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val status by vm.aiStatus.collectAsStateWithLifecycle()
    val agentWorking by vm.agentActive.collectAsStateWithLifecycle()
    val queue by vm.queueStatus.collectAsStateWithLifecycle()
    val visionReady by vm.visionReady.collectAsStateWithLifecycle()
    val name by vm.g.settings.userCall.collectAsStateWithLifecycle()
    val aName by vm.g.settings.assistantName.collectAsStateWithLifecycle()
    val cid by vm.currentChat.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    /** the text the user selected in a message and chose "Reply" on – goes with the next message */
    var expandInput by remember { mutableStateOf(false) }
    var replyTo by remember { mutableStateOf<String?>(null) }
    var tbRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var tbCopy by remember { mutableStateOf<(() -> Unit)?>(null) }
    val defaultToolbar = androidx.compose.ui.platform.LocalTextToolbar.current
    val replyToolbar = remember {
        object : androidx.compose.ui.platform.TextToolbar {
            var st: androidx.compose.ui.platform.TextToolbarStatus = androidx.compose.ui.platform.TextToolbarStatus.Hidden
            override val status: androidx.compose.ui.platform.TextToolbarStatus get() = st
            override fun hide() { tbRect = null; st = androidx.compose.ui.platform.TextToolbarStatus.Hidden }
            override fun showMenu(
                rect: androidx.compose.ui.geometry.Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
                onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?
            ) { tbRect = rect; tbCopy = onCopyRequested; st = androidx.compose.ui.platform.TextToolbarStatus.Shown }
        }
    }
    fun sendNow(text: String) {
        val q = replyTo
        vm.send(if (q != null) "[[reply:" + q.replace("]]", "] ]").replace('\n', ' ').take(300) + "]]\n" + text else text)
        replyTo = null
    }
    var attachMenu by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val colors = MaterialTheme.colorScheme

    // ---------------- photo: attach first, then say what you want from it ----------------
    var pendingPhoto by remember { mutableStateOf<android.net.Uri?>(null) }
    /** "analyse" = the photo stays in the message box and goes to the AI with your question · "ocr" = the text in the photo is pulled straight into the message box */
    var photoMode by remember { mutableStateOf("analyse") }
    var attachStep by remember { mutableStateOf(0) }
    fun runOcr(uri: android.net.Uri) {
        reading = true
        scope.launch {
            vm.extractText(uri).fold(
                onSuccess = { text -> input = (input.trim() + "\n\n" + text).trim() },
                onFailure = { Toast.makeText(ctx, it.message ?: "Couldn't read that photo.", Toast.LENGTH_LONG).show() }
            )
            reading = false
        }
    }
    fun handlePhoto(uri: android.net.Uri) { if (photoMode == "ocr") runOcr(uri) else pendingPhoto = uri }
    fun sendPhoto() {
        val uri = pendingPhoto ?: return
        if (!visionReady) {
            Toast.makeText(ctx, "Add a vision-capable model in API & Subscription to analyse photos – or use “Extract text”.", Toast.LENGTH_LONG).show()
            return
        }
        vm.analyzePhoto(uri, input); input = ""; pendingPhoto = null
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) handlePhoto(uri) }
    var cameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraUri?.let { handlePhoto(it) } }
    fun launchCamera() {
        val file = File(File(ctx.cacheDir, "camera").apply { mkdirs() }, "photo-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        cameraUri = uri
        takePhoto.launch(uri)
    }

    // The list is anchored to the BOTTOM (reverseLayout): opening the app or any chat shows the newest message, and new messages
    // keep you at the bottom – unless you have scrolled up to read.
    LaunchedEffect(cid) { listState.scrollToItem(0) }
    val count = (messages?.size ?: 0) + if (busy) 1 else 0
    // The answer replaces the "thinking" row, so the item count does not change – watch the last message and the busy flag instead,
    // otherwise the list stays on your own message and the answer sits below the fold.
    val lastKey = messages?.lastOrNull()?.let { it.id to it.content.length }
    LaunchedEffect(count, lastKey, busy) { if (count > 0 && listState.firstVisibleItemIndex <= 3) listState.scrollToItem(0) }

    // "/" shows every command
    val slash = input.startsWith("/") && !input.contains(' ') && !input.contains('\n')
    val matches = if (slash) LocalCommands.catalog.filter { it.first.startsWith(input.lowercase()) } else emptyList()

    androidx.compose.runtime.SideEffect { com.privacy.app.AppState.selecting = tbRect != null }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { com.privacy.app.AppState.selecting = false } }
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalTextToolbar provides replyToolbar) {
    ScreenBackground {
        Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // ---------------- header: menu · name ----------------
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = openDrawer) { Icon(Icons.Rounded.Menu, "Menu", tint = colors.onBackground) }
                Text(AppInfo.NAME, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing = 1.sp, color = colors.onBackground, maxLines = 1, modifier = Modifier.weight(1f))
                ModesButton(vm)
            }
            HeaderPager(vm)

            // ---------------- messages ----------------
            val list = messages
            when {
                list == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { PixelLoader() }
                list.isEmpty() && !busy -> Welcome(name, aName, status == null, Modifier.weight(1f), onAddKey = openApis) { sendNow(it) }
                else -> {
                    val lastUser = remember(list) { list.lastOrNull { it.role == "user" }?.content.orEmpty() }
                    val shown = remember(list) { list.asReversed() }
                    LazyColumn(
                        state = listState,
                        reverseLayout = true,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        if (busy) item(key = "thinking") { ThinkingRow(agentWorking, lastUser, name, queue) }
                        itemsIndexed(shown, key = { _, m -> m.id }) { i, m ->
                            MessageRow(m, aName)
                        }
                    }
                }
            }

            // ---------------- composer ----------------
            Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)) {
                AnimatedVisibility(visible = matches.isNotEmpty(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp), color = colors.surface, border = BorderStroke(1.dp, colors.outlineVariant),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                            matches.forEach { (cmd, desc) ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { input = "$cmd " }.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(cmd, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.width(92.dp))
                                    Text(desc, fontSize = 12.5.sp, color = colors.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                AnimatedVisibility(visible = reading) {
                    Row(Modifier.padding(start = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        PixelLoader()
                        Spacer(Modifier.width(10.dp))
                        MonoLabel("Reading the text in your photo…")
                    }
                }

                replyTo?.let { q ->
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp)).background(colors.surfaceVariant).padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.width(3.dp).height(30.dp).clip(RoundedCornerShape(2.dp)).background(colors.onSurface))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            MonoLabel("Replying to")
                            Text(q, fontSize = 13.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = colors.onSurface)
                        }
                        IconButton(onClick = { replyTo = null }) { Icon(Icons.Rounded.Close, "Cancel reply", tint = colors.onSurfaceVariant) }
                    }
                }
                pendingPhoto?.let { uri ->
                    val thumb = remember(uri) {
                        runCatching {
                            val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                            ctx.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }?.asImageBitmap()
                        }.getOrNull()
                    }
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (thumb != null) androidx.compose.foundation.Image(thumb, "Photo", Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Photo attached", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("Type what you want to know about it, then send.", fontSize = 12.sp, color = colors.onSurfaceVariant)
                        }
                        IconButton(onClick = { pendingPhoto = null }) { Icon(Icons.Rounded.Close, "Remove photo", tint = colors.onSurfaceVariant) }
                    }
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Row(
                        Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(26.dp)).background(colors.surfaceVariant).padding(start = 4.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box {
                            IconButton(onClick = { if (visionReady) attachStep = 0 else { photoMode = "ocr"; attachStep = 1 }; attachMenu = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.Add, "Add a photo", tint = colors.onSurface) }
                            DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                                if (attachStep == 0 && visionReady) {
                                    DropdownMenuItem(
                                        text = { Column { Text("Analyse"); Text("Attach the photo and ask about it", fontSize = 11.sp, color = colors.onSurfaceVariant) } },
                                        leadingIcon = { Icon(Icons.Rounded.Image, null) }, onClick = { photoMode = "analyse"; attachStep = 1 }
                                    )
                                    DropdownMenuItem(
                                        text = { Column { Text("Extract text"); Text("Read the text in the photo (OCR)", fontSize = 11.sp, color = colors.onSurfaceVariant) } },
                                        leadingIcon = { Icon(Icons.Rounded.TextFields, null) }, onClick = { photoMode = "ocr"; attachStep = 1 }
                                    )
                                } else {
                                    DropdownMenuItem(
                                        text = { Text("Take a photo") }, leadingIcon = { Icon(Icons.Rounded.PhotoCamera, null) },
                                        onClick = { attachMenu = false; launchCamera() }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Choose a photo") }, leadingIcon = { Icon(Icons.Rounded.Image, null) },
                                        onClick = { attachMenu = false; pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                                    )
                                }
                            }
                        }
                        // the cursor goes to the END whenever the text is set from outside (a picked /command, text read from a photo…)
                        var field by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(input)) }
                        if (field.text != input) field = androidx.compose.ui.text.input.TextFieldValue(input, androidx.compose.ui.text.TextRange(input.length))
                        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalTextToolbar provides defaultToolbar) {
                        TextField(
                            value = field,
                            onValueChange = { field = it; input = it.text },
                            // TextField has a built-in 16 dp left padding; pull it back so "+" and the text sit close together
                            modifier = Modifier.weight(1f).noDrawerSwipe().layout { measurable, constraints ->
                                val extra = 12.dp.roundToPx()
                                val p = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + extra))
                                layout(constraints.maxWidth, p.height) { p.place(-extra, 0) }
                            },
                            placeholder = { Text(if (pendingPhoto != null) "What should I look for in the photo?" else "Ask anything", fontSize = 16.sp, color = colors.onSurfaceVariant) },
                            maxLines = 6,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
                            )
                        )
                        }
                        if (input.count { it == '\n' } >= 4 || input.length > 220) {
                            IconButton(onClick = { expandInput = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.OpenInFull, "Expand", tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp)) }
                        }
                        // sending: the arrow flies off to the upper right while the button stays a moment, then the message slides into the chat
                        var flyAt by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
                        var flying by remember { mutableStateOf(false) }
                        LaunchedEffect(flyAt) { if (flyAt > 0) { flying = true; kotlinx.coroutines.delay(380); flying = false } }
                        val fly by androidx.compose.animation.core.animateFloatAsState(if (flying) 1f else 0f, androidx.compose.animation.core.tween(if (flying) 320 else 0), label = "fly")
                        if (busy || input.isNotBlank() || pendingPhoto != null || flying) {
                            Box(
                                Modifier.size(36.dp).clip(CircleShape).background(colors.onSurface)
                                    .clickable {
                                        if (busy) vm.stop()
                                        else if (pendingPhoto != null) { flyAt = System.currentTimeMillis(); sendPhoto() }
                                        else if (input.isNotBlank()) { flyAt = System.currentTimeMillis(); sendNow(input); input = "" }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (busy && !flying) Icon(Icons.Rounded.Stop, "Stop", tint = colors.background, modifier = Modifier.size(20.dp))
                                else com.privacy.app.ui.PaperPlaneIcon(colors.background, colors.onSurface, Modifier.size(20.dp).graphicsLayer {
                                    translationX = 26.dp.toPx() * fly; translationY = -26.dp.toPx() * fly; alpha = 1f - fly
                                })
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    // waveform → opens the pixel assistant, already listening
                    Box(
                        Modifier.size(52.dp).clip(CircleShape).background(colors.surfaceVariant).clickable { openFace(true) },
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Rounded.GraphicEq, "Talk", tint = colors.onSurface, modifier = Modifier.size(26.dp)) }
                }
            }
        }
        if (expandInput) androidx.compose.ui.window.Dialog(
            onDismissRequest = { expandInput = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            Column(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().navigationBarsPadding().imePadding().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Your message", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    IconButton(onClick = { expandInput = false }) { Icon(Icons.Rounded.CloseFullscreen, "Collapse") }
                }
                TextField(
                    value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f).fillMaxWidth(),
                    colors = TextFieldDefaults.colors(focusedContainerColor = colors.surfaceVariant, unfocusedContainerColor = colors.surfaceVariant, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent)
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    Button(onClick = { if (input.isNotBlank()) { sendNow(input); input = "" }; expandInput = false }, enabled = input.isNotBlank() && !busy) { Text("Send") }
                }
            }
        }
        // "Reply" bubble that appears over selected text (replaces the usual Copy / Select-all strip)
        tbRect?.let { r ->
            androidx.compose.ui.window.Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(r.left.roundToInt().coerceAtLeast(8), (r.top.roundToInt() - 150).coerceAtLeast(8)),
                onDismissRequest = { replyToolbar.hide() }
            ) {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(colors.onSurface).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).clickable {
                            tbCopy?.invoke()
                            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val sel = cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
                            if (sel.isNotBlank()) replyTo = sel
                            replyToolbar.hide()
                        }.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Reply", color = colors.background, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Rounded.Reply, null, tint = colors.background, modifier = Modifier.size(16.dp))
                    }
                    Box(Modifier.width(1.dp).height(18.dp).background(colors.background.copy(alpha = 0.3f)))
                    Text(
                        "Copy", color = colors.background, fontSize = 15.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable { tbCopy?.invoke(); replyToolbar.hide() }.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
        }
        }
    }
    }
}

@Composable
private fun Welcome(name: String, aName: String, needsKey: Boolean, modifier: Modifier, onAddKey: () -> Unit, onPick: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        PixelAvatar(
            170.dp, Modifier,
            gesture = com.privacy.app.ui.face.Gesture.WAVE, autoGestures = true, fps = 30
        )
        Spacer(Modifier.height(14.dp))
        Text("Hi $name.", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text("What's the plan?", color = colors.onSurfaceVariant, fontSize = 15.sp)
        Spacer(Modifier.height(22.dp))
        if (needsKey) {
            Surface(
                shape = RoundedCornerShape(50), color = colors.onSurface,
                modifier = Modifier.clickable(onClick = onAddKey)
            ) { Text("Add a free API key to start", Modifier.padding(horizontal = 18.dp, vertical = 11.dp), color = colors.background, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(12.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            listOf("What's on my plate today?", "Remind me my tasks at 7 PM", "Research the best AI tools for students", "/add Call mom @tomorrow").forEach { s ->
                Surface(shape = RoundedCornerShape(50), color = colors.surfaceVariant, modifier = Modifier.clickable { onPick(s) }) {
                    Text(s, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), fontSize = 14.sp, color = colors.onSurface)
                }
            }
        }
    }
}

/** "19 Oct • 16:52  [copy]" under every message. */
@Composable
private fun MessageMeta(m: ChatEntity, plain: String, align: Alignment.Horizontal) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val stamp = remember(m.createdAt) {
        java.time.Instant.ofEpochMilli(m.createdAt).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("d MMM • HH:mm", java.util.Locale.getDefault()))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (align == Alignment.End) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
        Text(stamp, fontSize = 11.sp, color = colors.onSurfaceVariant.copy(alpha = 0.8f))
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.Rounded.ContentCopy, "Copy", tint = colors.onSurfaceVariant,
            modifier = Modifier.size(28.dp).clip(CircleShape).clickable {
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("message", plain))
                Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
            }.padding(6.dp)
        )
    }
}

@Composable
private fun MessageRow(m: ChatEntity, aName: String) {
    val colors = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    when (m.role) {
        "user" -> {
            val img = Regex("\\[\\[img:([^\\]]+)\\]\\]").find(m.content)
            val quote = Regex("^\\[\\[reply:(.*?)]]\\n?", RegexOption.DOT_MATCHES_ALL).find(m.content)?.groupValues?.get(1)
            val friend = Regex("^\\[\\[from:([^\\]]+)\\]\\]").find(m.content)?.groupValues?.get(1)
            val text = m.content.replace(Regex("^\\[\\[from:[^\\]]+\\]\\]"), "").replace(Regex("\\n?\\[\\[img:[^\\]]+\\]\\]"), "").replace(Regex("^\\[\\[reply:.*?]]\\n?", RegexOption.DOT_MATCHES_ALL), "").trim()
            // a message that was just sent slides up from the input corner and fades in
            val fresh = remember(m.id) { System.currentTimeMillis() - m.createdAt < 2500 }
            val slide = remember(m.id) { androidx.compose.animation.core.Animatable(if (fresh) 1f else 0f) }
            LaunchedEffect(m.id) { if (fresh) slide.animateTo(0f, androidx.compose.animation.core.tween(420, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
            Column(
                Modifier.fillMaxWidth().graphicsLayer { translationY = 70.dp.toPx() * slide.value; translationX = 30.dp.toPx() * slide.value; alpha = 1f - slide.value; scaleX = 1f - 0.12f * slide.value; scaleY = 1f - 0.12f * slide.value },
                horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (img != null) {
                    val bmp = remember(img.groupValues[1]) {
                        runCatching { android.graphics.BitmapFactory.decodeFile(File(File(ctx.filesDir, "chat_images"), img.groupValues[1]).path)?.asImageBitmap() }.getOrNull()
                    }
                    if (bmp != null) androidx.compose.foundation.Image(
                        bmp, "Photo", Modifier.widthIn(max = 240.dp).heightIn(max = 300.dp).clip(RoundedCornerShape(20.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                    )
                }
                Box(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(22.dp)).background(colors.surfaceVariant)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 11.dp)) {
                        if (quote != null) Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(3.dp).height(30.dp).clip(RoundedCornerShape(2.dp)).background(colors.onSurfaceVariant))
                            Spacer(Modifier.width(8.dp))
                            Text(quote, fontSize = 13.sp, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = colors.onSurfaceVariant)
                        }
                        SelectionContainer(Modifier.blockOnLongPress()) { Text(text, color = colors.onSurface, fontSize = 16.sp) }
                    }
                }
                if (friend != null) Text(
                    friend + " · " + java.time.Instant.ofEpochMilli(m.createdAt).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("d MMM, h:mm a")),
                    fontSize = 11.sp, color = colors.onSurfaceVariant
                )
                MessageMeta(m, plain = text, Alignment.End)
            }
        }
        "action" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val shot = Regex("\\[\\[img:([^\\]]+)\\]\\]").find(m.content)?.groupValues?.get(1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Check, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(m.content.replace(Regex("\\s*\\[\\[img:[^\\]]+\\]\\]"), ""), fontSize = 12.5.sp, color = colors.onSurfaceVariant)
            }
            if (shot != null) {
                val f = remember(shot) { File(File(ctx.filesDir, "chat_images"), shot) }
                val bmp = remember(shot) { runCatching { android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap() }.getOrNull() }
                if (bmp != null) androidx.compose.foundation.Image(
                    bmp, "Picture (tap for full screen)",
                    Modifier.widthIn(max = 300.dp).heightIn(max = 360.dp).clip(RoundedCornerShape(18.dp)).clickable { com.privacy.app.AppState.imageRequest.value = android.net.Uri.fromFile(f) },
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit
                )
            }
        }
        else -> Column(Modifier.fillMaxWidth()) {
            SelectionContainer(Modifier.blockOnLongPress()) { MarkdownText(m.content, colors.onSurface, Modifier.fillMaxWidth()) }
            MessageMeta(m, plain = m.content, Alignment.Start)
        }
    }
}

@Composable
private fun ThinkingRow(agentWorking: Boolean, lastUser: String, name: String, queue: String?) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2400); tick++ } }
    val line = when {
        queue != null -> queue
        agentWorking -> "Big task · " + ThinkingPhrases.phrase(lastUser, name, tick)
        else -> ThinkingPhrases.phrase(lastUser, name, tick)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 2.dp)) {
        PixelAvatar(54.dp, state = AvatarState.THINKING, assemble = false, fps = 20)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(line, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (queue == null) PixelLoader(Modifier.padding(top = 5.dp))
        }
    }
}
