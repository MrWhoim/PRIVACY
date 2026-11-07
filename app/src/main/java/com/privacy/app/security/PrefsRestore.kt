package com.privacy.app.security

import android.content.Context
import android.content.SharedPreferences

/**
 * Safe restore of SharedPreferences values from a backup.
 * Backups store numbers as JSON, where a Long like 20000 comes back as an Int. Writing it as an Int makes the next
 * `getLong` crash the app at start-up. So every value is written with the SAME type the key already has, and
 * run-time state (last-run days, counters, current chat…) is never restored at all.
 */
object PrefsRestore {
    private val state = Regex("^(last_|proactive_|ha_|distracted_|tg_|conv_current|timer_|rates_)|(_day|_offset|_count|_last|_at|_end)$")

    fun isState(k: String) = state.containsMatchIn(k)

    fun put(e: SharedPreferences.Editor, existing: SharedPreferences, k: String, v: Any?) {
        if (isState(k)) return
        val old = existing.all[k]
        when (old) {
            is Long -> (v as? Number)?.let { e.putLong(k, it.toLong()) }
            is Int -> (v as? Number)?.let { e.putInt(k, it.toInt()) }
            is Float -> (v as? Number)?.let { e.putFloat(k, it.toFloat()) }
            is Boolean -> (v as? Boolean)?.let { e.putBoolean(k, it) }
            is String -> e.putString(k, v?.toString() ?: "")
            else -> when (v) {
                is String -> e.putString(k, v); is Boolean -> e.putBoolean(k, v); is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v); is Double -> e.putFloat(k, v.toFloat()); else -> Unit
            }
        }
    }

    /** Repairs an app that was already broken by an earlier import: state keys that were saved as Int become Long (or are dropped). */
    fun repair(ctx: Context) {
        listOf("dh_settings", "focus", "dh_early_points", "dh_file_defaults", "health_profile").forEach { name ->
            runCatching {
                val sp = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
                val e = sp.edit(); var dirty = false
                sp.all.forEach { (k, v) ->
                    if (isState(k) && v is Int) { if (k == "proactive_count") return@forEach; e.remove(k); dirty = true }
                    if (isState(k) && v is Float) { e.remove(k); dirty = true }
                }
                if (dirty) e.commit()
            }
        }
    }
}
