package com.privacy.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Big black-and-white "Continue with Google" button that follows the app theme (no colours). */
@Composable
fun GoogleButton(modifier: Modifier = Modifier, text: String = "Continue with Google", enabled: Boolean = true, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = modifier.fillMaxWidth().height(68.dp).clip(RoundedCornerShape(22.dp)).clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
    ) {
        Row(Modifier.fillMaxWidth().height(68.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            GoogleG(Modifier.size(30.dp), ink)
            androidx.compose.foundation.layout.Spacer(Modifier.size(14.dp))
            Text(text, color = ink.copy(alpha = if (enabled) 1f else 0.5f), fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
    }
}

private val G_PATHS = listOf(
    "M43.611,20.083H42V20H24v8h11.303c-1.649,4.657-6.08,8-11.303,8c-6.627,0-12-5.373-12-12c0-6.627,5.373-12,12-12c3.059,0,5.842,1.154,7.961,3.039l5.657-5.657C34.046,6.053,29.268,4,24,4C12.955,4,4,12.955,4,24c0,11.045,8.955,20,20,20c11.045,0,20-8.955,20-20C44,22.659,43.862,21.35,43.611,20.083z",
    "M6.306,14.691l6.571,4.819C14.655,15.108,18.961,12,24,12c3.059,0,5.842,1.154,7.961,3.039l5.657-5.657C34.046,6.053,29.268,4,24,4C16.318,4,9.656,8.337,6.306,14.691z",
    "M24,44c5.166,0,9.86-1.977,13.409-5.192l-6.19-5.238C29.211,35.091,26.715,36,24,36c-5.202,0-9.619-3.317-11.283-7.946l-6.522,5.025C9.505,39.556,16.227,44,24,44z",
    "M43.611,20.083H42V20H24v8h11.303c-0.792,2.237-2.231,4.166-4.087,5.571c0.001-0.001,0.002-0.001,0.003-0.002l6.19,5.238C36.971,39.205,44,34,44,24C44,22.659,43.862,21.35,43.611,20.083z"
)

/** The Google "G" in ONE colour (follows the theme: white in dark mode). */
@Composable
private fun GoogleG(modifier: Modifier, color: androidx.compose.ui.graphics.Color) {
    val paths = androidx.compose.runtime.remember { G_PATHS.map { androidx.compose.ui.graphics.vector.PathParser().parsePathString(it).toPath() } }
    androidx.compose.foundation.Canvas(modifier) {
        val k = size.width / 48f
        scale(k, k, pivot = androidx.compose.ui.geometry.Offset.Zero) { paths.forEach { drawPath(it, color) } }
    }
}
