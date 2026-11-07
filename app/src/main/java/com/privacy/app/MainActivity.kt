package com.privacy.app

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import android.content.Intent
import com.privacy.app.ui.chat.MarkdownText
import com.privacy.app.ui.PrivacyRoot
import com.privacy.app.ui.MainViewModel
import com.privacy.app.ui.theme.PrivacyTheme

class MainActivity : ComponentActivity() {
    private var stoppedAt = -1L
    /** A .md file somebody opened with this app: (file name, text). */
    private var mdFile by mutableStateOf<Pair<String, String>?>(null)

    private fun readMarkdown(i: Intent?) {
        if (i == null) return
        val uri = when (i.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java) else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        val text = when {
            uri != null -> runCatching { contentResolver.openInputStream(uri)!!.use { it.readBytes().take(600_000).toByteArray().toString(Charsets.UTF_8) } }.getOrNull()
            i.action == Intent.ACTION_SEND && i.type?.contains("markdown") == true -> i.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return
        val name = uri?.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Markdown file"
        mdFile = name to text
    }

    /** Google sign-in comes back as privacy://auth#access_token=… */
    private fun readAuth(i: Intent?) {
        val d = i?.data ?: return
        if (d.scheme == "privacy" && d.host == "auth") {
            com.privacy.app.credits.Credits.handleRedirect(d)
            AppState.pendingScreen.value = "apis"
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readAuth(intent)
        intent.getStringExtra("open_screen")?.let { AppState.pendingScreen.value = it }
        if (intent.getBooleanExtra("new_note", false)) AppState.newNoteRequest.value = true
        readMarkdown(intent)
    }
    private val graph get() = (application as PrivacyApp).graph
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readAuth(intent)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 && !graph.settings.askedNotifications) {
            graph.settings.askedNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        intent?.getStringExtra("open_screen")?.let { AppState.pendingScreen.value = it }
        if (intent?.getBooleanExtra("new_note", false) == true) AppState.newNoteRequest.value = true
        readMarkdown(intent)
        setContent {
            val fontKey by graph.settings.fontKey.collectAsStateWithLifecycle()
            val themeMode by graph.settings.themeMode.collectAsStateWithLifecycle()
            val dark = com.privacy.app.ui.theme.isDarkFor(themeMode)
            androidx.compose.runtime.SideEffect {
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !dark
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = !dark
            }
            PrivacyTheme(darkTheme = dark, fontKey = fontKey) {
                val vm: MainViewModel = viewModel()
                PrivacyRoot(vm)
                // files the assistant opens inside the app (Markdown / text, pictures)
                val reqMd by AppState.mdRequest
                androidx.compose.runtime.LaunchedEffect(reqMd) { reqMd?.let { mdFile = it; AppState.mdRequest.value = null } }
                val reqImg by AppState.imageRequest
                reqImg?.let { uri ->
                    val bmp = androidx.compose.runtime.remember(uri) {
                        runCatching {
                            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
                            var ss = 1; while (o.outWidth / ss > 2400 || o.outHeight / ss > 2400) ss *= 2
                            contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = ss }) }?.asImageBitmap()
                        }.getOrNull()
                    }
                    // full screen: pinch to zoom, drag to move, double tap resets
                    androidx.compose.ui.window.Dialog(
                        onDismissRequest = { AppState.imageRequest.value = null },
                        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false)
                    ) {
                        // make the dialog window really cover the whole screen (status bar, navigation bar, cut-outs), black behind
                        val dlgView = androidx.compose.ui.platform.LocalView.current
                        androidx.compose.runtime.SideEffect {
                            (dlgView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { w ->
                                w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                                w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
                                w.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                                w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                                w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                                if (android.os.Build.VERSION.SDK_INT >= 28) w.attributes = w.attributes.apply { layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
                            }
                        }
                        var scale by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
                        var offset by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
                            if (bmp != null) androidx.compose.foundation.Image(
                                bmp, "Picture",
                                Modifier.fillMaxSize()
                                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = 1f; offset = androidx.compose.ui.geometry.Offset.Zero }) }
                                    .pointerInput(Unit) { detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 6f); offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan } }
                                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit
                            ) else Text("Couldn't show that picture.", color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.align(Alignment.Center))
                            TextButton(onClick = { AppState.imageRequest.value = null }, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) { Text("Close", color = androidx.compose.ui.graphics.Color.White) }
                        }
                    }
                }
                var crash by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(CrashLog.take(this@MainActivity)) }
                crash?.let { txt ->
                    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
                    AlertDialog(
                        onDismissRequest = { crash = null },
                        title = { Text("The app closed unexpectedly last time") },
                        text = { Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) { Text(txt.take(4000), style = androidx.compose.material3.MaterialTheme.typography.bodySmall) } },
                        confirmButton = { TextButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(txt)); crash = null }) { Text("Copy & close") } },
                        dismissButton = { TextButton(onClick = { crash = null }) { Text("Close") } }
                    )
                }
                mdFile?.let { (name, text) ->
                    AlertDialog(
                        onDismissRequest = { mdFile = null },
                        title = { Text(name) },
                        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { MarkdownText(text, androidx.compose.material3.MaterialTheme.colorScheme.onSurface) } },
                        confirmButton = { TextButton(onClick = { vm.saveNote(null, name, text, false); mdFile = null }) { Text("Save to Notes") } },
                        dismissButton = { TextButton(onClick = { mdFile = null }) { Text("Close") } }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        // removed from the background while a Telegram session is running: tell the others (best effort)
        if (isFinishing) runCatching { com.privacy.app.ai.TelegramHub.goodbye(graph) }
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
        graph.settings.lastActiveAt = System.currentTimeMillis()
        AppState.foreground = false
    }

    /** Vault auto-locks if you were away from the app for more than 30 seconds. */
    override fun onStart() {
        super.onStart()
        AppState.foreground = true
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { AppResume.run(graph) } }
        if (stoppedAt >= 0 && SystemClock.elapsedRealtime() - stoppedAt > 30_000) graph.vault.lock()
    }
}
