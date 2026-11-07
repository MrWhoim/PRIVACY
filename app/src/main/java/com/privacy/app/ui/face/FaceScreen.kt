package com.privacy.app.ui.face

import kotlinx.coroutines.delay
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privacy.app.ui.MainViewModel
import com.privacy.app.ui.findActivity
import com.privacy.app.ui.theme.Mono
import com.privacy.app.voice.Speaker
import com.privacy.app.voice.VoiceInput
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Talk to a pixel person. You speak (tap the mic, tap again to send), the assistant answers, and the face reads the answer
 * aloud with expressions, blinking and lip-sync. Pure white pixels on black.
 */
@Composable
fun FaceScreen(vm: MainViewModel, autoListen: Boolean = false, overlay: Boolean = false, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val busy by vm.busy.collectAsStateWithLifecycle()
    val agent by vm.agentActive.collectAsStateWithLifecycle()
    val anim = remember { FaceAnimator() }
    val scratch = remember { PixelFace.Scratch() }
    val female = LocalAssistantFemale.current
    val speaker = remember { Speaker(vm.g) }
    val voice = remember { VoiceInput(vm.g) }
    var transcribing by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var heard by remember { mutableStateOf("") }
    var subtitle by remember { mutableStateOf("") }
    var frame by remember { mutableLongStateOf(0L) }
    val speaking by speaker.speaking.collectAsStateWithLifecycle()
    val word by speaker.word.collectAsStateWithLifecycle()
    val voiceLevel by speaker.level.collectAsStateWithLifecycle()
    val notice by speaker.notice.collectAsStateWithLifecycle()

    // white status-bar icons on the black screen
    DisposableEffect(Unit) {
        if (overlay) return@DisposableEffect onDispose { }
        val window = ctx.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val old = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose { if (controller != null && old != null) controller.isAppearanceLightStatusBars = old }
    }
    DisposableEffect(Unit) { onDispose { speaker.shutdown(); voice.destroy() } }

    val listener = remember {
        object : VoiceInput.Listener {
            override fun onListening(on: Boolean) { listening = on }
            override fun onPartial(text: String) { heard = text }
            override fun onLevel(level: Float) { anim.level = level }
            override fun onProcessing(on: Boolean) { transcribing = on }
            override fun onFinal(text: String) {
                listening = false; anim.level = 0f
                if (text.isNotBlank()) { heard = text; vm.send(text, spoken = true) }
            }
            override fun onError(message: String) {
                listening = false; anim.level = 0f
                Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) voice.start(listener, autoStop = true) else Toast.makeText(ctx, "Microphone permission is needed to talk.", Toast.LENGTH_SHORT).show()
    }
    fun startListening() {
        subtitle = ""
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) voice.start(listener, autoStop = true)
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun micTap() {
        when {
            speaking -> speaker.stop()
            listening -> voice.stop()
            busy || transcribing -> vm.stop()
            else -> startListening()
        }
    }

    // The pixels assemble into the face; when opened with the chat's mic button it starts listening right after.
    val assemble = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        anim.play(Gesture.WAVE, 2.4f)
        assemble.animateTo(1f, androidx.compose.animation.core.tween(1100, easing = androidx.compose.animation.core.LinearEasing))
        if (autoListen) startListening()
    }
    // While an answer is on screen the face shrinks a little so the text fits, but the whole face stays visible.
    val answering = subtitle.isNotBlank() && !listening
    val shrink by androidx.compose.animation.core.animateFloatAsState(
        if (answering) 1f else 0f, androidx.compose.animation.core.tween(450), label = "shrink"
    )

    // the assistant's answers are read aloud by the face
    LaunchedEffect(Unit) {
        vm.replies.collect { text ->
            subtitle = Speaker.plainSpeech(text)
            anim.mood = moodOf(text)
            gestureFor(text)?.let { anim.play(it, 2.8f) }
            speaker.speak(text, vm.g.settings.voiceLang.value)
        }
    }
    // From the home-screen widget the talk is a conversation: when the answer has been spoken, the mic opens again by itself.
    var spokeOnce by remember { mutableStateOf(false) }
    LaunchedEffect(speaking) {
        if (speaking) spokeOnce = true
        else if (overlay && spokeOnce) {
            spokeOnce = false
            delay(650)
            if (!listening && !busy && !transcribing) startListening()
        }
    }
    LaunchedEffect(word.seq) { anim.onWord(word.word, word.seq) }
    LaunchedEffect(voiceLevel) { anim.voiceLevel = voiceLevel }
    LaunchedEffect(transcribing) { anim.processing = transcribing }
    LaunchedEffect(listening, busy, speaking, transcribing) {
        anim.mode = when {
            listening -> FaceMode.LISTENING
            speaking -> FaceMode.SPEAKING
            busy || transcribing -> FaceMode.THINKING
            else -> FaceMode.IDLE
        }
        if (speaking) anim.beginSpeaking()
        if (!speaking && !busy && !listening && !transcribing) anim.mood = FaceMood.NEUTRAL
    }
    // 60 fps animation clock
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { n ->
                if (last != 0L) anim.update(((n - last) / 1_000_000_000f).coerceAtMost(0.05f))
                last = n
                frame = n
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(if (overlay) Color.Transparent else Color.Black)
            .then(if (overlay) Modifier.pointerInput(Unit) {
                // overlay: swipe down anywhere (or tap above the assistant) to dismiss
                var drag = 0f
                detectVerticalDragGestures(
                    onDragStart = { drag = 0f }, onDragEnd = { if (drag > 120f) onClose() }, onDragCancel = { drag = 0f },
                    onVerticalDrag = { _, dy -> drag += dy }
                )
            } else Modifier)
    ) {
        // ---------------- the face ----------------
        Canvas(Modifier.fillMaxSize()) {
            if (frame < 0) return@Canvas // reading `frame` makes this redraw every tick
            val topPad = if (overlay) size.height * 0.30f else 56.dp.toPx()
            val faceH = size.height * (1f - 0.34f * shrink)
            if (overlay) drawRect(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.62f), Color.Transparent), center = Offset(size.width / 2f, size.height * 0.62f), radius = size.width * 0.95f))
            drawAssistant(
                scratch, anim, female, Color.White,
                reveal = assemble.value * 1.25f, withAura = true,
                left = 0f, top = topPad, width = size.width, height = faceH - topPad
            )
            if (overlay) return@Canvas
            // CRT scanlines + vignette
            val line = 1.dp.toPx()
            var y = 0f
            while (y < size.height) {
                drawRect(Color.Black.copy(alpha = 0.16f), Offset(0f, y), Size(size.width, line))
                y += 3.dp.toPx()
            }
            drawRect(
                Brush.radialGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                    center = Offset(size.width / 2f, size.height * 0.42f), radius = max(size.width, size.height) * 0.72f
                )
            )
        }

        // ---------------- top bar ----------------
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
            Spacer(Modifier.weight(1f))
            val label = when {
                listening -> "LISTENING"
                transcribing -> "HEARING YOU"
                speaking -> "SPEAKING"
                busy -> if (agent) "WORKING ON A BIG TASK" else "THINKING"
                else -> "READY"
            }
            Text(label, color = Color.White.copy(alpha = 0.7f), fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.6.sp)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(48.dp))
        }

        // voice notice: small and quiet, at the top – never over the figure
        notice?.takeIf { it.isNotBlank() }?.let { note ->
            Text(
                note, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, textAlign = TextAlign.Center, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 50.dp, start = 40.dp, end = 40.dp)
            )
        }

        // ---------------- bottom: subtitles + mic ----------------
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (heard.isNotBlank()) {
                Text("“${heard.take(90)}”", color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp, fontFamily = Mono, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (subtitle.isNotBlank()) {
                Box(
                    Modifier.heightIn(max = 150.dp)
                        .then(if (overlay) Modifier.background(Color(0xCC000000), RoundedCornerShape(16.dp)).padding(12.dp) else Modifier)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(subtitle, color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 21.sp)
                }
            } else if (!busy && !listening && !transcribing) {
                Text("Tap the mic and talk, Sir. I'll answer when you pause.", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(4.dp))
            Pixels(anim, listening, speaking)
            Box(
                Modifier.size(68.dp).clip(CircleShape)
                    .background(if (listening) Color.White else Color.Transparent)
                    .border(2.dp, Color.White, CircleShape)
                    .clickable { micTap() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (listening || speaking || busy || transcribing) Icons.Rounded.Stop else Icons.Rounded.Mic, "Mic",
                    tint = if (listening) Color.Black else Color.White, modifier = Modifier.size(30.dp)
                )
            }
        }
    }
}

/** A small equaliser made of white squares; reacts to your voice or to the face's voice. */
@Composable
private fun Pixels(anim: FaceAnimator, listening: Boolean, speaking: Boolean) {
    Canvas(Modifier.fillMaxWidth().height(34.dp)) {
        val cols = 26
        val rows = 5
        val cell = min(size.width / cols, size.height / rows)
        val ox = (size.width - cell * cols) / 2f
        val gap = cell * 0.14f
        val amp = when {
            listening -> 0.15f + anim.level * 0.85f
            speaking -> 0.15f + anim.p.mouthOpen * 0.85f
            else -> 0.05f
        }
        for (c in 0 until cols) {
            val wave = 0.55f + 0.45f * sin(anim.time * 7f + c * 0.8f) * sin(anim.time * 3.1f + c * 0.35f)
            val h = max(1, (rows * amp * wave).roundToInt()).coerceAtMost(rows)
            for (r in 0 until h) {
                drawRect(
                    Color.White.copy(alpha = if (amp > 0.1f) 0.9f else 0.3f),
                    Offset(ox + c * cell + gap, size.height - (r + 1) * cell + gap),
                    Size(cell - gap * 2, cell - gap * 2)
                )
            }
        }
    }
}


/** A fitting hand gesture for what the assistant is about to say. */
private fun gestureFor(text: String): Gesture? {
    val t = text.lowercase()
    return when {
        Regex("congrat|awesome|great job|🎉|fantastic|amazing").containsMatchIn(t) -> Gesture.CHEER
        Regex("well done|nicely done|bravo|good job").containsMatchIn(t) -> Gesture.CLAP
        Regex("don't know|do not know|not sure|no idea|couldn't find|can't find").containsMatchIn(t) -> Gesture.SHRUG
        Regex("\\b(love|thank you|thanks)\\b|❤").containsMatchIn(t) -> Gesture.HEART
        t.contains("?") && t.length < 160 -> Gesture.THINK
        Regex("\\b(sorry|apolog)").containsMatchIn(t) -> Gesture.PRESENT
        Regex("\\b(done|completed|saved|created|finished|all set)\\b").containsMatchIn(t) -> Gesture.THUMBS_UP
        Regex("\\b(hello|hi|good (morning|evening|afternoon)|welcome)\\b").containsMatchIn(t) -> Gesture.WAVE
        else -> null
    }
}
