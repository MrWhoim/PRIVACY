package com.privacy.app.ui

import android.app.Activity
import androidx.compose.ui.input.pointer.pointerInput
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Canvas as GfxCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privacy.app.ui.theme.Mono

fun Context.findActivity(): Activity? {
    var c = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Living background: a dot grid (drawn ONCE into a bitmap, not every frame) with a slow band of light sweeping across it
 * and a few drifting soft orbs. Only the cheap moving parts are drawn per frame, which removes the old lag.
 */
@Composable
fun ScreenBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
}

/** Presses shrink slightly and spring back – used on every tappable surface for a tactile feel. */
fun Modifier.bounceClick(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    this.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = enabled, onClick = onClick)
}

/** Fades and slides its content in the first time it appears. [delayMs] staggers lists. */
@Composable
fun Appear(modifier: Modifier = Modifier, delayMs: Int = 0, content: @Composable () -> Unit) {
    Box(modifier) { content() }
}

/** Four squares that light up one after another – the app's "thinking" indicator. */
@Composable
fun PixelLoader(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurface
    val t = rememberInfiniteTransition(label = "loader")
    val phaseA by t.animateFloat(0f, 4f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "phase")
    Canvas(modifier.size(width = 34.dp, height = 8.dp)) {
        val phase = if (Anim.enabled) phaseA else 1.5f
        val cell = size.height
        for (i in 0 until 4) {
            val k = ((phase - i) % 4f + 4f) % 4f
            drawRect(color.copy(alpha = (1f - k / 4f).coerceIn(0.15f, 1f)), Offset(i * (cell + 2.dp.toPx()), 0f), Size(cell, cell))
        }
    }
}

/** Tiny uppercase monospace label – the "technical" voice of the UI. */
@Composable
fun MonoLabel(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, modifier, color = color, fontSize = 12.sp)
}

/** Opens the left menu; provided by the app root so every page can show the three-line button instead of a back arrow. */
val LocalOpenDrawer = androidx.compose.runtime.staticCompositionLocalOf<() -> Unit> { {} }

@Composable
fun MenuButton(modifier: Modifier = Modifier) {
    val open = LocalOpenDrawer.current
    IconButton(onClick = open, modifier = modifier) { Icon(androidx.compose.material.icons.Icons.Rounded.Menu, "Menu") }
}

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding()
            .padding(start = if (onBack != null) 6.dp else 20.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) MenuButton()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (subtitle != null) MonoLabel(subtitle)
        }
        trailing()
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/**
 * The app's signature surface: dark glass with a bright gradient rim, a soft outer glow (dark theme), a faint reflection along the top edge
 * and a pixel-dot texture – the same light as the logo's glowing cube.
 */
fun Modifier.pixelGlass(shape: androidx.compose.ui.graphics.Shape, fill: Color, glow: Boolean = true, dots: Boolean = true, strong: Boolean = false): Modifier =
    this.background(fill, shape).clip(shape)

/** A plain section: no box, just comfortable spacing and a hairline under it. */
@Composable
fun SoftCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        content()
        Spacer(Modifier.height(4.dp))
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** The app's mark: the pixel assistant's face (same portrait as the talking screen). */
@Composable
fun PixelMark(size: Dp, modifier: Modifier = Modifier, state: com.privacy.app.ui.face.AvatarState = com.privacy.app.ui.face.AvatarState.IDLE) {
    com.privacy.app.ui.face.PixelAvatar(size, modifier, state)
}


private val MIC = listOf(
    "...###...",
    "..#####..",
    "..#####..",
    "..#####..",
    "..#####..",
    "#.#####.#",
    "#.#####.#",
    "#..###..#",
    ".#.....#.",
    "..#####..",
    "....#....",
    "....#....",
    "..#####.."
)

/** A pixel-art microphone with two sparkle pixels that twinkle ([spark] runs 0..1 in a loop). */
@Composable
fun PixelMicIcon(color: Color, spark: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val cols = MIC[0].length + 3
        val rows = MIC.size
        val cell = minOf(size.width / cols, size.height / rows)
        val ox = (size.width - cell * cols) / 2f
        val oy = (size.height - cell * rows) / 2f
        MIC.forEachIndexed { y, row ->
            row.forEachIndexed { x, c ->
                if (c == '#') drawRect(color, Offset(ox + x * cell, oy + y * cell), Size(cell + 0.4f, cell + 0.4f))
            }
        }
        val a1 = (1f - kotlin.math.abs(spark * 2f - 1f)).coerceIn(0f, 1f)
        val a2 = (1f - kotlin.math.abs(((spark + 0.5f) % 1f) * 2f - 1f)).coerceIn(0f, 1f)
        drawRect(color.copy(alpha = a1), Offset(ox + 10 * cell, oy + 1 * cell), Size(cell, cell))
        drawRect(color.copy(alpha = a2), Offset(ox + 11 * cell, oy + 4 * cell), Size(cell, cell))
        drawRect(color.copy(alpha = a2), Offset(ox + 10 * cell, oy + 7 * cell), Size(cell, cell))
    }
}


/** The app's logo (the glowing cube, same picture as the launcher icon). */
@Composable
fun BrandLogo(size: Dp, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Image(
        painter = androidx.compose.ui.res.painterResource(com.privacy.app.R.drawable.brand_logo),
        contentDescription = "P.R.I.V.A.C.Y.",
        modifier = modifier.size(size).clip(RoundedCornerShape(size * 0.2f)),
        contentScale = androidx.compose.ui.layout.ContentScale.Fit
    )
}


/** Send arrow: a solid arrow flying to the upper right with a slit (drawn, so it works at any size and colour). `cut` is the colour behind the icon. */
@Composable
fun PaperPlaneIcon(color: Color, cut: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val u = size.minDimension / 24f
        fun pt(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x * u, y * u)
        val body = androidx.compose.ui.graphics.Path().apply {
            moveTo(20.2f * u, 3.8f * u); lineTo(4.2f * u, 9.8f * u); lineTo(10.6f * u, 13.4f * u); lineTo(14.2f * u, 19.8f * u); close()
        }
        val round = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.2f * u, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        drawPath(body, color)
        drawPath(body, color, style = round)
        drawLine(cut, pt(10.6f, 13.4f), pt(15.2f, 8.8f), strokeWidth = 1.5f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/** Keeps the menu swipe from stealing the swipe that moves between tabs: it is blocked while the pager is not on its first page or is moving. */
@Composable
fun TabSwipeGuard(pager: androidx.compose.foundation.pager.PagerState) {
    androidx.compose.runtime.LaunchedEffect(pager) {
        androidx.compose.runtime.snapshotFlow { pager.currentPage > 0 || pager.targetPage > 0 || pager.isScrollInProgress }
            .collect { com.privacy.app.AppState.tabCanGoPrev = it }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { com.privacy.app.AppState.tabCanGoPrev = false } }
}


/** While a finger is down on this, the "swipe right opens the menu" gesture is off (text fields, seek bars, the assistant being dragged). */
fun androidx.compose.ui.Modifier.noDrawerSwipe(): androidx.compose.ui.Modifier = this.then(
    androidx.compose.ui.Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                val down = e.changes.any { it.pressed }
                if (down && !tbDown) { tbDown = true; com.privacy.app.AppState.touchBlock++ }
                if (!down && tbDown) { tbDown = false; com.privacy.app.AppState.touchBlock = (com.privacy.app.AppState.touchBlock - 1).coerceAtLeast(0) }
            }
        }
    }
)
private var tbDown = false

/** Same, but only once the finger has stayed down for a moment (selecting text by press-and-drag); a quick swipe still opens the menu. */
fun androidx.compose.ui.Modifier.blockOnLongPress(): androidx.compose.ui.Modifier = this.then(
    androidx.compose.ui.Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            var t0 = 0L; var on = false
            while (true) {
                val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                val down = e.changes.any { it.pressed }
                val now = System.currentTimeMillis()
                if (down) {
                    if (t0 == 0L) t0 = now
                    if (!on && now - t0 > 280) { on = true; com.privacy.app.AppState.touchBlock++ }
                } else {
                    t0 = 0L
                    if (on) { on = false; com.privacy.app.AppState.touchBlock = (com.privacy.app.AppState.touchBlock - 1).coerceAtLeast(0) }
                }
            }
        }
    }
)
