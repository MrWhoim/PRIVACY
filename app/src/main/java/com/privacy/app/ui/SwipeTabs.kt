package com.privacy.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The pill row (Task | Health, Task | Health | Overall …). Pair it with a HorizontalPager so the pages can also be swiped. */
@Composable
fun SegmentedTabs(titles: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        titles.forEachIndexed { i, l ->
            val on = selected == i
            Surface(
                shape = RoundedCornerShape(12.dp), color = if (on) c.surfaceVariant else c.surface.copy(alpha = 0.4f),
                modifier = Modifier.weight(1f).clickable { onSelect(i) }
            ) {
                Text(
                    l, Modifier.fillMaxWidth().padding(vertical = 11.dp), textAlign = TextAlign.Center, fontSize = 15.sp,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal, color = if (on) c.onSurface else c.onSurface.copy(alpha = 0.45f)
                )
            }
        }
    }
}

/** Slides to a page (or jumps, when animations are switched off in Settings). */
suspend fun PagerState.goTo(page: Int) { if (Anim.enabled) animateScrollToPage(page) else scrollToPage(page) }
