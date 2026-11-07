package com.privacy.app.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.privacy.app.automation.AutomationScheduler
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Things the assistant can ask the on-screen app to do (open a page, show an Android permission prompt). */
sealed interface UiCommand {
    data class RequestPermission(val kind: String) : UiCommand
    data class OpenScreen(val name: String) : UiCommand
}

/**
 * The assistant runs without any screen of its own, so when it needs something from the UI it sends a command here.
 * If the app is not on screen (voice from the widget, an automation) nobody is listening and [send] says so.
 */
object UiBridge {
    private val _commands = MutableSharedFlow<UiCommand>(extraBufferCapacity = 8)
    val commands: SharedFlow<UiCommand> = _commands.asSharedFlow()

    /** True if the app is on screen and will act on the command. */
    fun send(c: UiCommand): Boolean {
        val listening = _commands.subscriptionCount.value > 0
        if (listening) _commands.tryEmit(c)
        return listening
    }
}

/** Android permission state, in words the assistant can read out. */
object PermissionInfo {
    val KINDS = listOf("notifications", "microphone", "exact_alarms", "battery", "phone", "music", "files", "overlay")

    fun granted(ctx: Context, kind: String): Boolean = when (kind) {
        "notifications" -> NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        "microphone" -> ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        "exact_alarms" -> AutomationScheduler.canExact(ctx)
        "battery" -> ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
        "phone" -> ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        "music" -> com.privacy.app.media.MusicPlayer.hasPermission(ctx)
        "files" -> com.privacy.app.media.FileFinder.hasAllFiles()
        "overlay" -> android.provider.Settings.canDrawOverlays(ctx)
        else -> false
    }

    fun describe(ctx: Context): String = KINDS.joinToString("\n") { k ->
        val label = when (k) {
            "notifications" -> "notifications (reminders show up)"
            "microphone" -> "microphone (voice)"
            "exact_alarms" -> "exact_alarms (reminders on the minute)"
            "phone" -> "phone (calls and contacts)"
            "music" -> "music (audio files for the player)"
            "files" -> "files (find any file on the phone)"
            "overlay" -> "overlay (float the assistant / notes over other apps)"
            else -> "battery (run in background without delays)"
        }
        "- $label: " + if (granted(ctx, k)) "ALLOWED" else "NOT allowed"
    }

    fun canonical(raw: String): String? {
        val v = raw.lowercase().replace(' ', '_').replace('-', '_')
        return when {
            v.startsWith("notif") -> "notifications"
            v.startsWith("mic") || v.startsWith("voice") || v.startsWith("audio") -> "microphone"
            v.startsWith("exact") || v.startsWith("alarm") -> "exact_alarms"
            v.startsWith("batt") || v.startsWith("background") -> "battery"
            v.startsWith("phone") || v.startsWith("call") || v.startsWith("contact") -> "phone"
            v.startsWith("music") || v.startsWith("audio_files") || v.startsWith("media") -> "music"
            v.startsWith("file") || v.startsWith("storage") -> "files"
            v.startsWith("overlay") || v.startsWith("draw") || v.startsWith("float") -> "overlay"
            else -> null
        }
    }

    @Suppress("unused") val needsRuntimeDialog = Build.VERSION.SDK_INT >= 33
}
