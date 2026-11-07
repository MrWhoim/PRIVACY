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

/** True when the assistant is shown as the female version. Provided once at the root of the app from the settings. */
val LocalAssistantFemale = compositionLocalOf { false }

/** Draws one frame of the dot-matrix assistant; the caller owns the [FaceAnimator] and calls `update` every frame. */
fun DrawScope.drawAssistant(
    sc: PixelFace.Scratch,
    anim: FaceAnimator,
    female: Boolean,
    color: Color,
    reveal: Float = 1.25f,
    withAura: Boolean = true,
    left: Float = 0f,
    top: Float = 0f,
    width: Float = size.width,
    height: Float = size.height
) {
    PixelFace.render(sc, anim.p, female, anim.shownGesture, anim.gestureAmount, anim.time)
    val cell = min(width / PixelFace.W, height / PixelFace.H)
    val ox = left + (width - cell * PixelFace.W) / 2f
    val oy = top + (height - cell * PixelFace.H) / 2f
    val radius = cell * 0.48f
    val glowR = cell * 0.95f
    // square pixels: the "radius" is half the side of the square
    fun DrawScope.drawSq(col: Color, r: Float, c: Offset) = drawRect(col, Offset(c.x - r, c.y - r), androidx.compose.ui.geometry.Size(r * 2f, r * 2f))
    for (iy in 0 until PixelFace.H) for (ix in 0 until PixelFace.W) {
        val c = Offset(ox + (ix + 0.5f) * cell, oy + (iy + 0.5f) * cell)
        if (sc.out[iy * PixelFace.W + ix]) {
            // pixels fly in from the top in random order while the assistant assembles
            if (AvatarFrames.hash(ix, iy) * 0.6f + (iy / PixelFace.H.toFloat()) * 0.4f > reveal) continue
            drawSq(color, radius, c)
            // a thin scan line through every lit square (the look of the original face screen)
            if (cell >= 4f) drawRect(Color.Black.copy(alpha = 0.22f), Offset(c.x - radius, c.y - cell * 0.045f), androidx.compose.ui.geometry.Size(radius * 2f, cell * 0.09f))
        } else if (sc.dim[iy * PixelFace.W + ix]) {
            drawSq(color.copy(alpha = 0.2f), radius * 0.85f, c)
        } else if (withAura && reveal >= 1.2f) {
            val a = PixelFace.aura(ix, iy, anim.time)
            if (a > 0f) drawSq(color.copy(alpha = a), radius * 0.8f, c)
        }
    }
}

