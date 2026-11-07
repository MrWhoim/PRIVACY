package com.privacy.app.ai

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Who is creating a note through a tool: "Conversation" (a chat the user asked to save from) or "Automation" (an unattended run). */
class ToolSource(val tag: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ToolSource>
}

/** The Markdown the app understands in notes. One source of truth: the AI's instructions and the About page both read it. */
object NoteRules {
    /** Tags the app puts in front of a note's title when the note was NOT typed by the user. */
    val TAGS = listOf(
        "Conversation" to "saved by the assistant from a chat, because you asked it to",
        "Automation" to "written by one of your automations when it ran",
        "Routine" to "written by a daily routine",
        "via Telegram" to "a message a friend sent you in Telegram while the app had no connection",
        "Morning Briefing" to "your daily AI briefing: yesterday, today's plan and leftovers"
    )

    /** Human-readable rules (About page). */
    val GUIDE = listOf(
        "# Title" to "Heading. Use ## and ### for smaller headings.",
        "**bold**  and  *italic*" to "Emphasis. `code` gives a monospace snippet.",
        "- item" to "Bullet list (also * item). Indent two spaces for a sub-item.",
        "1. item" to "Numbered list.",
        "- [ ] task   /   - [x] done" to "Checklist with an empty or ticked box.",
        "> quote" to "Quote or highlighted remark.",
        "---" to "A divider line.",
        "| a | b |  then  |---|---|" to "Table: a header row, a divider row of dashes, then one row per line (wide tables scroll sideways).",
        "```  …  ```" to "Code block (put ``` on its own line before and after)."
    )

    /** Instructions for the AI when it writes a note. */
    const val FOR_AI = "- NOTE FORMAT: write note bodies in simple Markdown that this app can show: `## Heading`, `- bullet`, `1. numbered`, `- [ ] checklist`, `**bold**`, `*italic*`, `> quote`, `---` divider, and tables (`| a | b |` then `|---|---|` then rows). " +
        "Short lines, one idea per bullet, a clear first-line title. Use a table only for real tabular data. No HTML, no images. Never put your own tag in the title – the app adds [Conversation] / [Automation] itself."

    /** "[Tag] Title" → Tag to Title, or null if the title has no tag. */
    fun splitTag(title: String): Pair<String, String>? {
        val m = Regex("^\\[([A-Za-z][A-Za-z ]{2,22}[A-Za-z])]\\s*(.*)$").find(title.trim()) ?: return null
        return m.groupValues[1] to m.groupValues[2].ifBlank { "Untitled" }
    }

    fun tagged(tag: String, title: String): String = "[$tag] ${title.trim().removePrefix("[$tag]").trim()}"
}
