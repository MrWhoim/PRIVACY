package com.privacy.app.security

import android.content.Context
import androidx.work.*
import com.privacy.app.AppGraph
import com.privacy.app.PrivacyApp
import com.privacy.app.ai.Net
import com.privacy.app.credits.Credits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Daily encrypted cloud backup for monthly subscribers.
 * - Encrypted on the phone with the user's backup password (AES-256-GCM, PBKDF2) – the server only stores unreadable bytes.
 * - NO pictures. Vault and API keys are not included either.
 * - One backup per user on the server: a new one replaces the old one. Nothing is sent when nothing changed.
 * - The password is kept on this phone only, encrypted with the Android Keystore, so the daily run needs no typing.
 */
object CloudBackup {
    data class State(val enabled: Boolean = false, val hasPassword: Boolean = false, val lastAt: Long = 0L, val message: String = "", val running: Boolean = false)

    private fun prefs(c: Context) = c.getSharedPreferences("cloud_backup", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun load(c: Context = Net.ctx!!) {
        val p = prefs(c)
        _state.value = _state.value.copy(enabled = p.getBoolean("enabled", false), hasPassword = p.getString("pw", null) != null, lastAt = p.getLong("last_at", 0L), message = p.getString("msg", "").orEmpty())
    }

    private fun password(c: Context): CharArray? = prefs(c).getString("pw", null)?.let { SecretStore(c).decrypt(it) }?.toCharArray()

    fun setPassword(c: Context, pw: String) {
        prefs(c).edit().putString("pw", SecretStore(c).encrypt(pw)).remove("last_hash").apply(); load(c)
    }

    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("enabled", on).apply(); load(c)
        if (on) schedule(c) else WorkManager.getInstance(c).cancelUniqueWork("cloud_backup")
    }

    fun schedule(c: Context) {
        if (!prefs(c).getBoolean("enabled", false)) return
        val req = PeriodicWorkRequestBuilder<CloudBackupWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("cloud_backup", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** On sign-out: stop and forget the saved password. */
    fun forget() {
        val c = Net.ctx ?: return
        prefs(c).edit().clear().apply(); WorkManager.getInstance(c).cancelUniqueWork("cloud_backup"); load(c)
    }

    private fun note(c: Context, msg: String, ok: Boolean) {
        val e = prefs(c).edit().putString("msg", msg)
        if (ok) e.putLong("last_at", System.currentTimeMillis())
        e.apply(); load(c)
    }

    /** Returns a short human message. `force` uploads even if nothing changed. */
    suspend fun run(g: AppGraph, force: Boolean = false): String = withContext(Dispatchers.IO) {
        val c = g.context
        _state.value = _state.value.copy(running = true)
        try {
            if (!Credits.signedIn) return@withContext "Sign in with Google first.".also { note(c, it, false) }
            Credits.refresh()
            if (!Credits.sub.value.planActive) return@withContext "Cloud backup needs an active monthly plan.".also { note(c, it, false) }
            val pw = password(c) ?: return@withContext "Set your backup password first.".also { note(c, it, false) }
            val opt = ExportOptions(tasks = true, notes = true, memories = true, chat = true, vault = false, routines = true, apiKeys = false)
            val (bytes, fp) = g.backup.exportWithHash(opt, pw)
            val p = prefs(c)
            if (!force && p.getString("last_hash", null) == fp) return@withContext "No changes since the last backup – nothing sent.".also { note(c, it, true) }
            val body = JSONObject().put("p_hash", fp).put("p_data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)).toString()
            val r = Credits.rpc("cloud_backup_put", body) ?: return@withContext "Couldn't reach the server – will retry.".also { note(c, it, false) }
            if (r.first !in 200..299) return@withContext "Backup failed (HTTP ${r.first}).".also { note(c, it, false) }
            val o = JSONObject(r.second)
            if (!o.optBoolean("ok")) return@withContext "Backup refused: ${o.optString("error")}".also { note(c, it, false) }
            p.edit().putString("last_hash", fp).apply()
            "Backed up (${bytes.size / 1024} KB, encrypted, no pictures).".also { note(c, it, true) }
        } catch (e: Exception) {
            "Backup failed: ${e.message}".also { note(c, it, false) }
        } finally { _state.value = _state.value.copy(running = false) }
    }

    /** Downloads the cloud copy and merges it into the phone (nothing is overwritten). */
    suspend fun restore(g: AppGraph, password: String): String = withContext(Dispatchers.IO) {
        try {
            val r = Credits.rpc("cloud_backup_get") ?: return@withContext "Couldn't reach the server."
            if (r.first !in 200..299) return@withContext "Couldn't download (HTTP ${r.first})."
            val b64 = r.second.trim().removeSurrounding("\"")
            if (b64 == "null" || b64.isBlank()) return@withContext "There is no cloud backup for this account (or the plan ended more than 14 days ago)."
            val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
            g.backup.import(bytes, password.toCharArray())
        } catch (e: Exception) { "Restore failed: ${e.message}" }
    }

    suspend fun info(): String? = withContext(Dispatchers.IO) {
        val r = Credits.rpc("cloud_backup_info") ?: return@withContext null
        if (r.first !in 200..299 || r.second.trim() == "null") null else r.second
    }
}

class CloudBackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val g = (applicationContext as PrivacyApp).graph
        val msg = CloudBackup.run(g)
        return if (msg.startsWith("Couldn't reach") || msg.startsWith("Backup failed")) Result.retry() else Result.success()
    }
}
