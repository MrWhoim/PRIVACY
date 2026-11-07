package com.privacy.app.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import com.privacy.app.AppGraph
import kotlinx.coroutines.Dispatchers
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Focus and Distraction of a day.
 *
 *   Focus %       = 100 × (30 + P) / (60 + P + D)
 *   Distraction % = 100 × (30 + D) / (60 + P + D)       (the two always add up to 100)
 *
 * P = useful time × its weight (focused work / tasks 1.0 · learning 0.9 · exercise 1.2 · useful personal 0.7), × 1.25 in the first two hours after waking,
 *     + 5 points per completed task (at most 15 a day).
 * D = distracting time × category weight (short videos 1.2 · social 1.0 · entertainment video 0.8 · games 0.9)
 *     × waste rating (useful 0.2 · somewhat relevant 0.45 · low value 0.75 · mindless 1.0)
 *     × timing (first two hours after waking 1.5 · normal 1.0 · 30 min before a nap 0.45 · last 90 min before bedtime 0.65).
 * Every app is tracked. Known distracting apps (and games) count as D, known useful apps (learning, fitness, productivity) count as P, and any other app
 * is "not scored" until the user decides what it is (Distracting / Useful / Ignore) – it never costs credit by itself.
 * A distracting app the user rates "Useful" for that day (YouTube for study…) is credited as learning time (weight 0.9) instead of costing dopamine.
 * The wake-up time comes from the Sleep entry (or the time you set by hand); without data the usual time of the last days is used.
 */
object FocusCfg {
    val DIST_CATS = listOf("short" to "Short videos", "social" to "Social media", "video" to "Entertainment video", "game" to "Games")
    val DIST_W = mapOf("short" to 1.2, "social" to 1.0, "video" to 0.8, "game" to 0.9)
    val POS_CATS = listOf("work" to "Focused work / task", "learning" to "Learning", "exercise" to "Exercise", "personal" to "Useful personal task")
    val POS_W = mapOf("work" to 1.0, "learning" to 0.9, "exercise" to 1.2, "personal" to 0.7)
    val RATINGS = listOf("useful" to "Useful (counts as learning time)", "relevant" to "Somewhat relevant", "low" to "Low value", "mindless" to "Mindless / nonsense")
    val RATING_W = mapOf("useful" to 0.2, "relevant" to 0.45, "low" to 0.75, "mindless" to 1.0)
    val DEFAULT_APPS: Map<String, String> = mapOf(
        "com.google.android.youtube" to "video", "com.netflix.mediaclient" to "video", "in.startv.hotstar" to "video",
        "com.instagram.android" to "social", "com.facebook.katana" to "social", "com.facebook.lite" to "social",
        "com.twitter.android" to "social", "com.snapchat.android" to "social", "com.reddit.frontpage" to "social", "com.pinterest" to "social",
        "com.zhiliaoapp.musically" to "short", "com.ss.android.ugc.trill" to "short", "in.mohalla.video" to "short"
    )
    /** Apps that count as useful time out of the box (best effort – the user can change any of them). */
    val DEFAULT_USEFUL: Map<String, String> = mapOf(
        "com.duolingo" to "learning", "org.khanacademy.android" to "learning", "org.coursera.android" to "learning", "com.udemy.android" to "learning",
        "com.google.android.apps.classroom" to "learning", "com.byjus.thelearningapp" to "learning", "com.unacademyapp" to "learning", "com.quizlet.quizletandroid" to "learning",
        "com.google.android.apps.books" to "learning", "com.amazon.kindle" to "learning", "com.microsoft.teams" to "work", "com.google.android.apps.docs" to "work",
        "com.google.android.apps.docs.editors.docs" to "work", "com.google.android.apps.docs.editors.sheets" to "work", "com.microsoft.office.word" to "work",
        "com.microsoft.office.excel" to "work", "notion.id" to "work", "com.todoist" to "work", "com.google.android.keep" to "work", "com.github.android" to "work",
        "com.google.android.apps.fitness" to "exercise", "com.strava" to "exercise", "com.fitbit.FitbitMobile" to "exercise", "com.healthifyme.basic" to "exercise",
        "com.nike.ntc" to "exercise", "com.nike.plusgps" to "exercise", "com.runtastic.android" to "exercise", "fit.cult.android" to "exercise",
        "com.headspace.android" to "personal", "com.calm.android" to "personal"
    )
    fun catLabel(k: String) = (DIST_CATS + POS_CATS).firstOrNull { it.first == k }?.second ?: k
    fun ratingLabel(k: String) = RATINGS.firstOrNull { it.first == k }?.second ?: k
}

class FocusRepo(private val g: AppGraph) {
    private val ctx: Context get() = g.context
    private val prefs get() = ctx.getSharedPreferences("focus", Context.MODE_PRIVATE)
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** Bumped after every change so screens and widgets can refresh. */
    val rev = MutableStateFlow(0)
    fun bump() {
        rev.value = rev.value + 1
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { runCatching { com.privacy.app.widget.FocusWidget().updateAll(ctx) } }
    }

    // ------------------------------------------------------------------ settings
    var guardOn: Boolean
        get() = prefs.getBoolean("guard", false)
        set(v) { prefs.edit().putBoolean("guard", v).apply(); com.privacy.app.work.FocusWatch.schedule(ctx, true); bump() }

    /** Dopamine vs Detox graph: false = contrast (compact, default), true = expanded. Remembered. */
    var graphExpanded: Boolean
        get() = prefs.getBoolean("graph_expanded", false)
        set(v) { prefs.edit().putBoolean("graph_expanded", v).apply(); bump() }

    fun dayStart(day: Long): Long = LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli()

    fun hasUsageAccess(): Boolean = runCatching {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    // ---- which apps count as distracting
    fun trackedApps(): Map<String, String> {
        val removed = runCatching { JSONArray(prefs.getString("removed", "[]")) }.getOrDefault(JSONArray()).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        val custom = runCatching { JSONObject(prefs.getString("apps", "{}")) }.getOrDefault(JSONObject())
        val m = LinkedHashMap<String, String>()
        FocusCfg.DEFAULT_APPS.forEach { (k, v) -> if (k !in removed) m[k] = v }
        custom.keys().forEach { val v = custom.getString(it); if (!v.startsWith("pos:") && v != "ignore") m[it] = v else m.remove(it) }
        return m
    }
    private fun custom(): JSONObject = runCatching { JSONObject(prefs.getString("apps", "{}")) }.getOrDefault(JSONObject())

    /** How an app counts: ("DIST", category) · ("POS", category) · ("IGN", "") · null = not scored yet (the user decides). */
    fun modeOf(pkg: String): Pair<String, String>? {
        val v = custom().optString(pkg, "")
        if (v == "ignore") return "IGN" to ""
        if (v.startsWith("pos:")) return "POS" to v.removePrefix("pos:")
        if (v.isNotBlank()) return "DIST" to v
        trackedApps()[pkg]?.let { return "DIST" to it }
        FocusCfg.DEFAULT_USEFUL[pkg]?.let { return "POS" to it }
        if (isGame(pkg)) return "DIST" to "game"
        return null
    }
    /** kind: DIST | POS | IGN | UNSET (back to the default). */
    fun setAppMode(pkg: String, kind: String, cat: String = "") {
        val c = custom()
        val removed = runCatching { JSONArray(prefs.getString("removed", "[]")) }.getOrDefault(JSONArray())
        val keep = JSONArray(); for (i in 0 until removed.length()) if (removed.getString(i) != pkg) keep.put(removed.getString(i))
        when (kind) {
            "DIST" -> c.put(pkg, cat.ifBlank { "social" })
            "POS" -> c.put(pkg, "pos:" + cat.ifBlank { "learning" })
            "IGN" -> c.put(pkg, "ignore")
            else -> c.remove(pkg)
        }
        prefs.edit().putString("apps", c.toString()).putString("removed", keep.toString()).apply(); bump()
    }
    private val launchCache = HashMap<String, Boolean>()
    /** A normal app the user can open (not the keyboard, the system UI, this app or the launcher). */
    private fun isUserApp(pkg: String): Boolean = launchCache.getOrPut(pkg) {
        pkg != ctx.packageName && runCatching { ctx.packageManager.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false) &&
            !pkg.startsWith("com.android.systemui") && !pkg.contains("launcher", true)
    }
    fun addApp(pkg: String, cat: String) {
        val custom = runCatching { JSONObject(prefs.getString("apps", "{}")) }.getOrDefault(JSONObject()).put(pkg, cat)
        val removed = runCatching { JSONArray(prefs.getString("removed", "[]")) }.getOrDefault(JSONArray())
        val keep = JSONArray(); for (i in 0 until removed.length()) if (removed.getString(i) != pkg) keep.put(removed.getString(i))
        prefs.edit().putString("apps", custom.toString()).putString("removed", keep.toString()).apply(); bump()
    }
    fun removeApp(pkg: String) {
        val custom = runCatching { JSONObject(prefs.getString("apps", "{}")) }.getOrDefault(JSONObject()); custom.remove(pkg)
        val removed = runCatching { JSONArray(prefs.getString("removed", "[]")) }.getOrDefault(JSONArray())
        if (pkg in FocusCfg.DEFAULT_APPS) removed.put(pkg)
        prefs.edit().putString("apps", custom.toString()).putString("removed", removed.toString()).apply(); bump()
    }
    private val gameCache = HashMap<String, Boolean>()
    private fun isGame(pkg: String): Boolean = gameCache.getOrPut(pkg) {
        runCatching { Build.VERSION.SDK_INT >= 26 && ctx.packageManager.getApplicationInfo(pkg, 0).category == ApplicationInfo.CATEGORY_GAME }.getOrDefault(false)
    }
    fun catOf(pkg: String): String? = modeOf(pkg)?.takeIf { it.first == "DIST" }?.second
    fun appLabel(pkg: String): String = runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    // ---- how the user rates the app time of a day
    fun ratingOf(day: Long, pkg: String): String = prefs.getString("r|$day|$pkg", null) ?: "mindless"
    fun hasRating(day: Long, pkg: String): Boolean = prefs.contains("r|$day|$pkg")
    fun setRating(day: Long, pkg: String, rating: String) { prefs.edit().putString("r|$day|$pkg", rating).apply(); bump() }

    // ------------------------------------------------------------------ sleep-based times
    fun setWake(day: Long, minuteOfDay: Int) { prefs.edit().putInt("wake_$day", minuteOfDay).apply(); bump() }
    fun clearWake(day: Long) { prefs.edit().remove("wake_$day").apply(); bump() }
    fun hasManualWake(day: Long) = prefs.getInt("wake_$day", -1) >= 0

    private suspend fun explicitWakeMin(day: Long): Int? {
        prefs.getInt("wake_$day", -1).takeIf { it >= 0 }?.let { return it }
        val e = g.db.healthDao().forDay(day).firstOrNull { it.category == "SLEEP" && !it.label.contains("nap", true) } ?: return null
        val t = Instant.ofEpochMilli(e.createdAt).atZone(zone).toLocalTime()
        return if (t.hour < 12) t.hour * 60 + t.minute else null   // logged when waking up; a late log says nothing about the time
    }
    suspend fun typicalWakeMin(day: Long): Int {
        val v = (1..14).mapNotNull { explicitWakeMin(day - it) }.take(7)
        return if (v.isEmpty()) 7 * 60 else v.average().toInt()
    }
    suspend fun wakeMs(day: Long): Long = dayStart(day) + (explicitWakeMin(day) ?: typicalWakeMin(day)) * 60_000L
    suspend fun wakeKnown(day: Long): Boolean = explicitWakeMin(day) != null
    suspend fun typicalSleepH(day: Long): Double {
        val h = (0..13).mapNotNull { d -> g.db.healthDao().forDay(day - d).firstOrNull { it.category == "SLEEP" && !it.label.contains("nap", true) }?.amount }.take(7)
        return if (h.isEmpty()) 8.0 else h.average()
    }
    /** The usual time to go to bed that evening (can be after midnight): the usual wake-up time of tomorrow minus the usual sleep length. */
    suspend fun bedMs(day: Long): Long {
        val wakeNext = typicalWakeMin(day + 1)
        val bedMin = (((wakeNext - typicalSleepH(day) * 60).toInt()) % 1440 + 1440) % 1440
        return dayStart(day) + bedMin * 60_000L + (if (bedMin < 12 * 60) 86_400_000L else 0L)
    }
    private suspend fun napWindows(day: Long): List<LongRange> =
        g.db.healthDao().forDay(day).filter { it.category == "SLEEP" && it.label.contains("nap", true) }.map {
            val start = it.createdAt - ((it.amount ?: 0.5) * 3_600_000).toLong()
            (start - 30 * 60_000L)..start
        }

    // ------------------------------------------------------------------ app usage (UsageStatsManager)
    class Sess(val startAt: Long, val endAt: Long, val pkg: String)

    fun sessions(day: Long, onlyTracked: Boolean = true): List<Sess> {
        if (!hasUsageAccess()) return emptyList()
        val from = dayStart(day); val now = System.currentTimeMillis()
        val to = minOf(from + 86_400_000L, now)
        if (to <= from) return emptyList()
        val um = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val out = ArrayList<Sess>()
        runCatching {
            val ev = um.queryEvents(from, to); val e = UsageEvents.Event()
            val open = HashMap<String, Long>()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                val pkg = e.packageName ?: continue
                when (e.eventType) {
                    1 -> open[pkg] = e.timeStamp                                   // activity resumed (moved to foreground)
                    2 -> open.remove(pkg)?.let { st -> if (e.timeStamp - st >= 3_000 && (if (onlyTracked) catOf(pkg) != null else isUserApp(pkg))) out += Sess(st, e.timeStamp, pkg) }
                }
            }
            open.forEach { (pkg, st) -> if (to - st >= 3_000 && (if (onlyTracked) catOf(pkg) != null else isUserApp(pkg))) out += Sess(st, to, pkg) }
        }
        return out.sortedBy { it.startAt }
    }

    // ------------------------------------------------------------------ the calculation
    class Item(
        val kind: String, val cat: String, val label: String, val minutes: Float, val points: Float, val source: String,
        val pkg: String = "", val logId: Long = 0, val rating: String = "", val startAt: Long = 0, val note: String = "",
        /** App items: DIST | POS | UNRATED, and minutes / credits for each hour of the day (credits: − dopamine, + detox). */
        val mode: String = "", val hourMin: FloatArray? = null, val hourPts: FloatArray? = null
    )
    class Result(
        val day: Long, val p: Float, val d: Float, val enough: Boolean,
        val items: List<Item>, val hourP: FloatArray, val hourD: FloatArray,
        val wakeAt: Long, val morningEnd: Long, val bedAt: Long, val wakeKnown: Boolean,
        val usageAccess: Boolean, val doneTasks: Int, val taskBonus: Float, val morningDistMin: Float, val distMin: Float, val posMin: Float
    ) {
        val focus: Float? get() = if (enough) 100f * (30f + p) / (60f + p + d) else null
        val dist: Float? get() = if (enough) 100f * (30f + d) / (60f + p + d) else null
        /** Focus % reached after the first [h] hours of the day (for the graph). */
        fun focusAt(h: Int): Float { var pp = 0f; var dd = 0f; for (i in 0 until h.coerceIn(0, 24)) { pp += hourP[i]; dd += hourD[i] }; return 100f * (30f + pp) / (60f + pp + dd) }
        fun distAt(h: Int): Float = 100f - focusAt(h)
    }

    suspend fun evaluate(day: Long): Result = withContext(Dispatchers.IO) {
        val ds = dayStart(day)
        val wake = wakeMs(day); val morningEnd = wake + 2 * 3_600_000L
        val bed = bedMs(day); val bedFrom = bed - 90 * 60_000L
        val naps = napWindows(day)
        fun timing(t: Long): Pair<Double, String> = when {
            t in wake until morningEnd -> 1.5 to "morning"
            naps.any { t in it } -> 0.45 to "before a nap"
            t >= bedFrom -> 0.65 to "late evening"
            else -> 1.0 to ""
        }
        fun hourOf(t: Long) = ((t - ds) / 3_600_000L).toInt().coerceIn(0, 23)
        val hp = FloatArray(24); val hd = FloatArray(24)
        val items = ArrayList<Item>()
        var p = 0.0; var d = 0.0; var posMin = 0f; var distMin = 0f; var morningDist = 0f

        fun posPoints(minutes: Double, w: Double, at: Long?): Double {
            val m = if (at != null && at in wake until morningEnd) 1.25 else 1.0
            val pts = minutes * w * m
            p += pts; posMin += minutes.toFloat()
            if (at != null) hp[hourOf(at)] += pts.toFloat() else hp[hourOf(wake + 3_600_000L)] += pts.toFloat()
            return pts
        }

        // 1) completed tasks
        var done = 0
        val today = LocalDate.now().toEpochDay()
        data class T(val title: String, val diff: Int, val at: Long?)
        val tasks: List<T> = if (day == today)
            g.db.taskDao().activeNow().filter { it.status == TaskStatus.COMPLETED }.map { T(it.title, it.difficulty, it.completedAt?.takeIf { c -> c >= ds && c < ds + 86_400_000L }) }
        else g.db.historyDao().forDay(day).filter { it.status == TaskStatus.COMPLETED.name }.map { T(it.title, it.difficulty, it.doneAt.takeIf { c -> c >= ds && c < ds + 86_400_000L }) }
        tasks.forEach { t ->
            val minutes = when (t.diff) { 0 -> 15.0; 2 -> 60.0; else -> 30.0 }
            val pts = posPoints(minutes, 1.0, t.at)
            items += Item("POS", "work", t.title, minutes.toFloat(), pts.toFloat(), "task", startAt = t.at ?: 0L, note = "completed task (about ${minutes.toInt()} min)")
            done++
        }
        val bonus = (done * 5f).coerceAtMost(15f)
        p += bonus
        if (bonus > 0f) hp[hourOf(wake + 3_600_000L)] += bonus

        // 2) exercise from the Health tab
        g.db.healthDao().forDay(day).filter { it.category == "ACTIVITY" }.forEach { e ->
            val minutes = runCatching { JSONObject(e.extra).optDouble("minutes", 0.0) }.getOrDefault(0.0).takeIf { it > 0 }
                ?: (if ((e.unit ?: "").startsWith("min")) e.amount ?: 0.0 else 0.0)
            if (minutes > 0) {
                val pts = posPoints(minutes, 1.2, e.createdAt.takeIf { it in ds until ds + 86_400_000L })
                items += Item("POS", "exercise", e.label, minutes.toFloat(), pts.toFloat(), "health", startAt = e.createdAt)
            }
        }

        // 3) things the user logged by hand
        g.db.focusDao().forDay(day).forEach { l ->
            if (l.kind == "POS") {
                val pts = posPoints(l.minutes.toDouble(), FocusCfg.POS_W[l.cat] ?: 0.7, l.startAt)
                items += Item("POS", l.cat, l.label, l.minutes.toFloat(), pts.toFloat(), "manual", logId = l.id, startAt = l.startAt)
            } else if (l.rating == "useful") {
                // a "distraction" the user says was useful (YouTube lecture…): credited as learning time
                val pts = posPoints(l.minutes.toDouble(), 0.9, l.startAt)
                items += Item("POS", "learning", l.label, l.minutes.toFloat(), pts.toFloat(), "manual", logId = l.id, rating = l.rating, startAt = l.startAt, note = "rated useful")
            } else {
                // split into 5-minute pieces: each piece gets the timing of the moment it happened
                var pts = 0.0; var left = l.minutes; var t = l.startAt; var tm = ""
                while (left > 0) {
                    val step = minOf(5, left); val (tw, tn) = timing(t)
                    val x = step * (FocusCfg.DIST_W[l.cat] ?: 1.0) * (FocusCfg.RATING_W[l.rating] ?: 1.0) * tw
                    pts += x; hd[hourOf(t)] += x.toFloat(); if (tw == 1.5) morningDist += step; if (tn.isNotBlank()) tm = tn
                    t += step * 60_000L; left -= step
                }
                d += pts; distMin += l.minutes
                items += Item("DIST", l.cat, l.label, l.minutes.toFloat(), pts.toFloat(), "manual", logId = l.id, rating = l.rating, startAt = l.startAt, note = tm)
            }
        }

        // 4) every app the phone says was used: distracting ones cost credit, useful ones earn it, unknown ones wait for the user's decision
        class Acc { var min = 0f; var pts = 0.0; var first = 0L; val hm = FloatArray(24); val hpts = FloatArray(24) }
        val acc = LinkedHashMap<String, Acc>()
        sessions(day, onlyTracked = false).forEach { s0 ->
            val mode = modeOf(s0.pkg)
            if (mode?.first == "IGN") return@forEach
            val rating = ratingOf(day, s0.pkg)
            val a0 = acc.getOrPut(s0.pkg) { Acc().also { it.first = s0.startAt } }
            var t = s0.startAt
            while (t < s0.endAt) {
                val stepMs = minOf(5 * 60_000L, s0.endAt - t); val minutes = stepMs / 60_000.0; val h = hourOf(t)
                a0.min += minutes.toFloat(); a0.hm[h] += minutes.toFloat()
                when {
                    mode?.first == "POS" -> { val x = posPoints(minutes, FocusCfg.POS_W[mode!!.second] ?: 0.9, t); a0.pts += x; a0.hpts[h] += x.toFloat() }
                    mode?.first == "DIST" && rating == "useful" -> { val x = posPoints(minutes, 0.9, t); a0.pts += x; a0.hpts[h] += x.toFloat() }
                    mode?.first == "DIST" -> {
                        val (tw, _) = timing(t)
                        val x = minutes * (FocusCfg.DIST_W[mode!!.second] ?: 1.0) * (FocusCfg.RATING_W[rating] ?: 1.0) * tw
                        d += x; hd[h] += x.toFloat(); distMin += minutes.toFloat(); if (tw == 1.5) morningDist += minutes.toFloat()
                        a0.pts += x; a0.hpts[h] -= x.toFloat()
                    }
                    else -> Unit
                }
                t += stepMs
            }
        }
        acc.forEach { (pkg, v) ->
            val mode = modeOf(pkg); val rating = ratingOf(day, pkg)
            when {
                mode?.first == "POS" -> items += Item("POS", mode!!.second, appLabel(pkg), v.min, v.pts.toFloat(), "app", pkg = pkg, startAt = v.first, mode = "POS", hourMin = v.hm, hourPts = v.hpts)
                mode?.first == "DIST" && rating == "useful" -> items += Item("POS", "learning", appLabel(pkg), v.min, v.pts.toFloat(), "app", pkg = pkg, rating = rating, startAt = v.first, mode = "DIST", hourMin = v.hm, hourPts = v.hpts)
                mode?.first == "DIST" -> items += Item("DIST", mode!!.second, appLabel(pkg), v.min, v.pts.toFloat(), "app", pkg = pkg, rating = rating, startAt = v.first, mode = "DIST", hourMin = v.hm, hourPts = v.hpts)
                else -> if (v.min >= 1f) items += Item("APP", "", appLabel(pkg), v.min, 0f, "app", pkg = pkg, startAt = v.first, mode = "UNRATED", hourMin = v.hm, hourPts = v.hpts)
            }
        }

        val enough = items.any { it.kind != "APP" }
        Result(day, p.toFloat(), d.toFloat(), enough, items.sortedBy { it.startAt }, hp, hd, wake, morningEnd, bed, wakeKnown(day), hasUsageAccess(), done, bonus, morningDist, distMin, posMin)
    }

    // ------------------------------------------------------------------ manual entries
    suspend fun addLog(day: Long, startAt: Long, minutes: Int, kind: String, cat: String, label: String, rating: String) {
        g.db.focusDao().insert(FocusLogEntity(day = day, startAt = startAt, minutes = minutes.coerceIn(1, 1440), kind = kind, cat = cat, label = label.trim().ifBlank { FocusCfg.catLabel(cat) }, rating = if (kind == "DIST") rating.ifBlank { "mindless" } else ""))
        bump()
    }
    suspend fun updateLog(e: FocusLogEntity) { g.db.focusDao().update(e); bump() }
    suspend fun deleteLog(id: Long) { g.db.focusDao().delete(id); bump() }
}
