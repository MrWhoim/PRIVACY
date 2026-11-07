package com.privacy.app.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.DecimalFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Small offline-first helpers shared by the Tools screen and the assistant's tools. Nothing here needs an API key. */

/** Exchange rates from a free, key-less endpoint, cached for 12 hours and usable offline afterwards. */
object CurrencyRates {
    private const val PREFS = "privacy_tools"
    private const val URL_RATES = "https://open.er-api.com/v6/latest/USD"
    private const val MAX_AGE = 12 * 3600_000L

    data class Rates(val perUsd: Map<String, Double>, val fetchedAt: Long)

    val popular = listOf("USD", "EUR", "GBP", "INR", "JPY", "AUD", "CAD", "CHF", "CNY", "AED", "SGD", "SAR", "KRW", "BRL", "RUB", "ZAR", "NZD", "HKD", "SEK", "THB")

    fun cached(ctx: Context): Rates? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = p.getString("rates_json", null) ?: return null
        return try {
            val o = JSONObject(raw)
            val m = HashMap<String, Double>()
            o.keys().forEach { m[it] = o.getDouble(it) }
            Rates(m, p.getLong("rates_at", 0L))
        } catch (_: Exception) { null }
    }

    /** Fresh rates when the cache is old (or [force]); falls back to the cache if the network is down. */
    suspend fun load(ctx: Context, force: Boolean = false): Rates? = withContext(Dispatchers.IO) {
        val old = cached(ctx)
        if (!force && old != null && System.currentTimeMillis() - old.fetchedAt < MAX_AGE) return@withContext old
        try {
            val c = URL(URL_RATES).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val rates = JSONObject(body).getJSONObject("rates")
            val now = System.currentTimeMillis()
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("rates_json", rates.toString()).putLong("rates_at", now).apply()
            cached(ctx) ?: old
        } catch (_: Exception) { old }
    }

    fun convert(r: Rates, amount: Double, from: String, to: String): Double? {
        val a = r.perUsd[from.uppercase()] ?: return null
        val b = r.perUsd[to.uppercase()] ?: return null
        return amount / a * b
    }

    fun fmt(v: Double): String = if (kotlin.math.abs(v) >= 1000) DecimalFormat("#,##0.00").format(v) else DecimalFormat("#,##0.####").format(v)

    /** Text answer for the assistant. */
    suspend fun describe(ctx: Context, amount: Double, from: String, to: String): String {
        val r = load(ctx) ?: return "No exchange rates are available (no internet and nothing saved yet)."
        val v = convert(r, amount, from, to) ?: return "I don't have a rate for ${from.uppercase()} or ${to.uppercase()}."
        val age = ((System.currentTimeMillis() - r.fetchedAt) / 3600_000L).coerceAtLeast(0)
        return "${fmt(amount)} ${from.uppercase()} = ${fmt(v)} ${to.uppercase()} (rates ${if (age < 1) "just now" else "$age h old"})."
    }
}

object CalendarTools {
    data class Ev(val title: String, val start: Long, val end: Long, val allDay: Boolean, val calendar: String)

    fun hasRead(ctx: Context) = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED
    fun hasWrite(ctx: Context) = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.WRITE_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Events of the phone's real calendars (Google calendar included, whatever is synced to the phone) between two days. */
    fun events(ctx: Context, from: LocalDate, to: LocalDate): List<Ev> {
        if (!hasRead(ctx)) return emptyList()
        val zone = ZoneId.systemDefault()
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        android.content.ContentUris.appendId(b, from.atStartOfDay(zone).toInstant().toEpochMilli())
        android.content.ContentUris.appendId(b, to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        val out = mutableListOf<Ev>()
        runCatching {
            ctx.contentResolver.query(b.build(), arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.CALENDAR_DISPLAY_NAME), null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
                while (c.moveToNext()) out += Ev(c.getString(0).orEmpty().ifBlank { "(no title)" }, c.getLong(1), c.getLong(2), c.getInt(3) == 1, c.getString(4).orEmpty())
            }
        }
        return out
    }

    /** Writes an event straight into the phone's calendar (needs the calendar permission); false if that is not possible. */
    fun insertDirect(ctx: Context, title: String, start: LocalDateTime, minutes: Int = 60, allDay: Boolean = false): Boolean {
        if (!hasWrite(ctx) || !hasRead(ctx)) return false
        return runCatching {
            val calId = ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID),
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR} AND ${CalendarContract.Calendars.VISIBLE} = 1", null,
                "${CalendarContract.Calendars.IS_PRIMARY} DESC")?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: return false
            val zone = ZoneId.systemDefault()
            val s = if (allDay) start.toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() else start.atZone(zone).toInstant().toEpochMilli()
            val v = android.content.ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId); put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, s); put(CalendarContract.Events.DTEND, if (allDay) s + 86_400_000L else s + minutes * 60_000L)
                put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else zone.id); put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            }
            ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) != null
        }.getOrDefault(false)
    }

    private fun launch(ctx: Context, i: Intent): String = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "ok"
    } catch (e: Exception) { "No calendar app could open it (${e.message ?: "nothing installed"})." }

    /** Opens the phone's calendar app on a day. */
    fun openDay(ctx: Context, day: LocalDate): String {
        val ms = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val uri = Uri.parse("content://com.android.calendar/time/$ms")
        val r = launch(ctx, Intent(Intent.ACTION_VIEW, uri))
        return if (r == "ok") "Opened the calendar on $day." else r
    }

    /** Opens the calendar app's "new event" screen already filled in; the user taps Save (no calendar permission needed). */
    fun addEvent(ctx: Context, title: String, start: LocalDateTime, minutes: Int = 60, notes: String = ""): String {
        val s = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.Events.DESCRIPTION, notes)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, s)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, s + minutes * 60_000L)
        val r = launch(ctx, i)
        return if (r == "ok") "Opened the new-event screen for \"$title\" at $start. Tap Save there to confirm." else r
    }
}

/** The countdown shown on the Tools screen. The ring itself comes from the phone's clock app so it still sounds when this app is closed. */
object TimerState {
    private const val PREFS = "privacy_tools"
    private const val CH = "countdown"
    fun endAt(ctx: Context): Long = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("timer_end", 0L)
    fun label(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("timer_label", "") ?: ""

    private fun pending(ctx: Context): android.app.PendingIntent =
        android.app.PendingIntent.getBroadcast(ctx, 4711, Intent(ctx, CountdownReceiver::class.java), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)

    /** The countdown is this app's own alarm (not the phone's clock app), so Cancel really stops it. */
    fun start(ctx: Context, seconds: Int, label: String): String {
        val end = System.currentTimeMillis() + seconds * 1000L
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("timer_end", end).putString("timer_label", label).apply()
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        // the "show" intent of an alarm clock must open an ACTIVITY (a broadcast there crashes some Samsung system apps)
        val show = android.app.PendingIntent.getActivity(ctx, 4713, Intent(ctx, com.privacy.app.MainActivity::class.java).putExtra("open_screen", "tools").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        val ok = runCatching { am.setAlarmClock(android.app.AlarmManager.AlarmClockInfo(end, show), pending(ctx)) }.isSuccess
        if (!ok) runCatching { am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, end, pending(ctx)) }
            .onFailure { runCatching { am.set(android.app.AlarmManager.RTC_WAKEUP, end, pending(ctx)) } }
        return "Timer started."
    }

    fun cancel(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("timer_end", 0L).apply()
        (ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).cancel(pending(ctx))
        androidx.core.app.NotificationManagerCompat.from(ctx).cancel(4711)
    }

    /** Called when the time is up. */
    @android.annotation.SuppressLint("MissingPermission")
    fun ring(ctx: Context) = runCatching { ringNow(ctx) }.let { }
    private fun ringNow(ctx: Context) {
        val label = label(ctx)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("timer_end", 0L).apply()
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        val sound = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
        nm.createNotificationChannel(android.app.NotificationChannel(CH, "Countdown", android.app.NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(sound, android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build())
            enableVibration(true); vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
        })
        val open = android.app.PendingIntent.getActivity(ctx, 4712, Intent(ctx, com.privacy.app.MainActivity::class.java).putExtra("open_screen", "tools").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), android.app.PendingIntent.FLAG_IMMUTABLE)
        if (androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
            androidx.core.app.NotificationManagerCompat.from(ctx).notify(4711, androidx.core.app.NotificationCompat.Builder(ctx, CH)
                .setSmallIcon(com.privacy.app.R.drawable.ic_stat_privacy).setContentTitle("Time is up").setContentText(label.ifBlank { "Your countdown finished." })
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH).setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(open).setAutoCancel(true).build())
        }
    }
}

class CountdownReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { TimerState.ring(context) }
}
