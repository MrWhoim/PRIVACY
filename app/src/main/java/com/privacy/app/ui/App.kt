package com.privacy.app.ui

import androidx.compose.foundation.clickable
import com.privacy.app.ui.face.CompanionOverlay
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.StickyNote2
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privacy.app.AppState
import com.privacy.app.ui.chat.ChatScreen
import com.privacy.app.ui.face.FaceScreen
import com.privacy.app.ui.history.HistoryScreen
import com.privacy.app.ui.notes.NotesScreen
import com.privacy.app.ui.settings.AboutPage
import com.privacy.app.ui.settings.ApisPage
import com.privacy.app.ui.settings.AutomationsPage
import com.privacy.app.ui.settings.rememberPermissionRequester
import com.privacy.app.AppInfo
import com.privacy.app.ai.UiBridge
import com.privacy.app.ai.UiCommand
import com.privacy.app.ui.tasks.TasksScreen
import com.privacy.app.ui.theme.Mono
import kotlinx.coroutines.launch

/**
 * No bottom bar. Chat is home. Swipe from the left edge (or tap ☰) for the menu: every settings category is its own page,
 * and the things you reach for most – Tasks, Notes, APIs – sit at the bottom of the menu, under your thumb.
 */
enum class Screen { CHAT, TASKS, NOTES, TOOLS, HISTORY, FACE, APIS, ME, AUTOMATIONS, SETTINGS, ABOUT, REPORTS }

private class DrawerEntry(val screen: Screen, val label: String, val icon: ImageVector)

private val topEntries = listOf(
    DrawerEntry(Screen.AUTOMATIONS, "Automations", Icons.Rounded.Bolt),
    DrawerEntry(Screen.REPORTS, "Reports", Icons.Rounded.CalendarMonth),
    DrawerEntry(Screen.SETTINGS, "Settings", Icons.Rounded.Settings),
    DrawerEntry(Screen.ME, "Account", Icons.Rounded.Lock),
    DrawerEntry(Screen.ABOUT, "About & privacy", Icons.Rounded.Info)
)

private val bottomEntries = listOf(
    DrawerEntry(Screen.TASKS, "Task & Health", Icons.Rounded.CheckCircle),
    DrawerEntry(Screen.NOTES, "Notes", Icons.Rounded.StickyNote2),
    DrawerEntry(Screen.TOOLS, "Tools", Icons.Rounded.Build),
    DrawerEntry(Screen.APIS, "API & Subscription", Icons.Rounded.Key)
)

@Composable
fun PrivacyRoot(vm: MainViewModel) {
    var screen by rememberSaveable { mutableStateOf(Screen.CHAT) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    // Back goes to where you came from (not always to the chat): every jump pushes the page you left.
    val backStack = remember { mutableStateListOf<Screen>() }
    var taskSection by remember { mutableStateOf("task") }
    val go: (Screen) -> Unit = { target ->
        if (target != screen) { backStack.add(screen); if (backStack.size > 24) backStack.removeAt(0); screen = target }
    }
    val home: () -> Unit = {
        if (backStack.isNotEmpty()) screen = backStack.removeAt(backStack.lastIndex) else screen = Screen.CHAT
    }
    val animOn by vm.g.settings.animationsOn.collectAsStateWithLifecycle()
    remember { Anim.enabled = vm.g.settings.animationsOn.value; 0 }
    androidx.compose.runtime.SideEffect { Anim.enabled = animOn }
    val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }

    var faceListen by rememberSaveable { mutableStateOf(false) }
    var toolStart by rememberSaveable { mutableStateOf("") }
    val aGender by vm.g.settings.assistantGender.collectAsStateWithLifecycle()
    val aName by vm.g.settings.assistantName.collectAsStateWithLifecycle()
    val female = com.privacy.app.voice.GroqSpeech.genderOf(aGender, aName) == "female"
    androidx.compose.runtime.CompositionLocalProvider(com.privacy.app.ui.face.LocalAssistantFemale provides female, LocalOpenDrawer provides { scope.launch { drawerState.open() } }) {
    com.privacy.app.ui.chat.TelegramPrompts(vm.g)
    var splash by rememberSaveable { mutableStateOf((vm.g.settings.splashOn.value && AppState.pendingScreen.value == null) || !vm.g.settings.profileDone) }
    val pendingForSplash by AppState.pendingScreen
    // opened from a widget or a notification: go straight to the page, no intro
    LaunchedEffect(pendingForSplash) { if (pendingForSplash != null && vm.g.settings.profileDone) splash = false }
    if (splash) {
        com.privacy.app.ui.splash.SplashScreen(vm, onDone = { splash = false })
        return@CompositionLocalProvider
    }
    val requestPermission = rememberPermissionRequester()

    val openByName: (String) -> Unit = { name ->
        val n = name.lowercase()
                    val target = when {
                        n.startsWith("report") -> Screen.REPORTS
                        n.startsWith("focus") || n.startsWith("dopamine") -> { taskSection = "focus"; Screen.TASKS }
                        n.startsWith("health") || n.startsWith("nutrition") || n.startsWith("sleep") || n.startsWith("water") -> { taskSection = "health"; Screen.TASKS }
                        n.startsWith("task") || n.startsWith("effic") -> { taskSection = "task"; Screen.TASKS }
                        n.startsWith("note") -> Screen.NOTES
                        n.startsWith("tool") || n.startsWith("timer") || n.startsWith("calc") || n.startsWith("calend") || n.startsWith("curren") || n.startsWith("alarm") -> { toolStart = n; Screen.TOOLS }
                        n.startsWith("look") || n.startsWith("font") || n.startsWith("compan") || n.startsWith("setting") || n.startsWith("perm") || n.startsWith("anim") -> Screen.SETTINGS
                        n.startsWith("hist") -> Screen.HISTORY
                        n.startsWith("face") || n.startsWith("voice") -> { faceListen = false; Screen.FACE }
                        n.startsWith("api") || n.startsWith("model") || n.startsWith("key") -> Screen.APIS
                        n == "me" || n.startsWith("vault") || n.startsWith("private") || n.startsWith("account") || n.startsWith("export") || n.startsWith("backup") -> Screen.ME
                        n.startsWith("music") || n.startsWith("player") || n.startsWith("song") -> { com.privacy.app.media.MusicPlayer.init(vm.g.context); com.privacy.app.media.MusicUi.focus.value = true; Screen.CHAT }
                        n.startsWith("telegram") -> Screen.ME
                        n.startsWith("auto") -> Screen.AUTOMATIONS
                        n.startsWith("about") || n.startsWith("privacy") -> Screen.ABOUT
                        else -> Screen.CHAT
                    }
                    go(target)
    }
    val pendingScreen by AppState.pendingScreen
    LaunchedEffect(pendingScreen) {
        pendingScreen?.let { openByName(it); AppState.pendingScreen.value = null }
    }
    // The assistant can open pages and show Android permission prompts through this bridge.
    LaunchedEffect(Unit) {
        UiBridge.commands.collect { c ->
            when (c) {
                is UiCommand.RequestPermission -> requestPermission(c.kind)
                is UiCommand.OpenScreen -> openByName(c.name)
            }
        }
    }

    BackHandler(enabled = screen != Screen.CHAT) { home() }
    BackHandler(enabled = drawerState.isOpen) { closeDrawer() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // opening is handled below (left half of the screen only); the built-in gesture stays on only for closing
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            AppDrawer(
                current = screen,
                onHome = { backStack.clear(); screen = Screen.CHAT; closeDrawer() },
                onNewChat = { vm.newChat(); backStack.clear(); screen = Screen.CHAT; closeDrawer() },
                onHistory = { go(Screen.HISTORY); closeDrawer() },
                onPick = { if (it == Screen.TASKS) taskSection = "task"; go(it); closeDrawer() }
            )
        }
    ) {
        val touch = remember { com.privacy.app.ui.face.TouchTracker() }
        Box(
            Modifier.fillMaxSize().background(colors.background)
            // swipe right starting in the LEFT HALF of the screen opens the menu (it watches the touch without taking it from the page)
            .pointerInput(drawerState) {
                while (true) awaitPointerEventScope {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    if (down.position.x > size.width / 2f || drawerState.isOpen || screen == Screen.FACE || AppState.tabCanGoPrev || AppState.blockMenuSwipe) return@awaitPointerEventScope
                    var dx = 0f; var dy = 0f
                    val slop = 56.dp.toPx()
                    while (true) {
                        val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        val ch = ev.changes.firstOrNull() ?: break
                        if (!ch.pressed) break
                        if (AppState.touchBlock > 0 || AppState.selecting) break
                        dx += ch.positionChange().x; dy += ch.positionChange().y
                        if (kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.4f && kotlin.math.abs(dy) > 24.dp.toPx()) break
                        if (dx > slop && dx > kotlin.math.abs(dy) * 1.6f) { scope.launch { drawerState.open() }; break }
                    }
                }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        e.changes.firstOrNull()?.let { touch.point = it.position; touch.at = System.currentTimeMillis() }
                    }
                }
            }
        ) {
            AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                },
                label = "screen"
            ) { s ->
                val padded: @Composable (@Composable () -> Unit) -> Unit = { body ->
                    Box(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) { body() }
                }
                when (s) {
                    Screen.CHAT -> ChatScreen(
                        vm,
                        openDrawer = { scope.launch { drawerState.open() } },
                        openFace = { listen -> faceListen = listen; go(Screen.FACE) },
                        openApis = { go(Screen.APIS) }
                    )
                    Screen.TASKS -> padded { TasksScreen(vm, onBack = home, initialSection = taskSection) }
                    Screen.REPORTS -> padded { com.privacy.app.ui.reports.ReportsScreen(vm, onBack = home) }
                    Screen.NOTES -> padded { NotesScreen(vm, onBack = home, onOpenGuide = { go(Screen.ABOUT) }) }
                    Screen.TOOLS -> padded { com.privacy.app.ui.tools.ToolsScreen(vm, onBack = home, startOn = when { toolStart.startsWith("alarm") -> "ALARM"; toolStart.startsWith("calc") -> "CALC"; toolStart.startsWith("calend") -> "CALENDAR"; toolStart.startsWith("curren") -> "CURRENCY"; else -> "TIMER" }) }
                    Screen.SETTINGS -> padded { com.privacy.app.ui.settings.SettingsPage(vm, onBack = home) }
                    Screen.HISTORY -> padded { HistoryScreen(vm, onBack = home, onOpen = { id -> vm.openChat(id); backStack.clear(); screen = Screen.CHAT }) }
                    Screen.FACE -> FaceScreen(vm, autoListen = faceListen, onClose = home)
                    Screen.APIS -> padded { ApisPage(vm, onBack = home) }
                    Screen.ME -> padded { com.privacy.app.ui.settings.AccountScreen(vm, onBack = home) }
                    Screen.AUTOMATIONS -> padded { AutomationsPage(vm, onBack = home) }
                    Screen.ABOUT -> padded { AboutPage(onBack = home) }
                }
            }
            CompanionOverlay(
                vm, com.privacy.app.ui.face.companionKeyOf(screen.name), touch,
                bottomPadding = if (screen == Screen.CHAT) 112 else 24,
                onFull = { faceListen = true; go(Screen.FACE) }
            )
        }
    }
    }
}

@Composable
private fun AppDrawer(current: Screen, onHome: () -> Unit, onNewChat: () -> Unit, onHistory: () -> Unit, onPick: (Screen) -> Unit) {
    val colors = MaterialTheme.colorScheme
    ModalDrawerSheet(drawerContainerColor = colors.background, modifier = Modifier.width(304.dp)) {
        Column(
            Modifier.fillMaxHeight().statusBarsPadding().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp, bottom = 16.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onHome).padding(4.dp)) {
                BrandLogo(32.dp)
                Spacer(Modifier.width(10.dp))
                Text(AppInfo.NAME, fontFamily = Mono, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 14.sp, letterSpacing = 2.sp)
            }
            // New chat  +  History, side by side
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = RoundedCornerShape(12.dp), color = colors.primary, modifier = Modifier.weight(1f).bounceClick(onClick = onNewChat)) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        Icon(Icons.Rounded.Add, null, tint = colors.onPrimary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("New chat", color = colors.onPrimary, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1)
                    }
                }
                Surface(shape = RoundedCornerShape(12.dp), color = colors.surfaceVariant, modifier = Modifier.bounceClick(onClick = onHistory)) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.History, null, tint = colors.onSurface, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("History", fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            // scrolls on small screens, so the pinned list below is never pushed off the bottom
            Column(Modifier.weight(1f).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                topEntries.forEachIndexed { i, e -> Appear(delayMs = 60 + i * 40) { DrawerRow(e, current == e.screen) { onPick(e.screen) } } }
            }

            HorizontalDivider(color = colors.outlineVariant, modifier = Modifier.padding(vertical = 10.dp))
            bottomEntries.forEachIndexed { i, e -> Appear(delayMs = 340 + i * 50) { DrawerRow(e, current == e.screen, big = true) { onPick(e.screen) } } }
        }
    }
}

@Composable
private fun DrawerRow(e: DrawerEntry, selected: Boolean, big: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.surfaceVariant else androidx.compose.ui.graphics.Color.Transparent)
            .bounceClick(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = if (big) 14.dp else 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(e.icon, null, tint = if (selected) colors.onSurface else colors.onSurfaceVariant, modifier = Modifier.size(if (big) 24.dp else 22.dp))
        Spacer(Modifier.width(14.dp))
        Text(e.label, fontSize = if (big) 17.sp else 15.sp, fontWeight = if (selected || big) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal)
    }
}
