package com.privacy.app.credits

/**
 * Your Supabase and Google values are NOT written here. Put them in local.properties (see local.properties.example);
 * Gradle turns them into BuildConfig at build time, so they never reach GitHub.
 * The anon key is public by design (it only works together with the row-level-security rules in supabase/schema.sql).
 * NEVER put the Groq key or the Supabase service_role key in the app.
 */
object CreditsConfig {
    val SUPABASE_URL: String = com.privacy.app.BuildConfig.SUPABASE_URL
    val SUPABASE_ANON_KEY: String = com.privacy.app.BuildConfig.SUPABASE_ANON_KEY
    /** Add exactly this under Supabase → Authentication → URL Configuration → Redirect URLs. */
    const val REDIRECT = "privacy://auth"

    /** "Web application" OAuth client ID from Google Cloud (also add it, and your Android client ID, to Supabase → Auth → Google → Authorized Client IDs). */
    val GOOGLE_WEB_CLIENT_ID: String = com.privacy.app.BuildConfig.GOOGLE_WEB_CLIENT_ID
    val nativeGoogle: Boolean get() = !GOOGLE_WEB_CLIENT_ID.startsWith("YOUR-")

    /** The admin people contact to pay (Telegram, without @). */
    const val ADMIN_TELEGRAM = "thenoirflames"

    /** Buy Me a Coffee pages: monthly plans open the membership page, top-ups open the main page. */
    const val BMC_PLANS_URL = "https://buymeacoffee.com/v_shal_/membership"
    const val BMC_TOPUP_URL = "https://buymeacoffee.com/v_shal_"

    val configured: Boolean get() = !SUPABASE_URL.contains("YOUR-PROJECT") && !SUPABASE_ANON_KEY.startsWith("YOUR-")
}

data class CreditPack(val name: String, val price: Int, val credits: Int, val note: String = "", val recommended: Boolean = false)

object CreditCatalog {
    val plans = listOf(
        CreditPack("Starter", 1, 200, "per month"),
        CreditPack("Plus", 3, 800, "per month", recommended = true),
        CreditPack("Pro", 5, 1800, "per month")
    )
    val topUps = listOf(
        CreditPack("Spark", 1, 150, "one time"),
        CreditPack("Boost", 2, 400, "one time"),
        CreditPack("Power", 4, 800, "one time")
    )

    /** What each action typically costs (credits). Small tasks are free until they add up to a whole credit. */
    val costs = listOf(
        Pair("Quick task – parse food, classify, short summary", "free · under 1"),
        Pair("Assistant bubble / tap insight", "free · under 1"),
        Pair("Normal chat reply", "2 – 3"),
        Pair("Chat with long history or notes attached", "3 – 6"),
        Pair("Morning brief", "3 – 5"),
        Pair("Automation run (research / report)", "4 – 9"),
        Pair("Weekly / monthly report analysis", "6 – 9"),
        Pair("Agent mode (big job, several steps)", "8 – 25"),
        Pair("Photo → text / photo analysis", "4 – 6"),
        Pair("Voice to text (listening)", "1 per minute"),
        Pair("Natural spoken reply (phone voice is free)", "≈ 4 per 200 characters"),
        Pair("Your own API key", "0")
    )
}
