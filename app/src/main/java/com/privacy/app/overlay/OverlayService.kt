package com.privacy.app.overlay

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import com.privacy.app.PrivacyApp
import com.privacy.app.data.NoteEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Floating bubbles over other apps (needs "Display over other apps"):
 *  • ✎ notes bubble – tap, type, Save → a new note.
 *  • ◉ assistant bubble – tap: screenshot of the current page → vision model → short report. Only works when a vision model is set up.
 * The screenshot needs Android's screen-capture consent, asked through [ScreenCaptureActivity] each time the service starts capturing.
 */
class OverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var notesBubble: View? = null
    private var assistBubble: View? = null
    private var panel: View? = null
    private var projection: MediaProjection? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("overlay", "Floating buttons", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "overlay").setContentTitle("P.R.I.V.A.C.Y. floating buttons are on").setSmallIcon(android.R.drawable.ic_menu_edit).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(77, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE.takeIf { Build.VERSION.SDK_INT >= 34 } ?: 0) else startForeground(77, n)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val g = (application as PrivacyApp).graph
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_PROJECTION && intent.hasExtra("code")) {
            val code = intent.getIntExtra("code", Activity.RESULT_CANCELED)
            val data = intent.getParcelableExtra<Intent>("data")
            if (code == Activity.RESULT_OK && data != null) capture(code, data) else toast("Screen capture was not allowed.")
            return START_STICKY
        }
        refresh(g.settings.notesOverlayOn.value, g.settings.assistantOverlayOn.value)
        return START_STICKY
    }

    private fun refresh(notes: Boolean, assist: Boolean) {
        if (notes && notesBubble == null) notesBubble = bubble("✎", Color.parseColor("#6750A4"), 0.35f) { showNotePanel() }
        if (!notes) { notesBubble?.let { runCatching { wm.removeView(it) } }; notesBubble = null }
        if (assist && assistBubble == null) assistBubble = assistantView()
        if (!assist) { assistBubble?.let { runCatching { wm.removeView(it) } }; assistBubble = null }
        if (!notes && !assist) stopSelf()
    }

    private fun lp(w: Int = WindowManager.LayoutParams.WRAP_CONTENT, h: Int = WindowManager.LayoutParams.WRAP_CONTENT, focus: Boolean = false) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        if (focus) WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    )

    private fun bubble(label: String, color: Int, yFrac: Float, onTap: () -> Unit): View {
        val d = resources.displayMetrics
        val size = (46 * d.density).toInt()
        val tv = TextView(this).apply {
            text = label; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
            alpha = 0.9f
        }
        val p = lp(size, size).apply { gravity = Gravity.TOP or Gravity.START; x = d.widthPixels - size; y = (d.heightPixels * yFrac).toInt() }
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0; var moved = false
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = p.x; sy = p.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.rawX - dx) > 12 || Math.abs(e.rawY - dy) > 12) moved = true
                    if (moved) { p.x = sx + (e.rawX - dx).toInt(); p.y = sy + (e.rawY - dy).toInt(); wm.updateViewLayout(tv, p) }
                }
                MotionEvent.ACTION_UP -> if (!moved) onTap() else { p.x = if (p.x + size / 2 > d.widthPixels / 2) d.widthPixels - size else 0; wm.updateViewLayout(tv, p) }
            }
            true
        }
        wm.addView(tv, p)
        return tv
    }


    // ---- the same pixel assistant, floating over other apps ----
    private class Owner : androidx.lifecycle.LifecycleOwner, androidx.savedstate.SavedStateRegistryOwner, androidx.lifecycle.ViewModelStoreOwner {
        private val reg = androidx.lifecycle.LifecycleRegistry(this)
        private val ssc = androidx.savedstate.SavedStateRegistryController.create(this)
        private val store = androidx.lifecycle.ViewModelStore()
        override val lifecycle: androidx.lifecycle.Lifecycle get() = reg
        override val savedStateRegistry: androidx.savedstate.SavedStateRegistry get() = ssc.savedStateRegistry
        override val viewModelStore: androidx.lifecycle.ViewModelStore get() = store
        fun start() { ssc.performRestore(null); reg.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        fun stop() { reg.currentState = androidx.lifecycle.Lifecycle.State.DESTROYED; store.clear() }
    }
    private var owner: Owner? = null
    private var talking = androidx.compose.runtime.mutableStateOf(false)
    private var saying: TextView? = null
    private val jokeLoop = object : Runnable { override fun run() { if (assistBubble != null) { say(com.privacy.app.ui.face.CompanionBrain.joke()); main.postDelayed(this, 90_000) } } }

    private fun assistantView(): View {
        val d = resources.displayMetrics
        val size = (84 * d.density).toInt()
        val o = Owner().also { it.start(); owner = it }
        val cv = androidx.compose.ui.platform.ComposeView(this).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeViewModelStoreOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                    val playing by com.privacy.app.media.MusicPlayer.playing.collectAsState()
                    com.privacy.app.ui.face.PixelAvatar(
                        size = 84.dp, assemble = false, color = androidx.compose.ui.graphics.Color.White,
                        state = if (talking.value) com.privacy.app.ui.face.AvatarState.TALKING else com.privacy.app.ui.face.AvatarState.IDLE,
                        mood = if (playing) com.privacy.app.ui.face.FaceMood.HAPPY else com.privacy.app.ui.face.FaceMood.NEUTRAL,
                        autoGestures = true
                    )
                }
            }
        }
        val p = lp(size, size).apply { gravity = Gravity.TOP or Gravity.START; x = d.widthPixels - size; y = (d.heightPixels * 0.5f).toInt() }
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0; var moved = false
        cv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX; dy = e.rawY; sx = p.x; sy = p.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.rawX - dx) > 12 || Math.abs(e.rawY - dy) > 12) moved = true
                    if (moved) { p.x = sx + (e.rawX - dx).toInt(); p.y = sy + (e.rawY - dy).toInt(); wm.updateViewLayout(cv, p) }
                }
                MotionEvent.ACTION_UP -> if (!moved) onAssistantTap() else { p.x = if (p.x + size / 2 > d.widthPixels / 2) d.widthPixels - size else 0; wm.updateViewLayout(cv, p) }
            }
            true
        }
        wm.addView(cv, p)
        main.postDelayed(jokeLoop, 8_000)
        return cv
    }

    /** Tap: tell about the screen when a vision model exists, otherwise tell a joke. */
    private fun onAssistantTap() {
        val g = (application as PrivacyApp).graph
        if (g.settings.visionReady()) { say("Let me look at your screen…"); requestCapture() } else say(com.privacy.app.ui.face.CompanionBrain.joke())
    }

    private fun say(text: String) = main.post {
        saying?.let { runCatching { wm.removeView(it) } }
        val d = resources.displayMetrics
        val tv = TextView(this).apply {
            this.text = text; setTextColor(Color.WHITE); textSize = 13f; setPadding(36, 24, 36, 24); maxWidth = (d.widthPixels * 0.7f).toInt()
            background = GradientDrawable().apply { setColor(Color.parseColor("#E61C1C1E")); cornerRadius = 40f }
        }
        val p = lp().apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = (d.heightPixels * 0.3f).toInt() }
        wm.addView(tv, p); saying = tv; talking.value = true
        main.postDelayed({ runCatching { wm.removeView(tv) }; if (saying === tv) saying = null; talking.value = false }, (2500 + text.length * 60L).coerceAtMost(9000))
    }

    private fun closePanel() { panel?.let { runCatching { wm.removeView(it) } }; panel = null }

    private fun box(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32)
        background = GradientDrawable().apply { setColor(Color.parseColor("#F5F5F5")); cornerRadius = 36f }
    }

    private var notePanel: View? = null
    private var noteLp: WindowManager.LayoutParams? = null

    private fun pill(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = 200f }

    /** The note bubble: opens the floating note, or brings back the hidden one with the same text. */
    private fun showNotePanel() {
        val existing = notePanel
        if (existing != null) { existing.visibility = if (existing.visibility == View.VISIBLE) View.GONE else View.VISIBLE; return }
        val g = (application as PrivacyApp).graph
        val d = resources.displayMetrics
        val title = EditText(this).apply { hint = "Title"; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); textSize = 17f; setSingleLine(true); background = null; setPadding(0, 0, 0, 4) }
        val body = EditText(this).apply { hint = "Write a note…"; gravity = Gravity.TOP; setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); background = null; setPadding(0, 4, 0, 4) }
        fun btn(label: String, fill: Int, text: Int, click: () -> Unit) = TextView(this).apply {
            this.text = label; setTextColor(text); textSize = 13f; gravity = Gravity.CENTER; setPadding(40, 14, 40, 14)
            background = pill(fill); setOnClickListener { click() }
        }
        val hide = btn("Hide", Color.parseColor("#3A3A3C"), Color.WHITE) { notePanel?.visibility = View.GONE }
        val done = btn("Done", Color.WHITE, Color.BLACK) { closeNote() }
        val grip = TextView(this).apply { text = "◢"; setTextColor(Color.GRAY); textSize = 16f; gravity = Gravity.END or Gravity.BOTTOM }
        val head = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            addView(TextView(this@OverlayService).apply { text = "⠿ drag"; setTextColor(Color.GRAY); textSize = 11f }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(hide); addView(View(this@OverlayService), LinearLayout.LayoutParams(16, 1)); addView(done)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(36, 22, 36, 14)
            background = GradientDrawable().apply { setColor(Color.parseColor("#F21C1C1E")); cornerRadius = 56f }
            addView(head); addView(title); addView(body, LinearLayout.LayoutParams(-1, 0, 1f)); addView(grip, LinearLayout.LayoutParams(-1, -2))
        }
        val p = lp((d.widthPixels * 0.88f).toInt(), (d.heightPixels * 0.42f).toInt(), focus = true).apply { gravity = Gravity.TOP or Gravity.START; x = (d.widthPixels * 0.06f).toInt(); y = 140 }
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        // drag the note by its top row
        var sx = 0; var sy = 0; var dx = 0f; var dy = 0f
        head.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = p.x; sy = p.y; dx = e.rawX; dy = e.rawY }
                MotionEvent.ACTION_MOVE -> { p.x = (sx + e.rawX - dx).toInt(); p.y = (sy + e.rawY - dy).toInt(); wm.updateViewLayout(root, p) }
            }
            true
        }
        // resize with the corner grip
        var sw = 0; var sh = 0
        grip.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sw = p.width; sh = p.height; dx = e.rawX; dy = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    p.width = (sw + e.rawX - dx).toInt().coerceIn((d.widthPixels * 0.5f).toInt(), d.widthPixels)
                    p.height = (sh + e.rawY - dy).toInt().coerceIn((d.heightPixels * 0.2f).toInt(), d.heightPixels)
                    wm.updateViewLayout(root, p)
                }
            }
            true
        }
        // autosave while typing (silent)
        var noteId: Long? = null
        var job: kotlinx.coroutines.Job? = null
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b2: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                job?.cancel()
                job = scope.launch {
                    kotlinx.coroutines.delay(700)
                    val t = title.text.toString().trim(); val bd = body.text.toString()
                    if (t.isEmpty() && bd.isBlank()) return@launch
                    val tt = t.ifBlank { bd.lines().first().take(40) }
                    val id = noteId
                    if (id == null) noteId = g.db.noteDao().insert(NoteEntity(title = tt, body = bd))
                    else g.db.noteDao().get(id)?.let { g.db.noteDao().update(it.copy(title = tt, body = bd, updatedAt = System.currentTimeMillis())) }
                    runCatching { com.privacy.app.widget.refreshNoteWidgets(this@OverlayService) }
                }
            }
        }
        title.addTextChangedListener(watcher); body.addTextChangedListener(watcher)
        notePanel = root; noteLp = p; wm.addView(root, p)
    }

    private fun closeNote() { notePanel?.let { runCatching { wm.removeView(it) } }; notePanel = null; noteLp = null }

    private fun showReport(text: String) = main.post {
        closePanel()
        val tv = TextView(this).apply { this.text = text; setTextColor(Color.BLACK); textSize = 14f }
        val sv = ScrollView(this).apply { addView(tv) }
        val close = Button(this).apply { this.text = "Close"; setOnClickListener { closePanel() } }
        val b = box().apply { addView(sv, LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.45f).toInt())); addView(close) }
        val p = lp((resources.displayMetrics.widthPixels * 0.92f).toInt(), focus = true).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = 80 }
        panel = b; wm.addView(b, p)
    }

    private fun toast(s: String) = main.post { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    private fun requestCapture() {
        val g = (application as PrivacyApp).graph
        if (!g.settings.visionReady()) { toast("Page reports need a vision-capable model. Add one in API settings."); return }
        closePanel()
        // let the bubble/panel vanish before the screenshot
        startActivity(Intent(this, ScreenCaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun capture(code: Int, data: Intent) {
        val g = (application as PrivacyApp).graph
        if (Build.VERSION.SDK_INT >= 29) startForeground(77, Notification.Builder(this, "overlay").setContentTitle("Reading this page…").setSmallIcon(android.R.drawable.ic_menu_view).build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = mpm.getMediaProjection(code, data).also { projection = it }
        val d = resources.displayMetrics
        val reader = ImageReader.newInstance(d.widthPixels, d.heightPixels, PixelFormat.RGBA_8888, 2)
        mp.registerCallback(object : MediaProjection.Callback() {}, main)
        val vd: VirtualDisplay = mp.createVirtualDisplay("pg", d.widthPixels, d.heightPixels, d.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, main)
        notesBubble?.visibility = View.INVISIBLE; assistBubble?.visibility = View.INVISIBLE
        main.postDelayed({
            val img = reader.acquireLatestImage()
            val bmp = img?.let {
                val pl = it.planes[0]; val rowPad = pl.rowStride - pl.pixelStride * d.widthPixels
                val b = Bitmap.createBitmap(d.widthPixels + rowPad / pl.pixelStride, d.heightPixels, Bitmap.Config.ARGB_8888)
                b.copyPixelsFromBuffer(pl.buffer); it.close(); Bitmap.createBitmap(b, 0, 0, d.widthPixels, d.heightPixels)
            }
            vd.release(); reader.close(); mp.stop(); projection = null
            notesBubble?.visibility = View.VISIBLE; assistBubble?.visibility = View.VISIBLE
            if (Build.VERSION.SDK_INT >= 29) startForeground(77, Notification.Builder(this, "overlay").setContentTitle("P.R.I.V.A.C.Y. floating buttons are on").setSmallIcon(android.R.drawable.ic_menu_edit).build(), if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
            if (bmp == null) { toast("Couldn't capture the screen."); return@postDelayed }
            toast("Reading this page…")
            scope.launch {
                val f = File(cacheDir, "page_capture.jpg")
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                val r = g.vision.analyze(android.net.Uri.fromFile(f), "This is a screenshot of the page the user is looking at on their phone. Give a short report: what app/page it is, the key information on it, and anything that needs attention. Be concise.")
                showReport(r.getOrElse { "Couldn't read the page: ${it.message}" })
            }
        }, 600)
    }

    override fun onDestroy() {
        closeNote(); main.removeCallbacks(jokeLoop); saying?.let { runCatching { wm.removeView(it) } }; owner?.stop(); closePanel(); notesBubble?.let { runCatching { wm.removeView(it) } }; assistBubble?.let { runCatching { wm.removeView(it) } }
        projection?.stop(); instance = null; super.onDestroy()
    }

    companion object {
        const val ACTION_PROJECTION = "projection"
        @Volatile var instance: OverlayService? = null

        /** Starts or stops the floating buttons according to the settings. */
        fun sync(ctx: Context) {
            val g = (ctx.applicationContext as PrivacyApp).graph
            val any = g.settings.notesOverlayOn.value || g.settings.assistantOverlayOn.value
            if (any && Settings.canDrawOverlays(ctx)) {
                val i = Intent(ctx, OverlayService::class.java)
                if (instance == null) ctx.startForegroundService(i) else ctx.startService(i)
            } else instance?.stopSelf() ?: ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }
}
