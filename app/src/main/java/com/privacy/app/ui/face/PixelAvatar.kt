package com.privacy.app.ui.face

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import kotlin.math.min

/** The small, shared pixel assistant: its own animator and clock. Used in the chat, the splash, lists and the companion. */
enum class AvatarState { IDLE, THINKING, TALKING }

object AvatarFrames {
    fun hash(x: Int, y: Int): Float {
        var n = x * 374761393 + y * 668265263
        n = (n xor (n ushr 13)) * 1274126177
        return ((n xor (n ushr 16)) and 0xFFFF) / 65535f
    }
}

/**
 * [assemble]: the pixels fly in one by one when it first appears.
 * [state]: IDLE blinks, THINKING looks up and aside, TALKING moves the mouth.
 * [gesture]: optionally play a gesture whenever it changes (e.g. WAVE on the welcome screen).
 */
@Composable
fun PixelAvatar(
    size: Dp,
    modifier: Modifier = Modifier,
    state: AvatarState = AvatarState.IDLE,
    assemble: Boolean = true,
    color: Color = MaterialTheme.colorScheme.onSurface,
    female: Boolean = LocalAssistantFemale.current,
    gesture: Gesture = Gesture.NONE,
    gestureKey: Int = 0,
    mood: FaceMood = FaceMood.NEUTRAL,
    autoGestures: Boolean = false,
    lookX: Float? = null,
    lookY: Float? = null,
    fps: Int = 30
) {
    val anim = remember { FaceAnimator() }
    val sc = remember { PixelFace.Scratch() }
    var frame by remember { mutableLongStateOf(0L) }
    val reveal = remember { androidx.compose.runtime.mutableFloatStateOf(if (assemble) 0f else 1.25f) }
    anim.autoGestures = autoGestures
    anim.externalLookX = lookX
    anim.externalLookY = lookY

    LaunchedEffect(state) {
        when (state) {
            AvatarState.IDLE -> { anim.mode = FaceMode.IDLE }
            AvatarState.THINKING -> { anim.mode = FaceMode.THINKING }
            AvatarState.TALKING -> { anim.mode = FaceMode.SPEAKING; anim.beginSpeaking() }
        }
    }
    LaunchedEffect(gesture, gestureKey) { if (gesture != Gesture.NONE) anim.play(gesture, 2.6f) }
    LaunchedEffect(mood) { anim.mood = mood }
    LaunchedEffect(Unit) {
        var last = 0L
        val step = 1_000_000_000L / fps
        var acc = 0L
        while (true) {
            withFrameNanos { n ->
                if (last != 0L) {
                    val d = n - last
                    acc += d
                    if (acc >= step) {
                        val dt = (acc / 1_000_000_000f).coerceAtMost(0.05f)
                        acc = 0
                        anim.update(dt)
                        if (state == AvatarState.TALKING) anim.onWord("aeiou"[(anim.time * 7f).toInt() % 5].toString(), (anim.time * 7f).toInt() + 1)
                        if (reveal.floatValue < 1.25f) reveal.floatValue = (reveal.floatValue + dt / 0.9f * 1.25f).coerceAtMost(1.25f)
                        frame = n
                    }
                }
                last = n
            }
        }
    }
    Canvas(modifier.size(size)) {
        if (frame < 0) return@Canvas
        drawAssistant(sc, anim, female, color, reveal = reveal.floatValue, withAura = false)
    }
}
