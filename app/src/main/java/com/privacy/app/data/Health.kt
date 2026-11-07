package com.privacy.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ======================================================================================================
// Profile → personalised targets
// ======================================================================================================
enum class HealthGoal(val label: String) {
    MAINTAIN("Maintain weight"), LOSE("Lose fat / weight"), GAIN("Build muscle"), FITNESS("General fitness")
}

enum class CalcMethod(val label: String) {
    MALE("Male equation"), FEMALE("Female equation"), RANGE("Sex-neutral (show a range)")
}

enum class ActivityLevel(val label: String, val factor: Double) {
    SEDENTARY("Mostly sedentary", 1.2), LIGHT("Lightly active", 1.375), MODERATE("Moderately active", 1.55), VERY("Very active", 1.725)
}

data class ExerciseGoal(val name: String, val amount: Double, val unit: String, val weight: Double = 1.0) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("amount", amount).put("unit", unit).put("weight", weight)
    fun describe(): String = "${fmt(amount)} $unit $name"
    companion object {
        fun fromJson(o: JSONObject) = ExerciseGoal(o.optString("name"), o.optDouble("amount", 0.0), o.optString("unit", "reps"), o.optDouble("weight", 1.0))
    }
}

internal fun fmt(v: Double): String = if (abs(v - v.roundToInt()) < 0.05) v.roundToInt().toString() else String.format(Locale.US, "%.1f", v)

private fun JSONObject.dbl(k: String): Double? = if (!has(k) || isNull(k)) null else optDouble(k)
private fun JSONObject.int(k: String): Int? = if (!has(k) || isNull(k)) null else optInt(k)

data class HealthProfile(
    val age: Int? = null,
    val heightCm: Double? = null,
    val weightKg: Double? = null,
    val goal: HealthGoal = HealthGoal.MAINTAIN,
    val method: CalcMethod = CalcMethod.RANGE,
    val activity: ActivityLevel = ActivityLevel.LIGHT,
    val diet: String = "",
    val allergies: String = "",
    val foodPrefs: String = "",
    val exercisePrefs: String = "",
    val equipment: String = "",
    val injuries: String = "",
    val medical: String = "",
    val sleepGoalH: Double? = null,
    val waterGoalMl: Int? = null,
    val kcalOverride: Int? = null,
    val proteinOverride: Int? = null,
    val exerciseGoals: List<ExerciseGoal> = emptyList(),
    val budget: String = "",
    val mealSchedule: String = "",
    val cookTime: String = ""
) {
    val isEmpty: Boolean get() = age == null && heightCm == null && weightKg == null && exerciseGoals.isEmpty() && sleepGoalH == null && waterGoalMl == null

    fun toJson(): JSONObject = JSONObject().apply {
        age?.let { put("age", it) }; heightCm?.let { put("heightCm", it) }; weightKg?.let { put("weightKg", it) }
        put("goal", goal.name); put("method", method.name); put("activity", activity.name)
        put("diet", diet); put("allergies", allergies); put("foodPrefs", foodPrefs); put("exercisePrefs", exercisePrefs)
        put("equipment", equipment); put("injuries", injuries); put("medical", medical)
        sleepGoalH?.let { put("sleepGoalH", it) }; waterGoalMl?.let { put("waterGoalMl", it) }
        kcalOverride?.let { put("kcalOverride", it) }; proteinOverride?.let { put("proteinOverride", it) }
        put("exerciseGoals", JSONArray().also { a -> exerciseGoals.forEach { a.put(it.toJson()) } })
        put("budget", budget); put("mealSchedule", mealSchedule); put("cookTime", cookTime)
    }

    companion object {
        fun fromJson(o: JSONObject) = HealthProfile(
            age = o.int("age"), heightCm = o.dbl("heightCm"), weightKg = o.dbl("weightKg"),
            goal = runCatching { HealthGoal.valueOf(o.optString("goal")) }.getOrDefault(HealthGoal.MAINTAIN),
            method = runCatching { CalcMethod.valueOf(o.optString("method")) }.getOrDefault(CalcMethod.RANGE),
            activity = runCatching { ActivityLevel.valueOf(o.optString("activity")) }.getOrDefault(ActivityLevel.LIGHT),
            diet = o.optString("diet"), allergies = o.optString("allergies"), foodPrefs = o.optString("foodPrefs"),
            exercisePrefs = o.optString("exercisePrefs"), equipment = o.optString("equipment"), injuries = o.optString("injuries"),
            medical = o.optString("medical"), sleepGoalH = o.dbl("sleepGoalH"), waterGoalMl = o.int("waterGoalMl"),
            kcalOverride = o.int("kcalOverride"), proteinOverride = o.int("proteinOverride"),
            exerciseGoals = o.optJSONArray("exerciseGoals")?.let { a -> (0 until a.length()).map { ExerciseGoal.fromJson(a.getJSONObject(it)) } } ?: emptyList(),
            budget = o.optString("budget"), mealSchedule = o.optString("mealSchedule"), cookTime = o.optString("cookTime")
        )
    }
}

/** The numbers the day is scored against – stored together with where each came from, when and how sure we are. */
data class HealthTargets(
    val kcal: Int?, val kcalLow: Int?, val kcalHigh: Int?, val proteinG: Int?, val sleepH: Double?, val waterMl: Int?,
    val exerciseGoals: List<ExerciseGoal>,
    val kcalSource: String, val proteinSource: String, val sleepSource: String, val waterSource: String,
    /** low | medium | high | user-set */
    val confidence: String, val notes: List<String>, val date: Long,
    /** Healthy carbohydrate range in grams (≈ 45–65 % of the calorie target). */
    val carbLowG: Int? = null, val carbHighG: Int? = null,
    /** Personal sleep range in hours – inside it Sleep scores 100. */
    val sleepLoH: Double? = null, val sleepHiH: Double? = null,
    /** Daily training dose to aim for, in MET-minutes. */
    val activityMetMin: Double = 60.0,
    /** Body weight used for calorie-burn estimates. */
    val bodyKg: Double? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        kcal?.let { put("kcal", it) }; kcalLow?.let { put("kcalLow", it) }; kcalHigh?.let { put("kcalHigh", it) }
        proteinG?.let { put("proteinG", it) }; sleepH?.let { put("sleepH", it) }; waterMl?.let { put("waterMl", it) }
        put("exerciseGoals", JSONArray().also { a -> exerciseGoals.forEach { a.put(it.toJson()) } })
        put("kcalSource", kcalSource); put("proteinSource", proteinSource); put("sleepSource", sleepSource); put("waterSource", waterSource)
        put("confidence", confidence); put("notes", JSONArray(notes)); put("date", date)
        carbLowG?.let { put("carbLowG", it) }; carbHighG?.let { put("carbHighG", it) }
        sleepLoH?.let { put("sleepLoH", it) }; sleepHiH?.let { put("sleepHiH", it) }
        put("activityMetMin", activityMetMin); bodyKg?.let { put("bodyKg", it) }
    }

    /** Same numbers (ignoring the date) – used to decide whether a new goal version is needed. */
    fun sameAs(o: HealthTargets): Boolean = toJson().apply { remove("date") }.toString() == o.toJson().apply { remove("date") }.toString()

    companion object {
        fun fromJson(o: JSONObject) = HealthTargets(
            kcal = o.int("kcal"), kcalLow = o.int("kcalLow"), kcalHigh = o.int("kcalHigh"), proteinG = o.int("proteinG"),
            sleepH = o.dbl("sleepH"), waterMl = o.int("waterMl"),
            exerciseGoals = emptyList(),
            kcalSource = o.optString("kcalSource"), proteinSource = o.optString("proteinSource"),
            sleepSource = o.optString("sleepSource"), waterSource = o.optString("waterSource"),
            confidence = o.optString("confidence", "low"),
            notes = o.optJSONArray("notes")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            date = o.optLong("date"),
            carbLowG = o.int("carbLowG"), carbHighG = o.int("carbHighG"), sleepLoH = o.dbl("sleepLoH"), sleepHiH = o.dbl("sleepHiH"),
            activityMetMin = o.optDouble("activityMetMin", 60.0), bodyKg = o.dbl("bodyKg")
        )
    }
}

object HealthCalc {
    /** Mifflin–St Jeor resting energy estimate (adults). */
    fun bmr(weightKg: Double, heightCm: Double, age: Int, male: Boolean): Double =
        10.0 * weightKg + 6.25 * heightCm - 5.0 * age + (if (male) 5.0 else -161.0)

    private fun round10(v: Double) = (v / 10.0).roundToInt() * 10
    private fun round5(v: Double) = (v / 5.0).roundToInt() * 5

    /** Protein per kg of body weight for the goal and activity (picked inside the usual ranges, not the top of every range). */
    fun proteinPerKg(goal: HealthGoal, act: ActivityLevel): Double = when (goal) {
        HealthGoal.GAIN -> when (act) { ActivityLevel.SEDENTARY -> 1.4; ActivityLevel.LIGHT -> 1.6; ActivityLevel.MODERATE -> 1.7; ActivityLevel.VERY -> 1.8 }
        HealthGoal.LOSE -> when (act) { ActivityLevel.SEDENTARY -> 1.1; ActivityLevel.LIGHT -> 1.2; ActivityLevel.MODERATE -> 1.4; ActivityLevel.VERY -> 1.5 }
        HealthGoal.FITNESS -> when (act) { ActivityLevel.SEDENTARY -> 1.0; ActivityLevel.LIGHT -> 1.1; ActivityLevel.MODERATE -> 1.3; ActivityLevel.VERY -> 1.5 }
        HealthGoal.MAINTAIN -> when (act) { ActivityLevel.SEDENTARY -> 0.8; ActivityLevel.LIGHT -> 1.0; ActivityLevel.MODERATE -> 1.2; ActivityLevel.VERY -> 1.3 }
    }

    fun targets(p: HealthProfile, today: Long): HealthTargets {
        val notes = mutableListOf<String>()
        var confidence = "medium"
        val adult = (p.age ?: 30) >= 18

        // ---- calories (a general reference is used until the user's details are known, and it says so)
        var kcal: Int? = null; var low: Int? = null; var high: Int? = null
        var kcalSource = ""
        if (p.kcalOverride != null) {
            kcal = p.kcalOverride; kcalSource = "Set by you"
        } else if (p.age != null && p.heightCm != null && p.weightKg != null && adult) {
            val bmrs = when (p.method) {
                CalcMethod.MALE -> listOf(bmr(p.weightKg, p.heightCm, p.age, true))
                CalcMethod.FEMALE -> listOf(bmr(p.weightKg, p.heightCm, p.age, false))
                CalcMethod.RANGE -> listOf(bmr(p.weightKg, p.heightCm, p.age, false), bmr(p.weightKg, p.heightCm, p.age, true))
            }
            val adj = when (p.goal) { HealthGoal.LOSE -> -300.0; HealthGoal.GAIN -> 300.0; else -> 0.0 }
            val list = bmrs.map { max(it * p.activity.factor + adj, it) }   // never below the resting estimate
            low = round10(list.min()); high = round10(list.max()); kcal = round10(list.average())
            kcalSource = "Mifflin–St Jeor × ${p.activity.factor} (${p.activity.label.lowercase()})" +
                when (p.goal) { HealthGoal.LOSE -> ", −300 kcal for a gentle deficit"; HealthGoal.GAIN -> ", +300 kcal for a gentle surplus"; else -> "" }
            if (p.method == CalcMethod.RANGE) notes += "No equation was chosen, so the calorie target is the middle of a range ($low–$high kcal)."
        } else {
            kcal = 2000; kcalSource = "General reference (add your age, height and weight in Account → Edit my plan for a personal target)"
            if (p.age != null && !adult) notes += "These calorie formulas are for adults (18+). Enter your own calorie target, or ask a doctor or dietitian."
            confidence = "low"
        }

        // ---- protein
        var protein: Int? = null
        var proteinSource = ""
        if (p.proteinOverride != null) {
            protein = p.proteinOverride; proteinSource = "Set by you"
        } else if (p.weightKg != null && adult) {
            var perKg = proteinPerKg(p.goal, p.activity)
            if (p.medical.isNotBlank()) {
                perKg = min(perKg, 1.0)
                notes += "You listed a medical restriction, so protein is kept at a general level. Check the right amount with a clinician or dietitian."
                confidence = "low"
            }
            protein = round5(p.weightKg * perKg)
            proteinSource = String.format(Locale.US, "%.1f g per kg × %.0f kg (%s, %s)", perKg, p.weightKg, p.goal.label.lowercase(), p.activity.label.lowercase())
        } else {
            protein = 56; proteinSource = "General reference (0.8 g per kg for a 70 kg adult)"
        }

        // ---- carbohydrates: roughly 45–65 % of calories
        val carbLow = kcal?.let { round5(it * 0.45 / 4.0) }
        val carbHigh = kcal?.let { round5(it * 0.65 / 4.0) }

        // ---- sleep: a personal range, not "exactly 8 hours"
        val sleep: Double; val sleepSource: String; val lo: Double; val hi: Double
        if (p.sleepGoalH != null) { sleep = p.sleepGoalH; lo = sleep - 1.0; hi = sleep + 1.0; sleepSource = "Your own goal (range ±1 h)" }
        else {
            val a = p.age
            val r = when { a == null -> 7.0 to 9.0; a < 14 -> 9.0 to 11.0; a < 18 -> 8.0 to 10.0; a < 65 -> 7.0 to 9.0; else -> 7.0 to 8.0 }
            lo = r.first; hi = r.second; sleep = (lo + hi) / 2.0
            sleepSource = "General reference for ${if (a == null) "adults" else "your age"} (${fmt(lo)}–${fmt(hi)} h) – set your own goal in your plan"
        }

        // ---- water
        val water: Int; val waterSource: String
        if (p.waterGoalMl != null) { water = p.waterGoalMl; waterSource = "Your own goal" }
        else if (p.weightKg != null) {
            water = (min(3500.0, max(1500.0, p.weightKg * 33.0)) / 250.0).roundToInt() * 250
            waterSource = "About 33 ml per kg – needs vary with heat, activity and health"
        } else { water = 2000; waterSource = "General reference (2 L)" }

        // ---- activity dose (MET-minutes per day): roughly the WHO minimum of moderate activity, adjusted for level and age
        var metMin = when (p.activity) { ActivityLevel.SEDENTARY -> 45.0; ActivityLevel.LIGHT -> 60.0; ActivityLevel.MODERATE -> 75.0; ActivityLevel.VERY -> 100.0 }
        val a2 = p.age
        if (a2 != null && a2 < 18) metMin = 210.0 else if (a2 != null && a2 >= 65) metMin *= 0.8
        if (p.injuries.isNotBlank()) { metMin *= 0.85; notes += "You listed an injury or limit, so the activity benchmark is eased a little." }

        if (p.kcalOverride != null && p.proteinOverride != null) confidence = "user-set"
        return HealthTargets(
            kcal, low, high, protein, sleep, water, emptyList(), kcalSource, proteinSource, sleepSource, waterSource, confidence, notes, today,
            carbLowG = carbLow, carbHighG = carbHigh, sleepLoH = lo, sleepHiH = hi, activityMetMin = metMin, bodyKg = p.weightKg
        )
    }
}

// ======================================================================================================
// Food estimates (a small built-in table of common foods – estimates, never exact)
// ======================================================================================================
object FoodDb {
    /** group: P protein · D dairy (counts as protein) · V vegetable/fruit · C carbohydrate/energy · O other. */
    class Food(val names: List<String>, val unit: String, val unitG: Double, val kcal100: Double, val prot100: Double, val group: String, val note: String = "")

    private val foods = listOf(
        Food(listOf("roti", "chapati", "chapatti", "phulka", "chapathi"), "roti", 40.0, 297.0, 9.0, "C"),
        Food(listOf("paratha", "parantha", "parathe", "aloo ke parathe", "aloo paratha"), "paratha", 80.0, 326.0, 7.0, "C"),
        Food(listOf("naan"), "naan", 90.0, 290.0, 9.0, "C"),
        Food(listOf("rice", "chawal", "steamed rice", "jeera rice"), "cup", 160.0, 130.0, 2.7, "C", "a cup of cooked rice"),
        Food(listOf("dal", "daal", "lentils", "lentil soup", "dal tadka"), "katori", 150.0, 105.0, 6.8, "P", "a katori (150 g) of cooked dal"),
        Food(listOf("rajma", "chole", "chana masala", "chickpeas", "kidney beans"), "katori", 150.0, 130.0, 8.0, "P"),
        Food(listOf("paneer"), "serving", 80.0, 265.0, 18.0, "P", "80 g paneer"),
        Food(listOf("boiled egg", "egg", "eggs"), "egg", 50.0, 155.0, 13.0, "P"),
        Food(listOf("omelette", "omelet"), "omelette", 100.0, 154.0, 11.0, "P", "a 2-egg omelette"),
        Food(listOf("chicken curry"), "serving", 150.0, 150.0, 14.0, "P"),
        Food(listOf("grilled chicken", "chicken breast", "chicken"), "serving", 120.0, 165.0, 31.0, "P"),
        Food(listOf("fish curry", "fish"), "serving", 150.0, 130.0, 17.0, "P"),
        Food(listOf("tofu"), "serving", 100.0, 76.0, 8.0, "P"),
        Food(listOf("soya chunks", "soy chunks", "nutrela"), "serving", 40.0, 345.0, 52.0, "P", "40 g dry"),
        Food(listOf("whey", "protein shake", "protein powder"), "scoop", 30.0, 400.0, 80.0, "P"),
        Food(listOf("milk"), "glass", 250.0, 61.0, 3.2, "D", "a 250 ml glass of whole milk"),
        Food(listOf("curd", "dahi", "yogurt", "yoghurt"), "bowl", 150.0, 60.0, 3.5, "D"),
        Food(listOf("lassi", "buttermilk", "chaas"), "glass", 250.0, 60.0, 3.0, "D"),
        Food(listOf("black coffee"), "cup", 240.0, 1.0, 0.1, "O"),
        Food(listOf("coffee"), "cup", 150.0, 40.0, 1.8, "O", "a cup with a little milk, no sugar"),
        Food(listOf("milk tea", "tea", "chai"), "cup", 150.0, 45.0, 1.5, "O", "a cup of milk tea with a little sugar"),
        Food(listOf("banana"), "banana", 118.0, 89.0, 1.1, "V"),
        Food(listOf("apple"), "apple", 180.0, 52.0, 0.3, "V"),
        Food(listOf("orange", "mosambi"), "orange", 130.0, 47.0, 0.9, "V"),
        Food(listOf("mango"), "mango", 200.0, 60.0, 0.8, "V"),
        Food(listOf("papaya"), "bowl", 150.0, 43.0, 0.5, "V"),
        Food(listOf("grapes"), "bowl", 100.0, 69.0, 0.7, "V"),
        Food(listOf("salad", "cucumber", "carrot", "tomato"), "bowl", 100.0, 20.0, 1.0, "V"),
        Food(listOf("sabzi", "vegetable curry", "mixed veg", "aloo gobi", "bhindi", "palak", "veg curry"), "katori", 150.0, 80.0, 2.0, "V"),
        Food(listOf("idli"), "idli", 40.0, 145.0, 5.0, "C"),
        Food(listOf("masala dosa"), "dosa", 150.0, 190.0, 4.0, "C"),
        Food(listOf("dosa"), "dosa", 100.0, 168.0, 3.9, "C"),
        Food(listOf("sambar"), "katori", 150.0, 50.0, 2.5, "P"),
        Food(listOf("poha"), "plate", 200.0, 130.0, 2.6, "C"),
        Food(listOf("upma"), "plate", 200.0, 135.0, 3.5, "C"),
        Food(listOf("oats", "oatmeal", "porridge"), "bowl", 240.0, 71.0, 2.5, "C", "a bowl of cooked oats"),
        Food(listOf("bread", "toast"), "slice", 28.0, 265.0, 9.0, "C"),
        Food(listOf("biryani"), "plate", 250.0, 160.0, 6.0, "C", "a 250 g plate – real portions and oil vary a lot"),
        Food(listOf("pasta", "noodles", "maggi"), "plate", 200.0, 140.0, 5.0, "C"),
        Food(listOf("samosa"), "samosa", 60.0, 262.0, 4.5, "O"),
        Food(listOf("pizza"), "slice", 107.0, 266.0, 11.0, "O"),
        Food(listOf("burger"), "burger", 150.0, 260.0, 13.0, "O"),
        Food(listOf("chips", "namkeen"), "packet", 30.0, 536.0, 7.0, "O"),
        Food(listOf("biscuit", "biscuits", "cookie", "cookies"), "biscuit", 10.0, 450.0, 6.0, "O"),
        Food(listOf("chocolate"), "piece", 20.0, 546.0, 5.0, "O"),
        Food(listOf("almonds", "almond", "nuts", "cashew", "cashews", "peanuts"), "handful", 28.0, 579.0, 21.0, "P"),
        Food(listOf("peanut butter"), "tbsp", 16.0, 588.0, 25.0, "P"),
        Food(listOf("butter"), "tsp", 5.0, 717.0, 0.9, "O"),
        Food(listOf("ghee"), "tsp", 5.0, 900.0, 0.0, "O"),
        Food(listOf("juice"), "glass", 250.0, 45.0, 0.5, "O"),
        Food(listOf("cola", "soda", "coke", "pepsi"), "can", 330.0, 42.0, 0.0, "O")
    )

    private val carbs100 = mapOf(
        "roti" to 50.0, "paratha" to 45.0, "naan" to 50.0, "rice" to 28.0, "dal" to 15.0, "rajma" to 22.0, "paneer" to 3.5, "boiled egg" to 1.1, "omelette" to 1.5,
        "chicken curry" to 5.0, "grilled chicken" to 0.0, "fish curry" to 3.0, "tofu" to 2.0, "soya chunks" to 33.0, "whey" to 10.0, "milk" to 4.8, "curd" to 4.5,
        "lassi" to 8.0, "black coffee" to 0.0, "coffee" to 4.0, "milk tea" to 6.0, "banana" to 23.0, "apple" to 14.0, "orange" to 12.0, "mango" to 15.0,
        "papaya" to 11.0, "grapes" to 18.0, "salad" to 4.0, "sabzi" to 8.0, "idli" to 28.0, "masala dosa" to 30.0, "dosa" to 29.0, "sambar" to 7.0, "poha" to 25.0,
        "upma" to 22.0, "oats" to 12.0, "bread" to 49.0, "biryani" to 22.0, "pasta" to 30.0, "samosa" to 25.0, "pizza" to 33.0, "burger" to 25.0, "chips" to 53.0,
        "biscuit" to 70.0, "chocolate" to 55.0, "almonds" to 22.0, "peanut butter" to 20.0, "butter" to 0.1, "ghee" to 0.0, "juice" to 11.0, "cola" to 10.6
    )
    /** Fat in grams per 100 g (typical values; used with the food table so fat is tracked along with calories, protein and carbs). */
    private val fat100 = mapOf(
        "roti" to 3.7, "paratha" to 13.0, "naan" to 9.0, "rice" to 0.4, "dal" to 3.5, "rajma" to 4.5, "paneer" to 21.0, "boiled egg" to 10.6, "omelette" to 11.0,
        "chicken curry" to 9.0, "grilled chicken" to 4.0, "fish curry" to 7.0, "tofu" to 4.8, "soya chunks" to 0.5, "whey" to 6.0, "milk" to 3.5, "curd" to 3.3,
        "lassi" to 2.5, "black coffee" to 0.0, "coffee" to 2.0, "milk tea" to 2.0, "banana" to 0.3, "apple" to 0.2, "orange" to 0.1, "mango" to 0.4,
        "papaya" to 0.3, "grapes" to 0.2, "salad" to 0.3, "sabzi" to 5.0, "idli" to 0.4, "masala dosa" to 8.0, "dosa" to 4.0, "sambar" to 2.0, "poha" to 4.0,
        "upma" to 5.0, "oats" to 6.5, "bread" to 3.2, "biryani" to 7.0, "pasta" to 5.0, "samosa" to 17.0, "pizza" to 11.0, "burger" to 13.0, "chips" to 34.0,
        "biscuit" to 20.0, "chocolate" to 31.0, "almonds" to 50.0, "peanut butter" to 50.0, "butter" to 81.0, "ghee" to 99.5, "juice" to 0.2, "cola" to 0.0
    )
    /** Food kinds for the variety score (M). Foods that add no real variety (tea, oil, soda, snacks) are left out. */
    private val kinds = mapOf(
        "roti" to "grain", "paratha" to "grain", "naan" to "grain", "rice" to "grain", "idli" to "grain", "masala dosa" to "grain", "dosa" to "grain", "poha" to "grain",
        "upma" to "grain", "oats" to "grain", "bread" to "grain", "biryani" to "grain", "pasta" to "grain",
        "dal" to "legume", "rajma" to "legume", "tofu" to "legume", "soya chunks" to "legume", "sambar" to "legume",
        "paneer" to "dairy", "milk" to "dairy", "curd" to "dairy", "lassi" to "dairy",
        "boiled egg" to "protein", "omelette" to "protein", "chicken curry" to "protein", "grilled chicken" to "protein", "fish curry" to "protein", "whey" to "protein",
        "banana" to "fruit", "apple" to "fruit", "orange" to "fruit", "mango" to "fruit", "papaya" to "fruit", "grapes" to "fruit",
        "salad" to "veg", "sabzi" to "veg", "almonds" to "nuts", "peanut butter" to "nuts"
    )

    private val aliasRegex: List<Pair<Regex, Food>> by lazy {
        foods.flatMap { f -> f.names.map { n -> Regex("\\b" + Regex.escape(n) + "(?:s|es)?\\b") to f } }
            .sortedByDescending { it.first.pattern.length }
    }

    private val numberWords = mapOf(
        "a" to 1.0, "an" to 1.0, "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0, "five" to 5.0, "six" to 6.0,
        "seven" to 7.0, "eight" to 8.0, "nine" to 9.0, "ten" to 10.0, "half" to 0.5, "couple" to 2.0
    )
    private val filler = Regex("\\b(i|had|ate|eat|eaten|have|for|my|breakfast|lunch|dinner|snack|snacks|supper|today|this|morning|evening|night|just|some|of|the|also|then|after|before|at|in|small|large|big)\\b")
    private val splitter = Regex("\\s*(?:,|\\+|&|;|\\band\\b|\\bwith\\b|\\bplus\\b|\\balong with\\b)\\s*")
    private val gramsRe = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:g|gm|gms|gram|grams|ml|millilitre|millilitres|milliliter|milliliters)\\b")
    private val numRe = Regex("(\\d+(?:\\.\\d+)?)")

    class Line(val label: String, val kcal: Double, val protein: Double, val assumed: Boolean, val group: String, val note: String, val carbs: Double = 0.0, val kind: String = "", val fat: Double = 0.0)
    class Estimate(
        val lines: List<Line>, val kcal: Double?, val protein: Double?, val partialKcal: Double, val partialProtein: Double,
        val unknown: List<String>, val assumptions: List<String>, val confidence: String, val balance: Int?,
        val carbs: Double? = null, val groups: List<String> = emptyList(), val fat: Double? = null
    ) {
        val found: Boolean get() = lines.isNotEmpty()
        fun describe(): String = buildString {
            lines.forEach { append("• ${it.label}: ~${it.kcal.roundToInt()} kcal, ~${fmt(it.protein)} g protein\n") }
            if (kcal != null) append("Total ≈ ${kcal.roundToInt()} kcal, ${fmt(protein ?: 0.0)} g protein (estimate)")
            else append("Partial total ≈ ${partialKcal.roundToInt()} kcal – NOT complete")
            if (assumptions.isNotEmpty()) append("\nAssumptions: " + assumptions.joinToString("; "))
            if (unknown.isNotEmpty()) append("\nCould not estimate: " + unknown.joinToString(", ") + " – ask the user for the portion or calories.")
        }
    }

    fun estimate(text: String): Estimate {
        val low = text.lowercase(Locale.ROOT)
            .replace("coffee with milk", "coffee").replace("tea with milk", "tea").replace("chai with milk", "chai")
        val lines = mutableListOf<Line>()
        val unknown = mutableListOf<String>()
        val assumptions = mutableListOf<String>()
        for (raw in low.split(splitter)) {
            val seg = raw.replace(filler, " ").replace(Regex("\\s+"), " ").trim()
            if (seg.isBlank() || seg.none { it.isLetter() }) continue
            val hit = aliasRegex.firstOrNull { it.first.containsMatchIn(seg) }
            if (hit == null) { unknown += seg; continue }
            val food = hit.second
            val grams = gramsRe.find(seg)?.groupValues?.get(1)?.toDoubleOrNull()
            var qty = 1.0
            var assumed = false
            var explicit = false
            if (grams == null) {
                val first = seg.split(" ").firstOrNull().orEmpty()
                val n = numRe.find(seg)?.groupValues?.get(1)?.toDoubleOrNull() ?: numberWords[first] ?: numberWords.entries.firstOrNull { Regex("\\b${it.key}\\b").containsMatchIn(seg) && it.key.length > 1 }?.value
                if (n != null) { qty = n; explicit = true } else assumed = true
            } else explicit = true
            if (Regex("\\bhalf\\b").containsMatchIn(seg) && grams == null) qty = 0.5
            val g = grams ?: (qty * food.unitG)
            val k = g * food.kcal100 / 100.0
            val p = g * food.prot100 / 100.0
            val label = if (grams != null) "${fmt(grams)} g ${food.names.first()}" else "${fmt(qty)} ${if (food.unit == food.names.first()) "" else food.unit + " "}${food.names.first()}".replace("  ", " ")
            if (assumed || (!explicit)) assumptions += "assumed 1 ${food.unit} of ${food.names.first()}" + if (food.note.isNotBlank()) " (${food.note})" else ""
            else if (food.note.isNotBlank() && food.group != "C") assumptions += "${food.names.first()}: ${food.note}"
            lines += Line(label.trim(), k, p, assumed, food.group, food.note, g * (carbs100[food.names.first()] ?: 0.0) / 100.0, kinds[food.names.first()] ?: "", g * (fat100[food.names.first()] ?: 0.0) / 100.0)
        }
        val completeKcal = if (unknown.isEmpty() && lines.isNotEmpty()) lines.sumOf { it.kcal } else null
        val completeProt = if (unknown.isEmpty() && lines.isNotEmpty()) lines.sumOf { it.protein } else null
        val conf = when { lines.isEmpty() -> "low"; unknown.isNotEmpty() || lines.any { it.assumed } -> "low"; else -> "medium" }
        return Estimate(lines, completeKcal, completeProt, lines.sumOf { it.kcal }, lines.sumOf { it.protein }, unknown, assumptions.distinct(), conf, balanceOf(lines.map { it.group }), if (unknown.isEmpty() && lines.isNotEmpty()) lines.sumOf { it.carbs } else null, lines.map { it.kind }.filter { it.isNotBlank() }.distinct(), if (unknown.isEmpty() && lines.isNotEmpty()) lines.sumOf { it.fat } else null)
    }

    /** Variety rubric (a product convention, not a medical rating): protein source + vegetable/fruit + an energy source. */
    fun balanceOf(groups: List<String>): Int? {
        val kinds = groups.map { if (it == "D") "P" else it }.filter { it == "P" || it == "V" || it == "C" }.toSet()
        return when (kinds.size) { 3 -> 100; 2 -> 70; 1 -> 40; else -> null }
    }
}

// ======================================================================================================
// Free-text logging (offline commands and the Health screen's quick box). The AI chat uses tools that write the same rows.
// ======================================================================================================
object HealthParser {
    class Draft(
        val category: String, val label: String, val amount: Double?, val unit: String?, val kcal: Double? = null, val protein: Double? = null,
        val meal: String? = null, val balance: Int? = null, val estimated: Boolean = false, val confidence: String = "high", val note: String = "",
        val carbs: Double? = null, val groups: List<String> = emptyList(), val sets: Int? = null, val fat: Double? = null
    )
    class Parsed(val drafts: List<Draft>, val questions: List<String>)

    fun mealFor(text: String, hour: Int): String {
        val t = text.lowercase(Locale.ROOT)
        return when {
            "breakfast" in t -> "breakfast"; "lunch" in t -> "lunch"; "dinner" in t || "supper" in t -> "dinner"
            "snack" in t -> "snack"
            hour in 5..10 -> "breakfast"; hour in 11..15 -> "lunch"; hour in 16..18 -> "snack"; hour in 19..23 -> "dinner"; else -> "snack"
        }
    }

    /** Canonical exercise name so "squats", "Squat" and "30 squats" match one goal. */
    fun canonName(raw: String): String {
        var s = raw.lowercase(Locale.ROOT).replace(Regex("[^a-z\\- ]"), " ").replace(Regex("\\s+"), " ").trim()
        s = s.replace("push ups", "push-up").replace("pushups", "push-up").replace("pushup", "push-up").replace("push-ups", "push-up")
            .replace("pull ups", "pull-up").replace("pullups", "pull-up").replace("pull-ups", "pull-up")
            .replace("sit ups", "sit-up").replace("situps", "sit-up").replace("sit-ups", "sit-up")
        return when (s) {
            "run", "ran", "running", "jog", "jogged", "jogging" -> "run"
            "walk", "walked", "walking" -> "walk"
            "cycle", "cycled", "cycling", "bike", "biking" -> "cycle"
            "swim", "swam", "swimming" -> "swim"
            else -> when {
                s.endsWith("ches") || s.endsWith("shes") -> s.dropLast(2)
                s.endsWith("s") && !s.endsWith("ss") -> s.dropLast(1)
                else -> s
            }
        }
    }

    private val actWords = "run|ran|running|jog|jogged|jogging|walk|walked|walking|cycle|cycled|cycling|swim|swam|swimming|yoga|gym|workout|football|cricket|badminton|dance|dancing|hiit|stretching|skipping|treadmill|tennis"
    private val repWords = "squats?|push-?ups?|push ups?|pull-?ups?|pull ups?|sit-?ups?|sit ups?|crunches|lunges?|burpees?|dips?|planks?"

    fun parse(text: String, hour: Int): Parsed {
        val t = text.trim()
        val low = t.lowercase(Locale.ROOT)
        val drafts = mutableListOf<Draft>()
        val questions = mutableListOf<String>()

        // ---- sleep
        if (Regex("\\b(slept|sleep|sleeping)\\b").containsMatchIn(low)) {
            val h = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:h|hr|hrs|hour|hours)\\b").find(low)?.groupValues?.get(1)?.toDoubleOrNull()
                ?: Regex("\\bslept\\s+(?:for\\s+)?(\\d+(?:\\.\\d+)?)\\b").find(low)?.groupValues?.get(1)?.toDoubleOrNull()
            if (h != null && h in 0.5..20.0) drafts += Draft("SLEEP", if ("nap" in low) "nap" else "night sleep", h, "h")
            else questions += "How many hours did you sleep?"
        }
        // ---- water
        val waterMl = Regex("(\\d+(?:\\.\\d+)?)\\s*(ml|millilit\\w*|l|lit(?:er|re)s?)\\b").find(low)
        val glasses = Regex("(\\d+(?:\\.\\d+)?|a|an|one|two|three|four|five|six)\\s*(?:big |small )?(glass(?:es)?|cups?|bottles?)\\s*(?:of\\s*)?water").find(low)
        if (Regex("\\bwater\\b|\\bdrank\\b|\\bdrunk\\b|\\bhydrat").containsMatchIn(low) && !Regex("\\bsleep|slept\\b").containsMatchIn(low)) {
            if (waterMl != null && (Regex("\\bwater\\b|\\bdrank\\b|\\bdrunk\\b").containsMatchIn(low))) {
                val v = waterMl.groupValues[1].toDouble()
                val u = waterMl.groupValues[2]
                drafts += Draft("WATER", "water", if (u.startsWith("ml") || u.startsWith("milli")) v else v * 1000.0, "ml")
            } else if (glasses != null) {
                val w = glasses.groupValues[1]
                val n = w.toDoubleOrNull() ?: mapOf("a" to 1.0, "an" to 1.0, "one" to 1.0, "two" to 2.0, "three" to 3.0, "four" to 4.0, "five" to 5.0, "six" to 6.0)[w] ?: 1.0
                val per = if (glasses.groupValues[2].startsWith("bottle")) 500.0 else 250.0
                drafts += Draft("WATER", "water", n * per, "ml", estimated = true, confidence = "medium",
                    note = "assumed ${per.roundToInt()} ml per ${glasses.groupValues[2].removeSuffix("es").removeSuffix("s")}")
            } else if (Regex("\\bwater\\b").containsMatchIn(low)) questions += "How much water – for example 500 ml, 1.5 litres, or a glass (about 250 ml)?"
        }
        // ---- exercise
        // "3 sets of 10 push-ups" / "3x10 squats" → 30 reps in 3 sets
        var lowR = low
        Regex("(\\d+)\\s*(?:x|sets? of|sets? x)\\s*(\\d+)\\s*($repWords)").findAll(low).toList().forEach { m ->
            val sets = m.groupValues[1].toInt(); val per = m.groupValues[2].toInt()
            drafts += Draft("ACTIVITY", canonName(m.groupValues[3]), (sets * per).toDouble(), "reps", sets = sets)
            lowR = lowR.replace(m.value, " ")
        }
        val reps = Regex("(\\d+)\\s*($repWords)").findAll(lowR).toList()
        reps.forEach { m ->
            val name = canonName(m.groupValues[2])
            if (name == "plank") drafts += Draft("ACTIVITY", name, m.groupValues[1].toDouble(), "sec", note = "plank length taken as seconds")
            else drafts += Draft("ACTIVITY", name, m.groupValues[1].toDouble(), "reps")
        }
        val act = Regex("\\b($actWords)\\b").find(low)
        if (act != null) {
            val name = canonName(act.groupValues[1])
            val dist = Regex("(\\d+(?:\\.\\d+)?)\\s*(km|kms|kilomet\\w*|mi|miles?|m|meters?|metres?)\\b").find(low)
            val dur = Regex("(\\d+(?:\\.\\d+)?)\\s*(min|mins|minutes?|hr|hrs|hours?)\\b").find(low)
            if (dist != null) {
                val v = dist.groupValues[1].toDouble(); val u = dist.groupValues[2]
                val km = when { u.startsWith("km") || u.startsWith("kilo") -> v; u.startsWith("mi") -> v * 1.609; else -> v / 1000.0 }
                drafts += Draft("ACTIVITY", name, km, "km")
            } else if (dur != null) {
                val v = dur.groupValues[1].toDouble(); val u = dur.groupValues[2]
                drafts += Draft("ACTIVITY", name, if (u.startsWith("h")) v * 60.0 else v, "min")
            } else questions += "For how long or how far did you $name?"
        }
        // ---- body weight
        Regex("\\b(?:i weigh|weight is|weighed|my weight is|weight)\\s*(?:is\\s*)?(\\d+(?:\\.\\d+)?)\\s*(kg|kgs|kilo|kilos)?\\b").find(low)?.let {
            val w = it.groupValues[1].toDouble()
            if (w in 25.0..300.0) drafts += Draft("WEIGHT", "body weight", w, "kg")
        }
        // ---- food (only when nothing else claimed the text)
        if (drafts.none { it.category != "WEIGHT" } || Regex("\\b(ate|had|eat|eaten|breakfast|lunch|dinner|snack)\\b").containsMatchIn(low)) {
            var onlyFoodText = low
            waterMl?.let { onlyFoodText = onlyFoodText.replace(it.value, " ") }
            glasses?.let { onlyFoodText = onlyFoodText.replace(it.value, " ") }
            onlyFoodText = onlyFoodText
                .replace(Regex("\\b(water|drank|drunk)\\b"), " ")
                .replace(Regex("\\b(slept|sleep)\\b.*"), " ")
                .replace(Regex("(\\d+)\\s*($repWords)"), " ")
                .replace(Regex("\\b($actWords)\\b"), " ")
                .replace(Regex("\\d+(?:\\.\\d+)?\\s*(?:km|kms|min|mins|minutes?|hrs?|hours?|miles?)\\b"), " ")
            val est = FoodDb.estimate(onlyFoodText)
            if (est.found) {
                val meal = mealFor(low, hour)
                val label = est.lines.joinToString(", ") { it.label }
                drafts += Draft(
                    "FOOD", label, null, null, est.kcal, est.protein, meal, if (meal == "snack") null else est.balance,
                    estimated = true, confidence = est.confidence, note = est.assumptions.joinToString("; "), carbs = est.carbs, groups = est.groups, fat = est.fat
                )
                if (est.unknown.isNotEmpty()) questions += "I couldn't estimate “${est.unknown.joinToString(", ")}” – tell me the portion or calories and I'll add it."
            } else if (drafts.isEmpty() && Regex("\\b(ate|had|eat|eaten)\\b").containsMatchIn(low)) {
                questions += "I don't recognise that food yet – tell me what it was with a portion (e.g. “1 bowl biryani”), or enter calories yourself in Health."
            }
        }
        return Parsed(drafts, questions)
    }
}

// ======================================================================================================
// Health of Day
//   H = 0.35 N + 0.25 A + 0.20 S + 0.20 W      (each 0–100; a category that is not logged contributes 0 and the denominator stays 100 %)
//   N = 0.40 C + 0.25 P + 0.15 CH + 0.20 M     calories · protein · carbohydrates · variety
//   S = 100 inside the personal sleep range, otherwise 100 × (1 − distance outside the range ÷ 4 h)
//   W = 100 × water ÷ reference (capped), with a safety fall-off for far too much
//   A = training dose (MET-minutes from what was logged) ÷ an age/ability benchmark, capped at 100 with a cautious overload penalty
// ======================================================================================================
object HealthScore {
    const val VERSION = 2
    const val W_N = 0.35
    const val W_A = 0.25
    const val W_S = 0.20
    const val W_W = 0.20
    val MAIN_MEALS = setOf("breakfast", "lunch", "dinner")

    /** One sub-score inside a category (for example Calories 87 inside Nutrition). */
    class Part(val label: String, val score: Float?, val detail: String)

    class CatEval(
        val key: String, val label: String, val weight: Double,
        /** null = not logged (it counts as 0 in the overall score, but the UI says "Not logged"). */
        val score: Float?,
        /** NOT_LOGGED | COMPLETE (logged) */
        val state: String,
        val estimated: Boolean, val unconfirmed: Boolean,
        val summary: String, val notes: List<String>,
        val parts: List<Part> = emptyList()
    ) {
        val logged: Boolean get() = state != "NOT_LOGGED"
    }

    class ExerciseLine(val entry: HealthEntryEntity, val est: ExerciseCalc.Est?)

    class DayEval(
        val day: Long, val cats: List<CatEval>,
        /** The overall percentage (null only when nothing at all was logged that day). */
        val score: Float?, val hasData: Boolean,
        val kcal: Double?, val protein: Double?, val carbs: Double?, val fat: Double?, val fiber: Double?,
        val sleepH: Double?, val waterMl: Double?,
        val burnedKcal: Double?, val activeMinutes: Double, val metMin: Double, val exercises: List<ExerciseLine>,
        val targets: HealthTargets, val entries: List<HealthEntryEntity>, val row: HealthDayEntity?
    ) {
        fun cat(key: String) = cats.first { it.key == key }
        val confirmed: Boolean get() = false
        val isFinal: Boolean get() = hasData
        val coverage: Float get() = (cats.filter { it.score != null }.sumOf { it.weight } * 100.0).toFloat()
        val statusLabel: String get() = if (hasData) "Live" else "No data"
        val missing: List<String> get() = cats.filter { it.score == null }.map { it.label + ": Not logged" }
    }

    private fun clamp(v: Double) = max(0.0, min(100.0, v))

    fun evaluate(day: Long, entries: List<HealthEntryEntity>, row: HealthDayEntity?, t: HealthTargets): DayEval {
        val cats = mutableListOf<CatEval>()

        // ---------------- Nutrition
        val foods = entries.filter { it.category == "FOOD" }
        val kcal = foods.sumOf { it.kcal ?: 0.0 }
        val prot = foods.sumOf { it.protein ?: 0.0 }
        val carbsKnown = foods.filter { it.carbs != null }
        val carbs = carbsKnown.sumOf { it.carbs ?: 0.0 }
        val fat = foods.mapNotNull { it.fat }.sum()
        val fiber = foods.mapNotNull { it.fiber }.sum()
        val nNotes = mutableListOf<String>()
        val parts = mutableListOf<Part>()
        var nScore: Float? = null
        if (foods.isNotEmpty()) {
            val comps = mutableListOf<Pair<Double, Double>>()   // weight, score
            val tk = t.kcal; val tp = t.proteinG
            if (tk != null && tk > 0) {
                val c = clamp(100.0 * (1.0 - abs(kcal - tk) / tk))
                comps += 0.40 to c; parts += Part("Calories", c.toFloat(), "${kcal.roundToInt()} of ${tk} kcal – both too little and too much lower it")
            }
            if (tp != null && tp > 0) {
                val p = min(100.0, prot / tp * 100.0)
                comps += 0.25 to p; parts += Part("Protein", p.toFloat(), "${fmt(prot)} of ${tp} g")
            }
            val lo = t.carbLowG; val hi = t.carbHighG
            if (carbsKnown.isNotEmpty() && lo != null && hi != null && hi > 0) {
                val ch = when {
                    carbs < lo -> 100.0 * carbs / lo
                    carbs > hi -> max(0.0, 100.0 * (1.0 - (carbs - hi) / (0.5 * hi)))
                    else -> 100.0
                }
                comps += 0.15 to ch; parts += Part("Carbs", ch.toFloat(), "${fmt(carbs)} g, healthy range $lo–$hi g")
                if (carbsKnown.size < foods.size) nNotes += "Some foods have no carb value, so carbs cover only part of what you ate."
            } else parts += Part("Carbs", null, "not measured for these foods")
            val kinds = foods.flatMap { it.groups.split(",") }.map { it.trim() }.filter { it.isNotBlank() }.toSet()
            if (kinds.isNotEmpty()) {
                val m = 100.0 * min(1.0, kinds.size / 5.0)
                comps += 0.20 to m; parts += Part("Variety", m.toFloat(), "${kinds.size} food groups: ${kinds.joinToString()}")
            } else parts += Part("Variety", null, "food groups unknown")
            nNotes += "Vitamins and minerals are not measured one by one – the variety score is a proxy, and unknown nutrients are not guessed."
            if (foods.any { it.kcal == null }) nNotes += "Some foods have no calorie value yet, so calories are underestimated."
            nScore = if (comps.isEmpty()) null else (comps.sumOf { it.first * it.second } / comps.sumOf { it.first }).toFloat()
        }
        val nSummary = if (foods.isEmpty()) "Not logged" else
            "${kcal.roundToInt()}${t.kcal?.let { " / $it" } ?: ""} kcal · ${prot.roundToInt()}${t.proteinG?.let { " / $it" } ?: ""} g protein" + (if (carbsKnown.isNotEmpty()) " · ${carbs.roundToInt()} g carbs" else "") + (if (foods.any { it.fat != null }) " · ${fat.roundToInt()} g fat" else "")
        cats += CatEval("N", "Nutrition", W_N, nScore, if (foods.isEmpty()) "NOT_LOGGED" else "COMPLETE", foods.any { it.estimated }, foods.any { !it.confirmed }, nSummary, nNotes, parts)

        // ---------------- Activity (estimated from what was logged – no goals to set)
        val acts = entries.filter { it.category == "ACTIVITY" }
        val lines = acts.map { e -> ExerciseLine(e, ExerciseCalc.fromJson(e.extra) ?: ExerciseCalc.estimate(e.label, e.amount, e.unit, t.bodyKg)) }
        val known = lines.mapNotNull { it.est }
        val dose = known.sumOf { it.metMin }
        val minutes = known.sumOf { it.minutes }
        val burned = known.mapNotNull { it.kcal }.takeIf { it.isNotEmpty() }?.sum()
        val aNotes = mutableListOf<String>()
        var aScore: Float? = null
        val aParts = mutableListOf<Part>()
        if (acts.isNotEmpty()) {
            if (known.isEmpty()) aNotes += "These exercises are not in the estimate table yet – connect an AI in API & Subscription to estimate them. Nothing is guessed."
            else {
                val target = max(1.0, t.activityMetMin)
                var a = min(100.0, 100.0 * dose / target)
                if (dose > 3.0 * target) a = max(40.0, 100.0 - 20.0 * (dose / target - 3.0))   // cautious overload penalty
                aScore = a.toFloat()
                aParts += Part("Training dose", aScore, "${dose.roundToInt()} of ~${target.roundToInt()} MET-min benchmark")
                if (dose > 3.0 * target) aNotes += "That is a lot of training for one day – recovery matters, so the score eases off."
                if (lines.size > known.size) aNotes += "${lines.size - known.size} exercise(s) could not be estimated and are not counted."
                aNotes += "Calories burned are estimates (MET × weight × time). Strength is described as a training stimulus, not exact muscle gained."
            }
        }
        val aSummary = if (acts.isEmpty()) "Not logged" else
            lines.joinToString(" · ") { "${fmt(it.entry.amount ?: 0.0)} ${it.entry.unit ?: ""} ${it.entry.label}".replace("  ", " ") } +
                (burned?.let { " · ~${it.roundToInt()} kcal (est.)" } ?: "")
        cats += CatEval("A", "Activity", W_A, aScore, if (acts.isEmpty()) "NOT_LOGGED" else "COMPLETE", acts.any { it.estimated } || lines.any { it.est?.source == "ai" }, acts.any { !it.confirmed }, aSummary, aNotes, aParts)

        // ---------------- Sleep (a personal range, both too little and too much lower it)
        val sl = entries.filter { it.category == "SLEEP" }
        val hours = sl.sumOf { it.amount ?: 0.0 }
        val lo = t.sleepLoH ?: 7.0; val hi = t.sleepHiH ?: 9.0
        var sScore: Float? = null
        val sNotes = mutableListOf<String>()
        if (sl.isNotEmpty()) {
            val dist = if (hours < lo) lo - hours else if (hours > hi) hours - hi else 0.0
            sScore = max(0.0, 100.0 * (1.0 - dist / 4.0)).toFloat()
            if (hours > hi) sNotes += "More sleep than your range also lowers the score."
        } else sNotes += "Enter how many hours you slept before waking up today."
        cats += CatEval(
            "S", "Sleep", W_S, sScore, if (sl.isEmpty()) "NOT_LOGGED" else "COMPLETE", sl.any { it.estimated }, sl.any { !it.confirmed },
            if (sl.isEmpty()) "Not logged" else "${fmt(hours)} h (your range ${fmt(lo)}–${fmt(hi)} h)", sNotes,
            if (sl.isEmpty()) emptyList() else listOf(Part("Sleep range", sScore, "${fmt(hours)} h vs ${fmt(lo)}–${fmt(hi)} h"))
        )

        // ---------------- Hydration (capped; far too much water is not rewarded)
        val wl = entries.filter { it.category == "WATER" }
        val ml = wl.sumOf { it.amount ?: 0.0 }
        val ref = (t.waterMl ?: 2000).toDouble()
        var wScore: Float? = null
        val wNotes = mutableListOf<String>()
        if (wl.isNotEmpty()) {
            wScore = (if (ml <= 1.5 * ref) min(100.0, 100.0 * ml / ref) else max(0.0, 100.0 - 100.0 * (ml - 1.5 * ref) / ref)).toFloat()
            if (ml > 1.5 * ref) wNotes += "That is far more than your reference – drinking much more than you need is not helpful, so the score eases off."
        }
        cats += CatEval(
            "W", "Water", W_W, wScore, if (wl.isEmpty()) "NOT_LOGGED" else "COMPLETE", wl.any { it.estimated }, wl.any { !it.confirmed },
            if (wl.isEmpty()) "Not logged" else "${ml.roundToInt()} / ${ref.roundToInt()} ml", wNotes,
            if (wl.isEmpty()) emptyList() else listOf(Part("Hydration", wScore, "${ml.roundToInt()} of ${ref.roundToInt()} ml"))
        )

        // ---------------- overall: fixed weights, the denominator is always 100 %
        val hasData = entries.any { it.category in setOf("FOOD", "ACTIVITY", "SLEEP", "WATER") }
        val overall = cats.sumOf { it.weight * (it.score ?: 0f).toDouble() }.toFloat()
        return DayEval(
            day, cats, if (hasData) overall else null, hasData,
            if (foods.isEmpty()) null else kcal, if (foods.isEmpty()) null else prot, if (carbsKnown.isEmpty()) null else carbs,
            fat.takeIf { foods.any { it.fat != null } }, fiber.takeIf { foods.any { it.fiber != null } },
            if (sl.isEmpty()) null else hours, if (wl.isEmpty()) null else ml, burned, minutes, dose, lines, t, entries, row
        )
    }
}
