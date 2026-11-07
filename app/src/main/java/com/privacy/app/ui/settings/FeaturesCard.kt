package com.privacy.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privacy.app.overlay.OverlayService
import com.privacy.app.ui.MainViewModel
import com.privacy.app.ui.SoftCard
import com.privacy.app.watch.WatchSync
import kotlinx.coroutines.launch

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f)); Switch(checked, onChange)
    }
}

/** Account → switches for the floating buttons, the watch sync, health alerts and "I'm Feeling Distracted". */
@Composable
fun FeaturesCard(vm: MainViewModel) {
    val st = vm.g.settings
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val notesOv by st.notesOverlayOn.collectAsStateWithLifecycle()
    val asstOv by st.assistantOverlayOn.collectAsStateWithLifecycle()
    val watch by st.watchSyncOn.collectAsStateWithLifecycle()
    val vision = remember(asstOv) { st.visionReady() }
    var msg by remember { mutableStateOf("") }
    val hcLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        if (granted.containsAll(WatchSync.PERMISSIONS)) { st.setWatchSyncOn(true); scope.launch { msg = WatchSync.sync(vm.g) } } else msg = "Health Connect access was not allowed."
    }
    fun overlayOk(): Boolean {
        if (Settings.canDrawOverlays(ctx)) return true
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        msg = "Allow “Display over other apps” for this app, then switch it on again."
        return false
    }
    SoftCard {
        CardTitle(Icons.Rounded.Favorite, "Floating buttons & smart watch")
        ToggleRow("Floating ✎ note button over other apps", notesOv) { on -> if (!on || overlayOk()) { st.setNotesOverlayOn(on); OverlayService.sync(ctx) } }
        ToggleRow("Pixel assistant over other apps: same personality and jokes" + if (vision) ", tap to hear about the screen" else " (add a vision model to let it describe the screen)", asstOv) { on ->
            if (!on || overlayOk()) { st.setAssistantOverlayOn(on); OverlayService.sync(ctx) }
        }
        ToggleRow("Fetch steps & calories from my smart watch (Health Connect)", watch) { on ->
            if (!on) st.setWatchSyncOn(false)
            else if (!WatchSync.available(ctx)) msg = "Health Connect is not installed. Install it from Play Store and connect your watch app to it."
            else hcLauncher.launch(WatchSync.PERMISSIONS)
        }
        if (watch) OutlinedButton(onClick = { scope.launch { msg = WatchSync.sync(vm.g) } }) { Text("Sync now") }
        if (msg.isNotBlank()) Text(msg, fontSize = 12.sp)
    }
}
