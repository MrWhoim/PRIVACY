package com.privacy.app.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.privacy.app.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * "Find that file on my phone": the assistant searches by name, lists the matches and opens the one the user picks.
 * mp3 / audio → the in-app music player · pictures and Markdown (and plain text) → viewed inside the app · anything else → another app
 * (the app the user names, a default they set for that file type, or the phone's own chooser).
 */
object FileFinder {
    data class Found(val name: String, val uri: Uri?, val file: File?, val mime: String, val size: Long, val modified: Long) {
        val ext: String get() = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val where: String get() = file?.parentFile?.absolutePath?.removePrefix(Environment.getExternalStorageDirectory().absolutePath)?.ifBlank { "/" } ?: "phone storage"
    }

    @Volatile var last: List<Found> = emptyList()
    @Volatile var lastChatImage: String? = null
    fun takeChatImage(): String? = lastChatImage.also { lastChatImage = null }
    private val audioExt = setOf("mp3", "m4a", "aac", "wav", "ogg", "opus", "flac", "wma", "amr")
    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")
    private val textExt = setOf("md", "markdown", "txt")

    fun hasAllFiles(): Boolean = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    private fun words(q: String) = q.lowercase().split(Regex("[^\\p{L}\\p{N}.]+")).filter { it.length > 1 && it !in setOf("find", "file", "the", "my", "named", "called", "open") }

    suspend fun search(ctx: Context, query: String, limit: Int = 8): List<Found> = withContext(Dispatchers.IO) {
        val w = words(query)
        if (w.isEmpty()) return@withContext emptyList()
        val out = LinkedHashMap<String, Found>()
        // 1) media library (music, pictures, video, downloads that Android indexes)
        runCatching {
            val sel = w.joinToString(" AND ") { "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?" }
            val args = w.map { "%$it%" }.toTypedArray()
            val proj = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.MIME_TYPE, MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.DATE_MODIFIED, MediaStore.Files.FileColumns.DATA)
            ctx.contentResolver.query(MediaStore.Files.getContentUri("external"), proj, sel, args, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use { c ->
                while (c.moveToNext() && out.size < limit * 2) {
                    val id = c.getLong(0); val name = c.getString(1) ?: continue
                    val data = c.getString(5)
                    val uri = android.content.ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)
                    out.putIfAbsent(data ?: uri.toString(), Found(name, uri, data?.let { File(it) }, c.getString(2) ?: "", c.getLong(3), c.getLong(4) * 1000))
                }
            }
        }
        // 2) everything else (PDF, docs, zip…) – only possible when the user allowed "all files access"
        if (hasAllFiles()) runCatching {
            var seen = 0
            Environment.getExternalStorageDirectory().walkTopDown().onEnter { it.name != "Android" && !it.name.startsWith(".") }.forEach { f ->
                if (++seen > 60_000 || out.size >= limit * 3) return@forEach
                if (f.isFile) { val n = f.name.lowercase(); if (w.all { n.contains(it) }) out.putIfAbsent(f.absolutePath, Found(f.name, null, f, "", f.length(), f.lastModified())) }
            }
        }
        out.values.sortedByDescending { it.modified }.take(limit).also { last = it }
    }

    fun describe(list: List<Found>): String = list.mapIndexed { i, f ->
        "${i + 1}. ${f.name} · ${f.where} · ${if (f.size > 1_048_576) "%.1f MB".format(f.size / 1_048_576.0) else "${f.size / 1024} KB"}"
    }.joinToString("\n")

    // ---- default apps per file type ----
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("dh_file_defaults", Context.MODE_PRIVATE)
    fun setDefault(ctx: Context, ext: String, appPackage: String) { prefs(ctx).edit().putString(ext.lowercase().removePrefix("."), appPackage).apply() }

    private fun shareUri(ctx: Context, f: Found): Uri? = f.uri ?: f.file?.let { runCatching { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", it) }.getOrNull() }

    /** Opens the n-th result of the last search. [app] = an installed app's name to use for files this app can't show itself. */
    fun open(ctx: Context, f: Found, app: String? = null): String {
        val ext = f.ext
        if (ext in audioExt || f.mime.startsWith("audio/")) {
            val u = f.uri ?: f.file?.let { Uri.fromFile(it) } ?: return "Couldn't read that file."
            MusicPlayer.init(ctx); MusicPlayer.playUri(ctx, u); MusicUi.focus.value = true
            return "Playing ${f.name} in the app's music player."
        }
        if (ext in imageExt || f.mime.startsWith("image/")) {
            val u = f.uri ?: f.file?.let { Uri.fromFile(it) } ?: return "Couldn't read that picture."
            // a copy is kept inside the app so the chat can show the picture itself (tap = full screen)
            lastChatImage = runCatching {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(u)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
                var ss = 1; while (o.outWidth / ss > 1800 || o.outHeight / ss > 1800) ss *= 2
                val bmp = ctx.contentResolver.openInputStream(u)!!.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = ss }) }!!
                val dir = File(ctx.filesDir, "chat_images").apply { mkdirs() }
                val name = "open-${System.currentTimeMillis()}.jpg"
                File(dir, name).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                name
            }.getOrNull()
            if (lastChatImage == null) AppState.imageRequest.value = u
            return "Showing ${f.name} in the chat."
        }
        if (ext in textExt) {
            val text = runCatching {
                (f.file?.inputStream() ?: ctx.contentResolver.openInputStream(f.uri!!))!!.use { it.readBytes().take(600_000).toByteArray().toString(Charsets.UTF_8) }
            }.getOrNull() ?: return "Couldn't read that file."
            AppState.mdRequest.value = f.name.substringBeforeLast('.') to text
            return "Showing ${f.name} in the app."
        }
        val uri = shareUri(ctx, f) ?: return "Couldn't prepare that file for another app."
        val mime = f.mime.ifBlank { android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*" }
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pm = ctx.packageManager
        view.clipData = android.content.ClipData.newRawUri("", uri)
        val handlers = pm.queryIntentActivities(view, 0)
        fun chooser(): String = runCatching {
            ctx.startActivity(Intent.createChooser(Intent(view).setPackage(null), "Open ${f.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "Asked which app should open ${f.name}."
        }.getOrElse { "No app on this phone can open .$ext files. Install one (for example a PDF reader) and try again." }
        if (handlers.isEmpty()) return chooser()
        val chosenPkg: String? = when {
            !app.isNullOrBlank() -> handlers.firstOrNull { it.loadLabel(pm).toString().contains(app, true) }?.activityInfo?.packageName
                ?: return "No app called \"$app\" can open it. Options: ${handlers.joinToString { it.loadLabel(pm).toString() }}."
            prefs(ctx).getString(ext, null)?.let { p -> handlers.any { it.activityInfo.packageName == p } } == true -> prefs(ctx).getString(ext, null)
            handlers.map { it.activityInfo.packageName }.distinct().size == 1 -> handlers[0].activityInfo.packageName
            else -> return "NEED_APP_CHOICE: ${f.name} can be opened by: ${handlers.map { it.loadLabel(pm).toString() }.distinct().joinToString()}. Ask the user which app to use (and whether to make it the default for .$ext files), then call open_file again with app=<name>."
        }
        return runCatching {
            ctx.startActivity(Intent(view).setPackage(chosenPkg)); "Opened ${f.name} in ${pm.getApplicationLabel(pm.getApplicationInfo(chosenPkg!!, 0))}."
        }.getOrElse { chooser() }
    }
}

/** UI hand-off: asks the chat header to switch to the music page. */
object MusicUi {
    val focus = androidx.compose.runtime.mutableStateOf(false)
    /** "Music Mode" (Modes button): the player page is showing under the chat header. */
    val mode = androidx.compose.runtime.mutableStateOf(false)
}
