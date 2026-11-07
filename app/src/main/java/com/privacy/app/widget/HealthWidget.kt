package com.privacy.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.privacy.app.R
import com.privacy.app.data.AppDatabase
import com.privacy.app.data.HealthRepo
import java.time.LocalDate
import java.util.Locale

private val hwWhite = ColorProvider(Color(0xFFFFFFFF))
private val hwMuted = ColorProvider(Color(0xFF8C8C8C))
private val hwTrack = ColorProvider(Color(0x33FFFFFF))

/** Health of the day at a glance: the overall percentage and four bars – Nutrition, Activity, Sleep, Water. Tap to open the Health tab. */
class HealthWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val db = AppDatabase.get(context)
        val ev = runCatching { HealthRepo(context, db.healthDao()).evaluate(LocalDate.now().toEpochDay()) }.getOrNull()
        val overall = ev?.score
        val bars = ev?.cats?.map { Triple(it.key, it.score, it.logged) } ?: emptyList()
        provideContent { HealthContent(context, overall, bars) }
    }
}

class HealthWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HealthWidget()
}

@Composable
private fun HealthContent(context: Context, overall: Float?, bars: List<Triple<String, Float?, Boolean>>) {
    val open = actionStartActivity(Intent(context, com.privacy.app.MainActivity::class.java).putExtra("open_screen", "health").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    val size = LocalSize.current
    val dens = context.resources.displayMetrics.density
    val bmp = HealthRings.panel(((size.width.value - 6f) * dens).toInt().coerceAtMost(900), ((size.height.value - 6f) * dens).toInt().coerceAtMost(900), overall, bars)
    Box(GlanceModifier.fillMaxSize().background(ImageProvider(R.drawable.widget_bg)).padding(3.dp).clickable(open)) {
        Image(ImageProvider(bmp), "Health of the day", GlanceModifier.fillMaxSize())
    }
}
