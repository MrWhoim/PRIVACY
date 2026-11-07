package com.privacy.app.ai

/**
 * Decides, per message, whether a request deserves Agent mode (big model plans → small models work in parallel → big model answers)
 * or a normal quick reply. Free and instant: it scores the wording and size of the request – no extra AI call.
 */
object AgentRouter {
    private val heavy = Regex(
        """\b(deep(ly)?|in[- ]?depth|detailed|thorough(ly)?|comprehensive|high[- ]level|research|investigate|investigation|analy[sz]e|analysis|""" +
            """compare|comparison|benchmark|strategy|roadmap|business plan|market (research|analysis)|competitors?|due diligence|""" +
            """literature review|full plan|complete plan|audit|agentic|agent mode|use (the )?agent|multi[- ]?step|""" +
            """(write|prepare|draft|build|create|make) (me )?(a |an |the )?(report|proposal|whitepaper|essay|business plan|study plan|strategy|case study)|""" +
            """report on|find (me )?\d+|list of \d+|(leads|prospects) (for|in)|plan my (week|month|quarter|year))\b""",
        RegexOption.IGNORE_CASE
    )
    private val quickAction = Regex("""^(add|create|remind|set|open|call|note|remember|delete|remove|done|mark|turn|start|stop|cancel|snooze)\b""", RegexOption.IGNORE_CASE)
    private val wantsShort = Regex("""\b(quick(ly)?|brief(ly)?|short|one line|in a word|tl;?dr|just tell me)\b""", RegexOption.IGNORE_CASE)
    private val sequence = Regex("""(\bthen\b|\bafter that\b|\balso\b|\bnext,?\b|\bfinally\b|\n\s*(\d+[.)]|[-•*])\s)""", RegexOption.IGNORE_CASE)

    fun shouldUseAgent(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.startsWith("/")) return false
        val words = t.split(Regex("\\s+")).size
        if (quickAction.containsMatchIn(t) && words < 16) return false

        var score = 0
        if (heavy.containsMatchIn(t)) score += 2
        score += when {
            words >= 45 -> 2
            words >= 24 -> 1
            else -> 0
        }
        if (sequence.findAll(t).count() >= 3) score += 1
        if (t.count { it == '?' } >= 3) score += 1
        if (wantsShort.containsMatchIn(t)) score -= 2
        return score >= 2
    }
}
