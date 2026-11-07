package com.privacy.app.data

import com.privacy.app.AppGraph
import com.privacy.app.ai.LlmClient
import com.privacy.app.ai.contentOrEmpty
import com.privacy.app.settings.ModelTier
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Estimates what a logged exercise cost and trained. Everything is an ESTIMATE:
 *   kcal ≈ MET × 3.5 × body weight (kg) ÷ 200 × minutes
 * MET values follow the usual compendium ranges. Strength work is described as a training stimulus (sets, reps, effort, progression),
 * never as exact muscle gained. Unknown exercises are estimated by the AI when one is connected; otherwise nothing is invented.
 */
object ExerciseCalc {
    class Est(
        val met: Double, val minutes: Double, val kcal: Double?, val type: String, val intensity: String,
        val stimulus: String, val source: String, val assumptions: List<String>
    ) {
        /** Training dose in MET-minutes – the unit the Activity score uses. */
        val metMin: Double get() = met * minutes
    }

    private class Rep(val met: Double, val type: String, val secPerRep: Double, val muscles: String)
    private class Timed(val met: Double, val type: String, val minPerKm: Double? = null)

    private val repKinds = mapOf(
        "push-up" to Rep(4.0, "strength", 3.0, "chest, shoulders, triceps"),
        "squat" to Rep(5.0, "strength", 2.5, "quads, glutes, core"),
        "pull-up" to Rep(6.0, "strength", 4.0, "back, biceps, grip"),
        "sit-up" to Rep(3.8, "strength", 2.5, "core"),
        "crunch" to Rep(3.8, "strength", 2.0, "core"),
        "lunge" to Rep(4.0, "strength", 3.0, "legs, glutes"),
        "burpee" to Rep(8.0, "cardio", 4.0, "full body, stamina"),
        "dip" to Rep(5.0, "strength", 3.0, "triceps, chest"),
        "plank" to Rep(3.3, "strength", 1.0, "core")
    )
    private val timed = listOf(
        "walk" to Timed(3.5, "cardio", 12.0), "run" to Timed(9.8, "cardio", 6.0), "cycl" to Timed(7.5, "cardio", 3.0), "swim" to Timed(6.0, "cardio", 25.0),
        "yoga" to Timed(2.5, "mobility"), "stretch" to Timed(2.3, "mobility"), "hiit" to Timed(8.0, "cardio"), "danc" to Timed(5.0, "cardio"),
        "skip" to Timed(11.8, "cardio"), "jump" to Timed(11.8, "cardio"), "gym" to Timed(5.0, "strength"), "workout" to Timed(5.0, "strength"),
        "weight" to Timed(4.5, "strength"), "football" to Timed(7.0, "sport"), "cricket" to Timed(4.8, "sport"), "badminton" to Timed(5.5, "sport"),
        "tennis" to Timed(7.3, "sport"), "treadmill" to Timed(9.0, "cardio", 6.0), "hike" to Timed(6.0, "cardio", 12.0), "pilates" to Timed(3.0, "mobility")
    )

    fun intensityOf(met: Double) = if (met < 3.0) "light" else if (met < 6.0) "moderate" else "vigorous"

    fun kcalOf(met: Double, kg: Double, minutes: Double) = met * 3.5 * kg / 200.0 * minutes

    /** Speed-based MET for walking and running (km/h). */
    private fun speedMet(kind: String, kmh: Double): Double = if (kind == "run") when {
        kmh < 6.4 -> 6.0; kmh < 8.4 -> 8.3; kmh < 9.8 -> 9.8; kmh < 11.4 -> 11.0; kmh < 13.0 -> 11.8; else -> 12.8
    } else when { kmh < 3.2 -> 2.8; kmh < 4.8 -> 3.5; kmh < 6.4 -> 4.3; else -> 5.0 }

    /** Local estimate for known exercises; null when the exercise is not in the table (the AI may then be asked). */
    fun estimate(label: String, amount: Double?, unit: String?, bodyKg: Double?, sets: Int? = null): Est? {
        val a = amount ?: return null
        if (a <= 0) return null
        val canon = HealthParser.canonName(label)
        val kg = bodyKg ?: 70.0
        val notes = mutableListOf<String>()
        if (bodyKg == null) notes += "assumed 70 kg body weight"
        val u = (unit ?: "reps").lowercase(Locale.ROOT)

        repKinds[canon]?.let { k ->
            val minutes: Double
            val stim: String
            if (canon == "plank" || u == "sec" || u == "min") {
                minutes = if (u == "min") a else a / 60.0
                stim = "Core endurance: ${fmt(minutes * 60)} s under tension."
            } else {
                val reps = a
                val n = sets ?: max(1, ceil(reps / 12.0).toInt()).also { notes += "assumed ~${it} set${if (it > 1) "s" else ""}" }
                minutes = reps * k.secPerRep / 60.0 + (n - 1) * 0.75
                val level = if (reps < 15) "light" else if (reps < 60) "moderate" else "high"
                stim = "${level.replaceFirstChar { it.uppercase() }} strength stimulus (${fmt(reps)} reps in $n set${if (n > 1) "s" else ""}) for ${k.muscles}."
            }
            return Est(k.met, minutes, kcalOf(k.met, kg, minutes), k.type, intensityOf(k.met), stim, "table", notes)
        }
        val key = timed.firstOrNull { canon.startsWith(it.first) } ?: return null
        val t = key.second
        var met = t.met
        val minutes: Double
        when (u) {
            "min", "mins", "minute", "minutes" -> minutes = a
            "h", "hr", "hrs", "hour", "hours" -> minutes = a * 60
            "km" -> {
                val pace = t.minPerKm ?: return null
                minutes = a * pace; notes += "assumed a pace of ${fmt(pace)} min/km"
                if (key.first == "run" || key.first == "walk" || key.first == "treadmill" || key.first == "hike") met = speedMet(if (key.first == "walk" || key.first == "hike") "walk" else "run", 60.0 / pace)
            }
            else -> return null
        }
        val kcal = kcalOf(met, kg, minutes)
        val stim = when (t.type) {
            "mobility" -> "Mobility and recovery work – low load, good for flexibility."
            "strength" -> "${intensityOf(met).replaceFirstChar { it.uppercase() }} resistance session of ${minutes.roundToInt()} min – a general strength stimulus."
            else -> "${intensityOf(met).replaceFirstChar { it.uppercase() }} aerobic work for ${minutes.roundToInt()} min – builds stamina."
        }
        return Est(met, minutes, kcal, t.type, intensityOf(met), stim, "table", notes)
    }

    fun toJson(e: Est): String = JSONObject().put("met", e.met).put("minutes", e.minutes).put("kcal", e.kcal ?: JSONObject.NULL).put("type", e.type)
        .put("intensity", e.intensity).put("stimulus", e.stimulus).put("src", e.source).put("notes", e.assumptions.joinToString("; ")).toString()

    fun fromJson(s: String): Est? = runCatching {
        if (s.isBlank()) return null
        val o = JSONObject(s)
        Est(
            o.getDouble("met"), o.getDouble("minutes"), if (o.isNull("kcal")) null else o.getDouble("kcal"), o.optString("type", "cardio"),
            o.optString("intensity", "moderate"), o.optString("stimulus"), o.optString("src", "table"),
            o.optString("notes").split("; ").filter { it.isNotBlank() }
        )
    }.getOrNull()

    /** For exercises that are not in the table: ask the AI for a defensible MET and duration. Null when no AI is connected or it fails. */
    suspend fun askAi(g: AppGraph, label: String, amount: Double?, unit: String?, bodyKg: Double?, age: Int?): Est? {
        if (!g.settings.aiReady()) return null
        val sys = "You estimate exercise load. Reply with ONE JSON object only: {\"met\":number,\"minutes\":number,\"type\":\"cardio|strength|mobility|sport\",\"intensity\":\"light|moderate|vigorous\",\"stimulus\":\"one short sentence about the training stimulus, no claims of exact muscle gain\"}. " +
            "Use a defensible compendium-style MET. If only reps/distance are given, assume a typical pace/tempo and say nothing else."
        val msg = "Exercise: $label; amount: ${amount?.let { fmt(it) } ?: "unknown"} ${unit ?: ""}; body weight: ${bodyKg?.let { fmt(it) } ?: "unknown"} kg; age: ${age ?: "unknown"}."
        val r = withTimeoutOrNull(15_000L) {
            runCatching { g.llm.chat(listOf(JSONObject().put("role", "system").put("content", sys), JSONObject().put("role", "user").put("content", msg)), null, maxTokens = 220, temperature = 0.2, tier = ModelTier.FAST) }.getOrNull()
        } as? LlmClient.Result.Ok ?: return null
        val raw = r.message.contentOrEmpty().replace(Regex("(?s)<think>.*?</think>"), "")
        val json = raw.substring(raw.indexOf('{').takeIf { it >= 0 } ?: run { return null }, (raw.lastIndexOf('}') + 1).takeIf { it > 0 } ?: run { return null })
        return runCatching {
            val o = JSONObject(json)
            val met = o.getDouble("met").coerceIn(1.0, 18.0)
            val minutes = (if (o.has("minutes")) o.getDouble("minutes") else if (unit?.startsWith("min") == true) amount ?: 0.0 else 0.0).coerceIn(0.0, 600.0)
            if (minutes <= 0.0) return null
            val kg = bodyKg ?: 70.0
            Est(met, minutes, kcalOf(met, kg, minutes), o.optString("type", "cardio"), o.optString("intensity", intensityOf(met)), o.optString("stimulus"), "ai",
                listOfNotNull("AI estimate", if (bodyKg == null) "assumed 70 kg body weight" else null))
        }.getOrNull()
    }
}

/** AI estimate of a food the built-in table does not know (e.g. "a sandwich"). Numbers are estimates and say so. */
object FoodAi {
    class Result(
        val label: String, val kcal: Double, val protein: Double, val carbs: Double, val fat: Double, val fiber: Double?,
        val groups: List<String>, val assumptions: String, val confidence: String
    )

    /** Why the last estimate failed (shown to the user instead of a vague message). */
    @Volatile var lastError: String = ""

    suspend fun estimate(g: AppGraph, text: String, profile: HealthProfile): Result? {
        lastError = ""
        if (!g.settings.aiReady()) { lastError = "No AI key is set up yet (API & Subscription)."; return null }
        if (!com.privacy.app.ai.Net.online()) { lastError = com.privacy.app.ai.Net.OFFLINE; return null }
        val sys = "You estimate nutrition for ONE logged meal. Reply with ONE JSON object only: " +
            "{\"label\":\"short name with portion\",\"kcal\":number,\"protein\":number,\"carbs\":number,\"fat\":number,\"fiber\":number|null," +
            "\"groups\":[\"grain|protein|legume|dairy|veg|fruit|nuts\"],\"assumptions\":\"portion/preparation you assumed\",\"confidence\":\"low|medium\"}. " +
            "The user may write in English, Hindi or Hinglish (Roman letters) and may name Indian dishes: e.g. \"sugi ke chilla\" / \"moong dal ke chilla\" (savoury lentil pancake), \"aloo ke parathe\" (potato-stuffed flatbread), \"kachori\", poha, upma, dosa, rajma chawal, chole bhature, samosa, paneer dishes, dal-roti, thali. Understand them as the Indian dish they are and use typical Indian home portions (e.g. 1 paratha with ghee/oil ≈ 250–300 kcal, 1 kachori ≈ 150–200 kcal, 1 chilla ≈ 100–130 kcal) – multiply by the count the user gives. " +
            "Numbers are grams except kcal. Use typical portions and say what you assumed. Never claim precision. Respect the user's diet: ${profile.diet.ifBlank { "no stated diet" }}."
        val msgs = listOf(JSONObject().put("role", "system").put("content", sys), JSONObject().put("role", "user").put("content", text))
        var res: LlmClient.Result? = null
        for (attempt in 0..1) {   // one quick retry – a model sometimes answers with prose or a cut-off JSON
            res = withTimeoutOrNull(60_000L) {
                runCatching { g.llm.chat(msgs, null, maxTokens = 900, temperature = 0.2, tier = ModelTier.FAST) }.getOrNull()
            }
            if (res is LlmClient.Result.Ok && res.message.contentOrEmpty().contains('{')) break
        }
        if (res is LlmClient.Result.Fail) { lastError = res.reason.lines().firstOrNull().orEmpty(); return null }
        val r = res as? LlmClient.Result.Ok ?: run { lastError = "The AI took too long to answer."; null } ?: return null
        val raw = r.message.contentOrEmpty().replace(Regex("(?s)<think>.*?</think>"), "")
        val json = raw.substring(raw.indexOf('{').takeIf { it >= 0 } ?: run { lastError = "The AI's answer could not be read."; return null }, (raw.lastIndexOf('}') + 1).takeIf { it > 0 } ?: run { lastError = "The AI's answer could not be read."; return null })
        return runCatching {
            val o = JSONObject(json)
            val kcal = o.getDouble("kcal")
            if (kcal <= 0 || kcal > 4000) return null
            val gr = o.optJSONArray("groups")?.let { a -> (0 until a.length()).map { a.getString(it).lowercase() } } ?: emptyList()
            Result(o.optString("label", text).ifBlank { text }, kcal, o.optDouble("protein", 0.0), o.optDouble("carbs", 0.0), o.optDouble("fat", 0.0),
                if (o.isNull("fiber") || !o.has("fiber")) null else o.getDouble("fiber"), gr, o.optString("assumptions"), o.optString("confidence", "low"))
        }.getOrNull()
    }
}
