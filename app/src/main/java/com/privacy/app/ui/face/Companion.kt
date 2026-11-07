@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.privacy.app.ui.face

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import com.privacy.app.ui.noDrawerSwipe
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privacy.app.data.TaskStatus
import com.privacy.app.ui.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Where the user last touched the screen (root coordinates); the companion looks that way. null = nothing recent. */
class TouchTracker { var point by mutableStateOf<Offset?>(null); var at by mutableStateOf(0L) }

/** The screen group a page belongs to, for the "where should the companion appear" setting. */
fun companionKeyOf(screenName: String): String = when (screenName) {
    "CHAT" -> "chat"; "APIS" -> "apis"; "TASKS" -> "tasks"; "NOTES" -> "notes"; "TOOLS" -> "none"; "REPORTS" -> "reports"
    "FACE" -> "none"
    else -> "settings"
}

/**
 * A small pixel assistant with a personality (see [CompanionBrain]). It looks at where you touch, reacts to what happens, wanders around the
 * screen, sings, plays rock-paper-scissors, gets dizzy when you shake the phone and starts conversations about your tasks, notes and memories.
 * Tap its comment to answer: those talks are saved in their own chat (History), never in the chat window. Drag it anywhere; drop it off-screen to hide it.
 * Tap it in chat for the full-screen assistant, elsewhere for a precise suggestion.
 */
@Composable
fun BoxScope.CompanionOverlay(vm: MainViewModel, screenKey: String, touch: TouchTracker, bottomPadding: Int, onFull: () -> Unit) {
    val s = vm.g.settings
    val enabled by s.companionOn.collectAsStateWithLifecycle()
    val where by s.companionWhere.collectAsStateWithLifecycle()
    val side by s.companionSide.collectAsStateWithLifecycle()
    val call by s.userCall.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    var hidden by rememberSaveable { mutableStateOf(false) }
    var comment by remember { mutableStateOf("") }
    var gesture by remember { mutableStateOf(Gesture.NONE) }
    var gestureKey by remember { mutableIntStateOf(0) }
    var mood by remember { mutableStateOf(FaceMood.NEUTRAL) }
    var center by remember { mutableStateOf(Offset.Zero) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var replying by remember { mutableStateOf(false) }
    var replyText by remember { mutableStateOf("") }
    var replyBusy by remember { mutableStateOf(false) }
    var rps by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var wobble by remember { mutableStateOf(0f) }
    var shakeKey by remember { mutableIntStateOf(0) }
    var lastAiAt by remember { mutableStateOf(0L) }
    var convoId by remember { mutableStateOf(0L) }
    val enteredAt = remember { System.currentTimeMillis() }
    // while music plays it sings along: mouth moving, swaying, little notes floating up, a cheer now and then
    val singing by com.privacy.app.media.MusicPlayer.playing.collectAsStateWithLifecycle()
    var beat by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    LaunchedEffect(singing) {
        if (!singing) return@LaunchedEffect
        mood = FaceMood.HAPPY
        var n = 0
        while (true) {
            beat += 0.12f
            if (n % 220 == 0) { gesture = listOf(Gesture.CHEER, Gesture.CLAP, Gesture.HEART).random(); gestureKey++ }
            n++; delay(33)
        }
    }
    LaunchedEffect(singing) { if (!singing && mood == FaceMood.HAPPY) mood = FaceMood.NEUTRAL }

    val ctx0 = androidx.compose.ui.platform.LocalContext.current
    var commentUrl by remember { mutableStateOf<String?>(null) }
    fun react(text: String, g: Gesture, m: FaceMood = FaceMood.NEUTRAL) { comment = text; commentUrl = null; gesture = g; gestureKey++; mood = m }
    /** Now and then (at most once every 3 days) it asks for a GitHub star or a little support; tapping the bubble opens the page. */
    fun maybeSupportNudge(chance: Int): Boolean {
        val sp = ctx0.getSharedPreferences("dh_support", android.content.Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - sp.getLong("last", 0L) < 3 * 24 * 3600_000L || kotlin.random.Random.nextInt(100) >= chance) return false
        sp.edit().putLong("last", now).apply()
        val star = sp.getBoolean("star_next", true)
        sp.edit().putBoolean("star_next", !star).apply()
        if (star) { react("If you really like this app, please give it a star on GitHub ⭐ (tap here to open the page)", Gesture.HEART, FaceMood.LOVE); commentUrl = com.privacy.app.AppInfo.STAR_URL }
        else { react("If this app feels perfect for you, please support and fund the project ☕ (tap here to open the page)", Gesture.PRESENT, FaceMood.HAPPY); commentUrl = com.privacy.app.AppInfo.FUND_URL }
        return true
    }

    val visible = enabled && screenKey in where
    if (!visible) return

    // finished tasks
    val doneCount = tasks.count { it.status == TaskStatus.COMPLETED }
    val openCount = tasks.count { it.status != TaskStatus.COMPLETED }
    var lastDone by remember { mutableIntStateOf(-1) }
    val startedAt = remember { System.currentTimeMillis() }
    LaunchedEffect(doneCount, openCount) {
        // the task list loads a moment after the app opens – that is not "something was just finished"
        if (System.currentTimeMillis() - startedAt < 5000L) { lastDone = doneCount; return@LaunchedEffect }
        if (lastDone >= 0 && doneCount > lastDone && !hidden && !replying) {
            val cheer = listOf("Boom. One less.", "That felt good, didn't it?", "Look at you go!", "Tick. Satisfying.", "Smooth, $call.", "And the crowd goes wild.")
            if (openCount == 0) react("Every last one, $call. Brilliant.", Gesture.HEART, FaceMood.HAPPY)
            else if (kotlin.random.Random.nextInt(100) < 60) react(cheer.random(), Gesture.THUMBS_UP, FaceMood.HAPPY)
        }
        lastDone = doneCount
    }
    // when the app opens: something energetic (a joke, a hype line) – not a greeting, not a task list
    LaunchedEffect(Unit) {
        delay(2600)
        if (hidden || replying || comment.isNotBlank()) return@LaunchedEffect
        if (maybeSupportNudge(15)) return@LaunchedEffect
        if (kotlin.random.Random.nextBoolean()) react(CompanionBrain.hype(), listOf(Gesture.CHEER, Gesture.WAVE, Gesture.PRESENT).random(), FaceMood.HAPPY)
        else react(CompanionBrain.joke(), Gesture.SHRUG, FaceMood.WINK)
    }
    // answers
    LaunchedEffect(Unit) {
        vm.replies.collect { text ->
            if (hidden || replying) return@collect
            val m = moodOf(text)
            when {
                m == FaceMood.SAD -> react("Hmm, that didn't go well, $call.", Gesture.THINK, m)
                Regex("\\b(done|added|saved|created|set)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> react("Done, $call.", Gesture.THUMBS_UP, FaceMood.HAPPY)
                text.trimEnd().endsWith("?") -> react("", Gesture.POINT, FaceMood.SURPRISED)
                else -> { mood = m; gesture = Gesture.NONE }
            }
        }
    }
    // working on something big
    LaunchedEffect(busy) { if (busy && !hidden && !replying) react("On it, $call…", Gesture.TYPE, FaceMood.NEUTRAL) }
    // new screen
    LaunchedEffect(screenKey) {
        delay(900)
        if (hidden || replying) return@LaunchedEffect
        when (screenKey) {
            "apis" -> react("Your keys stay on this phone, $call.", Gesture.PRESENT)
            "tasks" -> react(listOf("Pixels and percentages. My favourite.", "Tap me if you want my honest opinion.").random(), Gesture.POINT)
            "tools" -> react(listOf("Tick-tock. I wonder what you're timing.", "Tap me for my honest opinion.").random(), Gesture.PRESENT)
            "notes" -> react(listOf("Ooh, thoughts. Write the good ones.", "Tap me and I'll judge your notes. Kindly. Ish.").random(), Gesture.WAVE)
        }
    }
    // the comment goes away by itself – longer text stays longer – unless you are replying or playing
    LaunchedEffect(comment, gestureKey) {
        if (comment.isNotBlank() && !replying && !rps) { delay(4200L + comment.length * 45L); if (!replying && !rps) comment = "" }
    }
    LaunchedEffect(touch.at) { delay(5000); now = System.currentTimeMillis() }

    val cfg = LocalConfiguration.current
    val look = touch.point?.takeIf { System.currentTimeMillis() - touch.at < 5000 || now - touch.at < 5000 }
    val lx = look?.let { ((it.x - center.x) / (cfg.screenWidthDp * 1.6f)).coerceIn(-1f, 1f) }
    val ly = look?.let { ((it.y - center.y) / (cfg.screenHeightDp * 1.6f)).coerceIn(-1f, 1f) }

    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("companion_pos", 0) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val boxPx = with(density) { 112.dp.toPx() }
    val bubbleW = with(density) { 210.dp.toPx() }
    val screenW = with(density) { cfg.screenWidthDp.dp.toPx() }
    val screenH = with(density) { cfg.screenHeightDp.dp.toPx() }
    // position of the top-left corner in px; saved as fractions so it survives rotation
    var pos by remember {
        mutableStateOf(
            Offset(
                prefs.getFloat("fx", if (side == "left") 0.02f else 0.98f - boxPx / screenW) * screenW,
                prefs.getFloat("fy", 0.62f) * screenH
            )
        )
    }
    var bubbleH by remember { mutableIntStateOf(0) }
    var helping by remember { mutableStateOf(false) }

    suspend fun convo(): Long { if (convoId <= 0) convoId = CompanionBrain.ensureConvo(vm.g); return convoId }

    suspend fun moveTo(target: Offset, ms: Int = 2200) {
        moving = true
        val from = pos
        gesture = listOf(Gesture.WAVE, Gesture.CHEER, Gesture.POINT).random(); gestureKey++
        animate(0f, 1f, animationSpec = tween(ms)) { v, _ -> pos = Offset(from.x + (target.x - from.x) * v, from.y + (target.y - from.y) * v) }
        moving = false
        prefs.edit().putFloat("fx", pos.x / screenW).putFloat("fy", pos.y / screenH).apply()
    }
    fun randomSpot() = Offset(
        (screenW * (0.04f + 0.7f * kotlin.random.Random.nextFloat())).coerceAtMost(screenW - boxPx - 8f),
        screenH * (0.14f + 0.55f * kotlin.random.Random.nextFloat())
    )

    fun askHelp() {
        if (helping || busy) return
        helping = true
        if (maybeSupportNudge(6)) { helping = false; return }
        react("Hmm…", Gesture.THINK, FaceMood.NEUTRAL)
        scope.launch {
            val screenName = when (screenKey) { "tasks" -> "Task & Health"; "notes" -> "Notes"; "tools" -> "Tools"; "chat" -> "Chat"; "apis" -> "API keys"; "reports" -> "Reports"; else -> "Settings" }
            val txt = CompanionBrain.opinion(vm.g, call, screenKey, screenName)
            helping = false
            react(txt, listOf(Gesture.POINT, Gesture.THINK, Gesture.PRESENT).random(), FaceMood.HAPPY)
            val id = convo()
            CompanionBrain.record(vm.g, id, "assistant", "📎 Started from: you tapped me on the $screenName screen\n\n$txt")
        }
    }

    fun startAiTalk() {
        if (helping || busy) return
        scope.launch {
            if (!com.privacy.app.ai.Engagement.ok(vm.g)) return@launch
            val aiOk = CompanionBrain.aiAvailable(vm.g) && System.currentTimeMillis() - lastAiAt >= 150_000L
            val working = when (screenKey) { "tasks" -> "looking at the Tasks screen"; "notes" -> "looking at Notes"; "chat" -> "chatting with the assistant"; else -> "" }
            val st = if (aiOk) { lastAiAt = System.currentTimeMillis(); CompanionBrain.starter(vm.g, call, working) } else null
            val line = st?.line ?: CompanionBrain.chirp(vm.g, call)
            react(line, listOf(Gesture.POINT, Gesture.WAVE, Gesture.THINK, Gesture.PRESENT, Gesture.HEART).random(), listOf(FaceMood.WINK, FaceMood.HAPPY, FaceMood.SURPRISED, FaceMood.LOVE, FaceMood.CONFUSED).random())
            val id = convo()
            CompanionBrain.record(vm.g, id, "assistant", "📎 Started from: ${st?.reference ?: "a quick thought"}\n\n$line")
        }
    }

    fun sendReply() {
        val t = replyText.trim()
        if (t.isEmpty() || replyBusy) return
        replyText = ""; replyBusy = true
        scope.launch {
            val id = convo()
            CompanionBrain.record(vm.g, id, "user", t)
            react("…", Gesture.THINK, FaceMood.NEUTRAL)
            val ans = CompanionBrain.reply(vm.g, id, call, t) ?: "My little brain is napping for a minute, $call. Say it again soon? 💤"
            CompanionBrain.record(vm.g, id, "assistant", ans)
            replyBusy = false
            react(ans, listOf(Gesture.POINT, Gesture.SHRUG, Gesture.THUMBS_UP).random(), moodOf(ans))
        }
    }

    fun playRps(me: Int) {
        val names = listOf("rock ✊", "paper ✋", "scissors ✌️")
        val bot = (0..2).random()
        val res = when {
            bot == me -> Triple("We both picked ${names[me]}. Boring. Again?", FaceMood.CONFUSED, Gesture.SHRUG)
            (me - bot + 3) % 3 == 1 -> Triple("${names[bot]} loses to your ${names[me]}. Fine. You win. I'm not sulking.", FaceMood.SAD, Gesture.SHRUG)
            else -> Triple("My ${names[bot]} beats your ${names[me]}! Hah. Say it: I'm the best.", FaceMood.HAPPY, Gesture.CHEER)
        }
        rps = false
        react(res.first, res.third, res.second)
        scope.launch {
            val id = convo()
            CompanionBrain.record(vm.g, id, "assistant", "📎 Started from: Rock-paper-scissors\n\nI challenged you.")
            CompanionBrain.record(vm.g, id, "user", "I picked ${names[me]}")
            CompanionBrain.record(vm.g, id, "assistant", res.first)
        }
    }

    // random behaviour: it pokes you often – talks about tasks, notes and memories, sings, wanders, plays, sulks
    LaunchedEffect(hidden, screenKey) {
        if (hidden) return@LaunchedEffect
        delay(12_000)
        while (true) {
            delay((28_000L..65_000L).random())
            val idle = System.currentTimeMillis() - touch.at > 5_000
            if (hidden || busy || replying || rps || helping || moving || !idle || comment.isNotBlank()) continue
            when ((0..11).random()) {
                0, 1, 2, 3, 4, 5 -> startAiTalk()
                6 -> react(listOf("♪ La la la… I'm a little pixel, short and bright ♪", "♪ Tick-tock goes the clock, and I'm bored ♪", "♪ Oh, procrastination, my favourite ♪").random(), Gesture.CHEER, FaceMood.HAPPY)
                7 -> { rps = true; react("Rock, paper, scissors? Pick one, $call!", Gesture.PRESENT, FaceMood.WINK) }
                8 -> react(listOf("Sometimes I wonder if you'd notice if I vanished. …Don't answer that.", "I'm weirdly proud of you today, $call.", "I get lonely when you leave me on this screen.", "Honestly, I missed you.").random(), listOf(Gesture.SHRUG, Gesture.HEART).random(), listOf(FaceMood.SAD, FaceMood.LOVE).random())
                9 -> { moveTo(randomSpot()); react(listOf("Oops, in your way? Good.", "I live here now.", "Don't mind me, just supervising.").random(), Gesture.POINT, FaceMood.WINK) }
                10 -> { moveTo(randomSpot(), 2800); react("Wheee!", Gesture.CHEER, FaceMood.HAPPY) }
                else -> startAiTalk()
            }
        }
    }

    // dizzy: from shaking the phone or from dragging the assistant around fast
    var tilt by remember { mutableStateOf(0f) }
    var orbit by remember { mutableStateOf(0f) }
    var dizzyOn by remember { mutableStateOf(false) }
    val dizzyCool = remember { longArrayOf(0L) }
    fun triggerDizzy() {
        val t = System.currentTimeMillis()
        if (hidden || replying || t < dizzyCool[0] || t - enteredAt < 6000) return
        dizzyCool[0] = t + 6000
        val (line, m) = listOf(
            "Whoa, I'm feeling dizzy…" to FaceMood.CONFUSED,
            "Stop! Everything is spinning!" to FaceMood.ANGRY,
            "Earthquake?! Hold on to something!" to FaceMood.SURPRISED,
            "Ugh… I think I'm going to be sick. In pixels." to FaceMood.SAD
        ).random()
        rps = false
        react(line, Gesture.SHRUG, m)
        shakeKey++
    }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(android.content.Context.SENSOR_SERVICE) as? android.hardware.SensorManager
        val sensor = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        var hits = 0; var first = 0L; var lastHit = 0L
        val grav = floatArrayOf(0f, 0f, 9.81f)
        val l = object : android.hardware.SensorEventListener {
            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                // remove gravity with a low-pass filter, so only real movement counts
                for (i in 0..2) grav[i] = 0.9f * grav[i] + 0.1f * e.values[i]
                val lx = e.values[0] - grav[0]; val ly = e.values[1] - grav[1]; val lz = e.values[2] - grav[2]
                val mag = kotlin.math.sqrt(lx * lx + ly * ly + lz * lz)
                val t = System.currentTimeMillis()
                if (mag > 9f && t - lastHit > 90) {
                    lastHit = t
                    if (t - first > 1500) { hits = 0; first = t }
                    hits++
                    if (hits >= 3) { hits = 0; triggerDizzy() }
                }
            }
        }
        if (sensor != null) sm.registerListener(l, sensor, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm?.unregisterListener(l) }
    }
    LaunchedEffect(shakeKey) {
        if (shakeKey == 0) return@LaunchedEffect
        dizzyOn = true
        val amp = with(density) { 9.dp.toPx() }
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 2800) {
            val t = (System.currentTimeMillis() - start) / 1000f
            val fade = 1f - t / 2.8f
            wobble = kotlin.math.sin(t * 20f) * amp * fade
            tilt = kotlin.math.sin(t * 13f) * 16f * fade
            orbit = t * 7f
            delay(16)
        }
        wobble = 0f; tilt = 0f; dizzyOn = false
    }
    val inkColor = MaterialTheme.colorScheme.onBackground
    val dragState = remember { longArrayOf(0L, 0L) } // [0] fast-drag milliseconds collected, [1] window start

    if (!hidden) {
        // the assistant: a dark disc with a deep shadow keeps it visible on any page
        Box(
            Modifier
                .offset { IntOffset((pos.x + wobble).roundToInt(), pos.y.roundToInt()) }
                .size(112.dp)
                .onGloballyPositioned { c -> center = c.positionInRoot() + Offset(c.size.width / 2f, c.size.height * 0.3f) }
                .noDrawerSwipe()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = {
                            // hides as soon as about half of it (the visible pixels, not the empty margin) is pushed outside any screen edge
                            val out = 0.40f * boxPx
                            val off = pos.x < -out || pos.x > screenW - boxPx + out || pos.y < -out || pos.y > screenH - boxPx + out
                            if (off) hidden = true
                            else prefs.edit().putFloat("fx", pos.x / screenW).putFloat("fy", pos.y / screenH).apply()
                        },
                        onDrag = { change, d ->
                            change.consume(); pos = Offset(pos.x + d.x, pos.y + d.y)
                            // fast dragging for a moment makes it dizzy
                            val dt = (change.uptimeMillis - change.previousUptimeMillis).coerceAtLeast(1L)
                            val v = d.getDistance() / dt
                            val now2 = change.uptimeMillis
                            if (now2 - dragState[1] > 2000) { dragState[0] = 0L; dragState[1] = now2 }
                            if (v > 1.4f) dragState[0] += dt
                            if (dragState[0] > 500L) { dragState[0] = 0L; triggerDizzy() }
                        }
                    )
                }
                .combinedClickable(
                    indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    onLongClick = { onFull() }, onClick = { askHelp() }
                ),
            contentAlignment = Alignment.Center
        ) {
            // a very soft halo (no hard edge, no oval shadow) so the assistant stays readable on busy pages
            Box(
                Modifier.size(104.dp).background(
                    androidx.compose.ui.graphics.Brush.radialGradient(listOf(MaterialTheme.colorScheme.background.copy(alpha = 0.55f), Color.Transparent)), CircleShape
                )
            )
            Box(Modifier.size(112.dp).graphicsLayer {
                rotationZ = tilt + (if (singing) kotlin.math.sin(beat * 2f) * 5f else 0f)
                translationY = if (singing) -kotlin.math.abs(kotlin.math.sin(beat * 2f)) * 8.dp.toPx() else 0f
            }, contentAlignment = Alignment.Center) {
            PixelAvatar(
                size = 112.dp, color = MaterialTheme.colorScheme.onBackground, assemble = true, fps = 24,
                state = if (busy || helping || replyBusy) AvatarState.THINKING else if (singing) AvatarState.TALKING else AvatarState.IDLE,
                gesture = gesture, gestureKey = gestureKey, mood = mood, autoGestures = false,
                lookX = lx, lookY = ly
            )
            }
            if (singing) {
                // notes drift up from the head
                androidx.compose.foundation.layout.Box(Modifier.size(112.dp)) {
                    listOf("♪" to 0f, "♫" to 0.5f, "♪" to 0.25f).forEachIndexed { i, (ch, ph) ->
                        val k = (beat * 0.35f + ph) % 1f
                        Text(ch, color = inkColor.copy(alpha = 1f - k), fontSize = 18.sp,
                            modifier = Modifier.offset(x = (14 + i * 34 + kotlin.math.sin((beat + i) * 2f) * 6f).dp, y = (30 - k * 36).dp))
                    }
                }
            }
            if (dizzyOn) {
                // little pixel stars circling the head
                androidx.compose.foundation.Canvas(Modifier.size(112.dp)) {
                    val cx = size.width / 2f; val cy = size.height * 0.16f
                    for (i in 0 until 4) {
                        val a = orbit + i * (kotlin.math.PI.toFloat() / 2f)
                        val x = cx + kotlin.math.cos(a) * size.width * 0.30f
                        val y = cy + kotlin.math.sin(a) * size.height * 0.07f
                        val s2 = size.width * 0.045f
                        drawRect(inkColor.copy(alpha = 0.9f), Offset(x - s2, y - s2), androidx.compose.ui.geometry.Size(s2 * 2, s2 * 2))
                    }
                }
            }
        }

        // the comment floats above (or below) the assistant in its own layer, so it never moves the assistant
        if (comment.isNotBlank() && !replying) {
            val bx = (pos.x + boxPx / 2f - bubbleW / 2f).coerceIn(8f, (screenW - bubbleW - 8f).coerceAtLeast(8f))
            val above = pos.y - bubbleH - 6f
            val by = if (above >= 8f) above else pos.y + boxPx + 6f
            Surface(
                shape = RoundedCornerShape(16.dp), color = Color(0xF2202020), shadowElevation = 10.dp,
                modifier = Modifier
                    .offset { IntOffset(bx.roundToInt(), by.roundToInt()) }
                    .width(210.dp)
                    .onSizeChanged { bubbleH = it.height }
                    .clickable(enabled = !rps) {
                        val u = commentUrl
                        if (u != null) { runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }; comment = ""; commentUrl = null } else replying = true
                    }
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(comment, color = Color.White, fontSize = 13.sp, lineHeight = 17.sp)
                    if (rps) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly) {
                            listOf("✊", "✋", "✌️").forEachIndexed { i, e -> TextButton(onClick = { playRps(i) }) { Text(e, fontSize = 24.sp) } }
                        }
                    } else if (comment.length > 3 && comment != "Thinking…" && comment != "…") {
                        Text(if (commentUrl != null) "tap to open" else "tap to reply", color = Color(0x99FFFFFF), fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }

    // reply panel: sits above the keyboard
    if (replying) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(150); runCatching { focus.requestFocus() } }
        Surface(
            shape = RoundedCornerShape(20.dp), color = Color(0xF5202020), shadowElevation = 14.dp,
            modifier = Modifier.align(Alignment.BottomCenter).imePadding().padding(12.dp).fillMaxWidth()
        ) {
            Column(Modifier.padding(12.dp)) {
                if (comment.isNotBlank()) Text(comment, color = Color.White, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(bottom = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = replyText, onValueChange = { replyText = it }, modifier = Modifier.weight(1f).focusRequester(focus),
                        placeholder = { Text("Answer me…", color = Color(0x88FFFFFF)) }, maxLines = 3,
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0x88FFFFFF), unfocusedBorderColor = Color(0x44FFFFFF), cursorColor = Color.White
                        )
                    )
                    IconButton(onClick = { sendReply() }, enabled = replyText.isNotBlank() && !replyBusy) {
                        Icon(Icons.Rounded.ArrowUpward, "Send", tint = Color.White)
                    }
                    IconButton(onClick = { replying = false; comment = "" }) { Icon(Icons.Rounded.Close, "Close", tint = Color(0xAAFFFFFF)) }
                }
                Text("This talk is saved in History as “Assistant chat”.", color = Color(0x77FFFFFF), fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    if (hidden) {
        // a small tab on the edge nearest to where it was dropped
        val left = pos.x + boxPx / 2f < screenW / 2f
        val ty = pos.y.coerceIn(screenH * 0.15f, screenH * 0.8f)
        Surface(
            shape = RoundedCornerShape(topStart = if (left) 0.dp else 14.dp, bottomStart = if (left) 0.dp else 14.dp, topEnd = if (left) 14.dp else 0.dp, bottomEnd = if (left) 14.dp else 0.dp),
            color = Color(0xCC202020),
            modifier = Modifier.align(if (left) Alignment.TopStart else Alignment.TopEnd).offset { IntOffset(0, ty.roundToInt()) }
                .clickable {
                    hidden = false
                    pos = Offset(if (left) 8f else screenW - boxPx - 8f, ty.coerceAtMost(screenH - boxPx - 16f))
                    prefs.edit().putFloat("fx", pos.x / screenW).putFloat("fy", pos.y / screenH).apply()
                }
        ) { Text("◖◗", color = Color.White, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 14.dp)) }
    }
}
