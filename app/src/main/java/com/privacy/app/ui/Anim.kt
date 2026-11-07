package com.privacy.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One switch for decorative animations. Set from Settings; read by the shared UI helpers so every screen follows it. */
object Anim {
    var enabled by mutableStateOf(true)
}

/** A value that counts/fills up from 0 the first time it appears and glides to later changes. With animations off it just returns [target]. */
@androidx.compose.runtime.Composable
fun rememberFillIn(target: Float, ms: Int = 1100): Float {
    if (!Anim.enabled) return target
    val a = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(target) { a.animateTo(target, androidx.compose.animation.core.tween(ms, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    return a.value
}
