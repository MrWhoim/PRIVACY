package com.privacy.app.ui.focus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privacy.app.ui.MainViewModel
import com.privacy.app.ui.MonoLabel
import com.privacy.app.ui.tasks.DayCell
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** The calendar of the Mind tab: every day shows its detox % as a small pixel bar; tapping a day opens it in the tab (past days can be edited there). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MindHistorySheet(vm: MainViewModel, ui: FocusUi, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val today = remember { LocalDate.now() }
    var month by remember { mutableStateOf(YearMonth.from(LocalDate.ofEpochDay(ui.day))) }
    val rev by vm.g.focus.rev.collectAsStateWithLifecycle()
    val pcts by produceState(emptyMap<Long, Float>(), month, rev) {
        val out = HashMap<Long, Float>()
        for (d in 1..month.lengthOfMonth()) {
            val ep = month.atDay(d).toEpochDay()
            if (ep > today.toEpochDay()) break
            runCatching { vm.g.focus.evaluate(ep).focus }.getOrNull()?.let { out[ep] = it }
            value = HashMap(out)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous month") }
                Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())), Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = { month = month.plusMonths(1) }, enabled = month < YearMonth.from(today)) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next month") }
            }
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until 7) Text(DayOfWeek.MONDAY.plus(i.toLong()).getDisplayName(TextStyle.NARROW, Locale.getDefault()), Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 11.sp, color = c.onSurfaceVariant)
            }
            val lead = month.atDay(1).dayOfWeek.value - 1
            val weeks = (lead + month.lengthOfMonth() + 6) / 7
            for (w in 0 until weeks) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (d in 0 until 7) {
                        val n = w * 7 + d - lead + 1
                        if (n < 1 || n > month.lengthOfMonth()) Spacer(Modifier.weight(1f).aspectRatio(0.82f))
                        else {
                            val date = month.atDay(n); val ep = date.toEpochDay()
                            val f = pcts[ep]
                            DayCell(n, (f ?: 0f) / 100f, f != null, selected = ep == ui.day, isToday = date == today, enabled = !date.isAfter(today), modifier = Modifier.weight(1f)) { ui.day = ep; onDismiss() }
                        }
                    }
                }
            }
            MonoLabel("Tap a day to see its detox status and add or fix entries.")
        }
    }
}
