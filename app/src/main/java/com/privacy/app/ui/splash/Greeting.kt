package com.privacy.app.ui.splash

import com.privacy.app.ui.face.FaceMood
import com.privacy.app.ui.face.Gesture
import java.time.DayOfWeek
import java.time.LocalDateTime

/** What the assistant says (and does) when the app opens. Built from the clock and the user's own tasks – no AI call, so it is instant and free. */
data class Greeting(val line: String, val sub: String, val gesture: Gesture, val mood: FaceMood)

class GreetingContext(
    val call: String,            // "Sir" / "Ma'am"
    val name: String,            // may be empty
    val now: LocalDateTime,
    val openTasks: Int,
    val doneToday: Int,
    val overdue: Int,
    val hoursAway: Long,         // since the app was last used; -1 = unknown / first launch
    val firstRun: Boolean,
    val assistantName: String,
    val memory: String? = null,   // something the assistant remembers about the user
    val noteTitle: String? = null // a recent note
)

object Greetings {
    private fun who(c: GreetingContext) = if (c.name.isBlank()) c.call else "${c.name.trim().split(" ").first()} ${c.call.lowercase()}"
    private fun g(line: String, sub: String, gest: Gesture, mood: FaceMood) = Greeting(line, sub, gest, mood)

    /** Lines shown recently (newest last). Used so the same greeting does not come twice in a row. */
    fun pick(c: GreetingContext, recent: List<String>): Greeting {
        val all = candidates(c)
        // most openings are NOT about tasks – only now and then (about 1 in 5) do they come up
        val taskish = Regex("task|overdue|open|list|ticked|finish|pending|to go|done|waiting", RegexOption.IGNORE_CASE)
        val nonTask = all.filter { !taskish.containsMatchIn(it.line + " " + it.sub) }
        val pool = if (nonTask.isNotEmpty() && kotlin.random.Random.nextInt(100) < 80) nonTask else all
        val fresh = pool.filter { it.line !in recent }
        return (if (fresh.isNotEmpty()) fresh else pool).random()
    }

    fun forNow(c: GreetingContext): Greeting = pick(c, emptyList())

    private fun candidates(c: GreetingContext): List<Greeting> {
        val h = c.now.hour
        val w = who(c)
        val open = c.openTasks
        val total = c.openTasks + c.doneToday
        val weekend = c.now.dayOfWeek == DayOfWeek.SATURDAY || c.now.dayOfWeek == DayOfWeek.SUNDAY
        val day = c.now.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
        val out = ArrayList<Greeting>()

        if (c.firstRun) return listOf(g("Hello. I'm ${c.assistantName}.", "Tell me your name and how to address you – then we begin.", Gesture.WAVE, FaceMood.HAPPY))

        if (c.hoursAway >= 72) {
            val d = c.hoursAway / 24
            val tail = if (open > 0) "$open tasks are waiting." else "Nothing is pending – a clean slate."
            return listOf(
                g("Welcome back, $w.", "It's been $d days. $tail", Gesture.WAVE, FaceMood.HAPPY),
                g("There you are, $w!", "I counted $d days. $tail", Gesture.CHEER, FaceMood.HAPPY),
                g("Missed you, $w.", "$d days is a long time. $tail", Gesture.HEART, FaceMood.LOVE),
                g("Look who's back.", "Gone $d days, $w. $tail", Gesture.SHRUG, FaceMood.WINK)
            )
        }
        if (h in 0..4) out += listOf(
            g("These late nights, $w?", if (open > 0) "$open tasks are open. Tackle one, then sleep." else "Nothing pending. Rest is productive too.", Gesture.THINK, FaceMood.SURPRISED),
            g("Still awake, $w?", "Tomorrow-you will thank tonight-you for sleeping.", Gesture.SHRUG, FaceMood.SLEEPY),
            g("The world is asleep, $w.", "Only you and I are up. What's on your mind?", Gesture.WAVE, FaceMood.SLEEPY),
            g("Burning the midnight oil?", "Fine – but let's keep it short, $w.", Gesture.POINT, FaceMood.CONFUSED)
        )
        if (total > 0 && open == 0) out += listOf(
            g("All done for today, $w.", "A clean sweep. Well earned.", Gesture.THUMBS_UP, FaceMood.HAPPY),
            g("Every task finished, $w!", "That's a perfect list. Take a breath.", Gesture.CLAP, FaceMood.HAPPY),
            g("Zero left. Zero.", "I'm proud of you, $w.", Gesture.HEART, FaceMood.LOVE),
            g("Look at that list, $w.", "Nothing but ticks. Show-off.", Gesture.CHEER, FaceMood.WINK)
        )
        if (c.overdue > 0) {
            val n = c.overdue
            out += listOf(
                g("$w, $n task${if (n > 1) "s are" else " is"} overdue.", "Shall we sort ${if (n > 1) "them" else "it"} out now?", Gesture.POINT, FaceMood.NEUTRAL),
                g("Ahem, $w.", "$n overdue. They won't finish themselves.", Gesture.SHRUG, FaceMood.CONFUSED),
                g("Something is late, $w.", "Let's clear the oldest one first.", Gesture.THINK, FaceMood.NEUTRAL)
            )
        }
        if (open > 0 && c.doneToday > 0 && h >= 12) out += listOf(
            g("$w, some work is unfinished.", "${c.doneToday} done, $open to go. You're close.", Gesture.PRESENT, FaceMood.NEUTRAL),
            g("Halfway there, $w.", "${c.doneToday} ticked, $open waiting. Keep going.", Gesture.THUMBS_UP, FaceMood.HAPPY),
            g("Good progress, $w.", "Just $open more. I believe in you.", Gesture.CHEER, FaceMood.HAPPY)
        )
        when (h) {
            in 5..11 -> out += listOf(
                g("Good morning, $w.", if (open > 0) "$open task${if (open > 1) "s" else ""} on today's list. Let's begin." else if (weekend) "A free weekend morning. What shall we do?" else "Your list is empty – shall we plan the day?", Gesture.WAVE, FaceMood.HAPPY),
                g("Happy $day, $w!", "A fresh page. Make it count.", Gesture.CHEER, FaceMood.HAPPY),
                g("Rise and shine, $w.", "Coffee first, then conquer.", Gesture.WAVE, FaceMood.WINK),
                g("New day, new list.", if (open > 0) "$open things are waiting for you, $w." else "Nothing planned yet, $w. Your move.", Gesture.PRESENT, FaceMood.NEUTRAL)
            )
            in 12..16 -> out += listOf(
                g("Good afternoon, $w.", if (open > 0) "$open still open. Keep the pace." else "How is the day treating you?", Gesture.PRESENT, FaceMood.NEUTRAL),
                g("Afternoon slump, $w?", "A small task will wake you up.", Gesture.POINT, FaceMood.SLEEPY),
                g("Hey $w.", "Half the day is gone. How's the plan holding?", Gesture.WAVE, FaceMood.HAPPY),
                g("Lunch done, $w?", "Time for round two.", Gesture.CHEER, FaceMood.HAPPY)
            )
            in 17..21 -> out += listOf(
                g("Good evening, $w.", if (open > 0) "$open to finish before you wind down." else "How was your day?", Gesture.WAVE, FaceMood.HAPPY),
                g("How's it going, $w?", "Tell me one good thing from today.", Gesture.WAVE, FaceMood.HAPPY),
                g("Golden hour, $w.", if (open > 0) "One last push on those $open?" else "You've earned the evening.", Gesture.PRESENT, FaceMood.NEUTRAL),
                g("Winding down, $w?", "Shall I plan tomorrow while you relax?", Gesture.THINK, FaceMood.NEUTRAL)
            )
            else -> out += listOf(
                g("Getting late, $w.", if (open > 0) "$open tasks are open. Pick the smallest and close it." else "Let's wrap up and rest.", Gesture.THINK, FaceMood.NEUTRAL),
                g("Night mode, $w.", "Anything to jot down before bed?", Gesture.POINT, FaceMood.SLEEPY),
                g("Almost bedtime.", "Tomorrow starts clean, $w.", Gesture.WAVE, FaceMood.SLEEPY)
            )
        }
        // memories, notes and jokes – so the opening is not always about the task list
        c.memory?.takeIf { it.isNotBlank() }?.let { m -> out += listOf(
            g("I was just thinking, $w…", "You once told me: ${m.take(70)}. Still true?", Gesture.THINK, FaceMood.NEUTRAL),
            g("Remember this, $w?", m.take(80), Gesture.POINT, FaceMood.HAPPY)
        ) }
        c.noteTitle?.takeIf { it.isNotBlank() }?.let { n -> out += listOf(
            g("Your note “${n.take(28)}” is still there, $w.", "Want to add to it?", Gesture.PRESENT, FaceMood.NEUTRAL),
            g("Hey, I read “${n.take(28)}”.", "Interesting. I have opinions.", Gesture.WAVE, FaceMood.WINK)
        ) }
        out += listOf(
            g("Quick joke, $w:", "I told my phone I needed space. It opened a new tab.", Gesture.SHRUG, FaceMood.WINK),
            g("Why do programmers prefer dark mode?", "Because light attracts bugs, $w.", Gesture.CHEER, FaceMood.HAPPY),
            g("Fun fact, $w:", "Honey never spoils. I, however, expire without snacks.", Gesture.PRESENT, FaceMood.HAPPY),
            g("Let's go, $w!", "Energy at 110% – borrow some.", Gesture.CHEER, FaceMood.HAPPY),
            g("Plot twist, $w:", "today might actually be a good one.", Gesture.WAVE, FaceMood.WINK),
            g("Ooh, you're here, $w!", "I stretched my pixels in advance.", Gesture.WAVE, FaceMood.HAPPY)
        )
        // a few wildcards at any hour keep it from feeling scripted
        out += listOf(
            g("Hello again, $w.", "I'm here whenever you need me.", Gesture.WAVE, FaceMood.HAPPY),
            g("Ready when you are, $w.", "Ask, plan, remember – I've got you.", Gesture.PRESENT, FaceMood.NEUTRAL),
            g("Psst, $w.", "Did you drink water today?", Gesture.POINT, FaceMood.WINK),
            g("It's $day.", if (open > 0) "$open on the list. No pressure. Some pressure." else "Nothing on the list. Suspicious.", Gesture.SHRUG, FaceMood.WINK)
        )
        return out
    }
}
