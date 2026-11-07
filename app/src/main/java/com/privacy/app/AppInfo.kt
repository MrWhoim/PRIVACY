package com.privacy.app

/**
 * One place for the app's identity. Hard-coded on purpose: the assistant knows these facts and the About page shows them.
 * (The internal package name stays com.privacy.app so existing installs, databases and vaults keep working after the rename.)
 */
object AppInfo {
    const val NAME = "P.R.I.V.A.C.Y."
    const val VERSION = "1.0.0"

    /** Each letter maps to something the app really does. */
    val ACRONYM: List<Pair<String, String>> = listOf(
        "P" to "Private – everything lives on your phone; vault, tasks and notes never leave it. An account (Google name + email only) exists just for buying AI credits",
        "R" to "Reminders – tasks, daily habits, automations and notifications",
        "I" to "Intelligence – the AI assistant and its Agent mode for big jobs",
        "V" to "Voice – talk to it, and it talks back through the pixel face and the widget",
        "A" to "Automation – things that run by themselves at the time you choose",
        "C" to "Companion – the pixel face that listens, reacts and keeps you company",
        "Y" to "You – built around you: your name, your goals, your rules"
    )
    const val FULL_FORM = "Private Reminders & Intelligence with Voice, Automation, Companion – for You"

    /** What the app is, in plain words. Shown in About & privacy and given to the assistant when someone asks what the app is or what it can do. */
    const val OVERVIEW =
        "P.R.I.V.A.C.Y. is a private, all-in-one personal assistant for Android. You talk or type to an AI that knows your tasks, health, notes and habits, and it can act for you: add tasks, write notes, log meals, set reminders and automations. " +
            "It keeps your day in three scores – Efficiency (tasks), Health (food, activity, sleep, water) and Mind (focus vs. distraction from your phone use) – and turns them into reports with honest advice. " +
            "No account is needed: your data stays on your phone and the vault and backups are encrypted. What leaves is only the text of an AI request – to the provider you chose with your own API key, or, if you buy credits, through our secure server to secure AI providers, not stored. Credits need a Google sign-in; we keep only your name and email, for that purpose alone."

    /** Feature list: title to description. Also what the assistant answers from. */
    val FEATURES: List<Pair<String, String>> = listOf(
        "Chat & Agent" to "Ask anything. Quick questions get quick answers; big or research-style requests switch to Agent mode (a big model plans, small models work in parallel). It can use the web, your notes, tasks, memories and the phone itself.",
        "Tasks" to "Daily and one-off tasks with priority, difficulty, optional and core flags. A pixel progress bar shows the day; tap the date to browse every earlier day. Efficiency of the Day is scored from planned weight, credit for done/in-progress and up to four commitment stars.",
        "Health" to "Log food, exercise, sleep, water and weight by chat or form. Nutrition numbers are estimates and labelled so. Health of the Day = 35% nutrition, 25% activity, 20% sleep, 20% water, with a meal plan, goals and history.",
        "Mind" to "Tracks every app you use. Distracting apps cost credit, useful ones (study, fitness, productivity) earn it, and unknown apps give nothing until you decide. Tap an app for hourly graphs of usage and credits. Useful time you log (work, learning, exercise, personal) counts too. Shows Detox vs Dopamine %, a day graph, a morning guard that nudges you in the first two hours after waking, and rates each app use.",
        "Reports" to "Day, week and month reports for Efficiency, Health and Mind with rings, bars, trends and an AI section that says what went well, what keeps going wrong and how to fix it. Share them as a picture.",
        "Notes" to "Markdown notes with photos, audio, pinning, AI rewrite, share as image, and live app-to-app sharing where one person edits at a time.",
        "Private vault" to "An encrypted vault (passcode or biometrics) for personal facts and credentials; you choose which items the assistant may use.",
        "Memory" to "The assistant remembers lasting facts you tell it (name, job, goals, preferences) and you can edit them.",
        "Automations & Morning Brief" to "Reminders, task lists, AI prompts and note-reading at the times you pick, even when the app is closed. The Morning Brief gives yesterday, today's plan, leftovers, what is going well and what keeps going wrong.",
        "Tools" to "Countdown timer, stopwatch, alarm, calendar, currency converter and calculator – by hand or just ask.",
        "Voice & Companion" to "A pixel assistant that listens, talks back, reacts to taps and floats over your screen from a 1×1 widget.",
        "Widgets" to "Tasks, Health, Mind (Dopamine vs Detox), Note, Note view and Mic widgets, refreshed in the background.",
        "Telegram sync" to "Pair your own Telegram bot with a friend's bot to share an AI chat or a note app-to-app. Accept/Reject requests, a live list of connections, one editor at a time. No messages go to anyone's chat.",
        "Modes" to "Feeling Distracted (gentle nudges), Music Mode (a player over the chat), Telegram AI Connect and Telegram Notes Connect.",
        "Backup" to "One encrypted file (AES-256) with a password you choose: tasks, health, Mind and focus history, notes, chats, memories, routines, automations, vault, settings and API keys (you choose which). Pictures are never included. Import merges without duplicates. Monthly plan users can also turn on a daily encrypted cloud backup.",
        "Private by design" to "No analytics, no ads. A Google sign-in (name + email only) exists just for AI credits; without credits there is no account and no server of ours. AI keys are stored encrypted on the phone and several free keys rotate automatically when one hits a limit."
    )

    const val CREATOR = "Vishal Sharma"
    const val GITHUB = "github.com/mrwhoim"
    const val X_HANDLE = "x.com/v_shal_"
    const val GITHUB_URL = "https://github.com/mrwhoim"
    const val X_URL = "https://x.com/v_shal_"
    /** Where "Fund the project" leads. Change this one line to your own UPI / Ko-fi / Buy-Me-a-Coffee / sponsors page. */
    const val FUND_URL = "https://buymeacoffee.com/v_shal_"
    const val STAR_URL = "https://github.com/mrwhoim"

    /** How the assistant addresses the user unless they choose another name. */
    const val DEFAULT_USER_CALL = "Sir"
    const val CALL_FEMALE = "Ma'am"
    const val DEFAULT_ASSISTANT_NAME = "P.R.I.V.A.C.Y."
}
