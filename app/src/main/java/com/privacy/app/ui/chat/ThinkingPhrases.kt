package com.privacy.app.ui.chat

/**
 * Instead of a bare "Thinking…", the app says something that fits what you just asked – and keeps changing it while it works.
 * [name] is how the user is addressed ("Sir" by default).
 */
object ThinkingPhrases {

    private fun general(n: String) = listOf(
        "Wait a sec, $n…", "Analysing…", "Think and blink…", "Cooking something…", "Connecting the dots…",
        "Warming up the neurons…", "On it, $n…", "Brain loading…", "Give me a moment, $n…",
        "Doing the thinking so you don't have to…", "Putting the pixels in order…", "Hmm, interesting one…"
    )

    private fun tasks(n: String) = listOf(
        "Checking your list, $n…", "Sorting out your to-dos…", "Ticking boxes in my head…", "Making your day less messy…",
        "Lining up your tasks…"
    )

    private fun notes(n: String) = listOf(
        "Writing that down…", "Scribbling it into your notes…", "Filing that neatly, $n…", "Pen out, pixels ready…"
    )

    private fun reminders(n: String) = listOf(
        "Winding the alarm clock…", "Setting a reminder you can't ignore…", "Teaching time to tap you on the shoulder…",
        "Booking that for you, $n…"
    )

    private fun research(n: String) = listOf(
        "Digging through the internet…", "Reading so you don't have to…", "Asking the web nicely…",
        "Chasing down the facts, $n…", "Browsing like a very fast owl…", "Comparing sources…"
    )

    private fun math(n: String) = listOf(
        "Crunching the numbers…", "Counting on my pixel fingers…", "Math mode on…", "Double-checking the arithmetic, $n…"
    )

    private fun greet(n: String) = listOf(
        "Nice one, $n…", "Good to see you, $n…", "Pixels waving hello…", "Warming up a reply…"
    )

    private fun creative(n: String) = listOf(
        "Brainstorming furiously…", "Polishing the words, $n…", "Finding the right phrase…", "Sketching ideas in pixels…"
    )

    private fun photo(n: String) = listOf(
        "Squinting at your photo…", "Reading every pixel, $n…", "Examining the picture…"
    )

    private fun pick(text: String, n: String): List<String> {
        val t = text.lowercase()
        fun has(vararg w: String) = w.any { t.contains(it) }
        return when {
            has("photo", "image", "picture", "📷") -> photo(n) + general(n)
            has("remind", "alarm", "automation", "every morning", "every day", "schedule", "notify", "timer") -> reminders(n) + general(n)
            has("research", "find ", "search", "latest", "news", "compare", "price", "who is", "what is", "how to", "explain") -> research(n) + general(n)
            has("task", "todo", "to-do", "done", "complete", "habit", "plan my", "my day") -> tasks(n) + general(n)
            has("note", "save", "write down", "remember", "memo") -> notes(n) + general(n)
            has("calculate", "calc", "sum ", "total", "percent", "%", "+", "×", "*") && t.any { it.isDigit() } -> math(n) + general(n)
            has("hi", "hello", "hey", "thanks", "thank you", "good morning", "good night") && t.length < 30 -> greet(n) + general(n)
            has("write", "draft", "email", "idea", "story", "poem", "caption") -> creative(n) + general(n)
            else -> general(n)
        }
    }

    /** A phrase for this moment: rotates every ~2.4 s, starting from a spot that depends on the question. */
    fun phrase(userText: String, userName: String, tick: Int): String {
        val list = pick(userText, userName.ifBlank { "Sir" })
        val start = (userText.hashCode() and 0x7fffffff) % list.size
        return list[(start + tick) % list.size]
    }
}
