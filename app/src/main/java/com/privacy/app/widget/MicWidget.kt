package com.privacy.app.widget

import android.content.Context
import android.content.Intent
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
import androidx.glance.layout.padding
import androidx.compose.ui.unit.dp
import androidx.glance.layout.size
import com.privacy.app.R
import com.privacy.app.voice.AssistantOverlayActivity

/** A 1×1 mic. Tapping it floats the pixel assistant over whatever is on screen, already listening. */
class MicWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val open = actionStartActivity(Intent(context, AssistantOverlayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            // fixed square → always a round button, whatever the size of the home-screen cell
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(GlanceModifier.size(56.dp).background(ImageProvider(R.drawable.widget_mic_bg)).clickable(open), contentAlignment = Alignment.Center) {
                    Image(ImageProvider(R.drawable.ic_widget_face), "Talk to the assistant", GlanceModifier.size(36.dp))
                }
            }
        }
    }
}

class MicWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MicWidget()
}
