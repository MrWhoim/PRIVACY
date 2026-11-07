package com.privacy.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.size
import com.privacy.app.MainActivity
import com.privacy.app.R

/** A 1x1 square with a pixel "+": one tap opens a new empty note in the app. */
class NoteWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val open = actionStartActivity(
                Intent(context, MainActivity::class.java).putExtra("open_screen", "notes").putExtra("new_note", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(GlanceModifier.fillMaxSize().background(ImageProvider(R.drawable.widget_note_bg)).clickable(open), contentAlignment = Alignment.Center) {
                    Image(ImageProvider(R.drawable.widget_note_plus), "New note", GlanceModifier.size(34.dp))
                }
            }
        }
    }
}

class NoteWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NoteWidget()
}
